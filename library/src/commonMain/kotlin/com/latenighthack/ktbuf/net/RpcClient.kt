package com.latenighthack.ktbuf.net

import com.latenighthack.ktbuf.proto.Codes

fun RpcMethodSpecifier.toPath(serverPath: String) = "${serverPath}/api/${packageName}.${serviceName}/${methodName}"

interface RpcInterceptor {
}

typealias UnaryRpcCallback = suspend (RpcMethodSpecifier, Map<String, String>, ByteArray) -> RpcResponse
typealias StreamingRpcOpenCallback = suspend (method: RpcMethodSpecifier) -> Unit
typealias StreamingRpcSendCallback = suspend (method: RpcMethodSpecifier, request: ByteArray) -> Unit
typealias StreamingRpcReceiveCallback = suspend (method: RpcMethodSpecifier) -> ByteArray

interface UnaryRpcInterceptor: RpcInterceptor {

    suspend fun intercept(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        requestData: ByteArray,
        next: UnaryRpcCallback
    ): RpcResponse
}

interface StreamingRpcInterceptor: RpcInterceptor {
    suspend fun interceptOpen(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        next: suspend (method: RpcMethodSpecifier, headers: Map<String, String>) -> Unit
    )

    suspend fun interceptSend(
        method: RpcMethodSpecifier,
        request: ByteArray,
        next: suspend (method: RpcMethodSpecifier, request: ByteArray) -> Unit
    )

    suspend fun interceptReceive(
        method: RpcMethodSpecifier,
        next: suspend (method: RpcMethodSpecifier) -> ByteArray
    ): ByteArray
}

private class InterceptedServerStream(
    private val method: RpcMethodSpecifier,
    private val interceptor: StreamingRpcInterceptor,
    private val next: RpcServerStream
): RpcServerStream {
    override suspend fun receive(): ByteArray {
        return interceptor.interceptReceive(method) {
            next.receive()
        }
    }

    override suspend fun send(bytes: ByteArray) {
        return interceptor.interceptSend(method, bytes) { _, transformed ->
            next.send(transformed)
        }
    }
}

class InterceptingRpcClient(
    private val rpcClient: RpcClient,
    private val interceptors: List<RpcInterceptor>
): RpcClient {
    override suspend fun unaryCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcResponse {
        val wrappedCall = interceptors.fold(rpcClient::unaryCall as UnaryRpcCallback) { nextCall, interceptor ->
            if (interceptor !is UnaryRpcInterceptor) {
                nextCall
            } else {
                return@fold { method, headers, request ->
                    interceptor.intercept(method, headers, request, nextCall)
                }
            }
        }

        return wrappedCall(method, headers, request)
    }

    override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit
    ) {
        rpcClient.serverStreamingCall(method) {
            val thisServerStream = this
            val stream = interceptors.fold(thisServerStream) { nextStream, interceptor ->
                if (interceptor !is StreamingRpcInterceptor) {
                    nextStream
                } else {
                    InterceptedServerStream(method, interceptor, nextStream)
                }
            }

            stream.block()
        }
    }
}

class RpcResponseException(val path: String, val verb: String, val code: Codes, val errorMessage: String, cause: Throwable? = null) : Exception("$verb - $code: $path (${errorMessage.trim()})", cause) {
    fun retriable(): Boolean = code.retriable()
}

data class RpcMethodSpecifier(
    val packageName: String,
    val serviceName: String,
    val methodName: String
)

class RpcResponse(val data: ByteArray, val headers: Map<String, String>)

interface RpcServerStream {
    @Throws(IllegalStateException::class, RpcResponseException::class)
    suspend fun receive(): ByteArray

    @Throws(IllegalStateException::class)
    suspend fun send(bytes: ByteArray)
}

public interface RpcClient {
    suspend fun unaryCall(method: RpcMethodSpecifier, headers: Map<String, String>, request: ByteArray): RpcResponse

    suspend fun serverStreamingCall(method: RpcMethodSpecifier, block: suspend RpcServerStream.() -> Unit)
}
