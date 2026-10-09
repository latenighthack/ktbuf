package com.latenighthack.ktbuf.rpc
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.Codes
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class NodeWebSocketRegressionTests {
    private class Peer(val http:dynamic,val sockets:dynamic,val url:String,val opened:CompletableDeferred<Unit>,val closed:CompletableDeferred<Unit>,val headers:MutableMap<String,String>) {
        fun stop() { sockets.clients.forEach { connection:dynamic -> connection.terminate() }; sockets.close(); http.close() }
    }
    private suspend fun peer(burst:Int=0):Peer = suspendCancellableCoroutine { continuation ->
        val httpModule=js("require('http')")
        val server=httpModule.createServer()
        val options=js("({})"); options.server=server
        val constructor=js("require('ws').WebSocketServer")
        val sockets=js("new constructor(options)")
        val opened=CompletableDeferred<Unit>();val closed=CompletableDeferred<Unit>();val headers=mutableMapOf<String,String>()
        sockets.on("connection",{ connection:dynamic, request:dynamic ->
            headers["authorization"]=request.headers.authorization as? String ?: ""
            opened.complete(Unit)
            connection.on("close",{ _:dynamic,_:dynamic -> closed.complete(Unit); Unit })
            connection.on("message",{ data:dynamic, binary:Boolean ->
                if (binary) connection.send(data)
                else if (data.toString("utf8")==RPC_REQUESTS_END) connection.close(1000,"")
            })
            if (burst>0) {
                val bufferConstructor=js("require('buffer').Buffer")
                repeat(burst) { index -> val values=arrayOf(index+1); connection.send(bufferConstructor.from(values)) }
                connection.close(1000,"")
            }
        })
        server.listen(0,"127.0.0.1",{
            continuation.resume(Peer(server,sockets,"127.0.0.1:${server.address().port}",opened,closed,headers)) { _,_,_ -> sockets.clients.forEach { connection:dynamic -> connection.terminate() }; sockets.close();server.close() }
        })
        continuation.invokeOnCancellation { sockets.clients.forEach { connection:dynamic -> connection.terminate() }; sockets.close();server.close() }
    }
    @Test fun actualNodeCallbacksRetainEveryFrameAndCompleteNormally() = runTest(timeout=8.seconds) {
        if (!isNode) return@runTest
        withContext(Dispatchers.Default) {
            val peer=peer(32)
            try {
                val values=mutableListOf<Int>()
                withTimeout(2000) { HttpRpcClient(peer.url).serverStreamingCall(RpcMethodSpecifier("p","S","M"),{
                    try { while(true) values.add(receive()[0].toInt()) } catch (_:ClosedReceiveChannelException) {}
                },{}) }
                assertEquals((1..32).toList(),values)
            } finally { peer.stop() }
        }
    }
    @Test fun sendsWaitForHandshakeAndHalfClosePreservesAllResponsesAndHeaders() = runTest(timeout=8.seconds) {
        if (!isNode) return@runTest
        withContext(Dispatchers.Default) {
            val peer=peer();var ready=false
            try {
                val values=mutableListOf<Int>()
                withTimeout(2000) { HttpRpcClient(peer.url).serverStreamingCall(RpcMethodSpecifier("p","S","M"),mapOf("Authorization" to "node-regression"),{
                    assertTrue(ready,"block ran before successful handshake")
                    repeat(3) {send(byteArrayOf((it+1).toByte()))}; closeOutbound()
                    try {while(true)values.add(receive()[0].toInt())}catch(_:ClosedReceiveChannelException){}
                },{ready=true}) }
                assertEquals(listOf(1,2,3),values);assertEquals("node-regression",peer.headers["authorization"])
            } finally {peer.stop()}
        }
    }
    @Test fun queuedResponseOverflowFailsExplicitly() = runTest(timeout=8.seconds) {
        if (!isNode) return@runTest
        withContext(Dispatchers.Default) {
            val peer=peer(128)
            try {
                val error=assertFailsWith<RpcResponseException> { withTimeout(1500) {
                    HttpRpcClient(peer.url).serverStreamingCall(RpcMethodSpecifier("p","S","M"), {
                        delay(100); while(true) receive()
                    },{})
                } }
                assertEquals(Codes.RESOURCE_EXHAUSTED,error.code)
            } finally {peer.stop()}
        }
    }
    @Test fun cancellingAnActualNodeCallClosesThePeer() = runTest(timeout=8.seconds) {
        if (!isNode) return@runTest
        withContext(Dispatchers.Default) {
            val peer=peer()
            try {
                val job=launch {HttpRpcClient(peer.url).serverStreamingCall(RpcMethodSpecifier("p","S","M"),{receive()},{})}
                withTimeout(2000){peer.opened.await()};job.cancelAndJoin()
                withTimeout(1000){peer.closed.await()}
            } finally {peer.stop()}
        }
    }
}
