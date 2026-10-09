package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val lifecycle = com.latenighthack.ktbuf.net.RpcClientLifecycle()
    actual fun close() = lifecycle.close()
    actual suspend fun closeAndJoin() = lifecycle.closeAndJoin()

    actual override suspend fun unaryCall(method: RpcMethodSpecifier, headers: Map<String,String>, request: ByteArray): RpcResponse = lifecycle.call {
        val base = rpcHttpBaseUrl(serverPath)
        val url = if (useApiGateway) method.toApiGatewayPath(base) else method.toPath(base)
        performNativeHttpRequest(url, "POST", request, mapOf("Content-Type" to "application/proto") + headers)
    }

    actual override suspend fun serverStreamingCall(method: RpcMethodSpecifier, block: suspend RpcServerStream.() -> Unit, readyCallback: () -> Unit) =
        serverStreamingCall(method, emptyMap(), block, readyCallback)

    actual override suspend fun serverStreamingCall(method: RpcMethodSpecifier, headers: Map<String,String>, block: suspend RpcServerStream.() -> Unit, readyCallback: () -> Unit) = lifecycle.call {
        coroutineScope {
            require(headers.keys.none { it.equals("Sec-WebSocket-Protocol", true) }) { "RPC protocol header is reserved" }
            val base = rpcWebSocketBaseUrl(serverPath)
            val url = if (useApiGateway) method.toApiGatewayPath(base) else method.toPath(base)
            val request = NSMutableURLRequest.requestWithURL(requireNotNull(NSURL.URLWithString(url))).apply {
                headers.forEach { (name,value) -> setValue(value, forHTTPHeaderField = name) }
                setValue(RPC_STREAM_PROTOCOL, forHTTPHeaderField = "Sec-WebSocket-Protocol")
            }
            val ready = CompletableDeferred<Unit>()
            val inbound = Channel<ByteArray>(64)
            val active = AtomicReference(true)
            val selectedProtocol = AtomicReference<String?>(null)
            val terminal = AtomicReference<Throwable?>(null)
            val closeStatus = AtomicReference<Status?>(null)
            val queuedBytes = AtomicReference(0L)
            val byteLimit = com.latenighthack.ktbuf.ProtobufLimits().maxMessageBytes.toLong()
            lateinit var task: NSURLSessionWebSocketTask
            lateinit var receiveNext: () -> Unit
            fun failure(code: Codes, message: String, cause: Throwable? = null) = RpcResponseException(url, "WS", code, message, cause)
            fun stop(cause: Throwable?) {
                if (cause != null) terminal.compareAndSet(null, cause)
                ready.completeExceptionally(cause ?: failure(Codes.UNAVAILABLE, "Socket closed before opening"))
                inbound.close(cause)
                active.value = false
            }
            fun socketError(error: NSError): Throwable {
                val status = closeStatus.value ?: task.closeCode.toInt().takeIf { it != 0 }?.let { Status.fromWSCode(it, task.closeReason?.toByteArray()?.decodeToString()) }
                return if (status != null) {
                    if (status.code == Codes.OK) kotlinx.coroutines.channels.ClosedReceiveChannelException("RPC response EOF") else failure(status.code, status.message)
                } else error.rpcFailure(url, "WS", task.response)
            }
            val delegate = object : NSObject(), NSURLSessionWebSocketDelegateProtocol {
                @ObjCSignatureOverride
                override fun URLSession(session: NSURLSession, webSocketTask: NSURLSessionWebSocketTask, didOpenWithProtocol: String?) {
                    selectedProtocol.value = didOpenWithProtocol
                    ready.complete(Unit)
                    receiveNext()
                }
                @ObjCSignatureOverride
                override fun URLSession(session: NSURLSession, webSocketTask: NSURLSessionWebSocketTask, didCloseWithCode: NSURLSessionWebSocketCloseCode, reason: NSData?) {
                    closeStatus.value = Status.fromWSCode(didCloseWithCode.toInt(), reason?.toByteArray()?.decodeToString())
                }
                @ObjCSignatureOverride
                override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
                    if (!ready.isCompleted) stop(didCompleteWithError?.rpcFailure(url, "WS", task.response) ?: failure(Codes.UNAVAILABLE, "Socket closed before opening"))
                }
            }
            val callbackQueue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
            val session = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.defaultSessionConfiguration(), delegate, callbackQueue)
            task = session.webSocketTaskWithRequest(request)
            task.maximumMessageSize = byteLimit
            receiveNext = receive@{
                if (!active.value) return@receive
                task.receiveMessageWithCompletionHandler { message, error ->
                    if (active.value) {
                        if (error != null) {
                            val cause = socketError(error)
                            stop(if (cause is kotlinx.coroutines.channels.ClosedReceiveChannelException) null else cause)
                        } else {
                            val result = runCatching {
                                val data = message?.data ?: throw failure(Codes.INVALID_ARGUMENT, "Expected a binary RPC response")
                                val bytes = data.toByteArray()
                                val previous = queuedBytes.getAndUpdate { this + bytes.size }
                                if (bytes.size > byteLimit || previous + bytes.size > byteLimit) {
                                    queuedBytes.getAndUpdate { this - bytes.size }
                                    throw failure(Codes.RESOURCE_EXHAUSTED, "RPC response queue exceeds byte limit")
                                }
                                if (!inbound.trySend(bytes).isSuccess) {
                                    queuedBytes.getAndUpdate { this - bytes.size }
                                    throw failure(Codes.RESOURCE_EXHAUSTED, "RPC response queue is full")
                                }
                            }
                            if (result.isFailure) { stop(result.exceptionOrNull()); task.cancel() }
                            else receiveNext()
                        }
                    }
                }
            }
            val sending = Mutex()
            var outboundClosed = false
            suspend fun sendMessage(message: NSURLSessionWebSocketMessage) = suspendCancellableCoroutine<Unit> { continuation ->
                task.sendMessage(message) { error -> continuation.completeCallback(if (error == null) Result.success(Unit) else Result.failure(terminal.value ?: socketError(error))) }
                continuation.invokeOnCancellation { task.cancel() }
            }
            val stream = object : RpcServerStream {
                override suspend fun receive(): ByteArray = inbound.receive().also { queuedBytes.getAndUpdate { this - it.size } }
                override suspend fun send(bytes: ByteArray) {
                    ready.await()
                    sending.withLock {
                        check(!outboundClosed) { "Request stream is closed" }
                        if (bytes.size > byteLimit) throw failure(Codes.RESOURCE_EXHAUSTED, "RPC frame exceeds byte limit")
                        sendMessage(NSURLSessionWebSocketMessage(bytes.toNSData()))
                    }
                }
                override suspend fun closeOutbound() {
                    ready.await()
                    sending.withLock {
                        if (!outboundClosed) {
                            if (selectedProtocol.value != RPC_STREAM_PROTOCOL) throw failure(Codes.UNIMPLEMENTED, "Peer does not support request half-close")
                            sendMessage(NSURLSessionWebSocketMessage(RPC_REQUESTS_END))
                            outboundClosed = true
                        }
                    }
                }
                override suspend fun closeInbound() { stop(null); task.cancel() }
            }
            try {
                task.resume()
                ready.await()
                readyCallback()
                stream.block()
            } finally {
                active.value = false
                inbound.cancel()
                task.cancel()
                session.invalidateAndCancel()
            }
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
