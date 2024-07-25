package com.latenighthack.ktbuf.bytes

import kotlin.math.min

interface LinkedByteArray {

    val size: Int

    fun reset()

    fun clear()

    fun toByteArray(): ByteArray

    fun copyTo(bytes: ByteArray, destinationOffset: Int = 0, offset: Int = 0, length: Int = min(size - offset, bytes.size))

    fun read(bytes: ByteArray, destinationOffset: Int = 0, length: Int = bytes.size - destinationOffset)

    fun read(length: Int = size): ByteArray

    fun readAdvance(bytes: ByteArray, destinationOffset: Int = 0, length: Int = bytes.size - destinationOffset)

    fun readAdvance(length: Int = size): ByteArray

    fun advance(length: Int)

    fun subarray(offset: Int, length: Int): LinkedByteArray
}

interface MutableLinkedByteArray : LinkedByteArray {

    fun write(byte: Byte, destinationOffset: Int = size)

    fun write(bytes: ByteArray, sourceOffset: Int = 0, destinationOffset: Int = size, length: Int = bytes.size - sourceOffset)

    fun insert(bytes: ByteArray, sourceOffset: Int = 0, destinationOffset: Int = size, length: Int = bytes.size - sourceOffset)
}

@Suppress("FunctionName")
fun LinkedByteArray(bufferSize: Int = ConcreteLinkedByteArray.defaultBufferSize): LinkedByteArray {
    return ConcreteLinkedByteArray(bufferSize)
}

@Suppress("FunctionName")
fun MutableLinkedByteArray(bufferSize: Int = ConcreteLinkedByteArray.defaultBufferSize): MutableLinkedByteArray {
    return ConcreteLinkedByteArray(bufferSize)
}

fun linkedByteArrayOf(vararg bytes: Byte): LinkedByteArray = mutableLinkedByteArrayOf(*bytes)

fun mutableLinkedByteArrayOf(vararg bytes: Byte): LinkedByteArray = ConcreteLinkedByteArray()
    .apply {
        write(bytes)
    }
