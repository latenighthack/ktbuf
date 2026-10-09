package com.latenighthack.ktbuf.net

import okhttp3.OkHttpClient

// Defaults have no cookies, per-client interceptors or mutable credentials. Reuse one
// bounded connection pool and dispatcher; cancellation still targets only the call.
internal val defaultOkHttpClient: OkHttpClient by lazy { OkHttpClient() }
