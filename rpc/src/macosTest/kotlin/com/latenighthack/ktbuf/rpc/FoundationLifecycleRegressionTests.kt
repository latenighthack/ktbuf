@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.latenighthack.ktbuf.rpc
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.Codes
import kotlinx.cinterop.toKString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import platform.posix.getenv
import kotlin.test.*
class FoundationLifecycleRegressionTests {
    private fun peer():String=checkNotNull(getenv("KTBUF_PEER_URL")).toKString()
    @Test fun actualDataTasksStopWhenHttpAndRpcCallersCancel() = runBlocking<Unit> {
        val peer=peer();val http=PlatformHttpClient()
        for(rpc in listOf(false,true)) {
            val id="foundation-$rpc"
            val job=launch(Dispatchers.Default) {
                if(rpc)HttpRpcClient(peer).unaryCall(RpcMethodSpecifier("peer","Transport","Hang",mapOf("id" to id)),emptyMap(),byteArrayOf(1))
                else http.httpCall("$peer/api/peer.Transport/Hang?id=$id","POST",byteArrayOf(1),emptyMap())
            }
            delay(100);withTimeout(500){job.cancelAndJoin()}
            withTimeout(1000){while(http.httpCall("$peer/cancelled?id=$id","GET",byteArrayOf(),emptyMap()).decodeToString()!="true")delay(20)}
        }
    }
    @Test fun failedHandshakeNeverRunsBlockOrReadyCallback() = runBlocking<Unit> {
        var ready=false;var block=false
        assertFailsWith<RpcResponseException>{withTimeout(2000){HttpRpcClient(peer()).serverStreamingCall(RpcMethodSpecifier("peer","Transport","FailHandshake"),{block=true;receive()},{ready=true})}}
        assertFalse(ready);assertFalse(block)
    }
    @Test fun queuedBinaryResponsesDrainAtNormalEofAndTextFramesFailExplicitly() = runBlocking<Unit> {
        val client=HttpRpcClient(peer());val values=mutableListOf<Int>()
        withTimeout(2000){client.serverStreamingCall(RpcMethodSpecifier("peer","Transport","Burst"),{
            delay(50)
            try{while(true)values.add(receive()[0].toInt())}catch(_:ClosedReceiveChannelException){}
        },{})}
        assertEquals((1..32).toList(),values)
        val text=assertFailsWith<RpcResponseException>{withTimeout(2000){client.serverStreamingCall(RpcMethodSpecifier("peer","Transport","Text"),{receive()},{})}}
        assertEquals(Codes.INVALID_ARGUMENT,text.code)
        val error=assertFailsWith<RpcResponseException>{withTimeout(2000){client.serverStreamingCall(RpcMethodSpecifier("peer","Transport","Error"),{receive()},{})}}
        assertEquals(Codes.INTERNAL,error.code);assertEquals("arbitrary error detail",error.errorMessage)
    }
    @Test fun handshakeHeadersAndHalfCloseKeepEveryResponse() = runBlocking<Unit> {
        val url=peer();val client=HttpRpcClient(url);val values=mutableListOf<Int>();var ready=false
        withTimeout(2000){client.serverStreamingCall(RpcMethodSpecifier("peer","Transport","Echo",mapOf("id" to "native-headers")),mapOf("Authorization" to "foundation-regression"),{
            assertTrue(ready)
            repeat(3){send(byteArrayOf((it+1).toByte()))};closeOutbound()
            try{while(true)values.add(receive()[0].toInt())}catch(_:ClosedReceiveChannelException){}
        },{ready=true})}
        assertEquals(listOf(1,2,3),values)
        assertEquals("foundation-regression",PlatformHttpClient().httpCall("$url/handshake?id=native-headers","GET",byteArrayOf(),emptyMap()).decodeToString())
    }
    @Test fun cancellingAnActualReceiveClosesThePeer() = runBlocking<Unit> {
        val url=peer();val http=PlatformHttpClient()
        val job=launch(Dispatchers.Default){HttpRpcClient(url).serverStreamingCall(RpcMethodSpecifier("peer","Transport","Wait",mapOf("id" to "native-cancel")),{receive()},{})}
        delay(100);withTimeout(500){job.cancelAndJoin()}
        withTimeout(1000){while(http.httpCall("$url/cancelled?id=native-cancel","GET",byteArrayOf(),emptyMap()).decodeToString()!="true")delay(20)}
    }
    @Test fun connectionFailuresKeepTheirNativeCauseAndDomainMapping() = runBlocking<Unit> {
        val failure=assertFailsWith<RpcResponseException>{withTimeout(2000){HttpRpcClient("http://127.0.0.1:1").unaryCall(RpcMethodSpecifier("peer","Transport","Missing"),emptyMap(),byteArrayOf())}}
        assertEquals(Codes.UNAVAILABLE,failure.code)
        val cause=assertIs<NativeTransportException>(failure.cause)
        assertEquals("NSURLErrorDomain",cause.domain)
        assertTrue(cause.nativeCode<0)
    }
    @Test fun realUnaryErrorMetadataSurvivesFoundation() = runBlocking<Unit> {
        val failure=assertFailsWith<RpcResponseException>{withTimeout(2000){HttpRpcClient(peer()).unaryCall(RpcMethodSpecifier("peer","Transport","Error"),emptyMap(),byteArrayOf())}}
        assertEquals(Codes.ALREADY_EXISTS,failure.code);assertTrue(failure.errorMessage.contains("arbitrary error detail"))
    }

}
