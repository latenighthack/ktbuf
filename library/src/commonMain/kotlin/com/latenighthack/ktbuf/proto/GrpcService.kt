@file:OptIn(DelicateCoroutinesApi::class)

package com.latenighthack.ktbuf.proto

import com.latenighthack.ktbuf.*
import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.bytes.MutableLinkedByteArray
import com.latenighthack.ktbuf.net.RpcMethodSpecifier
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.*

open class GrpcService(private val rpc: RpcClient, private val packageName: String, private val serviceName: String) {
    data class MethodDescriptor<Req : Any, Res : Any>(
        val methodName: String,
        val requestParser: (ProtobufReader) -> Req,
        val responseSerializer: (ProtobufWriter, Any) -> Unit,
        val streamingIn: Boolean,
        val streamingOut: Boolean,
        val dummy: Int = 0
    ) {
        constructor(
            methodName: String,
            requestParser: (ProtobufReader) -> Req,
            typedResponseSerializer: Res.(ProtobufWriter) -> Unit,
            streamingIn: Boolean,
            streamingOut: Boolean
        ) : this(methodName, requestParser, { writer, type ->
            @Suppress("UNCHECKED_CAST")
            (type as Res).typedResponseSerializer(writer)
        }, streamingIn, streamingOut)
    }

    data class ServiceDescriptor(
        val packageName: String,
        val serviceName: String,
        val methods: List<MethodDescriptor<*, *>>
    )

    protected fun <RequestType, ResponseType> clientUnaryServerStream(
        methodName: String,
        request: RequestType,
        writeRequest: RequestType.(ProtobufWriter) -> Unit,
        readResponse: (ProtobufReader) -> ResponseType,
        generateExtraParams: ((RequestType) -> Map<String, String>)? = null,
        readyCallback: () -> Unit = {}
    ): Flow<ResponseType> = channelFlow {
        val requestBytes = ProtobufOutputStream()
            .also {
                it.write {
                    request.writeRequest(it)
                }
            }
            .toByteArray()

        val extraParams = generateExtraParams?.let { it(request) } ?: emptyMap()

        rpc.serverStreamingCall(
            RpcMethodSpecifier(packageName, serviceName, methodName, extraParams),
            {
                send(requestBytes)
                readyCallback()

                while (true) {
                    val responseBytes = try {
                        val bytes = receive()

                        bytes
                    } catch (ex: ClosedReceiveChannelException) {
                        streamCloseFailure(ex)?.let { throw it }
                        return@serverStreamingCall
                    }

                    val readBytes = MutableLinkedByteArray()
                    val reader = ScopedProtobufReader(readBytes)
                    readBytes.insert(responseBytes)

                    val response = readResponse(reader)

                    this@channelFlow.send(response)
                }
            }, {}
        )
    }

    protected suspend fun <RequestType, ResponseType> clientUnaryServerUnary(
        methodName: String,
        request: RequestType,
        writeRequest: RequestType.(ProtobufWriter) -> Unit,
        readResponse: (ProtobufReader) -> ResponseType,
        generateExtraParams: ((RequestType) -> Map<String, String>)? = null,
        readyCallback: () -> Unit = {}
    ): ResponseType {
        val extraParams = generateExtraParams?.let { it(request) } ?: emptyMap()
        return rpc.unaryCall(
            RpcMethodSpecifier(packageName, serviceName, methodName, extraParams), emptyMap(),
            ProtobufOutputStream()
                .also {
                    it.write {
                        request.writeRequest(it)
                    }
                }
                .toByteArray()
        )
            .let { response ->
                readyCallback()
                ProtobufInputStream()
                    .let { stream ->
                        stream.addBytes(response.data)
                        stream.read {
                            readResponse(it)
                        }
                    }
            }
    }

    protected suspend fun <RequestType, ResponseType> clientStreamServerUnary(
        methodName: String,
        request: Flow<RequestType>,
        writeRequest: RequestType.(ProtobufWriter) -> Unit,
        readResponse: (ProtobufReader) -> ResponseType,
        generateExtraParams: ((RequestType) -> Map<String, String>)? = null,
        readyCallback: () -> Unit = {}
    ): ResponseType {
        var seen = false
        var response: ResponseType? = null
        clientStreamServerStream(methodName, request, writeRequest, readResponse, generateExtraParams, readyCallback).collect {
            if (seen) throw com.latenighthack.ktbuf.net.RpcResponseException(methodName, "WS", Codes.DATA_LOSS, "Expected exactly one RPC response")
            seen = true
            response = it
        }
        if (!seen) throw com.latenighthack.ktbuf.net.RpcResponseException(methodName, "WS", Codes.DATA_LOSS, "Expected exactly one RPC response")
        @Suppress("UNCHECKED_CAST")
        return response as ResponseType
    }

    @OptIn(InternalCoroutinesApi::class)
    protected fun <RequestType, ResponseType> clientStreamServerStream(
        methodName: String,
        request: Flow<RequestType>,
        writeRequest: RequestType.(ProtobufWriter) -> Unit,
        readResponse: (ProtobufReader) -> ResponseType,
        generateExtraParams: ((RequestType) -> Map<String, String>)? = null,
        readyCallback: () -> Unit = {}
    ): Flow<ResponseType> = channelFlow {
        val firstRequest = CompletableDeferred<RequestType?>()
        val requests = Channel<RequestType>(16)
        val producer = launch {
            try {
                request.collect { nextRequest ->
                    if (!firstRequest.isCompleted) firstRequest.complete(nextRequest)
                    requests.send(nextRequest)
                }
                if (!firstRequest.isCompleted) firstRequest.complete(null)
                requests.close()
            } catch (cause: Throwable) {
                firstRequest.completeExceptionally(cause)
                requests.close(cause)
                throw cause
            }
        }
        try {
            val first = firstRequest.await()
            val extraParams = if (first == null) emptyMap() else generateExtraParams?.invoke(first) ?: emptyMap()
            rpc.serverStreamingCall(
                RpcMethodSpecifier(packageName, serviceName, methodName, extraParams),
                {
                    coroutineScope {
                        val sender = launch {
                            for (nextRequest in requests) {
                                val bytes = ProtobufOutputStream.encode { nextRequest.writeRequest(this) }
                                send(bytes)
                            }
                            closeOutbound()
                        }
                        try {
                            while (true) {
                                val bytes = try { receive() } catch (_: ClosedReceiveChannelException) { break }
                                val reader = ScopedProtobufReader(com.latenighthack.ktbuf.bytes.mutableLinkedByteArrayOf(*bytes))
                                this@channelFlow.send(readResponse(reader))
                            }
                        } finally {
                            sender.cancelAndJoin()
                        }
                    }
                }, readyCallback
            )
        } finally {
            producer.cancelAndJoin()
            requests.cancel()
        }
    }
}

/** Coroutine stacktrace recovery can wrap a normal closed-channel exception as its own cause. */
private fun streamCloseFailure(closed: ClosedReceiveChannelException): Throwable? {
    var cause: Throwable? = closed.cause
    repeat(64) {
        if (cause !is ClosedReceiveChannelException) return cause
        cause = cause?.cause
    }
    return cause
}
