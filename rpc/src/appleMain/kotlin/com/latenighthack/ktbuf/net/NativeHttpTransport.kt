@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package com.latenighthack.ktbuf.net

import com.latenighthack.ktbuf.proto.*
import com.latenighthack.ktbuf.rpc.toByteArray
import com.latenighthack.ktbuf.rpc.toNSData
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.*

internal class NativeTransportException(val domain: String?, val nativeCode: Long, message: String) : Exception("$domain ($nativeCode): $message")
internal fun NSError.rpcFailure(url: String, method: String, response: NSURLResponse? = null): RpcResponseException {
    val http = response as? NSHTTPURLResponse
    val status = if (http != null && http.statusCode >= 400) Status.fromHTTPCode(http.statusCode.toInt(), http.statusCode.toString(), http.allHeaderFields.mapKeys { it.key.toString() }.mapValues { it.value.toString() }.rpcHeader(RPC_STATUS_HEADER)) else null
    val message = localizedDescription
    return RpcResponseException(url, method, status?.code ?: nativeTransportCode(domain, code), status?.message ?: message, NativeTransportException(domain, code, message))
}

internal suspend fun performNativeHttpRequest(url: String, method: String, bytes: ByteArray, headers: Map<String,String>): RpcResponse = suspendCancellableCoroutine { continuation ->
    val nativeUrl = requireNotNull(NSURL.URLWithString(url)) { "Invalid HTTP URL" }
    val request = NSMutableURLRequest.requestWithURL(nativeUrl).apply {
        HTTPMethod = method.uppercase()
        headers.forEach { (name,value) -> setValue(value, forHTTPHeaderField = name) }
        if (method.uppercase() !in listOf("GET", "HEAD")) HTTPBody = bytes.toNSData()
    }
    val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
        continuation.completeCallback(runCatching {
            if (error != null) throw error.rpcFailure(url, method, response)
            val http = response as? NSHTTPURLResponse ?: throw RpcResponseException(url, method, Codes.UNAVAILABLE, "Missing HTTP response")
            val result = data?.toByteArray() ?: byteArrayOf()
            val responseHeaders = http.allHeaderFields.mapKeys { it.key.toString() }.mapValues { it.value.toString() }
            val statusHeader = responseHeaders.rpcHeader(RPC_STATUS_HEADER)
            if (http.statusCode.toInt() !in 200..299 || (statusHeader != null && Status.fromHTTPCode(http.statusCode.toInt(), null, statusHeader).code != Codes.OK)) {
                val status = Status.fromHTTPCode(http.statusCode.toInt(), result.decodeToString(), responseHeaders.rpcHeader(RPC_STATUS_HEADER))
                throw RpcResponseException(url, method, status.code, status.message)
            }
            RpcResponse(result, responseHeaders)
        })
    }
    continuation.invokeOnCancellation { task.cancel() }
    task.resume()
}
