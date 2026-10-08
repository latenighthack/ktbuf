package com.latenighthack.ktbuf.server

import com.latenighthack.ktbuf.ProtobufInputStream
import com.latenighthack.ktbuf.ProtobufLimits
import com.latenighthack.ktbuf.ProtobufLimitException
import com.latenighthack.ktbuf.bytes.MutableLinkedByteArray
import com.latenighthack.ktbuf.proto.Codes
import io.ktor.utils.io.*
import com.latenighthack.ktbuf.ProtobufOutputStream
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.selects.whileSelect

fun ApplicationCall.toGrpcRequestContext(
    descriptor: ServerDescriptor,
    methodDescriptor: ServerMethodDescriptor<*, *, *>,
) = GrpcRequestContext(
    request.path(),
    request.headers.entries().map { Pair(it.key, it.value.firstOrNull() ?: "") }.toMap(),
    request.queryParameters.entries().map { Pair(it.key, it.value.firstOrNull() ?: "") }.toMap(),
    mapOf(),
    descriptor,
    methodDescriptor
)

public fun <Server: Any> Routing.serveAll(
    server: Server,
    descriptor: ServerDescriptor,
    contextProcessor: (GrpcRequestContext) -> GrpcRequestContext = { it }
) = serveAll(server, descriptor, ProtobufLimits(), contextProcessor)

public fun <Server: Any> Routing.serveAll(
    server: Server,
    descriptor: ServerDescriptor,
    limits: ProtobufLimits,
    contextProcessor: (GrpcRequestContext) -> GrpcRequestContext = { it }
) {
    for (method in descriptor.methods) {
        if (method.streamingIn && method.streamingOut) {
            serveStreamingInOut<Server>(server, descriptor, method as ServerMethodDescriptor<Any, Any, Server>, limits, contextProcessor)
        } else if (method.streamingIn) {
            serveStreamingInOut<Server>(server, descriptor, method as ServerMethodDescriptor<Any, Any, Server>, limits, contextProcessor)
        } else if (method.streamingOut) {
            serveStreamingOut<Server>(server, descriptor, method as ServerMethodDescriptor<Any, Any, Server>, limits, contextProcessor)
        } else {
            serveUnary<Server>(server, descriptor, method as ServerMethodDescriptor<Any, Any, Server>, limits, contextProcessor)
        }
    }
}

public fun <Server: Any> Routing.serveUnary(
    server: Server,
    descriptor: ServerDescriptor,
    methodDescriptor: ServerMethodDescriptor<Any, Any, Server>,
    contextProcessor: (GrpcRequestContext) -> GrpcRequestContext = { it }
) = serveUnary(server, descriptor, methodDescriptor, ProtobufLimits(), contextProcessor)

