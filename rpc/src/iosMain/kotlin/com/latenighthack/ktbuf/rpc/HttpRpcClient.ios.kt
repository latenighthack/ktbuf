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
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, timeMillis * 1_000_000), dispatch_get_main_queue()) {
            try {
                with(continuation) {
                    resumeUndispatched(Unit)
                }
            } catch (err: Throwable) {
                throw err
            }
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        NSLog("invokeOnTimeout %d", timeMillis)
        val handle = object : DisposableHandle {
            var disposed = false
                private set

            override fun dispose() {
//                disposed = true
            }
        }
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, timeMillis * 1_000_000L), dispatch_get_main_queue()) {
            try {
                if (!handle.disposed) {
                    context.run {
                        block.run()
                    }
                }
            } catch (err: Throwable) {
                throw err
            }
        }

        return handle
    }
}

private val mainDispatcher = MainQueueDispatcher()

actual class HttpRpcClient actual constructor(private val serverPath: String) : RpcClient {
    @OptIn(ExperimentalForeignApi::class)
    actual override suspend fun unaryCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcResponse = suspendCoroutine { continuation ->
        val url = method.toPath(serverPath)
        val requestUrl = NSURL.URLWithString(url)!!
        val urlRequest = NSMutableURLRequest.requestWithURL(requestUrl).apply {
            HTTPMethod = "POST"
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
                val headers = httpResponse.allHeaderFields.mapKeys { it.key.toString() }.mapValues { it.value.toString() }
                val responseData = data?.toByteArray() ?: byteArrayOf()
                continuation.resume(RpcResponse(data = responseData, headers = headers))
            }
        }
        task.resume()
    }

    actual override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit
    ) {
        val url = method.toPath(serverPath)
        val requestUrl = NSURL.URLWithString(url)!!
        val urlRequest = NSMutableURLRequest.requestWithURL(requestUrl).apply {
            HTTPMethod = "POST"
        }

        val webSocketTask = NSURLSession.sharedSession.webSocketTaskWithRequest(urlRequest)
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
                        val data = (message as NSData).toByteArray()
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
        }

        webSocketTask.resume()

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
        NSData.create(bytes = pinned.addressOf(0), length = this.size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val byteArray = ByteArray(this.length.toInt())
    memScoped {
        val buffer = byteArray.refTo(0).getPointer(this)

        memcpy(buffer, this@toByteArray.bytes, this@toByteArray.length)
    }
    return byteArray
}
