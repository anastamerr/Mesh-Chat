package chat.mesh.simulator

import chat.mesh.crypto.MessageCrypto
import chat.mesh.crypto.MessageOpenRejection
import chat.mesh.crypto.MessageOpenResult
import chat.mesh.crypto.PublicIdentity
import chat.mesh.crypto.TinkMessageCrypto
import chat.mesh.engine.AcknowledgementVerifier
import chat.mesh.engine.DirectoryPacketStore
import chat.mesh.engine.MeshNode
import chat.mesh.engine.PacketStoreLimits
import chat.mesh.engine.ReceiveRejection
import chat.mesh.engine.ReceiveResult
import chat.mesh.protocol.FixedBytes16
import chat.mesh.protocol.MessageMetadata
import chat.mesh.protocol.PacketCodecV0
import chat.mesh.protocol.PacketType
import chat.mesh.protocol.RoutedPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ThreeNodeMeshTest {
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
    fun `sealed message and authenticated acknowledgement cross the required relay`() {
        val senderIdentity = TinkMessageCrypto.generate()
        val relayIdentity = TinkMessageCrypto.generate()
        val recipientIdentity = TinkMessageCrypto.generate()
        val fixture = threeNodeMesh(
            senderVerifier = acknowledgementVerifier(
                senderIdentity,
                recipientIdentity.publicIdentity,
            ),
        )
        val messageId = fixedBytes(0x40)
        val plaintext = "offline through one relay".encodeToByteArray()
        val metadata = metadata(messageId, RECIPIENT_TOKEN)
        val ciphertext = senderIdentity.sealPrivateMessage(
            metadata,
            recipientIdentity.publicIdentity,
            plaintext,
        )

        fixture.mesh.originate(
            SENDER_TOKEN,
            packet(PacketType.PRIVATE_MESSAGE, messageId, RECIPIENT_TOKEN, ciphertext),
        )

        assertEquals(2, fixture.mesh.runUntilIdle())
        assertFalse(fixture.mesh.isConnected(SENDER_TOKEN, RECIPIENT_TOKEN))
        assertEquals(
            listOf(
                Transmission(SENDER_TOKEN, RELAY_TOKEN, TransmissionOutcome.RETAINED),
                Transmission(RELAY_TOKEN, RECIPIENT_TOKEN, TransmissionOutcome.DELIVERED),
            ),
            fixture.mesh.transmissions(),
        )
        assertTrue(fixture.relay.deliveries().isEmpty())

        val deliveredMessage = fixture.recipient.deliveries().single()
        val opened = assertIs<MessageOpenResult.Success>(
            recipientIdentity.openPrivateMessage(
                metadata,
                senderIdentity.publicIdentity,
                deliveredMessage.copyPayload(),
            ),
        )
        assertContentEquals(plaintext, opened.copyPlaintext())
        assertEquals(
            MessageOpenResult.Rejected(MessageOpenRejection.DECRYPTION_FAILED),
            relayIdentity.openPrivateMessage(
                metadata,
                senderIdentity.publicIdentity,
                deliveredMessage.copyPayload(),
            ),
        )

        val forgedAcknowledgement = packet(
            type = PacketType.DELIVERY_ACK,
            messageId = messageId,
            recipientToken = SENDER_TOKEN,
            payload = relayIdentity.signDeliveryAcknowledgement(metadata(messageId, SENDER_TOKEN)),
        )
        val forgedResult = assertIs<ReceiveResult.Rejected>(
            fixture.sender.receive(
                PacketCodecV0.encode(forgedAcknowledgement),
                NOW_EPOCH_MILLIS,
            ),
        )
        assertEquals(ReceiveRejection.UnauthenticatedAcknowledgement, forgedResult.reason)
        assertFalse(fixture.sender.isDeliveryConfirmed(messageId))

        val acknowledgement = packet(
            type = PacketType.DELIVERY_ACK,
            messageId = messageId,
            recipientToken = SENDER_TOKEN,
            payload = recipientIdentity.signDeliveryAcknowledgement(
                metadata(messageId, SENDER_TOKEN),
            ),
        )
        fixture.mesh.originate(RECIPIENT_TOKEN, acknowledgement)

        assertEquals(2, fixture.mesh.runUntilIdle())
        assertTrue(fixture.sender.isDeliveryConfirmed(messageId))
        assertEquals(
            listOf(
                Transmission(RECIPIENT_TOKEN, RELAY_TOKEN, TransmissionOutcome.RETAINED),
                Transmission(RELAY_TOKEN, SENDER_TOKEN, TransmissionOutcome.DELIVERED),
            ),
            fixture.mesh.transmissions().takeLast(2),
        )
    }

    @Test
    fun `recipient persists one delivery across retries`() {
        val fixture = threeNodeMesh()
        val message = packet(
            PacketType.PRIVATE_MESSAGE,
            fixedBytes(0x50),
            RECIPIENT_TOKEN,
            byteArrayOf(1, 2, 3),
        )

        fixture.mesh.originate(SENDER_TOKEN, message)
        fixture.mesh.runUntilIdle()
        fixture.mesh.originate(SENDER_TOKEN, message)
        fixture.mesh.runUntilIdle()

        assertEquals(1, fixture.recipient.deliveries().size)
        assertEquals(TransmissionOutcome.DUPLICATE, fixture.mesh.transmissions().last().outcome)
    }

    @Test
    fun `offline relay recovers from disk and later completes authenticated delivery`() {
        val senderIdentity = TinkMessageCrypto.generate()
        val relayIdentity = TinkMessageCrypto.generate()
        val recipientIdentity = TinkMessageCrypto.generate()
        val fixture = threeNodeMesh(
            senderVerifier = acknowledgementVerifier(
                senderIdentity,
                recipientIdentity.publicIdentity,
            ),
            connectRecipient = false,
        )
        val messageId = fixedBytes(0x60)
        val plaintext = "survives relay reconstruction".encodeToByteArray()
        val metadata = metadata(messageId, RECIPIENT_TOKEN)
        fixture.mesh.originate(
            SENDER_TOKEN,
            packet(
                PacketType.PRIVATE_MESSAGE,
                messageId,
                RECIPIENT_TOKEN,
                senderIdentity.sealPrivateMessage(
                    metadata,
                    recipientIdentity.publicIdentity,
                    plaintext,
                ),
            ),
        )

        assertEquals(1, fixture.mesh.runUntilIdle())
        val storedBeforeRestart = PacketCodecV0.encode(
            fixture.relay.queuedPackets(NOW_EPOCH_MILLIS).single(),
        )
        val reconstructedRelay = node(RELAY_TOKEN, fixture.root.resolve("relay"))
        assertContentEquals(
            storedBeforeRestart,
            PacketCodecV0.encode(reconstructedRelay.queuedPackets(NOW_EPOCH_MILLIS).single()),
        )
        fixture.mesh.replaceNode(reconstructedRelay)

        fixture.mesh.connect(RELAY_TOKEN, RECIPIENT_TOKEN)
        assertEquals(1, fixture.mesh.runUntilIdle())
        val delivered = fixture.recipient.deliveries().single()
        val opened = assertIs<MessageOpenResult.Success>(
            recipientIdentity.openPrivateMessage(
                metadata,
                senderIdentity.publicIdentity,
                delivered.copyPayload(),
            ),
        )
        assertContentEquals(plaintext, opened.copyPlaintext())
        assertEquals(
            MessageOpenResult.Rejected(MessageOpenRejection.DECRYPTION_FAILED),
            relayIdentity.openPrivateMessage(
                metadata,
                senderIdentity.publicIdentity,
                delivered.copyPayload(),
            ),
        )

        fixture.mesh.originate(
            RECIPIENT_TOKEN,
            packet(
                PacketType.DELIVERY_ACK,
                messageId,
                SENDER_TOKEN,
                recipientIdentity.signDeliveryAcknowledgement(metadata(messageId, SENDER_TOKEN)),
            ),
        )
        assertEquals(2, fixture.mesh.runUntilIdle())
        assertTrue(fixture.sender.isDeliveryConfirmed(messageId))
        assertTrue(fixture.sender.queuedPackets(NOW_EPOCH_MILLIS).isEmpty())
    }

    @Test
    fun `recipient reconstruction preserves exactly-once display`() {
        val fixture = threeNodeMesh()
        val message = packet(
            PacketType.PRIVATE_MESSAGE,
            fixedBytes(0x70),
            RECIPIENT_TOKEN,
            byteArrayOf(1, 2, 3),
        )
        fixture.mesh.originate(SENDER_TOKEN, message)
        fixture.mesh.runUntilIdle()
        fixture.mesh.disconnect(RELAY_TOKEN, RECIPIENT_TOKEN)

        val reconstructedRecipient = node(
            RECIPIENT_TOKEN,
            fixture.root.resolve("recipient"),
        )
        fixture.mesh.replaceNode(reconstructedRecipient)
        fixture.mesh.connect(RELAY_TOKEN, RECIPIENT_TOKEN)

        assertEquals(1, fixture.mesh.runUntilIdle())
        assertEquals(1, reconstructedRecipient.deliveries().size)
        assertEquals(TransmissionOutcome.DUPLICATE, fixture.mesh.transmissions().last().outcome)
    }

    @Test
    fun `one hundred messages survive an offline relay queue`() {
        val senderIdentity = TinkMessageCrypto.generate()
        val recipientIdentity = TinkMessageCrypto.generate()
        val fixture = threeNodeMesh(connectRecipient = false)
        repeat(MESSAGE_COUNT) { index ->
            val messageId = indexedMessageId(index)
            fixture.mesh.originate(
                SENDER_TOKEN,
                packet(
                    PacketType.PRIVATE_MESSAGE,
                    messageId,
                    RECIPIENT_TOKEN,
                    senderIdentity.sealPrivateMessage(
                        metadata(messageId, RECIPIENT_TOKEN),
                        recipientIdentity.publicIdentity,
                        "message-$index".encodeToByteArray(),
                    ),
                ),
            )
            assertEquals(1, fixture.mesh.runUntilIdle())
        }
        assertEquals(MESSAGE_COUNT, fixture.relay.queuedPackets(NOW_EPOCH_MILLIS).size)

        fixture.mesh.connect(RELAY_TOKEN, RECIPIENT_TOKEN)

        assertEquals(MESSAGE_COUNT, fixture.mesh.runUntilIdle())
        assertEquals(MESSAGE_COUNT, fixture.recipient.deliveries().size)
        assertEquals(
            MESSAGE_COUNT,
            fixture.recipient.deliveries().map { it.messageId }.distinct().size,
        )
        fixture.recipient.deliveries().forEachIndexed { index, delivered ->
            val opened = assertIs<MessageOpenResult.Success>(
                recipientIdentity.openPrivateMessage(
                    metadata(delivered.messageId, RECIPIENT_TOKEN),
                    senderIdentity.publicIdentity,
                    delivered.copyPayload(),
                ),
            )
            assertContentEquals("message-$index".encodeToByteArray(), opened.copyPlaintext())
        }
    }

    private fun threeNodeMesh(
        senderVerifier: AcknowledgementVerifier = AcknowledgementVerifier { false },
        connectRecipient: Boolean = true,
    ): MeshFixture {
        val root = Files.createTempDirectory("mesh-simulator-")
        temporaryRoots.add(root)
        val sender = node(SENDER_TOKEN, root.resolve("sender"), senderVerifier)
        val relay = node(RELAY_TOKEN, root.resolve("relay"))
        val recipient = node(RECIPIENT_TOKEN, root.resolve("recipient"))
        val mesh = DeterministicMesh(
            nodes = listOf(sender, relay, recipient),
            nowEpochMillis = NOW_EPOCH_MILLIS,
        )
        mesh.connect(SENDER_TOKEN, RELAY_TOKEN)
        if (connectRecipient) {
            mesh.connect(RELAY_TOKEN, RECIPIENT_TOKEN)
        }
        return MeshFixture(mesh, sender, relay, recipient, root)
    }

    private fun node(
        token: FixedBytes16,
        directory: Path,
        verifier: AcknowledgementVerifier = AcknowledgementVerifier { false },
    ): MeshNode = MeshNode(
        routingToken = token,
        store = DirectoryPacketStore(directory, DEFAULT_LIMITS),
        acknowledgementVerifier = verifier,
    )

    private fun acknowledgementVerifier(
        verifier: MessageCrypto,
        expectedSigner: PublicIdentity,
    ): AcknowledgementVerifier =
        AcknowledgementVerifier { acknowledgement ->
            verifier.verifyDeliveryAcknowledgement(
                metadata = MessageMetadata(
                    messageId = acknowledgement.messageId,
                    recipientToken = acknowledgement.recipientToken,
                    createdAtEpochMillis = acknowledgement.createdAtEpochMillis,
                    expiresAtEpochMillis = acknowledgement.expiresAtEpochMillis,
                ),
                expectedSigner = expectedSigner,
                signature = acknowledgement.copyPayload(),
            )
        }

    private fun packet(
        type: PacketType,
        messageId: FixedBytes16,
        recipientToken: FixedBytes16,
        payload: ByteArray,
        hopLimit: Int = 2,
    ): RoutedPacket = RoutedPacket(
        type = type,
        messageId = messageId,
        recipientToken = recipientToken,
        createdAtEpochMillis = NOW_EPOCH_MILLIS,
        expiresAtEpochMillis = NOW_EPOCH_MILLIS + ONE_MINUTE_MILLIS,
        hopLimit = hopLimit,
        copyBudget = 1,
        payload = payload,
    )

    private fun metadata(
        messageId: FixedBytes16,
        recipientToken: FixedBytes16,
        createdAtEpochMillis: Long = NOW_EPOCH_MILLIS,
        expiresAtEpochMillis: Long = NOW_EPOCH_MILLIS + ONE_MINUTE_MILLIS,
    ): MessageMetadata = MessageMetadata(
        messageId = messageId,
        recipientToken = recipientToken,
        createdAtEpochMillis = createdAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )

    private data class MeshFixture(
        val mesh: DeterministicMesh,
        val sender: MeshNode,
        val relay: MeshNode,
        val recipient: MeshNode,
        val root: Path,
    )

    companion object {
        private const val NOW_EPOCH_MILLIS = 1_700_000_000_000L
        private const val ONE_MINUTE_MILLIS = 60_000L
        private const val MESSAGE_COUNT = 100
        private val SENDER_TOKEN = fixedBytes(0x10)
        private val RELAY_TOKEN = fixedBytes(0x20)
        private val RECIPIENT_TOKEN = fixedBytes(0x30)
        private val DEFAULT_LIMITS = PacketStoreLimits(maxPackets = 1_000, maxBytes = 1_000_000)
        private fun fixedBytes(start: Int): FixedBytes16 = FixedBytes16.from(
            ByteArray(FixedBytes16.SIZE_BYTES) { index -> (start + index).toByte() },
        )

        private fun indexedMessageId(index: Int): FixedBytes16 = FixedBytes16.from(
            ByteBuffer.allocate(FixedBytes16.SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(0L)
                .putLong(index.toLong())
                .array(),
        )
    }
}
