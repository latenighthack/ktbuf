package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.RpcResponseException
import com.latenighthack.ktbuf.ProtobufOutputLimitException
import com.latenighthack.ktbuf.ProtobufLimitException
import com.latenighthack.ktbuf.bytes.ByteArrayLimitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class Backoff {
    class PermanentError(cause: Throwable) : Error("[backoff] Permanent error, stopping retry", cause)
    class RetriableError(cause: Throwable? = null) : Error("[backoff] Retrying after error", cause)
    class ForceRetry : Error("[backoff] Retrying")
}

class RetryLimitExceeded() : CancellationException("[backoff] Retry limit exceeded")

expect class AtomicReference<T>(defaultValue: T) {
    var value: T
    fun compareAndSet(expected: T, updated: T): Boolean
}

/** Atomically updates the reference and returns its previous value.
 * The updater can run more than once under contention and must have no side effects.
 */
fun <T> AtomicReference<T>.getAndUpdate(updater: T.() -> T): T {
    while (true) {
        val previous = value
        val updated = updater(previous)
        if (compareAndSet(previous, updated)) return previous
    }
}

interface BackoffContext {
    companion object {
        const val DEFAULT_BACKOFF = 100L
        const val DEFAULT_BACKOFF_RATE = 1.5f
        const val DEFAULT_JITTER = 0.4f
        const val DEFAULT_MIN_RETRY_TIME = 100L
        const val DEFAULT_MAX_RETRY_TIME = 15000L
        const val DEFAULT_TIME_LIMIT = Long.MAX_VALUE
        const val DEFAULT_RETRY_LIMIT = Int.MAX_VALUE
        val DEFAULT_CANCEL_CHANNEL: ReceiveChannel<Unit> get() = Channel()
        val DEFAULT_RESET_CHANNEL: ReceiveChannel<Unit> get() = Channel()
        val DEFAULT_EXCEPTION_HANDLER: (Throwable) -> Boolean = {
            when (it) {
                is ProtobufOutputLimitException, is ProtobufLimitException, is ByteArrayLimitException -> false
                is RpcResponseException -> it.retriable()
                else -> true
            }
        }
    }

    fun reset()

    fun retry(): Nothing = throw Backoff.ForceRetry()

    fun cancel(): Nothing = throw Backoff.PermanentError(CancellationException("[backoff] cancelled"))
}

fun Long.clamp(lowerBound: Long, upperBound: Long) = max(min(this, upperBound), lowerBound)

private class RetrySchedule(
    private val initial: Long,
    private val rate: Float,
    private val jitter: Float,
    private val minimum: Long,
    private val maximum: Long,
    private val externalReset: ReceiveChannel<Unit>? = null
) : BackoffContext {
    private val resets = Channel<Unit>(Channel.CONFLATED)
    private var externalOpen = true
    private var current = initial.coerceIn(minimum, maximum)

    override fun reset() { resets.trySend(Unit) }
    fun close() { resets.close() }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun waitForRetry() {
        val wait = (current.toDouble() * (1.0 + Random.nextDouble() * jitter))
            .coerceIn(minimum.toDouble(), maximum.toDouble()).toLong()
        while (true) {
            val event = select<Int> {
                resets.onReceive { 1 }
                if (externalReset != null && externalOpen) externalReset.onReceiveCatching {
                    if (it.isSuccess) 1 else { externalOpen = false; -1 }
                }
                onTimeout(wait) { 0 }
            }
            if (event == -1) continue
            current = if (event == 1) initial.coerceIn(minimum, maximum)
                else (current.toDouble() * rate).coerceAtMost(maximum.toDouble()).toLong()
            return
        }
    }
}

private fun validateBackoff(backoff: Long, rate: Float, jitter: Float, minimum: Long, maximum: Long, timeLimit: Long, retryLimit: Int) {
    require(backoff >= 0 && minimum >= 0 && maximum >= minimum) { "Invalid backoff interval" }
    require(rate.isFinite() && rate >= 1f) { "Backoff rate must be finite and at least one" }
    require(jitter.isFinite() && jitter in 0f..1f) { "Jitter must be between zero and one" }
    require(timeLimit >= 0 && retryLimit >= 0) { "Retry and time limits must be nonnegative" }
}

