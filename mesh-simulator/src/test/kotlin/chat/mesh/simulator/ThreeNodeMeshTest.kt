package chat.mesh.simulator

import chat.mesh.engine.AcknowledgementVerifier
import chat.mesh.engine.DirectoryPacketStore
import chat.mesh.engine.MeshNode
import chat.mesh.engine.PacketStoreLimits
import chat.mesh.engine.ReceiveRejection
import chat.mesh.engine.ReceiveResult
import chat.mesh.protocol.FixedBytes16
import chat.mesh.protocol.PacketCodecV0
import chat.mesh.protocol.PacketType
import chat.mesh.protocol.RoutedPacket
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.Ed25519Parameters
import com.google.crypto.tink.signature.SignatureConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ThreeNodeMeshTest {
    private val temporaryRoots = mutableListOf<Path>()

    @BeforeTest
    fun registerCryptography() {
        HybridConfig.register()
        SignatureConfig.register()
    }

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
        val relayIdentity = TestIdentity()
        val recipientIdentity = TestIdentity()
        val fixture = threeNodeMesh(
            senderVerifier = acknowledgementVerifier(recipientIdentity),
        )
        val messageId = fixedBytes(0x40)
        val plaintext = "offline through one relay".encodeToByteArray()
        val context = packetContext(messageId)
        val ciphertext = recipientIdentity.encryptor.encrypt(plaintext, context)

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
        assertContentEquals(
            plaintext,
            recipientIdentity.decryptor.decrypt(deliveredMessage.copyPayload(), context),
        )
        assertFailsWith<GeneralSecurityException> {
            relayIdentity.decryptor.decrypt(deliveredMessage.copyPayload(), context)
        }

        val forgedAcknowledgement = packet(
            type = PacketType.DELIVERY_ACK,
            messageId = messageId,
            recipientToken = SENDER_TOKEN,
            payload = relayIdentity.signer.sign(
                acknowledgementContent(messageId),
            ),
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
            payload = recipientIdentity.signer.sign(acknowledgementContent(messageId)),
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
        val relayIdentity = TestIdentity()
        val recipientIdentity = TestIdentity()
        val fixture = threeNodeMesh(
            senderVerifier = acknowledgementVerifier(recipientIdentity),
            connectRecipient = false,
        )
        val messageId = fixedBytes(0x60)
        val plaintext = "survives relay reconstruction".encodeToByteArray()
        val context = packetContext(messageId)
        fixture.mesh.originate(
            SENDER_TOKEN,
            packet(
                PacketType.PRIVATE_MESSAGE,
                messageId,
                RECIPIENT_TOKEN,
                recipientIdentity.encryptor.encrypt(plaintext, context),
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
        assertContentEquals(
            plaintext,
            recipientIdentity.decryptor.decrypt(delivered.copyPayload(), context),
        )
        assertFailsWith<GeneralSecurityException> {
            relayIdentity.decryptor.decrypt(delivered.copyPayload(), context)
        }

        fixture.mesh.originate(
            RECIPIENT_TOKEN,
            packet(
                PacketType.DELIVERY_ACK,
                messageId,
                SENDER_TOKEN,
                recipientIdentity.signer.sign(acknowledgementContent(messageId)),
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
        val recipientIdentity = TestIdentity()
        val fixture = threeNodeMesh(connectRecipient = false)
        repeat(MESSAGE_COUNT) { index ->
            val messageId = indexedMessageId(index)
            fixture.mesh.originate(
                SENDER_TOKEN,
                packet(
                    PacketType.PRIVATE_MESSAGE,
                    messageId,
                    RECIPIENT_TOKEN,
                    recipientIdentity.encryptor.encrypt(
                        "message-$index".encodeToByteArray(),
                        packetContext(messageId),
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
            assertContentEquals(
                "message-$index".encodeToByteArray(),
                recipientIdentity.decryptor.decrypt(
                    delivered.copyPayload(),
                    packetContext(delivered.messageId),
                ),
            )
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

    private fun acknowledgementVerifier(identity: TestIdentity): AcknowledgementVerifier =
        AcknowledgementVerifier { acknowledgement ->
            try {
                identity.verifier.verify(
                    acknowledgement.copyPayload(),
                    acknowledgementContent(
                        messageId = acknowledgement.messageId,
                        recipientToken = acknowledgement.recipientToken,
                        createdAtEpochMillis = acknowledgement.createdAtEpochMillis,
                        expiresAtEpochMillis = acknowledgement.expiresAtEpochMillis,
                    ),
                )
                true
            } catch (_: GeneralSecurityException) {
                false
            }
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

    private fun packetContext(messageId: FixedBytes16): ByteArray =
        ByteBuffer.allocate(1 + FixedBytes16.SIZE_BYTES * 2 + Long.SIZE_BYTES * 2)
            .order(ByteOrder.BIG_ENDIAN)
            .put(PacketType.PRIVATE_MESSAGE.wireValue.toByte())
            .put(messageId.copyBytes())
            .put(RECIPIENT_TOKEN.copyBytes())
            .putLong(NOW_EPOCH_MILLIS)
            .putLong(NOW_EPOCH_MILLIS + ONE_MINUTE_MILLIS)
            .array()

    private fun acknowledgementContent(
        messageId: FixedBytes16,
        recipientToken: FixedBytes16 = SENDER_TOKEN,
        createdAtEpochMillis: Long = NOW_EPOCH_MILLIS,
        expiresAtEpochMillis: Long = NOW_EPOCH_MILLIS + ONE_MINUTE_MILLIS,
    ): ByteArray = ByteBuffer
        .allocate(
            ACKNOWLEDGEMENT_DOMAIN.size +
                2 + FixedBytes16.SIZE_BYTES * 2 + Long.SIZE_BYTES * 2,
        )
        .order(ByteOrder.BIG_ENDIAN)
        .put(ACKNOWLEDGEMENT_DOMAIN)
        .put(0.toByte())
        .put(PacketType.DELIVERY_ACK.wireValue.toByte())
        .put(messageId.copyBytes())
        .put(recipientToken.copyBytes())
        .putLong(createdAtEpochMillis)
        .putLong(expiresAtEpochMillis)
        .array()

    private class TestIdentity {
        private val encryptionPrivateKeys = KeysetHandle.generateNew(HPKE_PARAMETERS)
        private val signingPrivateKeys = KeysetHandle.generateNew(ED25519_PARAMETERS)

        val encryptor: HybridEncrypt = encryptionPrivateKeys.publicKeysetHandle.getPrimitive(
            RegistryConfiguration.get(),
            HybridEncrypt::class.java,
        )
        val decryptor: HybridDecrypt = encryptionPrivateKeys.getPrimitive(
            RegistryConfiguration.get(),
            HybridDecrypt::class.java,
        )
        val signer: PublicKeySign = signingPrivateKeys.getPrimitive(
            RegistryConfiguration.get(),
            PublicKeySign::class.java,
        )
        val verifier: PublicKeyVerify = signingPrivateKeys.publicKeysetHandle.getPrimitive(
            RegistryConfiguration.get(),
            PublicKeyVerify::class.java,
        )
    }

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
        private val ACKNOWLEDGEMENT_DOMAIN = "dm:delivery-ack:v0|".encodeToByteArray()
        private val HPKE_PARAMETERS: HpkeParameters = HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .setVariant(HpkeParameters.Variant.NO_PREFIX)
            .build()
        private val ED25519_PARAMETERS: Ed25519Parameters = Ed25519Parameters.create(
            Ed25519Parameters.Variant.NO_PREFIX,
        )

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
