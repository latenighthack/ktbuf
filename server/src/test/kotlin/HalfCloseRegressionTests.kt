import com.latenighthack.ktbuf.conformance.v1.*
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.rpc.HttpRpcClient
import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktbuf.test.server.TestServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class HalfCloseRegressionTests {
    @Test fun finiteAndEmptyRequestStreamsCompleteWithoutLosingDelayedResponses() = runBlocking<Unit> {
        val server = TestServer()
        server.start { routing { serveAll(object : ConformanceServer by ConformanceTestServer() {
            override fun bidi(context: GrpcRequestContext, request: Flow<AllTypes>): Flow<StreamControlEvent<AllTypes>> = flow {
                val all = request.toList(); delay(20)
                all.forEach { emit(StreamControlEvent.Message(it)) }
            }
        }, ConformanceServer.Descriptor) } }
        try {
            val service = ConformanceServiceRpc(HttpRpcClient(server.serverUrl))
            for (all in listOf(listOf(AllTypes(), AllTypes(fInt32=2), AllTypes(fInt32=3)), emptyList())) {
                assertEquals(all, withTimeout(2000) { service.bidi(all.asFlow()).toList() })
            }
        } finally { server.stop() }
    }
    @Test fun anOlderPeerFailsHalfCloseExplicitly() = runBlocking<Unit> {
        val server = TestServer()
        server.start { routing { webSocket("/api/p.S/M") { for (frame in incoming) { if (frame is Frame.Binary) send(frame.data) } } } }
        try {
            val failure = assertFailsWith<RpcResponseException> { withTimeout(2000) {
                HttpRpcClient(server.serverUrl).serverStreamingCall(RpcMethodSpecifier("p","S","M"), { send(byteArrayOf()); closeOutbound() }, {})
            } }
            assertEquals(Codes.UNIMPLEMENTED, failure.code)
        } finally { server.stop() }
    }
}
