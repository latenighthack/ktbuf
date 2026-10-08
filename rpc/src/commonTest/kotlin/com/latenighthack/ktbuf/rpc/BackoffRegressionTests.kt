@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.ktbuf.rpc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlin.test.*

class BackoffRegressionTests {
    @Test fun zeroRetriesStillAllowsTheInitialAttempt() = runTest {
        var calls=0
        assertEquals(42,repeatWithBackoff(retryLimit=0) { calls++; 42 })
        assertEquals(1,calls)
    }
    @Test fun retryBudgetsAndProgressiveWaitsAreExact() = runTest {
        val times=mutableListOf<Long>()
        val value=repeatWithBackoff<Int>(backoff=10,backoffRate=2f,jitter=0f,minRetryTime=0,maxRetryTime=100,retryLimit=2) {
            times.add(currentTime)
            if(times.size<3) throw IllegalStateException("temporary")
            7
        }
        assertEquals(7,value)
        assertEquals(listOf(0L,10L,30L),times)
        var calls=0
        assertFailsWith<RetryLimitExceeded> { repeatWithBackoff<Int>(backoff=0,minRetryTime=0,maxRetryTime=0,retryLimit=2) { calls++; retry() } }
        assertEquals(3,calls)
    }
    @Test fun flowLimitsAndStateBelongToEachCollection() = runTest {
        var calls=0
        val source=flow<Int> { calls++; if(calls<20) throw IllegalStateException("temporary"); emit(1) }
        val retried=source.retryWithBackoff(backoff=0,minRetryTime=0,maxRetryTime=0,retryLimit=1) { null }
        repeat(2) { assertFailsWith<IllegalStateException> { retried.collect() } }
        assertEquals(4,calls)
        val noRetry=source.retryWithBackoff(retryLimit=0) { null }
        assertFailsWith<IllegalStateException> { noRetry.collect() }
        assertEquals(5,calls)
    }
    @Test fun cancellationAndPermanentFailuresNeverRetry() = runTest {
        var calls=0
        val cancelled=CancellationException("cancelled")
        assertSame(cancelled,assertFailsWith<CancellationException> { repeatWithBackoff<Int>(retryLimit=3) { calls++; throw cancelled } })
        assertEquals(1,calls)
        val permanent=IllegalArgumentException("permanent")
        assertSame(permanent,assertFailsWith<IllegalArgumentException> { repeatWithBackoff<Int> { calls++; throw Backoff.PermanentError(permanent) } })
        assertEquals(2,calls)
        assertSame(cancelled,assertFailsWith<CancellationException> { flow<Int> { calls++; throw cancelled }.retryWithBackoff { null }.collect() })
        assertEquals(3,calls)
    }
    @Test fun deadlineBoundsRunningTasksAndAllRetryWaits() = runTest {
        assertFailsWith<TimeoutCancellationException> {
            repeatWithBackoff<Int>(timeLimit=5,retryLimit=10) { delay(30); retry() }
        }
        assertEquals(5,currentTime)
        assertFailsWith<TimeoutCancellationException> {
            flow<Int> { throw IllegalStateException() }.retryWithBackoff(timeLimit=5) { null }.collect()
        }
        assertEquals(10,currentTime)
    }
    @Test fun resetSkipsThePendingWaitAndRestartsTheDelay() = runTest {
        var calls=0
        assertEquals(9,repeatWithBackoff<Int>(backoff=100,minRetryTime=100,maxRetryTime=100,jitter=0f,retryLimit=1) {
            calls++
            if(calls==1) { reset(); retry() }
            9
        })
        assertEquals(0,currentTime)
        val reset=Channel<Unit>(1)
        launch { delay(7); reset.send(Unit) }
        calls=0
        assertEquals(9,repeatWithBackoff<Int>(backoff=100,minRetryTime=100,maxRetryTime=100,jitter=0f,retryLimit=1,resetChannel=reset) { calls++; if(calls==1) retry(); 9 })
        assertEquals(7,currentTime)
        reset.close()
    }
    @Test fun recoveryHooksAndClosedResetChannelsPreserveRetryTiming() = runTest {
        var calls=0
        val hooks=mutableListOf<Int>()
        val result=flow { calls++; if(calls<3) throw IllegalStateException(); emit(9) }
            .retryWithBackoff(backoff=10,backoffRate=2f,jitter=0f,minRetryTime=0,maxRetryTime=100,retryLimit=2) { hooks.add(it); null }.toList()
        assertEquals(listOf(9),result)
        assertEquals(listOf(1,2),hooks)
        assertEquals(30,currentTime)
        val closed=Channel<Unit>(); closed.close()
        calls=0
        assertEquals(9,repeatWithBackoff<Int>(backoff=10,jitter=0f,minRetryTime=0,maxRetryTime=100,retryLimit=1,resetChannel=closed) {
            calls++; if(calls==1) retry(); 9
        })
        assertEquals(40,currentTime)
    }
    @Test fun downstreamFailuresAreNotRetriedAndJitterStaysBounded() = runTest {
        var calls=0
        val failure=IllegalStateException("collector")
        assertSame(failure,assertFailsWith<IllegalStateException> {
            flow { calls++; emit(1) }.retryWithBackoff(retryLimit=3) { null }.collect { throw failure }
        })
        assertEquals(1,calls)
        val times=mutableListOf<Long>()
        repeatWithBackoff<Int>(backoff=10,backoffRate=1f,jitter=1f,minRetryTime=10,maxRetryTime=15,retryLimit=4) {
            times.add(currentTime)
            if(times.size<5) retry()
            1
        }
        assertTrue(times.zipWithNext().all { (a,b) -> b-a in 10L..15L })
    }
    @Test fun invalidParametersAreRejectedBeforeCallingTasks() = runTest {
        assertFailsWith<IllegalArgumentException> { repeatWithBackoff<Int>(backoff=-1) { 1 } }
        assertFailsWith<IllegalArgumentException> { repeatWithBackoff<Int>(backoffRate=Float.NaN) { 1 } }
        assertFailsWith<IllegalArgumentException> { repeatWithBackoff<Int>(jitter=-1f) { 1 } }
        assertFailsWith<IllegalArgumentException> { repeatWithBackoff<Int>(minRetryTime=2,maxRetryTime=1) { 1 } }
        assertFailsWith<IllegalArgumentException> { flowOf(1).retryWithBackoff(timeLimit=-1) { null } }
        assertFailsWith<IllegalArgumentException> { flowOf(1).retryWithBackoff(retryLimit=-1) { null } }
    }
}
