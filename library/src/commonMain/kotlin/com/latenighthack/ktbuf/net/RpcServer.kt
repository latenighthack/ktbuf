package com.latenighthack.ktbuf.net.server

import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.net.*

typealias UnaryRpcCallback = suspend (RpcMethodSpecifier, Map<String, String>, ByteArray) -> RpcResponse
typealias StreamingRpcOpenCallback = suspend (method: RpcMethodSpecifier) -> Unit
typealias StreamingRpcSendCallback = suspend (method: RpcMethodSpecifier, request: ByteArray) -> Unit
typealias StreamingRpcReceiveCallback = suspend (method: RpcMethodSpecifier) -> ByteArray

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

public interface RpcServer {
    suspend fun registerUnaryCall(method: RpcMethodSpecifier, callback: suspend (headers: Map<String, String>, request: ByteArray) -> ByteArray): RpcResponse

    suspend fun registerServerStreamingCall(method: RpcMethodSpecifier, block: suspend RpcServerStream.() -> Unit)
}
