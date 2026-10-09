package com.latenighthack.ktbuf.rpc
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.Codes
import kotlinx.coroutines.test.runTest
import kotlin.test.*
class UnsupportedTransportRegressionTests {
    @Test fun unsupportedLinuxTransportsFailImmediatelyAndNeverPretendToRunStreams() = runTest {
        val client=HttpRpcClient("http://localhost")
        val method=RpcMethodSpecifier("p","S","M")
        val unary=assertFailsWith<RpcResponseException> {client.unaryCall(method,emptyMap(),byteArrayOf())}
        assertEquals(Codes.UNIMPLEMENTED,unary.code)
        val http=assertFailsWith<RpcResponseException> {PlatformHttpClient().httpCall("http://localhost","GET",byteArrayOf(),emptyMap())}
        assertEquals(Codes.UNIMPLEMENTED,http.code)
        var block=false;var ready=false
        val stream=assertFailsWith<RpcResponseException> {client.serverStreamingCall(method,{block=true},{ready=true})}
        assertEquals(Codes.UNIMPLEMENTED,stream.code);assertFalse(block);assertFalse(ready)
    }
}
