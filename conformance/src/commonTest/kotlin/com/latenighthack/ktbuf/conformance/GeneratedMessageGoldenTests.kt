package com.latenighthack.ktbuf.conformance

import com.latenighthack.ktbuf.conformance.v1.AllTypes
import com.latenighthack.ktbuf.conformance.v1.Empty
import com.latenighthack.ktbuf.conformance.v1.OneOfHolder
import com.latenighthack.ktbuf.conformance.v1.fromByteArray
import com.latenighthack.ktbuf.conformance.v1.toByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// End-to-end coverage of protoc-gen-kt output: the generated writeTo/readFrom pair is
// checked against bytes produced by the reference protobuf runtime, so a mismatch means
// a real peer would misread ktbuf (or vice versa).
class GeneratedMessageGoldenTests {
    private fun allTypes(golden: String, message: AllTypes, label: String) =
        assertGoldenRoundTrip(golden, message, { it.toByteArray() }, { AllTypes.fromByteArray(it) }, label)

    private fun oneOf(golden: String, message: OneOfHolder, label: String) =
        assertGoldenRoundTrip(golden, message, { it.toByteArray() }, { OneOfHolder.fromByteArray(it) }, label)

    @Test
    fun defaultMessageEncodesToNothing() {
        // proto3 omits fields at their default; an all-default message is zero bytes.
        allTypes(ConformanceGoldens.Empty, AllTypes(), "default AllTypes")

        assertEquals(0, AllTypes().toByteArray().size)
    }

    @Test
    fun emptyMessageTypeEncodesToNothing() {
        assertGoldenRoundTrip(
            ConformanceGoldens.EmptyMessage, Empty(),
            { it.toByteArray() }, { Empty.fromByteArray(it) }, "Empty message"
        )
    }

    @Test
    fun integerFieldsMatchGoldens() {
        allTypes(ConformanceGoldens.Int32Only, AllTypes(fInt32 = 42), "f_int32 = 42")
        allTypes(ConformanceGoldens.Int32Negative, AllTypes(fInt32 = -1), "f_int32 = -1")
        allTypes(ConformanceGoldens.Int32Min, AllTypes(fInt32 = Int.MIN_VALUE), "f_int32 min")
        allTypes(ConformanceGoldens.Int32Max, AllTypes(fInt32 = Int.MAX_VALUE), "f_int32 max")
        allTypes(ConformanceGoldens.Int64Min, AllTypes(fInt64 = Long.MIN_VALUE), "f_int64 min")
        allTypes(ConformanceGoldens.Int64Max, AllTypes(fInt64 = Long.MAX_VALUE), "f_int64 max")
        allTypes(ConformanceGoldens.Uint32Max, AllTypes(fUint32 = UInt.MAX_VALUE), "f_uint32 max")
        allTypes(ConformanceGoldens.Uint64Max, AllTypes(fUint64 = ULong.MAX_VALUE), "f_uint64 max")
    }

    @Test
    fun zigZagFieldsMatchGoldens() {
        allTypes(ConformanceGoldens.Sint32Negative, AllTypes(fSint32 = -1), "f_sint32 = -1")
        allTypes(ConformanceGoldens.Sint32Min, AllTypes(fSint32 = Int.MIN_VALUE), "f_sint32 min")
        allTypes(ConformanceGoldens.Sint64Negative, AllTypes(fSint64 = -1), "f_sint64 = -1")
        allTypes(ConformanceGoldens.Sint64Min, AllTypes(fSint64 = Long.MIN_VALUE), "f_sint64 min")
    }

    @Test
    fun sint32IsMuchSmallerThanInt32ForNegatives() {
        // The whole point of sint32: -1 costs 2 bytes zigzagged versus 11 sign-extended.
        val zigZagged = AllTypes(fSint32 = -1).toByteArray()
        val signExtended = AllTypes(fInt32 = -1).toByteArray()

        assertEquals(2, zigZagged.size, "sint32 -1 is a tag plus a one-byte varint")
        assertEquals(11, signExtended.size, "int32 -1 is a tag plus a ten-byte varint")
    }

