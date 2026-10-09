package com.latenighthack.ktbuf.net

import com.latenighthack.ktbuf.proto.Codes

actual class PlatformHttpClient: HttpClient {
    actual override suspend fun httpCall(url: String, method: String, request: ByteArray, headers: Map<String, String>): ByteArray =
        throw RpcResponseException(url, method, Codes.UNIMPLEMENTED, "Built-in HTTP transport is not supported on Linux; supply a custom HttpClient")
}
