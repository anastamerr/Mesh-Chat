package chat.mesh.protocol

/**
 * A validated logical packet. Transport fragmentation is intentionally outside
 * this type so the same packet can move over BLE, Wi-Fi, or a future gateway.
 */
public class RoutedPacket(
    public val type: PacketType,
    public val messageId: FixedBytes16,
    public val recipientToken: FixedBytes16,
    public val createdAtEpochMillis: Long,
    public val expiresAtEpochMillis: Long,
    public val hopLimit: Int,
    public val copyBudget: Int,
    payload: ByteArray,
) {
    private val payloadBytes = payload.copyOf()

    public val payloadSize: Int
        get() = payloadBytes.size

    init {
        require(createdAtEpochMillis >= 0) { "Creation time cannot be negative" }
        require(expiresAtEpochMillis > createdAtEpochMillis) {
            "Expiration must be later than creation"
        }
        require(hopLimit in 0..MAX_UNSIGNED_BYTE) { "Hop limit must fit in one byte" }
        require(copyBudget in 0..MAX_UNSIGNED_BYTE) { "Copy budget must fit in one byte" }
        require(payloadBytes.size <= PacketCodecV0.MAX_PAYLOAD_BYTES) {
            "Payload exceeds the protocol limit"
        }
    }

    public fun copyPayload(): ByteArray = payloadBytes.copyOf()

    public companion object {
        private const val MAX_UNSIGNED_BYTE = 255
    }
}