    @Test
    fun fixedWidthFieldsMatchGoldens() {
        allTypes(ConformanceGoldens.Fixed32Max, AllTypes(fFixed32 = UInt.MAX_VALUE), "f_fixed32 max")
        allTypes(ConformanceGoldens.Fixed64Max, AllTypes(fFixed64 = ULong.MAX_VALUE), "f_fixed64 max")
        allTypes(ConformanceGoldens.Sfixed32Negative, AllTypes(fSfixed32 = -1), "f_sfixed32 = -1")
        allTypes(ConformanceGoldens.Sfixed64Negative, AllTypes(fSfixed64 = -1), "f_sfixed64 = -1")
    }

    @Test
    fun floatingPointFieldsMatchGoldens() {
        allTypes(ConformanceGoldens.FloatOne, AllTypes(fFloat = 1.0f), "f_float = 1.0")
        allTypes(ConformanceGoldens.FloatNegative, AllTypes(fFloat = -2.5f), "f_float = -2.5")
        allTypes(ConformanceGoldens.DoubleOne, AllTypes(fDouble = 1.0), "f_double = 1.0")
        allTypes(ConformanceGoldens.DoubleNegative, AllTypes(fDouble = -2.5), "f_double = -2.5")
    }

    @Test
    fun nonFiniteFloatsMatchGoldens() {
        // NaN never equals itself, so these are compared by encoded bytes and raw bits
        // rather than through message equality.
        assertEquals(ConformanceGoldens.FloatNan, AllTypes(fFloat = Float.NaN).toByteArray().toHex())
        assertEquals(ConformanceGoldens.DoubleNan, AllTypes(fDouble = Double.NaN).toByteArray().toHex())

        val decodedFloat = AllTypes.fromByteArray(ConformanceGoldens.FloatNan.hexToBytes())
        val decodedDouble = AllTypes.fromByteArray(ConformanceGoldens.DoubleNan.hexToBytes())

        assertTrue(decodedFloat.fFloat.isNaN(), "NaN survives the float round trip")
        assertTrue(decodedDouble.fDouble.isNaN(), "NaN survives the double round trip")

        allTypes(ConformanceGoldens.FloatPosInf, AllTypes(fFloat = Float.POSITIVE_INFINITY), "+Inf float")
        allTypes(ConformanceGoldens.FloatNegInf, AllTypes(fFloat = Float.NEGATIVE_INFINITY), "-Inf float")
        allTypes(ConformanceGoldens.DoublePosInf, AllTypes(fDouble = Double.POSITIVE_INFINITY), "+Inf double")
    }

    @Test
    fun boolStringAndBytesMatchGoldens() {
        allTypes(ConformanceGoldens.BoolTrue, AllTypes(fBool = true), "f_bool = true")
        allTypes(ConformanceGoldens.StringAscii, AllTypes(fString = "hello"), "ascii string")
        allTypes(ConformanceGoldens.StringCjk, AllTypes(fString = "你好世界"), "cjk string")
        allTypes(ConformanceGoldens.StringEmoji, AllTypes(fString = "🎉"), "emoji string")
        allTypes(
            ConformanceGoldens.BytesSimple,
            AllTypes(fBytes = byteArrayOf(0x00, 0x01, 0xFF.toByte())),
            "bytes field"
        )
    }

    @Test
    fun enumsEncodeTheirDeclaredValue() {
        // Kind has a deliberate gap (UNKNOWN=0, A=3, B=4, C=5) so ordinal-based
        // encoding would produce visibly wrong bytes.
        allTypes(ConformanceGoldens.EnumA, AllTypes(fEnum = AllTypes.Kind.A), "enum A")
        allTypes(ConformanceGoldens.EnumC, AllTypes(fEnum = AllTypes.Kind.C), "enum C")

        // Bound through typed locals because `AllTypes.Kind.A` otherwise resolves to the
        // nested class rather than the companion instance.
        val a: AllTypes.Kind = AllTypes.Kind.A
        val c: AllTypes.Kind = AllTypes.Kind.C

        assertEquals(3, a.value)
        assertEquals(5, c.value)
    }

    @Test
    fun unrecognisedEnumValuesArePreserved() {
        // A newer peer may send an enum constant this build has never heard of. The
        // generated code keeps the raw number instead of collapsing it to UNKNOWN.
        val fromFuture = AllTypes(fEnum = AllTypes.Kind.fromInt(99))

        assertEquals(99, fromFuture.fEnum.value)

        val decoded = AllTypes.fromByteArray(fromFuture.toByteArray())

        assertEquals(99, decoded.fEnum.value, "unknown enum value survives a round trip")
    }

