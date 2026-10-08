package com.latenighthack.ktbuf.patch
import com.latenighthack.ktbuf.ProtobufOutputStream
import com.latenighthack.ktbuf.patch.v1.*
import kotlin.test.*
class PatchSelectorRegressionTests {
    @Test fun distinctRemovalTouchesOnlyTheIndexedRepeatedContainer() {
        val target=byteArrayOf(8,1)
        val input=ProtobufOutputStream.encode { repeat(2) { encode(10) { encode(1) { encodeRaw(target) } } } }
        val expected=ProtobufOutputStream.encode { encode(10) { encode(1) { encodeRaw(target) } }; encode(10) {} }
        val path=Path(listOf(Path.Component(10,1),Path.Component(1,0)))
        assertContentEquals(expected,input.applyChange(Change.RemoveDistinct(path,target,null,null)))
    }
    @Test fun unsupportedMatchSelectorsFailExplicitlyBeforeChangingData() {
        val original=RootMessage(repeatMe=listOf(RootMessage.RepeatedMessage(repeatedInt=1))).toByteArray()
        val path=Path(listOf(Path.Component(2,0)))
        for(change in listOf(
            Change.RemoveDistinct(path,byteArrayOf(),Path(listOf(Path.Component(1,0))),byteArrayOf()),
            Change.RemoveDistinct(path,byteArrayOf(),null,byteArrayOf()),
            Change.InsertDistinct(path,byteArrayOf(),Path(listOf(Path.Component(1,0))),byteArrayOf())
        )) assertFailsWith<IllegalArgumentException> { original.applyChange(change) }
    }
    @Test fun protectedFieldsSurviveNestedReplacement() {
        val original=RootMessage(str="root",inner=RootMessage.InnerMessage(str="keep",anInt=7))
        val replacement=RootMessage(inner=RootMessage.InnerMessage(str="replace",anInt=9)).toByteArray()
        val change=Change.Replace(Path(listOf(Path.Component(1,0))),replacement,listOf(1))
        assertEquals(original.copy(inner=RootMessage.InnerMessage(str="keep",anInt=9)),RootMessage.fromByteArray(original.toByteArray().applyChange(change)))
    }
}
