package com.latenighthack.ktbuf
import com.latenighthack.ktbuf.bytes.*
import kotlin.test.*
class Base64UrlRegressionTests {
    @Test fun encodingMatchesIndependentUnpaddedUrlVectors() {
        val cases=mapOf("" to "", "f" to "Zg", "fo" to "Zm8", "foo" to "Zm9v", "foob" to "Zm9vYg", "fooba" to "Zm9vYmE", "foobar" to "Zm9vYmFy")
        for ((plain,encoded) in cases) {
            assertEquals(encoded,plain.encodeToByteArray().toBase64UrlString())
            assertContentEquals(plain.encodeToByteArray(),encoded.fromBase64UrlString())
            assertContentEquals(plain.encodeToByteArray(),encoded.encodeToByteArray().decodeBase64Url())
            assertEquals(encoded,plain.encodeToByteArray().toBase64String())
            assertContentEquals(plain.encodeToByteArray(),encoded.fromBase64String())
        }
        val bytes=ByteArray(256) { it.toByte() }
        val independent="AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk9QUVJTVFVWV1hZWltcXV5fYGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn-AgYKDhIWGh4iJiouMjY6PkJGSk5SVlpeYmZqbnJ2en6ChoqOkpaanqKmqq6ytrq-wsbKztLW2t7i5uru8vb6_wMHCw8TFxsfIycrLzM3Oz9DR0tPU1dbX2Nna29zd3t_g4eLj5OXm5-jp6uvs7e7v8PHy8_T19vf4-fr7_P3-_w"
        assertEquals(independent,bytes.toBase64String())
        assertContentEquals(bytes,independent.fromBase64String())
        assertEquals("-_8",byteArrayOf(-5,-1).toBase64String())
    }
    @Test fun malformedOrNoncanonicalEncodingsCannotBeSilentlyAccepted() {
        for(encoded in listOf("A","Zg==","Z g","Zg\n","+w","/w","AB","AAB","é")) {
            assertFailsWith<IllegalArgumentException>(encoded) { encoded.fromBase64UrlString() }
            assertFailsWith<IllegalArgumentException>(encoded) { encoded.fromBase64String() }
            assertFailsWith<IllegalArgumentException>(encoded) { encoded.encodeToByteArray().decodeBase64() }
        }
    }
}
