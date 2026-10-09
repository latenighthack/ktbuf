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

actual class HttpRpcClient actual constructor(private val serverPath: String, private val useApiGateway: Boolean) : RpcClient {
    private val client: OkHttpClient = OkHttpClient()
    private val address = normalizedHttpRpcAddress(serverPath)
    private val lifecycle = TransportLifecycle()
    actual fun close() { lifecycle.close(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    actual suspend fun closeAndJoin() {
        close(); lifecycle.join()
        withContext(Dispatchers.IO) {
            check(client.dispatcher.executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) { "HTTP dispatcher did not stop" }
        }
    }

    actual override suspend fun unaryCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcResponse = lifecycle.call {
        checkedRpcMessage(request)
        suspendCancellableCoroutine { continuation ->
            val path = if (useApiGateway) {
                method.toApiGatewayPath(address)
            } else {
                method.toPath(address)
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
                                val errorMessage = response.body!!.boundedBytes().decodeToString()
                                val status = Status.fromHTTPCode(response.code, errorMessage)
                                throw RpcResponseException(method.toPath(""), "POST", status.code, status.message, RuntimeException(errorMessage))
                            } else {
                                RpcResponse(response.body!!.boundedBytes(), response.headers.toMap())
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

    private class StreamContext : RpcServerStream {
        val inbound = RpcInboundBuffer()
        val outbound = Channel<ByteArray>(1)
        val isReady = CompletableDeferred<Unit>()
        override suspend fun receive(): ByteArray = inbound.receive()
        override suspend fun send(bytes: ByteArray) { checkedRpcMessage(bytes); isReady.await(); outbound.send(bytes) }
        override suspend fun closeOutbound() { outbound.close() }
        override suspend fun closeInbound() { inbound.close() }
        fun failed(cause: Throwable) { isReady.completeExceptionally(cause); inbound.close(cause); outbound.close(cause) }
    }

    actual override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit,
        readyCallback: () -> Unit
    ): Unit = lifecycle.call {
        coroutineScope {
            val context = StreamContext()
            val job = launch {
                try { context.block() }
                finally { context.outbound.close(); context.inbound.close() }
            }
            val path = webSocketRpcAddress(address)
            val url = if (useApiGateway) method.toApiGatewayPath(path) else method.toPath(path)
            val webSocket = client.newWebSocket(Request.Builder().get().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    context.isReady.complete(Unit)
                    runCatching { readyCallback() }.exceptionOrNull()?.let { context.failed(it); webSocket.cancel() }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { context.inbound.close(); webSocket.close(code, reason) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { context.inbound.close(); context.outbound.close() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { response?.close(); context.failed(t) }
                private fun incoming(webSocket: WebSocket, bytes: ByteArray) {
                    val failure = if (bytes.size > MAX_RPC_MESSAGE_BYTES) IllegalArgumentException("RPC frame exceeds 16 MiB limit")
                        else if (!context.inbound.offer(bytes)) IllegalStateException("RPC inbound buffer is full or closed") else null
                    if (failure != null) { context.failed(failure); webSocket.cancel() }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size > MAX_RPC_MESSAGE_BYTES) { context.failed(IllegalArgumentException("RPC frame exceeds 16 MiB limit")); webSocket.cancel() }
                    else incoming(webSocket, bytes.toByteArray())
                }
                override fun onMessage(webSocket: WebSocket, text: String) { incoming(webSocket, text.toByteArray()) }
            })
            try {
                for (bytes in context.outbound) {
                    if (!webSocket.send(bytes.toByteString())) throw IOException("WebSocket rejected outbound frame")
                }
                job.join()
            } finally {
                webSocket.cancel()
                job.cancelAndJoin()
            }
        }
    }
}

private fun ResponseBody.boundedBytes(): ByteArray {
    require(contentLength() <= MAX_RPC_MESSAGE_BYTES) { "RPC response exceeds 16 MiB limit" }
    val output = java.io.ByteArrayOutputStream()
    byteStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(read <= MAX_RPC_MESSAGE_BYTES - output.size()) { "RPC response exceeds 16 MiB limit" }
            output.write(buffer, 0, read)
        }
    }
    return output.toByteArray()
}
