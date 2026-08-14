package chat.mesh.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Canonical v0 bytes passed to HPKE and Ed25519 adapters. */
public object PacketBindingV0 {
    public const val RAW_PUBLIC_KEY_SIZE_BYTES: Int = 32

    private val PRIVATE_MESSAGE_CONTEXT_DOMAIN =
        "dm:private-message-context:v0|".encodeToByteArray()
    private val PRIVATE_MESSAGE_SIGNATURE_DOMAIN =
        "dm:private-message-signature:v0|".encodeToByteArray()
    private val DELIVERY_ACK_SIGNATURE_DOMAIN =
        "dm:delivery-ack-signature:v0|".encodeToByteArray()

    /** HPKE key-schedule context; mutable forwarding budgets are intentionally excluded. */
    public fun privateMessageContext(metadata: MessageMetadata): ByteArray = ByteBuffer
        .allocate(PRIVATE_MESSAGE_CONTEXT_DOMAIN.size + COMMON_METADATA_SIZE_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .put(PRIVATE_MESSAGE_CONTEXT_DOMAIN)
        .putCommonMetadata(PacketType.PRIVATE_MESSAGE, metadata)
        .array()

    /** Content authenticated by the sender signature inside the HPKE plaintext. */
    public fun privateMessageSignature(
        metadata: MessageMetadata,
        recipientEncryptionPublicKey: ByteArray,
        senderSigningPublicKey: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        requireRawPublicKey(recipientEncryptionPublicKey)
        requireRawPublicKey(senderSigningPublicKey)
        require(plaintext.size <= PrivateMessageEnvelopeV0.MAX_PLAINTEXT_BYTES) {
            "Plaintext exceeds the private-message limit"
        }

        return ByteBuffer
            .allocate(
                PRIVATE_MESSAGE_SIGNATURE_DOMAIN.size +
                    COMMON_METADATA_SIZE_BYTES +
                    RAW_PUBLIC_KEY_SIZE_BYTES * 2 +
                    Int.SIZE_BYTES +
                    plaintext.size,
            )
            .order(ByteOrder.BIG_ENDIAN)
            .put(PRIVATE_MESSAGE_SIGNATURE_DOMAIN)
            .putCommonMetadata(PacketType.PRIVATE_MESSAGE, metadata)
            .put(recipientEncryptionPublicKey)
            .put(senderSigningPublicKey)
            .putInt(plaintext.size)
            .put(plaintext)
            .array()
    }

    /** Content signed by the original recipient when acknowledging delivery. */
    public fun deliveryAcknowledgementSignature(metadata: MessageMetadata): ByteArray = ByteBuffer
        .allocate(DELIVERY_ACK_SIGNATURE_DOMAIN.size + COMMON_METADATA_SIZE_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .put(DELIVERY_ACK_SIGNATURE_DOMAIN)
        .putCommonMetadata(PacketType.DELIVERY_ACK, metadata)
        .array()

    private fun ByteBuffer.putCommonMetadata(
        type: PacketType,
        metadata: MessageMetadata,
    ): ByteBuffer = put(PacketCodecV0.PROTOCOL_VERSION.toByte())
        .put(type.wireValue.toByte())
        .put(metadata.messageId.copyBytes())
        .put(metadata.recipientToken.copyBytes())
        .putLong(metadata.createdAtEpochMillis)
        .putLong(metadata.expiresAtEpochMillis)

    private fun requireRawPublicKey(key: ByteArray) {
        require(key.size == RAW_PUBLIC_KEY_SIZE_BYTES) {
            "Public key must be $RAW_PUBLIC_KEY_SIZE_BYTES bytes"
        }
    }

    private const val COMMON_METADATA_SIZE_BYTES =
        2 + FixedBytes16.SIZE_BYTES * 2 + Long.SIZE_BYTES * 2
}
