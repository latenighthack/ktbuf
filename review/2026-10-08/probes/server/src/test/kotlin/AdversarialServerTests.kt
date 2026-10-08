import com.latenighthack.ktbuf.conformance.v1.*
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.rpc.*
import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktbuf.test.server.TestServer
import com.latenighthack.ktbuf.test.rpc.*
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.*

class AdversarialServerTests {
    @Test fun unaryRunsAuthenticationContextProcessor() = runBlocking<Unit> {
        val checks=AtomicInteger()
        val s=TestServer()
        s.start { routing { serveAll(ConformanceTestServer(), ConformanceServer.Descriptor) {
            checks.incrementAndGet()
            throw RpcResponseException("","POST",Codes.UNAUTHENTICATED,"denied")
        } } }
        try {
            val result=runCatching { withTimeout(3000) { ConformanceServiceRpc(HttpRpcClient(s.serverUrl)).unary(AllTypes(fInt32=21)) } }
            assertEquals(1,checks.get(),"auth must run; actual result=$result")
            assertTrue(result.exceptionOrNull() is RpcResponseException)
        } finally { s.stop() }
    }
    @Test fun streamingFailureIsNotReportedAsSuccessfulEmptyStream() = runBlocking<Unit> {
        val s=TestServer()
        s.start { routing { serveAll(object:ConformanceServer by ConformanceTestServer() {
            override fun serverStream(context:GrpcRequestContext,request:AllTypes):Flow<StreamControlEvent<AllTypes>> = flow {
                throw RpcResponseException("","WS",Codes.PERMISSION_DENIED,"denied")
            }
        },ConformanceServer.Descriptor) } }
        try {
            assertFailsWith<RpcResponseException> { withTimeout(3000) { ConformanceServiceRpc(HttpRpcClient(s.serverUrl)).serverStream(AllTypes()).toList() } }
        } finally { s.stop() }
    }
    @Test fun cancellingTransportClosesRemoteSocket() = runBlocking<Unit> {
        val connected=CompletableDeferred<Unit>(); val closed=CompletableDeferred<Unit>(); val s=TestServer()
        s.start { routing { webSocket("/api/p.S/M") {
            connected.complete(Unit)
            try { incoming.receive() } finally { closed.complete(Unit) }
        } } }
        try {
            val job=launch(Dispatchers.Default) { HttpRpcClient(s.serverUrl).serverStreamingCall(RpcMethodSpecifier("p","S","M"), { receive() }, {}) }
            withTimeout(3000) { connected.await() }
            job.cancelAndJoin()
            assertTrue(withTimeoutOrNull(700) { closed.await(); true } ?: false, "cancelled call left socket open")
        } finally { s.stop() }
    }
    @Test fun truncatedHttpResponseFailsCallerRatherThanHanging() = runBlocking<Unit> {
        val listener=ServerSocket(0)
        val t=thread(isDaemon=true) {
            listener.accept().use { socket ->
                val input=socket.getInputStream(); var last4=""
                while(!last4.endsWith("\r\n\r\n")) { val x=input.read(); if(x<0) break; last4=(last4+x.toChar()).takeLast(4) }
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\nx".toByteArray())
            }
        }
        try {
            val error=runCatching { withTimeout(1000) { HttpRpcClient("127.0.0.1:${listener.localPort}").unaryCall(RpcMethodSpecifier("p","S","M"),emptyMap(),byteArrayOf()) } }.exceptionOrNull()
            assertTrue(error is java.io.IOException,"expected IOException, got $error")
        } finally { listener.close(); t.join(1000) }
    }
    @Test fun normalServerStreamCompletesAtRemoteEnd() = runBlocking<Unit> {
        val s=TestServer()
        s.start { routing { serveAll(ConformanceTestServer(frameCount=2),ConformanceServer.Descriptor) } }
        try {
            val frames=withTimeout(1500) { ConformanceServiceRpc(HttpRpcClient(s.serverUrl)).serverStream(AllTypes()).toList() }
            assertEquals(2,frames.size)
        } finally { s.stop() }
    }
    @Test fun ambiguousStatusCodesSurviveHttpTransport() = runBlocking<Unit> {
        val s=TestServer(); s.start { routing { serveAll(ConformanceTestServer(),ConformanceServer.Descriptor) } }
        try {
            for(code in listOf(Codes.FAILED_PRECONDITION,Codes.OUT_OF_RANGE,Codes.ALREADY_EXISTS,Codes.INTERNAL,Codes.DATA_LOSS)) {
                val e=runCatching { withTimeout(1500) { ConformanceServiceRpc(HttpRpcClient(s.serverUrl)).unary(AllTypes(fInt32=code.value,fBool=true)) } }.exceptionOrNull()
                assertTrue(e is RpcResponseException); assertEquals(code,e.code)
            }
        } finally { s.stop() }
    }
    @Test fun atomicUpdatesDoNotLoseConcurrentWrites() {
        val ref=AtomicReference(0)
        val gate=java.util.concurrent.CyclicBarrier(8)
        val threads=(1..8).map { thread { ref.getAndUpdate { gate.await(); this+1 } } }
        threads.forEach { it.join() }
        assertEquals(8,ref.value)
    }
    @Test fun retryLimitOneStillRunsInitialAttempt() = runBlocking<Unit> {
        var calls=0
        assertEquals(42,repeatWithBackoff(retryLimit=1) { calls++; 42 })
        assertEquals(1,calls)
    }
    @Test fun cancellationIsNeverRetried() = runBlocking<Unit> {
        var calls=0
        runCatching { repeatWithBackoff<Int>(retryLimit=5,backoff=0,minRetryTime=0,maxRetryTime=0) { calls++; throw CancellationException("cancelled") } }
        assertEquals(1,calls)
    }
    @Test fun flowRetryLimitIsHonored() = runBlocking<Unit> {
        var calls=0
        val source=flow<Int> { calls++; if(calls<6) throw IllegalStateException("failed"); emit(1) }
        runCatching { source.retryWithBackoff(retryLimit=0) { null }.toList() }
        assertEquals(1,calls)
    }
    @Test fun backoffHonorsTotalTimeLimit() = runBlocking<Unit> {
        val start=System.nanoTime()
        runCatching { repeatWithBackoff<Int>(retryLimit=6,timeLimit=5,backoff=10,minRetryTime=10,maxRetryTime=10) { delay(30); throw IllegalStateException("failed") } }
        assertTrue((System.nanoTime()-start)/1_000_000<100,"timeLimit=5ms exceeded 100ms")
    }
    @Test fun typedStubDoesNotDiscardErrorOnlyResult() = runBlocking<Unit> {
        val handler=object:TypedUnaryHandler<Int,Int>({1},{byteArrayOf(it.toByte())}) {
            override suspend fun handleTypedRequest(method:RpcMethodSpecifier,headers:Map<String,String>,request:Int)=UnaryHandlerResponse<Int>(null,IllegalStateException("expected"))
        }
        assertNotNull(handler.handleRequest(RpcMethodSpecifier("p","s","m"),emptyMap(),byteArrayOf()))
    }
}
