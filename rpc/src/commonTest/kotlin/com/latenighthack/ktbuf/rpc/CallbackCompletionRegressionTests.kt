package com.latenighthack.ktbuf.rpc
import com.latenighthack.ktbuf.net.completeCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
class CallbackCompletionRegressionTests {
    @Test fun lateAndDuplicateCallbacksCannotResumeCancelledOrCompletedCallers() = runTest {
        lateinit var callback:CancellableContinuation<Int>
        val cancelled=async(start=CoroutineStart.UNDISPATCHED) { suspendCancellableCoroutine<Int> { callback=it } }
        cancelled.cancelAndJoin()
        callback.completeCallback(Result.success(1))
        callback.completeCallback(Result.failure(IllegalStateException("late")))
        assertTrue(cancelled.isCancelled)
        val success=async(start=CoroutineStart.UNDISPATCHED) { suspendCancellableCoroutine<Int> { callback=it } }
        callback.completeCallback(Result.success(2))
        callback.completeCallback(Result.success(3))
        callback.completeCallback(Result.failure(IllegalStateException("duplicate")))
        assertEquals(2,success.await())
    }
}
