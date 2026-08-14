package chat.mesh.engine

import chat.mesh.protocol.FixedBytes16
import chat.mesh.protocol.PacketCodecV0
import chat.mesh.protocol.PacketType
import chat.mesh.protocol.RoutedPacket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MeshNodeTest {
    private val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun removeTemporaryStores() {
        temporaryRoots.forEach { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `exhausted copy does not poison a later viable copy`() {
        val node = node(RELAY_TOKEN)
        val messageId = fixedBytes(0x30)
        val exhausted = packet(messageId, hopLimit = 0)
        val viable = packet(messageId, hopLimit = 2)

        assertEquals(
            ReceiveResult.Exhausted,
            node.receive(PacketCodecV0.encode(exhausted), NOW_EPOCH_MILLIS),
        )
        assertIs<ReceiveResult.Retained>(
            node.receive(PacketCodecV0.encode(viable), NOW_EPOCH_MILLIS),
        )
    }

    @Test
    fun `invalid acknowledgement cannot confirm delivery or block a valid one`() {
        val messageId = fixedBytes(0x40)
        val node = node(
            token = SENDER_TOKEN,
            verifier = AcknowledgementVerifier { acknowledgement ->
                acknowledgement.copyPayload().contentEquals(VALID_SIGNATURE)
            },
        )
        assertIs<OriginResult.Queued>(
            node.originate(packet(messageId), NOW_EPOCH_MILLIS),
        )
        val invalid = acknowledgement(messageId, byteArrayOf(0))

        val rejected = assertIs<ReceiveResult.Rejected>(
            node.receive(PacketCodecV0.encode(invalid), NOW_EPOCH_MILLIS),
        )
        assertEquals(ReceiveRejection.UnauthenticatedAcknowledgement, rejected.reason)
        assertFalse(node.isDeliveryConfirmed(messageId))

        val valid = acknowledgement(messageId, VALID_SIGNATURE)
        assertIs<ReceiveResult.Delivered>(
            node.receive(PacketCodecV0.encode(valid), NOW_EPOCH_MILLIS),
        )
        assertTrue(node.isDeliveryConfirmed(messageId))
        assertTrue(node.queuedPackets(NOW_EPOCH_MILLIS).isEmpty())
    }

    @Test
    fun `authenticated acknowledgement cannot confirm an unknown message`() {
        val messageId = fixedBytes(0x50)
        val node = node(
            token = SENDER_TOKEN,
            verifier = AcknowledgementVerifier { true },
        )

        val result = assertIs<ReceiveResult.Rejected>(
            node.receive(
                PacketCodecV0.encode(acknowledgement(messageId, VALID_SIGNATURE)),
                NOW_EPOCH_MILLIS,
            ),
        )

        assertEquals(ReceiveRejection.UnknownAcknowledgedMessage, result.reason)
        assertFalse(node.isDeliveryConfirmed(messageId))
    }

    private fun node(
        token: FixedBytes16,
        verifier: AcknowledgementVerifier = AcknowledgementVerifier { false },
    ): MeshNode {
        val root = Files.createTempDirectory("mesh-node-")
        temporaryRoots.add(root)
        return MeshNode(
            routingToken = token,
            store = DirectoryPacketStore(
                root,
                PacketStoreLimits(maxPackets = 20, maxBytes = 20_000),
            ),
            acknowledgementVerifier = verifier,
        )
    }

    private fun packet(messageId: FixedBytes16, hopLimit: Int = 2): RoutedPacket = RoutedPacket(
        type = PacketType.PRIVATE_MESSAGE,
        messageId = messageId,
        recipientToken = RECIPIENT_TOKEN,
        createdAtEpochMillis = NOW_EPOCH_MILLIS,
        expiresAtEpochMillis = NOW_EPOCH_MILLIS + 1_000,
        hopLimit = hopLimit,
        copyBudget = 1,
        payload = byteArrayOf(1),
    )

    private fun acknowledgement(messageId: FixedBytes16, payload: ByteArray): RoutedPacket =
        RoutedPacket(
            type = PacketType.DELIVERY_ACK,
            messageId = messageId,
            recipientToken = SENDER_TOKEN,
            createdAtEpochMillis = NOW_EPOCH_MILLIS,
            expiresAtEpochMillis = NOW_EPOCH_MILLIS + 1_000,
            hopLimit = 2,
            copyBudget = 1,
            payload = payload,
        )

    companion object {
        private const val NOW_EPOCH_MILLIS = 1_700_000_000_000L
        private val VALID_SIGNATURE = byteArrayOf(7, 8, 9)
        private val SENDER_TOKEN = fixedBytes(0x00)
        private val RELAY_TOKEN = fixedBytes(0x10)
        private val RECIPIENT_TOKEN = fixedBytes(0x20)

        private fun fixedBytes(start: Int): FixedBytes16 = FixedBytes16.from(
            ByteArray(FixedBytes16.SIZE_BYTES) { index -> (start + index).toByte() },
        )
    }
}
