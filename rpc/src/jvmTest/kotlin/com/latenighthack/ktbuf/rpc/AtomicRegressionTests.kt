package com.latenighthack.ktbuf.rpc

import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.*

class AtomicRegressionTests {
    @Test fun concurrentUpdatesCannotLoseWrites() {
        val reference=AtomicReference(0)
        val barrier=CyclicBarrier(8)
        val threads=(1..8).map { thread {
            var first=true
            reference.getAndUpdate {
                if (first) { first=false; barrier.await() }
                this+1
            }
        } }
        threads.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(8,reference.value)
    }
}
