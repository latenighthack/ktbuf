package com.latenighthack.ktbuf.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.logging.*
import kotlinx.coroutines.*

suspend fun defaultServer(config: ApplicationEngine.Configuration.() -> Unit = {}, extensions: suspend Application.() -> Unit = {}): EmbeddedServer<*, *> {
    val setup = CompletableDeferred<Unit>()
    val server = embeddedServer(
        CIO,
        environment = applicationEnvironment { log = KtorSimpleLogger("server") },
        configure = {
            config()
        }
    ) {
        install(WebSockets) { maxFrameSize = com.latenighthack.ktbuf.ProtobufLimits().maxMessageBytes.toLong() }

        launch {
            try { extensions(); setup.complete(Unit) }
            catch (cause: Throwable) { setup.completeExceptionally(cause) }
        }.invokeOnCompletion { cause -> if (cause != null) setup.completeExceptionally(cause) }
    }

    val ready = CompletableDeferred<Boolean>()

    server.monitor.subscribe(ServerReady) {
        ready.complete(true)
    }
    server.monitor.subscribe(ApplicationStopped) {
        ready.completeExceptionally(IllegalStateException("Stopping"))
    }

    try {
        server.start()
        ready.await()
        setup.await()
        return server
    } catch (cause: Throwable) {
        withContext(NonCancellable) { runCatching { server.stop() } }
        throw cause
    }
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