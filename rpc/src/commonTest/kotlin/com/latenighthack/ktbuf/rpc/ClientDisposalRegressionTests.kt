package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.Codes
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class ClientDisposalRegressionTests {
    @Test fun disposedClientsRejectCallsBeforeOpeningAnyTransport() = runTest {
        val client = HttpRpcClient("invalid URL")
        client.close()
        client.closeAndJoin()
        val method = RpcMethodSpecifier("p", "S", "M")
        assertEquals(Codes.CANCELLED, assertFailsWith<RpcResponseException> { client.unaryCall(method, emptyMap(), byteArrayOf()) }.code)
        var entered = false
        assertFailsWith<RpcResponseException> { client.serverStreamingCall(method, { entered = true }, { entered = true }) }
        assertFalse(entered)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun disposalWaitsForChildrenAndNonCancellableCleanup() = runTest {
        val lifecycle = RpcClientLifecycle()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cleaned = false
        val operation = launch {
            lifecycle.call {
                CoroutineScope(currentCoroutineContext()).launch {
                    started.complete(Unit)
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { release.await(); cleaned = true } }
                }
            }
        }
        started.await()
        val closing = launch { lifecycle.closeAndJoin() }
        runCurrent()
        assertFalse(closing.isCompleted)
        assertFalse(cleaned)
        release.complete(Unit)
        closing.join()
        assertTrue(cleaned && operation.isCompleted)
        lifecycle.closeAndJoin()
    }

    @Test fun joiningWithinAnOwnedCallRejectsEvenInNonCancellableContext() = runTest {
        val lifecycle = RpcClientLifecycle()
        lifecycle.call {
            withContext(NonCancellable) { assertFailsWith<IllegalStateException> { lifecycle.closeAndJoin() } }
        }
        lifecycle.closeAndJoin()
    }
}
