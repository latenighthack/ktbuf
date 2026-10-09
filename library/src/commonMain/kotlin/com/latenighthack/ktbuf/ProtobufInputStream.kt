package com.latenighthack.ktbuf

import com.latenighthack.ktbuf.bytes.LinkedByteArray
import com.latenighthack.ktbuf.bytes.MutableLinkedByteArray

/** Limits are shared by every child reader of a message. */
data class ProtobufLimits(
    val maxMessageBytes: Int = 16 * 1024 * 1024,
    val maxDepth: Int = 64,
    val maxWork: Long = 1_000_000
) {
    init { require(maxMessageBytes >= 0 && maxDepth >= 0 && maxWork > 0) { "Invalid protobuf limits" } }
}

class ProtobufLimitException(message: String) : IllegalArgumentException(message)

private class ReadBudget(val limits: ProtobufLimits) {
    private var remaining = limits.maxWork
    fun account(units: Long) {
        require(units >= 0) { "Negative decoding work" }
        if (units > remaining) throw ProtobufLimitException("Protobuf decoding work limit exceeded")
        remaining -= units
    }
}

interface ProtobufReader {
    val currentFieldNumber: Int

    /** Wire type of the last tag. Custom readers must implement this for generated decoders. */
    val currentWireType: UInt
        get() = throw UnsupportedOperationException("This reader does not report protobuf wire types")

    /** Accounts additional copying work performed by generated message merges. */
    fun accountWork(units: Long) { require(units >= 0) }

    fun isByteAvailable(): Boolean

    fun nextField(): Boolean

    fun skipField(): ByteArray

    fun readFloat(): Float

    fun readDouble(): Double

    fun readInt32(): Int

    fun readInt64(): Long

    fun readSInt32(): Int

    fun readSInt64(): Long

    fun readUInt32(): UInt

    fun readUInt64(): ULong

    fun readFixedInt32(): UInt

    fun readFixedInt64(): ULong

    fun readSFixedInt32(): Int

    fun readSFixedInt64(): Long

    fun readBool(): Boolean

    fun readString(): String

    fun readBytes(): ByteArray

    fun <T> readField(builder: (ProtobufReader) -> T): T
}

