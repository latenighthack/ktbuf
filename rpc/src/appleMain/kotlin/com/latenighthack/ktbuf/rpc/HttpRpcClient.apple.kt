package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.Foundation.*
import platform.darwin.*
import platform.posix.memcpy
import kotlin.coroutines.*

@OptIn(InternalCoroutinesApi::class)
class MainQueueDispatcher: CoroutineDispatcher(), Delay {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatch_async(dispatch_get_main_queue()) {
            try {
                block.run()
            } catch (err: Throwable) {
                throw err
            }
        }
    }

    @OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val handle = invokeOnTimeout(timeMillis, Runnable {
            if (continuation.isActive) continuation.resume(Unit)
        }, continuation.context)
        continuation.invokeOnCancellation { handle.dispose() }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val active = AtomicReference(true)
        // Timer creation and invalidation are confined to the main queue.
        var timer: NSTimer? = null
        dispatch_async(dispatch_get_main_queue()) {
            if (active.value) {
                val scheduled = NSTimer.timerWithTimeInterval(timerIntervalSeconds(timeMillis), false) {
                    timer = null
                    if (active.compareAndSet(true, false)) block.run()
                }
                timer = scheduled
                NSRunLoop.mainRunLoop.addTimer(scheduled, NSRunLoopCommonModes)
                if (!active.value) { scheduled.invalidate(); timer = null }
            }
        }
        return object : DisposableHandle {
            override fun dispose() {
                active.value = false
                dispatch_async(dispatch_get_main_queue()) {
                    timer?.invalidate()
                    timer = null
                }
            }
        }
    }

}

