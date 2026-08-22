package com.latenighthack.ktbuf.net

import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.proto.Status
import com.latenighthack.ktbuf.proto.fromHTTPCode
import com.latenighthack.ktbuf.rpc.toByteArray
import com.latenighthack.ktbuf.rpc.toNSData
import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

actual class PlatformHttpClient: HttpClient {
    @OptIn(BetaInteropApi::class)
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
                val statusCode = httpResponse.statusCode.toInt()
                if (statusCode < 200 || statusCode > 299) {
                    val errorMessage = data?.let {
                        NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString()
                    } ?: ""
                    val status = Status.fromHTTPCode(statusCode, errorMessage)
                    continuation.resumeWithException(
                        RpcResponseException(
                            path = url,
                            verb = method,
                            code = status.code,
                            errorMessage = status.message
                        )
                    )
                } else {
                    val responseData = data?.toByteArray() ?: byteArrayOf()
                    continuation.resume(responseData)
                }
            }
        }
        task.resume()
    }
}
