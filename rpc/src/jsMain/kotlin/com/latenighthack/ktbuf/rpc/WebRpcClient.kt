package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.proto.RpcClient
import com.latenighthack.ktbuf.proto.Status
import com.latenighthack.ktbuf.proto.fromHTTPCode
import com.latenighthack.ktbuf.proto.toPath
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

private fun statusHandler(xhr: XMLHttpRequest, coroutineContext: Continuation<RpcClient.Response>) {
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
            coroutineContext.resume(RpcClient.Response(Int8Array(xhr.response as ArrayBuffer).unsafeCast<ByteArray>(), headers))
        } else {
            val status = Status.fromHTTPCode(xhr.status.toInt(), xhr.responseText)
            coroutineContext.resumeWithException(RpcClient.ResponseException(xhr.responseURL, "POST", status.code, status.message))
        }
    }
}

private suspend fun httpPost(host: String, headers: Map<String, String>, path: String, secure: Boolean, data: ByteArray): RpcClient.Response = suspendCoroutine { c ->
    val localHost = host
    val localPath = path
    val localSecure = secure
    val localData = data

    val scheme = if (secure) "https" else "http"

    val xhr = XMLHttpRequest()

    xhr.onreadystatechange = { _ ->
        statusHandler(xhr, c)
    }
    xhr.open("POST", "$scheme://$host$path", true)
    xhr.responseType = XMLHttpRequestResponseType.ARRAYBUFFER
    xhr.setRequestHeader("Content-type", "application/proto")

    xhr.send(data)
}

public class WebRpcClient(private val serverUrl: String, private val secure: Boolean) : RpcClient {
    override suspend fun unaryCall(
        method: RpcClient.MethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcClient.Response {
        return httpPost(serverUrl, headers, method.toPath(""), secure, request)
    }

    private class WebSocketServerStream(private val webSocket: WebSocket) : RpcClient.ServerStream {
        private val receiveChannel = Channel<ByteArray>()
        private var doOnReady: (() -> Unit)? = null
        private var isReady = false

        init {
            webSocket.onopen = {
                isReady = true
                doOnReady?.invoke()
            }
            webSocket.onclose = {
                window.setTimeout({
                    println("stream closed: ${(it as CloseEvent).code}, ${(it as CloseEvent).reason}, ${(it as CloseEvent).type}")
                    receiveChannel.close()
                }, 20)
            }
            webSocket.onerror = {
                println("stream error ${(it as ErrorEvent).message}")
                window.setTimeout({
                    receiveChannel.close()
                }, 20)
            }
            webSocket.onmessage = { event ->
                if (js("event.data instanceof ArrayBuffer") as Boolean) {
                    println("Message received")
                    val data = Int8Array(event.data as ArrayBuffer).unsafeCast<ByteArray>()

                    receiveChannel.trySend(data)
                } else {
                    println("Other message event")
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
    }

    override suspend fun serverStreamingCall(
        method: RpcClient.MethodSpecifier,
        block: suspend RpcClient.ServerStream.() -> Unit
    ) {
        if (isNode) {
            js("global.WebSocket = require('websocket').w3cwebsocket")
        }

        val scheme = if (secure) "wss" else "ws"

        val webSocket = WebSocket(method.toPath("$scheme://$serverUrl"))
        val stream = WebSocketServerStream(webSocket)

        stream.block()
    }
}