    @Test
    fun nestedMessagesMatchGoldens() {
        allTypes(
            ConformanceGoldens.InnerSimple,
            AllTypes(fInner = AllTypes.Inner(str = "in", anInt = 7)),
            "simple inner"
        )
        allTypes(
            ConformanceGoldens.InnerNested,
            AllTypes(
                fInner = AllTypes.Inner(
                    str = "a", anInt = 1,
                    innerInner = AllTypes.InnerInner(str = "b", anInt = 2)
                )
            ),
            "doubly nested inner"
        )
    }

    @Test
    fun presentButEmptySubmessageIsDistinctFromAbsent() {
        // An empty submessage still needs its tag and a zero length; dropping it would
        // silently turn "present and empty" into "absent".
        allTypes(ConformanceGoldens.InnerEmpty, AllTypes(fInner = AllTypes.Inner()), "empty inner")

        assertNull(AllTypes().fInner, "absent by default")
        assertNotNull(
            AllTypes.fromByteArray(ConformanceGoldens.InnerEmpty.hexToBytes()).fInner,
            "an empty submessage decodes as present"
        )
        assertEquals(0, AllTypes().toByteArray().size, "absent inner writes nothing")
    }

    @Test
    fun largeSubmessageCrossesTheLengthPrefixBoundary() {
        // A 200-byte body forces the encoder's nested length back-patch from one byte
        // to two while the parent is still being written.
        allTypes(
            ConformanceGoldens.LargeInner,
            AllTypes(fInner = AllTypes.Inner(str = "z".repeat(200))),
            "large inner"
        )
    }

    @Test
    fun repeatedFieldsMatchGoldens() {
        allTypes(ConformanceGoldens.RepeatedInt32, AllTypes(rInt32 = listOf(1, 2, 3)), "repeated int32")
        allTypes(
            ConformanceGoldens.RepeatedInt32Negative,
            AllTypes(rInt32 = listOf(-1, 0, 1)),
            "repeated int32 with negatives"
        )
        allTypes(
            ConformanceGoldens.RepeatedString,
            AllTypes(rString = listOf("a", "bb", "")),
            "repeated string"
        )
        allTypes(
            ConformanceGoldens.RepeatedInner,
            AllTypes(rInner = listOf(AllTypes.Inner(anInt = 1), AllTypes.Inner(anInt = 2))),
            "repeated message"
        )
    }

    @Test
    fun repeatedScalarsArePackedButRepeatedStringsAndMessagesAreNot() {
        // proto3 packs repeated numerics into one length-delimited field and leaves
        // repeated strings/messages as one tagged entry each. Generated code follows
        // both rules, which is why the goldens above line up with the reference runtime.
        val packed = AllTypes(rInt32 = listOf(1, 2, 3)).toByteArray()

        assertEquals("920103010203", packed.toHex(), "three ints share one field 18 entry")

        val strings = AllTypes(rString = listOf("a", "bb")).toByteArray()

        assertEquals("9a0101619a01026262", strings.toHex(), "each string is its own field 19 entry")
    }

    @Test
    fun emptyRepeatedFieldsWriteNothing() {
        val message = AllTypes(rInt32 = emptyList(), rString = emptyList(), rInner = emptyList())

        assertEquals(0, message.toByteArray().size, "empty repeated fields are omitted entirely")
    }

    @Test
    fun highFieldNumbersMatchGoldens() {
        // Field 2048 needs a three-byte tag once shifted for the wire type.
        allTypes(ConformanceGoldens.HighField, AllTypes(fHighField = 9), "field 2048")
    }

    @Test
    fun fullyPopulatedMessageMatchesGolden() {
        // The ordering test: every field set at once must serialize in field-number
        // order exactly as the reference implementation does.
        allTypes(
            ConformanceGoldens.AllPopulated,
            AllTypes(
                fInt32 = -1, fInt64 = -2L, fUint32 = 3u, fUint64 = 4uL,
                fSint32 = -5, fSint64 = -6L, fFixed32 = 7u, fFixed64 = 8uL,
                fSfixed32 = -9, fSfixed64 = -10L, fFloat = 1.5f, fDouble = -2.5,
                fBool = true, fString = "all", fBytes = byteArrayOf(0x01, 0x02),
                fEnum = AllTypes.Kind.B,
                fInner = AllTypes.Inner(
                    str = "i", anInt = 11,
                    innerInner = AllTypes.InnerInner(str = "ii", anInt = 12)
                ),
                rInt32 = listOf(13, 14), rString = listOf("x", "y"),
                rInner = listOf(AllTypes.Inner(anInt = 15)),
                fHighField = 16
            ),
            "fully populated"
        )
    }

