package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.*
import kotlinx.coroutines.*
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.BinaryType
import org.w3c.dom.ARRAYBUFFER
import org.w3c.dom.WebSocket
import org.w3c.xhr.ARRAYBUFFER
import org.w3c.xhr.XMLHttpRequest
import org.w3c.xhr.XMLHttpRequestResponseType

val isNode: Boolean = js("typeof window === 'undefined'") as Boolean

// responseText is unreadable when responseType is "arraybuffer" (throws InvalidStateError);
// decode the error body from xhr.response instead.
internal fun XMLHttpRequest.errorBodyText(): String {
    val response = this.response as? ArrayBuffer ?: return ""
    return js("new TextDecoder().decode(response)") as String
}

private suspend fun httpPost(url: String, headers: Map<String, String>, data: ByteArray): RpcResponse = suspendCancellableCoroutine { continuation ->
    val xhr = XMLHttpRequest()
    xhr.onreadystatechange = { _ ->
        if (xhr.readyState == XMLHttpRequest.DONE) {
            continuation.completeCallback(runCatching {
                val bytes = checkedRpcMessage(Int8Array(xhr.response as ArrayBuffer).unsafeCast<ByteArray>())
                val responseHeaders = xhr.getAllResponseHeaders().split("\r\n").mapNotNull {
                    val parts = it.split(":", limit = 2)
                    if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                }.toMap()
                val statusHeader = xhr.getResponseHeader(RPC_STATUS_HEADER)
                if (xhr.status / 100 != 2 || (statusHeader != null && Status.fromHTTPCode(xhr.status.toInt(), null, statusHeader).code != Codes.OK)) {
                    val status = Status.fromHTTPCode(xhr.status.toInt(), bytes.decodeToString(), statusHeader)
                    throw RpcResponseException(xhr.responseURL, "POST", status.code, status.message)
                }
                RpcResponse(bytes, responseHeaders)
            })
        }
    }
    xhr.onerror = { continuation.completeCallback(Result.failure(IllegalStateException("RPC HTTP transport failed"))) }
    xhr.onprogress = { event ->
        if ((event as org.w3c.xhr.ProgressEvent).loaded.toDouble() > MAX_RPC_MESSAGE_BYTES.toDouble()) {
            xhr.abort()
            continuation.completeCallback(Result.failure(IllegalArgumentException("RPC response exceeds 16 MiB limit")))
        }
    }
    xhr.open("POST", url, true)
    xhr.responseType = XMLHttpRequestResponseType.ARRAYBUFFER
    xhr.setRequestHeader("Content-type", "application/proto")
    for ((key, value) in headers) xhr.setRequestHeader(key, value)
    xhr.send(checkedRpcMessage(data))
    continuation.invokeOnCancellation { xhr.onreadystatechange = null; xhr.onerror = null; xhr.abort() }
}

actual class HttpRpcClient actual constructor(private val serverPath: String, private val useApiGateway: Boolean) : RpcClient {
    private val address = normalizedHttpRpcAddress(serverPath)
    private val lifecycle = TransportLifecycle()
    actual fun close() { lifecycle.close() }
    actual suspend fun closeAndJoin() { close(); lifecycle.join() }
    actual override suspend fun unaryCall(method: RpcMethodSpecifier, headers: Map<String, String>, request: ByteArray): RpcResponse = lifecycle.call {
        val path = if (useApiGateway) method.toApiGatewayPath(address) else method.toPath(address)
        httpPost(path, headers, request)
    }
    private class WebSocketServerStream(private val webSocket: WebSocket, private val readyCallback: () -> Unit, private val path: String) : RpcServerStream {
        private val received = RpcInboundBuffer()
        private val ready = CompletableDeferred<Unit>()
        private val closed = CompletableDeferred<Unit>()
        suspend fun awaitClosed() { closed.await() }
        init {
            webSocket.binaryType = BinaryType.ARRAYBUFFER
            webSocket.onopen = { ready.complete(Unit); runCatching { readyCallback() }.exceptionOrNull()?.let(::failed) }
            webSocket.onclose = { event ->
                closed.complete(Unit)
                val close = event as org.w3c.dom.CloseEvent
                val failure = rpcWebSocketCloseFailure(path, close.code.toInt(), close.reason)
                received.close(failure)
                ready.completeExceptionally(failure ?: IllegalStateException("RPC WebSocket closed before ready"))
            }
            webSocket.onerror = { failed(IllegalStateException("RPC WebSocket transport failed")) }
            webSocket.onmessage = { event ->
                runCatching {
                    val bytes = checkedRpcMessage(Int8Array(event.data as ArrayBuffer).unsafeCast<ByteArray>())
                    check(received.offer(bytes)) { "RPC inbound buffer is full or closed" }
                }.exceptionOrNull()?.let(::failed)
            }
        }
        private fun failed(error: Throwable) { received.close(error); ready.completeExceptionally(error); webSocket.close() }
        override suspend fun receive(): ByteArray = received.receive()
        override suspend fun send(bytes: ByteArray) { checkedRpcMessage(bytes); ready.await(); webSocket.send(Int8Array(bytes.toTypedArray())) }
        override suspend fun closeOutbound() { webSocket.close() }
        override suspend fun closeInbound() { received.close(); webSocket.close() }
    }
    actual override suspend fun serverStreamingCall(method: RpcMethodSpecifier, block: suspend RpcServerStream.() -> Unit, readyCallback: () -> Unit): Unit = lifecycle.call {
        if (isNode) js("global.WebSocket = require('websocket').w3cwebsocket")
        val path = webSocketRpcAddress(address)
        val url = if (useApiGateway) method.toApiGatewayPath(path) else method.toPath(path)
        val webSocket = WebSocket(url)
        val stream = WebSocketServerStream(webSocket, readyCallback, url)
        try { stream.block() } finally { webSocket.close(); withContext(NonCancellable) { withTimeout(5_000) { stream.awaitClosed() } } }
    }
}
