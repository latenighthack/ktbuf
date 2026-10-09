package com.latenighthack.ktbuf.rpc

actual class AtomicReference<T> actual constructor(defaultValue: T) {
    actual var value: T = defaultValue
    actual fun compareAndSet(expected: T, updated: T): Boolean {
        val current = value
        if (!(js("Object.is(current, expected)") as Boolean)) return false
        value = updated
        return true
    }
}