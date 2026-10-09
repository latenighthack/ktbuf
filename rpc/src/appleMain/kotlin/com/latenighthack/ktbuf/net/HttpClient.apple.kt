package com.latenighthack.ktbuf.net
actual class PlatformHttpClient: HttpClient {
    actual override suspend fun httpCall(url: String, method: String, request: ByteArray, headers: Map<String,String>): ByteArray =
        performNativeHttpRequest(url, method, request, headers).data
}
