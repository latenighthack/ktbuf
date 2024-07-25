package com.latenighthack.ktbuf.bytes

fun listOfBytesEquals(a: List<ByteArray>, b: List<ByteArray>): Boolean {
    if (a === b) return true
    if (a.size != b.size) return false

    for (i in a.indices) {
        if (!a[i].contentEquals(b[i])) return false
    }

    return true
}
