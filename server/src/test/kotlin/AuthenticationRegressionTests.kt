import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.rpc.HttpRpcClient
import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktbuf.test.server.TestServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import java.net.URI
import java.net.http.WebSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AuthenticationRegressionTests {
    @Test fun everyRouteAuthenticatesOnceBeforeParsingAnyRequest() = runBlocking<Unit> {
        val checks=AtomicInteger()
        val parses=AtomicInteger()
        val handlers:List<ServerMethod<Unit,Unit,Any>> = listOf(
            ServerMethod.Unary { _, _ -> Unit },
            ServerMethod.ServerStreaming { _, _ -> emptyFlow() },
            ServerMethod.ClientServerStreaming { _, _ -> emptyFlow() },
            ServerMethod.ClientStreaming { _, _ -> Unit }
        )
        val methods=handlers.mapIndexed { index, handler ->
            ServerMethodDescriptor<Unit,Unit,Any>("Route$index", { parses.incrementAndGet(); it.readInt32(); Unit }, { _, _ -> Unit },index>=2,index==1||index==2,handler,0)
        }
        val descriptor=ServerDescriptor("auth","Test",methods)
        val server=TestServer()
        server.start { routing { serveAll(Any(),descriptor) {
            checks.incrementAndGet()
            throw RpcResponseException("","AUTH",Codes.UNAUTHENTICATED,"denied")
        } } }
        try {
            val error=runCatching { withTimeout(3000) {
                HttpRpcClient(server.serverUrl).unaryCall(RpcMethodSpecifier("auth","Test","Route0"),emptyMap(),byteArrayOf(-128))
            } }.exceptionOrNull()
            assertTrue(error is RpcResponseException)
            assertEquals(1,checks.get())
            for (index in 1..3) {
                val socket=java.net.http.HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(
                    URI("ws://127.0.0.1:${server.port}/api/auth.Test/Route$index"), object:WebSocket.Listener {}
                ).get()
                try { withTimeout(3000) { while(checks.get()!=index+1) delay(1) } }
                finally { socket.abort() }
            }
            assertEquals(4,checks.get())
            assertEquals(0,parses.get(),"unauthenticated input must never reach protobuf decoding")
        } finally { server.stop() }
    }

    @Test fun authorizedContextChangesReachTheHandler() = runBlocking<Unit> {
        val checks=AtomicInteger()
        val calls=AtomicInteger()
        val descriptor=ServerDescriptor("auth","Allowed",listOf(ServerMethodDescriptor<Unit,Unit,Any>(
            "Unary", { Unit }, { _, _ -> Unit }, false,false,
            ServerMethod.Unary { context, _ ->
                assertEquals("user",context.extensions["identity"])
                calls.incrementAndGet()
                Unit
            },0
        )))
        val server=TestServer()
        server.start { routing { serveAll(Any(),descriptor) {
            checks.incrementAndGet()
            it.copy(extensions=mapOf("identity" to "user"))
        } } }
        try {
            withTimeout(3000) { HttpRpcClient(server.serverUrl).unaryCall(RpcMethodSpecifier("auth","Allowed","Unary"),emptyMap(),byteArrayOf()) }
            assertEquals(1,checks.get())
            assertEquals(1,calls.get())
        } finally { server.stop() }
    }
}
