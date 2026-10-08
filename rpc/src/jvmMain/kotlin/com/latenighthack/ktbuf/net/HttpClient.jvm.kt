package com.latenighthack.ktbuf.net

import com.latenighthack.ktbuf.net.RpcResponseException
import com.latenighthack.ktbuf.proto.Status
import com.latenighthack.ktbuf.proto.fromHTTPCode
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

actual class PlatformHttpClient: HttpClient {
    private val client: OkHttpClient = OkHttpClient()

    actual override suspend fun httpCall(url: String, method: String, request: ByteArray, headers: Map<String, String>): ByteArray {
        return suspendCancellableCoroutine { continuation ->
            val unaryRequest = Request.Builder()
                .url(url)
                .headers(
                    Headers.headersOf(
                        *headers
                            .entries
                            .fold(mutableListOf<String>()) { list, entry ->
                                list.add(entry.key)
                                list.add(entry.value)

                                list
                            }
                            .toTypedArray()
                    ))
                .let {
                    when (method.lowercase()) {
                        "post" -> it.post(request.toRequestBody((headers["Content-Type"] ?: "application/raw").toMediaType()))
                        "put" -> it.put(request.toRequestBody((headers["Content-Type"] ?: "application/raw").toMediaType()))
                        "patch" -> it.patch(request.toRequestBody((headers["Content-Type"] ?: "application/raw").toMediaType()))
                        "get" -> it.get()
                        "delete" -> it.delete()
                        "head" -> it.head()
                        else -> throw UnsupportedOperationException()
                    }
                }
                .build()
            val call = client.newCall(unaryRequest)

            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (response.code > 299 || response.code < 200) {
                                val errorMessage = response.body!!.string()
                                val status = Status.fromHTTPCode(response.code, errorMessage)
                                throw RpcResponseException(url, method, status.code, status.message, RuntimeException(errorMessage))
                            } else {
                                response.body!!.bytes()
                            }
                        }
                    }
                    continuation.completeCallback(result)
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.completeCallback(Result.failure(e))
                }
            })

            continuation.invokeOnCancellation {
                call.cancel()
            }
        }
    }
}
