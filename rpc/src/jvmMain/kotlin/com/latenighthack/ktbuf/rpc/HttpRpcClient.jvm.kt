package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
actual class HttpRpcClient actual constructor(private val serverPath: String, private val useApiGateway: Boolean) : RpcClient {
    private val client: OkHttpClient = OkHttpClient()

    actual override suspend fun unaryCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcResponse {
        return suspendCancellableCoroutine { continuation ->
            val path = if (useApiGateway) {
                method.toApiGatewayPath(rpcHttpBaseUrl(serverPath))
            } else {
                method.toPath(rpcHttpBaseUrl(serverPath))
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
                    val result = runCatching {
                        response.use {
                            if (response.code > 299 || response.code < 200) {
                                val errorMessage = response.body!!.string()
                                val status = Status.fromHTTPCode(response.code, errorMessage)
                                throw RpcResponseException(method.toPath(""), "POST", status.code, status.message, RuntimeException(errorMessage))
                            } else {
                                RpcResponse(response.body!!.bytes(), response.headers.toMap())
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

    actual override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit,
        readyCallback: () -> Unit
    ) = coroutineScope {
        val inbound = Channel<ByteArray>(64)
        val ready = CompletableDeferred<Unit>()
        val queuedBytes = java.util.concurrent.atomic.AtomicLong()
        val byteLimit = com.latenighthack.ktbuf.ProtobufLimits().maxMessageBytes.toLong()
        var outboundClosed = false
        val sending = kotlinx.coroutines.sync.Mutex()
        var socket: WebSocket? = null
        val wsUrl = rpcWebSocketBaseUrl(serverPath)
        val url = if (useApiGateway) method.toApiGatewayPath(wsUrl) else method.toPath(wsUrl)
        fun error(code: Codes, message: String, cause: Throwable? = null) = RpcResponseException(url, "WS", code, message, cause)
        fun fail(webSocket: WebSocket, cause: Throwable) {
            ready.completeExceptionally(cause)
            inbound.close(cause)
            webSocket.cancel()
        }
        fun finish(code: Int, reason: String) {
            val status = Status.fromWSCode(code, reason)
            val cause = if (status.code == Codes.OK) null else error(status.code, status.message)
            if (!ready.isCompleted) ready.completeExceptionally(cause ?: error(Codes.UNAVAILABLE, "Socket closed before opening"))
            inbound.close(cause)
        }
        val stream = object : RpcServerStream {
            override suspend fun receive(): ByteArray = inbound.receive().also { queuedBytes.addAndGet(-it.size.toLong()) }
            override suspend fun send(bytes: ByteArray) {
                ready.await()
                sending.lock()
                try {
                    check(!outboundClosed) { "Request stream is closed" }
                    if (bytes.size > byteLimit) throw error(Codes.RESOURCE_EXHAUSTED, "RPC frame exceeds byte limit")
                    if (!checkNotNull(socket).send(bytes.toByteString())) throw error(Codes.UNAVAILABLE, "WebSocket rejected the outgoing message")
                } finally { sending.unlock() }
            }
            override suspend fun closeOutbound() {
                sending.lock()
                try { outboundClosed = true } finally { sending.unlock() }
            }
            override suspend fun closeInbound() { inbound.close() }
        }
        try {
            socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { ready.complete(Unit) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    finish(code, reason)
                    webSocket.close(1000, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { finish(code, reason) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val status = response?.takeIf { it.code >= 400 }?.let { Status.fromHTTPCode(it.code, it.message) }
                    fail(webSocket, error(status?.code ?: Codes.UNAVAILABLE, status?.message ?: "WebSocket transport failed", t))
                    response?.body?.close()
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (inbound.isClosedForSend) return
                    val count = bytes.size.toLong()
                    if (count > byteLimit) { fail(webSocket, error(Codes.RESOURCE_EXHAUSTED, "RPC frame exceeds byte limit")); return }
                    val reserved = queuedBytes.addAndGet(count)
                    if (reserved > byteLimit) {
                        queuedBytes.addAndGet(-count)
                        fail(webSocket, error(Codes.RESOURCE_EXHAUSTED, "RPC response queue exceeds byte limit"))
                        return
                    }
                    if (!inbound.trySend(bytes.toByteArray()).isSuccess) {
                        queuedBytes.addAndGet(-count)
                        if (!inbound.isClosedForSend) fail(webSocket, error(Codes.RESOURCE_EXHAUSTED, "RPC response queue is full"))
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    fail(webSocket, error(Codes.INVALID_ARGUMENT, "Expected a binary RPC response"))
                }
            })
            ready.await()
            readyCallback()
            stream.block()
        } finally {
            inbound.cancel()
            socket?.cancel()
        }
    }
}