public fun <Server: Any> Routing.serveUnary(
    server: Server,
    descriptor: ServerDescriptor,
    methodDescriptor: ServerMethodDescriptor<Any, Any, Server>,
    limits: ProtobufLimits,
    contextProcessor: (GrpcRequestContext) -> GrpcRequestContext = { it }
) {
    val path = "/api/${descriptor.packageName}.${descriptor.serviceName}/${methodDescriptor.methodName}"
    post(path) {
        try {
            val context = contextProcessor(call.toGrpcRequestContext(descriptor, methodDescriptor))
            val request = decodeRequest(call.readBoundedBody(limits.maxMessageBytes), limits, methodDescriptor.requestParser)
            val handler = methodDescriptor.handler as ServerMethod.Unary

            val outgoing = handler.handler.invoke(server, context, request)
            val outStream = ProtobufOutputStream()

            outStream.write { writer ->
                methodDescriptor.responseSerializer.invoke(writer, outgoing)
            }

            val outgoingBytes = outStream.toByteArray()

            call.respond(outgoingBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (limit: ProtobufLimitException) {
            call.response.headers.append(RPC_STATUS_HEADER, Codes.RESOURCE_EXHAUSTED.value.toString())
            call.respondText("Request exceeds decoding limits", status = HttpStatusCode.TooManyRequests)
        } catch (rpcException: RpcResponseException) {
            call.response.headers.append(RPC_STATUS_HEADER, rpcException.code.value.toString())
            call.respondText(
                rpcException.errorMessage,
                status = HttpStatusCode(rpcException.code.toHttpCode(), rpcException.code.name)
            )
        } catch (t: Exception) {
            call.application.log.error("RPC handler failed ($path)", t)
            call.response.headers.append(RPC_STATUS_HEADER, Codes.INTERNAL.value.toString())
            call.respondText("Internal server error", status = HttpStatusCode.InternalServerError)
        }
    }
}

public fun <Server: Any> Routing.serveStreamingOut(
    server: Server,
    descriptor: ServerDescriptor,
    methodDescriptor: ServerMethodDescriptor<Any, Any, Server>,
    contextProcessor: (GrpcRequestContext) -> GrpcRequestContext = { it }
) = serveStreamingOut(server, descriptor, methodDescriptor, ProtobufLimits(), contextProcessor)

public fun <Server: Any> Routing.serveStreamingOut(
    server: Server,
    descriptor: ServerDescriptor,
    methodDescriptor: ServerMethodDescriptor<Any, Any, Server>,
    limits: ProtobufLimits,
    contextProcessor: (GrpcRequestContext) -> GrpcRequestContext = { it }
) {
    webSocket("/api/${descriptor.packageName}.${descriptor.serviceName}/${methodDescriptor.methodName}") {
        maxFrameSize = limits.maxMessageBytes.toLong()
        try {
            val context = contextProcessor(call.toGrpcRequestContext(descriptor, methodDescriptor))
            val frame = incoming.receive()
            if (frame !is Frame.Binary) throw RpcResponseException("", "WS", Codes.INVALID_ARGUMENT, "Expected binary RPC request")
            val request = decodeRequest(frame.readBytes(), limits, methodDescriptor.requestParser)
            (methodDescriptor.handler as ServerMethod.ServerStreaming).handler.invoke(server, context, request)
                .takeWhile { it !is StreamControlEvent.Close<*> }
                .collect { event ->
                    val outStream = ProtobufOutputStream()

                    when (event) {
                        is StreamControlEvent.Close<*> -> {
                            close()
                        }
                        is StreamControlEvent.Message<*> -> {
                            val outgoing = event.message

                            outStream.write { writer ->
                                methodDescriptor.responseSerializer.invoke(writer, outgoing)
                            }

                            val outgoingBytes = outStream.toByteArray()

                            send(outgoingBytes)
                        }
                    }
                }
            close(CloseReason(CloseReason.Codes.NORMAL, ""))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (limit: ProtobufLimitException) {
            close(reason = CloseReason(4008.toShort(), "Request exceeds decoding limits"))
        } catch (rpcException: RpcResponseException) {
            close(reason = CloseReason(rpcException.code.toWSCode().toShort(), safeWSReason(rpcException.errorMessage)))
        } catch (t: Exception) {
            call.application.log.error("RPC streaming handler failed (${call.request.path()})", t)
            close(reason = CloseReason(Codes.INTERNAL.toWSCode().toShort(), "Internal server error"))
        }
    }
}


public fun <Server: Any> Routing.serveStreamingInOut(
    server: Server,
    descriptor: ServerDescriptor,
    methodDescriptor: ServerMethodDescriptor<Any, Any, Server>,
    contextProcessor: (GrpcRequestContext) -> GrpcRequestContext = { it }
) = serveStreamingInOut(server, descriptor, methodDescriptor, ProtobufLimits(), contextProcessor)

public fun <Server: Any> Routing.serveStreamingInOut(
    server: Server,
    descriptor: ServerDescriptor,
    methodDescriptor: ServerMethodDescriptor<Any, Any, Server>,
    limits: ProtobufLimits,
    contextProcessor: (GrpcRequestContext) -> GrpcRequestContext = { it }
) {
    // Prefer the negotiated route while retaining legacy long-lived request streams.
    for (protocol in listOf(RPC_STREAM_PROTOCOL, null)) {
        webSocket("/api/${descriptor.packageName}.${descriptor.serviceName}/${methodDescriptor.methodName}", protocol) {
            maxFrameSize = limits.maxMessageBytes.toLong()
            try {
                val context = contextProcessor(call.toGrpcRequestContext(descriptor, methodDescriptor))
                val requests = kotlinx.coroutines.flow.flow {
                    while (true) {
                        val frame = try { incoming.receive() } catch (_: ClosedReceiveChannelException) { break }
                        if (frame is Frame.Text) {
                            if (protocol == RPC_STREAM_PROTOCOL && frame.readText() == RPC_REQUESTS_END) break
                            throw RpcResponseException("", "WS", Codes.INVALID_ARGUMENT, "Unexpected RPC text frame")
                        }
                        if (frame !is Frame.Binary) throw RpcResponseException("", "WS", Codes.INVALID_ARGUMENT, "Expected binary RPC request")
                        emit(decodeRequest(frame.readBytes(), limits, methodDescriptor.requestParser))
                    }
                }
                val events = when (val handler = methodDescriptor.handler) {
                    is ServerMethod.ClientStreaming -> kotlinx.coroutines.flow.flow {
                        emit(StreamControlEvent.Message(handler.handler.invoke(server, context, requests)))
                    }
                    is ServerMethod.ClientServerStreaming -> handler.handler.invoke(server, context, requests)
                    else -> error("Invalid client streaming handler")
                }
                events.takeWhile { it !is StreamControlEvent.Close<*> }.collect { event ->
                    when (event) {
                        is StreamControlEvent.Close<*> -> close()
                        is StreamControlEvent.Message<*> -> {
                            val output = ProtobufOutputStream()
                            output.write { methodDescriptor.responseSerializer.invoke(it, event.message) }
                            send(output.toByteArray())
                        }
                    }
                }
                close(CloseReason(CloseReason.Codes.NORMAL, ""))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (limit: ProtobufLimitException) {
                close(CloseReason(4008.toShort(), "Request exceeds decoding limits"))
            } catch (rpcException: RpcResponseException) {
                close(CloseReason(rpcException.code.toWSCode().toShort(), safeWSReason(rpcException.errorMessage)))
            } catch (t: Exception) {
                call.application.log.error("RPC streaming handler failed (${call.request.path()})", t)
                close(CloseReason(Codes.INTERNAL.toWSCode().toShort(), "Internal server error"))
            }
        }
    }
}

private suspend fun ApplicationCall.readBoundedBody(limit: Int): ByteArray {
    val channel = receiveChannel()
    val bytes = MutableLinkedByteArray(minOf(8192, limit))
    val chunk = ByteArray(8192)
    while (true) {
        val count = channel.readAvailable(chunk)
        if (count < 0) break
        if (count > limit - bytes.size) throw RpcResponseException("", "POST", Codes.RESOURCE_EXHAUSTED, "Request exceeds byte limit")
        bytes.write(chunk, length = count)
    }
    return bytes.toByteArray()
}

private fun <T> decodeRequest(bytes: ByteArray, limits: ProtobufLimits, parser: (com.latenighthack.ktbuf.ProtobufReader) -> T): T {
    try {
        val input = ProtobufInputStream(limits = limits)
        input.addBytes(bytes)
        return input.read(parser)
    } catch (limit: ProtobufLimitException) {
        throw limit
    } catch (malformed: IllegalArgumentException) {
        throw RpcResponseException("", "DECODE", Codes.INVALID_ARGUMENT, "Malformed protobuf request", malformed)
    } catch (malformed: IndexOutOfBoundsException) {
        throw RpcResponseException("", "DECODE", Codes.INVALID_ARGUMENT, "Malformed protobuf request", malformed)
    }
}
