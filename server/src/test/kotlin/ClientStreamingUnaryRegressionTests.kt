import com.latenighthack.ktbuf.conformance.v1.*
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.rpc.HttpRpcClient
import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktbuf.test.server.TestServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ClientStreamingUnaryRegressionTests {
    @Test fun generatedClientCollectsFiniteRequestsAndPropagatesHandlerFailures() = runBlocking<Unit> {
        val server = TestServer()
        server.start { routing { serveAll(object : ConformanceServer by ConformanceTestServer() {
            override suspend fun clientStream(context: GrpcRequestContext, request: Flow<AllTypes>): AllTypes {
                val all = request.toList()
                if (all.any { it.fBool }) throw RpcResponseException("", "WS", com.latenighthack.ktbuf.proto.Codes.PERMISSION_DENIED, "denied")
                return AllTypes(fInt32 = all.sumOf { it.fInt32 })
            }
        }, ConformanceServer.Descriptor) } }
        try {
            val client = ConformanceServiceRpc(HttpRpcClient(server.serverUrl))
            assertEquals(AllTypes(fInt32 = 6), withTimeout(2000) { client.clientStream(listOf(1, 2, 3).map { AllTypes(fInt32 = it) }.asFlow()) })
            assertEquals(AllTypes(), withTimeout(2000) { client.clientStream(emptyFlow()) })
            val failure = assertFailsWith<RpcResponseException> { withTimeout(2000) { client.clientStream(flowOf(AllTypes(fBool = true))) } }
            assertEquals(com.latenighthack.ktbuf.proto.Codes.PERMISSION_DENIED, failure.code)
        } finally { server.stop() }
    }
    @Test fun correctHandlerCollectsFiniteRequestsAndEmitsExactlyOneResponse() = runBlocking<Unit> {
        val checks=AtomicInteger()
        val descriptor=ServerDescriptor("upload","Test",listOf(ServerMethodDescriptor<AllTypes,AllTypes,Any>(
            "Collect", AllTypes.Companion::readFrom, AllTypes::writeTo, true, false,
            ServerMethod.ClientStreaming { context, requests ->
                assertEquals("authorized",context.extensions["user"])
                AllTypes(fInt32=requests.toList().sumOf {it.fInt32})
            }
        )))
        val server=TestServer()
        server.start { routing { serveAll(Any(),descriptor) { checks.incrementAndGet(); it.copy(extensions=mapOf("user" to "authorized")) } } }
        try {
            for (values in listOf(listOf(1,2,3),emptyList())) {
                val responses=mutableListOf<AllTypes>()
                withTimeout(2000) { HttpRpcClient(server.serverUrl).serverStreamingCall(RpcMethodSpecifier("upload","Test","Collect"), {
                    values.forEach { send(AllTypes(fInt32=it).toByteArray()) }; closeOutbound()
                    try { while(true) responses.add(AllTypes.fromByteArray(receive())) } catch (_:ClosedReceiveChannelException) { }
                }, {}) }
                assertEquals(listOf(AllTypes(fInt32=values.sum())),responses)
            }
            assertEquals(2,checks.get())
        } finally { server.stop() }
    }
}
