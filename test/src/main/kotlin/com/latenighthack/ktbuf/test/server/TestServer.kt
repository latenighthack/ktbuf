package com.latenighthack.ktbuf.test.server

import com.latenighthack.ktbuf.server.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

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

/**
 * An embedded test server.
 *
 * By default ([requestedPort] == 0) the server binds an OS-assigned free port: the
 * kernel hands out a guaranteed-unused port atomically at bind time, so [start] can
 * never lose a race to a port that is already taken. The previous implementation
 * guessed a random port in 49152..65535 and merely deduped against ports it had
 * handed out itself — but that range overlaps the OS ephemeral range used by outbound
 * client sockets, so a guessed port could already be in use and `start()` would throw
 * BindException intermittently. Pass an explicit [requestedPort] only when a test needs
 * a fixed, known port. The actually-bound port is exposed as [port] after [start].
 */
class TestServer(private val requestedPort: Int = 0): ServerTarget {
    /** The port the server is actually bound to. Only meaningful after [start]. */
    var port: Int = requestedPort
        private set

    override val serverUrl: String
        get() = "0.0.0.0:$port"

    private var server: EmbeddedServer<*, *>? = null
    private var serverStopped = CompletableDeferred<Boolean>()

    suspend fun start(extensions: suspend Application.() -> Unit = {}) {
        val started = defaultServer(config = {
            connector {
                this.port = requestedPort
            }
        }, extensions)
        server = started

        // Read the port the OS actually bound. When requestedPort is 0 this is the
        // kernel-assigned free port; when a fixed port was requested it echoes it back.
        port = started.engine.resolvedConnectors().first().port

        started.monitor.subscribe(ApplicationStopped) {
            serverStopped.complete(true)
        }
    }

    suspend fun stop() {
        server?.stop() ?: return

        serverStopped.await()
    }
}