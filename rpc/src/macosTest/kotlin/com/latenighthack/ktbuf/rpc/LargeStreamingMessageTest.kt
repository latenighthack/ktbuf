package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.net.RpcMethodSpecifier
import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import platform.posix.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real Foundation transport regression: the old 1 MiB default rejects this snapshot. */
@OptIn(ExperimentalForeignApi::class)
class LargeStreamingMessageTest {
    @Test
    fun receivesSnapshotLargerThanOneMiB() = runBlocking {
        // A loopback RFC 6455 fixture using only macOS's Python standard library.
        // The OS assigns the port; no shared server or production endpoint is involved.
        val fixture = """
            import socket, hashlib, base64, struct
            listener = socket.socket()
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.settimeout(15)
            print(listener.getsockname()[1], flush=True)
            peer, _ = listener.accept()
            peer.settimeout(15)
            request = b""
            while b"\r\n\r\n" not in request:
                request += peer.recv(4096)
            headers = dict(line.split(b":", 1) for line in request.split(b"\r\n")[1:] if b":" in line)
            key = next(value.strip() for name, value in headers.items() if name.lower() == b"sec-websocket-key")
            accept = base64.b64encode(hashlib.sha1(key + b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest())
            peer.sendall(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + b"\r\n\r\n")
            payload = bytes([42]) * 1142787
            peer.sendall(bytes([130, 127]) + struct.pack("!Q", len(payload)) + payload)
            try:
                peer.recv(4096)
            except OSError:
                pass
            peer.close()
            listener.close()
        """.trimIndent()
        val pipe = checkNotNull(popen("/usr/bin/python3 -u -c '$fixture'", "r"))
        try {
            val port = memScoped {
                val buffer = allocArray<ByteVar>(32)
                checkNotNull(fgets(buffer, 32, pipe)).toKString().trim().toInt()
            }
            val client = HttpRpcClient("http://127.0.0.1:$port")
            try { client.serverStreamingCall(
                RpcMethodSpecifier("test", "Snapshot", "Watch"),
                block = {
                    val payload = receive()
                    assertEquals(1142787, payload.size)
                    assertTrue(payload.all { it == 42.toByte() })
                },
                readyCallback = {},
            ) } finally { client.closeAndJoin() }
        } finally {
            pclose(pipe)
        }
    }
}
