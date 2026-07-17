package com.latenighthack.ktbuf

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

// ProtobufOutputStream's default internal buffer is exactly 8192 bytes. Any single
// message whose encoded size (payload + protobuf tag/length overhead) exceeds that
// must trigger a buffer grow in ConcreteLinkedByteArray. These sizes bracket that
// boundary to pin down exactly where growth kicks in and whether it's still correct.
class ProtobufOutputStreamSizeTests {
    private fun roundTrip(payloadSize: Int): ByteArray {
        val payload = Random(payloadSize).nextBytes(payloadSize)

        val encoded = ProtobufOutputStream().also { stream ->
            stream.write { writer -> writer.encode(payload, 1) }
        }.toByteArray()

        val decoded = ProtobufInputStream().let { stream ->
            stream.addBytes(encoded)
            stream.read { reader ->
                reader.nextField()
                reader.readBytes()
            }
        }

        assertContentEquals(payload, decoded, "payload size $payloadSize did not round-trip")

        return decoded
    }

    @Test
    fun singleFieldRoundTripsAcross8192ByteBoundary() {
        val sizes = listOf(
            0, 1, 1024, 4096,
            8000, 8100, 8180, 8185, 8186, 8187, 8188, 8189, 8190, 8191, 8192, 8193, 8194, 8200, 8300,
            9000, 16384, 65536, 131072
        )

        for (size in sizes) {
            roundTrip(size)
        }
    }

    @Test
    fun manySmallWritesFollowedByOneLargeWriteRoundTrips() {
        // Mimics a message with several small header fields (tags/lengths) followed by
        // one large payload field -- the shape that triggers growth from a small
        // `cachedSize` straight up to a much larger `targetSize`.
        val payload = Random(65536).nextBytes(65536)

        val encoded = ProtobufOutputStream().also { stream ->
            stream.write { writer ->
                writer.encode(1, 1)
                writer.encode(2, 2)
                writer.encode(3, 3)
                writer.encode(payload, 4)
            }
        }.toByteArray()

        val decoded = ProtobufInputStream().let { stream ->
            stream.addBytes(encoded)
            stream.read { reader ->
                reader.nextField()
                reader.readInt32()
                reader.nextField()
                reader.readInt32()
                reader.nextField()
                reader.readInt32()
                reader.nextField()
                reader.readBytes()
            }
        }

        assertContentEquals(payload, decoded)
    }
}
