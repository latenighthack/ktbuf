package com.latenighthack.ktbuf

import com.latenighthack.ktbuf.bytes.MutableLinkedByteArray
import com.latenighthack.ktbuf.proto.Enum
import kotlin.experimental.or

internal object ProtoConstants {
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

class ProtobufOutputStream(bufferSize: Int = 8192) {
    private val output = MutableLinkedByteArray(bufferSize)
    private val writer = ScopedProtobufWriter(output)

    fun write(encoder: (ProtobufWriter) -> Unit) {
        encoder(writer)
    }

    fun toByteArray(): ByteArray {
        return output.toByteArray()
    }
}

open class ScopedProtobufWriter(private val output: MutableLinkedByteArray) : ProtobufWriter {
    private val tempBuffer = ByteArray(12)
    override var length: Int = 0

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
        encode(value.encodeToByteArray(), fieldNumber)
    }

    override fun encode(value: ByteArray, fieldNumber: Int?) {
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
        val messageLength = with(ScopedProtobufWriter(output)) {
            builder()
            length
        }

        // Check to see if the length can be serialized into a single byte. If not, add the
        // remaining bytes to the buffer.
        val varintSize = sizeofVarint32(messageLength)
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

        length += varintSize - 1 + messageLength
    }

    override fun encodeRaw(value: ByteArray) {
        writeBytes(value)
    }

    private fun fieldTag(fieldNumber: Int, wireType: UInt): ULong {
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
        output.write(byte)
        length++
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
        output.write(value, length = sourceLength)
        length += sourceLength
    }
}
