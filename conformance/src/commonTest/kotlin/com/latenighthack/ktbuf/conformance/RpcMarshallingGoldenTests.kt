package com.latenighthack.ktbuf.conformance

import com.latenighthack.ktbuf.conformance.v1.AllTypes
import com.latenighthack.ktbuf.conformance.v1.ConformanceService
import com.latenighthack.ktbuf.conformance.v1.ConformanceServiceRpc
import com.latenighthack.ktbuf.conformance.v1.fromByteArray
import com.latenighthack.ktbuf.conformance.v1.toByteArray
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Covers the marshalling layer between a generated service client and the transport:
// what bytes go out, what comes back, and whether streaming preserves order. Runs on
// every target because both the fake transport and GrpcService are common code.
class RpcMarshallingGoldenTests {
    private val request = AllTypes(fInt32 = 42)
    private val response = AllTypes(fString = "hello")

    @Test
    fun unaryCallPutsGoldenRequestBytesOnTheWire() = runTest {
        val transport = FakeRpcClient(unaryResponse = ConformanceGoldens.StringAscii.hexToBytes())
        val service = ConformanceServiceRpc(transport)

        val result = service.unary(request)

        assertEquals(1, transport.unaryCalls.size, "exactly one unary call")
        assertEquals(
            ConformanceGoldens.Int32Only,
            transport.unaryCalls.single().request.toHex(),
            "request serialized to the canonical bytes"
        )
        assertEquals(response, result, "response deserialized from the canonical bytes")
    }

    @Test
    fun unaryCallTargetsTheDeclaredMethod() = runTest {
        val transport = FakeRpcClient()
        val service = ConformanceServiceRpc(transport)

        service.unary(request)

        val method = transport.unaryCalls.single().method

        assertEquals("conformance.v1", method.packageName)
        assertEquals("Conformance", method.serviceName)
        assertEquals("Unary", method.methodName)
    }

    @Test
    fun unaryCallDecodesAnEmptyResponseAsDefaults() = runTest {
        // A zero-byte body is a valid response meaning "all fields default", not an error.
        val transport = FakeRpcClient(unaryResponse = ByteArray(0))
        val service = ConformanceServiceRpc(transport)

        assertEquals(AllTypes(), service.unary(request), "empty body decodes to defaults")
    }

    @Test
    fun serverStreamingDeliversEveryFrameInOrder() = runTest {
        val frames = listOf(
            AllTypes(fInt32 = 1),
            AllTypes(fInt32 = 2),
            AllTypes(fInt32 = 3)
        )
        val transport = FakeRpcClient(serverFrames = frames.map { it.toByteArray() })
        val service = ConformanceServiceRpc(transport)

        val received = service.serverStream(request).toList()

        assertEquals(frames, received, "all frames arrive in order")
        assertEquals(
            listOf(ConformanceGoldens.Int32Only),
            transport.streamedRequests.map { it.toHex() },
            "the opening request is sent once, serialized canonically"
        )
    }

    @Test
    fun serverStreamingWithNoFramesCompletesEmpty() = runTest {
        val transport = FakeRpcClient(serverFrames = emptyList())
        val service = ConformanceServiceRpc(transport)

        assertEquals(emptyList(), service.serverStream(request).toList(), "a stream may be empty")
    }

    @Test
    fun serverStreamingFiresTheReadyCallback() = runTest {
        val transport = FakeRpcClient(serverFrames = listOf(response.toByteArray()))
        var ready = false

        val service = ConformanceServiceRpc(transport)
        val received = service.serverStream(request) { ready = true }.toList()

        assertEquals(listOf(response), received)
        assertTrue(transport.readyCallbackFired, "the transport signalled readiness")
        assertTrue(ready, "the caller's readyCallback ran")
    }

    // A bidi request side that emits its messages and then stays open, the way a live
    // client holds the stream. Letting the flow complete instead would end the call:
    // clientStreamServerStream stops reading as soon as the request side finishes, so a
    // completing flow races the responses rather than testing them.
    private fun openRequestFlow(vararg messages: AllTypes) = flow {
        for (message in messages) {
            emit(message)
        }

        awaitCancellation()
    }

    @Test
    fun bidiStreamingRoundTripsEveryMessage() = runTest {
        // The echo transport decodes each request and replies with a derived message,
        // so this exercises encode -> transport -> decode for both directions at once.
        val transport = FakeRpcClient(echo = { bytes ->
            val incoming = AllTypes.fromByteArray(bytes)

            AllTypes(fInt32 = incoming.fInt32 * 10).toByteArray()
        })
        val service = ConformanceServiceRpc(transport)

        val requests = openRequestFlow(AllTypes(fInt32 = 1), AllTypes(fInt32 = 2), AllTypes(fInt32 = 3))
        val received = service.bidi(requests).take(3).toList()

        assertEquals(
            listOf(AllTypes(fInt32 = 10), AllTypes(fInt32 = 20), AllTypes(fInt32 = 30)),
            received,
            "responses arrive in request order"
        )
        assertEquals(
            listOf(AllTypes(fInt32 = 1), AllTypes(fInt32 = 2), AllTypes(fInt32 = 3)),
            transport.streamedRequests.map { AllTypes.fromByteArray(it) },
            "every request reached the transport, in order"
        )
    }

    @Test
    fun bidiStreamingPreservesAnImmediateSecondRequest() = runTest {
        // Regression guard for the lossless request pipeline: a client that follows the
        // opening message with another one straight away must not lose either. The old
        // shareIn(replay = 1) pipeline dropped the open in exactly this shape.
        val transport = FakeRpcClient(echo = { it })
        val service = ConformanceServiceRpc(transport)

        val requests = openRequestFlow(AllTypes(fInt32 = 1), AllTypes(fInt32 = 2))
        val received = service.bidi(requests).take(2).toList()

        assertEquals(2, transport.streamedRequests.size, "both requests were sent")
        assertEquals(
            listOf(AllTypes(fInt32 = 1), AllTypes(fInt32 = 2)),
            received,
            "the opening request is not swallowed"
        )
    }

    @Test
    fun bidiStreamingHandlesALargePayload() = runTest {
        // Forces the nested length back-patch and buffer growth through the full
        // marshalling path rather than just the codec.
        val big = AllTypes(fString = "x".repeat(20_000))
        val transport = FakeRpcClient(echo = { it })
        val service = ConformanceServiceRpc(transport)

        val received = service.bidi(openRequestFlow(big)).take(1).toList()

        assertEquals(listOf(big), received, "a 20KB message survives the round trip")
    }

    @Test
    fun serviceDescriptorMatchesTheProtoDefinition() {
        val descriptor = ConformanceService.Descriptor

        assertEquals("conformance.v1", descriptor.packageName)
        assertEquals("Conformance", descriptor.serviceName)

        val shapes = descriptor.methods.associate { it.methodName to (it.streamingIn to it.streamingOut) }

        assertEquals(false to false, shapes["Unary"], "Unary is unary in and out")
        assertEquals(false to true, shapes["ServerStream"], "ServerStream streams out only")
        assertEquals(true to true, shapes["Bidi"], "Bidi streams both ways")
        assertEquals(3, descriptor.methods.size, "no client-streaming method -- see conformance.proto")
    }
}