private fun checkRetriable(cause: Throwable, handler: (Throwable) -> Boolean) {
    if (cause is CancellationException) throw cause
    if (cause is Backoff.PermanentError) throw (cause.cause ?: cause)
    if (cause is Backoff.RetriableError) {
        if (cause.cause is CancellationException) throw cause.cause!!
        return
    }
    if (cause is Backoff.ForceRetry) return
    if (cause is Error || !handler(cause)) throw cause
}

private suspend fun <T> withinDeadline(timeLimit: Long, block: suspend () -> T): T =
    if (timeLimit == Long.MAX_VALUE) block() else withTimeout(timeLimit) { block() }

/** Retry limits count retries after the initial collection. State is per collection.
 * [task] is the legacy recovery hook, invoked before each retry; its result is ignored.
 */
fun <T> Flow<T>.retryWithBackoff(
    backoff: Long = BackoffContext.DEFAULT_BACKOFF,
    backoffRate: Float = BackoffContext.DEFAULT_BACKOFF_RATE,
    jitter: Float = BackoffContext.DEFAULT_JITTER,
    minRetryTime: Long = BackoffContext.DEFAULT_MIN_RETRY_TIME,
    maxRetryTime: Long = BackoffContext.DEFAULT_MAX_RETRY_TIME,
    timeLimit: Long = BackoffContext.DEFAULT_TIME_LIMIT,
    retryLimit: Int = BackoffContext.DEFAULT_RETRY_LIMIT,
    task: suspend BackoffContext.(Int) -> T?
): Flow<T> {
    validateBackoff(backoff, backoffRate, jitter, minRetryTime, maxRetryTime, timeLimit, retryLimit)
    return flow {
        val schedule = RetrySchedule(backoff, backoffRate, jitter, minRetryTime, maxRetryTime)
        try {
            withinDeadline(timeLimit) {
                this@retryWithBackoff.retryWhen { cause, attempt ->
                    checkRetriable(cause, BackoffContext.DEFAULT_EXCEPTION_HANDLER)
                    if (attempt >= retryLimit) false else {
                        schedule.task((attempt + 1).toInt())
                        schedule.waitForRetry()
                        true
                    }
                }.collect { emit(it) }

            }
        } finally { schedule.close() }
    }
}

/** Runs an initial attempt numbered zero, followed by at most [retryLimit] retries.
 * [timeLimit] bounds the whole operation, including tasks and waits. A null result
 * is successful completion. Cancellation and permanent errors propagate unchanged.
 */
suspend fun <T: Any> repeatWithBackoff(
    backoff: Long = BackoffContext.DEFAULT_BACKOFF,
    backoffRate: Float = BackoffContext.DEFAULT_BACKOFF_RATE,
    jitter: Float = BackoffContext.DEFAULT_JITTER,
    minRetryTime: Long = BackoffContext.DEFAULT_MIN_RETRY_TIME,
    maxRetryTime: Long = BackoffContext.DEFAULT_MAX_RETRY_TIME,
    timeLimit: Long = BackoffContext.DEFAULT_TIME_LIMIT,
    retryLimit: Int = BackoffContext.DEFAULT_RETRY_LIMIT,
    resetChannel: ReceiveChannel<Unit> = BackoffContext.DEFAULT_RESET_CHANNEL,
    exceptionHandler: (Throwable) -> Boolean = BackoffContext.DEFAULT_EXCEPTION_HANDLER,
    task: suspend BackoffContext.(Int) -> T?
): T? {
    validateBackoff(backoff, backoffRate, jitter, minRetryTime, maxRetryTime, timeLimit, retryLimit)
    val schedule = RetrySchedule(backoff, backoffRate, jitter, minRetryTime, maxRetryTime, resetChannel)
    try {
        return withinDeadline(timeLimit) {
            var attempt = 0
            while (true) {
                try { return@withinDeadline schedule.task(attempt) }
                catch (cause: Throwable) { checkRetriable(cause, exceptionHandler) }
                if (attempt >= retryLimit) throw RetryLimitExceeded()
                attempt++
                schedule.waitForRetry()
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    } finally { schedule.close() }
}
