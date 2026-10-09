package com.latenighthack.ktbuf.rpc

internal const val MAX_RPC_MESSAGE_BYTES = 16 * 1024 * 1024
internal fun checkedRpcMessage(bytes: ByteArray) {
    require(bytes.size <= MAX_RPC_MESSAGE_BYTES) { "RPC message exceeds 16 MiB limit" }
}
