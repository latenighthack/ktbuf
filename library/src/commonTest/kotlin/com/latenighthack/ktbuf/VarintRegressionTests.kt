package com.latenighthack.ktbuf

import com.latenighthack.ktbuf.bytes.mutableLinkedByteArrayOf
import kotlin.test.*

class VarintRegressionTests {
    @Test fun writersRejectInvalidFieldNumbersBeforeWritingOrRunningBuilders() {
        for (number in listOf(0, -1, 536870912, Int.MAX_VALUE)) {
            val output = ProtobufOutputStream()
            output.write { it.encode(7, 1) }
            val before = output.toByteArray()
            assertFailsWith<IllegalArgumentException> { output.write { it.encode(9, number) } }
            assertContentEquals(before, output.toByteArray())
            var invoked = false
            assertFailsWith<IllegalArgumentException> { output.write { it.encode(number) { invoked = true } } }
            assertFalse(invoked)
            assertContentEquals(before, output.toByteArray())
        }
        val maximum = reader(ProtobufOutputStream.encode { encode(9, 536870911) }.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
        assertTrue(maximum.nextField()); assertEquals(536870911, maximum.currentFieldNumber); assertEquals(9, maximum.readInt32())
    }
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
