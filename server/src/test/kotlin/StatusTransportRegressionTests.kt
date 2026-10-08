import com.latenighthack.ktbuf.conformance.v1.*
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.rpc.HttpRpcClient
import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktbuf.test.server.TestServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class StatusTransportRegressionTests {
    @Test fun everyErrorCodeSurvivesHttpAndWebSocketWithoutBodyMarkers() = runBlocking<Unit> {
        val reason = "雪".repeat(100)
        val server=TestServer()
        server.start { routing { serveAll(object:ConformanceServer by ConformanceTestServer() {
            override suspend fun unary(context:GrpcRequestContext,request:AllTypes):AllTypes = throw RpcResponseException("","POST",Codes.entries.first {it.value==request.fInt32},reason)
            override fun serverStream(context:GrpcRequestContext,request:AllTypes):Flow<StreamControlEvent<AllTypes>> = flow { throw RpcResponseException("","WS",Codes.entries.first {it.value==request.fInt32},reason) }
        },ConformanceServer.Descriptor) } }
        try {
            val client=ConformanceServiceRpc(HttpRpcClient(server.serverUrl))
            for(code in Codes.entries.filter {it!=Codes.OK}) {
                val unary=assertFailsWith<RpcResponseException> { withTimeout(2000) { client.unary(AllTypes(fInt32=code.value)) } }
                assertEquals(code,unary.code,"HTTP $code")
                val stream=assertFailsWith<RpcResponseException> { withTimeout(2000) { client.serverStream(AllTypes(fInt32=code.value)).toList() } }
                assertEquals(code,stream.code,"WS $code")
                assertTrue(stream.errorMessage.encodeToByteArray().size<=123)
                assertTrue(stream.errorMessage.all {it=='雪'})
            }
        } finally { server.stop() }
    }
}
