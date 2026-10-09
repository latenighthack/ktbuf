package com.latenighthack.ktbuf.rpc
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.Codes
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class PlatformCancellationRegressionTests {
    @Test fun actualHttpAndRpcOperationsCancelTheirNetworkRequests() = runTest(timeout=8.seconds) {
        withContext(Dispatchers.Default) {
            val peer=testPeerUrl();val http=PlatformHttpClient()
            for (rpc in listOf(false,true)) {
                val id="js-${isNode}-$rpc"
                val job=launch {
                    if (rpc) HttpRpcClient(peer).unaryCall(RpcMethodSpecifier("peer","Transport","Hang",mapOf("id" to id)),emptyMap(),byteArrayOf(1))
                    else http.httpCall("$peer/api/peer.Transport/Hang?id=$id","POST",byteArrayOf(1),emptyMap())
                }
                delay(100)
                withTimeout(500){job.cancelAndJoin()}
                withTimeout(1000) {
                    while(http.httpCall("$peer/cancelled?id=$id","GET",byteArrayOf(),emptyMap()).decodeToString()!="true") delay(20)
                }
            }
        }
    }
    @Test fun realHttpSuccessAndExplicitErrorMetadataWorkOnBothJsHosts() = runTest(timeout=8.seconds) {
        withContext(Dispatchers.Default) {
            val peer=testPeerUrl();val http=PlatformHttpClient()
            assertContentEquals(byteArrayOf(1,2,3),http.httpCall("$peer/echo","POST",byteArrayOf(1,2,3),emptyMap()))
            val error=assertFailsWith<RpcResponseException> {HttpRpcClient(peer).unaryCall(RpcMethodSpecifier("peer","Transport","Error"),emptyMap(),byteArrayOf())}
            assertEquals(Codes.ALREADY_EXISTS,error.code);assertTrue(error.errorMessage.contains("arbitrary error detail"))
        }
    }
    @Test fun actualWebSocketCallbacksDrainBurstAndRejectTextFrames() = runTest(timeout=8.seconds) {
        withContext(Dispatchers.Default) {
            val client=HttpRpcClient(testPeerUrl());val values=mutableListOf<Int>()
            withTimeout(2000){client.serverStreamingCall(RpcMethodSpecifier("peer","Transport","Burst"), {
                delay(50)
                try {while(true) values.add(receive()[0].toInt())}catch(_:ClosedReceiveChannelException){}
            },{})}
            assertEquals((1..32).toList(),values)
            val error=assertFailsWith<RpcResponseException> {withTimeout(2000){client.serverStreamingCall(RpcMethodSpecifier("peer","Transport","Text"),{receive()},{})}}
            assertEquals(Codes.INVALID_ARGUMENT,error.code)
        }
    }
    @Test fun failedHandshakeNeverSignalsReadinessOrRunsTheBlock() = runTest(timeout=8.seconds) {
        withContext(Dispatchers.Default) {
            var ready=false;var block=false
            assertFailsWith<RpcResponseException> {withTimeout(2000){HttpRpcClient(testPeerUrl()).serverStreamingCall(RpcMethodSpecifier("peer","Transport","FailHandshake"),{block=true;receive()},{ready=true})}}
            assertFalse(ready);assertFalse(block)
        }
    }
    @Test fun cancellingAnActualBrowserOrNodeReceiveClosesThePeer() = runTest(timeout=8.seconds) {
        withContext(Dispatchers.Default) {
            val url=testPeerUrl();val id="ws-js-$isNode";val http=PlatformHttpClient()
            val job=launch {HttpRpcClient(url).serverStreamingCall(RpcMethodSpecifier("peer","Transport","Wait",mapOf("id" to id)),{receive()},{})}
            delay(100);withTimeout(500){job.cancelAndJoin()}
            withTimeout(1000){while(http.httpCall("$url/cancelled?id=$id","GET",byteArrayOf(),emptyMap()).decodeToString()!="true")delay(20)}
        }
    }
    @Test fun browsersRejectCustomHandshakeHeadersExplicitly() = runTest(timeout=8.seconds) {
        if(isNode)return@runTest
        val error=assertFailsWith<RpcResponseException> {HttpRpcClient(testPeerUrl()).serverStreamingCall(RpcMethodSpecifier("peer","Transport","Echo"),mapOf("Authorization" to "unsupported"),{error("unexpected")},{})}
        assertEquals(Codes.UNIMPLEMENTED,error.code)
    }
}
