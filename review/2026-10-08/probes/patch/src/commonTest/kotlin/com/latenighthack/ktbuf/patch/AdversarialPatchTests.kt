package com.latenighthack.ktbuf.patch
import com.latenighthack.ktbuf.ProtobufOutputStream
import com.latenighthack.ktbuf.patch.v1.*
import kotlin.test.*

class AdversarialPatchTests {
    private fun path(vararg n:Int)=Path(n.map { Path.Component(it,0) })
    @Test fun requirePreservesExistingMessage() {
        val a=RootMessage(inner=RootMessage.InnerMessage(str="kept",anInt=7))
        assertEquals(a,RootMessage.fromByteArray(a.toByteArray().applyChange(Change.Require(path(1)))))
    }
    @Test fun requireCreatesMissingMessage() {
        assertNotNull(RootMessage.fromByteArray(byteArrayOf().applyChange(Change.Require(path(1)))).inner)
    }
    @Test fun nestedEditPreservesSiblingFields() {
        val a=RootMessage(inner=RootMessage.InnerMessage(str="kept",anInt=7))
        val b=RootMessage.fromByteArray(a.toByteArray().applyChanges(a.edit { inner { anInt=8 } }.changes))
        assertEquals("kept",b.inner?.str); assertEquals(8,b.inner?.anInt)
    }
    @Test fun addingNestedFieldPreservesUnrelatedRoot() {
        val a=RootMessage(anInt=12,str="kept")
        val b=RootMessage.fromByteArray(a.toByteArray().applyChanges(a.edit { inner { anInt=8 } }.changes))
        assertEquals(12,b.anInt); assertEquals("kept",b.str); assertEquals(8,b.inner?.anInt)
    }
    @Test fun indexedDistinctRemoveOnlyTouchesSelectedContainer() {
        val p=RootMessage.RepeatedMessage(1).toByteArray()
        val wire=ProtobufOutputStream.encode { encode(10) { encode(2) { encodeRaw(p) } }; encode(10) { encode(2) { encodeRaw(p) } } }
        val change=Change.RemoveDistinct(Path(listOf(Path.Component(10,1),Path.Component(2,0))),p,null,null)
        val expected=ProtobufOutputStream.encode { encode(10) { encode(2) { encodeRaw(p) } }; encode(10) {} }
        assertContentEquals(expected,wire.applyChange(change))
    }
    @Test fun listSetEmitsChange() {
        val c=EditContext(); val list=EditorList(c,2,{ byteArrayOf(this.toByte()) },listOf(1))
        list[0]=2
        assertEquals(1,c.changes.size)
    }
    @Test fun addDistinctDoesNotSilentlyDoNothing() {
        val c=EditContext(); val list=EditorList(c,2,{ byteArrayOf(this.toByte()) },listOf(1))
        list.addDistinct(2)
        assertEquals(listOf(1,2),list.toList())
    }
    @Test fun invalidIndexDoesNotRecordChange() {
        val c=EditContext(); val list=EditorList(c,2,{ byteArrayOf(this.toByte()) },listOf(1))
        assertFails { list.removeAt(100) }; assertEquals(0,c.changes.size)
    }
    @Test fun insertEqualityAgainstUnrelatedObjectIsFalse() { assertFalse(Change.Insert(path(1),byteArrayOf()).equals("x")) }
}
