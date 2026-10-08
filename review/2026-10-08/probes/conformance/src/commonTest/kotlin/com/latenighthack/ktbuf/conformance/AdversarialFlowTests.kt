package com.latenighthack.ktbuf.conformance
import com.latenighthack.ktbuf.conformance.v1.*
import com.latenighthack.ktbuf.net.*
import com.latenighthack.ktbuf.proto.Codes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AdversarialFlowTests {
    @Test fun queryParameterValuesAreEscaped() {
        val path=RpcMethodSpecifier("p","s","m",mapOf("token" to "a&admin=true#fragment")).toPath("https://example.test")
        assertEquals("https://example.test/api/p.s/m?token=a%26admin%3Dtrue%23fragment",path)
    }
    @Test fun gatewayPathRetainsAdditionalParameters() {
        assertTrue(RpcMethodSpecifier("p","s","m",mapOf("token" to "secret")).toApiGatewayPath("https://example.test").contains("token=secret"))
    }
    @Test fun localStreamHonorsCloseEvent() = runTest {
        val server=object:ConformanceServer {
            override suspend fun unary(context:GrpcRequestContext,request:AllTypes)=request
            override fun serverStream(context:GrpcRequestContext,request:AllTypes):Flow<StreamControlEvent<AllTypes>> = flow {
                emit(StreamControlEvent.Close()); emit(StreamControlEvent.Message(AllTypes(fInt32=7)))
            }
            override fun bidi(context:GrpcRequestContext,request:Flow<AllTypes>):Flow<StreamControlEvent<AllTypes>> = emptyFlow()
        }
        assertEquals(emptyList(),LocalConformanceServiceRpc(server).serverStream(AllTypes()).toList())
    }
    @Test fun finiteBidiRequestMustDrainAllResponses() = runTest {
        val rpc=FakeRpcClient(echo={it})
        assertEquals(listOf(1,2,3), ConformanceServiceRpc(rpc).bidi(flowOf(AllTypes(fInt32=1),AllTypes(fInt32=2),AllTypes(fInt32=3))).toList().map { it.fInt32 })
    }
    @Test fun bidiMustPropagateReceiveErrors() = runTest {
        val rpc=object:RpcClient {
            override suspend fun unaryCall(method:RpcMethodSpecifier,headers:Map<String,String>,request:ByteArray)=error("unused")
            override suspend fun serverStreamingCall(method:RpcMethodSpecifier,block:suspend RpcServerStream.()->Unit,readyCallback:()->Unit) {
                object:RpcServerStream {
                    override suspend fun receive():ByteArray=throw RpcResponseException("","WS",Codes.PERMISSION_DENIED,"denied")
                    override suspend fun send(bytes:ByteArray) {}
                    override suspend fun closeOutbound() {}
                    override suspend fun closeInbound() {}
                }.block()
            }
        }
        val requests=flow { emit(AllTypes()); delay(100); }
        assertFailsWith<RpcResponseException> { ConformanceServiceRpc(rpc).bidi(requests).toList() }
    }
    @Test fun streamingOpenInterceptorMustRun() = runTest {
        var opened=false
        val interceptor=object:StreamingRpcInterceptor {
            override suspend fun interceptOpen(method:RpcMethodSpecifier,headers:Map<String,String>,next:suspend (RpcMethodSpecifier,Map<String,String>)->Unit) { opened=true; next(method,headers) }
            override suspend fun interceptSend(method:RpcMethodSpecifier,request:ByteArray,next:suspend (RpcMethodSpecifier,ByteArray)->Unit)=next(method,request)
            override suspend fun interceptReceive(method:RpcMethodSpecifier,next:suspend (RpcMethodSpecifier)->ByteArray)=next(method)
        }
        val rpc=InterceptingRpcClient(FakeRpcClient(),listOf(interceptor))
        rpc.serverStreamingCall(RpcMethodSpecifier("p","s","m"), {}, {})
        assertTrue(opened)
    }
}
