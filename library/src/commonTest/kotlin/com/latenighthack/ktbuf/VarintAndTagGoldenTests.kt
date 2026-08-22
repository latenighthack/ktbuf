package com.latenighthack.ktbuf

import kotlin.test.Test
import kotlin.test.assertEquals

// Varint and tag encoding are the two places where an off-by-one in a shift or a
// continuation-bit test stays invisible for small values and corrupts large ones.
// These bracket every width boundary.
class VarintAndTagGoldenTests {
    @Test
    fun varintWidthBoundariesMatchGoldens() {
        val cases = listOf(
            WireGoldens.VARINT_B1Max to 127uL,
            WireGoldens.VARINT_B2Min to 128uL,
            WireGoldens.VARINT_B2Max to 16383uL,
            WireGoldens.VARINT_B3Min to 16384uL,
            WireGoldens.VARINT_B3Max to 2097151uL,
            WireGoldens.VARINT_B4Min to 2097152uL,
            WireGoldens.VARINT_B4Max to 268435455uL,
            WireGoldens.VARINT_B5Min to 268435456uL,
            WireGoldens.VARINT_B9Max to 9223372036854775807uL,
            WireGoldens.VARINT_B10Min to 9223372036854775808uL,
            WireGoldens.VARINT_Max to ULong.MAX_VALUE
        )

        for ((golden, value) in cases) {
            assertRoundTrip(golden, value, "varint $value", { encode(it, 1) }, { it.readUInt64() })
        }
    }

    @Test
    fun varintByteWidthsAreExact() {
        // Tag for field 1 is one byte, so encoded size - 1 is the varint width.
        val widths = listOf(
            127uL to 1, 128uL to 2,
            16383uL to 2, 16384uL to 3,
            2097151uL to 3, 2097152uL to 4,
            268435455uL to 4, 268435456uL to 5,
            ULong.MAX_VALUE to 10
        )

        for ((value, expected) in widths) {
            val encoded = encode { encode(value, 1) }

            assertEquals(expected, encoded.size - 1, "varint width for $value")
        }
    }

    @Test
    fun fieldNumberBoundariesMatchGoldens() {
        // Field number is shifted left 3 before varint encoding, so tag width steps
        // at 16 (1->2 bytes), 2048 (2->3) and 262144 (3->4).
        val cases = listOf(
            WireGoldens.TAG_F1 to 1,
            WireGoldens.TAG_F15 to 15,
            WireGoldens.TAG_F16 to 16,
            WireGoldens.TAG_F2047 to 2047,
            WireGoldens.TAG_F2048 to 2048,
            WireGoldens.TAG_F262143 to 262143,
            WireGoldens.TAG_F262144 to 262144,
            WireGoldens.TAG_FMax to 536870911
        )

        for ((golden, fieldNumber) in cases) {
            assertGolden(golden, encode { encode(1, fieldNumber) }, "int32 field $fieldNumber = 1")

            decode(golden.hexToBytes()) { reader ->
                reader.nextField()

                assertEquals(fieldNumber, reader.currentFieldNumber, "field number round-trip")
                assertEquals(1, reader.readInt32(), "value for field $fieldNumber")
            }
        }
    }

    @Test
    fun maxFieldNumberIsReadableBack() {
        // 536870911 == 2^29-1, the largest legal protobuf field number. Its tag needs
        // the full 32 bits after the 3-bit shift, so a signed-int shift bug shows here.
        val encoded = encode { encode(42, 536870911) }

        decode(encoded) { reader ->
            reader.nextField()

            assertEquals(536870911, reader.currentFieldNumber)
            assertEquals(42, reader.readInt32())
        }
    }
}
