package com.latenighthack.ktbuf.server

import com.latenighthack.ktbuf.ProtobufInputStream
import com.latenighthack.ktbuf.ProtobufOutputStream
import com.latenighthack.ktbuf.net.*
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
) {
    for (method in descriptor.methods) {
        if (method.streamingIn && method.streamingOut) {
            serveStreamingInOut<Server>(server, descriptor, method as ServerMethodDescriptor<Any, Any, Server>, contextProcessor)
        } else if (method.streamingIn) {
            serveStreamingInOut<Server>(server, descriptor, method as ServerMethodDescriptor<Any, Any, Server>, contextProcessor)
        } else if (method.streamingOut) {
            serveStreamingOut<Server>(server, descriptor, method as ServerMethodDescriptor<Any, Any, Server>, contextProcessor)
        } else {
            serveUnary<Server>(server, descriptor, method as ServerMethodDescriptor<Any, Any, Server>)
        }
    }
}

public fun <Server: Any> Routing.serveUnary(
    server: Server,
    descriptor: ServerDescriptor,
    methodDescriptor: ServerMethodDescriptor<Any, Any, Server>,
    contextProcessor: (GrpcRequestContext) -> GrpcRequestContext = { it }
) {
    val path = "/api/${descriptor.packageName}.${descriptor.serviceName}/${methodDescriptor.methodName}"
    post(path) {
        val stream = ProtobufInputStream()

        stream.addBytes(call.receive<ByteArray>())

        val request = stream.read(methodDescriptor.requestParser)
        try {
            val handler = methodDescriptor.handler as ServerMethod.Unary
            val context = contextProcessor(call.toGrpcRequestContext(
                descriptor,
                methodDescriptor
            ))

            val outgoing = handler.handler.invoke(server, context, request)
            val outStream = ProtobufOutputStream()

            outStream.write { writer ->
                methodDescriptor.responseSerializer.invoke(writer, outgoing)
            }

            val outgoingBytes = outStream.toByteArray()

            call.respond(outgoingBytes)
        } catch (rpcException: RpcResponseException) {
            call.respond(HttpStatusCode(rpcException.code.value, rpcException.errorMessage))
        } catch (t: Throwable) {
            println("gRPC unary error($path): $t")
            t.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, t.toString() + "\n" + t.stackTraceToString())
        }
    }
}

public fun <Server: Any> Routing.serveStreamingOut(
    server: Server,
    descriptor: ServerDescriptor,
    methodDescriptor: ServerMethodDescriptor<Any, Any, Server>,
    contextProcessor: (GrpcRequestContext) -> GrpcRequestContext = { it }
) {
    webSocket("/api/${descriptor.packageName}.${descriptor.serviceName}/${methodDescriptor.methodName}") {
        val stream = ProtobufInputStream()

        val frame = incoming.receive()
        stream.addBytes(frame.readBytes())

        val context = contextProcessor(call.toGrpcRequestContext(
            descriptor,
            methodDescriptor
        ))

        val request = stream.read(methodDescriptor.requestParser)
        try {
            (methodDescriptor.handler as ServerMethod.ServerStreaming).handler.invoke(server, context, request)
                .onCompletion {
                    when (it) {
                        null -> close(CloseReason(CloseReason.Codes.NORMAL, ""))
                        is RpcResponseException -> close(reason = CloseReason(CloseReason.Codes.INTERNAL_ERROR, it.errorMessage))
                        else -> close(reason = CloseReason(CloseReason.Codes.INTERNAL_ERROR, ""))
                    }
                }
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
        } catch (rpcException: RpcResponseException) {
            close(reason = CloseReason(CloseReason.Codes.INTERNAL_ERROR, rpcException.errorMessage))
        } catch (t: Throwable) {
            close(reason = CloseReason(CloseReason.Codes.INTERNAL_ERROR, ""))
        }
    }
}


public fun <Server: Any> Routing.serveStreamingInOut(
    server: Server,
    descriptor: ServerDescriptor,
    methodDescriptor: ServerMethodDescriptor<Any, Any, Server>,
    contextProcessor: (GrpcRequestContext) -> GrpcRequestContext = { it }
) {
    webSocket("/api/${descriptor.packageName}.${descriptor.serviceName}/${methodDescriptor.methodName}") {
        val handler = (methodDescriptor.handler as ServerMethod.ClientServerStreaming).handler
        val context = contextProcessor(call.toGrpcRequestContext(
            descriptor,
            methodDescriptor
        ))

        val requestFlow = channelFlow {
            whileSelect {
                incoming.onReceiveCatching {
                    if (it.isSuccess) {
                        val stream = ProtobufInputStream()
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

        try {
            handler(server, context, requestFlow)
                .onCompletion {
                    when (it) {
                        null -> close(CloseReason(CloseReason.Codes.NORMAL, ""))
                        is RpcResponseException -> close(reason = CloseReason(CloseReason.Codes.INTERNAL_ERROR, it.errorMessage))
                        else -> close(reason = CloseReason(CloseReason.Codes.INTERNAL_ERROR, ""))
                    }
                }
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
        } catch (rpcException: RpcResponseException) {
            close(reason = CloseReason(CloseReason.Codes.INTERNAL_ERROR, rpcException.errorMessage))
        } catch (t: Throwable) {
            close(reason = CloseReason(CloseReason.Codes.INTERNAL_ERROR, ""))
        }
    }
}
