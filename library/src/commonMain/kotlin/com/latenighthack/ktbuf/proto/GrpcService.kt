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
                        if (ex.cause != null) {
                            this@channelFlow.cancel(ex.cause!!.toString(), ex.cause!!)
                        }

                        return@serverStreamingCall
                    }

                    val readBytes = MutableLinkedByteArray()
                    val reader = ScopedProtobufReader(readBytes)
                    readBytes.insert(responseBytes)

                    val response = try {
                        val resp = readResponse(reader)

                        resp
                    } catch (ex: Throwable) {
                        this@channelFlow.cancel("failed to unmarshal response", ex)
                        return@serverStreamingCall
                    }

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

    @OptIn(InternalCoroutinesApi::class)
    protected fun <RequestType, ResponseType> clientStreamServerStream(
        methodName: String,
        request: Flow<RequestType>,
        writeRequest: RequestType.(ProtobufWriter) -> Unit,
        readResponse: (ProtobufReader) -> ResponseType,
        generateExtraParams: ((RequestType) -> Map<String, String>)? = null,
        readyCallback: () -> Unit = {}
    ): Flow<ResponseType> = channelFlow {
        // Lossless, ordered request pipeline. The previous shareIn(replay = 1) dropped any
        // request emitted between the first() peek and the transport's subscription — a client
        // that follows its open with an immediate second request (pending acks) lost the open
        // itself and could never establish the stream.
        val firstRequest = CompletableDeferred<RequestType>()
        val requests = Channel<RequestType>(Channel.UNLIMITED)

        launch {
            try {
                request.collect { nextRequest ->
                    if (!firstRequest.isCompleted) {
                        firstRequest.complete(nextRequest)
                    }
                    requests.send(nextRequest)
                }
                if (!firstRequest.isCompleted) {
                    firstRequest.completeExceptionally(NoSuchElementException("request flow completed without emitting"))
                }
            } catch (cause: Throwable) {
                if (!firstRequest.isCompleted) {
                    firstRequest.completeExceptionally(cause)
                }
                throw cause
            } finally {
                requests.close()
            }
        }

        val first = firstRequest.await()

        val extraParams = generateExtraParams?.invoke(first) ?: emptyMap()

        rpc.serverStreamingCall(
            RpcMethodSpecifier(packageName, serviceName, methodName, extraParams),
            {
                var keepGoing = true
                val requestJob = launch {
                    try {
                        for (nextRequest in requests) {
                            val requestBytes = ProtobufOutputStream()
                                .also {
                                    it.write {
                                        nextRequest.writeRequest(it)
                                    }
                                }
                                .toByteArray()

                            send(requestBytes)
                        }
                    } finally {
                        // Request side exhausted (flow completed and queue drained) or cancelled.
                        keepGoing = false
                        closeInbound()
                    }
                }

                try {
                    while (keepGoing) {
                        val bytes = receive()
                        val readBytes = MutableLinkedByteArray()
                        val reader = ScopedProtobufReader(readBytes)

                        readBytes.insert(bytes)

                        val response = try {
                            val resp = readResponse(reader)

                            resp
                        } catch (ex: Throwable) {
                            this@channelFlow.cancel("failed to unmarshal response", ex)
                            return@serverStreamingCall
                        }

                        this@channelFlow.send(response)
                    }
                } catch (ex: Throwable) {
                } finally {
                    requestJob.cancel()
                    closeOutbound()
                }
            }, readyCallback
        )
    }
}
