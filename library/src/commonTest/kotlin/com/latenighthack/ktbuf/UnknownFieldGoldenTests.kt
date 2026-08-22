package com.latenighthack.ktbuf

import kotlin.test.Test
import kotlin.test.assertEquals

// Generated decoders route unrecognised field numbers to skipField(), which re-encodes
// the field so it can be preserved in `unknownFields` and written back out verbatim.
// Forward compatibility depends entirely on that re-encode being byte-exact.
class UnknownFieldGoldenTests {
    @Test
    fun skipFieldReEncodesEveryWireTypeExactly() {
        val cases = listOf(
            WireGoldens.UNKNOWN_Varint to "varint",
            WireGoldens.UNKNOWN_Fixed64 to "fixed64",
            WireGoldens.UNKNOWN_Fixed32 to "fixed32",
            WireGoldens.UNKNOWN_LengthDelimited to "length-delimited"
        )

        for ((golden, label) in cases) {
            val skipped = decodeFirstField(golden.hexToBytes()) { it.skipField() }

            assertEquals(golden, skipped.toHex(), "$label field must re-encode to the original bytes")
        }
    }

    @Test
    fun unknownFieldsArePreservedBetweenKnownOnes() {
        val bytes = WireGoldens.UNKNOWN_Interleaved.hexToBytes()

        var known1 = 0
        var known2 = ""
        var unknown = byteArrayOf()

        decode(bytes) { reader ->
            while (reader.nextField()) {
                when (reader.currentFieldNumber) {
                    1 -> known1 = reader.readInt32()
                    2 -> known2 = reader.readString()
                    else -> unknown += reader.skipField()
                }
            }
        }

        assertEquals(7, known1, "field 1")
        assertEquals("kept", known2, "field 2")
        assertEquals(WireGoldens.UNKNOWN_LengthDelimited, unknown.toHex(), "unknown field 9 preserved")
    }

    @Test
    fun roundTrippingThroughUnknownFieldsIsLossless() {
        // The full forward-compatibility contract: a reader that knows only field 1
        // must be able to re-emit the message with field 9 byte-identical.
        val original = WireGoldens.UNKNOWN_Interleaved.hexToBytes()

        var known1 = 0
        var known2 = ""
        var unknown = byteArrayOf()

        decode(original) { reader ->
            while (reader.nextField()) {
                when (reader.currentFieldNumber) {
                    1 -> known1 = reader.readInt32()
                    2 -> known2 = reader.readString()
                    else -> unknown += reader.skipField()
                }
            }
        }

        // Re-encode in the generated-code order: known fields first, unknowns appended
        // via encodeRaw, exactly as protoc-gen-kt emits.
        val reEncoded = encode {
            encode(known1, 1)
            encode(known2, 2)
            encodeRaw(unknown)
        }

        decode(reEncoded) { reader ->
            reader.nextField()
            assertEquals(1, reader.currentFieldNumber)
            assertEquals(7, reader.readInt32())

            reader.nextField()
            assertEquals(2, reader.currentFieldNumber)
            assertEquals("kept", reader.readString())

            reader.nextField()
            assertEquals(9, reader.currentFieldNumber, "unknown field survived the round trip")
            assertEquals("skipme", reader.readString())
        }
    }

    @Test
    fun skippingAnUnknownNestedMessageKeepsItIntact() {
        val nested = encode { encode(9) { encode(1, 1); encode("deep", 2) } }

        val skipped = decodeFirstField(nested) { it.skipField() }

        assertEquals(nested.toHex(), skipped.toHex(), "unknown nested message re-encoded exactly")
    }
}
