package com.latenighthack.ktbuf.bytes

import kotlin.math.max

/** Offsets address the unread portion. reset rewinds; clear discards all data. */
internal class ConcreteLinkedByteArray(bufferSize: Int = defaultBufferSize, private val maxSize: Int = DEFAULT_BYTE_ARRAY_LIMIT) : MutableLinkedByteArray {
    internal companion object {
        const val defaultBufferSize = 10 * 2048
    }
    private var buffer = ByteArray(minOf(bufferSize, maxSize).also { require(bufferSize >= 0 && maxSize >= 0) })
    private var cachedSize = 0
    private var tipOffset = 0
    override val size: Int get() = cachedSize - tipOffset

    private fun capacity(target: Int) {
        if (target > maxSize) throw ByteArrayLimitException("Byte buffer limit exceeded")
        if (target > buffer.size) {
            val grown = (buffer.size.toLong() * 4 / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            buffer = buffer.copyOf(max(target, grown).coerceAtMost(maxSize))
        }
    }
    override fun write(byte: Byte, destinationOffset: Int) = write(byteArrayOf(byte), 0, destinationOffset, 1)
    override fun write(bytes: ByteArray, sourceOffset: Int, destinationOffset: Int, length: Int) {
        checkRange(bytes.size, sourceOffset, length)
        checkRange(size, destinationOffset, 0)
        if (length.toLong() + tipOffset + destinationOffset > maxSize) throw ByteArrayLimitException("Byte buffer limit or size overflow")
        val destination = tipOffset + destinationOffset
        val target = max(cachedSize, destination + length)
        capacity(target)
        bytes.copyInto(buffer, destination, sourceOffset, sourceOffset + length)
        cachedSize = target
    }
    override fun insert(bytes: ByteArray, sourceOffset: Int, destinationOffset: Int, length: Int) {
        checkRange(bytes.size, sourceOffset, length)
        checkRange(size, destinationOffset, 0)
        if (length.toLong() + cachedSize > maxSize) throw ByteArrayLimitException("Byte buffer limit or size overflow")
        val destination = tipOffset + destinationOffset
        val target = cachedSize + length
        capacity(target)
        buffer.copyInto(buffer, destination + length, destination, cachedSize)
        bytes.copyInto(buffer, destination, sourceOffset, sourceOffset + length)
        cachedSize = target
    }
    override fun reset() { tipOffset = 0 }
    override fun clear() { cachedSize = 0; tipOffset = 0 }
    override fun toByteArray(): ByteArray = read()
    override fun copyTo(bytes: ByteArray, destinationOffset: Int, offset: Int, length: Int) {
        checkRange(size, offset, length)
        checkRange(bytes.size, destinationOffset, length)
        buffer.copyInto(bytes, destinationOffset, tipOffset + offset, tipOffset + offset + length)
    }
    override fun read(bytes: ByteArray, destinationOffset: Int, length: Int) = copyTo(bytes, destinationOffset, 0, length)
    override fun read(length: Int): ByteArray {
        checkRange(size, 0, length)
        return buffer.copyOfRange(tipOffset, tipOffset + length)
    }
    override fun readAdvance(bytes: ByteArray, destinationOffset: Int, length: Int) {
        read(bytes, destinationOffset, length); advance(length)
    }
    override fun readAdvance(length: Int): ByteArray = read(length).also { advance(length) }
    override fun advance(length: Int) { checkRange(size, 0, length); tipOffset += length }
    override fun subarray(offset: Int, length: Int): LinkedByteArray = LinkedByteSubarray(this, offset, length)
}

internal fun checkRange(size: Int, offset: Int, length: Int) {
    require(offset >= 0 && length >= 0 && offset <= size && length <= size - offset) { "Range exceeds available bytes" }
}

class LinkedByteSubarray(private val parent: LinkedByteArray, private val offset: Int, private val length: Int) : LinkedByteArray {
    init { checkRange(parent.size, offset, length) }
    private var progress = 0
    override val size: Int get() = length - progress
    override fun reset() { progress = 0 }
    override fun clear() { progress = length }
    override fun toByteArray(): ByteArray = read()
    override fun copyTo(bytes: ByteArray, destinationOffset: Int, offset: Int, length: Int) {
        checkRange(size, offset, length)
        checkRange(bytes.size, destinationOffset, length)
        parent.copyTo(bytes, destinationOffset, this.offset + progress + offset, length)
    }
    override fun read(bytes: ByteArray, destinationOffset: Int, length: Int) = copyTo(bytes, destinationOffset, 0, length)
    override fun read(length: Int): ByteArray {
        checkRange(size, 0, length)
        return ByteArray(length).also { read(it) }
    }
    override fun readAdvance(length: Int): ByteArray = read(length).also { advance(length) }
    override fun readAdvance(bytes: ByteArray, destinationOffset: Int, length: Int) { read(bytes, destinationOffset, length); advance(length) }
    override fun advance(length: Int) { checkRange(size, 0, length); progress += length }
    override fun subarray(offset: Int, length: Int): LinkedByteArray {
        checkRange(size, offset, length)
        return LinkedByteSubarray(parent, this.offset + progress + offset, length)
    }
}
