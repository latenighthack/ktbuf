package com.latenighthack.ktbuf

import com.latenighthack.ktbuf.bytes.mutableLinkedByteArrayOf
import kotlin.test.*

class GroupRegressionTests {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun reader(hex: String) = ScopedProtobufReader(mutableLinkedByteArrayOf(*bytes(hex)))
    @Test fun unknownGroupIsOpaqueAndRoundTripsWithNestedGroups() {
        val group="a30608630b10070ca406"
        val r=reader(group+"0802")
        assertTrue(r.nextField())
        assertContentEquals(bytes(group),r.skipField())
        assertTrue(r.nextField())
        assertEquals(1,r.currentFieldNumber)
        assertEquals(2,r.readInt32())
        assertFalse(r.nextField())
    }
    @Test fun groupsMustHaveMatchingTerminatorsAndCannotEndTheRoot() {
        for (hex in listOf("0b", "0b14", "0b0b0c", "0c", "0b0e")) {
            assertFails(hex) { val r=reader(hex); while(r.nextField()) r.skipField() }
        }
    }
    @Test fun groupNestingCannotOverflowTheStack() {
        assertFails { val r=reader("0b".repeat(200)+"0c".repeat(200)); r.nextField(); r.skipField() }
    }
}
