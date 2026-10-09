package com.latenighthack.ktbuf.net

import com.latenighthack.ktbuf.proto.Codes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Owns complete operation jobs, including children and cancellation cleanup. */
internal class RpcClientLifecycle {
    private data class State(val closed: Boolean = false, val jobs: Set<Job> = emptySet())
    private val state = MutableStateFlow(State())
    private class Call(val owner: RpcClientLifecycle, val parent: Call?) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Call>
    }

    suspend fun <T> call(block: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        currentCoroutineContext().ensureActive()
        while (true) {
            val old = state.value
            if (old.closed) throw RpcResponseException("", "RPC", Codes.CANCELLED, "RPC client is closed")
            if (state.compareAndSet(old, old.copy(jobs = old.jobs + job))) break
        }
        // A body can return before its children finish; release ownership on job completion.
        job.invokeOnCompletion { state.update { it.copy(jobs = it.jobs - job) } }
        currentCoroutineContext().ensureActive()
        withContext(Call(this@RpcClientLifecycle, currentCoroutineContext()[Call])) { block() }
    }

    fun close() {
        state.getAndUpdate { it.copy(closed = true) }.jobs.forEach { it.cancel(CancellationException("RPC client is closed")) }
    }

    suspend fun closeAndJoin() {
        var call = currentCoroutineContext()[Call]
        while (call != null) {
            check(call.owner !== this) { "Use close() within this client's call; closeAndJoin() cannot join its own operation" }
            call = call.parent
        }
        close()
        state.value.jobs.forEach { it.join() }
    }
}
