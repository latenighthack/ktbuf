package com.latenighthack.ktbuf.rpc

actual class AtomicReference<T> actual constructor(defaultValue: T) {
    actual var value: T = defaultValue
    actual fun compareAndSet(expected: T, updated: T): Boolean {
        if (value !== expected) return false
        value = updated
        return true
    }
}