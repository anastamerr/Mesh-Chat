package chat.mesh.crypto

import chat.mesh.protocol.MessageMetadata
import chat.mesh.protocol.PacketBindingV0

/** Raw, library-neutral public identity exchanged through a trusted bootstrap. */
public class PublicIdentity(
    encryptionPublicKey: ByteArray,
    signingPublicKey: ByteArray,
) {
    private val encryptionPublicKeyBytes: ByteArray = encryptionPublicKey.copyOf()
    private val signingPublicKeyBytes: ByteArray = signingPublicKey.copyOf()

    init {
        require(encryptionPublicKeyBytes.size == RAW_KEY_SIZE_BYTES) {
            "Encryption public key must be $RAW_KEY_SIZE_BYTES bytes"
        }
        require(signingPublicKeyBytes.size == RAW_KEY_SIZE_BYTES) {
            "Signing public key must be $RAW_KEY_SIZE_BYTES bytes"
        }
    }

    public fun copyEncryptionPublicKey(): ByteArray = encryptionPublicKeyBytes.copyOf()

    public fun copySigningPublicKey(): ByteArray = signingPublicKeyBytes.copyOf()

    public companion object {
        public const val RAW_KEY_SIZE_BYTES: Int = PacketBindingV0.RAW_PUBLIC_KEY_SIZE_BYTES
    }
}

/** Raw key material handed only to a platform-protected identity store. */
public class DeviceKeyMaterial(
    encryptionPrivateKey: ByteArray,
    publicIdentity: PublicIdentity,
    signingPrivateKey: ByteArray,
) {
    private val encryptionPrivateKeyBytes: ByteArray = encryptionPrivateKey.copyOf()
    private val signingPrivateKeyBytes: ByteArray = signingPrivateKey.copyOf()

    public val publicIdentity: PublicIdentity = PublicIdentity(
        publicIdentity.copyEncryptionPublicKey(),
        publicIdentity.copySigningPublicKey(),
    )

    init {
        require(encryptionPrivateKeyBytes.size == PublicIdentity.RAW_KEY_SIZE_BYTES) {
            "Encryption private key must be ${PublicIdentity.RAW_KEY_SIZE_BYTES} bytes"
        }
        require(signingPrivateKeyBytes.size == PublicIdentity.RAW_KEY_SIZE_BYTES) {
            "Signing private key must be ${PublicIdentity.RAW_KEY_SIZE_BYTES} bytes"
        }
    }

    public fun copyEncryptionPrivateKey(): ByteArray = encryptionPrivateKeyBytes.copyOf()

    public fun copySigningPrivateKey(): ByteArray = signingPrivateKeyBytes.copyOf()
}

/** End-to-end cryptography boundary used above routing and transport layers. */
public interface MessageCrypto {
    public val publicIdentity: PublicIdentity

    public fun sealPrivateMessage(
        metadata: MessageMetadata,
        recipient: PublicIdentity,
        plaintext: ByteArray,
    ): ByteArray

    public fun openPrivateMessage(
        metadata: MessageMetadata,
        expectedSender: PublicIdentity,
        ciphertext: ByteArray,
    ): MessageOpenResult

    public fun signDeliveryAcknowledgement(metadata: MessageMetadata): ByteArray

    public fun verifyDeliveryAcknowledgement(
        metadata: MessageMetadata,
        expectedSigner: PublicIdentity,
        signature: ByteArray,
    ): Boolean
}

public sealed interface MessageOpenResult {
    public class Success internal constructor(plaintext: ByteArray) : MessageOpenResult {
        private val plaintextBytes: ByteArray = plaintext.copyOf()

        public fun copyPlaintext(): ByteArray = plaintextBytes.copyOf()
    }

    public data class Rejected(public val reason: MessageOpenRejection) : MessageOpenResult
}

public enum class MessageOpenRejection {
    DECRYPTION_FAILED,
    MALFORMED_ENVELOPE,
    UNEXPECTED_SENDER,
    INVALID_SIGNATURE,
}
