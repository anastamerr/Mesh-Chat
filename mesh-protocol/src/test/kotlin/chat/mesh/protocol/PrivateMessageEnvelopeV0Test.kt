package chat.mesh.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PrivateMessageEnvelopeV0Test {
    @Test
    fun `envelope round trip is canonical and defensive`() {
        val senderKey = ByteArray(32) { it.toByte() }
        val signature = ByteArray(64) { (0x40 + it).toByte() }
        val plaintext = "private".encodeToByteArray()
        val encoded = PrivateMessageEnvelopeV0.encode(senderKey, signature, plaintext)

        senderKey.fill(0)
        signature.fill(0)
        plaintext.fill(0)

        val decoded = assertIs<PrivateMessageEnvelopeDecodeResult.Success>(
            PrivateMessageEnvelopeV0.decode(encoded),
        ).message
        assertContentEquals(ByteArray(32) { it.toByte() }, decoded.copySenderSigningPublicKey())
        assertContentEquals(ByteArray(64) { (0x40 + it).toByte() }, decoded.copySignature())
        assertContentEquals("private".encodeToByteArray(), decoded.copyPlaintext())

        decoded.copyPlaintext().fill(0)
        assertContentEquals("private".encodeToByteArray(), decoded.copyPlaintext())
    }

    @Test
    fun `decoder rejects truncated versioned oversized and mismatched envelopes`() {
        val valid = PrivateMessageEnvelopeV0.encode(ByteArray(32), ByteArray(64), byteArrayOf(1))

        assertFailure(ByteArray(PrivateMessageEnvelopeV0.HEADER_SIZE_BYTES - 1), PrivateMessageEnvelopeDecodeError.TOO_SHORT)
        assertFailure(valid.copyOf().also { it[0] = 1 }, PrivateMessageEnvelopeDecodeError.UNSUPPORTED_VERSION)
        assertFailure(
            valid.copyOf().also { bytes ->
                val lengthOffset = PrivateMessageEnvelopeV0.HEADER_SIZE_BYTES - Int.SIZE_BYTES
                bytes[lengthOffset] = 0x7f
            },
            PrivateMessageEnvelopeDecodeError.PLAINTEXT_TOO_LARGE,
        )
        assertFailure(valid.copyOf(valid.size + 1), PrivateMessageEnvelopeDecodeError.LENGTH_MISMATCH)
    }

    @Test
    fun `encoder enforces fixed fields and payload bound`() {
        assertFailsWith<IllegalArgumentException> {
            PrivateMessageEnvelopeV0.encode(ByteArray(31), ByteArray(64), ByteArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            PrivateMessageEnvelopeV0.encode(ByteArray(32), ByteArray(63), ByteArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            PrivateMessageEnvelopeV0.encode(
                ByteArray(32),
                ByteArray(64),
                ByteArray(PrivateMessageEnvelopeV0.MAX_PLAINTEXT_BYTES + 1),
            )
        }
    }

    private fun assertFailure(input: ByteArray, expected: PrivateMessageEnvelopeDecodeError) {
        val failure = assertIs<PrivateMessageEnvelopeDecodeResult.Failure>(
            PrivateMessageEnvelopeV0.decode(input),
        )
        assertEquals(expected, failure.error)
    }
}
