package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.closeQuietly
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class, DelicateCoroutinesApi::class)
actual class HttpRpcClient actual constructor(private val serverPath: String) : RpcClient {
    private val client: OkHttpClient = OkHttpClient()

    actual override suspend fun unaryCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcResponse {
        return suspendCancellableCoroutine { continuation ->
            val unaryRequest = Request.Builder()
                .url(method.toPath("https://$serverPath"))
                .headers(Headers.headersOf(
                    *headers
                        .entries
                        .fold(mutableListOf<String>()) { list, entry ->
                            list.add(entry.key)
                            list.add(entry.value)

                            list
                        }
                        .toTypedArray()
                ))
                .post(request.toRequestBody("application/proto".toMediaType()))
                .build()
            val call = client.newCall(unaryRequest)

            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    if (response.code > 299 || response.code < 200) {
                        val errorMessage = response.body!!.string()
                        val status = Status.fromHTTPCode(response.code, errorMessage)
                        continuation.resumeWithException(RpcResponseException(method.toPath(""), "POST", status.code, status.message, RuntimeException(errorMessage)))
                    } else {
                        continuation.resume(RpcResponse(response.body!!.bytes(), response.headers.toMap()))
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

    private class StreamContext : RpcServerStream {
        val inbound = Channel<ByteArray>()
        val outbound = Channel<ByteArray>(1)

        override suspend fun receive(): ByteArray {
            return inbound.receive()
        }

        override suspend fun send(bytes: ByteArray) {
            outbound.send(bytes)
        }
    }

    actual override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit
    ) {
        var globalException: Throwable? = null
        val context = StreamContext()

        val job = GlobalScope.launch(CoroutineExceptionHandler { _, exception ->
            globalException = exception
        }) {
            context.block()
        }

        val requestData = Request.Builder()
            .get()
            .url(method.toPath("wss://$serverPath"))
            .build()
        val webSocket = client.newWebSocket(requestData, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosing(webSocket, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)

                val statusCode = when (t) {
                    is EOFException -> {
                        try {
                            context.inbound.close()
                            context.outbound.close()
                        } catch (t: Throwable) {

                        }
                        return
                    }
                    is ConnectException -> 0
                    else -> 0
                }

                // todo: needs testing (this might not be onClosed with error)
                val status = Status.fromWSCode(statusCode, "")
                val error = CancellationException("${statusCode}", RpcResponseException(method.toPath(""), "WS", status.code, response?.message ?: "", t))

                context.inbound.cancel(error)
                context.outbound.cancel(error)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)

                context.inbound.close()
                context.outbound.close()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                super.onMessage(webSocket, bytes)

                GlobalScope.launch {
                    context.inbound.send(bytes.toByteArray())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)

                GlobalScope.launch {
                    context.inbound.send(text.toByteArray())
                }
            }
        })

        while (!context.outbound.isClosedForReceive) {
            val bytes = context.outbound.receive()
            webSocket.send(bytes.toByteString())
        }

        job.join()

        if (globalException != null) {
            throw globalException!!
        }
    }
}
