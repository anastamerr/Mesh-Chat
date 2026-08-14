package chat.mesh.engine

import chat.mesh.protocol.FixedBytes16
import chat.mesh.protocol.PacketCodecV0
import chat.mesh.protocol.PacketType
import chat.mesh.protocol.RoutedPacket
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DirectoryPacketStoreTest {
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
    fun `queued ciphertext survives store reconstruction exactly`() {
        val root = temporaryRoot()
        val packet = packet(messageId = fixedBytes(0x20), payload = byteArrayOf(9, 8, 7))
        val originalBytes = PacketCodecV0.encode(packet)

        assertEquals(StoreWriteResult.STORED, store(root).retain(packet))

        val recovered = store(root).queuedPackets(NOW_EPOCH_MILLIS).single()
        assertContentEquals(originalBytes, PacketCodecV0.encode(recovered))
    }

    @Test
    fun `capacity and identity conflicts fail deterministically`() {
        val root = temporaryRoot()
        val store = DirectoryPacketStore(
            root,
            PacketStoreLimits(maxPackets = 1, maxBytes = 1_000),
        )
        val first = packet(messageId = fixedBytes(0x30), payload = byteArrayOf(1))
        val conflict = packet(messageId = first.messageId, payload = byteArrayOf(2))
        val excess = packet(messageId = fixedBytes(0x40), payload = byteArrayOf(3))

        assertEquals(StoreWriteResult.STORED, store.retain(first))
        assertEquals(StoreWriteResult.DUPLICATE, store.retain(first))
        assertEquals(StoreWriteResult.CONFLICT, store.retain(conflict))
        assertEquals(StoreWriteResult.CAPACITY_EXCEEDED, store.retain(excess))
        assertEquals(1, store.queuedPackets(NOW_EPOCH_MILLIS).size)
    }

    @Test
    fun `byte capacity is enforced before a write becomes visible`() {
        val root = temporaryRoot()
        val packet = packet(messageId = fixedBytes(0x45), payload = byteArrayOf(1, 2, 3))
        val encodedSize = PacketCodecV0.encode(packet).size.toLong()
        val store = DirectoryPacketStore(
            root,
            PacketStoreLimits(maxPackets = 10, maxBytes = encodedSize - 1),
        )

        assertEquals(StoreWriteResult.CAPACITY_EXCEEDED, store.retain(packet))
        assertTrue(store.queuedPackets(NOW_EPOCH_MILLIS).isEmpty())
    }

    @Test
    fun `expired queue entries are removed persistently`() {
        val root = temporaryRoot()
        val store = store(root)
        val packet = packet(messageId = fixedBytes(0x50), payload = byteArrayOf(1))
        assertEquals(StoreWriteResult.STORED, store.retain(packet))

        assertTrue(store.queuedPackets(packet.expiresAtEpochMillis).isEmpty())
        assertEquals(StoredPacketKnowledge.ABSENT, store(root).knowledgeOf(packet))
    }

    @Test
    fun `verified receipt persists before its private message is removed`() {
        val root = temporaryRoot()
        val store = store(root)
        val messageId = fixedBytes(0x60)
        val message = packet(messageId = messageId, payload = byteArrayOf(4))
        val acknowledgement = packet(
            type = PacketType.DELIVERY_ACK,
            messageId = messageId,
            recipientToken = SENDER_TOKEN,
            payload = byteArrayOf(5),
        )
        store.retain(message)

        assertEquals(StoreWriteResult.STORED, store.confirmDelivery(acknowledgement))

        val reconstructed = store(root)
        assertTrue(reconstructed.isDeliveryConfirmed(messageId))
        assertTrue(reconstructed.queuedPackets(NOW_EPOCH_MILLIS).isEmpty())
        assertEquals(PacketType.DELIVERY_ACK, reconstructed.deliveredPackets().single().type)
    }

    @Test
    fun `corrupt persisted packet fails store reconstruction`() {
        val root = temporaryRoot()
        store(root)
        Files.write(
            root.resolve("queued/01-00000000000000000000000000000000.packet"),
            byteArrayOf(1, 2, 3),
        )

        assertFailsWith<IOException> {
            store(root)
        }
    }

    @Test
    fun `incomplete atomic write is removed during reconstruction`() {
        val root = temporaryRoot()
        store(root)
        val incomplete = root.resolve("queued/.write-interrupted.tmp")
        Files.write(incomplete, byteArrayOf(1, 2, 3))

        store(root)

        assertFalse(Files.exists(incomplete))
    }

    @Test
    fun `symbolic link packet entry fails store reconstruction`() {
        val root = temporaryRoot()
        store(root)
        val external = root.resolve("external")
        Files.write(external, byteArrayOf(1, 2, 3))
        Files.createSymbolicLink(
            root.resolve("queued/01-00000000000000000000000000000000.packet"),
            external,
        )

        assertFailsWith<IOException> {
            store(root)
        }
    }

    private fun store(root: Path): DirectoryPacketStore = DirectoryPacketStore(
        root,
        PacketStoreLimits(maxPackets = 20, maxBytes = 20_000),
    )

    private fun temporaryRoot(): Path = Files.createTempDirectory("packet-store-").also {
        temporaryRoots.add(it)
    }

    private fun packet(
        type: PacketType = PacketType.PRIVATE_MESSAGE,
        messageId: FixedBytes16,
        recipientToken: FixedBytes16 = RECIPIENT_TOKEN,
        payload: ByteArray,
    ): RoutedPacket = RoutedPacket(
        type = type,
        messageId = messageId,
        recipientToken = recipientToken,
        createdAtEpochMillis = NOW_EPOCH_MILLIS,
        expiresAtEpochMillis = NOW_EPOCH_MILLIS + 1_000,
        hopLimit = 2,
        copyBudget = 1,
        payload = payload,
    )

    companion object {
        private const val NOW_EPOCH_MILLIS = 1_700_000_000_000L
        private val SENDER_TOKEN = fixedBytes(0x00)
        private val RECIPIENT_TOKEN = fixedBytes(0x10)

        private fun fixedBytes(start: Int): FixedBytes16 = FixedBytes16.from(
            ByteArray(FixedBytes16.SIZE_BYTES) { index -> (start + index).toByte() },
        )
    }
}