    @Test
    fun oneOfCasesDecodeFromCanonicalBytes() {
        // Decode direction against bytes the reference serializer actually produces:
        // this is what ktbuf receives from a real protobuf peer.
        val cases = listOf(
            Triple(ConformanceGoldens.OneOfInt, OneOfHolder.OneOfChoice.cInt(2), "int"),
            Triple(ConformanceGoldens.OneOfString, OneOfHolder.OneOfChoice.cString("s"), "string"),
            Triple(
                ConformanceGoldens.OneOfInner,
                OneOfHolder.OneOfChoice.cInner(AllTypes.Inner(anInt = 3)),
                "message"
            )
        )

        for ((golden, choice, label) in cases) {
            assertEquals(
                OneOfHolder(lead = 1, choice = choice),
                OneOfHolder.fromByteArray(golden.hexToBytes()),
                "canonical oneof $label decodes correctly"
            )
        }

        oneOf(ConformanceGoldens.OneOfNone, OneOfHolder(lead = 1), "no oneof case")
    }

    @Test
    fun oneOfCasesEncodeInGeneratorFieldOrder() {
        // protoc-gen-kt writes the oneof block before the message's regular fields, so
        // `lead` (field 1) lands after the oneof case (field 2+). Field order carries no
        // meaning in protobuf and the reference runtime parses these bytes back to the
        // same message -- verified when the goldens are generated -- but the output is
        // not byte-identical to the reference serializer's. Pinned so the layout cannot
        // drift unnoticed.
        val cases = listOf(
            Triple(ConformanceGoldens.OneOfIntKtbufOrder, OneOfHolder.OneOfChoice.cInt(2), "int"),
            Triple(ConformanceGoldens.OneOfStringKtbufOrder, OneOfHolder.OneOfChoice.cString("s"), "string"),
            Triple(
                ConformanceGoldens.OneOfInnerKtbufOrder,
                OneOfHolder.OneOfChoice.cInner(AllTypes.Inner(anInt = 3)),
                "message"
            )
        )

        for ((golden, choice, label) in cases) {
            val message = OneOfHolder(lead = 1, choice = choice)

            assertEquals(golden, message.toByteArray().toHex(), "oneof $label encoded bytes")
            assertEquals(message, OneOfHolder.fromByteArray(golden.hexToBytes()), "oneof $label round trip")
        }
    }

    @Test
    fun oneOfSetToZeroIsStillWritten() {
        // oneof has explicit presence: a case set to the zero value must still appear on
        // the wire, otherwise the receiver cannot tell it was set at all.
        oneOf(
            ConformanceGoldens.OneOfIntZero,
            OneOfHolder(choice = OneOfHolder.OneOfChoice.cInt(0)),
            "oneof int set to zero"
        )

        val decoded = OneOfHolder.fromByteArray(ConformanceGoldens.OneOfIntZero.hexToBytes())

        assertNotNull(decoded.choice, "a zero-valued oneof case is still present")
        assertEquals(0, decoded.choice?.getCInt())
    }

    @Test
    fun lastOneOfCaseOnTheWireWins() {
        // Two cases of the same oneof arriving in one message is legal; the last one
        // must win, as it does in every other protobuf implementation.
        val both = ConformanceGoldens.OneOfInt.hexToBytes() + ConformanceGoldens.OneOfString.hexToBytes()

        val decoded = OneOfHolder.fromByteArray(both)

        assertEquals("s", decoded.choice?.getCString(), "the later case wins")
        assertNull(decoded.choice?.getCInt())
    }

    @Test
    fun unknownFieldsSurviveARoundTrip() {
        // Forward compatibility: a field this schema has never seen must be preserved
        // byte-for-byte when the message is re-encoded.
        val withUnknown = AllTypes(fInt32 = 1).toByteArray() +
            byteArrayOf(0xF8.toByte(), 0x2A, 0x7B) // field 685, varint 123

        val decoded = AllTypes.fromByteArray(withUnknown)

        assertEquals(1, decoded.fInt32, "known field still decodes")
        assertNotNull(decoded.unknownFields, "the unknown field was captured")

        val reEncoded = decoded.toByteArray()

        assertEquals(withUnknown.toHex(), reEncoded.toHex(), "unknown field re-emitted verbatim")
    }
}
