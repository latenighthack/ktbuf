package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class WebSocketPlatformRegressionTests {
    @Test fun streamingOpenHeadersAndMethodTransformsReachTheActualHandshake() = runBlocking<Unit> {
        Peer(0).use { peer ->
            val interceptor = object : StreamingRpcInterceptor {
                override suspend fun interceptOpen(method: RpcMethodSpecifier, headers: Map<String, String>, next: suspend (RpcMethodSpecifier, Map<String, String>) -> Unit) =
                    next(method.copy(methodName = "Changed"), headers + ("Authorization" to "regression-token"))
                override suspend fun interceptSend(method: RpcMethodSpecifier, request: ByteArray, next: suspend (RpcMethodSpecifier, ByteArray) -> Unit) = next(method, request)
                override suspend fun interceptReceive(method: RpcMethodSpecifier, next: suspend (RpcMethodSpecifier) -> ByteArray) = next(method)
            }
            InterceptingRpcClient(HttpRpcClient("127.0.0.1:${peer.listener.localPort}"), listOf(interceptor)).serverStreamingCall(
                RpcMethodSpecifier("p", "S", "M"), mapOf("X-Caller" to "preserved"), {}, {})
            assertEquals("/api/p.S/Changed", peer.path)
            assertEquals("regression-token", peer.headers["authorization"])
            assertEquals("preserved", peer.headers["x-caller"])
        }
    }
    @Test fun explicitSchemesAndGatewayParametersReachThePeerIntact() = runBlocking<Unit> {
        for (scheme in listOf("http://", "ws://")) Peer(0).use { peer ->
            val job = launch(Dispatchers.Default) {
                HttpRpcClient("${scheme}127.0.0.1:${peer.listener.localPort}/", true).serverStreamingCall(
                    RpcMethodSpecifier("p", "S", "M", mapOf("a&" to "+#雪")), { receive() }, {})
            }
            try {
                assertTrue(peer.opened.await(2, TimeUnit.SECONDS))
                assertEquals("/ws_p_S_M?a%26=%2B%23%E9%9B%AA", peer.path)
            } finally { job.cancelAndJoin() }
        }
    }
    @Test fun disposingAClientCancelsAndJoinsItsActualSocket() = runBlocking<Unit> {
        Peer(0).use { peer ->
            val client = HttpRpcClient("127.0.0.1:${peer.listener.localPort}")
            val operation = launch(Dispatchers.Default) { client.serverStreamingCall(RpcMethodSpecifier("p", "S", "M"), { receive() }, {}) }
            try {
                assertTrue(peer.opened.await(5, TimeUnit.SECONDS))
                client.closeAndJoin()
                withTimeout(2000) { operation.join() }
                assertTrue(operation.isCompleted && operation.isCancelled)
                assertTrue(peer.ended.await(1, TimeUnit.SECONDS), "client disposal retained its socket")
            } finally { client.closeAndJoin(); operation.cancelAndJoin() }
        }
    }
    private class Peer(private val frames:Int):AutoCloseable {
        val listener=ServerSocket(0)
        val opened=CountDownLatch(1)
        val ended=CountDownLatch(1)
        @Volatile var path=""
        @Volatile var headers: Map<String, String> = emptyMap()
        @Volatile var socket:Socket?=null
        val worker=thread(isDaemon=true) {
            try {
                listener.accept().use { accepted ->
                    socket=accepted
                    accepted.soTimeout=3000
                    val input=accepted.getInputStream()
                    val header=StringBuilder()
                    while(!header.endsWith("\r\n\r\n")) { val byte=input.read(); if(byte<0) return@use; header.append(byte.toChar()) }
                    val lines=header.toString().split("\r\n")
                    path=lines[0].split(" ")[1]
                    headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                    val key=lines.first { it.startsWith("Sec-WebSocket-Key:",true) }.substringAfter(':').trim()
                    val accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    val protocol=lines.firstOrNull { it.startsWith("Sec-WebSocket-Protocol:",true) }?.substringAfter(':')?.trim()
                    val extra=if(protocol==null) "" else "Sec-WebSocket-Protocol: $protocol\r\n"
                    val output=accepted.getOutputStream()
                    output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n${extra}\r\n".toByteArray())
                    output.flush(); opened.countDown()
                    repeat(frames) { output.write(byteArrayOf(-126,1,(it+1).toByte())) }
                    if(frames>0) output.write(byteArrayOf(-120,2,3,-24))
                    output.flush()
                    while(input.read()!=-1) { }
                }
            } catch (_:java.io.IOException) { }
            finally { ended.countDown() }
        }
        override fun close() { socket?.close(); listener.close(); worker.join(4000) }
    }
    @Test fun actualPlatformCallbacksPreserveOrderAndCompleteAtClose() = runBlocking<Unit> {
        Peer(32).use { peer ->
            val values=mutableListOf<Int>()
            withTimeout(2000) {
                HttpRpcClient("127.0.0.1:${peer.listener.localPort}").serverStreamingCall(RpcMethodSpecifier("p","S","M"), {
                    try { while(true) values.add(receive()[0].toInt()) } catch (_:ClosedReceiveChannelException) { }
                },{})
            }
            assertEquals((1..32).toList(),values)
        }
    }
    @Test fun actualPlatformCancellationClosesThePeerConnection() = runBlocking<Unit> {
        Peer(0).use { peer ->
            val job=launch(Dispatchers.Default) { HttpRpcClient("127.0.0.1:${peer.listener.localPort}").serverStreamingCall(RpcMethodSpecifier("p","S","M"),{ receive() },{}) }
            assertTrue(peer.opened.await(2,TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(peer.ended.await(700,TimeUnit.MILLISECONDS),"cancelled socket remained connected")
        }
    }
    @Test fun actualPlatformUsesTheGatewayPathWhenRequested() = runBlocking<Unit> {
        Peer(0).use { peer ->
            val job=launch(Dispatchers.Default) { HttpRpcClient("127.0.0.1:${peer.listener.localPort}",true).serverStreamingCall(RpcMethodSpecifier("p","S","M"),{ receive() },{}) }
            try {
                assertTrue(peer.opened.await(2,TimeUnit.SECONDS))
                assertEquals("/ws_p_S_M",peer.path)
            } finally { job.cancelAndJoin() }
        }
    }
}
