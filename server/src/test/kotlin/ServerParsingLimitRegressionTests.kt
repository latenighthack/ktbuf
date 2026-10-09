import com.latenighthack.ktbuf.ProtobufLimits
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.rpc.HttpRpcClient
import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktbuf.test.server.TestServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
class ServerParsingLimitRegressionTests {
    @Test fun oversizedBodiesAreRejectedBeforeDecoding() = runBlocking<Unit> {
        val parses=AtomicInteger()
        val method=ServerMethodDescriptor<Unit,Unit,Any>("Unary", { parses.incrementAndGet(); Unit }, { _, _ -> Unit },false,false,ServerMethod.Unary { _, _ -> Unit },0)
        val server=TestServer()
        server.start { routing { serveAll(Any(),ServerDescriptor("limits","Test",listOf(method)),ProtobufLimits(maxMessageBytes=8)) } }
        try {
            val error=runCatching { withTimeout(3000) { HttpRpcClient(server.serverUrl).unaryCall(RpcMethodSpecifier("limits","Test","Unary"),emptyMap(),ByteArray(100000)) } }.exceptionOrNull()
            assertTrue(error is RpcResponseException)
            assertEquals(Codes.OUT_OF_RANGE,error.code)
            assertFalse(error.retriable())
            assertEquals(0,parses.get())
        } finally { server.stop() }
    }
}
