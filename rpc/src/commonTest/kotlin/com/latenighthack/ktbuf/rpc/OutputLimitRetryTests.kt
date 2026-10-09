package com.latenighthack.ktbuf.rpc

import com.latenighthack.ktbuf.*
import com.latenighthack.ktbuf.bytes.ByteArrayLimitException
import com.latenighthack.ktbuf.net.RpcResponseException
import com.latenighthack.ktbuf.proto.Codes
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlin.test.*

class OutputLimitRetryTests {
    @Test fun deterministicLocalEncodingLimitsAreNeverRetried() = runTest {
        for (failure in listOf(ProtobufOutputLimitException("output"), ProtobufLimitException("input"), ByteArrayLimitException("buffer"))) {
            var calls = 0
            val caught = assertFailsWith<IllegalArgumentException> { repeatWithBackoff(retryLimit = 3) { calls++; throw failure } }
            assertSame(failure, caught); assertEquals(1, calls)
        }
        assertTrue(BackoffContext.DEFAULT_EXCEPTION_HANDLER(RpcResponseException("", "POST", Codes.RESOURCE_EXHAUSTED, "temporary quota")))
    }
    @Test fun normalInboundCloseDrainsFramesThenReportsTypedEndOfStream() = runTest {
        val input = RpcInboundBuffer(8, 2)
        assertTrue(input.offer(byteArrayOf(1)))
        input.close()
        assertContentEquals(byteArrayOf(1), input.receive())
        assertFailsWith<ClosedReceiveChannelException> { input.receive() }
    }

}
