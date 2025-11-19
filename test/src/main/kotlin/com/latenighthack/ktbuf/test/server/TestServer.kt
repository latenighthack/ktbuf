package com.latenighthack.ktbuf.test.server

import com.latenighthack.ktbuf.server.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random

interface ServerTarget {
    val serverUrl: String
}

class RemoteServer(override val serverUrl: String): ServerTarget

fun <T> runTestWithServer(
    extensions: suspend Application.() -> T? = { null },
    runner: suspend CoroutineScope.(server: TestServer, context: T?) -> Unit
) = runTest {
    val server = TestServer()
    var context: T? = null

    server.start({
        context = extensions()
    })

    runner(server, context)

    server.stop()
}

class TestServer(val port: Int = randomPort()): ServerTarget {
    companion object {
        private val activePorts = mutableSetOf<Int>()
        private fun randomPort(): Int {
            var port: Int

            while (true) {
                port = Random.nextInt(49152, 65535)

                if (activePorts.contains(port)) {
                    continue
                }

                activePorts.add(port)
                break
            }

            return port
        }
    }

    override val serverUrl: String
        get() = "0.0.0.0:$port"

    private var server: EmbeddedServer<*, *>? = null
    private var serverStopped = CompletableDeferred<Boolean>()

    suspend fun start(extensions: suspend Application.() -> Unit = {}) {
        server = defaultServer(config = {
            connector {
                this.port = this@TestServer.port
            }
        }, extensions)

        server?.monitor?.subscribe(ApplicationStopped) {
            serverStopped.complete(true)
        }
    }

    suspend fun stop() {
        server?.stop() ?: return

        serverStopped.await()
    }
}