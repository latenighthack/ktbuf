package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.CloseEvent
import org.w3c.dom.ErrorEvent
import org.w3c.dom.WebSocket
import org.w3c.xhr.ARRAYBUFFER
import org.w3c.xhr.XMLHttpRequest
import org.w3c.xhr.XMLHttpRequestResponseType
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

val isNode: Boolean = js("typeof process !== 'undefined' && process.versions != null && process.versions.node != null") as Boolean

// responseText is unreadable when responseType is "arraybuffer" (throws InvalidStateError);
// decode the error body from xhr.response instead.
internal fun XMLHttpRequest.errorBodyText(): String {
    val response = this.response as? ArrayBuffer ?: return ""
    return js("new TextDecoder().decode(response)") as String
}

private fun statusHandler(xhr: XMLHttpRequest, coroutineContext: Continuation<RpcResponse>) {
    if (xhr.readyState == XMLHttpRequest.DONE) {
        val headers = xhr.getAllResponseHeaders()
            .split("\r\n").associate {
                val parts = it.split(":", limit = 1)

                if (parts.size > 1) {
                    parts[0] to parts[1]
                } else {
                    parts[0] to ""
                }
            }

        if (xhr.status / 100 == 2) {
            coroutineContext.resume(RpcResponse(Int8Array(xhr.response as ArrayBuffer).unsafeCast<ByteArray>(), headers))
        } else {
            val status = Status.fromHTTPCode(xhr.status.toInt(), xhr.errorBodyText(), xhr.getResponseHeader(RPC_STATUS_HEADER))
            coroutineContext.resumeWithException(RpcResponseException(xhr.responseURL, "POST", status.code, status.message))
        }
    }
}

private suspend fun httpPost(host: String, headers: Map<String, String>, path: String, secure: Boolean, data: ByteArray): RpcResponse = suspendCoroutine { c ->
    val scheme = if (secure) "" else "http://"

    val xhr = XMLHttpRequest()

    xhr.onreadystatechange = { _ ->
        statusHandler(xhr, c)
    }
    xhr.open("POST", "$scheme$host$path", true)
    xhr.responseType = XMLHttpRequestResponseType.ARRAYBUFFER
    xhr.setRequestHeader("Content-type", "application/proto")

    for ((key, value) in headers) {
        xhr.setRequestHeader(key, value)
    }

    xhr.send(data)
}

