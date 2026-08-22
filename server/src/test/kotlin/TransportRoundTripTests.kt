import com.latenighthack.ktbuf.conformance.v1.AllTypes
import com.latenighthack.ktbuf.conformance.v1.ConformanceServer
import com.latenighthack.ktbuf.conformance.v1.ConformanceServiceRpc
import com.latenighthack.ktbuf.net.GrpcRequestContext
import com.latenighthack.ktbuf.net.RpcResponseException
import com.latenighthack.ktbuf.net.StreamControlEvent
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.rpc.HttpRpcClient
import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktbuf.test.server.runTestWithServer
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// End-to-end transport coverage: a real Ktor server, a real HttpRpcClient, and the
// generated typed service on both sides. The common-code tests in :library and
// :conformance pin marshalling; these pin what actually crosses HTTP and WebSocket.

/**
 * Real network I/O has to run on a real dispatcher: runTest drives virtual time, so a
 * withTimeout on the test scheduler expires instantly instead of waiting for the server.
 */
private suspend fun <T> realTime(timeoutMillis: Long = 15_000, block: suspend () -> T): T =
    withContext(Dispatchers.Default) {
        withTimeout(timeoutMillis) { block() }
    }

/** Server behaviour is switched per-test by the failWith/frameCount knobs. */
class ConformanceTestServer(
    private val failWith: RpcResponseException? = null,
    private val frameCount: Int = 3
) : ConformanceServer {
    override suspend fun unary(context: GrpcRequestContext, request: AllTypes): AllTypes {
        failWith?.let { throw it }

        // fInt32 doubles as a code selector so one server can cover the whole
        // code-mapping table without nesting test servers.
        val requested = Codes.entries.firstOrNull { it.value == request.fInt32 && it != Codes.OK }

        if (requested != null && request.fBool) {
            throw RpcResponseException("", "POST", requested, "boom-${requested.name}")
        }

        return request.copy(fInt32 = request.fInt32 * 2)
    }

    override fun serverStream(
        context: GrpcRequestContext,
        request: AllTypes
    ): Flow<StreamControlEvent<AllTypes>> = flow {
        for (index in 1..frameCount) {
            emit(AllTypes(fInt32 = request.fInt32 + index))
        }
    }.map { StreamControlEvent.Message(it) }

    override fun bidi(
        context: GrpcRequestContext,
        request: Flow<AllTypes>
    ): Flow<StreamControlEvent<AllTypes>> = request
        .map { AllTypes(fInt32 = it.fInt32 * 10, fString = it.fString) }
        .map { StreamControlEvent.Message(it) }
}

private fun Application.attachConformance(server: ConformanceServer) {
    routing {
        serveAll(server, ConformanceServer.Descriptor)
    }
}

class TransportRoundTripTests {
    @Test
    fun unaryCallRoundTripsOverHttp() = runTestWithServer({
        attachConformance(ConformanceTestServer())
    }) { server, _ ->
        val service = ConformanceServiceRpc(HttpRpcClient(server.serverUrl))

        val response = realTime(10_000) { service.unary(AllTypes(fInt32 = 21)) }

        assertEquals(42, response.fInt32, "the server doubled the value and it came back")
    }

    @Test
    fun unaryCallPreservesEveryFieldType() = runTestWithServer({
        attachConformance(object : ConformanceServer by ConformanceTestServer() {
            override suspend fun unary(context: GrpcRequestContext, request: AllTypes) = request
        })
    }) { server, _ ->
        // A full-fidelity echo: whatever survives HTTP framing must be identical, so
        // this catches truncation or charset damage in the transport rather than the codec.
        val service = ConformanceServiceRpc(HttpRpcClient(server.serverUrl))
        val sent = AllTypes(
            fInt32 = -1, fInt64 = Long.MIN_VALUE, fUint32 = UInt.MAX_VALUE, fUint64 = ULong.MAX_VALUE,
            fSint32 = -5, fSint64 = -6L, fFixed32 = 7u, fFixed64 = 8uL,
            fSfixed32 = -9, fSfixed64 = -10L, fFloat = 1.5f, fDouble = -2.5,
            fBool = true, fString = "héllo 世界 🎉", fBytes = byteArrayOf(0x00, 0x01, 0xFF.toByte()),
            fEnum = AllTypes.Kind.B,
            fInner = AllTypes.Inner(str = "i", anInt = 11),
            rInt32 = listOf(1, 2, 3), rString = listOf("a", "b"),
            rInner = listOf(AllTypes.Inner(anInt = 15)),
            fHighField = 16
        )

        val received = realTime(10_000) { service.unary(sent) }

        assertEquals(sent, received, "every field survived the round trip")
    }

    @Test
    fun unaryCallCarriesLargePayloads() = runTestWithServer({
        attachConformance(object : ConformanceServer by ConformanceTestServer() {
            override suspend fun unary(context: GrpcRequestContext, request: AllTypes) = request
        })
    }) { server, _ ->
        val service = ConformanceServiceRpc(HttpRpcClient(server.serverUrl))
        val sent = AllTypes(fString = "x".repeat(200_000))

        val received = realTime(20_000) { service.unary(sent) }

        assertEquals(sent, received, "a 200KB message survived the round trip")
    }

