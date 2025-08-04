package com.latenighthack.ktbuf.rpc

actual class AtomicReference<T> actual constructor(defaultValue: T) {
    private val internalReference = kotlin.concurrent.AtomicReference(defaultValue)
    actual var value: T
        get() = internalReference.value
        set(value) {
            internalReference.value = value
        }
}