actual class HttpRpcClient actual constructor(private val serverPath: String, private val useApiGateway: Boolean) : RpcClient {

    actual override suspend fun unaryCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcResponse {
        return httpPost(rpcHttpBaseUrl(serverPath), headers, if (useApiGateway) method.toApiGatewayPath("") else method.toPath(""), true, request)
    }

    private class WebSocketServerStream(private val socket: WebSocket) : RpcServerStream {
        private val inbound = Channel<ByteArray>(64)
        private val ready = CompletableDeferred<Unit>()
        private val byteLimit = com.latenighthack.ktbuf.ProtobufLimits().maxMessageBytes
        private var queuedBytes = 0
        private var outboundClosed = false
        private var failure: Throwable? = null
        private fun error(code: Codes, message: String) = RpcResponseException(socket.url, "WS", code, message)
        private fun fail(cause: Throwable) {
            if (failure == null) failure = cause
            ready.completeExceptionally(cause)
            inbound.close(cause)
            socket.close()
        }
        init {
            socket.asDynamic().binaryType = "arraybuffer"
            socket.onopen = { ready.complete(Unit); Unit }
            socket.onclose = { event ->
                val closed = event.asDynamic()
                val status = Status.fromWSCode(closed.code as? Int ?: 1006, closed.reason as? String)
                val cause = if (status.code == Codes.OK) null else error(status.code, status.message)
                if (cause != null && failure == null) failure = cause
                if (!ready.isCompleted) ready.completeExceptionally(cause ?: error(Codes.UNAVAILABLE, "Socket closed before opening"))
                inbound.close(cause)
                Unit
            }
            socket.onerror = { event ->
                fail(error(Codes.UNAVAILABLE, event.asDynamic().message as? String ?: "WebSocket transport failed"))
            }
            socket.onmessage = { event ->
                val data = event.data
                if (!(js("data instanceof ArrayBuffer") as Boolean)) fail(error(Codes.INVALID_ARGUMENT, "Expected a binary RPC response"))
                else {
                    val bytes = Int8Array(data as ArrayBuffer).unsafeCast<ByteArray>()
                    if (bytes.size > byteLimit - queuedBytes) fail(error(Codes.RESOURCE_EXHAUSTED, "RPC response queue exceeds byte limit"))
                    else {
                        queuedBytes += bytes.size
                        if (!inbound.trySend(bytes).isSuccess) {
                            queuedBytes -= bytes.size
                            if (!inbound.isClosedForSend) fail(error(Codes.RESOURCE_EXHAUSTED, "RPC response queue is full"))
                        }
                    }
                }
            }
        }
        suspend fun awaitOpen() { ready.await() }
        override suspend fun receive(): ByteArray = inbound.receive().also { queuedBytes -= it.size }
        private fun checkWritable() {
            failure?.let { throw it }
            if (socket.readyState.toInt() != 1) throw error(Codes.UNAVAILABLE, "WebSocket is not open")
            check(!outboundClosed) { "Request stream is closed" }
        }
        override suspend fun send(bytes: ByteArray) {
            ready.await(); checkWritable()
            val pendingBytes = socket.bufferedAmount.toDouble()
            if (bytes.size > byteLimit || pendingBytes + bytes.size > byteLimit) throw error(Codes.RESOURCE_EXHAUSTED, "RPC outgoing queue exceeds byte limit")
            socket.send(bytes.unsafeCast<Int8Array>())
        }
        override suspend fun closeOutbound() {
            ready.await()
            if (outboundClosed) return
            checkWritable()
            if (socket.protocol != RPC_STREAM_PROTOCOL) throw error(Codes.UNIMPLEMENTED, "Peer does not support request half-close")
            socket.send(RPC_REQUESTS_END)
            outboundClosed = true
        }
        override suspend fun closeInbound() { inbound.cancel(); socket.close() }
        fun dispose() {
            // onclose remains installed to observe termination; it never touches browser-only globals.
            socket.onmessage = null
            inbound.cancel()
            socket.close()
        }
    }

    actual override suspend fun serverStreamingCall(method: RpcMethodSpecifier, block: suspend RpcServerStream.() -> Unit, readyCallback: () -> Unit) =
        serverStreamingCall(method, emptyMap(), block, readyCallback)

    actual override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        block: suspend RpcServerStream.() -> Unit,
        readyCallback: () -> Unit
    ) = coroutineScope {
        require(headers.keys.none { it.equals("Sec-WebSocket-Protocol", true) }) { "RPC protocol header is reserved" }
        val base = rpcWebSocketBaseUrl(serverPath)
        val url = if (useApiGateway) method.toApiGatewayPath(base) else method.toPath(base)
        val protocols = arrayOf(RPC_STREAM_PROTOCOL)
        val socket = if (isNode) {
            val socketConstructor = js("require('ws')")
            val headerObject = js("({})")
            headers.forEach { (name, value) -> headerObject[name] = value }
            val configuration = js("({ maxPayload: 16777216, perMessageDeflate: false })")
            configuration.headers = headerObject
            js("new socketConstructor(url, protocols, configuration)").unsafeCast<WebSocket>()
        } else {
            if (headers.isNotEmpty()) throw RpcResponseException(url, "WS", Codes.UNIMPLEMENTED, "Browser WebSocket does not support custom handshake headers")
            WebSocket(url, protocols)
        }
        val stream = WebSocketServerStream(socket)
        try {
            stream.awaitOpen()
            readyCallback()
            stream.block()
        } finally { stream.dispose() }
    }
}