    @Test
    fun unaryErrorsCarryTheGrpcCodeAndMessage() = runTestWithServer({
        attachConformance(
            ConformanceTestServer(
                failWith = RpcResponseException("", "POST", Codes.NOT_FOUND, "no such widget")
            )
        )
    }) { server, _ ->
        // The server maps the code to an HTTP status and puts the message in the body;
        // the client has to reconstruct both. This is the contract the recent
        // arraybuffer error-body fixes on JS/Apple exist to satisfy.
        val service = ConformanceServiceRpc(HttpRpcClient(server.serverUrl))

        try {
            realTime(10_000) { service.unary(AllTypes(fInt32 = 1)) }

            fail("expected the server error to surface as an exception")
        } catch (ex: RpcResponseException) {
            assertEquals(Codes.NOT_FOUND, ex.code, "gRPC code recovered from the HTTP status")
            assertTrue(
                ex.errorMessage.contains("no such widget"),
                "error body reached the client, got: '${ex.errorMessage}'"
            )
        }
    }

    @Test
    fun unaryErrorsRoundTripForEveryUnambiguousCode() = runTestWithServer({
        attachConformance(ConformanceTestServer())
    }) { server, _ ->
        // Each code owning a distinct HTTP status must survive the mapping intact. The
        // server picks its failure code from the request, so one server covers them all.
        val service = ConformanceServiceRpc(HttpRpcClient(server.serverUrl))
        val codes = listOf(
            Codes.NOT_FOUND, Codes.PERMISSION_DENIED, Codes.UNAUTHENTICATED,
            Codes.RESOURCE_EXHAUSTED, Codes.UNIMPLEMENTED, Codes.UNAVAILABLE,
            Codes.DEADLINE_EXCEEDED, Codes.CANCELLED
        )

        for (code in codes) {
            try {
                realTime(10_000) { service.unary(AllTypes(fInt32 = code.value, fBool = true)) }

                fail("expected $code to surface as an exception")
            } catch (ex: RpcResponseException) {
                assertEquals(code, ex.code, "$code survived the HTTP status round trip")
                assertTrue(
                    ex.errorMessage.contains("boom-${code.name}"),
                    "error body for $code reached the client, got: '${ex.errorMessage}'"
                )
            }
        }
    }

    @Test
    fun serverStreamingDeliversEveryFrameOverWebSocket() = runTestWithServer({
        attachConformance(ConformanceTestServer(frameCount = 5))
    }) { server, _ ->
        val service = ConformanceServiceRpc(HttpRpcClient(server.serverUrl))

        val received = realTime(15_000) {
            service.serverStream(AllTypes(fInt32 = 100)).take(5).toList()
        }

        assertEquals(
            listOf(101, 102, 103, 104, 105),
            received.map { it.fInt32 },
            "every streamed frame arrived, in order"
        )
    }

    @Test
    fun serverStreamingDeliversASingleFrame() = runTestWithServer({
        attachConformance(ConformanceTestServer(frameCount = 1))
    }) { server, _ ->
        val service = ConformanceServiceRpc(HttpRpcClient(server.serverUrl))

        val received = realTime(15_000) {
            service.serverStream(AllTypes(fInt32 = 7)).take(1).toList()
        }

        assertEquals(listOf(8), received.map { it.fInt32 })
    }

    @Test
    fun bidiStreamingRoundTripsInOrderOverWebSocket() = runTestWithServer({
        attachConformance(ConformanceTestServer())
    }) { server, _ ->
        val service = ConformanceServiceRpc(HttpRpcClient(server.serverUrl))

        val requests = flow {
            emit(AllTypes(fInt32 = 1))
            emit(AllTypes(fInt32 = 2))
            emit(AllTypes(fInt32 = 3))

            // Hold the request side open; clientStreamServerStream stops reading once
            // the request flow completes, so a completing flow would race the replies.
            awaitCancellation()
        }

        val received = realTime(15_000) { service.bidi(requests).take(3).toList() }

        assertEquals(listOf(10, 20, 30), received.map { it.fInt32 }, "replies arrive in request order")
    }

    @Test
    fun bidiStreamingSurvivesAnImmediateSecondRequest() = runTestWithServer({
        attachConformance(ConformanceTestServer())
    }) { server, _ ->
        // Regression guard for the lossless ordered request pipeline: the opening
        // message followed straight away by a second one. The previous
        // shareIn(replay = 1) implementation dropped the open in this exact shape and
        // the stream never established.
        val service = ConformanceServiceRpc(HttpRpcClient(server.serverUrl))

        val requests = flow {
            emit(AllTypes(fInt32 = 1))
            emit(AllTypes(fInt32 = 2))

            awaitCancellation()
        }

        val received = realTime(15_000) { service.bidi(requests).take(2).toList() }

        assertEquals(listOf(10, 20), received.map { it.fInt32 }, "neither request was lost")
    }

    @Test
    fun bidiStreamingCarriesLargeFrames() = runTestWithServer({
        attachConformance(ConformanceTestServer())
    }) { server, _ ->
        // Frames well past the 8192-byte buffer boundary, over the WebSocket path.
        val service = ConformanceServiceRpc(HttpRpcClient(server.serverUrl))
        val payload = "y".repeat(50_000)

        val requests = flow {
            emit(AllTypes(fInt32 = 1, fString = payload))

            awaitCancellation()
        }

        val received = realTime(20_000) { service.bidi(requests).take(1).toList() }

        assertEquals(1, received.size)
        assertEquals(payload, received.single().fString, "the large frame arrived intact")
        assertEquals(10, received.single().fInt32)
    }
}
