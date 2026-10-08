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
import kotlinx.coroutines.flow.toList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class StreamLifecycleRegressionTests {
    @Test fun normalRemoteCompletionDrainsEveryQueuedResponse() = runBlocking<Unit> {
        val server=TestServer(); server.start { routing { serveAll(ConformanceTestServer(frameCount=32),ConformanceServer.Descriptor) } }
        try {
            val ready=AtomicInteger()
            val frames=withTimeout(2000) { ConformanceServiceRpc(HttpRpcClient(server.serverUrl)).serverStream(AllTypes(), {ready.incrementAndGet()}).toList() }
            assertEquals((1..32).toList(),frames.map {it.fInt32})
            assertEquals(1,ready.get())
        } finally { server.stop() }
    }
    @Test fun cancellingCollectionClosesTheActualRemoteSocket() = runBlocking<Unit> {
        val connected=CompletableDeferred<Unit>(); val closed=CompletableDeferred<Unit>()
        val server=TestServer(); server.start { routing { webSocket("/api/lifecycle.Test/Hang") {
            connected.complete(Unit)
            try { incoming.receive() } finally { closed.complete(Unit) }
        } } }
        try {
            val call=launch { HttpRpcClient(server.serverUrl).serverStreamingCall(RpcMethodSpecifier("lifecycle","Test","Hang"),{ receive() },{}) }
            withTimeout(2000) { connected.await() }
            call.cancelAndJoin()
            assertTrue(withTimeoutOrNull(700) { closed.await(); true } ?: false,"cancelled collection left its socket open")
        } finally { server.stop() }
    }
    @Test fun failedHandshakeFailsWaitingSendsWithoutWaitingForTimeout() = runBlocking<Unit> {
        val server=TestServer(); server.start {}
        try {
            val failure=runCatching { withTimeout(2000) { HttpRpcClient(server.serverUrl).serverStreamingCall(RpcMethodSpecifier("lifecycle","Test","Missing"),{ send(byteArrayOf()) },{}) } }.exceptionOrNull()
            assertTrue(failure is RpcResponseException,"handshake failure stranded the sender: $failure")
            assertEquals(Codes.NOT_FOUND,failure.code)
        } finally { server.stop() }
    }

    @Test fun responseQueueOverflowFailsExplicitly() = runBlocking<Unit> {
        val server=TestServer(); server.start { routing { webSocket("/api/lifecycle.Test/Flood") {
            repeat(1000) { send(byteArrayOf(8,1)) }
            close(CloseReason(CloseReason.Codes.NORMAL,""))
        } } }
        try {
            val failure=runCatching { withTimeout(3000) {
                HttpRpcClient(server.serverUrl).serverStreamingCall(RpcMethodSpecifier("lifecycle","Test","Flood"), {
                    delay(250)
                    while(true) receive()
                },{})
            } }.exceptionOrNull()
            assertTrue(failure is RpcResponseException,"overflow must fail rather than drop responses: $failure")
            assertEquals(Codes.RESOURCE_EXHAUSTED,failure.code)
        } finally { server.stop() }
    }
}
