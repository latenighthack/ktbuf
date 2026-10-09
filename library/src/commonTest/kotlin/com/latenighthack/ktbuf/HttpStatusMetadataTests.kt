package com.latenighthack.ktbuf

import com.latenighthack.ktbuf.proto.*
import kotlin.test.*

class HttpStatusMetadataTests {
    @Test fun exactMetadataRoundTripsEveryCodeAndRetryPolicy() {
        for (code in Codes.entries) {
            val received = Status.fromHTTPCode(code.toHttpCode(), "opaque", code.value.toString()).code
            assertEquals(code, received)
            assertEquals(code.retriable(), received.retriable())
        }
    }
    @Test fun invalidConflictingAndDuplicateMetadataIsPermanentProtocolFailure() {
        for ((http, header) in listOf(401 to "0", 200 to "3", 400 to "999", 400 to "3,9", 400 to "3,3",
            500 to "", 400 to "-1", 400 to "+3", 400 to " 3", 400 to "bad", 400 to "2147483648", 409 to "3")) {
            val received = Status.fromHTTPCode(http, "opaque", header)
            assertEquals(Codes.DATA_LOSS, received.code, "$http / $header")
            assertFalse(received.code.retriable())
        }
        assertEquals("9", mapOf("GrPc-StAtUs" to "9").rpcHeader(RPC_STATUS_HEADER))
        val duplicate = mapOf("grpc-status" to "3", "GRPC-STATUS" to "9").rpcHeader(RPC_STATUS_HEADER)
        assertEquals(Codes.DATA_LOSS, Status.fromHTTPCode(400, "", duplicate).code)
        assertNull(emptyMap<String, String>().rpcHeader(RPC_STATUS_HEADER))
        assertEquals("", mapOf(RPC_STATUS_HEADER to "").rpcHeader(RPC_STATUS_HEADER))
    }
    @Test fun absencePreservesTheLegacyStatusAndMessageContract() {
        for (http in listOf(200, 400, 401, 403, 404, 408, 409, 429, 499, 500, 501, 503, 504)) {
            for (message in listOf(null, "opaque", "FailedPrecondition", "OutOfRange", "AlreadyExists", "DataLoss")) {
                assertEquals(Status.fromHTTPCode(http, message), Status.fromHTTPCode(http, message, null))
            }
        }
    }
}
