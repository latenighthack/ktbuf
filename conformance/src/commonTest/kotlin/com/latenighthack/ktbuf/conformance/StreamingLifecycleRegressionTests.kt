package com.latenighthack.ktbuf.conformance

import com.latenighthack.ktbuf.conformance.v1.*
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.Codes
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingLifecycleRegressionTests {
    private fun transport(stream: RpcServerStream) = object : RpcClient {
        override suspend fun unaryCall(method: RpcMethodSpecifier, headers: Map<String, String>, request: ByteArray) = error("unused")
        override suspend fun serverStreamingCall(method: RpcMethodSpecifier, block: suspend RpcServerStream.() -> Unit, readyCallback: () -> Unit) { readyCallback(); stream.block() }
    }
    @Test fun finiteRequestCompletionDrainsDelayedRepliesIncludingEmptyMessages() = runTest(timeout = 3.seconds) {
        val replies = Channel<ByteArray>(16)
        val sent = mutableListOf<ByteArray>()
        var closed = false
        val stream = object : RpcServerStream {
            override suspend fun receive() = replies.receive()
            override suspend fun send(bytes: ByteArray) { sent.add(bytes) }
            override suspend fun closeOutbound() { closed = true; delay(20); sent.forEach { replies.send(it) }; replies.close() }
            override suspend fun closeInbound() { error("request completion must not discard responses") }
        }
        val requests = listOf(AllTypes(), AllTypes(fInt32=2), AllTypes(fInt32=3))
        assertEquals(requests, ConformanceServiceRpc(transport(stream)).bidi(requests.asFlow()).toList())
        assertTrue(closed)
    }
    @Test fun emptyRequestFlowStillOpensAndHalfCloses() = runTest(timeout = 3.seconds) {
        val replies = Channel<ByteArray>()
        val stream = object : RpcServerStream {
            override suspend fun receive() = replies.receive()
            override suspend fun send(bytes: ByteArray) { error("empty source sent a request") }
            override suspend fun closeOutbound() { replies.close() }
            override suspend fun closeInbound() { }
        }
        assertEquals(emptyList(), ConformanceServiceRpc(transport(stream)).bidi(emptyFlow()).toList())
    }
    @Test fun transportFailurePropagatesAndStopsAnInfiniteProducer() = runTest(timeout = 3.seconds) {
        var stopped = false
        val requests = flow { try { emit(AllTypes()); awaitCancellation() } finally { stopped = true } }
        val stream = object : RpcServerStream {
            override suspend fun receive(): ByteArray = throw RpcResponseException("", "WS", Codes.PERMISSION_DENIED, "denied")
            override suspend fun send(bytes: ByteArray) { }
            override suspend fun closeOutbound() { }
            override suspend fun closeInbound() { }
        }
        val failure = assertFailsWith<RpcResponseException> { ConformanceServiceRpc(transport(stream)).bidi(requests).toList() }
        assertEquals(Codes.PERMISSION_DENIED, failure.code); assertTrue(stopped)
    }
    @Test fun successfulResponseEofAlsoStopsAnInfiniteProducer() = runTest(timeout = 3.seconds) {
        var stopped = false
        val stream = object : RpcServerStream {
            override suspend fun receive(): ByteArray = throw ClosedReceiveChannelException("EOF")
            override suspend fun send(bytes: ByteArray) { }
            override suspend fun closeOutbound() { }
            override suspend fun closeInbound() { }
        }
        assertEquals(emptyList(), ConformanceServiceRpc(transport(stream)).bidi(flow { try { emit(AllTypes()); awaitCancellation() } finally { stopped = true } }).toList())
        assertTrue(stopped)
    }
    @Test fun parsingFailureIsNotReplacedByCancellation() = runTest(timeout = 3.seconds) {
        val rpc = FakeRpcClient(serverFrames=listOf(byteArrayOf(8, -128)))
        assertFailsWith<IndexOutOfBoundsException> { ConformanceServiceRpc(rpc).serverStream(AllTypes()).toList() }
        assertFailsWith<IndexOutOfBoundsException> { ConformanceServiceRpc(rpc).bidi(flow { emit(AllTypes()); awaitCancellation() }).toList() }
    }
    @Test fun requestCollectionIsBoundedBeforeTheSocketOpens() = runTest(timeout = 3.seconds) {
        var collected = 0
        val rpc = object : RpcClient {
            override suspend fun unaryCall(method: RpcMethodSpecifier, headers: Map<String,String>, request: ByteArray) = error("unused")
            override suspend fun serverStreamingCall(method: RpcMethodSpecifier, block: suspend RpcServerStream.() -> Unit, readyCallback: () -> Unit) { awaitCancellation() }
        }
        val job = launch { ConformanceServiceRpc(rpc).bidi(flow { repeat(10000) { collected++; emit(AllTypes()) } }).toList() }
        runCurrent(); assertTrue(collected <= 18, "collected $collected requests before connection opened"); job.cancelAndJoin()
    }
}
