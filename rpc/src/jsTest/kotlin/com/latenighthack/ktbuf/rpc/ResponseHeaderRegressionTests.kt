package com.latenighthack.ktbuf.rpc
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.rpcHeader
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
internal fun testPeerUrl(): String {
    val value = if (isNode) js("process.env.KTBUF_PEER_URL") else js("window.__karma__.config.originalArgs[0]")
    return value as? String ?: error("Missing peer URL: " + if (isNode) "Node environment" else js("JSON.stringify(window.__karma__.config)"))
}
class ResponseHeaderRegressionTests {
    @Test fun rawHeaderParsingUsesTheFirstColonAndCanonicalRepeatedKeys() {
        val values = parseResponseHeaders("X-Test: a:b:c\r\n x-test: second \r\nX-Empty: \r\n\r\ninvalid\r\n")
        assertEquals(mapOf("x-test" to "a:b:c, second", "x-empty" to ""), values)
        assertEquals("a:b:c, second", values.rpcHeader("X-TEST"))
    }
    @Test fun realXhrHeadersKeepColonsRepeatedValuesAndCaseInsensitiveLookup() = runTest(timeout=8.seconds) {
        if (isNode) return@runTest
        withContext(Dispatchers.Default) {
            val response=HttpRpcClient(testPeerUrl()).unaryCall(RpcMethodSpecifier("peer","Transport","Headers"),emptyMap(),byteArrayOf(1))
            assertContentEquals(byteArrayOf(1),response.data)
            assertEquals("value:with:colons",response.headers.rpcHeader("X-COLON"))
            assertEquals("first, second",response.headers.rpcHeader("x-repeat"))
            assertEquals("",response.headers.rpcHeader("x-empty"));assertFalse(response.headers.containsKey(""))
        }
    }
}
