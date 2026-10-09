package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.net.RpcMethodSpecifier
import com.latenighthack.ktbuf.net.RpcResponse
import com.latenighthack.ktbuf.net.RpcServerStream

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public expect class HttpRpcClient(serverPath: String, useApiGateway: Boolean = false): RpcClient {

    override suspend fun unaryCall(method: RpcMethodSpecifier, headers: Map<String, String>, request: ByteArray): RpcResponse

    override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit,
        readyCallback: () -> Unit
    )
    override suspend fun serverStreamingCall(method: RpcMethodSpecifier, headers: Map<String, String>, block: suspend RpcServerStream.() -> Unit, readyCallback: () -> Unit)

    /** Reject future calls and cancel every active operation without closing shared HTTP resources. */
    fun close()
    /** Also wait for owned call jobs and their cancellation cleanup. Call outside an active RPC. */
    suspend fun closeAndJoin()

}
