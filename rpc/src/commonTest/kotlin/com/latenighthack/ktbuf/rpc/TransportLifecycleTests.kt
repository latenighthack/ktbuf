package com.latenighthack.ktbuf.rpc
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import com.latenighthack.ktbuf.proto.Codes
class TransportLifecycleTests {
    @Test fun closeCancelsAndJoinsOwnedCallsAndRejectsNewWork() = runTest {
        val lifecycle = TransportLifecycle(); val entered = CompletableDeferred<Unit>(); var cleaned = false
        val call = async { lifecycle.call { entered.complete(Unit); try { awaitCancellation() } finally { cleaned = true } } }
        entered.await(); lifecycle.close(); lifecycle.join()
        assertTrue(cleaned); assertTrue(call.isCancelled)
        assertFailsWith<IllegalStateException> { lifecycle.call { error("must not run") } }
        lifecycle.close()
    }
    @Test fun addressesNormalizeConsistentlyAcrossPlatforms() {
        assertEquals("http://host:80", normalizedHttpRpcAddress("host:80"))
        assertEquals("http://host:80", normalizedHttpRpcAddress("http://host:80/"))
        assertEquals("https://host", normalizedHttpRpcAddress("https://host"))
        assertEquals("ws://host:80", webSocketRpcAddress("http://host:80"))
        assertEquals("wss://host", webSocketRpcAddress("https://host"))
        assertFails { normalizedHttpRpcAddress("ftp://host") }
    }
    @Test fun callbackQueueRejectsByteOverflowWithoutDroppingAcceptedFrames() = runTest {
        val buffer = RpcInboundBuffer(maxBytes = 8, capacity = 4)
        assertTrue(buffer.offer(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertFalse(buffer.offer(byteArrayOf(7, 8, 9)))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), buffer.receive())
        assertTrue(buffer.offer(byteArrayOf(7, 8, 9)))
        buffer.close()
        assertContentEquals(byteArrayOf(7, 8, 9), buffer.receive())
        assertFails { buffer.receive() }
    }
    @Test fun privateWebSocketCloseCodesPreserveRpcClassification() {
        assertNull(rpcWebSocketCloseFailure("path", 1000, "normal"))
        assertEquals(Codes.PERMISSION_DENIED, rpcWebSocketCloseFailure("path", 4007, "denied")!!.code)
        assertEquals(Codes.UNAVAILABLE, rpcWebSocketCloseFailure("path", 1001, "going away")!!.code)
    }
}
