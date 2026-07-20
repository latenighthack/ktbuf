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
actual class HttpRpcClient actual constructor(private val serverPath: String, private val useApiGateway: Boolean) : RpcClient {
    private val client: OkHttpClient = OkHttpClient()
    private val isSecure = serverPath.startsWith("https")

    actual override suspend fun unaryCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcResponse {
        return suspendCancellableCoroutine { continuation ->
            val path = if (useApiGateway) {
                method.toApiGatewayPath("${if (isSecure) "" else "http://"}$serverPath")
            } else {
                method.toPath("${if (isSecure) "" else "http://"}$serverPath")
            }
            val unaryRequest = Request.Builder()
                .url(path)
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
        // Unlimited inbound: messages are handed over synchronously from the OkHttp listener
        // thread (ordered), and a close still lets already-buffered messages drain. A rendezvous
        // channel + async launch loses the final message(s) when the server closes right after
        // sending — e.g. an error response followed by a close frame.
        val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val outbound = Channel<ByteArray>(1)
        val isReady = CompletableDeferred<Unit>()

        override suspend fun receive(): ByteArray {
            return inbound.receiveCatching().getOrThrow()
        }

        override suspend fun send(bytes: ByteArray) {
            isReady.await()
            outbound.send(bytes)
        }

        override suspend fun closeOutbound() {
            outbound.close()
        }

        override suspend fun closeInbound() {
            inbound.close()
        }
    }

    actual override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit,
        readyCallback: () -> Unit
    ) {
        var globalException: Throwable? = null
        val context = StreamContext()

        val job = GlobalScope.launch(CoroutineExceptionHandler { _, exception ->
            globalException = exception
        }) {
            context.block()
            context.outbound.close()
            context.inbound.close()
        }

        val wsUrl = if (isSecure) {
            serverPath.replace("https:", "wss:")
        } else {
            "ws://${serverPath.replace("http://", "")}"
        }
        val url = if (useApiGateway) {
            method.toApiGatewayPath(wsUrl)
        } else {
            method.toPath(wsUrl)
        }

        val requestData = Request.Builder()
            .get()
            .url(url)
            .build()
        val webSocket = client.newWebSocket(requestData, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                context.isReady.complete(Unit)
                readyCallback()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosing(webSocket, code, reason)

                // Close (don't cancel): the block drains any buffered inbound messages, then
                // receive() throws Closed and the block winds down on its own.
                context.inbound.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)

                job.cancel()

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

                response?.closeQuietly()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)

                context.inbound.close()
                context.outbound.close()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                super.onMessage(webSocket, bytes)

                // Synchronous handoff on the listener thread keeps message order and beats any
                // subsequent close; trySend never suspends thanks to the unlimited buffer.
                context.inbound.trySend(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)

                context.inbound.trySend(text.toByteArray())
            }
        })

        while (!context.outbound.isClosedForReceive) {
            val result = context.outbound.receiveCatching()

            if (result.isSuccess) {
                val bytes = result.getOrThrow()

                webSocket.send(bytes.toByteString())
            } else {
                break
            }
        }

        job.join()

        webSocket.close(1000, null)

        if (globalException != null) {
            throw globalException!!
        }
    }
}
