package com.latenighthack.ktbuf.rpc
import com.latenighthack.ktbuf.net.*
import kotlinx.coroutines.*
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.*
class HttpOwnershipRegressionTests {
    private fun headers(socket: java.net.Socket) {
        var suffix = ""
        while (!suffix.endsWith("\r\n\r\n")) { val byte = socket.getInputStream().read(); check(byte >= 0); suffix = (suffix + byte.toChar()).takeLast(4) }
    }
    @Test fun bareAndFullHttpUrlsReachTheSameRealEndpoint() = runBlocking {
        for (full in listOf(false, true)) {
            val listener = ServerSocket(0)
            val worker = thread(isDaemon = true) {
                listener.accept().use { socket -> headers(socket); socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx".toByteArray()) }
            }
            val host = "127.0.0.1:${listener.localPort}"
            val client = HttpRpcClient(if (full) "http://$host" else host)
            try { assertContentEquals(byteArrayOf('x'.code.toByte()), withTimeout(2_000) { client.unaryCall(RpcMethodSpecifier("p", "S", "M"), emptyMap(), byteArrayOf()).data }) }
            finally { client.closeAndJoin(); listener.close(); worker.join(2_000) }
        }
    }
    @Test fun closeAndJoinCancelsHeldResponseAndClosesItsSocket() = runBlocking {
        val listener = ServerSocket(0); val entered = CompletableDeferred<Unit>(); val disconnected = CompletableDeferred<Unit>()
        val worker = thread(isDaemon = true) {
            runCatching { listener.accept().use { socket -> headers(socket); entered.complete(Unit); assertEquals(-1, socket.getInputStream().read()); disconnected.complete(Unit) } }.exceptionOrNull()?.let { disconnected.completeExceptionally(it) }
        }
        val client = HttpRpcClient("http://127.0.0.1:${listener.localPort}")
        try {
            val request = async { client.unaryCall(RpcMethodSpecifier("p", "S", "M"), emptyMap(), byteArrayOf()) }
            withTimeout(2_000) { entered.await(); client.closeAndJoin(); disconnected.await() }
            assertTrue(request.isCancelled)
            assertFailsWith<IllegalStateException> { client.unaryCall(RpcMethodSpecifier("p", "S", "M"), emptyMap(), byteArrayOf()) }
        } finally { client.closeAndJoin(); listener.close(); worker.join(2_000) }
        Unit
    }
}
