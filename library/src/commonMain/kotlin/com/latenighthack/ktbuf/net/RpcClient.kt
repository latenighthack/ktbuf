package com.latenighthack.ktbuf.net

import com.latenighthack.ktbuf.ProtobufReader
import com.latenighthack.ktbuf.ProtobufWriter
import com.latenighthack.ktbuf.proto.Codes
import kotlinx.coroutines.flow.Flow

private fun String.urlComponent(): String = buildString {
    for (byte in this@urlComponent.encodeToByteArray()) {
        val value = byte.toInt() and 255
        if (value in 65..90 || value in 97..122 || value in 48..57 || value == 45 || value == 46 || value == 95 || value == 126) append(value.toChar())
        else { append('%'); append("0123456789ABCDEF"[value ushr 4]); append("0123456789ABCDEF"[value and 15]) }
    }
}

private fun RpcMethodSpecifier.queryString(): String = if (additionalParameters.isEmpty()) "" else
    additionalParameters.entries.joinToString("&", prefix = "?") { "${it.key.urlComponent()}=${it.value.urlComponent()}" }

fun RpcMethodSpecifier.toPath(serverPath: String) = "${serverPath.trimEnd('/')}/api/${"$packageName.$serviceName".urlComponent()}/${methodName.urlComponent()}${queryString()}"
fun RpcMethodSpecifier.toApiGatewayPath(serverPath: String) = "${serverPath.trimEnd('/')}/ws_${packageName.replace('.', '_').urlComponent()}_${serviceName.replace('.', '_').urlComponent()}_${methodName.urlComponent()}${queryString()}"

/** Bare addresses use HTTP; explicit HTTP/WS schemes retain their security choice. */
fun rpcHttpBaseUrl(serverPath: String): String {
    require(serverPath.isNotBlank() && serverPath.none { it.isWhitespace() || it == '?' || it == '#' }) { "Invalid RPC base URL" }
    val scheme = serverPath.substringBefore("://", "").lowercase()
    val base = when (scheme) {
        "" -> "http://$serverPath"
        "http", "https" -> "$scheme://${serverPath.substringAfter("://")}"
        "ws" -> "http://${serverPath.substringAfter("://")}"
        "wss" -> "https://${serverPath.substringAfter("://")}"
        else -> throw IllegalArgumentException("Unsupported RPC URL scheme: $scheme")
    }
    require(base.substringAfter("://").substringBefore('/').isNotEmpty()) { "RPC base URL requires a host" }
    return base.trimEnd('/')
}

fun rpcWebSocketBaseUrl(serverPath: String): String = rpcHttpBaseUrl(serverPath).let {
    if (it.startsWith("https://")) "wss://${it.removePrefix("https://")}" else "ws://${it.removePrefix("http://") }"
}

data class GrpcRequestContext(
    val originalUrl: String,
    val headers: Map<String, String>,
    val query: Map<String, String>,
    val extensions: Map<String, Any>,
    val serverDescriptor: ServerDescriptor,
    val methodDescriptor: ServerMethodDescriptor<*, *, *>
)

sealed class StreamControlEvent<T : Any> {
    class Close<T : Any> : StreamControlEvent<T>()
    data class Message<T : Any>(val message: T) : StreamControlEvent<T>()
}

sealed class ServerMethod<Req : Any, Res : Any, Srv: Any> {
    data class Unary<Req : Any, Res : Any, Srv: Any>(val handler: suspend Srv.(GrpcRequestContext, Req) -> Res) : ServerMethod<Req, Res, Srv>()
    data class ClientStreaming<Req : Any, Res : Any, Srv: Any>(val handler: suspend Srv.(GrpcRequestContext, Flow<Req>) -> Res) : ServerMethod<Req, Res, Srv>()
    data class ServerStreaming<Req : Any, Res : Any, Srv: Any>(val handler: suspend Srv.(GrpcRequestContext, Req) -> Flow<StreamControlEvent<Res>>) : ServerMethod<Req, Res, Srv>()
    data class ClientServerStreaming<Req : Any, Res : Any, Srv: Any>(val handler: suspend Srv.(GrpcRequestContext, Flow<Req>) -> Flow<StreamControlEvent<Res>>) : ServerMethod<Req, Res, Srv>()
}

data class ServerMethodDescriptor<Req : Any, Res : Any, Srv: Any>(
    val methodName: String,
    val requestParser: (ProtobufReader) -> Req,
    val responseSerializer: (ProtobufWriter, Any) -> Unit,
    val streamingIn: Boolean,
    val streamingOut: Boolean,
    val handler: ServerMethod<Req, Res, Srv>,
    val dummy: Int = 0
) {
    constructor(
        methodName: String,
        requestParser: (ProtobufReader) -> Req,
        typedResponseSerializer: Res.(ProtobufWriter) -> Unit,
        streamingIn: Boolean,
        streamingOut: Boolean,
        handler: ServerMethod<Req, Res, Srv>
    ) : this(methodName, requestParser, { writer, type ->
        @Suppress("UNCHECKED_CAST")
        (type as Res).typedResponseSerializer(writer)
    }, streamingIn, streamingOut, handler)
}

data class ServerDescriptor(
    val packageName: String,
    val serviceName: String,
    val methods: List<ServerMethodDescriptor<*, *, *>>
)

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

    override suspend fun closeOutbound() {
        next.closeOutbound()
    }

    override suspend fun closeInbound() {
        next.closeInbound()
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
        block: suspend RpcServerStream.() -> Unit,
        readyCallback: () -> Unit
    ) {
        rpcClient.serverStreamingCall(
            method,
            {
                val thisServerStream = this
                val stream = interceptors.fold(thisServerStream) { nextStream, interceptor ->
                    if (interceptor !is StreamingRpcInterceptor) {
                        nextStream
                    } else {
                        InterceptedServerStream(method, interceptor, nextStream)
                    }
                }

                stream.block()
            }, readyCallback
        )
    }
}

class RpcResponseException(val path: String, val verb: String, val code: Codes, val errorMessage: String, cause: Throwable? = null) : Exception("$verb - $code: $path (${errorMessage.trim()})", cause) {
    fun retriable(): Boolean = code.retriable()
}

data class RpcMethodSpecifier(
    val packageName: String,
    val serviceName: String,
    val methodName: String,
    val additionalParameters: Map<String, String> = emptyMap()
)

class RpcResponse(val data: ByteArray, val headers: Map<String, String>)

interface RpcServerStream {
    @Throws(IllegalStateException::class, RpcResponseException::class)
    suspend fun receive(): ByteArray

    @Throws(IllegalStateException::class)
    suspend fun send(bytes: ByteArray)

    suspend fun closeOutbound()

    suspend fun closeInbound()
}

public interface RpcClient {
    suspend fun unaryCall(method: RpcMethodSpecifier, headers: Map<String, String>, request: ByteArray): RpcResponse

    suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit,
        readyCallback: () -> Unit
    )
}
