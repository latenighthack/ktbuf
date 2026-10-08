package com.latenighthack.ktbuf.conformance
import com.latenighthack.ktbuf.*
import com.latenighthack.ktbuf.bytes.*
import com.latenighthack.ktbuf.conformance.v1.*
import review.v1.BytesMessage
import kotlin.test.*

class AdversarialWireTests {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun parse(s: String) = AllTypes.fromByteArray(hex(s))
    @Test fun rejectMissingVarint() { assertFails { parse("08") } }
    @Test fun rejectUnterminatedVarint() { assertFails { parse("0880") } }
    @Test fun rejectElevenByteVarint() { assertFails { parse("08" + "80".repeat(10) + "00") } }
    @Test fun rejectTruncatedFixed32() { assertFails { parse("3d01") } }
    @Test fun rejectTruncatedBytes() { assertFails { parse("7a0401") } }
    @Test fun rejectWrappedLength() { assertFails { parse("728080808010") } }
    @Test fun rejectFieldZero() { assertFails { parse("0000") } }
    @Test fun rejectInvalidWireType() { assertFails { parse("0e00") } }
    @Test fun wrongWireTypeMustNotSetKnownField() { assertEquals(0, parse("0a0101").fInt32) }
    @Test fun unknownGroupMustNotSetTopLevelField() { assertEquals(0, parse("a3060863a406").fInt32) }
    @Test fun nestedReadsStayInsideDeclaredLength() { assertFails { parse("8a01020a040807") } }
    @Test fun nonzeroBooleanIsTrue() { assertTrue(parse("6802").fBool) }
    @Test fun unpackedNumericsAreAccepted() { assertEquals(listOf(1, 2), parse("900101900102").rInt32) }
    @Test fun duplicateMessagesMerge() {
        val bytes = AllTypes(fInner=AllTypes.Inner(str="a")).toByteArray() + AllTypes(fInner=AllTypes.Inner(anInt=7)).toByteArray()
        assertEquals(AllTypes.Inner(str="a", anInt=7), AllTypes.fromByteArray(bytes).fInner)
    }
    @Test fun sameOneofMessageMerges() {
        val bytes=OneOfHolder(choice=OneOfHolder.OneOfChoice.cInner(AllTypes.Inner(str="a"))).toByteArray()+OneOfHolder(choice=OneOfHolder.OneOfChoice.cInner(AllTypes.Inner(anInt=7))).toByteArray()
        assertEquals(AllTypes.Inner(str="a",anInt=7),OneOfHolder.fromByteArray(bytes).choice?.getCInner())
    }
    @Test fun negativeZeroSurvives() {
        assertEquals((-0.0f).toRawBits(), AllTypes.fromByteArray(AllTypes(fFloat=-0.0f).toByteArray()).fFloat.toRawBits())
    }
    @Test fun rejectInvalidUtf8() { assertFails { parse("7201ff") } }
    @Test fun unknownFieldEqualityIsSymmetric() {
        val a=AllTypes(); val b=AllTypes(unknownFields=hex("f82a7b"))
        assertEquals(a.equals(b), b.equals(a))
    }
    @Test fun repeatedBytesEqualObjectsHaveEqualHashes() {
        val a=BytesMessage(chunks=listOf(byteArrayOf(1))); val b=BytesMessage(chunks=listOf(byteArrayOf(1)))
        assertEquals(a,b); assertEquals(a.hashCode(),b.hashCode())
    }
    @Test fun oneofBytesUseValueEquality() {
        assertEquals(BytesMessage(choice=BytesMessage.OneOfChoice.payload(byteArrayOf(1))),BytesMessage(choice=BytesMessage.OneOfChoice.payload(byteArrayOf(1))))
    }
    @Test fun signedZeroEqualObjectsHaveEqualHashes() {
        val a=AllTypes(fFloat=0.0f); val b=AllTypes(fFloat=-0.0f)
        if(a.equals(b)) assertEquals(a.hashCode(), b.hashCode())
    }
    @Test fun enumEqualsUnrelatedObjectIsFalse() { val a: AllTypes.Kind = AllTypes.Kind.A; assertFalse(a.equals("A")) }
    @Test fun inputStreamCanBeRefilledAfterRead() {
        val s=ProtobufInputStream(); s.addBytes(hex("0801"))
        assertEquals(1,s.read { AllTypes.readFrom(it) }.fInt32)
        s.addBytes(hex("0802"))
        assertEquals(2,s.read { AllTypes.readFrom(it) }.fInt32)
    }
    @Test fun byteBufferResetRewindsView() {
        val b=MutableLinkedByteArray(); b.write(byteArrayOf(1,2)); val sub=b.subarray(0,2)
        sub.readAdvance(1); sub.reset(); assertEquals(2,sub.size)
    }
    @Test fun byteBufferCopyRespectsDestinationOffset() {
        val b=MutableLinkedByteArray(); b.write(byteArrayOf(1,2)); val out=ByteArray(2)
        b.copyTo(out,destinationOffset=1); assertContentEquals(byteArrayOf(0,1),out)
    }
}
