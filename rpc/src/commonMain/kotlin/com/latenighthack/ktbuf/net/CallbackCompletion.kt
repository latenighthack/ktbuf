package com.latenighthack.ktbuf.net

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi

@OptIn(InternalCoroutinesApi::class)
internal fun <T> CancellableContinuation<T>.completeCallback(result: Result<T>) {
    val token = result.fold({ tryResume(it) }, { tryResumeWithException(it) })
    if (token != null) completeResume(token)
}
