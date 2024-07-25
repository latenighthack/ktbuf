package com.latenighthack.ktbuf.proto

fun RpcClient.MethodSpecifier.toPath(serverPath: String) = "${serverPath}/api/${packageName}.${serviceName}/${methodName}"

interface RpcInterceptor {
}

typealias UnaryRpcCallback = suspend (RpcClient.MethodSpecifier, Map<String, String>, ByteArray) -> RpcClient.Response
typealias StreamingRpcOpenCallback = suspend (method: RpcClient.MethodSpecifier) -> Unit
typealias StreamingRpcSendCallback = suspend (method: RpcClient.MethodSpecifier, request: ByteArray) -> Unit
typealias StreamingRpcReceiveCallback = suspend (method: RpcClient.MethodSpecifier) -> ByteArray

interface UnaryRpcInterceptor: RpcInterceptor {

    suspend fun intercept(
        method: RpcClient.MethodSpecifier,
        headers: Map<String, String>,
        requestData: ByteArray,
        next: UnaryRpcCallback
    ): RpcClient.Response
}

interface StreamingRpcInterceptor: RpcInterceptor {
    suspend fun interceptOpen(
        method: RpcClient.MethodSpecifier,
        headers: Map<String, String>,
        next: suspend (method: RpcClient.MethodSpecifier, headers: Map<String, String>) -> Unit
    )

    suspend fun interceptSend(
        method: RpcClient.MethodSpecifier,
        request: ByteArray,
        next: suspend (method: RpcClient.MethodSpecifier, request: ByteArray) -> Unit
    )

    suspend fun interceptReceive(
        method: RpcClient.MethodSpecifier,
        next: suspend (method: RpcClient.MethodSpecifier) -> ByteArray
    ): ByteArray
}

private class InterceptedServerStream(
    private val method: RpcClient.MethodSpecifier,
    private val interceptor: StreamingRpcInterceptor,
    private val next: RpcClient.ServerStream
): RpcClient.ServerStream {
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
        method: RpcClient.MethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcClient.Response {
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
        method: RpcClient.MethodSpecifier,
        block: suspend RpcClient.ServerStream.() -> Unit
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

interface RpcClient {
    class ResponseException(val path: String, val verb: String, val code: Codes, val errorMessage: String, cause: Throwable? = null) : Exception("$verb - $code: $path (${errorMessage.trim()})", cause) {
        fun retriable(): Boolean = code.retriable()
    }

    class Response(val data: ByteArray, val headers: Map<String, String>)

    data class MethodSpecifier(
        val packageName: String,
        val serviceName: String,
        val methodName: String
    )

    interface ServerStream {
        @Throws(IllegalStateException::class, ResponseException::class)
        suspend fun receive(): ByteArray

        @Throws(IllegalStateException::class)
        suspend fun send(bytes: ByteArray)
    }

    suspend fun unaryCall(method: MethodSpecifier, headers: Map<String, String>, request: ByteArray): Response

    suspend fun serverStreamingCall(method: MethodSpecifier, block: suspend ServerStream.() -> Unit)
}
