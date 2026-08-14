package chat.mesh.engine

import chat.mesh.protocol.FixedBytes16
import chat.mesh.protocol.RoutedPacket

/** Durable state required by a mesh node. */
public interface PacketStore {
    public fun knowledgeOf(packet: RoutedPacket): StoredPacketKnowledge

    public fun retain(packet: RoutedPacket): StoreWriteResult

    public fun deliver(packet: RoutedPacket): StoreWriteResult

    /** Persists a verified acknowledgement before removing its private message. */
    public fun confirmDelivery(acknowledgement: RoutedPacket): StoreWriteResult

    /** Returns unexpired packets and removes expired queue entries. */
    public fun queuedPackets(nowEpochMillis: Long): List<RoutedPacket>

    public fun deliveredPackets(): List<RoutedPacket>

    public fun isDeliveryConfirmed(messageId: FixedBytes16): Boolean
}

public enum class StoredPacketKnowledge {
    ABSENT,
    KNOWN,
    CONFLICT,
}

public enum class StoreWriteResult {
    STORED,
    DUPLICATE,
    CONFLICT,
    CAPACITY_EXCEEDED,
    MISSING_ORIGINAL,
}

/** One hard bound shared by queued packets, deliveries, and receipts. */
public data class PacketStoreLimits(
    public val maxPackets: Int,
    public val maxBytes: Long,
) {
    init {
        require(maxPackets > 0) { "Packet capacity must be positive" }
        require(maxBytes > 0) { "Byte capacity must be positive" }
    }
}
