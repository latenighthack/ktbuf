package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.RpcResponseException
import com.latenighthack.ktbuf.net.toPath
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.net.RpcMethodSpecifier
import com.latenighthack.ktbuf.net.RpcResponse
import com.latenighthack.ktbuf.net.RpcServerStream

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public actual class HttpRpcClient actual constructor(serverPath: String, useApiGateway: Boolean) : RpcClient {
    private val lifecycle = com.latenighthack.ktbuf.net.RpcClientLifecycle()
    actual fun close() = lifecycle.close()
    actual suspend fun closeAndJoin() = lifecycle.closeAndJoin()

    actual override suspend fun unaryCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcResponse = lifecycle.call {
        throw RpcResponseException(method.toPath(""), "POST", Codes.UNIMPLEMENTED, "Built-in RPC transport is not supported on Linux; supply a custom RpcClient")
    }

    actual override suspend fun serverStreamingCall(method: RpcMethodSpecifier, block: suspend RpcServerStream.() -> Unit, readyCallback: () -> Unit) =
        serverStreamingCall(method, emptyMap(), block, readyCallback)

    actual override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        block: suspend RpcServerStream.() -> Unit,
        readyCallback: () -> Unit
    ) : Unit = lifecycle.call {
        throw RpcResponseException(method.toPath(""), "WS", Codes.UNIMPLEMENTED, "Built-in RPC transport is not supported on Linux; supply a custom RpcClient")
    }

}