class ScopedProtobufReader private constructor(
    private val input: LinkedByteArray,
    private val budget: ReadBudget,
    private val depth: Int
) : ProtobufReader {
    constructor(input: LinkedByteArray, limits: ProtobufLimits = ProtobufLimits()) : this(input, ReadBudget(limits), 0) {
        if (input.size > limits.maxMessageBytes) throw ProtobufLimitException("Protobuf message byte limit exceeded")
    }
    override fun accountWork(units: Long) = budget.account(units)

    private val tempBuffer = ByteArray(8)

    override var currentWireType: UInt = 0U
        private set

    override var currentFieldNumber: Int = 0
        get() = field
        private set(value) {
            field = value
        }

    override fun isByteAvailable(): Boolean {
        return input.size > 0
    }

    override fun nextField(): Boolean {
        if (input.size <= 0) {
            return false
        }

        readTag()
        require(currentWireType != 4U) { "Unexpected end-group tag" }
        return true
    }

    private fun readTag() {
        val tag = readVarInt()
        require(tag <= UInt.MAX_VALUE.toULong() && tag shr 3 != 0UL && tag and 7UL <= 5UL) {
            "Invalid protobuf tag"
        }
        currentFieldNumber = (tag shr 3).toInt()
        currentWireType = (tag and 7UL).toUInt()
    }

    override fun skipField(): ByteArray {
        val result = MutableLinkedByteArray(32, maxSize = budget.limits.maxMessageBytes)
        skipFieldInto(ScopedProtobufWriter(result, ProtobufOutputLimits(budget.limits.maxMessageBytes, budget.limits.maxDepth, budget.limits.maxWork)), 0)
        return result.toByteArray()
    }

    private fun skipFieldInto(writer: ProtobufWriter, groupDepth: Int) {
        when (currentWireType) {
            ProtoConstants.WireType.varInt -> {
                writer.encode(readVarInt(), currentFieldNumber)
            }
            ProtoConstants.WireType.fixed32 -> {
                writer.encodeFixed(readFixedInt32(), currentFieldNumber)
            }
            ProtoConstants.WireType.fixed64 -> {
                writer.encodeFixed(readFixedInt64(), currentFieldNumber)
            }
            ProtoConstants.WireType.lengthDelimited -> {
                writer.encode(readBytes(), currentFieldNumber)
            }
            3U -> {
                if (depth + groupDepth >= budget.limits.maxDepth) throw ProtobufLimitException("Group nesting limit exceeded")
                val groupNumber = currentFieldNumber
                writer.encode((groupNumber.toULong() shl 3) or 3UL, null)
                while (true) {
                    require(isByteAvailable()) { "Missing end-group tag" }
                    readTag()
                    if (currentWireType == 4U) {
                        require(currentFieldNumber == groupNumber) { "Mismatched end-group tag" }
                        writer.encode((groupNumber.toULong() shl 3) or 4UL, null)
                        currentFieldNumber = groupNumber
                        currentWireType = 3U
                        break
                    }
                    skipFieldInto(writer, groupDepth + 1)
                }
            }
            else -> error("Unexpected end-group tag")
        }

    }

    override fun readFloat(): Float = Float.fromBits(readFixedInt32().toInt())

    override fun readDouble(): Double = Double.fromBits(readFixedInt64().toLong())

    override fun readInt32(): Int = readVarInt().toInt()

    override fun readInt64(): Long = readVarInt().toLong()

    override fun readSInt32(): Int {
        val unsigned = readVarInt()
        var x = unsigned.toUInt() shr 1
        if ((unsigned.toUInt() and 1U) != 0U) {
            x = x.inv()
        }
        return x.toInt()
    }

    override fun readSInt64(): Long {
        val unsigned = readVarInt()
        var x = unsigned shr 1
        if ((unsigned and 1UL) != 0UL) {
            x = x.inv()
        }
        return x.toLong()
    }

    override fun readUInt32(): UInt = readVarInt().toUInt()

    override fun readUInt64(): ULong = readVarInt()

    override fun readFixedInt32(): UInt {
        accountWork(1)
        input.readAdvance(tempBuffer, length = 4)

        return (tempBuffer[0].toUByte().toUInt() shl 0) or
                (tempBuffer[1].toUByte().toUInt() shl 8) or
                (tempBuffer[2].toUByte().toUInt() shl 16) or
                (tempBuffer[3].toUByte().toUInt() shl 24)
    }

    override fun readFixedInt64(): ULong {
        accountWork(1)
        input.readAdvance(tempBuffer, length = 8)

        return (tempBuffer[0].toUByte().toULong() shl 0) or
            (tempBuffer[1].toUByte().toULong() shl 8) or
            (tempBuffer[2].toUByte().toULong() shl 16) or
            (tempBuffer[3].toUByte().toULong() shl 24) or
            (tempBuffer[4].toUByte().toULong() shl 32) or
            (tempBuffer[5].toUByte().toULong() shl 40) or
            (tempBuffer[6].toUByte().toULong() shl 48) or
            (tempBuffer[7].toUByte().toULong() shl 56)
    }

    override fun readSFixedInt32(): Int  = readFixedInt32().toInt()

    override fun readSFixedInt64(): Long = readFixedInt64().toLong()

    override fun <T> readField(builder: (ProtobufReader) -> T): T {
        if (depth >= budget.limits.maxDepth) throw ProtobufLimitException("Message nesting limit exceeded")
        val expectedLength = readLength()

        return builder(ScopedProtobufReader(input.subarray(0, expectedLength), budget, depth + 1)).also {
            input.advance(expectedLength)
        }
    }

    override fun readBool(): Boolean = readVarInt() != 0UL

    override fun readString(): String {
        return readBytes().decodeToString(throwOnInvalidSequence = true)
    }

    override fun readBytes(): ByteArray {
        val len = readLength()
        return input.readAdvance(length = len)
    }

    private fun readLength(): Int {
        val length = readVarInt()
        require(length <= Int.MAX_VALUE.toULong() && length <= input.size.toULong()) {
            "Length-delimited field exceeds its message boundary"
        }
        return length.toInt()
    }

    private fun nextByte(): Byte {
        if (input.size == 0) {
            throw IndexOutOfBoundsException("Message length exceeded")
        }

        input.readAdvance(tempBuffer, length = 1)

        return tempBuffer[0]
    }

    private fun readVarInt(): ULong {
        accountWork(1)
        var result = 0UL
        for (index in 0 until 10) {
            val byte = nextByte().toUByte().toUInt()
            require(index < 9 || byte <= 1U) { "Varint exceeds 64 bits" }
            result = result or ((byte and 0x7fU).toULong() shl (index * 7))
            if (byte and 0x80U == 0U) return result
        }
        error("Unterminated varint")
    }

}

class ProtobufInputStream(
    private val bufferSize: Int = 2024,
    private val limits: ProtobufLimits = ProtobufLimits()
) {
    private val input = MutableLinkedByteArray(minOf(bufferSize, limits.maxMessageBytes), maxSize = limits.maxMessageBytes)
    private var consumed = 0

    fun addBytes(bytes: ByteArray) {
        if (bytes.size > limits.maxMessageBytes - input.size) throw ProtobufLimitException("Protobuf message byte limit exceeded")
        if (consumed > 0 && (consumed >= maxOf(input.size, minOf(bufferSize, limits.maxMessageBytes)) ||
                consumed.toLong() + input.size + bytes.size > limits.maxMessageBytes)) {
            val unread = input.toByteArray()
            input.clear()
            input.write(unread)
            consumed = 0
        }
        input.insert(bytes)
    }

    fun <T> read(builder: (ProtobufReader) -> T): T {
        val before = input.size
        try { return builder(ScopedProtobufReader(input, limits)) }
        finally {
            consumed += before - input.size
            if (input.size == 0) { input.clear(); consumed = 0 }
        }
    }
}
