package chat.mesh.engine

import chat.mesh.protocol.FixedBytes16
import chat.mesh.protocol.PacketType
import chat.mesh.protocol.RoutedPacket

/** The deduplication identity of one routed packet. */
public data class PacketKey(
    public val type: PacketType,
    public val messageId: FixedBytes16,
) {
    public companion object {
        public fun from(packet: RoutedPacket): PacketKey = PacketKey(
            type = packet.type,
            messageId = packet.messageId,
        )
    }
}
