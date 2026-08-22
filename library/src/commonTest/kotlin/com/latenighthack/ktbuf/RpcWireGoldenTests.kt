package com.latenighthack.ktbuf

import com.latenighthack.ktbuf.net.RpcMethodSpecifier
import com.latenighthack.ktbuf.net.toApiGatewayPath
import com.latenighthack.ktbuf.net.toPath
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.proto.Status
import com.latenighthack.ktbuf.proto.fromHTTPCode
import com.latenighthack.ktbuf.proto.fromWSCode
import com.latenighthack.ktbuf.proto.toHttpCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The RPC routing and status-mapping layer is pure common code, so it can be pinned
// on every target. These are the contracts a server or a peer client sees: change a
// path shape or a status mapping and existing deployments break.
class RpcWireGoldenTests {
    @Test
    fun unaryPathsMatchGoldens() {
        val method = RpcMethodSpecifier("com.example.v1", "Greeter", "SayHello")

        assertEquals("https://host/api/com.example.v1.Greeter/SayHello", method.toPath("https://host"))
        assertEquals("/api/com.example.v1.Greeter/SayHello", method.toPath(""))
    }

    @Test
    fun queryParametersAreAppendedToUnaryPaths() {
        val single = RpcMethodSpecifier("pkg", "Svc", "M", mapOf("a" to "1"))

        assertEquals("/api/pkg.Svc/M?a=1", single.toPath(""))

        val multiple = RpcMethodSpecifier("pkg", "Svc", "M", mapOf("a" to "1", "b" to "2"))

        assertEquals("/api/pkg.Svc/M?a=1&b=2", multiple.toPath(""))
    }

    @Test
    fun apiGatewayPathsFlattenDotsToUnderscores() {
        // The websocket route form: dots in package/service become underscores so the
        // whole thing is a single path segment.
        val method = RpcMethodSpecifier("com.example.v1", "Greeter", "SayHello")

        assertEquals("wss://host/ws_com_example_v1_Greeter_SayHello", method.toApiGatewayPath("wss://host"))
        assertEquals("/ws_com_example_v1_Greeter_SayHello", method.toApiGatewayPath(""))
    }

    @Test
    fun apiGatewayPathIgnoresQueryParameters() {
        val method = RpcMethodSpecifier("pkg", "Svc", "M", mapOf("a" to "1"))

        assertEquals("/ws_pkg_Svc_M", method.toApiGatewayPath(""))
    }

    @Test
    fun httpCodesMapToGrpcCodes() {
        val cases = listOf(
            200 to Codes.OK,
            400 to Codes.INVALID_ARGUMENT,
            401 to Codes.UNAUTHENTICATED,
            403 to Codes.PERMISSION_DENIED,
            404 to Codes.NOT_FOUND,
            408 to Codes.CLIENT_TIMEOUT,
            409 to Codes.ABORTED,
            429 to Codes.RESOURCE_EXHAUSTED,
            499 to Codes.CANCELLED,
            500 to Codes.UNKNOWN,
            501 to Codes.UNIMPLEMENTED,
            503 to Codes.UNAVAILABLE,
            504 to Codes.DEADLINE_EXCEEDED,
            418 to Codes.UNKNOWN,
            0 to Codes.UNKNOWN
        )

        for ((http, expected) in cases) {
            assertEquals(expected, Status.fromHTTPCode(http, null).code, "HTTP $http")
        }
    }

    @Test
    fun ambiguousHttpCodesAreDisambiguatedByTheErrorBody() {
        // 400, 409 and 500 each cover several gRPC codes; the server encodes which one
        // it meant in the response body. This is the contract the error-body decoding
        // on JVM/JS/Apple exists to serve.
        assertEquals(Codes.FAILED_PRECONDITION, Status.fromHTTPCode(400, "FailedPrecondition: nope").code)
        assertEquals(Codes.OUT_OF_RANGE, Status.fromHTTPCode(400, "OutOfRange: nope").code)
        assertEquals(Codes.INVALID_ARGUMENT, Status.fromHTTPCode(400, "something else").code)

        assertEquals(Codes.ALREADY_EXISTS, Status.fromHTTPCode(409, "AlreadyExists: dup").code)
        assertEquals(Codes.ABORTED, Status.fromHTTPCode(409, "conflict").code)

        assertEquals(Codes.INTERNAL, Status.fromHTTPCode(500, "Internal: boom").code)
        assertEquals(Codes.DATA_LOSS, Status.fromHTTPCode(500, "DataLoss: gone").code)
        assertEquals(Codes.UNKNOWN, Status.fromHTTPCode(500, "huh").code)
    }

    @Test
    fun errorBodyMatchingIsCaseInsensitive() {
        // The body is lowercased before matching, so a server that spells the marker
        // differently still resolves to the same code.
        assertEquals(Codes.FAILED_PRECONDITION, Status.fromHTTPCode(400, "FAILEDPRECONDITION").code)
        assertEquals(Codes.FAILED_PRECONDITION, Status.fromHTTPCode(400, "failedprecondition").code)
        assertEquals(Codes.ALREADY_EXISTS, Status.fromHTTPCode(409, "ALREADYEXISTS").code)
        assertEquals(Codes.DATA_LOSS, Status.fromHTTPCode(500, "DATALOSS").code)
    }

    @Test
    fun statusMessageCarriesTheHttpCodeAndBody() {
        assertEquals("404 - not here", Status.fromHTTPCode(404, "not here").message)
        assertEquals("404 - ", Status.fromHTTPCode(404, null).message, "a null body yields an empty tail")
    }

