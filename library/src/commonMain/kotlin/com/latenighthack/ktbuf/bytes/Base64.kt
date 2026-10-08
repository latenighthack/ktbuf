package com.latenighthack.ktbuf.bytes

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
private val BASE64_INVERSE_ALPHABET = IntArray(256) { BASE64_ALPHABET.indexOf(it.toChar()) }

/** RFC 4648 Base64URL, without padding. Invalid characters and noncanonical pad bits fail. */
fun String.fromBase64UrlString(): ByteArray = decodeBase64Url(length) { this[it].code }

/** RFC 4648 Base64URL, without padding. */
fun ByteArray.toBase64UrlString(): String {
    val count = (size.toLong() * 8 + 5) / 6
    require(count <= Int.MAX_VALUE) { "Base64URL output exceeds the string size limit" }
    val output = CharArray(count.toInt())
    var source = 0
    var target = 0
    while (source < size) {
        val remaining = size - source
        val value = ((this[source].toInt() and 255) shl 16) or
            (if (remaining > 1) (this[source + 1].toInt() and 255) shl 8 else 0) or
            (if (remaining > 2) this[source + 2].toInt() and 255 else 0)
        output[target++] = BASE64_ALPHABET[value ushr 18]
        output[target++] = BASE64_ALPHABET[(value ushr 12) and 63]
        if (remaining > 1) output[target++] = BASE64_ALPHABET[(value ushr 6) and 63]
        if (remaining > 2) output[target++] = BASE64_ALPHABET[value and 63]
        source += minOf(3, remaining)
    }
    return output.concatToString()
}

/** Decodes ASCII Base64URL without padding, with the same validation as the String API. */
fun ByteArray.decodeBase64Url(): ByteArray = decodeBase64Url(size) { this[it].toInt() and 255 }

private inline fun decodeBase64Url(length: Int, character: (Int) -> Int): ByteArray {
    require(length % 4 != 1) { "Invalid Base64URL length" }
    for (index in 0 until length) {
        val code = character(index)
        require(code in 0..255 && BASE64_INVERSE_ALPHABET[code] >= 0) { "Invalid Base64URL character" }
    }
    if (length % 4 != 0) {
        val last = BASE64_INVERSE_ALPHABET[character(length - 1)]
        val mask = if (length % 4 == 2) 15 else 3
        require(last and mask == 0) { "Noncanonical Base64URL pad bits" }
    }
    val output = ByteArray((length / 4) * 3 + if (length % 4 == 0) 0 else length % 4 - 1)
    var accumulator = 0
    var bits = 0
    var target = 0
    for (index in 0 until length) {
        accumulator = (accumulator shl 6) or BASE64_INVERSE_ALPHABET[character(index)]
        bits += 6
        if (bits >= 8) {
            bits -= 8
            output[target++] = (accumulator ushr bits).toByte()
        }
    }
    return output
}

@Deprecated("This API uses Base64URL without padding; use fromBase64UrlString", ReplaceWith("fromBase64UrlString()"))
fun String.fromBase64String(): ByteArray = fromBase64UrlString()

@Deprecated("This API uses Base64URL without padding; use toBase64UrlString", ReplaceWith("toBase64UrlString()"))
fun ByteArray.toBase64String(): String = toBase64UrlString()

@Deprecated("This API uses Base64URL without padding; use decodeBase64Url", ReplaceWith("decodeBase64Url()"))
fun ByteArray.decodeBase64(): ByteArray = decodeBase64Url()
