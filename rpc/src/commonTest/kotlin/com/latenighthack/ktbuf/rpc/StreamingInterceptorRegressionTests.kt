package com.latenighthack.ktbuf.rpc
import com.latenighthack.ktbuf.net.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
class StreamingInterceptorRegressionTests {
    @Test fun legacyTransportsRejectUnsupportedHeadersInsteadOfDroppingCredentials() = runTest {
        var invoked = false
        val legacy = object : RpcClient {
            override suspend fun unaryCall(method: RpcMethodSpecifier, headers: Map<String, String>, request: ByteArray) = error("unused")
            override suspend fun serverStreamingCall(method: RpcMethodSpecifier, block: suspend RpcServerStream.() -> Unit, readyCallback: () -> Unit) { invoked = true }
        }
        val failure = assertFailsWith<RpcResponseException> { legacy.serverStreamingCall(RpcMethodSpecifier("p", "S", "M"), mapOf("Authorization" to "token"), {}, {}) }
        assertEquals(com.latenighthack.ktbuf.proto.Codes.UNIMPLEMENTED, failure.code)
        assertFalse(invoked)
    }
    @Test fun openInterceptorsRunAndTheirMethodChangesReachTheTransport() = runTest {
        var opens=0
        var actual:RpcMethodSpecifier?=null
        val transport=object:RpcClient {
            override suspend fun unaryCall(method:RpcMethodSpecifier,headers:Map<String,String>,request:ByteArray)=error("unused")
            override suspend fun serverStreamingCall(method:RpcMethodSpecifier,block:suspend RpcServerStream.()->Unit,readyCallback:()->Unit) {actual=method}
        }
        val interceptor=object:StreamingRpcInterceptor {
            override suspend fun interceptOpen(method:RpcMethodSpecifier,headers:Map<String,String>,next:suspend (RpcMethodSpecifier,Map<String,String>)->Unit) {opens++;next(method.copy(methodName="Changed"),headers)}
            override suspend fun interceptSend(method:RpcMethodSpecifier,request:ByteArray,next:suspend (RpcMethodSpecifier,ByteArray)->Unit)=next(method,request)
            override suspend fun interceptReceive(method:RpcMethodSpecifier,next:suspend (RpcMethodSpecifier)->ByteArray)=next(method)
        }
        InterceptingRpcClient(transport,listOf(interceptor)).serverStreamingCall(RpcMethodSpecifier("p","S","M"),{},{})
        assertEquals(1,opens); assertEquals("Changed",actual!!.methodName)
    }
}
