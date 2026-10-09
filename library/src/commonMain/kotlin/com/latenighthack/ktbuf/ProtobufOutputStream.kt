package com.latenighthack.ktbuf

import com.latenighthack.ktbuf.bytes.MutableLinkedByteArray
import com.latenighthack.ktbuf.proto.Enum
import kotlin.experimental.or

object ProtoConstants {
    object Flags {
        const val TagType = 7
        const val TagTypeBitCount = 3
    }

    object WireType {
        const val varInt: UInt = 0U
        const val fixed64: UInt = 1U
        const val lengthDelimited: UInt = 2U
        const val fixed32: UInt = 5U
    }

    fun fieldNumberFromTag(tag: Int): Int {
        return (tag.toUInt() shr Flags.TagTypeBitCount).toInt()
    }

    fun wireTypeFromTag(tag: UInt): UInt {
        return tag and Flags.TagType.toUInt()
    }
}

interface ProtobufWriter {
    val length: Int

    fun encode(value: Float, fieldNumber: Int?)

    fun encode(value: Double, fieldNumber: Int?)

    fun encode(value: Int, fieldNumber: Int?)

    fun encodeSInt(value: Int, fieldNumber: Int?)

    fun encodeFixed(value: Int, fieldNumber: Int?)

    fun encode(value: Long, fieldNumber: Int?)

    fun encodeSInt(value: Long, fieldNumber: Int?)

    fun encodeFixed(value: Long, fieldNumber: Int?)

    fun encode(value: UInt, fieldNumber: Int?)

    fun encodeFixed(value: UInt, fieldNumber: Int?)

    fun encode(value: ULong, fieldNumber: Int?)

    fun encodeFixed(value: ULong, fieldNumber: Int?)

    fun encode(value: Boolean, fieldNumber: Int?)

    fun encode(value: String, fieldNumber: Int?)

    fun encode(value: ByteArray, fieldNumber: Int?)

    fun <T : Enum> encode(value: T, fieldNumber: Int?)

    fun encode(fieldNumber: Int, builder: ProtobufWriter.() -> Unit)

    fun encodeRaw(value: ByteArray)
}

/** Budgets apply to the entire output and are shared by all nested message writers. */
data class ProtobufOutputLimits(
    val maxMessageBytes: Int = 16 * 1024 * 1024,
    val maxDepth: Int = 64,
    val maxWork: Long = 1_000_000,
) {
    init { require(maxMessageBytes >= 0 && maxDepth >= 0 && maxWork > 0) { "Invalid protobuf output limits" } }
}
class ProtobufOutputLimitException(message: String) : IllegalArgumentException(message)

private val utf8ReplacementSize = "\uD800".encodeToByteArray().size

private class OutputBudget(val limits: ProtobufOutputLimits) {
    private var remaining = limits.maxWork
    private var failed: Throwable? = null
    fun ensureValid() { failed?.let { throw it } }
    fun poison(failure: Throwable) { if (failed == null) failed = failure }
    fun reject(message: String): Nothing {
        val failure = ProtobufOutputLimitException(message); poison(failure); throw failure
    }
    fun checkSize(current: Int, additional: Long): Int {
        ensureValid()
        if (current < 0 || additional < 0 || additional > limits.maxMessageBytes.toLong() - current)
            reject("Protobuf output byte limit or size overflow")
        return (current.toLong() + additional).toInt()
    }
    fun account() { ensureValid(); if (remaining == 0L) reject("Protobuf encoding work limit exceeded"); remaining-- }
    fun child(depth: Int) { ensureValid(); if (depth >= limits.maxDepth) reject("Protobuf output nesting limit exceeded") }
}

class ProtobufOutputStream(bufferSize: Int = 8192, limits: ProtobufOutputLimits) {
    // Preserve existing constructor/default and companion encode JVM signatures.
    constructor(bufferSize: Int = 8192) : this(bufferSize, ProtobufOutputLimits())
    companion object {
        fun encode(writer: ProtobufWriter.() -> Unit): ByteArray = encode(ProtobufOutputLimits(), writer)
        fun encode(limits: ProtobufOutputLimits, writer: ProtobufWriter.() -> Unit): ByteArray =
            ProtobufOutputStream(limits = limits).apply { write { it.writer() } }.toByteArray()
    }

    private val output = MutableLinkedByteArray(bufferSize, maxSize = limits.maxMessageBytes)
    private val writer = ScopedProtobufWriter(output, limits)

