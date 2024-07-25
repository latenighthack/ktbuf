package com.latenighthack.ktbuf

import com.latenighthack.ktbuf.bytes.LinkedByteArray
import com.latenighthack.ktbuf.bytes.MutableLinkedByteArray

interface ProtobufReader {
    val currentFieldNumber: Int

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

class ScopedProtobufReader(
    private val input: LinkedByteArray
) : ProtobufReader {
    private val tempBuffer = ByteArray(8)

    private var currentFieldType: UInt = 0U

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

        val tag = readVarInt()

        currentFieldNumber = ProtoConstants.fieldNumberFromTag(tag.toInt())
        currentFieldType = ProtoConstants.wireTypeFromTag(tag.toUInt())

        return true
    }

    override fun skipField(): ByteArray {
        val result = MutableLinkedByteArray()
        val writer = ScopedProtobufWriter(result)

        when (currentFieldType) {
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
        }

        return result.toByteArray()
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
        input.readAdvance(tempBuffer, length = 4)

        return (tempBuffer[0].toUByte().toUInt() shl 0) or
                (tempBuffer[1].toUByte().toUInt() shl 8) or
                (tempBuffer[2].toUByte().toUInt() shl 16) or
                (tempBuffer[3].toUByte().toUInt() shl 24)
    }

    override fun readFixedInt64(): ULong {
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
        val expectedLength = readInt32()

        return builder(ScopedProtobufReader(input.subarray(0, expectedLength))).also {
            input.advance(expectedLength)
        }
    }

    override fun readBool(): Boolean = readVarInt() == 1UL

    override fun readString(): String {
        return readBytes().decodeToString()
    }

    override fun readBytes(): ByteArray {
        val len = readInt32()
        return input.readAdvance(length = len)
    }

    private fun nextByte(): Byte {
        if (input.size == 0) {
            throw IndexOutOfBoundsException("Message length exceeded")
        }

        input.readAdvance(tempBuffer, length = 1)

        return tempBuffer[0]
    }

    private fun readVarInt(): ULong {
        if (!isByteAvailable()) {
            return 0U
        }

        var shift = 0
        var result = 0UL
        var tmp = nextByte().toUByte()

        while ((tmp and 0x80.toUByte()).toInt() != 0 && shift < 10) {
            result = result or ((tmp and 0x7f.toUByte()).toULong() shl (shift * 7))
            shift++

            if (!isByteAvailable()) {
                break
            }

            tmp = nextByte().toUByte()
        }

        result = result or (tmp.toULong() shl (shift * 7))

        return result
    }
}

class ProtobufInputStream(bufferSize: Int = 2024) {
    private val input = MutableLinkedByteArray(bufferSize)
    private val reader = ScopedProtobufReader(input)

    fun addBytes(bytes: ByteArray) {
        input.insert(bytes)
    }

    fun <T> read(builder: (ProtobufReader) -> T): T {
        return builder(reader)
    }
}
