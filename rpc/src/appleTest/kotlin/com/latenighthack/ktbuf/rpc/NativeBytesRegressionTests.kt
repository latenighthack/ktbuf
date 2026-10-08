package com.latenighthack.ktbuf.rpc

import kotlin.test.*

class NativeBytesRegressionTests {
    @Test fun nativeLengthsAreCheckedBeforeTheyBecomeKotlinArraySizes() {
        assertEquals(0,checkedNativeByteCount(0UL))
        assertEquals(Int.MAX_VALUE,checkedNativeByteCount(Int.MAX_VALUE.toULong()))
        for (length in listOf(Int.MAX_VALUE.toULong()+1UL,UInt.MAX_VALUE.toULong()+1UL,ULong.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { checkedNativeByteCount(length) }
        }
    }
    @Test fun actualNSDataCopiesMatchTheirCheckedLength() {
        for (bytes in listOf(byteArrayOf(),byteArrayOf(0,1,-1),ByteArray(100000) { it.toByte() })) {
            assertContentEquals(bytes,bytes.toNSData().toByteArray())
        }
    }
}
