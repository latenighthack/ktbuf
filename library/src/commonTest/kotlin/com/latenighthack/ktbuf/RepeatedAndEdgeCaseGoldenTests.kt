package com.latenighthack.ktbuf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Repeated-field layout plus the codec's documented rough edges. The behaviour tests
// at the bottom are characterization tests: they assert what ktbuf does today so a
// change is a deliberate decision rather than a silent wire-format shift.
class RepeatedAndEdgeCaseGoldenTests {
    @Test
    fun repeatedScalarsAreUnpacked() {
        // protoc-gen-kt emits one tag per element. proto3 normally packs repeated
        // scalars, so this is a real divergence -- pinned here so it cannot change
        // by accident.
        val encoded = encode {
            encode(1, 2)
            encode(2, 2)
            encode(3, 2)
        }

        assertGolden(WireGoldens.REPEATED_Int32, encoded, "repeated int32 unpacked")
        assertNotEquals(WireGoldens.REPEATED_Int32Packed, encoded.toHex(), "ktbuf does not emit packed form")

        val values = mutableListOf<Int>()

        decode(WireGoldens.REPEATED_Int32.hexToBytes()) { reader ->
            while (reader.nextField()) {
                values.add(reader.readInt32())
            }
        }

        assertEquals(listOf(1, 2, 3), values)
    }

    @Test
    fun emptyRepeatedFieldWritesNothing() {
        val encoded = encode { }

        assertEquals(0, encoded.size, "an empty repeated field puts nothing on the wire")
        assertGolden(WireGoldens.REPEATED_Empty, encoded, "empty repeated field")
    }

    @Test
    fun repeatedMessagesMatchGolden() {
        val encoded = encode {
            for (value in 1..3) {
                encode(2) { encode(value, 1) }
            }
        }

        assertGolden(WireGoldens.REPEATED_Messages, encoded, "repeated message field")

        val values = mutableListOf<Int>()

        decode(WireGoldens.REPEATED_Messages.hexToBytes()) { reader ->
            while (reader.nextField()) {
                values.add(reader.readField { it.nextField(); it.readInt32() })
            }
        }

        assertEquals(listOf(1, 2, 3), values)
    }

    @Test
    fun emptyMessageDecodesToNoFields() {
        decode(byteArrayOf()) { reader ->
            assertFalse(reader.nextField(), "an empty buffer yields no fields")
        }
    }

    @Test
    fun packedInputIsReadableAsBytesEvenThoughItIsNotEmitted() {
        // If a peer sends the packed form, ktbuf sees a single length-delimited field
        // rather than three varints. Documenting this makes the interop limitation
        // explicit instead of surprising.
        decode(WireGoldens.REPEATED_Int32Packed.hexToBytes()) { reader ->
            assertTrue(reader.nextField())
            assertEquals(2, reader.currentFieldNumber)

            val packed = reader.readBytes()

            assertEquals("010203", packed.toHex(), "packed payload arrives as one blob")
            assertFalse(reader.nextField(), "and it is a single field, not three")
        }
    }

    @Test
    fun boolTreatsNonOneVarintAsFalse() {
        // Canonical protobuf treats any non-zero varint as true; ktbuf's readBool()
        // compares against 1 exactly. A field carrying 2 therefore reads as false.
        // Characterizing it so the divergence is visible and testable.
        val two = encode { encode(2, 1) }

        val decoded = decodeFirstField(two) { it.readBool() }

        assertFalse(decoded, "readBool() currently only treats exactly 1 as true")

        assertTrue(decodeFirstField(WireGoldens.BOOL_True.hexToBytes()) { it.readBool() })
        assertFalse(decodeFirstField(WireGoldens.BOOL_False.hexToBytes()) { it.readBool() })
    }

    @Test
    fun largeStringAndBytesFieldsCrossTheBufferBoundary() {
        // ProtobufOutputStream's default buffer is 8192 bytes; values either side of it
        // exercise ConcreteLinkedByteArray growth with length-delimited payloads.
        for (size in listOf(8190, 8191, 8192, 8193, 20000)) {
            val text = buildString { repeat(size) { append('x') } }

            val encoded = encode { encode(text, 1) }
            val decoded = decodeFirstField(encoded) { it.readString() }

            assertEquals(size, decoded.length, "string of size $size round-trips")
            assertEquals(text, decoded, "string of size $size content")
        }
    }

    @Test
    fun multiByteCharactersSpanningTheBufferBoundaryRoundTrip() {
        // A 3-byte character whose encoding straddles the internal buffer edge would
        // be split by a naive per-character copy.
        for (charCount in listOf(2730, 2731, 2732)) {
            val text = buildString { repeat(charCount) { append('中') } }

            val encoded = encode { encode(text, 1) }
            val decoded = decodeFirstField(encoded) { it.readString() }

            assertEquals(text, decoded, "$charCount CJK characters round-trip intact")
        }
    }

    @Test
    fun fieldsAreReadableInDeclarationOrderAcrossManyFields() {
        val encoded = encode {
            for (fieldNumber in 1..50) {
                encode(fieldNumber * 1000, fieldNumber)
            }
        }

        var seen = 0

        decode(encoded) { reader ->
            while (reader.nextField()) {
                seen++

                assertEquals(seen, reader.currentFieldNumber, "field order preserved")
                assertEquals(seen * 1000, reader.readInt32(), "value for field $seen")
            }
        }

        assertEquals(50, seen, "all fields were read")
    }
}
