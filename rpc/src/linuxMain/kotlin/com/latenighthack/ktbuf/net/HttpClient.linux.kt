package com.latenighthack.ktbuf.net

actual class PlatformHttpClient: HttpClient {
    actual override suspend fun httpCall(url: String, method: String, request: ByteArray, headers: Map<String, String>): ByteArray {
        TODO("Not yet implemented")
    }
}