package com.latenighthack.ktbuf.server

import com.latenighthack.ktbuf.ProtobufInputStream
import com.latenighthack.ktbuf.ProtobufLimits
import com.latenighthack.ktbuf.ProtobufLimitException
import com.latenighthack.ktbuf.ProtobufOutputLimits
import com.latenighthack.ktbuf.ProtobufOutputLimitException
import com.latenighthack.ktbuf.bytes.ByteArrayLimitException
import kotlinx.coroutines.CancellationException
import com.latenighthack.ktbuf.bytes.MutableLinkedByteArray
import com.latenighthack.ktbuf.proto.Codes
import io.ktor.utils.io.*
import com.latenighthack.ktbuf.ProtobufOutputStream
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.toHttpCode
import com.latenighthack.ktbuf.proto.RPC_STATUS_HEADER
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.cancel
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
            val stream = ProtobufInputStream(limits = limits)
            stream.addBytes(call.readBoundedBody(limits.maxMessageBytes))
            val request = stream.read(methodDescriptor.requestParser)
            val handler = methodDescriptor.handler as ServerMethod.Unary

            val outgoing = handler.handler.invoke(server, context, request)
            val outStream = ProtobufOutputStream(limits = ProtobufOutputLimits(limits.maxMessageBytes, limits.maxDepth, limits.maxWork))

            outStream.write { writer ->
                methodDescriptor.responseSerializer.invoke(writer, outgoing)
            }

            val outgoingBytes = outStream.toByteArray()

            call.rpcStatusHeader(Codes.OK)
            call.respond(outgoingBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (limit: ProtobufOutputLimitException) {
            call.respondRpcError(Codes.OUT_OF_RANGE, "OutOfRange: response exceeds encoding limits")
        } catch (limit: ByteArrayLimitException) {
            call.respondRpcError(Codes.OUT_OF_RANGE, "OutOfRange: protobuf buffer exceeds byte limits")
        } catch (limit: ProtobufLimitException) {
            call.respondRpcError(Codes.OUT_OF_RANGE, "OutOfRange: request exceeds decoding limits")
        } catch (rpcException: RpcResponseException) {
            call.respondRpcError(rpcException.code, rpcException.errorMessage)
        } catch (invalid: IllegalArgumentException) {
            call.respondRpcError(Codes.INVALID_ARGUMENT, "InvalidArgument: malformed protobuf")
        } catch (t: Throwable) {
            println("gRPC unary error($path): $t")
            t.printStackTrace()
            call.respondRpcError(Codes.UNKNOWN, t.toString())
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
            val stream = ProtobufInputStream(limits = limits)
            val frame = incoming.receive()
            stream.addBytes(frame.readBytes())
            val request = stream.read(methodDescriptor.requestParser)
            (methodDescriptor.handler as ServerMethod.ServerStreaming).handler.invoke(server, context, request)
                .onCompletion {
                    close(reason = rpcCloseReason(it))
                }
                .collect { event ->
                    val outStream = ProtobufOutputStream(limits = ProtobufOutputLimits(limits.maxMessageBytes, limits.maxDepth, limits.maxWork))

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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            close(reason = rpcCloseReason(failure))
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
    webSocket("/api/${descriptor.packageName}.${descriptor.serviceName}/${methodDescriptor.methodName}") {
        maxFrameSize = limits.maxMessageBytes.toLong()
        try {
            val context = contextProcessor(call.toGrpcRequestContext(descriptor, methodDescriptor))
            val handler = (methodDescriptor.handler as ServerMethod.ClientServerStreaming).handler

        val requestFlow = channelFlow {
            whileSelect {
                incoming.onReceiveCatching {
                    if (it.isSuccess) {
                        val stream = ProtobufInputStream(limits = limits)
                        val frame = it.getOrNull()!!

                        stream.addBytes(frame.readBytes())

                        val request = stream.read(methodDescriptor.requestParser)

                        this@channelFlow.send(request)

                        true
                    } else {
                        incoming.cancel()
                        this@channelFlow.close()
                        false
                    }
                }
            }
        }

            handler(server, context, requestFlow)
                .onCompletion {
                    close(reason = rpcCloseReason(it))
                }
                .collect { event ->
                    val outStream = ProtobufOutputStream(limits = ProtobufOutputLimits(limits.maxMessageBytes, limits.maxDepth, limits.maxWork))

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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            close(reason = rpcCloseReason(failure))
        }
    }
}

private suspend fun ApplicationCall.readBoundedBody(limit: Int): ByteArray {
    val channel = receiveChannel()
    val bytes = MutableLinkedByteArray(minOf(8192, limit), maxSize = limit)
    val chunk = ByteArray(8192)
    while (true) {
        val count = channel.readAvailable(chunk)
        if (count < 0) break
        if (count > limit - bytes.size) throw RpcResponseException("", "POST", Codes.OUT_OF_RANGE, "OutOfRange: request exceeds byte limit")
        bytes.write(chunk, length = count)
    }
    return bytes.toByteArray()
}

private fun rpcCloseReason(failure: Throwable?): CloseReason = when (failure) {
    null, is CancellationException -> CloseReason(CloseReason.Codes.NORMAL, "")
    is ProtobufLimitException, is ProtobufOutputLimitException, is ByteArrayLimitException -> CloseReason(4011.toShort(), "Protobuf resource limit exceeded")
    is RpcResponseException -> CloseReason((4000 + failure.code.value).toShort(), failure.code.name)
    is IllegalArgumentException -> CloseReason(4003.toShort(), "Malformed protobuf")
    else -> CloseReason(CloseReason.Codes.INTERNAL_ERROR, "")
}

private fun ApplicationCall.rpcStatusHeader(code: Codes) {
    response.headers.append(RPC_STATUS_HEADER, code.value.toString())
    // Browser clients need access when an embedding host enables cross-origin RPCs.
    response.headers.append(HttpHeaders.AccessControlExposeHeaders, RPC_STATUS_HEADER)
}
private suspend fun ApplicationCall.respondRpcError(code: Codes, message: String) {
    rpcStatusHeader(code)
    respondText(message, status = HttpStatusCode(code.toHttpCode(), code.name))
}
