package chat.mesh.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Canonical authenticated plaintext sealed inside a private-message HPKE payload. */
public object PrivateMessageEnvelopeV0 {
    // X25519 encapsulation (32 bytes) plus the AES-GCM authentication tag (16 bytes).
    private const val HPKE_BASE_OVERHEAD_BYTES = 48

    public const val SIGNATURE_SIZE_BYTES: Int = 64
    public const val HEADER_SIZE_BYTES: Int =
        1 + PacketBindingV0.RAW_PUBLIC_KEY_SIZE_BYTES + SIGNATURE_SIZE_BYTES + Int.SIZE_BYTES
    public const val MAX_PLAINTEXT_BYTES: Int =
        PacketCodecV0.MAX_PAYLOAD_BYTES - HPKE_BASE_OVERHEAD_BYTES - HEADER_SIZE_BYTES

    public fun encode(
        senderSigningPublicKey: ByteArray,
        signature: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        require(senderSigningPublicKey.size == PacketBindingV0.RAW_PUBLIC_KEY_SIZE_BYTES) {
            "Sender signing key must be ${PacketBindingV0.RAW_PUBLIC_KEY_SIZE_BYTES} bytes"
        }
        require(signature.size == SIGNATURE_SIZE_BYTES) {
            "Ed25519 signature must be $SIGNATURE_SIZE_BYTES bytes"
        }
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) {
            "Plaintext exceeds the private-message limit"
        }

        return ByteBuffer.allocate(HEADER_SIZE_BYTES + plaintext.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(PacketCodecV0.PROTOCOL_VERSION.toByte())
            .put(senderSigningPublicKey)
            .put(signature)
            .putInt(plaintext.size)
            .put(plaintext)
            .array()
    }

    public fun decode(input: ByteArray): PrivateMessageEnvelopeDecodeResult {
        if (input.size < HEADER_SIZE_BYTES) {
            return PrivateMessageEnvelopeDecodeResult.Failure(
                PrivateMessageEnvelopeDecodeError.TOO_SHORT,
            )
        }

        val buffer = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN)
        if ((buffer.get().toInt() and 0xff) != PacketCodecV0.PROTOCOL_VERSION) {
            return PrivateMessageEnvelopeDecodeResult.Failure(
                PrivateMessageEnvelopeDecodeError.UNSUPPORTED_VERSION,
            )
        }

        val senderSigningPublicKey = ByteArray(PacketBindingV0.RAW_PUBLIC_KEY_SIZE_BYTES)
            .also(buffer::get)
        val signature = ByteArray(SIGNATURE_SIZE_BYTES).also(buffer::get)
        val plaintextSize = buffer.int
        if (plaintextSize < 0 || plaintextSize > MAX_PLAINTEXT_BYTES) {
            return PrivateMessageEnvelopeDecodeResult.Failure(
                PrivateMessageEnvelopeDecodeError.PLAINTEXT_TOO_LARGE,
            )
        }
        if (input.size != HEADER_SIZE_BYTES + plaintextSize) {
            return PrivateMessageEnvelopeDecodeResult.Failure(
                PrivateMessageEnvelopeDecodeError.LENGTH_MISMATCH,
            )
        }

        val plaintext = ByteArray(plaintextSize).also(buffer::get)
        return PrivateMessageEnvelopeDecodeResult.Success(
            AuthenticatedPrivateMessage(senderSigningPublicKey, signature, plaintext),
        )
    }
}

public class AuthenticatedPrivateMessage internal constructor(
    senderSigningPublicKey: ByteArray,
    signature: ByteArray,
    plaintext: ByteArray,
) {
    private val senderSigningPublicKeyBytes: ByteArray = senderSigningPublicKey.copyOf()
    private val signatureBytes: ByteArray = signature.copyOf()
    private val plaintextBytes: ByteArray = plaintext.copyOf()

    public fun copySenderSigningPublicKey(): ByteArray = senderSigningPublicKeyBytes.copyOf()

    public fun copySignature(): ByteArray = signatureBytes.copyOf()

    public fun copyPlaintext(): ByteArray = plaintextBytes.copyOf()
}

public sealed interface PrivateMessageEnvelopeDecodeResult {
    public data class Success(
        public val message: AuthenticatedPrivateMessage,
    ) : PrivateMessageEnvelopeDecodeResult

    public data class Failure(
        public val error: PrivateMessageEnvelopeDecodeError,
    ) : PrivateMessageEnvelopeDecodeResult
}

public enum class PrivateMessageEnvelopeDecodeError {
    TOO_SHORT,
    UNSUPPORTED_VERSION,
    PLAINTEXT_TOO_LARGE,
    LENGTH_MISMATCH,
}
