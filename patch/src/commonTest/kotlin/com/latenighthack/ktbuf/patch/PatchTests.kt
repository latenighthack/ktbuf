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

        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals(RootMessage.Type.B, original.type)

        // the edit updates the value appropriately
        assertEquals(RootMessage.Type.C, patched.type)

        // the edit is otherwise the same
        assertEquals(patched, original.copy(type = patched.type))
    }

    @Test
    fun `patch overwrites int value`() {
        val original = RootMessage(anInt = 10, str = "testing")
        val encodedOriginal = original.toByteArray()
        val patch = original.edit {
            anInt = 42
        }

        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals(10, original.anInt)

        // the edit is otherwise the same
        assertEquals(patched, original.copy(anInt = patched.anInt))
    }

    @Test
    fun `patch overwrites string value`() {
        val original = RootMessage(anInt = 10, str = "hello")
        val encodedOriginal = original.toByteArray()
        val patch = original.edit {
            str = "world"
        }

        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals("hello", original.str)
        assertEquals("world", patched.str)
        assertEquals(patched, original.copy(str = patched.str))
    }

    @Test
    fun `patch adds int value to empty message`() {
        val original = RootMessage()
        val encodedOriginal = byteArrayOf()
        val patch = original.edit {
            anInt = 42
        }

        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals(0, original.anInt)
        assertEquals(42, patched.anInt)
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

        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals(null, original.inner?.anInt)
        assertEquals(9000, patched.inner?.anInt)
        assertEquals(patched, original.copy(inner = RootMessage.InnerMessage(anInt = patched.inner?.anInt!!)))
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

        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals(null, original.inner?.innerInner?.anInt)
        assertEquals(9000, patched.inner?.innerInner?.anInt)
        assertEquals(patched, original.copy(inner = RootMessage.InnerMessage(innerInner = RootMessage.InnerInnerMessage(anInt = patched.inner?.innerInner?.anInt!!))))
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

        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals(listOf(2), patched.repeatMe.map { it.repeatedInt })
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

        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals(listOf(2, 3, 4), patched.repeatMe.map { it.repeatedInt })
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

        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals("testing", patched.str)
        assertEquals(listOf(2, 3, 4), patched.repeatMe.map { it.repeatedInt })
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
                removeAt(2)
            }
        }

        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals("testing", patched.str)
        assertEquals(listOf(1, 2, 4), patched.repeatMe.map { it.repeatedInt })
    }

    @Test
    fun `patch removes distinct values from message`() {
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

        val encodedPatched = encodedOriginal.applyChanges(patch.changes)
        val patched = RootMessage.fromByteArray(encodedPatched)

        assertEquals("testing", patched.str)
        assertEquals(listOf(1, 2, 4), patched.repeatMe.map { it.repeatedInt })
    }
}
