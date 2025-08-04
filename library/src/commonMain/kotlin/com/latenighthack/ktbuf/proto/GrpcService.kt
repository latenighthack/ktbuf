@file:OptIn(DelicateCoroutinesApi::class)

package com.latenighthack.ktbuf.proto

import com.latenighthack.ktbuf.*
import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.bytes.MutableLinkedByteArray
import com.latenighthack.ktbuf.net.RpcMethodSpecifier
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

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
        readResponse: (ProtobufReader) -> ResponseType
    ): Flow<ResponseType> = channelFlow {
        val requestBytes = ProtobufOutputStream()
            .also {
                it.write {
                    request.writeRequest(it)
                }
            }
            .toByteArray()

        rpc.serverStreamingCall(RpcMethodSpecifier(packageName, serviceName, methodName)) {
            println("RPC: Connecting to 1 $methodName")

            send(requestBytes)

            println("RPC: Sent to 2 $methodName")

            while (true) {
                val responseBytes = try {
                    println("RPC: Trying to receive to 3 $methodName")
                    val bytes = receive()
                    println("RPC: Receiving in 4 $methodName = ${bytes.size}")

                    bytes
                } catch (ex: ClosedReceiveChannelException) {
                    println("RPC: Closed ReceiveChannelException")
                    if (ex.cause != null) {
                        println("RPC: Cancelled")
                        this@channelFlow.cancel(ex.cause!!.toString(), ex.cause!!)
                    }
                    println("RPC: Stopped without cancel")

                    return@serverStreamingCall
                }

                println("RPC: Got bytes $methodName")
                val readBytes = MutableLinkedByteArray()
                val reader = ScopedProtobufReader(readBytes)
                readBytes.insert(responseBytes)
                println("RPC: Proceeding with bytes $methodName")

                val response = try {
                    println("RPC: Trying read $methodName")
                    val resp = readResponse(reader)
                    println("RPC: Did read $methodName")

                    resp
                } catch (ex: Throwable) {
                    println("RPC: failed to parse $methodName")
                    this@channelFlow.cancel("failed to unmarshal response", ex)
                    return@serverStreamingCall
                }

                println("RPC: sending response $methodName")
                this@channelFlow.send(response)
                println("RPC: sent response $methodName")
            }
        }
    }

    protected suspend fun <RequestType, ResponseType> clientUnaryServerUnary(
        methodName: String,
        request: RequestType,
        writeRequest: RequestType.(ProtobufWriter) -> Unit,
        readResponse: (ProtobufReader) -> ResponseType
    ): ResponseType {
        return rpc.unaryCall(
            RpcMethodSpecifier(packageName, serviceName, methodName), emptyMap(),
            ProtobufOutputStream()
                .also {
                    it.write {
                        request.writeRequest(it)
                    }
                }
                .toByteArray()
        )
            .let { response ->
                ProtobufInputStream()
                    .let { stream ->
                        stream.addBytes(response.data)
                        stream.read {
                            readResponse(it)
                        }
                    }
            }
    }

    protected fun <RequestType, ResponseType> clientStreamServerStream(
        methodName: String,
        request: Flow<RequestType>,
        writeRequest: RequestType.(ProtobufWriter) -> Unit,
        readResponse: (ProtobufReader) -> ResponseType
    ): Flow<ResponseType> = channelFlow {
        rpc.serverStreamingCall(RpcMethodSpecifier(packageName, serviceName, methodName)) {
            GlobalScope.launch {
                request.collect { nextRequest ->
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

            while (true) {
                val bytes = receive()
                val readBytes = MutableLinkedByteArray()
                val reader = ScopedProtobufReader(readBytes)

                readBytes.insert(bytes)

                val field = reader.readField {
                    readResponse(it)
                }

                this@channelFlow.send(field)
            }
        }
    }
}
