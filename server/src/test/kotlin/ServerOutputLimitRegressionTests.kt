import com.latenighthack.ktbuf.ProtobufLimits
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.rpc.HttpRpcClient
import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktbuf.test.server.TestServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class ServerOutputLimitRegressionTests {
    @Test fun oversizedResponsesArePermanentForUnaryAndBothStreamingKinds() = runBlocking<Unit> {
        val methods = listOf(
            ServerMethodDescriptor<Unit,Unit,Any>("Unary", { Unit }, { writer, _ -> writer.encodeRaw(ByteArray(9)) },false,false,ServerMethod.Unary { _, _ -> Unit },0),
            ServerMethodDescriptor<Unit,Unit,Any>("Streaming", { Unit }, { writer, _ -> writer.encodeRaw(ByteArray(9)) },false,true,ServerMethod.ServerStreaming { _, _ -> flowOf(StreamControlEvent.Message(Unit)) },0),
            ServerMethodDescriptor<Unit,Unit,Any>("Bidi", { Unit }, { writer, _ -> writer.encodeRaw(ByteArray(9)) },true,true,ServerMethod.ClientServerStreaming { _, requests -> requests.map { StreamControlEvent.Message(Unit) } },0),
        )
        val server = TestServer()
        server.start { routing { serveAll(Any(),ServerDescriptor("output","Test",methods),ProtobufLimits(maxMessageBytes=8)) } }
        val client = HttpRpcClient(server.serverUrl)
        try {
            for (name in listOf("Unary", "Streaming", "Bidi")) {
                val method = RpcMethodSpecifier("output","Test",name)
                val failure = assertFailsWith<RpcResponseException> { withTimeout(3_000) {
                    if (name == "Unary") client.unaryCall(method,emptyMap(),byteArrayOf())
                    else client.serverStreamingCall(method, { send(byteArrayOf()); receive() }, {})
                } }
                assertEquals(Codes.OUT_OF_RANGE,failure.code,name)
                assertFalse(failure.retriable(),name)
            }
        } finally { client.closeAndJoin(); server.stop() }
    }
}
