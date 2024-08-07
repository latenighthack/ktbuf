package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.net.RpcMethodSpecifier
import com.latenighthack.ktbuf.net.RpcResponse
import com.latenighthack.ktbuf.net.RpcServerStream

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public actual class HttpRpcClient actual constructor(serverPath: String) : RpcClient {
    actual override suspend fun unaryCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcResponse {
        TODO("Not yet implemented")
    }

    actual override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit
    ) {
    }

}