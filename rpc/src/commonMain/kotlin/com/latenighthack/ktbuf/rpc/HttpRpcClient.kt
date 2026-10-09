package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.net.RpcMethodSpecifier
import com.latenighthack.ktbuf.net.RpcResponse
import com.latenighthack.ktbuf.net.RpcServerStream

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public expect class HttpRpcClient(serverPath: String, useApiGateway: Boolean = false): RpcClient {

    /** Stops admission and cancels in-flight calls. Idempotent; this client owns its transports. */
    fun close()
    /** Returns once in-flight coroutine work and owned transports have stopped. */
    suspend fun closeAndJoin()

    override suspend fun unaryCall(method: RpcMethodSpecifier, headers: Map<String, String>, request: ByteArray): RpcResponse

    override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit,
        readyCallback: () -> Unit
    )
}
