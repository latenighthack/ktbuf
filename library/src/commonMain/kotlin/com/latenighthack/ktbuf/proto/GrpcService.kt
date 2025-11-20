@file:OptIn(DelicateCoroutinesApi::class)

package com.latenighthack.ktbuf.proto

import com.latenighthack.ktbuf.*
import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.bytes.MutableLinkedByteArray
import com.latenighthack.ktbuf.net.RpcMethodSpecifier
import kotlinx.coroutines.*
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
        val completion = CompletableDeferred<Unit>()
        val shared = request
            .onCompletion { cause ->
                if (cause == null) {
                    completion.complete(Unit)
                } else {
                    completion.completeExceptionally(cause)
                    throw cause
                }
            }
            .shareIn(this, SharingStarted.Eagerly, replay = 1)

        val first = shared.first()

        val extraParams = generateExtraParams?.invoke(first) ?: emptyMap()

        rpc.serverStreamingCall(
            RpcMethodSpecifier(packageName, serviceName, methodName, extraParams),
            {
                var keepGoing = true
                val requestJob = launch {
                    shared
                        .onCompletion {
                            keepGoing = false
                            closeInbound()
                        }
                        .collect { nextRequest ->
                            val requestBytes = ProtobufOutputStream()
                                .also {
                                    it.write {
                                        nextRequest.writeRequest(it)
                                    }
                                }
                                .toByteArray()

                            send(requestBytes)
                        }
                }

                launch {
                    completion.await()
                    requestJob.cancel()
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
