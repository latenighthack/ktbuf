package com.latenighthack.ktbuf.consumer

import com.latenighthack.ktbuf.conformance.v1.*
import com.latenighthack.ktbuf.rpc.HttpRpcClient

class Consumer {
    val service = ConformanceServiceRpc(HttpRpcClient("https://example.com"))
    fun roundTrip(value: AllTypes) = AllTypes.fromByteArray(value.toByteArray())
}
