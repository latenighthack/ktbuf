package com.latenighthack.ktbuf

import com.latenighthack.ktbuf.bytes.*
import kotlin.test.*

class OutputLimitRegressionTests {
    @Test fun defaultWriterRejectsGrowthBeyondSixteenMiB() {
        val stream = ProtobufOutputStream(0)
        val chunk = ByteArray(1024)
        assertFailsWith<IllegalArgumentException> {
            repeat(16 * 1024 + 1) { stream.write { writer -> writer.encodeRaw(chunk) } }
        }
    }
    @Test fun scopedLengthOverflowRejectsBeforeBufferGrowth() {
        val output = MutableLinkedByteArray(0)
        val writer = ScopedProtobufWriter(output, ProtobufOutputLimits(maxMessageBytes = Int.MAX_VALUE))
        writer.length = Int.MAX_VALUE
        assertFailsWith<IllegalArgumentException> { writer.encodeRaw(byteArrayOf(1)) }
        assertEquals(0, output.size)
    }
    @Test fun tinyFieldBudgetIncludesTagLengthAndPayloadAndPoisonsCaughtFailures() {
        assertContentEquals(byteArrayOf(10, 6) + ByteArray(6), ProtobufOutputStream.encode(ProtobufOutputLimits(8)) { encode(ByteArray(6), 1) })
        val stream = ProtobufOutputStream(limits = ProtobufOutputLimits(8))
        stream.write { writer -> assertFailsWith<ProtobufOutputLimitException> { writer.encode(ByteArray(7), 1) } }
        assertFailsWith<ProtobufOutputLimitException> { stream.toByteArray() }
        assertFailsWith<ProtobufOutputLimitException> { stream.write { it.encodeRaw(byteArrayOf()) } }
    }
    @Test fun nestedWritersShareOutputBudgetAndLengthBackpatchCannotGrowPastIt() {
        assertContentEquals(byteArrayOf(10, 6) + ByteArray(6), ProtobufOutputStream.encode(ProtobufOutputLimits(8)) { encode(1) { encodeRaw(ByteArray(6)) } })
        assertFailsWith<ProtobufOutputLimitException> { ProtobufOutputStream.encode(ProtobufOutputLimits(8)) { encode(1) { encodeRaw(ByteArray(7)) } } }
        val output = MutableLinkedByteArray(maxSize = 130)
        val writer = ScopedProtobufWriter(output, ProtobufOutputLimits(130))
        assertFailsWith<ProtobufOutputLimitException> { writer.encode(1) { encodeRaw(ByteArray(128)) } }
        assertEquals(130, output.size, "length-prefix insertion must reject before buffer growth")
    }
    @Test fun utf8ExpansionIsPreflightedWithoutChangingValidOrReplacementEncoding() {
        for (value in listOf("é", "😀é", "\uD800", "\uDC00", "\uD800x")) {
            val bytes = value.encodeToByteArray()
            assertContentEquals(byteArrayOf(10, bytes.size.toByte()) + bytes,
                ProtobufOutputStream.encode(ProtobufOutputLimits(bytes.size + 2)) { encode(value, 1) })
        }
        assertFailsWith<ProtobufOutputLimitException> { ProtobufOutputStream.encode(ProtobufOutputLimits(10)) { encode("é".repeat(8), 1) } }
    }
    @Test fun byteStorageHasAnExplicitFiniteBudgetAndFailedGrowthLeavesBytesIntact() {
        val output = MutableLinkedByteArray(bufferSize = Int.MAX_VALUE, maxSize = 2)
        output.write(byteArrayOf(1, 2))
        assertFailsWith<ByteArrayLimitException> { output.insert(byteArrayOf(3), destinationOffset = 1) }
        assertFailsWith<ByteArrayLimitException> { output.write(byteArrayOf(3)) }
        assertContentEquals(byteArrayOf(1, 2), output.toByteArray())
        val zero = MutableLinkedByteArray(bufferSize = Int.MAX_VALUE, maxSize = 0)
        assertFailsWith<ByteArrayLimitException> { zero.write(1) }
        assertEquals(0, zero.size)
        assertFailsWith<ProtobufOutputLimitException> { ProtobufOutputStream(bufferSize = Int.MAX_VALUE, limits = ProtobufOutputLimits(0)).write { it.encodeRaw(byteArrayOf(1)) } }
    }
    @Test fun depthAndWorkLimitsAreSharedAndDefaultSignaturesRemainUsable() {
        assertFailsWith<ProtobufOutputLimitException> { ProtobufOutputStream.encode(ProtobufOutputLimits(maxDepth = 1)) { encode(1) { encode(1) {} } } }
        assertFailsWith<ProtobufOutputLimitException> { ProtobufOutputStream.encode(ProtobufOutputLimits(maxWork = 2)) { repeat(3) { encodeRaw(byteArrayOf(1)) } } }
        assertContentEquals(byteArrayOf(8, 1), ProtobufOutputStream.encode { encode(1, 1) })
        assertContentEquals(byteArrayOf(), ProtobufOutputStream().toByteArray())
    }

}
