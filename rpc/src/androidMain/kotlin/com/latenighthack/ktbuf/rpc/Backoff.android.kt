package com.latenighthack.ktbuf.rpc


actual class AtomicReference<T> actual constructor(defaultValue: T) {
    private val internalReference = java.util.concurrent.atomic.AtomicReference(defaultValue)
    actual var value: T
        get() = internalReference.get()
        set(value) {
            internalReference.set(value)
        }
}