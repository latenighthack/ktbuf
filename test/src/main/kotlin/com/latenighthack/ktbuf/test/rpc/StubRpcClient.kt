package com.latenighthack.ktbuf.test.rpc

import com.latenighthack.ktbuf.ProtobufInputStream
import com.latenighthack.ktbuf.ProtobufOutputStream
import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.net.RpcMethodSpecifier
import com.latenighthack.ktbuf.net.RpcResponse
import com.latenighthack.ktbuf.net.RpcResponseException
import com.latenighthack.ktbuf.net.RpcServerStream
import com.latenighthack.ktbuf.net.toPath
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.proto.GrpcService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take

data class UnaryHandlerResponse<T>(
    val response: T?,
    val error: Exception?,
    val discardHandler: Boolean = false
)

interface UnaryHandler {
    suspend fun handleRequest(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): UnaryHandlerResponse<ByteArray>?
}

abstract class TypedUnaryHandler<T, U>(
    private val requestParser: suspend (ByteArray) -> T,
    private val responseSerializer: (U) -> ByteArray,
) : UnaryHandler {
    abstract suspend fun handleTypedRequest(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: T
    ): UnaryHandlerResponse<U>?

    final override suspend fun handleRequest(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): UnaryHandlerResponse<ByteArray>? {
        return handleTypedRequest(method, headers, requestParser(request))
            ?.let { result ->
                result.response?.let { response ->
                    UnaryHandlerResponse(responseSerializer(response), result.error, result.discardHandler)
                }
            }
    }
}

abstract class MatchingTypedUnaryHandler<T, U>(
    private val matchingMethod: RpcMethodSpecifier,
    private val requestParser: suspend (ByteArray) -> T,
    private val responseSerializer: (U) -> ByteArray,
) : TypedUnaryHandler<T, U>(requestParser, responseSerializer) {
    abstract suspend fun handleTypedRequest(
        headers: Map<String, String>,
        request: T
    ): UnaryHandlerResponse<U>?

    final override suspend fun handleTypedRequest(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: T
    ): UnaryHandlerResponse<U>? {
        return if (matchingMethod.toPath("") == method.toPath("")) {
            handleTypedRequest(headers, request)
        } else {
            null
        }
    }
}

class StubRpcClient : RpcClient {

    private val handlers = mutableListOf<UnaryHandler>()
    private val streamingHandlers = mutableListOf<UnaryHandler>()

    override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit,
        readyCallback: () -> Unit
    ) {
    }

    override suspend fun unaryCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcResponse {
        val discards = mutableListOf<UnaryHandler>()
        var rpcResponse: RpcResponse? = null
        var error: Throwable? = null

        for (handler in handlers) {
            val response = handler.handleRequest(method, headers, request)

            if (response != null) {
                if (response.discardHandler) {
                    discards.add(handler)
                }

                if (response.response != null) {
                    rpcResponse = RpcResponse(response.response, mapOf())

                    break
                } else if (response.error != null) {
                    error = response.error
                    break
                }
            }
        }

        for (discard in discards) {
            handlers.remove(discard)
        }

        if (error != null) {
            throw error
        }

        return rpcResponse ?: throw RpcResponseException(method.toPath(""), "POST", Codes.UNIMPLEMENTED, "not implemented")
    }

    fun <T, U> stubCallOnce(service: GrpcService.ServiceDescriptor, methodName: String, response: U): Flow<T> {
        return stubCallOnce<T, U>(service, methodName, { _ -> response })
    }

    fun <T, U> stubCallOnce(service: GrpcService.ServiceDescriptor, methodName: String, response: (T) -> U): Flow<T> {
        return stubCallInternal<T, U>(service, methodName, response, 1).take(1)
    }

    fun <T, U> stubCall(service: GrpcService.ServiceDescriptor, methodName: String, response: U): Flow<T> {
        return stubCall<T, U>(service, methodName, { _ -> response })
    }

    fun <T, U> stubCall(service: GrpcService.ServiceDescriptor, methodName: String, response: (T) -> U): Flow<T> {
        return stubCallInternal<T, U>(service, methodName, response, -1)
    }

    private fun <T, U> stubCallInternal(service: GrpcService.ServiceDescriptor, methodName: String, handler: (T) -> U, count: Int = -1): Flow<T> {
        var remaining = count
        val method = service.findMethod(methodName)!!
        val requestFlow = MutableSharedFlow<T>()

        @Suppress("UNCHECKED_CAST")
        handlers.add(object : MatchingTypedUnaryHandler<T, U>(
            RpcMethodSpecifier(service.packageName, service.serviceName, method.methodName),
            { requestBytes ->
                val inputStream = ProtobufInputStream()

                inputStream.addBytes(requestBytes)

                val request = inputStream.read(method.requestParser) as T

                requestFlow.emit(request)

                request
            },
            { responseMessage ->
                val outputStream = ProtobufOutputStream()

                outputStream.write { writer ->
                    method.responseSerializer(writer, responseMessage as Any)
                }

                outputStream.toByteArray()
            }
        ) {
            override suspend fun handleTypedRequest(
                headers: Map<String, String>,
                request: T
            ): UnaryHandlerResponse<U>? {
                val count = remaining

                remaining -= 1

                return UnaryHandlerResponse(handler(request), null, count - 1 == 0)
            }
        })

        return requestFlow
    }
}

fun GrpcService.ServiceDescriptor.findMethod(name: String): GrpcService.MethodDescriptor<*, *>? {
    return this.methods.firstOrNull {
        it.methodName == name
    }
}
