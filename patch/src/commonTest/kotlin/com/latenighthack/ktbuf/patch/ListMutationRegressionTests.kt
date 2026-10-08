package com.latenighthack.ktbuf.patch
import com.latenighthack.ktbuf.patch.v1.*
import kotlin.test.*

class ListMutationRegressionTests {
    private fun item(n:Int)=RootMessage.RepeatedMessage(repeatedInt=n)
    @Test fun eachMutationReplaysToTheEditorsExactList() {
        val original=RootMessage(str="keep",repeatMe=listOf(item(1),item(1),item(2)))
        val context=EditContext()
        val editor=RepeatMeListEditor(context,original.repeatMe)
        fun verify() {
            val replay=RootMessage.fromByteArray(original.toByteArray().applyChanges(context.changes))
            assertEquals(editor.toList(),replay.repeatMe); assertEquals("keep",replay.str)
        }
        editor[1]=item(3); verify()
        editor.remove(item(1)); verify()
        editor.add(0,item(5)); verify()
        editor.removeAt(1); verify()
        editor.addDistinct(item(6)); verify()
        val before=context.changes.size
        editor.addDistinct(item(6)); verify(); assertEquals(before,context.changes.size)
        editor.addAll(listOf(item(7),item(8))); verify()
        editor.clear(); verify()
    }
    @Test fun removingOneDuplicateRemovesExactlyOneOccurrence() {
        val original=RootMessage(repeatMe=listOf(item(1),item(1),item(2)))
        val context=EditContext(); val editor=RepeatMeListEditor(context,original.repeatMe)
        assertTrue(editor.remove(item(1)))
        assertEquals(editor.toList(),RootMessage.fromByteArray(original.toByteArray().applyChanges(context.changes)).repeatMe)
    }
    @Test fun invalidIndicesNeverRecordOrMutateAnything() {
        val context=EditContext(); val editor=RepeatMeListEditor(context,listOf(item(1)))
        assertFailsWith<IndexOutOfBoundsException> {editor.removeAt(3)}
        assertFailsWith<IndexOutOfBoundsException> {editor[3]=item(2)}
        assertFailsWith<IndexOutOfBoundsException> {editor.add(-1,item(2))}
        assertFailsWith<IndexOutOfBoundsException> {editor.add(3,item(2))}
        assertEquals(emptyList(),context.changes); assertEquals(listOf(item(1)),editor.toList())
    }
    @Test fun removeAllAndInsertDistinctHaveDefinedReplaySemantics() {
        val original=RootMessage(str="keep",repeatMe=listOf(item(1),item(1),item(2)))
        val path=Path(listOf(Path.Component(2,0)))
        assertEquals(original.copy(repeatMe=emptyList()),RootMessage.fromByteArray(original.toByteArray().applyChange(Change.RemoveAll(path))))
        assertEquals(original,RootMessage.fromByteArray(original.toByteArray().applyChange(Change.InsertDistinct(path,item(1).toByteArray(),null,null))))
        val append=Change.InsertDistinct(Path(listOf(Path.Component(2,3))),item(3).toByteArray(),null,null)
        assertEquals(original.copy(repeatMe=original.repeatMe+item(3)),RootMessage.fromByteArray(original.toByteArray().applyChange(append)))
    }
}
