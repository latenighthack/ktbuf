package com.latenighthack.ktbuf
import com.latenighthack.ktbuf.bytes.mutableLinkedByteArrayOf
import kotlin.test.*
class ParsingLimitRegressionTests {
    private fun parse(bytes: ByteArray,limits: ProtobufLimits,block:(ProtobufReader)->Unit) {
        val stream=ProtobufInputStream(limits=limits); stream.addBytes(bytes); stream.read(block)
    }
    @Test fun bytesAreLimitedBeforeBufferingAndOversizedAddsLeaveInputIntact() {
        val stream=ProtobufInputStream(limits=ProtobufLimits(maxMessageBytes=2))
        stream.addBytes(byteArrayOf(8))
        assertFailsWith<ProtobufLimitException> { stream.addBytes(byteArrayOf(1,2)) }
        stream.addBytes(byteArrayOf(1))
        assertEquals(1,stream.read { it.nextField(); it.readInt32() })
        assertFailsWith<ProtobufLimitException> { ScopedProtobufReader(mutableLinkedByteArrayOf(1,2,3),ProtobufLimits(maxMessageBytes=2)) }
    }
    @Test fun nestedReadersShareDepthAndWorkLimits() {
        var bytes=byteArrayOf()
        repeat(6) { val previous=bytes; bytes=ProtobufOutputStream.encode { encode(1) { encodeRaw(previous) } } }
        fun descend(r:ProtobufReader) { while(r.nextField()) r.readField(::descend) }
        assertFailsWith<ProtobufLimitException> { parse(bytes,ProtobufLimits(maxDepth=4),::descend) }
        parse(bytes,ProtobufLimits(maxDepth=6),::descend)
        assertFailsWith<ProtobufLimitException> {
            parse(byteArrayOf(10,2,8,1,8,2),ProtobufLimits(maxWork=5)) { r ->
                r.nextField(); r.readField { it.nextField(); it.readInt32() }; r.nextField(); r.readInt32()
            }
        }
    }
    @Test fun incrementalReadsRetainOnlyUnreadDataAndResetTheirWorkBudget() {
        val stream=ProtobufInputStream(1,ProtobufLimits(maxMessageBytes=3,maxWork=1))
        stream.addBytes(byteArrayOf(0,1,2))
        repeat(10000) { index ->
            assertEquals((index%127).toUInt(),stream.read { it.readUInt32() })
            stream.addBytes(byteArrayOf(((index+3)%127).toByte()))
        }
    }
    @Test fun workLimitsCoverValuesGroupsAndGeneratedCopying() {
        val r=ScopedProtobufReader(mutableLinkedByteArrayOf(8,1,8,2),ProtobufLimits(maxWork=3))
        r.nextField(); r.readInt32(); r.nextField()
        assertFailsWith<ProtobufLimitException> { r.readInt32() }
        val copy=ScopedProtobufReader(mutableLinkedByteArrayOf(),ProtobufLimits(maxWork=2))
        copy.accountWork(2)
        assertFailsWith<ProtobufLimitException> { copy.accountWork(1) }
        assertFailsWith<IllegalArgumentException> { copy.accountWork(-1) }
        assertFailsWith<ProtobufLimitException> { parse(byteArrayOf(11,11,12,12),ProtobufLimits(maxDepth=1)) { it.nextField(); it.skipField() } }
    }
}
