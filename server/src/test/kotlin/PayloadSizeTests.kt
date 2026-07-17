import com.latenighthack.ktbuf.ProtobufInputStream
import com.latenighthack.ktbuf.ProtobufOutputStream
import com.latenighthack.ktbuf.ProtobufReader
import com.latenighthack.ktbuf.ProtobufWriter
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.rpc.HttpRpcClient
import com.latenighthack.ktbuf.server.serveUnary
import com.latenighthack.ktbuf.test.server.runTestWithServer
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class EchoServer {
    fun echo(context: GrpcRequestContext, request: ByteArray): ByteArray = request
}

fun Application.attachEchoService() {
    val service = EchoServer()

    routing {
        val method = ServerMethodDescriptor<Any, Any, EchoServer>(
            "Echo",
            { reader: ProtobufReader ->
                reader.nextField()
                reader.readBytes()
            },
            { writer: ProtobufWriter, payload: Any ->
                writer.encode(payload as ByteArray, 1)
            },
            false,
            false,
            handler = ServerMethod.Unary { context, request -> echo(context, request as ByteArray) }
        )

        serveUnary(
            service,
            ServerDescriptor(
                "com.latenighthack",
                "Echo",
                listOf(method)
            ),
            method
        )
    }
}

private val echoMethod = RpcMethodSpecifier("com.latenighthack", "Echo", "Echo")

private fun encodePayload(payload: ByteArray): ByteArray =
    ProtobufOutputStream().also { stream ->
        stream.write { writer -> writer.encode(payload, 1) }
    }.toByteArray()

private fun decodePayload(bytes: ByteArray): ByteArray =
    ProtobufInputStream().let { stream ->
        stream.addBytes(bytes)
        stream.read { reader ->
            reader.nextField()
            reader.readBytes()
        }
    }

class PayloadSizeTests {
    // Sweep payload sizes on either side of the 8192-byte boundary (ktor CIO's default
    // read-buffer size) to isolate whether the stall is size-dependent in the raw
    // ktbuf unary path, with no other application logic in play.
    @Test
    fun payloadSizeSweepReturnsExactBytes() = runTestWithServer(Application::attachEchoService) { server, _ ->
        val client = HttpRpcClient(server.serverUrl)
        val sizes = listOf(
            0, 1, 1024, 4096,
            7000, 7500, 7900, 7999,
            8000, 8001, 8100, 8191, 8192, 8193, 8200, 8300,
            9000, 12000, 16384, 65536, 131072
        )

        for (size in sizes) {
            val payload = Random(size).nextBytes(size)

            val response = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(10_000) {
                    client.unaryCall(echoMethod, emptyMap(), encodePayload(payload))
                }
            }

            val decoded = decodePayload(response.data)

            assertContentEquals(payload, decoded, "payload size $size did not round-trip")
        }
    }

    // Fire many large-payload unary calls concurrently to probe for a fan-out wedge:
    // a server-side suspend that never resumes, leaving the client to time out and
    // retry into the same stuck state.
    @Test
    fun concurrentLargePayloadCallsAllComplete() = runTestWithServer(Application::attachEchoService) { server, _ ->
        val client = HttpRpcClient(server.serverUrl)
        val concurrency = 20
        val size = 65536

        val calls = (0 until concurrency).map { index ->
            async {
                val payload = Random(size + index).nextBytes(size)

                val response = withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(15_000) {
                        client.unaryCall(echoMethod, emptyMap(), encodePayload(payload))
                    }
                }

                assertContentEquals(payload, decodePayload(response.data), "call $index did not round-trip")
            }
        }

        calls.forEach { it.await() }
    }
}
