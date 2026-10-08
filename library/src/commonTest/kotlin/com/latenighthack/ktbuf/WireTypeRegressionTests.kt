package com.latenighthack.ktbuf
import com.latenighthack.ktbuf.bytes.mutableLinkedByteArrayOf
import kotlin.test.*
class WireTypeRegressionTests {
    @Test fun tagsExposeTheirWireTypeIncludingUnknownGroups() {
        val bytes="0801150000000019000000000000000022000b0c".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val reader=ScopedProtobufReader(mutableLinkedByteArrayOf(*bytes))
        val types=mutableListOf<UInt>()
        while(reader.nextField()) { types.add(reader.currentWireType); reader.skipField() }
        assertEquals(listOf(0U,5U,1U,2U,3U),types)
    }
}