    fun write(encoder: (ProtobufWriter) -> Unit) {
        writer.ensureValid()
        val initialSize = output.size
        try { encoder(writer) } catch (failure: Throwable) {
            // Preflight validation leaves an unchanged output usable. Budget failures
            // poison themselves; failures after a partial write remain terminal.
            if (output.size != initialSize) writer.poison(failure)
            throw failure
        }
    }

    fun toByteArray(): ByteArray {
        writer.ensureValid()
        return output.toByteArray()
    }
}

open class ScopedProtobufWriter private constructor(
    private val output: MutableLinkedByteArray, private val budget: OutputBudget, private val depth: Int,
) : ProtobufWriter {
    constructor(output: MutableLinkedByteArray) : this(output, ProtobufOutputLimits())
    constructor(output: MutableLinkedByteArray, limits: ProtobufOutputLimits) : this(output, OutputBudget(limits), 0)
    internal fun ensureValid() = budget.ensureValid()
    internal fun poison(failure: Throwable) = budget.poison(failure)
    private val tempBuffer = ByteArray(12)
    override var length: Int = 0
        set(value) { budget.checkSize(value, 0); field = value }

    override fun encode(value: Float, fieldNumber: Int?) {
        if (fieldNumber != null) {
            writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.fixed32))
        }

        writeFixed32(value.toRawBits().toUInt())
    }

    override fun encode(value: Double, fieldNumber: Int?) {
        if (fieldNumber != null) {
            writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.fixed64))
        }

        writeFixed64(value.toRawBits().toULong())
    }

    override fun encode(value: Int, fieldNumber: Int?) {
        if (fieldNumber != null) {
            writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.varInt))
        }

        writeVarint(value.toULong())
    }

    override fun encodeSInt(value: Int, fieldNumber: Int?) {
        if (fieldNumber != null) {
            writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.varInt))
        }

        writeVarint(zigZag(value.toLong()))
    }

    override fun encodeFixed(value: Int, fieldNumber: Int?) {
        if (fieldNumber != null) {
            writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.fixed32))
        }

        writeFixed32(value.toUInt())
    }

    override fun encode(value: Long, fieldNumber: Int?) {
        if (fieldNumber != null) {
            writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.varInt))
        }

        writeVarint(value.toULong())
    }

    override fun encodeSInt(value: Long, fieldNumber: Int?) {
        if (fieldNumber != null) {
            writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.varInt))
        }

        writeVarint(zigZag(value))
    }

    override fun encodeFixed(value: Long, fieldNumber: Int?) {
        if (fieldNumber != null) {
            writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.fixed64))
        }

        writeFixed64(value.toULong())
    }

    override fun encode(value: UInt, fieldNumber: Int?) {
        if (fieldNumber != null) {
            writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.varInt))
        }

        writeVarint(value.toULong())
    }

    override fun encodeFixed(value: UInt, fieldNumber: Int?) {
        if (fieldNumber != null) {
            writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.fixed32))
        }

        writeFixed32(value)
    }

    override fun encode(value: ULong, fieldNumber: Int?) {
        if (fieldNumber != null) {
            writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.varInt))
        }

        writeVarint(value)
    }

    override fun encodeFixed(value: ULong, fieldNumber: Int?) {
        if (fieldNumber != null) {
            writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.fixed64))
        }

        writeFixed64(value)
    }

    override fun encode(value: Boolean, fieldNumber: Int?) {
        if (fieldNumber != null) {
            writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.varInt))
        }

        writeVarint(if (value) 1UL else 0UL)
    }

    override fun encode(value: String, fieldNumber: Int?) {
        // Count UTF-8 bytes before encoding; even a short UTF-16 string can expand past the budget.
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            bytes += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() && index < value.length && value[index].isLowSurrogate() -> { index++; 4 }
                char.isSurrogate() -> utf8ReplacementSize
                else -> 3
            }
            budget.checkSize(output.size, bytes)
        }
        preflightField(bytes, fieldNumber)
        encode(value.encodeToByteArray(), fieldNumber)
    }

    override fun encode(value: ByteArray, fieldNumber: Int?) {
        preflightField(value.size.toLong(), fieldNumber)
        if (fieldNumber != null) {
            writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.lengthDelimited))
        }

        writeVarint(value.size.toULong())
        writeBytes(value)
    }

    override fun <T : Enum> encode(value: T, fieldNumber: Int?) {
        if (fieldNumber != null) {
            writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.varInt))
        }

        writeVarint(value.value.toULong())
    }

    override fun encode(fieldNumber: Int, builder: ProtobufWriter.() -> Unit) {
        budget.child(depth)
        budget.checkSize(output.size, varintSize(fieldTag(fieldNumber, ProtoConstants.WireType.lengthDelimited)).toLong() + 1)
        writeVarint(fieldTag(fieldNumber, ProtoConstants.WireType.lengthDelimited))

        // We optimistically guess that most messages size are less than 127 bytes, and so
        // we reserve a single byte for the length encoding.
        //
        // However, in the case that this is not true, we will have to double back and add
        // extra space. To do this, we mark in the output where the length encoding begins.
        // We can then inject extra bytes at this location if necessary.
        //
        // We write a zero byte to reserve the length encoding in the optimistic case.
        val messageLengthOffset = output.size
        writeByte(0x00)

        // Write the actual message and the get the resultant length.
        val messageLength = with(ScopedProtobufWriter(output, budget, depth + 1)) {
            builder()
            length
        }

        // Check to see if the length can be serialized into a single byte. If not, add the
        // remaining bytes to the buffer.
        val varintSize = sizeofVarint32(messageLength)
        budget.checkSize(output.size, (varintSize - 1).toLong())
        val finalLength = budget.checkSize(length, messageLength.toLong() + varintSize - 1)
        budget.account()
        if (varintSize > 1) {
            output.insert(ByteArray(varintSize - 1), 0, messageLengthOffset)
        }

        var offset = messageLengthOffset
        var pendingWrite = messageLength.toULong()

        do {
            var b = (pendingWrite and 0x7FUL).toByte()
            pendingWrite = pendingWrite shr 7

            if (pendingWrite != 0UL) {
                b = b or 0x80.toByte()
            }

            output.write(b, offset++)
        } while (pendingWrite != 0UL)

        length = finalLength
    }

    override fun encodeRaw(value: ByteArray) {
        writeBytes(value)
    }

    private fun varintSize(value: ULong): Int {
        var remaining = value; var size = 1
        while (remaining >= 128UL) { remaining = remaining shr 7; size++ }
        return size
    }
    private fun preflightField(bytes: Long, fieldNumber: Int?) {
        val tag = if (fieldNumber == null) 0 else varintSize(fieldTag(fieldNumber, ProtoConstants.WireType.lengthDelimited))
        budget.checkSize(output.size, bytes + varintSize(bytes.toULong()) + tag)
    }

    private fun fieldTag(fieldNumber: Int, wireType: UInt): ULong {
        require(fieldNumber in 1..536870911) { "Protobuf field number is outside the valid range: $fieldNumber" }
        return fieldNumber.toULong() shl ProtoConstants.Flags.TagTypeBitCount or wireType.toULong()
    }

    private fun zigZag(value: Long): ULong {
        return ((value shl 1) xor (value shr 63)).toULong()
    }

    private fun sizeofVarint32(value: Int): Int {
        if (value and (-1 shl 7) == 0) return 1
        if (value and (-1 shl 14) == 0) return 2
        if (value and (-1 shl 21) == 0) return 3
        if (value and (-1 shl 28) == 0) return 4
        return 5
    }

    private fun writeByte(byte: Byte) {
        budget.checkSize(output.size, 1)
        val nextLength = budget.checkSize(length, 1)
        budget.account()
        output.write(byte)
        length = nextLength
    }

    private fun writeFixed32(value: UInt) {
        var pendingWrite = value
        var offset = 0

        for (i in 1..4) {
            tempBuffer[offset++] = (pendingWrite and 0xffU).toByte()
            pendingWrite = pendingWrite shr 8
        }

        writeBytes(tempBuffer, offset)
    }

    private fun writeFixed64(value: ULong) {
        var pendingWrite = value
        var offset = 0

        for (i in 1..8) {
            tempBuffer[offset++] = (pendingWrite and 0xffU).toByte()
            pendingWrite = pendingWrite shr 8
        }

        writeBytes(tempBuffer, offset)
    }

    private fun writeVarint(value: ULong) {
        var pendingWrite = value
        var written = 0

        do {
            var b = (pendingWrite and 0x7FUL).toByte()
            pendingWrite = pendingWrite shr 7

            if (pendingWrite != 0UL) {
                b = b or 0x80.toByte()
            }

            tempBuffer[written++] = b

        } while (pendingWrite != 0UL)

        writeBytes(tempBuffer, written)
    }

    private fun writeBytes(value: ByteArray, sourceLength: Int = value.size) {
        budget.checkSize(output.size, sourceLength.toLong())
        val nextLength = budget.checkSize(length, sourceLength.toLong())
        budget.account()
        output.write(value, length = sourceLength)
        length = nextLength
    }
}
