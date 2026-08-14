package chat.mesh.crypto

import chat.mesh.protocol.MessageMetadata
import chat.mesh.protocol.PacketBindingV0
import chat.mesh.protocol.PacketCodecV0
import chat.mesh.protocol.PrivateMessageEnvelopeDecodeResult
import chat.mesh.protocol.PrivateMessageEnvelopeV0
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.Ed25519Parameters
import com.google.crypto.tink.signature.Ed25519PrivateKey
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import java.security.GeneralSecurityException
import java.security.MessageDigest

/** Tink-backed RFC 9180 HPKE and Ed25519 implementation with raw v0 wire keys. */
public class TinkMessageCrypto private constructor(
    private val keyMaterial: DeviceKeyMaterial,
    private val decryptor: HybridDecrypt,
    private val signer: PublicKeySign,
) : MessageCrypto {
    override val publicIdentity: PublicIdentity = PublicIdentity(
        keyMaterial.publicIdentity.copyEncryptionPublicKey(),
        keyMaterial.publicIdentity.copySigningPublicKey(),
    )

    override fun sealPrivateMessage(
        metadata: MessageMetadata,
        recipient: PublicIdentity,
        plaintext: ByteArray,
    ): ByteArray {
        val senderSigningPublicKey = publicIdentity.copySigningPublicKey()
        val signatureContent = PacketBindingV0.privateMessageSignature(
            metadata = metadata,
            recipientEncryptionPublicKey = recipient.copyEncryptionPublicKey(),
            senderSigningPublicKey = senderSigningPublicKey,
            plaintext = plaintext,
        )
        val envelope = PrivateMessageEnvelopeV0.encode(
            senderSigningPublicKey = senderSigningPublicKey,
            signature = signer.sign(signatureContent),
            plaintext = plaintext,
        )
        val ciphertext = recipient.encryptor().encrypt(
            envelope,
            PacketBindingV0.privateMessageContext(metadata),
        )
        check(ciphertext.size <= PacketCodecV0.MAX_PAYLOAD_BYTES) {
            "Encrypted message exceeds the routed-packet payload limit"
        }
        return ciphertext
    }

    override fun openPrivateMessage(
        metadata: MessageMetadata,
        expectedSender: PublicIdentity,
        ciphertext: ByteArray,
    ): MessageOpenResult {
        if (ciphertext.size > PacketCodecV0.MAX_PAYLOAD_BYTES) {
            return MessageOpenResult.Rejected(MessageOpenRejection.DECRYPTION_FAILED)
        }
        val envelopeBytes = try {
            decryptor.decrypt(ciphertext, PacketBindingV0.privateMessageContext(metadata))
        } catch (_: GeneralSecurityException) {
            return MessageOpenResult.Rejected(MessageOpenRejection.DECRYPTION_FAILED)
        }
        val envelope = when (val decoded = PrivateMessageEnvelopeV0.decode(envelopeBytes)) {
            is PrivateMessageEnvelopeDecodeResult.Failure -> {
                return MessageOpenResult.Rejected(MessageOpenRejection.MALFORMED_ENVELOPE)
            }
            is PrivateMessageEnvelopeDecodeResult.Success -> decoded.message
        }

        val expectedSigningKey = expectedSender.copySigningPublicKey()
        if (!MessageDigest.isEqual(expectedSigningKey, envelope.copySenderSigningPublicKey())) {
            return MessageOpenResult.Rejected(MessageOpenRejection.UNEXPECTED_SENDER)
        }

        val plaintext = envelope.copyPlaintext()
        val signatureContent = PacketBindingV0.privateMessageSignature(
            metadata = metadata,
            recipientEncryptionPublicKey = publicIdentity.copyEncryptionPublicKey(),
            senderSigningPublicKey = expectedSigningKey,
            plaintext = plaintext,
        )
        if (!expectedSender.verifies(envelope.copySignature(), signatureContent)) {
            return MessageOpenResult.Rejected(MessageOpenRejection.INVALID_SIGNATURE)
        }
        return MessageOpenResult.Success(plaintext)
    }

    override fun signDeliveryAcknowledgement(metadata: MessageMetadata): ByteArray =
        signer.sign(PacketBindingV0.deliveryAcknowledgementSignature(metadata))

    override fun verifyDeliveryAcknowledgement(
        metadata: MessageMetadata,
        expectedSigner: PublicIdentity,
        signature: ByteArray,
    ): Boolean = expectedSigner.verifies(
        signature,
        PacketBindingV0.deliveryAcknowledgementSignature(metadata),
    )

    public fun copyKeyMaterial(): DeviceKeyMaterial = DeviceKeyMaterial(
        encryptionPrivateKey = keyMaterial.copyEncryptionPrivateKey(),
        publicIdentity = publicIdentity,
        signingPrivateKey = keyMaterial.copySigningPrivateKey(),
    )

    private fun PublicIdentity.encryptor(): HybridEncrypt {
        val key = HpkePublicKey.create(
            HPKE_PARAMETERS,
            Bytes.copyFrom(copyEncryptionPublicKey()),
            null,
        )
        return key.handle().getPrimitive(
            RegistryConfiguration.get(),
            HybridEncrypt::class.java,
        )
    }

    private fun PublicIdentity.verifier(): PublicKeyVerify {
        val key = Ed25519PublicKey.create(
            Ed25519Parameters.Variant.NO_PREFIX,
            Bytes.copyFrom(copySigningPublicKey()),
            null,
        )
        return key.handle().getPrimitive(
            RegistryConfiguration.get(),
            PublicKeyVerify::class.java,
        )
    }

    private fun PublicIdentity.verifies(signature: ByteArray, content: ByteArray): Boolean =
        try {
            verifier().verify(signature, content)
            true
        } catch (_: GeneralSecurityException) {
            false
        }

    public companion object {
        private val SECRET_KEY_ACCESS = InsecureSecretKeyAccess.get()
        private val HPKE_PARAMETERS: HpkeParameters = HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .setVariant(HpkeParameters.Variant.NO_PREFIX)
            .build()
        private val ED25519_PARAMETERS: Ed25519Parameters = Ed25519Parameters.create(
            Ed25519Parameters.Variant.NO_PREFIX,
        )

        init {
            HybridConfig.register()
            SignatureConfig.register()
        }

        public fun generate(): TinkMessageCrypto {
            val encryptionKey = KeysetHandle.generateNew(HPKE_PARAMETERS).primary
                .key as HpkePrivateKey
            val signingKey = KeysetHandle.generateNew(ED25519_PARAMETERS).primary
                .key as Ed25519PrivateKey
            return fromKeyMaterial(
                DeviceKeyMaterial(
                    encryptionPrivateKey = encryptionKey.privateBytes(),
                    publicIdentity = PublicIdentity(
                        encryptionKey.publicKey.publicKeyBytes.toByteArray(),
                        signingKey.publicKey.publicKeyBytes.toByteArray(),
                    ),
                    signingPrivateKey = signingKey.privateBytes(),
                ),
            )
        }

        public fun fromKeyMaterial(keyMaterial: DeviceKeyMaterial): TinkMessageCrypto {
            val publicIdentity = keyMaterial.publicIdentity
            val hpkePublicKey = HpkePublicKey.create(
                HPKE_PARAMETERS,
                Bytes.copyFrom(publicIdentity.copyEncryptionPublicKey()),
                null,
            )
            val hpkePrivateKey = HpkePrivateKey.create(
                hpkePublicKey,
                SecretBytes.copyFrom(keyMaterial.copyEncryptionPrivateKey(), SECRET_KEY_ACCESS),
            )
            val signingPublicKey = Ed25519PublicKey.create(
                Ed25519Parameters.Variant.NO_PREFIX,
                Bytes.copyFrom(publicIdentity.copySigningPublicKey()),
                null,
            )
            val signingPrivateKey = Ed25519PrivateKey.create(
                signingPublicKey,
                SecretBytes.copyFrom(keyMaterial.copySigningPrivateKey(), SECRET_KEY_ACCESS),
            )
            return TinkMessageCrypto(
                keyMaterial = keyMaterial,
                decryptor = hpkePrivateKey.handle().getPrimitive(
                    RegistryConfiguration.get(),
                    HybridDecrypt::class.java,
                ),
                signer = signingPrivateKey.handle().getPrimitive(
                    RegistryConfiguration.get(),
                    PublicKeySign::class.java,
                ),
            )
        }

        private fun com.google.crypto.tink.Key.handle(): KeysetHandle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(this).withRandomId().makePrimary())
            .build()

        private fun HpkePrivateKey.privateBytes(): ByteArray =
            privateKeyBytes.toByteArray(SECRET_KEY_ACCESS)

        private fun Ed25519PrivateKey.privateBytes(): ByteArray =
            privateKeyBytes.toByteArray(SECRET_KEY_ACCESS)
    }
}
