package com.latenighthack.ktbuf

import com.latenighthack.ktbuf.proto.Enum
import kotlin.test.Test
import kotlin.test.assertEquals

// Byte-exact coverage of every scalar type ProtobufWriter/ProtobufReader supports.
// Goldens come from the reference protobuf implementation (see tools/generate_goldens.py),
// so these assert interoperability, not merely self-consistency.
class WireCodecGoldenTests {
    @Test
    fun int32MatchesGoldens() {
        val cases = listOf(
            WireGoldens.INT32_Zero to 0,
            WireGoldens.INT32_One to 1,
            WireGoldens.INT32_MinusOne to -1,
            WireGoldens.INT32_OneTwentySeven to 127,
            WireGoldens.INT32_OneTwentyEight to 128,
            WireGoldens.INT32_ThreeHundred to 300,
            WireGoldens.INT32_Max to Int.MAX_VALUE,
            WireGoldens.INT32_Min to Int.MIN_VALUE
        )

        for ((golden, value) in cases) {
            assertRoundTrip(golden, value, "int32 $value", { encode(it, 1) }, { it.readInt32() })
        }
    }

    @Test
    fun negativeInt32SignExtendsToTenBytes() {
        // The classic int32/sint32 trap: proto3 sign-extends negative int32 to a full
        // 64-bit varint. Truncating to 5 bytes still round-trips through ktbuf but is
        // unreadable by every other protobuf implementation.
        val encoded = encode { encode(-1, 1) }

        assertEquals(11, encoded.size, "negative int32 must be a 1-byte tag + 10-byte varint")
        assertGolden(WireGoldens.INT32_MinusOne, encoded, "int32 -1")
    }

    @Test
    fun int64MatchesGoldens() {
        val cases = listOf(
            WireGoldens.INT64_Zero to 0L,
            WireGoldens.INT64_One to 1L,
            WireGoldens.INT64_MinusOne to -1L,
            WireGoldens.INT64_Max to Long.MAX_VALUE,
            WireGoldens.INT64_Min to Long.MIN_VALUE
        )

        for ((golden, value) in cases) {
            assertRoundTrip(golden, value, "int64 $value", { encode(it, 1) }, { it.readInt64() })
        }
    }

    @Test
    fun uint32MatchesGoldens() {
        val cases = listOf(
            WireGoldens.UINT32_Zero to 0u,
            WireGoldens.UINT32_One to 1u,
            WireGoldens.UINT32_Max to UInt.MAX_VALUE
        )

        for ((golden, value) in cases) {
            assertRoundTrip(golden, value, "uint32 $value", { encode(it, 1) }, { it.readUInt32() })
        }
    }

    @Test
    fun uint64MatchesGoldens() {
        val cases = listOf(
            WireGoldens.UINT64_Zero to 0uL,
            WireGoldens.UINT64_One to 1uL,
            WireGoldens.UINT64_Max to ULong.MAX_VALUE
        )

        for ((golden, value) in cases) {
            assertRoundTrip(golden, value, "uint64 $value", { encode(it, 1) }, { it.readUInt64() })
        }
    }

    @Test
    fun sint32MatchesGoldensWithZigZag() {
        // 0,-1,1,-2,2 -> 0,1,2,3,4 pins the zigzag interleave direction; getting it
        // backwards is a bug that only shows up against a foreign encoder.
        val cases = listOf(
            WireGoldens.SINT32_Zero to 0,
            WireGoldens.SINT32_MinusOne to -1,
            WireGoldens.SINT32_One to 1,
            WireGoldens.SINT32_MinusTwo to -2,
            WireGoldens.SINT32_Two to 2,
            WireGoldens.SINT32_Max to Int.MAX_VALUE,
            WireGoldens.SINT32_Min to Int.MIN_VALUE
        )

        for ((golden, value) in cases) {
            assertRoundTrip(golden, value, "sint32 $value", { encodeSInt(it, 1) }, { it.readSInt32() })
        }
    }

