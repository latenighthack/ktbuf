package com.latenighthack.ktbuf.rpc
import com.latenighthack.ktbuf.net.RpcResponseException
import com.latenighthack.ktbuf.proto.*
/** Preserve the RPC status encoded in server private close codes; normal close still drains accepted frames. */
internal fun rpcWebSocketCloseFailure(path: String, code: Int, reason: String): RpcResponseException? {
    val status = Status.fromWSCode(code, reason)
    return if (status.code == Codes.OK) null else RpcResponseException(path, "WS", status.code, status.message)
}
