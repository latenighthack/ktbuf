package com.latenighthack.ktbuf.conformance

import kotlin.test.assertEquals

// Goldens are hex string constants rather than test resources because Kotlin/Native
// and Kotlin/JS share no resource-loading API with the JVM; hex compiles everywhere.

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length, got $length" }

    return ByteArray(length / 2) { i ->
        ((this[i * 2].hexDigit() shl 4) or this[i * 2 + 1].hexDigit()).toByte()
    }
}

private fun Char.hexDigit(): Int = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    in 'A'..'F' -> this - 'A' + 10
    else -> throw IllegalArgumentException("not a hex digit: '$this'")
}

fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val builder = StringBuilder(size * 2)

    for (byte in this) {
        val value = byte.toInt() and 0xFF

        builder.append(digits[value shr 4])
        builder.append(digits[value and 0x0F])
    }

    return builder.toString()
}

/**
 * Asserts a generated message encodes to exactly [golden] and that [golden] decodes back
 * to an equal message. Both directions matter: encode-only would miss a decoder that
 * silently drops a field, decode-only would miss an encoder that emits the wrong tag.
 */
fun <T> assertGoldenRoundTrip(
    golden: String,
    message: T,
    encode: (T) -> ByteArray,
    decode: (ByteArray) -> T,
    label: String
) {
    assertEquals(golden, encode(message).toHex(), "$label encoded to the wrong bytes")
    assertEquals(message, decode(golden.hexToBytes()), "$label decoded to the wrong message")
}
