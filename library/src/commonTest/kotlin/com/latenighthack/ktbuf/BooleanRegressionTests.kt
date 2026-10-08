package com.latenighthack.ktbuf

import com.latenighthack.ktbuf.bytes.mutableLinkedByteArrayOf
import kotlin.test.*

class BooleanRegressionTests {
    @Test fun everyNonzeroBooleanEncodingIsTrue() {
        for (bytes in listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(127), byteArrayOf(-128,1), ByteArray(10) { if(it==9) 1 else -1 })) {
            assertTrue(ScopedProtobufReader(mutableLinkedByteArrayOf(*bytes)).readBool())
        }
        assertFalse(ScopedProtobufReader(mutableLinkedByteArrayOf(0)).readBool())
    }
}
