package chat.mesh.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PacketBindingV0Test {
    @Test
    fun `private message context matches frozen vector`() {
        assertEquals(PRIVATE_MESSAGE_CONTEXT_VECTOR, PacketBindingV0.privateMessageContext(METADATA).hex())
    }

    @Test
    fun `private message signature content matches frozen vector`() {
        val content = PacketBindingV0.privateMessageSignature(
            metadata = METADATA,
            recipientEncryptionPublicKey = RECIPIENT_KEY,
            senderSigningPublicKey = SENDER_KEY,
            plaintext = PLAINTEXT,
        )

        assertEquals(PRIVATE_MESSAGE_SIGNATURE_VECTOR, content.hex())
    }

    @Test
    fun `delivery acknowledgement content matches frozen vector`() {
        assertEquals(
            DELIVERY_ACK_SIGNATURE_VECTOR,
            PacketBindingV0.deliveryAcknowledgementSignature(METADATA).hex(),
        )
    }

    @Test
    fun `signature input copies mutable values`() {
        val recipientKey = RECIPIENT_KEY.copyOf()
        val senderKey = SENDER_KEY.copyOf()
        val plaintext = PLAINTEXT.copyOf()
        val encoded = PacketBindingV0.privateMessageSignature(
            METADATA,
            recipientKey,
            senderKey,
            plaintext,
        )

        recipientKey.fill(0)
        senderKey.fill(0)
        plaintext.fill(0)

        assertEquals(PRIVATE_MESSAGE_SIGNATURE_VECTOR, encoded.hex())
    }

    @Test
    fun `public keys and plaintext are bounded`() {
        assertFailsWith<IllegalArgumentException> {
            PacketBindingV0.privateMessageSignature(
                METADATA,
                ByteArray(PacketBindingV0.RAW_PUBLIC_KEY_SIZE_BYTES - 1),
                SENDER_KEY,
                PLAINTEXT,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PacketBindingV0.privateMessageSignature(
                METADATA,
                RECIPIENT_KEY,
                SENDER_KEY,
                ByteArray(PrivateMessageEnvelopeV0.MAX_PLAINTEXT_BYTES + 1),
            )
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private val METADATA = MessageMetadata(
            messageId = FixedBytes16.from(ByteArray(16) { it.toByte() }),
            recipientToken = FixedBytes16.from(ByteArray(16) { (0x10 + it).toByte() }),
            createdAtEpochMillis = 1_700_000_000_000L,
            expiresAtEpochMillis = 1_700_000_060_000L,
        )
        private val RECIPIENT_KEY = ByteArray(32) { (0x20 + it).toByte() }
        private val SENDER_KEY = ByteArray(32) { (0x40 + it).toByte() }
        private val PLAINTEXT = "mesh-v0".encodeToByteArray()
        private const val PRIVATE_MESSAGE_CONTEXT_VECTOR =
            "646d3a707269766174652d6d6573736167652d636f6e746578743a76307c0001" +
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "0000018bcfe568000000018bcfe65260"
        private const val PRIVATE_MESSAGE_SIGNATURE_VECTOR =
            "646d3a707269766174652d6d6573736167652d7369676e61747572653a76307c0001" +
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "0000018bcfe568000000018bcfe65260" +
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
                "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f" +
                "000000076d6573682d7630"
        private const val DELIVERY_ACK_SIGNATURE_VECTOR =
            "646d3a64656c69766572792d61636b2d7369676e61747572653a76307c0002" +
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "0000018bcfe568000000018bcfe65260"
    }
}
