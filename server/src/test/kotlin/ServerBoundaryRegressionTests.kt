import com.latenighthack.ktbuf.conformance.v1.*
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.rpc.HttpRpcClient
import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktbuf.test.server.TestServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ServerBoundaryRegressionTests {
    @Test fun unexpectedHandlerErrorsNeverExposePrivateDetailsOnAnyRpcShape() = runBlocking<Unit> {
        val secret="private credential: do-not-expose"
        val server=TestServer()
        server.start { routing { serveAll(object:ConformanceServer {
            override suspend fun unary(context:GrpcRequestContext,request:AllTypes):AllTypes=error(secret)
            override fun serverStream(context:GrpcRequestContext,request:AllTypes):Flow<StreamControlEvent<AllTypes>> = flow { error(secret) }
            override fun bidi(context:GrpcRequestContext,request:Flow<AllTypes>):Flow<StreamControlEvent<AllTypes>> = flow { error(secret) }
            override suspend fun clientStream(context:GrpcRequestContext,request:Flow<AllTypes>):AllTypes=error(secret)
        },ConformanceServer.Descriptor) } }
        try {
            val client=ConformanceServiceRpc(HttpRpcClient(server.serverUrl))
            val calls:List<suspend ()->Unit> = listOf({client.unary(AllTypes()); Unit},{client.serverStream(AllTypes()).toList();Unit},{client.bidi(flowOf(AllTypes())).toList();Unit},{client.clientStream(flowOf(AllTypes()));Unit})
            for((index,call) in calls.withIndex()) {
                val failure=assertFailsWith<RpcResponseException> { withTimeout(2000) { call() } }
                assertEquals(Codes.INTERNAL,failure.code,"RPC shape $index: ${failure.errorMessage}"); assertFalse(failure.errorMessage.contains(secret)); assertFalse(failure.errorMessage.contains("IllegalStateException"))
            }
        } finally { server.stop() }
    }
    @Test fun malformedInputMapsToAnInputErrorAfterAuthentication() = runBlocking<Unit> {
        val authenticated=AtomicInteger(); val handled=AtomicInteger()
        val server=TestServer()
        server.start { routing { serveAll(object:ConformanceServer by ConformanceTestServer() {
            override suspend fun unary(context:GrpcRequestContext,request:AllTypes):AllTypes { handled.incrementAndGet(); return request }
        },ConformanceServer.Descriptor) { authenticated.incrementAndGet(); it } } }
        try {
            val error=assertFailsWith<RpcResponseException> { withTimeout(2000) { HttpRpcClient(server.serverUrl).unaryCall(RpcMethodSpecifier("conformance.v1","Conformance","Unary"),emptyMap(),byteArrayOf(8,-128)) } }
            assertEquals(Codes.INVALID_ARGUMENT,error.code); assertEquals(1,authenticated.get()); assertEquals(0,handled.get())
        } finally { server.stop() }
    }
    @Test fun cancellationIsRethrownInsteadOfBecomingAnInternalResponse() = runBlocking<Unit> {
        val server=TestServer()
        server.start { routing { serveAll(object:ConformanceServer by ConformanceTestServer() {
            override fun serverStream(context:GrpcRequestContext,request:AllTypes):Flow<StreamControlEvent<AllTypes>> = flow { throw CancellationException("cancel handler") }
        },ConformanceServer.Descriptor) } }
        try {
            val failure=assertFailsWith<RpcResponseException> { withTimeout(2000) { ConformanceServiceRpc(HttpRpcClient(server.serverUrl)).serverStream(AllTypes()).toList() } }
            assertEquals(Codes.UNAVAILABLE,failure.code)
        } finally { server.stop() }
    }
}
