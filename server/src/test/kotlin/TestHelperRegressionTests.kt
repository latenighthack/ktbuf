import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.server.defaultServer
import com.latenighthack.ktbuf.test.rpc.*
import com.latenighthack.ktbuf.test.server.*
import io.ktor.server.engine.connector
import kotlinx.coroutines.*
import java.net.Socket
import java.net.ServerSocket
import kotlin.test.*

class TestHelperRegressionTests {
    private val method=RpcMethodSpecifier("p","S","M")
    @Test fun errorOnlyTypedResultsPreserveErrorsAndDiscardFlags() = runBlocking<Unit> {
        val expected=IllegalStateException("expected")
        val handler=object:TypedUnaryHandler<Int,Int>({1},{byteArrayOf(it.toByte())}) {
            override suspend fun handleTypedRequest(method:RpcMethodSpecifier,headers:Map<String,String>,request:Int)=UnaryHandlerResponse<Int>(null,expected,true)
        }
        val result=assertNotNull(handler.handleRequest(method,emptyMap(),byteArrayOf()))
        assertSame(expected,result.error); assertTrue(result.discardHandler); assertNull(result.response)
    }
    @Test fun matchingHappensBeforeParsingAndIgnoresAdditionalRoutingParameters() = runBlocking<Unit> {
        var parses=0
        val handler=object:MatchingTypedUnaryHandler<Int,Int>(method,{parses++;1},{byteArrayOf(it.toByte())}) {
            override suspend fun handleTypedRequest(headers:Map<String,String>,request:Int)=UnaryHandlerResponse(request,null)
        }
        assertNull(handler.handleRequest(method.copy(methodName="Other"),emptyMap(),byteArrayOf(-128)))
        assertEquals(0,parses)
        assertNotNull(handler.handleRequest(method.copy(additionalParameters=mapOf("tenant" to "x")),emptyMap(),byteArrayOf()))
        assertEquals(1,parses)
    }
    @Test fun unsupportedStreamingStubFailsInsteadOfSucceedingSilently() = runBlocking<Unit> {
        val failure=assertFailsWith<RpcResponseException> { StubRpcClient().serverStreamingCall(method,{error("unexpected")},{}) }
        assertEquals(Codes.UNIMPLEMENTED,failure.code)
    }
    @Test fun aFailedRunnerAlwaysStopsItsServer() {
        var active:TestServer?=null
        var port=0
        try {
            assertFailsWith<IllegalStateException> { runTestWithServer<Unit> { server,_ -> active=server; port=server.port; error("runner failed") } }
            assertFailsWith<java.net.ConnectException> { Socket("127.0.0.1",port).close() }
        } finally { runBlocking { active?.stop() } }
    }
    @Test fun failedSetupSurfacesItsOriginalFailureAndReleasesItsPort() = runBlocking<Unit> {
        val port=ServerSocket(0).use {it.localPort}
        val failure=runCatching { withTimeout(2000) { defaultServer({ connector { this.port=port } }) { error("setup failed") } } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException,"setup failure was replaced by $failure")
        assertEquals("setup failed",failure.message)
        assertFailsWith<java.net.ConnectException> { Socket("127.0.0.1",port).close() }
    }
}
