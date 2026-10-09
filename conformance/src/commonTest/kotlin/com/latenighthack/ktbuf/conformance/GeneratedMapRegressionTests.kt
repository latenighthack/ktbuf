package com.latenighthack.ktbuf.conformance

import com.latenighthack.ktbuf.conformance.v1.*
import kotlin.test.*

class GeneratedMapRegressionTests {
    @Test fun mapsMatchIndependentWireBytesAndContentEquality() {
        val value = MapHolder(
            ints = mapOf("a" to 7), messages = mapOf(1 to MapValue(9, "ok")),
            enums = mapOf(true to AllTypes.Kind.A), blobs = mapOf("b" to byteArrayOf(1, 2))
        )
        assertGoldenRoundTrip(
            "0a050a01611007120a08011206080912026f6b1a040801100322070a016212020102",
            value, { it.toByteArray() }, { MapHolder.fromByteArray(it) }, "all map value types"
        )
        assertEquals(value.hashCode(), MapHolder.fromByteArray(value.toByteArray()).hashCode())
    }

    @Test fun mapDefaultsDuplicatesAndBuildersPreserveValues() {
        val defaults = MapHolder.fromByteArray(byteArrayOf(10, 0, 18, 0, 26, 0, 34, 0))
        assertEquals(mapOf("" to 0), defaults.ints)
        assertEquals(mapOf(0 to MapValue()), defaults.messages)
        assertEquals(mapOf(false to AllTypes.Kind.UNKNOWN), defaults.enums)
        assertContentEquals(byteArrayOf(), defaults.blobs[""])
        val duplicates = MapHolder(ints = mapOf("a" to 1)).toByteArray() + MapHolder(ints = mapOf("a" to 2)).toByteArray()
        assertEquals(2, MapHolder.fromByteArray(duplicates).ints["a"])
        val built = MapHolder { ints { "a" to 3 }; messages { 1 to MapValue(4) }; enums { false to AllTypes.Kind.A }; blobs { "b" to byteArrayOf(5) } }
        assertEquals(3, built.ints["a"])
        assertEquals(4, built.messages[1]?.id)
        assertEquals(built, MapHolder.fromByteArray(built.toByteArray()))
    }
}