    @Test
    fun sint64MatchesGoldensWithZigZag() {
        val cases = listOf(
            WireGoldens.SINT64_Zero to 0L,
            WireGoldens.SINT64_MinusOne to -1L,
            WireGoldens.SINT64_One to 1L,
            WireGoldens.SINT64_Max to Long.MAX_VALUE,
            WireGoldens.SINT64_Min to Long.MIN_VALUE
        )

        for ((golden, value) in cases) {
            assertRoundTrip(golden, value, "sint64 $value", { encodeSInt(it, 1) }, { it.readSInt64() })
        }
    }

    @Test
    fun fixed32MatchesGoldens() {
        val cases = listOf(
            WireGoldens.FIXED32_Zero to 0u,
            WireGoldens.FIXED32_One to 1u,
            WireGoldens.FIXED32_Max to UInt.MAX_VALUE
        )

        for ((golden, value) in cases) {
            assertRoundTrip(golden, value, "fixed32 $value", { encodeFixed(it, 1) }, { it.readFixedInt32() })
        }
    }

    @Test
    fun fixed64MatchesGoldens() {
        val cases = listOf(
            WireGoldens.FIXED64_Zero to 0uL,
            WireGoldens.FIXED64_One to 1uL,
            WireGoldens.FIXED64_Max to ULong.MAX_VALUE
        )

        for ((golden, value) in cases) {
            assertRoundTrip(golden, value, "fixed64 $value", { encodeFixed(it, 1) }, { it.readFixedInt64() })
        }
    }

    @Test
    fun sfixed32MatchesGoldens() {
        val cases = listOf(
            WireGoldens.SFIXED32_Zero to 0,
            WireGoldens.SFIXED32_MinusOne to -1,
            WireGoldens.SFIXED32_Max to Int.MAX_VALUE,
            WireGoldens.SFIXED32_Min to Int.MIN_VALUE
        )

        for ((golden, value) in cases) {
            assertRoundTrip(golden, value, "sfixed32 $value", { encodeFixed(it, 1) }, { it.readSFixedInt32() })
        }
    }

    @Test
    fun sfixed64MatchesGoldens() {
        val cases = listOf(
            WireGoldens.SFIXED64_Zero to 0L,
            WireGoldens.SFIXED64_MinusOne to -1L,
            WireGoldens.SFIXED64_Max to Long.MAX_VALUE,
            WireGoldens.SFIXED64_Min to Long.MIN_VALUE
        )

        for ((golden, value) in cases) {
            assertRoundTrip(golden, value, "sfixed64 $value", { encodeFixed(it, 1) }, { it.readSFixedInt64() })
        }
    }

    @Test
    fun floatMatchesGoldensIncludingSpecialValues() {
        // Compared as raw bits so -0.0 stays distinguishable from 0.0 and NaN
        // compares meaningfully.
        val cases = listOf(
            WireGoldens.FLOAT_Zero to 0.0f,
            WireGoldens.FLOAT_NegZero to -0.0f,
            WireGoldens.FLOAT_One to 1.0f,
            WireGoldens.FLOAT_MinusOne to -1.0f,
            WireGoldens.FLOAT_Nan to Float.NaN,
            WireGoldens.FLOAT_PosInf to Float.POSITIVE_INFINITY,
            WireGoldens.FLOAT_NegInf to Float.NEGATIVE_INFINITY,
            WireGoldens.FLOAT_MinValue to Float.MIN_VALUE,
            WireGoldens.FLOAT_MaxValue to Float.MAX_VALUE
        )

        for ((golden, value) in cases) {
            assertGolden(golden, encode { encode(value, 1) }, "float $value")

            val decoded = decodeFirstField(golden.hexToBytes()) { it.readFloat() }

            assertEquals(value.toRawBits(), decoded.toRawBits(), "float $value decoded to the wrong bits")
        }
    }

    @Test
    fun doubleMatchesGoldensIncludingSpecialValues() {
        val cases = listOf(
            WireGoldens.DOUBLE_Zero to 0.0,
            WireGoldens.DOUBLE_NegZero to -0.0,
            WireGoldens.DOUBLE_One to 1.0,
            WireGoldens.DOUBLE_MinusOne to -1.0,
            WireGoldens.DOUBLE_Nan to Double.NaN,
            WireGoldens.DOUBLE_PosInf to Double.POSITIVE_INFINITY,
            WireGoldens.DOUBLE_NegInf to Double.NEGATIVE_INFINITY,
            WireGoldens.DOUBLE_MinValue to Double.MIN_VALUE,
            WireGoldens.DOUBLE_MaxValue to Double.MAX_VALUE
        )

        for ((golden, value) in cases) {
            assertGolden(golden, encode { encode(value, 1) }, "double $value")

            val decoded = decodeFirstField(golden.hexToBytes()) { it.readDouble() }

            assertEquals(value.toRawBits(), decoded.toRawBits(), "double $value decoded to the wrong bits")
        }
    }

