package com.latenighthack.ktbuf.rpc

actual class AtomicReference<T> actual constructor(defaultValue: T) {
    actual var value: T = defaultValue
}