package com.latenighthack.ktbuf

import com.latenighthack.ktbuf.bytes.mutableLinkedByteArrayOf
import kotlin.test.*

class Utf8RegressionTests {
    @Test fun invalidStringsAreRejectedInsteadOfReplaced() {
        for (hex in listOf("01ff", "02c080", "03eda080", "04f4908080", "02e282", "0280bf")) {
            val bytes=hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertFails(hex) { ScopedProtobufReader(mutableLinkedByteArrayOf(*bytes)).readString() }
        }
    }
    @Test fun validUnicodeIncludingNulAndSupplementaryCharactersSurvives() {
        val text="a\u0000中😀"
        val bytes=text.encodeToByteArray()
        assertEquals(text,ScopedProtobufReader(mutableLinkedByteArrayOf(bytes.size.toByte(),*bytes)).readString())
    }
}
