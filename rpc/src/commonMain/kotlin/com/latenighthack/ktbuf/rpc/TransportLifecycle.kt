package com.latenighthack.ktbuf.rpc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Cancellation ownership without an independent or detached coroutine scope. */
internal class TransportLifecycle {
    private data class State(val closed: Boolean = false, val calls: Set<Job> = emptySet())
    private val state = MutableStateFlow(State())
    suspend fun <T> call(operation: suspend () -> T): T = coroutineScope {
        val job = coroutineContext[Job]!!
        state.update { check(!it.closed) { "HTTP RPC client is closed" }; it.copy(calls = it.calls + job) }
        try { operation() }
        finally { state.update { it.copy(calls = it.calls - job) } }
    }
    fun close() {
        while (true) {
            val previous = state.value
            if (previous.closed) return
            if (state.compareAndSet(previous, previous.copy(closed = true))) {
                previous.calls.forEach { it.cancel(CancellationException("HTTP RPC client closed")) }
                return
            }
        }
    }
    suspend fun join() { state.first { it.closed && it.calls.isEmpty() } }
}
internal const val MAX_RPC_MESSAGE_BYTES = 16 * 1024 * 1024
internal fun checkedRpcMessage(bytes: ByteArray): ByteArray {
    require(bytes.size <= MAX_RPC_MESSAGE_BYTES) { "RPC message exceeds 16 MiB limit" }
    return bytes
}
internal fun normalizedHttpRpcAddress(address: String): String {
    val normalized = address.trim().trimEnd('/')
    require(normalized.isNotEmpty()) { "Empty RPC address" }
    val result = if (normalized.contains("://")) normalized else "http://$normalized"
    require(result.startsWith("http://") || result.startsWith("https://")) { "Unsupported RPC URL scheme" }
    return result
}
internal fun webSocketRpcAddress(address: String): String = normalizedHttpRpcAddress(address)
    .replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
