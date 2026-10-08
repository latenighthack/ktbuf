package com.latenighthack.ktbuf.patch
import com.latenighthack.ktbuf.patch.v1.*
import kotlin.test.*
class RequireRegressionTests {
    private val path=Path(listOf(Path.Component(1,0)))
    @Test fun requirePreservesExistingMessagesAndCreatesMissingMessages() {
        val existing=RootMessage(inner=RootMessage.InnerMessage(str="keep",anInt=7),str="root")
        assertContentEquals(existing.toByteArray(),existing.toByteArray().applyChange(Change.Require(path)))
        val empty=RootMessage(str="root")
        assertEquals(empty.copy(inner=RootMessage.InnerMessage()),RootMessage.fromByteArray(empty.toByteArray().applyChange(Change.Require(path))))
    }
    @Test fun nestedEditorsPreserveSiblingFields() {
        val existing=RootMessage(inner=RootMessage.InnerMessage(str="keep",anInt=7,innerInner=RootMessage.InnerInnerMessage(str="deep")),str="root")
        val result=RootMessage.fromByteArray(existing.toByteArray().applyChanges(existing.edit { inner { innerInner { anInt=9 } } }.changes))
        assertEquals(existing.copy(inner=existing.inner!!.copy(innerInner=existing.inner!!.innerInner!!.copy(anInt=9))),result)
    }
    @Test fun createDefaultUsesTheExactScopedPath() {
        val scoped=EditContext().createScopedContext(1).createScopedContext(3)
        scoped.createDefault()
        assertEquals<List<Change>>(listOf(Change.Require(scoped.pathPrefix)),scoped.changes)
    }
}
