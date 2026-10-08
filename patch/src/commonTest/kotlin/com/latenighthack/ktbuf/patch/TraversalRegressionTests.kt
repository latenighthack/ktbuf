package com.latenighthack.ktbuf.patch

import com.latenighthack.ktbuf.ProtobufOutputStream
import com.latenighthack.ktbuf.patch.v1.*
import kotlin.test.*

class TraversalRegressionTests {
    @Test fun missingContainerDoesNotReadUnrelatedParentFields() {
        val root=RootMessage(anInt=12,str="keep")
        val change=Change.Replace(Path(listOf(Path.Component(1,0),Path.Component(2,0))),ProtobufOutputStream.encode { encode(8,2) },emptyList())
        assertEquals(root.copy(inner=RootMessage.InnerMessage(anInt=8)),RootMessage.fromByteArray(root.toByteArray().applyChange(change)))
    }
    @Test fun higherIndicesMakeProgressAndPreserveOtherOccurrences() {
        val root=RootMessage(repeatMe=(1..4).map { RootMessage.RepeatedMessage(it) },str="keep")
        val result=RootMessage.fromByteArray(root.toByteArray().applyChange(Change.Remove(Path(listOf(Path.Component(2,2))))))
        assertEquals(root.copy(repeatMe=listOf(root.repeatMe[0],root.repeatMe[1],root.repeatMe[3])),result)
        assertFailsWith<IllegalArgumentException> { root.toByteArray().applyChange(Change.Remove(Path(listOf(Path.Component(2,20))))) }
    }
    @Test fun pathsAreCheckedAndEmptyMessagesAreRecognized() {
        for(path in listOf(Path(emptyList()),Path(listOf(Path.Component(0,0))),Path(listOf(Path.Component(1,-1))),Path(List(101) { Path.Component(1,0) }))) {
            assertFailsWith<IllegalArgumentException> { byteArrayOf().applyChange(Change.Remove(path)) }
        }
        val wire=ProtobufOutputStream.encode { encode(1) {}; encode(7,3) }
        val changed=wire.applyChange(Change.Replace(Path(listOf(Path.Component(1,0))),ProtobufOutputStream.encode { encode(1) {} },emptyList()))
        assertContentEquals(wire,changed)
    }
}
