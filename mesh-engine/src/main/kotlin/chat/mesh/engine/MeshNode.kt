package chat.mesh.engine

import chat.mesh.protocol.DecodeError
import chat.mesh.protocol.DecodeResult
import chat.mesh.protocol.FixedBytes16
import chat.mesh.protocol.PacketCodecV0
import chat.mesh.protocol.PacketType
import chat.mesh.protocol.RoutedPacket

/** Verifies a delivery acknowledgement at its intended sender. */
public fun interface AcknowledgementVerifier {
    public fun verify(acknowledgement: RoutedPacket): Boolean
}

/** Transport-independent receive and durable store-and-forward behavior. */
public class MeshNode(
    public val routingToken: FixedBytes16,
    private val store: PacketStore,
    private val acknowledgementVerifier: AcknowledgementVerifier = REJECT_ACKNOWLEDGEMENTS,
) {
    public fun originate(packet: RoutedPacket, nowEpochMillis: Long): OriginResult {
        require(packet.createdAtEpochMillis <= nowEpochMillis) {
            "Packet creation time is in the future"
        }
        require(packet.expiresAtEpochMillis > nowEpochMillis) {
            "Cannot originate an expired packet"
        }

        return when (val result = store.retain(packet)) {
            StoreWriteResult.STORED,
            StoreWriteResult.DUPLICATE,
            -> OriginResult.Queued(packet, result)

            StoreWriteResult.CAPACITY_EXCEEDED -> OriginResult.CapacityExceeded
            StoreWriteResult.CONFLICT -> OriginResult.Conflict
            StoreWriteResult.MISSING_ORIGINAL -> error(
                "Retaining a packet cannot require an original message",
            )
        }
    }

    public fun receive(encodedPacket: ByteArray, nowEpochMillis: Long): ReceiveResult {
        val packet = when (val decoded = PacketCodecV0.decode(encodedPacket)) {
            is DecodeResult.Failure -> {
                return ReceiveResult.Rejected(ReceiveRejection.Malformed(decoded.error))
            }
            is DecodeResult.Success -> decoded.packet
        }

        if (packet.expiresAtEpochMillis <= nowEpochMillis) {
            return ReceiveResult.Rejected(ReceiveRejection.Expired)
        }

        when (store.knowledgeOf(packet)) {
            StoredPacketKnowledge.KNOWN -> return ReceiveResult.Duplicate
            StoredPacketKnowledge.CONFLICT -> return ReceiveResult.Conflict
            StoredPacketKnowledge.ABSENT -> Unit
        }

        if (packet.recipientToken == routingToken) {
            return receiveForThisNode(packet)
        }

        if (packet.hopLimit == 0 || packet.copyBudget == 0) {
            return ReceiveResult.Exhausted
        }

        val forwardable = packet.withForwardingBudgetConsumed()
        return when (store.retain(forwardable)) {
            StoreWriteResult.STORED -> ReceiveResult.Retained(forwardable)
            StoreWriteResult.DUPLICATE -> ReceiveResult.Duplicate
            StoreWriteResult.CAPACITY_EXCEEDED -> ReceiveResult.CapacityExceeded
            StoreWriteResult.CONFLICT -> ReceiveResult.Conflict
            StoreWriteResult.MISSING_ORIGINAL -> error(
                "Retaining a packet cannot require an original message",
            )
        }
    }

    public fun queuedPackets(nowEpochMillis: Long): List<RoutedPacket> =
        store.queuedPackets(nowEpochMillis)

    public fun deliveries(): List<RoutedPacket> = store.deliveredPackets()

    public fun isDeliveryConfirmed(messageId: FixedBytes16): Boolean =
        store.isDeliveryConfirmed(messageId)

    private fun receiveForThisNode(packet: RoutedPacket): ReceiveResult {
        if (packet.type == PacketType.DELIVERY_ACK && !acknowledgementVerifier.verify(packet)) {
            return ReceiveResult.Rejected(ReceiveRejection.UnauthenticatedAcknowledgement)
        }

        val result = if (packet.type == PacketType.DELIVERY_ACK) {
            store.confirmDelivery(packet)
        } else {
            store.deliver(packet)
        }

        return when (result) {
            StoreWriteResult.STORED -> ReceiveResult.Delivered(packet)
            StoreWriteResult.DUPLICATE -> ReceiveResult.Duplicate
            StoreWriteResult.CAPACITY_EXCEEDED -> ReceiveResult.CapacityExceeded
            StoreWriteResult.CONFLICT -> ReceiveResult.Conflict
            StoreWriteResult.MISSING_ORIGINAL -> ReceiveResult.Rejected(
                ReceiveRejection.UnknownAcknowledgedMessage,
            )
        }
    }

    private fun RoutedPacket.withForwardingBudgetConsumed(): RoutedPacket = RoutedPacket(
        type = type,
        messageId = messageId,
        recipientToken = recipientToken,
        createdAtEpochMillis = createdAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
        hopLimit = hopLimit - 1,
        copyBudget = copyBudget - 1,
        payload = copyPayload(),
    )

    public companion object {
        private val REJECT_ACKNOWLEDGEMENTS: AcknowledgementVerifier =
            AcknowledgementVerifier { false }
    }
}

public sealed interface OriginResult {
    public data class Queued(
        public val packet: RoutedPacket,
        public val writeResult: StoreWriteResult,
    ) : OriginResult

    public data object CapacityExceeded : OriginResult
    public data object Conflict : OriginResult
}

public sealed interface ReceiveResult {
    public data class Retained(public val packet: RoutedPacket) : ReceiveResult
    public data class Delivered(public val packet: RoutedPacket) : ReceiveResult
    public data class Rejected(public val reason: ReceiveRejection) : ReceiveResult
    public data object Duplicate : ReceiveResult
    public data object Exhausted : ReceiveResult
    public data object CapacityExceeded : ReceiveResult
    public data object Conflict : ReceiveResult
}

public sealed interface ReceiveRejection {
    public data class Malformed(public val error: DecodeError) : ReceiveRejection
    public data object Expired : ReceiveRejection
    public data object UnauthenticatedAcknowledgement : ReceiveRejection
    public data object UnknownAcknowledgedMessage : ReceiveRejection
}
