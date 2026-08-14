package chat.mesh.crypto

import chat.mesh.protocol.FixedBytes16
import chat.mesh.protocol.MessageMetadata
import chat.mesh.protocol.PacketBindingV0
import chat.mesh.protocol.PacketCodecV0
import chat.mesh.protocol.PrivateMessageEnvelopeV0
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.util.Bytes
import java.security.GeneralSecurityException
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TinkMessageCryptoTest {
    @Test
    fun `frozen Swift and Tink vectors open with raw keys`() {
        val vector = loadVector()
        val identity = PublicIdentity(
            vector.required("recipient_hpke_public").hexBytes(),
            vector.required("sender_signing_public").hexBytes(),
        )
        val crypto = TinkMessageCrypto.fromKeyMaterial(
            DeviceKeyMaterial(
                vector.required("recipient_hpke_private").hexBytes(),
                identity,
                vector.required("sender_signing_private").hexBytes(),
            ),
        )
        assertContentEquals(
            vector.required("context").hexBytes(),
            PacketBindingV0.privateMessageContext(METADATA),
        )
        assertContentEquals(
            vector.required("signed_content").hexBytes(),
            PacketBindingV0.privateMessageSignature(
                METADATA,
                identity.copyEncryptionPublicKey(),
                identity.copySigningPublicKey(),
                "swift-tink-v0".encodeToByteArray(),
            ),
        )

        listOf("swift_ciphertext", "tink_ciphertext").forEach { name ->
            val opened = assertIs<MessageOpenResult.Success>(
                crypto.openPrivateMessage(METADATA, identity, vector.required(name).hexBytes()),
            )
            assertContentEquals("swift-tink-v0".encodeToByteArray(), opened.copyPlaintext())
        }
    }

    @Test
    fun `authenticated private message survives key reconstruction`() {
        val sender = TinkMessageCrypto.generate()
        val recipient = TinkMessageCrypto.generate()
        val reconstructedRecipient = TinkMessageCrypto.fromKeyMaterial(recipient.copyKeyMaterial())
        val plaintext = "sealed and signed".encodeToByteArray()

        val ciphertext = sender.sealPrivateMessage(METADATA, recipient.publicIdentity, plaintext)
        val opened = assertIs<MessageOpenResult.Success>(
            reconstructedRecipient.openPrivateMessage(METADATA, sender.publicIdentity, ciphertext),
        )

        assertContentEquals(plaintext, opened.copyPlaintext())
        opened.copyPlaintext().fill(0)
        assertContentEquals(plaintext, opened.copyPlaintext())
    }

    @Test
    fun `wrong recipient tampering and changed metadata fail closed`() {
        val sender = TinkMessageCrypto.generate()
        val recipient = TinkMessageCrypto.generate()
        val wrongRecipient = TinkMessageCrypto.generate()
        val ciphertext = sender.sealPrivateMessage(
            METADATA,
            recipient.publicIdentity,
            "private".encodeToByteArray(),
        )

        assertRejected(
            wrongRecipient.openPrivateMessage(METADATA, sender.publicIdentity, ciphertext),
            MessageOpenRejection.DECRYPTION_FAILED,
        )
        assertRejected(
            recipient.openPrivateMessage(
                METADATA,
                sender.publicIdentity,
                ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
            ),
            MessageOpenRejection.DECRYPTION_FAILED,
        )
        assertRejected(
            recipient.openPrivateMessage(
                METADATA.copy(expiresAtEpochMillis = METADATA.expiresAtEpochMillis + 1),
                sender.publicIdentity,
                ciphertext,
            ),
            MessageOpenRejection.DECRYPTION_FAILED,
        )
        assertRejected(
            recipient.openPrivateMessage(
                METADATA,
                sender.publicIdentity,
                ByteArray(PacketCodecV0.MAX_PAYLOAD_BYTES + 1),
            ),
            MessageOpenRejection.DECRYPTION_FAILED,
        )
    }

    @Test
    fun `unexpected sender is rejected after decryption`() {
        val sender = TinkMessageCrypto.generate()
        val recipient = TinkMessageCrypto.generate()
        val wrongSender = TinkMessageCrypto.generate()
        val ciphertext = sender.sealPrivateMessage(
            METADATA,
            recipient.publicIdentity,
            "private".encodeToByteArray(),
        )

        assertRejected(
            recipient.openPrivateMessage(METADATA, wrongSender.publicIdentity, ciphertext),
            MessageOpenRejection.UNEXPECTED_SENDER,
        )
    }

    @Test
    fun `malformed envelope and invalid sender signature are distinguished`() {
        val sender = TinkMessageCrypto.generate()
        val recipient = TinkMessageCrypto.generate()
        val context = PacketBindingV0.privateMessageContext(METADATA)

        assertRejected(
            recipient.openPrivateMessage(
                METADATA,
                sender.publicIdentity,
                encryptRaw(recipient.publicIdentity, byteArrayOf(0), context),
            ),
            MessageOpenRejection.MALFORMED_ENVELOPE,
        )

        val forgedEnvelope = PrivateMessageEnvelopeV0.encode(
            senderSigningPublicKey = sender.publicIdentity.copySigningPublicKey(),
            signature = ByteArray(PrivateMessageEnvelopeV0.SIGNATURE_SIZE_BYTES),
            plaintext = "forged".encodeToByteArray(),
        )
        assertRejected(
            recipient.openPrivateMessage(
                METADATA,
                sender.publicIdentity,
                encryptRaw(recipient.publicIdentity, forgedEnvelope, context),
            ),
            MessageOpenRejection.INVALID_SIGNATURE,
        )
    }

    @Test
    fun `delivery acknowledgements bind signer metadata and signature`() {
        val sender = TinkMessageCrypto.generate()
        val recipient = TinkMessageCrypto.generate()
        val attacker = TinkMessageCrypto.generate()
        val signature = recipient.signDeliveryAcknowledgement(METADATA)

        assertTrue(
            sender.verifyDeliveryAcknowledgement(METADATA, recipient.publicIdentity, signature),
        )
        assertFalse(
            sender.verifyDeliveryAcknowledgement(METADATA, attacker.publicIdentity, signature),
        )
        assertFalse(
            sender.verifyDeliveryAcknowledgement(
                METADATA.copy(recipientToken = fixedBytes(0x70)),
                recipient.publicIdentity,
                signature,
            ),
        )
        assertFalse(
            sender.verifyDeliveryAcknowledgement(
                METADATA,
                recipient.publicIdentity,
                signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
            ),
        )
        assertFalse(
            sender.verifyDeliveryAcknowledgement(
                METADATA,
                PublicIdentity(
                    recipient.publicIdentity.copyEncryptionPublicKey(),
                    ByteArray(PublicIdentity.RAW_KEY_SIZE_BYTES) { 0xff.toByte() },
                ),
                signature,
            ),
        )
    }

    @Test
    fun `maximum plaintext fits one routed packet and larger input is rejected`() {
        val sender = TinkMessageCrypto.generate()
        val recipient = TinkMessageCrypto.generate()
        val maximum = ByteArray(PrivateMessageEnvelopeV0.MAX_PLAINTEXT_BYTES)

        assertEquals(
            PacketCodecV0.MAX_PAYLOAD_BYTES,
            sender.sealPrivateMessage(METADATA, recipient.publicIdentity, maximum).size,
        )
        assertFailsWith<IllegalArgumentException> {
            sender.sealPrivateMessage(
                METADATA,
                recipient.publicIdentity,
                ByteArray(PrivateMessageEnvelopeV0.MAX_PLAINTEXT_BYTES + 1),
            )
        }
    }

    @Test
    fun `key material and public identity use defensive fixed-size raw keys`() {
        val crypto = TinkMessageCrypto.generate()
        val exported = crypto.copyKeyMaterial()
        val encryptionPrivateKey = exported.copyEncryptionPrivateKey()
        val signingPrivateKey = exported.copySigningPrivateKey()
        val encryptionPublicKey = exported.publicIdentity.copyEncryptionPublicKey()
        val signingPublicKey = exported.publicIdentity.copySigningPublicKey()

        assertEquals(32, encryptionPrivateKey.size)
        assertEquals(32, signingPrivateKey.size)
        assertEquals(32, encryptionPublicKey.size)
        assertEquals(32, signingPublicKey.size)

        encryptionPrivateKey.fill(0)
        signingPrivateKey.fill(0)
        encryptionPublicKey.fill(0)
        signingPublicKey.fill(0)

        val reconstructed = TinkMessageCrypto.fromKeyMaterial(exported)
        assertFalse(reconstructed.copyKeyMaterial().copyEncryptionPrivateKey().all { it == 0.toByte() })
        assertFalse(reconstructed.publicIdentity.copySigningPublicKey().all { it == 0.toByte() })
        assertFailsWith<IllegalArgumentException> {
            PublicIdentity(ByteArray(31), ByteArray(32))
        }

        val other = TinkMessageCrypto.generate().copyKeyMaterial()
        assertFailsWith<GeneralSecurityException> {
            TinkMessageCrypto.fromKeyMaterial(
                DeviceKeyMaterial(
                    exported.copyEncryptionPrivateKey(),
                    PublicIdentity(
                        other.publicIdentity.copyEncryptionPublicKey(),
                        exported.publicIdentity.copySigningPublicKey(),
                    ),
                    exported.copySigningPrivateKey(),
                ),
            )
        }
    }

    private fun encryptRaw(
        recipient: PublicIdentity,
        plaintext: ByteArray,
        context: ByteArray,
    ): ByteArray {
        val key = HpkePublicKey.create(
            HPKE_PARAMETERS,
            Bytes.copyFrom(recipient.copyEncryptionPublicKey()),
            null,
        )
        val handle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
            .build()
        return handle.getPrimitive(
            RegistryConfiguration.get(),
            HybridEncrypt::class.java,
        ).encrypt(plaintext, context)
    }

    private fun assertRejected(result: MessageOpenResult, reason: MessageOpenRejection) {
        assertEquals(reason, assertIs<MessageOpenResult.Rejected>(result).reason)
    }

    private fun String.hexBytes(): ByteArray = chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private fun loadVector(): Properties = Properties().also { properties ->
        val resource = requireNotNull(javaClass.getResourceAsStream("/crypto-v0.properties"))
        resource.use(properties::load)
    }

    private fun Properties.required(name: String): String =
        requireNotNull(getProperty(name)) { "Missing vector field: $name" }

    companion object {
        private val METADATA = MessageMetadata(
            messageId = fixedBytes(0x40),
            recipientToken = fixedBytes(0x30),
            createdAtEpochMillis = 1_700_000_000_000L,
            expiresAtEpochMillis = 1_700_000_060_000L,
        )
        private val HPKE_PARAMETERS: HpkeParameters = HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .setVariant(HpkeParameters.Variant.NO_PREFIX)
            .build()

        private fun fixedBytes(start: Int): FixedBytes16 = FixedBytes16.from(
            ByteArray(FixedBytes16.SIZE_BYTES) { index -> (start + index).toByte() },
        )
    }
}
