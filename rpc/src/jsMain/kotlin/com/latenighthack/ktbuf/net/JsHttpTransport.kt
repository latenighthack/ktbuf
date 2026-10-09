package com.latenighthack.ktbuf.net

import com.latenighthack.ktbuf.proto.*
import com.latenighthack.ktbuf.rpc.isNode
import kotlinx.coroutines.*
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.xhr.*
import kotlin.js.Promise

private fun httpResult(url: String, method: String, status: Int, bytes: ByteArray, headers: Map<String,String>): RpcResponse {
    val statusHeader = headers.rpcHeader(RPC_STATUS_HEADER)
    if (status !in 200..299 || (statusHeader != null && Status.fromHTTPCode(status, null, statusHeader).code != Codes.OK)) {
        val error = Status.fromHTTPCode(status, bytes.decodeToString(), headers.rpcHeader(RPC_STATUS_HEADER))
        throw RpcResponseException(url, method, error.code, error.message)
    }
    return RpcResponse(bytes, headers)
}

internal suspend fun performJsHttpRequest(url: String, method: String, bytes: ByteArray, headers: Map<String,String>): RpcResponse {
    if (!isNode) return xhrRequest(url, method, bytes, headers)
    val controller = js("new AbortController()")
    val options = js("({})")
    options.method = method.uppercase()
    options.signal = controller.signal
    val requestHeaders = js("({})")
    headers.forEach { (name, value) -> requestHeaders[name] = value }
    options.headers = requestHeaders
    if (method.uppercase() !in listOf("GET", "HEAD")) options.body = bytes.unsafeCast<Int8Array>()
    try {
        val response = (js("fetch(url, options)") as Promise<dynamic>).await()
        val resultHeaders = linkedMapOf<String,String>()
        response.headers.forEach { value: String, name: String -> resultHeaders[name.lowercase()] = value }
        val buffer = (response.arrayBuffer() as Promise<ArrayBuffer>).await()
        return httpResult(url, method, response.status as Int, Int8Array(buffer).unsafeCast<ByteArray>(), resultHeaders)
    } catch (cancelled: CancellationException) {
        controller.abort()
        throw cancelled
    } catch (error: Throwable) {
        if (error is RpcResponseException) throw error
        throw RpcResponseException(url, method, Codes.UNAVAILABLE, "HTTP transport failed", error)
    }
}

private suspend fun xhrRequest(url: String, method: String, bytes: ByteArray, headers: Map<String,String>): RpcResponse = suspendCancellableCoroutine { continuation ->
    val xhr = XMLHttpRequest()
    xhr.onreadystatechange = {
        if (xhr.readyState == XMLHttpRequest.DONE) continuation.completeCallback(runCatching {
            if (xhr.status.toInt() == 0) throw RpcResponseException(url, method, Codes.UNAVAILABLE, "HTTP transport failed")
            val response = xhr.response as? ArrayBuffer
            httpResult(url, method, xhr.status.toInt(), response?.let { Int8Array(it).unsafeCast<ByteArray>() } ?: byteArrayOf(), parseResponseHeaders(xhr.getAllResponseHeaders()))
        })
    }
    xhr.onerror = { continuation.completeCallback(Result.failure(RpcResponseException(url,method,Codes.UNAVAILABLE,"HTTP transport failed"))) }
    xhr.ontimeout = { continuation.completeCallback(Result.failure(RpcResponseException(url,method,Codes.CLIENT_TIMEOUT,"HTTP transport timed out"))) }
    xhr.onabort = { continuation.completeCallback(Result.failure(CancellationException("HTTP request aborted"))) }
    continuation.invokeOnCancellation {
        xhr.onreadystatechange = null; xhr.onerror = null; xhr.ontimeout = null; xhr.onabort = null
        xhr.abort()
    }
    try {
        xhr.open(method.uppercase(), url, true)
        xhr.responseType = XMLHttpRequestResponseType.ARRAYBUFFER
        headers.forEach { (name,value) -> xhr.setRequestHeader(name,value) }
        xhr.send(bytes.unsafeCast<Int8Array>())
    } catch (failure: Throwable) { continuation.completeCallback(Result.failure(failure)) }
}
