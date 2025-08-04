package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.RpcResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
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
}

fun <T> AtomicReference<T>.getAndUpdate(updater: T.() -> T): T {
    val updated = updater(value)

    this.value = updated

    return updated
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
            (it as? RpcResponseException)?.retriable() ?: true
        }
    }

    fun reset()

    fun retry(): Nothing = throw Backoff.ForceRetry()

    fun cancel(): Nothing = throw Backoff.PermanentError(CancellationException("[backoff] cancelled"))
}

fun Long.clamp(lowerBound: Long, upperBound: Long) = max(min(this, upperBound), lowerBound)

private data class BackoffState(
    val attempts: Int = 0,
    val progressiveBackoff: Int = 0,
    val isComplete: Boolean = false
)

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
    var isFirst = true
    val internalResetChannel = Channel<Unit>(1)

    val state = AtomicReference<BackoffState>(BackoffState(progressiveBackoff = backoff.toInt()))

    val context = object : BackoffContext {
        override fun reset() {
            internalResetChannel.trySend(Unit)
        }
    }

    return this.retry { cause ->
        if (state.value.attempts > retryLimit) {
            false
        } else {
            if (isFirst) {
                isFirst = false

                context.task(state.value.attempts).also {
                    state.getAndUpdate {
                        copy(isComplete = true)
                    }
                }
            }

            state.getAndUpdate {
                copy(progressiveBackoff = min((progressiveBackoff * backoffRate).toInt(), maxRetryTime.toInt()))
            }
        }

        true
    }
}

/**
 * @param backoff
 * @param backoffRate
 * @param jitter
 * @param minRetryTime
 * @param maxRetryTime
 * @param timeLimit
 * @param retryLimit
 * @param task
 *
 * @return The value returned by the task provided a value is returned within the limits of the backoff
 * @throws RetryLimitExceeded if the total retries exceed the allowed limit
 *         TimeLimitExceeded if the total backoff period is exceeded
 *         Any exception thrown by the task which is not wrapped as retriable will be rethrown
 */
@OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
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
    var isFirst = true
    val internalResetChannel = Channel<Unit>(1)

    val state = AtomicReference<BackoffState>(BackoffState(progressiveBackoff = backoff.toInt()))

    val context = object : BackoffContext {
        override fun reset() {
            internalResetChannel.trySend(Unit)
        }
    }

    var result: T? = null

    while ((state.getAndUpdate {
        copy(attempts = attempts + 1)
    }).attempts < retryLimit) {
        try {
            result = if (isFirst) {
                isFirst = false

                context.task(state.value.attempts).also {
                    state.getAndUpdate {
                        copy(isComplete = true)
                    }
                }
            } else {
                select {
                    resetChannel.onReceiveCatching {
                        throw Backoff.PermanentError(CancellationException("Operation cancelled"))
                    }

                    internalResetChannel.onReceiveCatching {
                        context.task(state.value.attempts).also {
                            state.getAndUpdate {
                                copy(isComplete = true)
                            }
                        }
                    }

                    // compute backoff time
                    val currentBackoff = state.value.progressiveBackoff
                    val currentJitter = (Random.nextFloat() * jitter * currentBackoff).toLong()
                    val clampedBackoff = (currentBackoff + currentJitter).clamp(minRetryTime, maxRetryTime)
                    val timeoutClampedBackoff = min(clampedBackoff, timeLimit)

                    onTimeout(timeoutClampedBackoff) {
                        context.task(state.value.attempts).also {
                            state.getAndUpdate {
                                copy(isComplete = true)
                            }
                        }
                    }
                }
            }

            break
        } catch (e: Backoff.PermanentError) {
            throw e.cause!!
        } catch (e: Backoff.RetriableError) {
        } catch (e: Backoff.ForceRetry) {
        } catch (e: ClosedSendChannelException) {
            break
        } catch (e: Throwable) {
            val isRetriable = exceptionHandler(e)
            if (!isRetriable) {
                throw e
            }
        }

        state.getAndUpdate {
            copy(progressiveBackoff = min((progressiveBackoff * backoffRate).toInt(), maxRetryTime.toInt()))
        }
    }

    if (state.value.attempts >= retryLimit) {
        throw RetryLimitExceeded()
    } else if (!state.value.isComplete) {
        throw IllegalStateException("Did not complete")
    }

    return result
}