    @Test
    fun grpcCodesMapBackToHttpCodes() {
        val cases = listOf(
            Codes.OK to 200,
            Codes.CANCELLED to 499,
            Codes.UNKNOWN to 500,
            Codes.INVALID_ARGUMENT to 400,
            Codes.DEADLINE_EXCEEDED to 504,
            Codes.NOT_FOUND to 404,
            Codes.ALREADY_EXISTS to 409,
            Codes.PERMISSION_DENIED to 403,
            Codes.RESOURCE_EXHAUSTED to 429,
            Codes.FAILED_PRECONDITION to 400,
            Codes.ABORTED to 409,
            Codes.OUT_OF_RANGE to 400,
            Codes.UNIMPLEMENTED to 501,
            Codes.INTERNAL to 500,
            Codes.UNAVAILABLE to 503,
            Codes.DATA_LOSS to 500,
            Codes.UNAUTHENTICATED to 401,
            Codes.CLIENT_TIMEOUT to 408
        )

        for ((code, expected) in cases) {
            assertEquals(expected, code.toHttpCode(), "$code -> HTTP")
        }

        assertEquals(Codes.entries.size, cases.size, "every Codes entry has an HTTP mapping pinned")
    }

    @Test
    fun httpRoundTripIsStableForUnambiguousCodes() {
        // Codes that own their HTTP status must survive server -> wire -> client intact.
        val unambiguous = listOf(
            Codes.OK, Codes.CANCELLED, Codes.NOT_FOUND, Codes.PERMISSION_DENIED,
            Codes.RESOURCE_EXHAUSTED, Codes.UNIMPLEMENTED, Codes.UNAVAILABLE,
            Codes.DEADLINE_EXCEEDED, Codes.UNAUTHENTICATED, Codes.CLIENT_TIMEOUT
        )

        for (code in unambiguous) {
            val recovered = Status.fromHTTPCode(code.toHttpCode(), null).code

            assertEquals(code, recovered, "$code should survive an HTTP round trip")
        }
    }

    @Test
    fun ambiguousCodesRoundTripOnlyWithAnErrorBody() {
        // These share an HTTP status, so the body marker is required to recover them.
        val ambiguous = listOf(
            Codes.FAILED_PRECONDITION to "FailedPrecondition",
            Codes.OUT_OF_RANGE to "OutOfRange",
            Codes.ALREADY_EXISTS to "AlreadyExists",
            Codes.INTERNAL to "Internal",
            Codes.DATA_LOSS to "DataLoss"
        )

        for ((code, marker) in ambiguous) {
            assertEquals(code, Status.fromHTTPCode(code.toHttpCode(), marker).code, "$code with marker")
        }

        // Without the marker they collapse to the status' default code.
        assertEquals(Codes.INVALID_ARGUMENT, Status.fromHTTPCode(Codes.FAILED_PRECONDITION.toHttpCode(), null).code)
        assertEquals(Codes.UNKNOWN, Status.fromHTTPCode(Codes.DATA_LOSS.toHttpCode(), null).code)
    }

    @Test
    fun webSocketCloseCodesMapToGrpcCodes() {
        assertEquals(Codes.OK, Status.fromWSCode(1000, null).code)
        assertEquals(Codes.UNAVAILABLE, Status.fromWSCode(1001, null).code)
        assertEquals(Codes.UNKNOWN, Status.fromWSCode(1006, null).code)

        // The 4000-4999 private range carries the gRPC code as an offset from 4000.
        for (code in Codes.entries.filter { it.value <= 16 }) {
            assertEquals(code, Status.fromWSCode(4000 + code.value, null).code, "ws 4000+${code.value}")
        }

        assertEquals(Codes.UNKNOWN, Status.fromWSCode(4099, null).code, "unmapped offset falls back")
    }

    @Test
    fun webSocketStatusCarriesTheRawMessage() {
        assertEquals("boom", Status.fromWSCode(4013, "boom").message)
        assertEquals("", Status.fromWSCode(1000, null).message)
    }

    @Test
    fun retriableCodesAreExactlyTheTransientOnes() {
        val retriable = setOf(
            Codes.UNKNOWN, Codes.CLIENT_TIMEOUT, Codes.DEADLINE_EXCEEDED,
            Codes.RESOURCE_EXHAUSTED, Codes.ABORTED, Codes.INTERNAL, Codes.UNAVAILABLE
        )

        for (code in Codes.entries) {
            if (code in retriable) {
                assertTrue(code.retriable(), "$code should be retriable")
            } else {
                assertFalse(code.retriable(), "$code should not be retriable")
            }
        }
    }

    @Test
    fun codesFromIntIsCurrentlyBroken() {
        // CHARACTERIZATION TEST -- this pins a bug, it does not endorse it.
        //
        // Codes.from() ignores the value entirely: anything <= 16 becomes ABORTED and
        // anything above becomes UNKNOWN, so it never returns the code it was handed.
        // It is live in HttpRpcClient.apple.kt (NSError codes) and HttpRpcClient.js.kt
        // (WebSocket close codes), where a normal 1000 close currently surfaces as
        // UNKNOWN and an arbitrary NSError surfaces as the retriable ABORTED.
        //
        // Fixing it is a behaviour change and deliberately out of scope here; this test
        // will fail the moment someone corrects it, which is the intent.
        assertEquals(Codes.ABORTED, Codes.from(0), "0 should map to OK but yields ABORTED")
        assertEquals(Codes.ABORTED, Codes.from(5), "5 should map to NOT_FOUND but yields ABORTED")
        assertEquals(Codes.ABORTED, Codes.from(16), "16 should map to UNAUTHENTICATED but yields ABORTED")
        assertEquals(Codes.UNKNOWN, Codes.from(17))
        assertEquals(Codes.UNKNOWN, Codes.from(1000), "a WebSocket 1000 close reads as UNKNOWN")
    }
}
