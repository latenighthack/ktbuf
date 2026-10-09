package com.latenighthack.ktbuf.rpc
import kotlin.test.*
class AtomicValueRegressionTests {
    @Test fun compareAndSetAcceptsTheExactObservedNaNReference() {
        val reference = AtomicReference<Any>(Double.NaN)
        val observed = reference.value
        assertTrue(reference.compareAndSet(observed, "updated"))
        assertEquals("updated", reference.value)
    }
    @Test fun compareAndSetDistinguishesSignedZero() {
        val reference = AtomicReference<Any>(-0.0)
        assertFalse(reference.compareAndSet(0.0, "wrong"))
        assertTrue(reference.compareAndSet(reference.value, "updated"))
    }
    @Test fun getAndUpdateReturnsThePreviousValue() {
        val reference=AtomicReference(3)
        assertEquals(3,reference.getAndUpdate { this+1 })
        assertEquals(4,reference.value)
    }
}
