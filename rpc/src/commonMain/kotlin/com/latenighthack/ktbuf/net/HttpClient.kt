package com.latenighthack.ktbuf.net

interface HttpClient {
    suspend fun httpCall(url: String, method: String, request: ByteArray, headers: Map<String, String>): ByteArray
}

expect class PlatformHttpClient(): HttpClient {
    override suspend fun httpCall(url: String, method: String, request: ByteArray, headers: Map<String, String>): ByteArray
}

