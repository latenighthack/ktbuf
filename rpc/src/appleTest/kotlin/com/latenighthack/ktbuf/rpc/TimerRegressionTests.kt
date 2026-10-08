@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
package com.latenighthack.ktbuf.rpc

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.*
import platform.Foundation.*
import kotlin.test.*

class TimerRegressionTests {
    @Test fun longDelaysDoNotOverflowIntoImmediateTimers() {
        assertEquals(0.0,timerIntervalSeconds(-1))
        assertEquals(1.0,timerIntervalSeconds(1000))
        assertTrue(timerIntervalSeconds(Long.MAX_VALUE).isFinite())
        assertTrue(timerIntervalSeconds(Long.MAX_VALUE)>0)
    }

    @Test fun cancelledCoroutineDoesNotResumeFromItsTimer() {
        var resumed=false
        val scope=CoroutineScope(MainQueueDispatcher()+SupervisorJob())
        val job=scope.launch(start=CoroutineStart.UNDISPATCHED) { delay(1); resumed=true }
        job.cancel()
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.03))
        assertTrue(job.isCancelled)
        assertFalse(resumed)
        scope.cancel()
    }
    @Test fun disposingATimerSuppressesItsCallback() {
        var fired=0
        val dispatcher=MainQueueDispatcher()
        val disposed=dispatcher.invokeOnTimeout(1, Runnable { fired++ },EmptyCoroutineContext)
        disposed.dispose()
        dispatcher.invokeOnTimeout(2,Runnable { fired+=10 },EmptyCoroutineContext)
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
        assertEquals(10,fired)
    }
}
