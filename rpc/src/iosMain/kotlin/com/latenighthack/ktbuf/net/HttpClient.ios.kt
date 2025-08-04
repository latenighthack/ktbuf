package com.latenighthack.ktbuf.net

import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.rpc.toByteArray
import com.latenighthack.ktbuf.rpc.toNSData
import platform.Foundation.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

actual class PlatformHttpClient: HttpClient {
    actual override suspend fun httpCall(url: String, method: String, request: ByteArray, headers: Map<String, String>): ByteArray = suspendCoroutine { continuation ->
        val requestUrl = NSURL.URLWithString(url)!!
        val urlRequest = NSMutableURLRequest.requestWithURL(requestUrl).apply {
            HTTPMethod = method
            headers.forEach { (key, value) ->
                setValue(value, forHTTPHeaderField = key)
            }
            when (method.lowercase()) {
                "post", "put", "patch" -> {
                    HTTPBody = request.toNSData()
                }
                else -> {}
            }
        }

        val task = NSURLSession.sharedSession.dataTaskWithRequest(urlRequest) { data, response, error ->
            if (error != null) {
                continuation.resumeWithException(
                    RpcResponseException(
                        path = url,
                        verb = method,
                        code = Codes.from(error.code.toInt()),
                        errorMessage = error.localizedDescription ?: "Unknown error"
                    )
                )
            } else {
                val httpResponse = response as NSHTTPURLResponse
                val headers = httpResponse.allHeaderFields.mapKeys { it.key.toString() }.mapValues { it.value.toString() }
                val responseData = data?.toByteArray() ?: byteArrayOf()
                continuation.resume(responseData)
            }
        }
        task.resume()
    }
}
