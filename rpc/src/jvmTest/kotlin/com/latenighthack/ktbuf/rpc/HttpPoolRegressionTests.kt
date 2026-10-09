package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.*
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

class HttpPoolRegressionTests {
    @Test fun defaultClientsReuseConnectionsWithoutSharingRequestCredentials() = runBlocking {
        val listener = ServerSocket(0)
        val peer = AtomicReference<java.net.Socket?>()
        val credentials = Collections.synchronizedList(mutableListOf<String>())
        val worker = thread(isDaemon = true) {
            try {
                listener.accept().use { socket ->
                    peer.set(socket)
                    socket.soTimeout = 4000
                    val input = socket.getInputStream().bufferedReader()
                    repeat(3) {
                        check(input.readLine() != null)
                        var line = input.readLine()
                        while (line.isNotEmpty()) {
                            if (line.startsWith("Authorization:", ignoreCase = true)) credentials.add(line.substringAfter(':').trim())
                            line = input.readLine()
                        }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: keep-alive\r\n\r\nx".toByteArray())
                            flush()
                        }
                    }
                }
            } catch (_: java.io.IOException) { /* Closed by timeout/finally on the failing baseline. */ }
        }
        try {
            withTimeout(2500) {
                val host = "127.0.0.1:${listener.localPort}"
                val method = RpcMethodSpecifier("p", "S", "M")
                HttpRpcClient(host).unaryCall(method, mapOf("Authorization" to "Bearer first"), byteArrayOf())
                PlatformHttpClient().httpCall("http://$host/raw", "GET", byteArrayOf(), mapOf("Authorization" to "Bearer second"))
                HttpRpcClient(host).unaryCall(method, mapOf("Authorization" to "Bearer third"), byteArrayOf())
            }
            worker.join(1000)
            assertEquals(listOf("Bearer first", "Bearer second", "Bearer third"), credentials)
        } finally {
            peer.get()?.close()
            listener.close()
            worker.join(2000)
        }
    }
}
