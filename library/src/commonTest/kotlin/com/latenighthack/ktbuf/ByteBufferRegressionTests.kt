package com.latenighthack.ktbuf

import com.latenighthack.ktbuf.bytes.*
import kotlin.test.*

class ByteBufferRegressionTests {
    @Test fun refillReadsOnlyNewInput() {
        val input = ProtobufInputStream()
        for (value in 1..4) {
            input.addBytes(byteArrayOf(8, value.toByte()))
            input.read { assertTrue(it.nextField()); assertEquals(value, it.readInt32()); assertFalse(it.nextField()) }
        }
    }
    @Test fun editsAddressUnreadBytesAndRewindIsExplicit() {
        val b = MutableLinkedByteArray(0)
        b.write(byteArrayOf(1, 2, 3)); b.advance(1)
        b.write(4); b.write(9, destinationOffset = 0); b.insert(byteArrayOf(8), destinationOffset = 1)
        assertContentEquals(byteArrayOf(9, 8, 3, 4), b.toByteArray())
        b.reset(); assertContentEquals(byteArrayOf(1, 9, 8, 3, 4), b.read())
        b.clear(); assertEquals(0, b.size); b.write(7); assertContentEquals(byteArrayOf(7), b.read())
    }
    @Test fun viewsRewindAndRemainWithinTheirDeclaredScope() {
        val b = MutableLinkedByteArray(); b.write(byteArrayOf(1,2,3,4))
        val view = b.subarray(1,2); assertContentEquals(byteArrayOf(2), view.readAdvance(1))
        assertContentEquals(byteArrayOf(3), view.toByteArray()); view.reset()
        assertContentEquals(byteArrayOf(2,3), view.read()); view.clear(); assertEquals(0, view.size)
        view.reset(); assertContentEquals(byteArrayOf(3), view.subarray(1,1).read())
        assertFails { view.read(3) }; assertEquals(2,view.size)
    }
    @Test fun copyAccountsForDestinationOffset() {
        val b = MutableLinkedByteArray(); b.write(byteArrayOf(1,2))
        val destination = ByteArray(2); b.copyTo(destination, destinationOffset=1)
        assertContentEquals(byteArrayOf(0,1),destination)
    }
    @Test fun invalidOperationsLeaveBytesAndCursorsUntouched() {
        val b = MutableLinkedByteArray(); b.write(byteArrayOf(1,2)); b.advance(1)
        for (operation in listOf<() -> Unit>({b.advance(-1)}, {b.advance(2)}, {b.read(2)}, {b.insert(byteArrayOf(3),sourceOffset=2)}, {b.write(byteArrayOf(4),length=-1)}, {b.subarray(0,2)}, {b.copyTo(ByteArray(0),length=1)})) {
            assertFails { operation() }; assertEquals(1,b.size); assertContentEquals(byteArrayOf(2),b.read())
        }
    }
}
