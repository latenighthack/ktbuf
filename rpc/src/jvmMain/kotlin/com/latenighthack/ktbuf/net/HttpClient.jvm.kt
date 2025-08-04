package com.latenighthack.ktbuf.net

import com.latenighthack.ktbuf.net.RpcResponseException
import com.latenighthack.ktbuf.proto.Status
import com.latenighthack.ktbuf.proto.fromHTTPCode
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.closeQuietly
import java.io.IOException
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
                    if (response.code > 299 || response.code < 200) {
                        val errorMessage = response.body!!.string()
                        val status = Status.fromHTTPCode(response.code, errorMessage)
                        continuation.resumeWithException(RpcResponseException(url, method, status.code, status.message, RuntimeException(errorMessage)))
                    } else {
                        continuation.resume(response.body!!.bytes())
                    }

                    response.closeQuietly()
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) {
                        return
                    }

                    call.cancel()

                    continuation.resumeWithException(e)
                }
            })

            continuation.invokeOnCancellation {
                try {
                    call.cancel()
                } catch (ex: Throwable) {
                    // Ignore cancel exception
                }
            }
        }
    }
}