private val mainDispatcher = MainQueueDispatcher()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class HttpRpcClient actual constructor(private val serverPath: String, private val useApiGateway: Boolean) : RpcClient {
    private val address = normalizedHttpRpcAddress(serverPath)
    private val lifecycle = TransportLifecycle()
    private val closed = CompletableDeferred<Unit>()
    private val delegate = object : NSObject(), NSURLSessionDelegateProtocol {
        override fun URLSession(session: NSURLSession, didBecomeInvalidWithError: NSError?) { closed.complete(Unit) }
    }
    private val session = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration, delegate, null)
    actual fun close() { lifecycle.close(); session.invalidateAndCancel() }
    actual suspend fun closeAndJoin() { close(); lifecycle.join(); closed.await() }
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    actual override suspend fun unaryCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcResponse = lifecycle.call { suspendCancellableCoroutine { continuation ->
        checkedRpcMessage(request)
        val url = if (useApiGateway) method.toApiGatewayPath(address) else method.toPath(address)
        val requestUrl = NSURL.URLWithString(url)!!
        val urlRequest = NSMutableURLRequest.requestWithURL(requestUrl).apply {
            HTTPMethod = "POST"
            setValue("application/proto", "Content-Type")
            headers.forEach { (key, value) ->
                setValue(value, forHTTPHeaderField = key)
            }
            HTTPBody = request.toNSData()
        }

        val task = session.dataTaskWithRequest(urlRequest) { data, response, error ->
            continuation.completeCallback(runCatching {
                if (error != null) throw RpcResponseException(url, "POST", Codes.from(error.code.toInt()), error.localizedDescription ?: "Unknown error")
                val httpResponse = response as? NSHTTPURLResponse ?: error("Missing RPC HTTP response")
                require(data == null || data.length <= MAX_RPC_MESSAGE_BYTES.toULong()) { "RPC response exceeds 16 MiB limit" }
                val responseData = data?.toByteArray() ?: byteArrayOf()
                val responseHeaders = httpResponse.allHeaderFields.mapKeys { it.key.toString() }.mapValues { it.value.toString() }
                val statusHeader = responseHeaders.rpcHeader(RPC_STATUS_HEADER)
                if (httpResponse.statusCode !in 200L..299L || (statusHeader != null && Status.fromHTTPCode(httpResponse.statusCode.toInt(), null, statusHeader).code != Codes.OK)) {
                    val status = Status.fromHTTPCode(httpResponse.statusCode.toInt(), responseData.decodeToString(), statusHeader)
                    throw RpcResponseException(url, "POST", status.code, status.message)
                }
                RpcResponse(responseData, responseHeaders)
            })
        }
        task.resume()
        continuation.invokeOnCancellation { task.cancel() }
    } }

    actual override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit,
        readyCallback: () -> Unit
    ): Unit = lifecycle.call {
        val path = webSocketRpcAddress(address)
        val url = if (useApiGateway) method.toApiGatewayPath(path) else method.toPath(path)

        val requestUrl = NSURL.URLWithString(url)!!
        val urlRequest = NSMutableURLRequest.requestWithURL(requestUrl)

        val webSocketTask = session.webSocketTaskWithRequest(urlRequest)
        // Foundation defaults to 1 MiB, smaller than ordinary streamed RPC
        // snapshots (for example a full game frame set). Keep a bounded receive
        // buffer, but allow the same multi-megabyte payloads as unary calls.
        webSocketTask.maximumMessageSize = 16L * 1024 * 1024
        val serverStream = object : RpcServerStream {
            override suspend fun receive(): ByteArray = suspendCancellableCoroutine { cont ->
                webSocketTask.receiveMessageWithCompletionHandler { message, error ->
                    if (error != null) {
                        cont.completeCallback(Result.failure(
                            RpcResponseException(
                                path = url,
                                verb = "RECEIVE",
                                code = Codes.from(error.code.toInt()),
                                errorMessage = error.localizedDescription ?: "Unknown error"
                            )
                        ))
                    } else {
                        cont.completeCallback(runCatching {
                            val data = message?.data ?: error("Missing RPC frame")
                            require(data.length <= MAX_RPC_MESSAGE_BYTES.toULong()) { "RPC frame exceeds 16 MiB limit" }
                            data.toByteArray()
                        })
                    }
                }
                cont.invokeOnCancellation { webSocketTask.cancel() }
            }

            override suspend fun send(bytes: ByteArray) = suspendCancellableCoroutine<Unit> { cont ->
                checkedRpcMessage(bytes)
                val data = bytes.toNSData()
                val message = NSURLSessionWebSocketMessage(data)

                webSocketTask.sendMessage(message) { error ->
                    if (error != null) {
                        cont.completeCallback(Result.failure(
                            RpcResponseException(
                                path = url,
                                verb = "SEND",
                                code = Codes.from(error.code.toInt()),
                                errorMessage = error.localizedDescription ?: "Unknown error"
                            )
                        ))
                    } else {
                        cont.completeCallback(Result.success(Unit))
                    }
                }
                cont.invokeOnCancellation { webSocketTask.cancel() }
            }

            override suspend fun closeOutbound() { webSocketTask.cancel() }

            override suspend fun closeInbound() { webSocketTask.cancel() }
        }

        webSocketTask.resume()
        try {
            readyCallback()
            serverStream.block()
        } catch (e: Exception) {
            throw e
        } finally {
            webSocketTask.cancel()
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun ByteArray.toNSData(): NSData {
    return this.usePinned { pinned ->
        if (this.isEmpty()) {
            NSData()
        } else {
            NSData.create(bytes = pinned.addressOf(0), length = this.size.toULong())
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val length = checkedNativeByteCount(this.length)
    if (length == 0) {
        return byteArrayOf()
    }

    val byteArray = ByteArray(length)
    memScoped {
        val buffer = byteArray.refTo(0).getPointer(this)

        memcpy(buffer, this@toByteArray.bytes, length.toULong())
    }
    return byteArray
}

internal fun checkedNativeByteCount(length: ULong): Int {
    require(length <= Int.MAX_VALUE.toULong()) { "Native byte data exceeds the Kotlin array size limit" }
    return length.toInt()
}

internal fun timerIntervalSeconds(timeMillis: Long): Double = timeMillis.coerceAtLeast(0).toDouble() / 1000.0
