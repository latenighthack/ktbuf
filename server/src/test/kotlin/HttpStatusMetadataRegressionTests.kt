import com.latenighthack.ktbuf.conformance.v1.*
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.*
import com.latenighthack.ktbuf.rpc.HttpRpcClient
import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktbuf.test.server.runTestWithServer
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlin.test.*

class HttpStatusMetadataRegressionTests {
    private suspend fun <T> network(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(5000) { block() } }
    @Test fun everyRpcStatusSurvivesAnOpaqueHttpErrorBody() = runTestWithServer({ routing {
        serveAll(object : ConformanceServer by ConformanceTestServer() {
            override suspend fun unary(context: GrpcRequestContext, request: AllTypes): AllTypes =
                throw RpcResponseException("", "POST", Codes.from(request.fInt32), "opaque error with no status name")
        }, ConformanceServer.Descriptor)
    } }) { server, _ ->
        val client = HttpRpcClient(server.serverUrl)
        try {
            val rpc = ConformanceServiceRpc(client)
            val http = PlatformHttpClient()
            val descriptor = ConformanceServer.Descriptor
            val base = if (server.serverUrl.contains("://")) server.serverUrl else "http://${server.serverUrl}"
            val url = "$base/api/${descriptor.packageName}.${descriptor.serviceName}/${descriptor.methods.first().methodName}"
            for (code in Codes.entries.filter { it != Codes.OK }) {
                val failure = assertFailsWith<RpcResponseException> { network { rpc.unary(AllTypes(fInt32 = code.value)) } }
                assertEquals(code, failure.code, "Exact $code must survive HTTP")
                assertEquals(code.retriable(), failure.retriable())
                val rawFailure = assertFailsWith<RpcResponseException> { network {
                    http.httpCall(url, "POST", AllTypes(fInt32 = code.value).toByteArray(), emptyMap())
                } }
                assertEquals(code, rawFailure.code, "Generic HTTP client must preserve $code")
            }
        } finally { client.closeAndJoin() }
    }
    @Test fun malformedAndConflictingMetadataCannotChangeOperationClassification() = runTestWithServer({ routing {
        val descriptor = ConformanceServer.Descriptor
        post("/api/${descriptor.packageName}.${descriptor.serviceName}/${descriptor.methods.first().methodName}") {
            val header = call.request.queryParameters["status"]!!
            val http = call.request.queryParameters["http"]!!.toInt()
            header.split('|').forEach { call.response.headers.append("gRpC-StAtUs", it) }
            call.respondText("", status = HttpStatusCode(http, "test"))
        }
    } }) { server, _ ->
        val client = HttpRpcClient(server.serverUrl)
        try {
            for ((http, header) in listOf(401 to "0", 200 to "3", 500 to "no", 400 to "999", 409 to "3", 400 to "-1", 400 to "3,9", 400 to "3|9", 200 to "0|0")) {
                val rpc = ConformanceServiceRpc(client) { _, _ -> mapOf("status" to header, "http" to http.toString()) }
                assertEquals(Codes.DATA_LOSS, assertFailsWith<RpcResponseException> { network { rpc.unary(AllTypes()) } }.code, "$http / $header")
            }
        } finally { client.closeAndJoin() }
    }
    @Test fun callerDeadlineRemainsCoroutineCancellation() = runTestWithServer({ routing {
        serveAll(object : ConformanceServer by ConformanceTestServer() {
            override suspend fun unary(context: GrpcRequestContext, request: AllTypes): AllTypes {
                delay(1000)
                return AllTypes()
            }
        }, ConformanceServer.Descriptor)
    } }) { server, _ ->
        val client = HttpRpcClient(server.serverUrl)
        try {
            val rpc = ConformanceServiceRpc(client)
            assertFailsWith<TimeoutCancellationException> { network { withTimeout(50) { rpc.unary(AllTypes()) } } }
        } finally { client.closeAndJoin() }
    }
    @Test fun legacyHttpResponsesKeepTheirExistingFallback() = runTestWithServer({ routing {
        val descriptor = ConformanceServer.Descriptor
        post("/api/${descriptor.packageName}.${descriptor.serviceName}/${descriptor.methods.first().methodName}") {
            val http = call.request.queryParameters["http"]!!.toInt()
            call.respondText(call.request.queryParameters["body"].orEmpty(), status = HttpStatusCode(http, "legacy"))
        }
    } }) { server, _ ->
        val client = HttpRpcClient(server.serverUrl)
        try {
            for ((http, body, expected) in listOf(Triple(400, "FailedPrecondition: old server", Codes.FAILED_PRECONDITION),
                Triple(400, "OutOfRange: old server", Codes.OUT_OF_RANGE), Triple(409, "AlreadyExists", Codes.ALREADY_EXISTS),
                Triple(500, "DataLoss", Codes.DATA_LOSS), Triple(503, "down", Codes.UNAVAILABLE))) {
                val rpc = ConformanceServiceRpc(client) { _, _ -> mapOf("http" to http.toString(), "body" to body) }
                assertEquals(expected, assertFailsWith<RpcResponseException> { network { rpc.unary(AllTypes()) } }.code)
            }
        } finally { client.closeAndJoin() }
    }
}
