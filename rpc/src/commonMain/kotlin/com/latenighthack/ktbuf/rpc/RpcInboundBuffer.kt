package com.latenighthack.ktbuf.rpc
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
/** Callback producers reject overflow explicitly; neither message count nor queued bytes can grow without bound. */
internal class RpcInboundBuffer(private val maxBytes: Int = MAX_RPC_MESSAGE_BYTES, capacity: Int = 64) {
    private val channel = Channel<ByteArray>(capacity)
    private val queued = MutableStateFlow(0)
    fun offer(bytes: ByteArray): Boolean {
        while (true) {
            val previous = queued.value
            if (bytes.size > maxBytes - previous) return false
            if (queued.compareAndSet(previous, previous + bytes.size)) break
        }
        if (channel.trySend(bytes).isSuccess) return true
        queued.update { it - bytes.size }
        return false
    }
    suspend fun receive(): ByteArray = channel.receiveCatching().getOrThrow().also { bytes -> queued.update { it - bytes.size } }
    fun close(cause: Throwable? = null) { channel.close(cause) }
}
