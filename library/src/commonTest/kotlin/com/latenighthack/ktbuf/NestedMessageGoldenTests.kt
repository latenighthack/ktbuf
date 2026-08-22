package com.latenighthack.ktbuf

import kotlin.test.Test
import kotlin.test.assertEquals

// ScopedProtobufWriter.encode(fieldNumber, builder) reserves a single byte for the
// child's length and, when the child turns out longer than 127 bytes, inserts the
// extra length bytes back at the reserved offset. That back-patch is the most
// intricate path in the encoder; these tests pin it at every width step.
class NestedMessageGoldenTests {
    @Test
    fun emptyNestedMessageMatchesGolden() {
        val encoded = encode { encode(1) { } }

        assertGolden(WireGoldens.NESTED_Empty, encoded, "empty nested message")
    }

    @Test
    fun simpleNestedMessageMatchesGolden() {
        val encoded = encode {
            encode(1) { encode(150, 1) }
        }

        assertGolden(WireGoldens.NESTED_Simple, encoded, "nested message with int32 150")

        val inner = decodeFirstField(WireGoldens.NESTED_Simple.hexToBytes()) { reader ->
            reader.readField { fieldReader ->
                fieldReader.nextField()
                fieldReader.readInt32()
            }
        }

        assertEquals(150, inner)
    }

    @Test
    fun nestedLengthBackPatchMatchesGoldensAtEveryWidth() {
        // 127/128 exercises the 1->2 byte length growth, 16383/16384 the 2->3 growth.
        val cases = listOf(
            Triple(WireGoldens.NESTED_Len127, WireGoldens.NESTED_Len127_Payload, 127),
            Triple(WireGoldens.NESTED_Len128, WireGoldens.NESTED_Len128_Payload, 128),
            Triple(WireGoldens.NESTED_Len16383, WireGoldens.NESTED_Len16383_Payload, 16383),
            Triple(WireGoldens.NESTED_Len16384, WireGoldens.NESTED_Len16384_Payload, 16384)
        )

        for ((golden, payloadSize, childSize) in cases) {
            val payload = ByteArray(payloadSize) { 0xAB.toByte() }

            val encoded = encode {
                encode(1) { encode(payload, 1) }
            }

            assertGolden(golden, encoded, "nested message with $childSize-byte child")

            val decoded = decodeFirstField(golden.hexToBytes()) { reader ->
                reader.readField { fieldReader ->
                    fieldReader.nextField()
                    fieldReader.readBytes()
                }
            }

            assertEquals(payloadSize, decoded.size, "child payload size for $childSize-byte child")
            assertEquals(payload.toHex(), decoded.toHex(), "child payload for $childSize-byte child")
        }
    }

    @Test
    fun deeplyNestedMessageMatchesGolden() {
        val encoded = encode {
            encode(3) {
                encode(3) {
                    encode(3) { encode(42, 1) }
                }
            }
        }

        assertGolden(WireGoldens.NESTED_Deep, encoded, "three-level nested message")

        val innermost = decodeFirstField(WireGoldens.NESTED_Deep.hexToBytes()) { l1 ->
            l1.readField { l2 ->
                l2.nextField()
                l2.readField { l3 ->
                    l3.nextField()
                    l3.readField { leaf ->
                        leaf.nextField()
                        leaf.readInt32()
                    }
                }
            }
        }

        assertEquals(42, innermost)
    }

    @Test
    fun siblingNestedMessagesAfterABackPatchStayAligned() {
        // A back-patch shifts every byte written after the reserved offset. If the
        // insert is mishandled, the FIRST child still decodes and only the following
        // siblings are corrupt -- so a single-child test would miss it.
        val big = ByteArray(200) { 0x7F }

        val encoded = encode {
            encode(1) { encode(big, 1) }
            encode(2) { encode(7, 1) }
            encode(3) { encode("tail", 1) }
        }

        decode(encoded) { reader ->
            reader.nextField()
            assertEquals(1, reader.currentFieldNumber)
            val first = reader.readField { it.nextField(); it.readBytes() }
            assertEquals(big.toHex(), first.toHex(), "first child survived")

            reader.nextField()
            assertEquals(2, reader.currentFieldNumber)
            assertEquals(7, reader.readField { it.nextField(); it.readInt32() }, "sibling after back-patch")

            reader.nextField()
            assertEquals(3, reader.currentFieldNumber)
            assertEquals("tail", reader.readField { it.nextField(); it.readString() }, "last sibling")
        }
    }

    @Test
    fun nestedMessageInsideALargeParentRoundTrips() {
        // Forces a back-patch on the outer message while the inner one is also
        // multi-byte, i.e. two nested inserts against the same buffer.
        val payload = ByteArray(500) { (it % 256).toByte() }

        val encoded = encode {
            encode(1) {
                encode(2) { encode(payload, 1) }
                encode(99, 3)
            }
        }

        decode(encoded) { reader ->
            reader.nextField()

            reader.readField { outer ->
                outer.nextField()
                assertEquals(2, outer.currentFieldNumber)
                val inner = outer.readField { it.nextField(); it.readBytes() }
                assertEquals(payload.toHex(), inner.toHex(), "inner payload")

                outer.nextField()
                assertEquals(3, outer.currentFieldNumber)
                assertEquals(99, outer.readInt32(), "field after inner message")
            }
        }
    }
}
