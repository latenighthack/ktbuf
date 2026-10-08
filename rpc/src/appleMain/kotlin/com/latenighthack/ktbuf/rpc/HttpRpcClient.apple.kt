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

actual class HttpRpcClient actual constructor(private val serverPath: String, private val useApiGateway: Boolean) : RpcClient {
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    actual override suspend fun unaryCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcResponse = suspendCoroutine { continuation ->
        val url = if (useApiGateway) method.toApiGatewayPath(rpcHttpBaseUrl(serverPath)) else method.toPath(rpcHttpBaseUrl(serverPath))
        val requestUrl = NSURL.URLWithString(url)!!
        val urlRequest = NSMutableURLRequest.requestWithURL(requestUrl).apply {
            HTTPMethod = "POST"
            setValue("application/proto", "Content-Type")
            headers.forEach { (key, value) ->
                setValue(value, forHTTPHeaderField = key)
            }
            HTTPBody = request.toNSData()
        }

        val task = NSURLSession.sharedSession.dataTaskWithRequest(urlRequest) { data, response, error ->
            if (error != null) {
                continuation.resumeWithException(
                    RpcResponseException(
                        path = url,
                        verb = "POST",
                        code = Codes.from(error.code.toInt()),
                        errorMessage = error.localizedDescription ?: "Unknown error"
                    )
                )
            } else {
                val httpResponse = response as NSHTTPURLResponse
                val statusCode = httpResponse.statusCode.toInt()
                if (statusCode < 200 || statusCode > 299) {
                    val errorMessage = data?.let {
                        NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString()
                    } ?: ""
                    val status = Status.fromHTTPCode(statusCode, errorMessage)
                    continuation.resumeWithException(
                        RpcResponseException(
                            path = url,
                            verb = "POST",
                            code = status.code,
                            errorMessage = status.message
                        )
                    )
                } else {
                    val headers = httpResponse.allHeaderFields.mapKeys { it.key.toString() }.mapValues { it.value.toString() }
                    val responseData = data?.toByteArray() ?: byteArrayOf()
                    continuation.resume(RpcResponse(data = responseData, headers = headers))
                }
            }
        }
        task.resume()
    }

    actual override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit,
        readyCallback: () -> Unit
    ) {
        val url = if (useApiGateway) {
            method.toApiGatewayPath(rpcWebSocketBaseUrl(serverPath))
        } else {
            method.toPath(rpcWebSocketBaseUrl(serverPath))
        }

        val requestUrl = NSURL.URLWithString(url)!!
        val urlRequest = NSMutableURLRequest.requestWithURL(requestUrl)

        val webSocketTask = NSURLSession.sharedSession.webSocketTaskWithRequest(urlRequest)
        // Foundation defaults to 1 MiB, smaller than ordinary streamed RPC
        // snapshots (for example a full game frame set). Keep a bounded receive
        // buffer, but allow the same multi-megabyte payloads as unary calls.
        webSocketTask.maximumMessageSize = 16L * 1024 * 1024
        val serverStream = object : RpcServerStream {
            override suspend fun receive(): ByteArray = suspendCoroutine { cont ->
                webSocketTask.receiveMessageWithCompletionHandler { message, error ->
                    if (error != null) {
                        cont.resumeWithException(
                            RpcResponseException(
                                path = url,
                                verb = "RECEIVE",
                                code = Codes.from(error.code.toInt()),
                                errorMessage = error.localizedDescription ?: "Unknown error"
                            )
                        )
                    } else {
                        val data = message?.data!!.toByteArray()
                        cont.resume(data)
                    }
                }
            }

            override suspend fun send(bytes: ByteArray) = suspendCoroutine<Unit> { cont ->
                val data = bytes.toNSData()
                val message = NSURLSessionWebSocketMessage(data)

                webSocketTask.sendMessage(message) { error ->
                    if (error != null) {
                        cont.resumeWithException(
                            RpcResponseException(
                                path = url,
                                verb = "SEND",
                                code = Codes.from(error.code.toInt()),
                                errorMessage = error.localizedDescription ?: "Unknown error"
                            )
                        )
                    } else {
                        cont.resume(Unit)
                    }
                }
            }

            override suspend fun closeOutbound() {
            }

            override suspend fun closeInbound() {
            }
        }

        webSocketTask.resume()
        readyCallback()

        try {
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
