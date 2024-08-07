package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.proto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlin.coroutines.resumeWithException
import platform.Foundation.NSLog
import platform.darwin.*
import kotlin.coroutines.CoroutineContext

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

interface RetainedState {
    fun unimportantWork()
}

public typealias HttpCallback = (httpCode: Int, internalCode: Int, responseHeaders: Map<String, String>, response: ByteArray?, status: String?) -> Unit

public interface NativeHttpDriver {
    interface StreamHandler {
        fun onReceive(bytes: ByteArray)

        fun send(sender: (ByteArray) -> Unit)

        fun close(code: Int, message: String)
    }

    fun openWebSocket(url: String, handler: StreamHandler): RetainedState

    fun post(url: String, headers: Map<String, String>, bytes: ByteArray, callback: HttpCallback): RetainedState
}

@OptIn(DelicateCoroutinesApi::class)
public class NativeHttpRpcClient(private val serverPath: String, private val platformDriver: NativeHttpDriver) : RpcClient {
    private class StreamContext : RpcClient.ServerStream {
        // Note: we must buffer the outbound channel in order to catch WebSocket setup errors.
        //
        // If there is a (terminal?) error constructing the websocket, the outbound channel will never be received()
        // from, resulting in the calling send() blocking indefinitely. This approach currently works since we only
        // send a single request for any stream. If we move to full bidi, we would need to account for this more
        // accurately.
        val outbound = Channel<ByteArray>(1)
        val inbound = Channel<ByteArray>()

        override suspend fun receive(): ByteArray {
            return inbound.receive()
        }

        override suspend fun send(bytes: ByteArray) {
            outbound.send(bytes)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun unaryCall(
        method: RpcClient.MethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcClient.Response = suspendCancellableCoroutine { continuation ->
        val callback: HttpCallback = { httpCode: Int, internalCode: Int, responseHeaders: Map<String, String>, response: ByteArray?, status: String? ->
            if (httpCode == 200) {
                continuation.resume(RpcClient.Response(response ?: byteArrayOf(), responseHeaders)) { }
            } else {
                val status = Status.fromHTTPCode(httpCode, "$status (code: $internalCode)")
                continuation.resumeWithException(RpcClient.ResponseException(method.toPath(""), "POST", status.code, status.message))
            }
        }

        platformDriver.post("https://${method.toPath(serverPath)}", headers, request, callback)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun serverStreamingCall(
        method: RpcClient.MethodSpecifier,
        block: suspend RpcClient.ServerStream.() -> Unit
    ) {
        val context = StreamContext()

        val retainedState = platformDriver.openWebSocket("wss://${method.toPath(serverPath)}", object : NativeHttpDriver.StreamHandler {
            override fun onReceive(bytes: ByteArray) {
                GlobalScope.launch(mainDispatcher) {
                    context.inbound.send(bytes)
                }
            }

            override fun send(sender: (ByteArray) -> Unit) {
                GlobalScope.launch(mainDispatcher) {
                    while (!context.outbound.isClosedForReceive) {
                        try {
                            sender(context.outbound.receive())
                        } catch (_: ClosedReceiveChannelException) {
                            // ClosedReceiveChannelException occurs in the happy path when the stream ends.
                        } catch (_: RpcClient.ResponseException) {
                        } catch (t: Throwable) {
                        }
                    }
                }
            }

            override fun close(code: Int, message: String) {
                GlobalScope.launch(mainDispatcher) {
                    val status = Status.fromWSCode(code, message)
                    val cause = if (status.code == Codes.OK) null else {
                        RpcClient.ResponseException("", "WS", status.code, status.message)
                    }

                    context.inbound.close(cause)
                    context.outbound.close(cause)
                }
            }
        })

        context.block()

        retainedState.unimportantWork()
    }
}
