package com.latenighthack.ktbuf.net

import com.latenighthack.ktbuf.proto.Status
import com.latenighthack.ktbuf.proto.fromHTTPCode
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.xhr.ARRAYBUFFER
import org.w3c.xhr.XMLHttpRequest
import org.w3c.xhr.XMLHttpRequestResponseType
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private fun statusHandler(method: String, xhr: XMLHttpRequest, coroutineContext: Continuation<ByteArray>) {
    if (xhr.readyState == XMLHttpRequest.DONE) {
        val headers = xhr.getAllResponseHeaders()
            .split("\r\n").associate {
                val parts = it.split(":", limit = 1)

                if (parts.size > 1) {
                    parts[0] to parts[1]
                } else {
                    parts[0] to ""
                }
            }

        if (xhr.status / 100 == 2) {
            coroutineContext.resume(Int8Array(xhr.response as ArrayBuffer).unsafeCast<ByteArray>())
        } else {
            val status = Status.fromHTTPCode(xhr.status.toInt(), xhr.responseText)
            coroutineContext.resumeWithException(RpcResponseException(xhr.responseURL, method, status.code, status.message))
        }
    }
}

actual class PlatformHttpClient: HttpClient {
    actual override suspend fun httpCall(url: String, method: String, request: ByteArray, headers: Map<String, String>): ByteArray = suspendCoroutine { c ->
        val xhr = XMLHttpRequest()

        xhr.onreadystatechange = { _ ->
            statusHandler(method, xhr, c)
        }
        xhr.open(method.uppercase(), url, true)
        xhr.responseType = XMLHttpRequestResponseType.ARRAYBUFFER

        for ((key, value) in headers) {
            xhr.setRequestHeader(key, value)
        }

        xhr.send(request)
    }
}