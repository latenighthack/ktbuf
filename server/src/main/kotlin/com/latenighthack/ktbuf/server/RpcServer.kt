package com.latenighthack.ktbuf.server

import com.latenighthack.ktbuf.net.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.logging.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

suspend fun defaultServer(config: ApplicationEngine.Configuration.() -> Unit = {}, extensions:Application.() -> Unit = {}): EmbeddedServer<*, *> {
    val server = embeddedServer(
        CIO,
        environment = applicationEnvironment { log = KtorSimpleLogger("server") },
        configure = {
            config()
        }
    ) {
        install(WebSockets)

        extensions()
    }
    val ready = CompletableDeferred<Boolean>()

    server.monitor.subscribe(ServerReady) {
        ready.complete(true)
    }
    server.monitor.subscribe(ApplicationStopped) {
        ready.completeExceptionally(IllegalStateException("Stopping"))
    }

    server.start()

    ready.await()

    return server
}

public suspend fun runServer(extensions: Application.() -> Unit) {
    val server = defaultServer(config = {
        connectionGroupSize = 2
        workerGroupSize = 5
        callGroupSize = 10
        shutdownGracePeriod = 2000
        shutdownTimeout = 3000

        connector {
            port = 8080
        }
    }) {
        install(CORS) {
            allowHost("*")
            allowHeader(HttpHeaders.ContentType)
        }

        extensions()
    }

    val finished = CompletableDeferred<Boolean>()

    server.addShutdownHook {
        finished.complete(true)
    }

    finished.await()
}