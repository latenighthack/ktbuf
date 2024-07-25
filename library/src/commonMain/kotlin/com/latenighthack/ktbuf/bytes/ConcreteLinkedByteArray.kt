package com.latenighthack.ktbuf.bytes

import kotlin.math.max

internal class ConcreteLinkedByteArray(private val bufferSize: Int = defaultBufferSize) : MutableLinkedByteArray {
    internal companion object {
        const val defaultBufferSize = 10 * 2048
        const val defaultCacheLimit = 10
    }

    private var buffer = ByteArray(bufferSize)
    private var cachedSize = 0
    private var tipOffset = 0

    override fun write(byte: Byte, destinationOffset: Int) {
        val targetSize = max(cachedSize, destinationOffset + 1)

        if (buffer.size < targetSize) {
            buffer = buffer.copyOf(cachedSize * 4 / 3)
        }
        cachedSize = targetSize

        buffer[destinationOffset] = byte
    }

    override fun write(bytes: ByteArray, sourceOffset: Int, destinationOffset: Int, length: Int) {
        val targetSize = max(cachedSize, destinationOffset + length)

        if (buffer.size < targetSize) {
            buffer = buffer.copyOf(cachedSize * 4 / 3)
        }
        cachedSize = targetSize

        bytes.copyInto(buffer, destinationOffset, sourceOffset, length + sourceOffset)
    }

    override fun insert(bytes: ByteArray, sourceOffset: Int, destinationOffset: Int, length: Int) {
        cachedSize += length

        if (buffer.size < cachedSize) {
            buffer = buffer.copyOf(cachedSize * 4 / 3)
        }

        buffer.copyInto(buffer, destinationOffset + length, destinationOffset, cachedSize - length)
        bytes.copyInto(buffer, destinationOffset, sourceOffset, length + sourceOffset)
    }

    override val size: Int
        get() = cachedSize - tipOffset

    override fun reset() {
        tipOffset = 0
    }

    override fun clear() {
        cachedSize = 0
        tipOffset = 0
    }

    override fun toByteArray(): ByteArray {
        return buffer.copyOf(cachedSize)
    }

    override fun copyTo(bytes: ByteArray, destinationOffset: Int, offset: Int, length: Int) {
        buffer.copyInto(bytes, destinationOffset, tipOffset + offset, tipOffset + length + offset)
    }

    override fun read(bytes: ByteArray, destinationOffset: Int, length: Int) {
        copyTo(bytes, destinationOffset, 0, length)
    }

    override fun read(length: Int): ByteArray {
        return buffer.copyOfRange(tipOffset, tipOffset + length)
    }

    override fun readAdvance(bytes: ByteArray, destinationOffset: Int, length: Int) {
        read(bytes, destinationOffset, length)
        advance(length)
    }

    override fun readAdvance(length: Int): ByteArray {
        return read(length).also {
            advance(length)
        }
    }

    override fun advance(length: Int) {
        tipOffset += length
    }

    override fun subarray(offset: Int, length: Int): LinkedByteArray {
        return LinkedByteSubarray(this, offset, length)
    }
}

class LinkedByteSubarray(
    private val parent: LinkedByteArray,
    private val offset: Int,
    private val length: Int
) : LinkedByteArray {

    private var progress = 0

    override val size: Int
        get() = length - progress

    override fun reset() {
        progress = length
    }

    override fun clear() {
        reset()
    }

    override fun toByteArray(): ByteArray = ByteArray(size).also {
        copyTo(it)
    }

    override fun copyTo(bytes: ByteArray, destinationOffset: Int, offset: Int, length: Int) {
        parent.copyTo(bytes, destinationOffset, offset + this.offset + this.progress, length)
    }

    override fun read(bytes: ByteArray, destinationOffset: Int, length: Int) {
        copyTo(bytes, destinationOffset, 0, length)
    }

    override fun read(length: Int): ByteArray = ByteArray(length).also { read(it) }

    override fun readAdvance(length: Int): ByteArray = read(length).also {
        advance(length)
    }

    override fun readAdvance(bytes: ByteArray, destinationOffset: Int, length: Int) {
        read(bytes, destinationOffset, length)
        advance(length)
    }

    override fun advance(length: Int) {
        progress += length
    }

    override fun subarray(offset: Int, length: Int): LinkedByteArray {
        return parent.subarray(offset + this.offset + this.progress, length)
    }
}
