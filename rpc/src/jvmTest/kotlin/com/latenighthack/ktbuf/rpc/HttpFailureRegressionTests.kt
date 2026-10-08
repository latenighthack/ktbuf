package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.*
import kotlinx.coroutines.*
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.*

class HttpFailureRegressionTests {
    private suspend fun truncatedResponse(status:Int, rpc:Boolean) {
        val listener=ServerSocket(0)
        val worker=thread(isDaemon=true) {
            listener.accept().use { socket ->
                var suffix=""
                val input=socket.getInputStream()
                while(!suffix.endsWith("\r\n\r\n")) {
                    val byte=input.read(); if(byte<0) return@use
                    suffix=(suffix+byte.toChar()).takeLast(4)
                }
                socket.getOutputStream().write("HTTP/1.1 $status Broken\r\nContent-Length: 10\r\nConnection: close\r\n\r\nx".toByteArray())
            }
        }
        try {
            val error=runCatching { withTimeout(1500) {
                val host="127.0.0.1:${listener.localPort}"
                if(rpc) HttpRpcClient(host).unaryCall(RpcMethodSpecifier("p","S","M"),emptyMap(),byteArrayOf())
                else PlatformHttpClient().httpCall("http://$host/test","GET",byteArrayOf(),emptyMap())
            } }.exceptionOrNull()
            assertTrue(error is IOException,"truncated $status body stranded caller: $error")
        } finally { listener.close(); worker.join(2000) }
    }
    @Test fun rpcSuccessBodyFailureResumesCaller() = runBlocking { truncatedResponse(200,true) }
    @Test fun rpcErrorBodyFailureResumesCaller() = runBlocking { truncatedResponse(500,true) }
    @Test fun httpSuccessBodyFailureResumesCaller() = runBlocking { truncatedResponse(200,false) }
    @Test fun httpErrorBodyFailureResumesCaller() = runBlocking { truncatedResponse(500,false) }
}
