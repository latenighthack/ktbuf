package com.latenighthack.ktbuf.patch

import com.latenighthack.ktbuf.patch.v1.*
import kotlin.test.Test
import kotlin.test.assertEquals

class PatchTests {
    @Test
    fun `patch overwrites an enum`() {
        val original = RootMessage(str = "testing", type = RootMessage.Type.B)
        val encodedOriginal = original.toByteArray()
        val patch = original.edit {
            type = RootMessage.Type.C
        }

        val edited = patch.value
        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals(RootMessage.Type.B, original.type)

        // the edit updates the value appropriately
        assertEquals(RootMessage.Type.C, edited.type)

        // the edit is otherwise the same
        assertEquals(edited, original.copy(type = edited.type))

        // deserializing the patch returns a message equal to edited
        assertEquals(edited, patched)
    }

    @Test
    fun `patch overwrites int value`() {
        val original = RootMessage(anInt = 10, str = "testing")
        val encodedOriginal = original.toByteArray()
        val patch = original.edit {
            anInt = 42
        }

        val edited = patch.value
        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals(10, original.anInt)

        // the edit updates the value appropriately
        assertEquals(42, edited.anInt)

        // the edit is otherwise the same
        assertEquals(edited, original.copy(anInt = edited.anInt))

        // deserializing the patch returns a message equal to edited
        assertEquals(edited, patched)
    }

    @Test
    fun `patch overwrites string value`() {
        val original = RootMessage(anInt = 10, str = "hello")
        val encodedOriginal = original.toByteArray()
        val patch = original.edit {
            str = "world"
        }

        val edited = patch.value
        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals("hello", original.str)
        assertEquals("world", edited.str)
        assertEquals(edited, original.copy(str = edited.str))
        assertEquals(edited, patched)
    }

    @Test
    fun `patch adds int value to empty message`() {
        val original = RootMessage()
        val encodedOriginal = byteArrayOf()
        val patch = original.edit {
            anInt = 42
        }

        val edited = patch.value
        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals(0, original.anInt)
        assertEquals(42, edited.anInt)
        assertEquals(edited, original.copy(anInt = edited.anInt))
        assertEquals(edited, patched)
    }

    @Test
    fun `patch adds int value to inner message`() {
        val original = RootMessage()
        val encodedOriginal = byteArrayOf()
        val patch = original.edit {
            inner {
                anInt = 9000
            }
        }

        val edited = patch.value
        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals(null, original.inner?.anInt)
        assertEquals(9000, edited.inner?.anInt)
        assertEquals(edited, original.copy(inner = RootMessage.InnerMessage(anInt = edited.inner?.anInt!!)))
        assertEquals(edited, patched)
    }

    @Test
    fun `patch adds int value to inner inner message`() {
        val original = RootMessage()
        val encodedOriginal = byteArrayOf()
        val patch = original.edit {
            inner {
                innerInner {
                    anInt = 9000
                }
            }
        }

        val edited = patch.value
        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals(null, original.inner?.innerInner?.anInt)
        assertEquals(9000, edited.inner?.innerInner?.anInt)
        assertEquals(edited, original.copy(inner = RootMessage.InnerMessage(innerInner = RootMessage.InnerInnerMessage(anInt = edited.inner?.innerInner?.anInt!!))))
        assertEquals(edited, patched)
    }

    @Test
    fun `patch adds repeated value`() {
        val original = RootMessage()
        val encodedOriginal = original.toByteArray()
        val patch = original.edit {
            repeatMe {
                add {
                    repeatedInt = 2
                }
            }
        }

        val edited = patch.value
        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals(listOf(2), edited.repeatMe.map { it.repeatedInt })
        assertEquals(edited, patched)
    }

    @Test
    fun `patch adds multiple repeated values`() {
        val original = RootMessage()
        val encodedOriginal = original.toByteArray()
        val patch = original.edit {
            repeatMe {
                add {
                    repeatedInt = 2
                }
                add {
                    repeatedInt = 3
                }
                add {
                    repeatedInt = 4
                }
            }
        }

        val edited = patch.value
        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals(listOf(2, 3, 4), edited.repeatMe.map { it.repeatedInt })
        assertEquals(edited, patched)
    }

    @Test
    fun `patch adds multiple repeated values to existing message`() {
        val original = RootMessage(anInt = 12, str = "testing")
        val encodedOriginal = original.toByteArray()
        val patch = original.edit {
            repeatMe {
                add {
                    repeatedInt = 2
                }
                add {
                    repeatedInt = 3
                }
                add {
                    repeatedInt = 4
                }
            }
        }

        val edited = patch.value
        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals("testing", edited.str)
        assertEquals(listOf(2, 3, 4), edited.repeatMe.map { it.repeatedInt })
        assertEquals(edited, patched)
    }

    @Test
    fun `patch removes values from message`() {
        val original = RootMessage(anInt = 12, str = "testing", repeatMe = listOf(
            RootMessage.RepeatedMessage(1),
            RootMessage.RepeatedMessage(2),
            RootMessage.RepeatedMessage(3),
            RootMessage.RepeatedMessage(4),
        ))
        val encodedOriginal = original.toByteArray()
        val patch = original.edit {
            repeatMe {
                remove(RootMessage.RepeatedMessage(3))
            }
        }

        val edited = patch.value
        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals("testing", edited.str)
        assertEquals(listOf(1, 2, 4), edited.repeatMe.map { it.repeatedInt })
        assertEquals(edited, patched)
    }
}
