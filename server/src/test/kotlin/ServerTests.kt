import com.latenighthack.ktbuf.ProtobufReader
import com.latenighthack.ktbuf.ProtobufWriter
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.rpc.HttpRpcClient
import com.latenighthack.ktbuf.server.serveStreamingInOut
import com.latenighthack.ktbuf.test.server.runTestWithServer
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test

fun Application.attachTestServices() {
    val service = DummyServer()

    routing {
        val method = ServerMethodDescriptor<Any, Any, DummyServer>(
            "Placeholder",
            { _: ProtobufReader -> Unit},
            { _: ProtobufWriter, _: Any -> },
            true,
            true,
            handler = ServerMethod.ClientServerStreaming(DummyServer::placeholder)
        )
        serveStreamingInOut(
            service,
            ServerDescriptor(
                "com.latenighthack",
                "Dummy",
                listOf(method)
            ),
            method
        )
    }
}

class DummyServer {
    fun placeholder(context: GrpcRequestContext, request: Flow<Any>): Flow<Any> {
        return flow {
            request.collect {
                println("Hello")
                emit(Unit)
            }
        }
    }
}

class ServerTests {
    @Test
    fun testServerRuns() = runTestWithServer(Application::attachTestServices) { server ->
        val client = HttpRpcClient(server.serverUrl)
        client.serverStreamingCall(
            RpcMethodSpecifier("com.latenighthack", "Dummy", "Placeholder"),
            {
                for (i in 1..5) {
                    send(ByteArray(0))
                    receive()
                    println("World")
                }
            },
        )
    }
}
