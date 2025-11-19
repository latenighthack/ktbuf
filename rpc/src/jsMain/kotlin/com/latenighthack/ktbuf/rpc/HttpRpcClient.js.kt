package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.*
import kotlinx.browser.window
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.CloseEvent
import org.w3c.dom.ErrorEvent
import org.w3c.dom.WebSocket
import org.w3c.files.Blob
import org.w3c.files.FileReader
import org.w3c.xhr.ARRAYBUFFER
import org.w3c.xhr.XMLHttpRequest
import org.w3c.xhr.XMLHttpRequestResponseType
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

val isNode: Boolean = js("typeof window === 'undefined'") as Boolean

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
            val status = Status.fromHTTPCode(xhr.status.toInt(), xhr.responseText)
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
    private val secure = serverPath.startsWith("https://")

    actual override suspend fun unaryCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcResponse {
        return httpPost(serverPath, headers, method.toPath(""), secure, request)
    }

    private class WebSocketServerStream(private val webSocket: WebSocket, private val readyCallback: () -> Unit) : RpcServerStream {
        private val receiveChannel = Channel<ByteArray>()
        private var doOnReady: (() -> Unit)? = null
        private var isReady = false

        init {
            webSocket.onopen = {
                isReady = true
                doOnReady?.invoke()

                readyCallback()
            }
            webSocket.onclose = {
                window.setTimeout({
                    receiveChannel.close(RpcResponseException(webSocket.url, "WS", Codes.from((it as CloseEvent).code.toInt()), (it as CloseEvent).reason))
                }, 20)
            }
            webSocket.onerror = {
                window.setTimeout({
                    receiveChannel.close(RpcResponseException(webSocket.url, "WS", Codes.UNKNOWN, (it as ErrorEvent).message))
                }, 20)
            }
            webSocket.onmessage = { event ->
                if (js("event.data instanceof ArrayBuffer") as Boolean) {
                    val data = Int8Array(event.data as ArrayBuffer).unsafeCast<ByteArray>()

                    receiveChannel.trySend(data)
                } else {
                    val reader = FileReader()

                    reader.onloadend = {
                        val buffer = reader.result as ArrayBuffer
                        val data = Int8Array(buffer).unsafeCast<ByteArray>()

                        receiveChannel.trySend(data)
                    }

                    reader.readAsArrayBuffer(event.data as Blob)
                }
            }
        }

        @OptIn(InternalCoroutinesApi::class)
        override suspend fun receive(): ByteArray {
            val receiveOrClosed = receiveChannel.receiveCatching()

            if (!receiveOrClosed.isSuccess) {
                throw Exception("Closed during receive")
            }

            return receiveOrClosed.getOrNull()!!
        }

        override suspend fun send(bytes: ByteArray) {
            doOnReady = {
                webSocket.send(Int8Array(bytes.toTypedArray()))
            }

            if (isReady) {
                doOnReady?.invoke()
            }
        }

        override suspend fun closeOutbound() {
        }

        override suspend fun closeInbound() {
            receiveChannel.close()
        }
    }

    actual override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit,
        readyCallback: () -> Unit
    ) {
        if (isNode) {
            js("global.WebSocket = require('websocket').w3cwebsocket")
        }

        val serverWsUrl = if (secure) serverPath.replace("https:", "wss:") else "ws://${serverPath.replace("http://", "")}"
        val wsUrl = if (useApiGateway) {
            method.toApiGatewayPath(serverWsUrl)
        } else {
            method.toPath(serverWsUrl)
        }
        val webSocket = WebSocket(wsUrl)
        val stream = WebSocketServerStream(webSocket, readyCallback)

        stream.block()
    }
}