    @Test
    fun boolMatchesGoldens() {
        assertRoundTrip(WireGoldens.BOOL_True, true, "bool true", { encode(it, 1) }, { it.readBool() })
        assertRoundTrip(WireGoldens.BOOL_False, false, "bool false", { encode(it, 1) }, { it.readBool() })
    }

    @Test
    fun stringMatchesGoldensAcrossUtf8() {
        // Kotlin strings are UTF-16; the emoji case forces correct surrogate-pair
        // widening to 4-byte UTF-8 on every platform.
        val cases = listOf(
            WireGoldens.STRING_Empty to "",
            WireGoldens.STRING_Ascii to "hello",
            WireGoldens.STRING_Cjk to "你好世界",
            WireGoldens.STRING_Emoji to "🎉",
            WireGoldens.STRING_Mixed to "aé中😀z",
            WireGoldens.STRING_Nul to "a\u0000b"
        )

        for ((golden, value) in cases) {
            assertRoundTrip(golden, value, "string '$value'", { encode(it, 1) }, { it.readString() })
        }
    }

    @Test
    fun bytesMatchesGoldens() {
        val cases = listOf(
            WireGoldens.BYTES_Empty to byteArrayOf(),
            WireGoldens.BYTES_Simple to byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte()),
            WireGoldens.BYTES_HighBytes to ByteArray(6) { (250 + it).toByte() }
        )

        for ((golden, value) in cases) {
            assertGolden(golden, encode { encode(value, 1) }, "bytes ${value.toHex()}")

            val decoded = decodeFirstField(golden.hexToBytes()) { it.readBytes() }

            assertEquals(value.toHex(), decoded.toHex(), "bytes ${value.toHex()} decoded wrong")
        }
    }

    @Test
    fun enumEncodesDeclaredValueNotOrdinal() {
        // model.proto-style enum with a deliberate gap: UNKNOWN=0, A=3, B=4, C=5.
        // Encoding by ordinal instead of declared value would silently produce 0,1,2,3.
        val cases = listOf(
            WireGoldens.ENUM_Unknown to TestEnum.UNKNOWN,
            WireGoldens.ENUM_A to TestEnum.A,
            WireGoldens.ENUM_B to TestEnum.B,
            WireGoldens.ENUM_C to TestEnum.C
        )

        for ((golden, value) in cases) {
            assertGolden(golden, encode { encode(value, 5) }, "enum $value")

            val decoded = decodeFirstField(golden.hexToBytes()) { TestEnum.fromInt(it.readInt32()) }

            assertEquals(value, decoded, "enum $value decoded wrong")
        }
    }

    @Test
    fun mixedScalarMessageMatchesGolden() {
        val encoded = encode {
            encode(-1, 1)
            encode("hi", 2)
            encode(true, 3)
            encode(1.5, 4)
            encode(byteArrayOf(0x01), 5)
        }

        assertGolden(WireGoldens.MIXED_AllScalars, encoded, "mixed scalar message")

        decode(WireGoldens.MIXED_AllScalars.hexToBytes()) { reader ->
            reader.nextField()
            assertEquals(-1, reader.readInt32())
            reader.nextField()
            assertEquals("hi", reader.readString())
            reader.nextField()
            assertEquals(true, reader.readBool())
            reader.nextField()
            assertEquals(1.5, reader.readDouble())
            reader.nextField()
            assertEquals("01", reader.readBytes().toHex())
        }
    }

    private enum class TestEnum(override val value: Int) : Enum {
        UNKNOWN(0), A(3), B(4), C(5);

        companion object {
            fun fromInt(value: Int) = entries.firstOrNull { it.value == value } ?: UNKNOWN
        }
    }
}
