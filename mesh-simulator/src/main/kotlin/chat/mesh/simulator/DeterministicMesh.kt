package chat.mesh.simulator

import chat.mesh.engine.MeshNode
import chat.mesh.engine.OriginResult
import chat.mesh.engine.ReceiveResult
import chat.mesh.protocol.FixedBytes16
import chat.mesh.protocol.PacketCodecV0
import chat.mesh.protocol.RoutedPacket

/**
 * A synchronous byte-level network used to test mesh behavior without radios.
 *
 * Links are explicit and bidirectional. Packets cross each link as encoded
 * bytes, ensuring that simulations exercise the production packet codec.
 */
public class DeterministicMesh(
    nodes: Collection<MeshNode>,
    private var nowEpochMillis: Long,
) {
    private val nodesByToken = nodes.associateByTo(linkedMapOf(), MeshNode::routingToken)
    private val neighbors = nodesByToken.keys.associateWith { linkedSetOf<FixedBytes16>() }
    private val pending = ArrayDeque<PendingTransmission>()
    private val completedTransmissions = mutableListOf<Transmission>()

    init {
        require(nodesByToken.size == nodes.size) { "Node routing tokens must be unique" }
        require(nowEpochMillis >= 0) { "Simulation time cannot be negative" }
    }

    public fun connect(first: FixedBytes16, second: FixedBytes16) {
        require(first != second) { "A node cannot connect to itself" }
        requireNode(first)
        requireNode(second)
        val newLink = neighbors.getValue(first).add(second)
        neighbors.getValue(second).add(first)
        if (newLink) {
            offerQueued(first, second)
            offerQueued(second, first)
        }
    }

    public fun disconnect(first: FixedBytes16, second: FixedBytes16) {
        requireNode(first)
        requireNode(second)
        neighbors.getValue(first).remove(second)
        neighbors.getValue(second).remove(first)
    }

    /** Reconstructs a node while preserving its existing topology. */
    public fun replaceNode(node: MeshNode) {
        requireNode(node.routingToken)
        check(pending.none { it.to == node.routingToken || it.from == node.routingToken }) {
            "A node can only be replaced while it has no pending transmissions"
        }
        nodesByToken[node.routingToken] = node
    }

    public fun isConnected(first: FixedBytes16, second: FixedBytes16): Boolean =
        neighbors[first]?.contains(second) == true

    /** Persists an outbound packet before queuing it on direct links. */
    public fun originate(sender: FixedBytes16, packet: RoutedPacket): OriginResult {
        requireNode(sender)
        val result = nodesByToken.getValue(sender).originate(packet, nowEpochMillis)

        if (result is OriginResult.Queued) {
            offer(sender, result.packet, neighbors.getValue(sender))
        }
        return result
    }

    /** Processes queued transmissions in stable insertion order. */
    public fun runUntilIdle(maxTransmissions: Int = DEFAULT_MAX_TRANSMISSIONS): Int {
        require(maxTransmissions > 0) { "Transmission limit must be positive" }
        var processed = 0

        while (pending.isNotEmpty()) {
            check(processed < maxTransmissions) {
                "Simulation exceeded $maxTransmissions transmissions"
            }

            val transmission = pending.removeFirst()
            val recipient = nodesByToken.getValue(transmission.to)
            val result = recipient.receive(transmission.encodedPacket, nowEpochMillis)

            completedTransmissions += Transmission(
                from = transmission.from,
                to = transmission.to,
                outcome = result.toOutcome(),
            )
            processed += 1

            if (result is ReceiveResult.Retained) {
                offer(
                    sender = transmission.to,
                    packet = result.packet,
                    recipients = neighbors.getValue(transmission.to).filterNot {
                        it == transmission.from
                    },
                )
            }
        }

        return processed
    }

    public fun transmissions(): List<Transmission> = completedTransmissions.toList()

    public fun advanceTimeTo(epochMillis: Long) {
        require(epochMillis >= nowEpochMillis) { "Simulation time cannot move backwards" }
        nowEpochMillis = epochMillis
    }

    private fun offerQueued(sender: FixedBytes16, recipient: FixedBytes16) {
        nodesByToken.getValue(sender).queuedPackets(nowEpochMillis).forEach { packet ->
            offer(sender, packet, listOf(recipient))
        }
    }

    private fun offer(
        sender: FixedBytes16,
        packet: RoutedPacket,
        recipients: Iterable<FixedBytes16>,
    ) {
        val encoded = PacketCodecV0.encode(packet)
        recipients.asSequence().take(SINGLE_COPY).forEach { recipient ->
            pending += PendingTransmission(sender, recipient, encoded.copyOf())
        }
    }

    private fun requireNode(token: FixedBytes16) {
        require(nodesByToken.containsKey(token)) { "Unknown node: $token" }
    }

    private fun ReceiveResult.toOutcome(): TransmissionOutcome = when (this) {
        is ReceiveResult.Delivered -> TransmissionOutcome.DELIVERED
        ReceiveResult.Duplicate -> TransmissionOutcome.DUPLICATE
        ReceiveResult.Exhausted -> TransmissionOutcome.EXHAUSTED
        is ReceiveResult.Retained -> TransmissionOutcome.RETAINED
        is ReceiveResult.Rejected -> TransmissionOutcome.REJECTED
        ReceiveResult.CapacityExceeded -> TransmissionOutcome.CAPACITY_EXCEEDED
        ReceiveResult.Conflict -> TransmissionOutcome.CONFLICT
    }

    private data class PendingTransmission(
        val from: FixedBytes16,
        val to: FixedBytes16,
        val encodedPacket: ByteArray,
    )

    public companion object {
        public const val DEFAULT_MAX_TRANSMISSIONS: Int = 10_000
        private const val SINGLE_COPY: Int = 1
    }
}

/** Metadata-only evidence for one simulated link transmission. */
public data class Transmission(
    public val from: FixedBytes16,
    public val to: FixedBytes16,
    public val outcome: TransmissionOutcome,
)

public enum class TransmissionOutcome {
    DELIVERED,
    DUPLICATE,
    EXHAUSTED,
    RETAINED,
    REJECTED,
    CAPACITY_EXCEEDED,
    CONFLICT,
}
