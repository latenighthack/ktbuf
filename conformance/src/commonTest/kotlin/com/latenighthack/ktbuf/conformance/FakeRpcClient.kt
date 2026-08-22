package com.latenighthack.ktbuf.conformance

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.net.RpcMethodSpecifier
import com.latenighthack.ktbuf.net.RpcResponse
import com.latenighthack.ktbuf.net.RpcServerStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException

// A multiplatform stand-in for the transport. :test's StubRpcClient is JVM-only and has
// a no-op serverStreamingCall, so it cannot drive the streaming paths from commonTest.
// This one records the exact bytes GrpcService hands the transport, which is what the
// marshalling goldens assert against.

class RecordedCall(
    val method: RpcMethodSpecifier,
    val headers: Map<String, String>,
    val request: ByteArray
)

class FakeRpcClient(
    /** Frames the server sends back on a streaming call, delivered in order. */
    private val serverFrames: List<ByteArray> = emptyList(),
    /** Unary reply bytes. */
    private val unaryResponse: ByteArray = ByteArray(0),
    /** When set, a streaming call echoes each sent frame through this transform. */
    private val echo: ((ByteArray) -> ByteArray)? = null
) : RpcClient {
    val unaryCalls = mutableListOf<RecordedCall>()
    val streamedRequests = mutableListOf<ByteArray>()
    var lastStreamMethod: RpcMethodSpecifier? = null
        private set
    var readyCallbackFired = false
        private set

    override suspend fun unaryCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray
    ): RpcResponse {
        unaryCalls.add(RecordedCall(method, headers, request))

        return RpcResponse(unaryResponse, emptyMap())
    }

    override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit,
        readyCallback: () -> Unit
    ) {
        lastStreamMethod = method
        readyCallback()
        readyCallbackFired = true

        val stream = if (echo != null) {
            EchoStream(echo, streamedRequests)
        } else {
            ScriptedStream(serverFrames, streamedRequests)
        }

        stream.block()
    }
}

/**
 * Replays a fixed script of server frames, then reports end-of-stream. Mirrors a
 * server-streaming call: the client sends one request and reads until the server is done.
 *
 * End-of-stream is signalled with a cause-less ClosedReceiveChannelException, which is
 * how GrpcService distinguishes a clean close from a transport failure -- when the
 * exception carries a cause it cancels the flow with it instead of completing normally.
 */
private class ScriptedStream(
    private val frames: List<ByteArray>,
    private val sent: MutableList<ByteArray>
) : RpcServerStream {
    private var index = 0

    override suspend fun receive(): ByteArray {
        if (index >= frames.size) {
            throw ClosedReceiveChannelException("end of scripted stream")
        }

        return frames[index++]
    }

    override suspend fun send(bytes: ByteArray) {
        sent.add(bytes)
    }

    override suspend fun closeOutbound() {}

    override suspend fun closeInbound() {}
}

/**
 * Bidirectional stand-in: every frame the client sends comes back transformed. Ending
 * the request flow closes the inbound side, which is how a real bidi call terminates.
 */
private class EchoStream(
    private val transform: (ByteArray) -> ByteArray,
    private val sent: MutableList<ByteArray>
) : RpcServerStream {
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)

    override suspend fun receive(): ByteArray = inbound.receive()

    override suspend fun send(bytes: ByteArray) {
        sent.add(bytes)
        inbound.send(transform(bytes))
    }

    override suspend fun closeOutbound() {}

    override suspend fun closeInbound() {
        inbound.close()
    }
}
