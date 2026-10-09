package com.latenighthack.ktbuf

import com.latenighthack.ktbuf.bytes.mutableLinkedByteArrayOf
import kotlin.test.*

class MessageBoundaryRegressionTests {
    private fun reader(hex: String) = ScopedProtobufReader(mutableLinkedByteArrayOf(*hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))

    @Test fun lengthsAreValidatedBeforeNarrowingOrAllocation() {
        for (hex in listOf("8080808010", "ffffffff0f", "ffffffffffffffffff01", "0201")) {
            assertFails(hex) { reader(hex).readBytes() }
            assertFails(hex) { reader(hex).readField { Unit } }
        }
    }
    @Test fun fixedWidthReadsCannotCrossTheDeclaredMessageBoundary() {
        assertFails { reader("010203").readFixedInt32() }
        assertFails { reader("01020304050607").readFixedInt64() }
        // Parent bytes exist after the one-byte child, but cannot satisfy its fixed32.
        assertFails { reader("0101020304").readField { it.readFixedInt32() } }
        assertFails { reader("020a040807").readField { it.nextField(); it.readBytes() } }
    }
    @Test fun aValidChildLeavesTheFollowingParentFieldIntact() {
        val r = reader("0208011007")
        assertEquals(1, r.readField { assertTrue(it.nextField()); it.readInt32() })
        assertTrue(r.nextField())
        assertEquals(2, r.currentFieldNumber)
        assertEquals(7, r.readInt32())
    }
    @Test fun maliciousTwoByteNestedLengthCannotFabricateAZeroPaddedChild() {
        // Locker.open tag followed by a claimed 127-byte message, with no remaining bytes.
        val reader = reader("0a7f")
        assertTrue(reader.nextField())
        var enteredChild = false
        assertFails { reader.readField { enteredChild = true; Unit } }
        assertFalse(enteredChild, "Reject the length before slicing or invoking a child decoder")
        for (hex in listOf("0a8080808008", "0a80808080808080808002", "0a80")) {
            val malformed = reader(hex)
            assertTrue(malformed.nextField())
            assertFails { malformed.readField { Unit } }
        }
    }
}
