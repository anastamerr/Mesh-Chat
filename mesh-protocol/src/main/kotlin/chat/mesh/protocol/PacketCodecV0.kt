package chat.mesh.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Canonical v0 packet encoding.
 *
 * All multi-byte integers use network byte order (big endian). This codec is
 * deliberately dependency-free and rejects malformed input without exposing a
 * partially parsed packet.
 */
public object PacketCodecV0 {
    public const val PROTOCOL_VERSION: Int = 0
    public const val HEADER_SIZE_BYTES: Int = 60
    public const val MAX_PAYLOAD_BYTES: Int = 16 * 1024

    private const val MAGIC_FIRST: Int = 0x44 // D
    private const val MAGIC_SECOND: Int = 0x4d // M
    private const val FLAGS_NONE: Int = 0
    private const val RESERVED: Int = 0

    public fun encode(packet: RoutedPacket): ByteArray {
        val output = ByteBuffer
            .allocate(HEADER_SIZE_BYTES + packet.payloadSize)
            .order(ByteOrder.BIG_ENDIAN)

        output.put(MAGIC_FIRST.toByte())
        output.put(MAGIC_SECOND.toByte())
        output.put(PROTOCOL_VERSION.toByte())
        output.put(packet.type.wireValue.toByte())
        output.put(FLAGS_NONE.toByte())
        output.put(packet.hopLimit.toByte())
        output.put(packet.copyBudget.toByte())
        output.put(RESERVED.toByte())
        output.put(packet.messageId.copyBytes())
        output.put(packet.recipientToken.copyBytes())
        output.putLong(packet.createdAtEpochMillis)
        output.putLong(packet.expiresAtEpochMillis)
        output.putInt(packet.payloadSize)
        output.put(packet.copyPayload())

        return output.array()
    }

    public fun decode(input: ByteArray): DecodeResult {
        if (input.size < HEADER_SIZE_BYTES) {
            return DecodeResult.Failure(DecodeError.TOO_SHORT)
        }

        val buffer = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN)

        if (buffer.readUnsignedByte() != MAGIC_FIRST || buffer.readUnsignedByte() != MAGIC_SECOND) {
            return DecodeResult.Failure(DecodeError.BAD_MAGIC)
        }

        if (buffer.readUnsignedByte() != PROTOCOL_VERSION) {
            return DecodeResult.Failure(DecodeError.UNSUPPORTED_VERSION)
        }

        val type = PacketType.fromWireValue(buffer.readUnsignedByte())
            ?: return DecodeResult.Failure(DecodeError.UNKNOWN_PACKET_TYPE)

        if (buffer.readUnsignedByte() != FLAGS_NONE) {
            return DecodeResult.Failure(DecodeError.UNSUPPORTED_FLAGS)
        }

        val hopLimit = buffer.readUnsignedByte()
        val copyBudget = buffer.readUnsignedByte()

        if (buffer.readUnsignedByte() != RESERVED) {
            return DecodeResult.Failure(DecodeError.NONZERO_RESERVED_FIELD)
        }

        val messageId = ByteArray(FixedBytes16.SIZE_BYTES).also { buffer.get(it) }
        val recipientToken = ByteArray(FixedBytes16.SIZE_BYTES).also { buffer.get(it) }
        val createdAt = buffer.long
        val expiresAt = buffer.long
        val payloadSize = buffer.int

        if (payloadSize < 0 || payloadSize > MAX_PAYLOAD_BYTES) {
            return DecodeResult.Failure(DecodeError.PAYLOAD_TOO_LARGE)
        }

        if (input.size != HEADER_SIZE_BYTES + payloadSize) {
            return DecodeResult.Failure(DecodeError.LENGTH_MISMATCH)
        }

        val payload = ByteArray(payloadSize).also { buffer.get(it) }

        return try {
            DecodeResult.Success(
                RoutedPacket(
                    type = type,
                    messageId = FixedBytes16.from(messageId),
                    recipientToken = FixedBytes16.from(recipientToken),
                    createdAtEpochMillis = createdAt,
                    expiresAtEpochMillis = expiresAt,
                    hopLimit = hopLimit,
                    copyBudget = copyBudget,
                    payload = payload,
                ),
            )
        } catch (_: IllegalArgumentException) {
            DecodeResult.Failure(DecodeError.INVALID_FIELD)
        }
    }

    private fun ByteBuffer.readUnsignedByte(): Int = get().toInt() and 0xff
}

public sealed interface DecodeResult {
    public data class Success(public val packet: RoutedPacket) : DecodeResult
    public data class Failure(public val error: DecodeError) : DecodeResult
}

public enum class DecodeError {
    TOO_SHORT,
    BAD_MAGIC,
    UNSUPPORTED_VERSION,
    UNKNOWN_PACKET_TYPE,
    UNSUPPORTED_FLAGS,
    NONZERO_RESERVED_FIELD,
    PAYLOAD_TOO_LARGE,
    LENGTH_MISMATCH,
    INVALID_FIELD,
}
