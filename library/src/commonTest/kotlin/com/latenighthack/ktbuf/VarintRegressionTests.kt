package com.latenighthack.ktbuf

import com.latenighthack.ktbuf.bytes.mutableLinkedByteArrayOf
import kotlin.test.*

class VarintRegressionTests {
    private fun reader(hex: String) = ScopedProtobufReader(mutableLinkedByteArrayOf(*hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
    @Test fun incompleteAndOverflowingValuesAreRejected() {
        for (hex in listOf("", "80", "8080", "80".repeat(10)+"00", "ff".repeat(9)+"02", "ff".repeat(10))) {
            assertFails(hex) { reader(hex).readUInt64() }
        }
        assertEquals(ULong.MAX_VALUE, reader("ff".repeat(9)+"01").readUInt64())
        assertEquals(-1L, reader("ff".repeat(9)+"01").readInt64())
        assertEquals(0UL,reader("8000").readUInt64()) // valid nonminimal encoding
    }
    @Test fun tagsRequireLegalFieldNumbersAndWireTypes() {
        for (hex in listOf("00", "01", "06", "0e", "0f", "8080808010", "80")) {
            assertFails(hex) { reader(hex).nextField() }
        }
        assertFalse(reader("").nextField())
        val maximum=reader("fdffffff0f")
        assertTrue(maximum.nextField())
        assertEquals(536870911,maximum.currentFieldNumber)
    }
}
