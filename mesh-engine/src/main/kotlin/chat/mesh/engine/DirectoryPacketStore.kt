package chat.mesh.engine

import chat.mesh.protocol.DecodeResult
import chat.mesh.protocol.FixedBytes16
import chat.mesh.protocol.PacketCodecV0
import chat.mesh.protocol.PacketType
import chat.mesh.protocol.RoutedPacket
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * A bounded packet store using canonical packet bytes and atomic file moves.
 *
 * One instance must have one process-level owner. Atomic moves prevent partial
 * packet visibility across ordinary process termination; this class does not
 * claim durability against storage hardware failure.
 */
public class DirectoryPacketStore(
    root: Path,
    private val limits: PacketStoreLimits,
) : PacketStore {
    private val rootDirectory = root.toAbsolutePath().normalize()
    private val queuedDirectory = rootDirectory.resolve(QUEUED_DIRECTORY)
    private val deliveredDirectory = rootDirectory.resolve(DELIVERED_DIRECTORY)
    private val receiptsDirectory = rootDirectory.resolve(RECEIPTS_DIRECTORY)
    private val stateDirectories = listOf(
        queuedDirectory,
        deliveredDirectory,
        receiptsDirectory,
    )

    init {
        ensureDirectory(rootDirectory)
        stateDirectories.forEach(::ensureDirectory)
        stateDirectories.forEach(::removeIncompleteWrites)
        validateStoredPackets()
        checkUsageWithinLimits()
    }

    @Synchronized
    override fun knowledgeOf(packet: RoutedPacket): StoredPacketKnowledge {
        val key = PacketKey.from(packet)
        val ordinaryPaths = listOf(
            packetPath(queuedDirectory, key),
            packetPath(deliveredDirectory, key),
        )
        ordinaryPaths.firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }?.let { path ->
            return if (decodeStoredPacket(path).sameLogicalPacketAs(packet)) {
                StoredPacketKnowledge.KNOWN
            } else {
                StoredPacketKnowledge.CONFLICT
            }
        }

        val receipt = receiptPath(key.messageId)
        if (!Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
            return StoredPacketKnowledge.ABSENT
        }

        return if (packet.type == PacketType.PRIVATE_MESSAGE ||
            decodeStoredPacket(receipt).sameLogicalPacketAs(packet)
        ) {
            StoredPacketKnowledge.KNOWN
        } else {
            StoredPacketKnowledge.CONFLICT
        }
    }

    @Synchronized
    override fun retain(packet: RoutedPacket): StoreWriteResult =
        writePacket(packetPath(queuedDirectory, PacketKey.from(packet)), packet)

    @Synchronized
    override fun deliver(packet: RoutedPacket): StoreWriteResult =
        writePacket(packetPath(deliveredDirectory, PacketKey.from(packet)), packet)

    @Synchronized
    override fun confirmDelivery(acknowledgement: RoutedPacket): StoreWriteResult {
        require(acknowledgement.type == PacketType.DELIVERY_ACK) {
            "Delivery confirmation requires an acknowledgement packet"
        }

        val target = receiptPath(acknowledgement.messageId)
        val queuedMessage = packetPath(
            queuedDirectory,
            PacketKey(PacketType.PRIVATE_MESSAGE, acknowledgement.messageId),
        )
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(queuedMessage, LinkOption.NOFOLLOW_LINKS)
        ) {
            return StoreWriteResult.MISSING_ORIGINAL
        }
        val result = writePacket(target, acknowledgement, setOf(queuedMessage))

        if (result == StoreWriteResult.STORED || result == StoreWriteResult.DUPLICATE) {
            if (Files.deleteIfExists(queuedMessage)) {
                forceDirectory(queuedDirectory)
            }
        }

        return result
    }

    @Synchronized
    override fun queuedPackets(nowEpochMillis: Long): List<RoutedPacket> {
        require(nowEpochMillis >= 0) { "Current time cannot be negative" }
        val packets = readPackets(queuedDirectory)
        val eligible = ArrayList<RoutedPacket>(packets.size)
        var changed = false

        packets.forEach { stored ->
            val acknowledged = stored.packet.type == PacketType.PRIVATE_MESSAGE &&
                Files.exists(receiptPath(stored.packet.messageId), LinkOption.NOFOLLOW_LINKS)
            if (stored.packet.expiresAtEpochMillis <= nowEpochMillis || acknowledged) {
                Files.delete(stored.path)
                changed = true
            } else {
                eligible += stored.packet
            }
        }

        if (changed) {
            forceDirectory(queuedDirectory)
        }
        return eligible
    }

    @Synchronized
    override fun deliveredPackets(): List<RoutedPacket> =
        (readPackets(deliveredDirectory) + readPackets(receiptsDirectory))
            .sortedBy { it.path.fileName.toString() }
            .map(StoredPacket::packet)

    @Synchronized
    override fun isDeliveryConfirmed(messageId: FixedBytes16): Boolean =
        Files.exists(receiptPath(messageId), LinkOption.NOFOLLOW_LINKS)

    private fun writePacket(
        target: Path,
        packet: RoutedPacket,
        filesReplacedByWrite: Set<Path> = emptySet(),
    ): StoreWriteResult {
        val encoded = PacketCodecV0.encode(packet)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return if (Files.readAllBytes(target).contentEquals(encoded)) {
                StoreWriteResult.DUPLICATE
            } else {
                StoreWriteResult.CONFLICT
            }
        }

        val usage = calculateUsage(filesReplacedByWrite)
        if (usage.packetCount + 1 > limits.maxPackets ||
            usage.byteCount + encoded.size > limits.maxBytes
        ) {
            return StoreWriteResult.CAPACITY_EXCEEDED
        }

        writeAtomically(target, encoded)
        return StoreWriteResult.STORED
    }

    private fun writeAtomically(target: Path, encoded: ByteArray) {
        val temporary = Files.createTempFile(target.parent, TEMPORARY_PREFIX, TEMPORARY_SUFFIX)
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                val bytes = ByteBuffer.wrap(encoded)
                while (bytes.hasRemaining()) {
                    channel.write(bytes)
                }
                channel.force(true)
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            forceDirectory(target.parent)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readPackets(directory: Path): List<StoredPacket> = packetFiles(directory)
        .map { path -> StoredPacket(path, decodeStoredPacket(path)) }

    private fun decodeStoredPacket(path: Path): RoutedPacket {
        val decoded = PacketCodecV0.decode(Files.readAllBytes(path))
        if (decoded !is DecodeResult.Success) {
            throw IOException("Corrupt packet store entry: ${path.fileName}")
        }

        val expectedName = PacketKey.from(decoded.packet).fileName()
        if (path.fileName.toString() != expectedName) {
            throw IOException("Packet store key does not match contents: ${path.fileName}")
        }
        return decoded.packet
    }

    private fun validateStoredPackets() {
        stateDirectories.forEach { directory ->
            readPackets(directory).forEach { stored ->
                if (directory == receiptsDirectory && stored.packet.type != PacketType.DELIVERY_ACK) {
                    throw IOException("Receipt entry is not an acknowledgement: ${stored.path.fileName}")
                }
            }
        }
    }

    private fun checkUsageWithinLimits() {
        val usage = calculateUsage()
        if (usage.packetCount > limits.maxPackets || usage.byteCount > limits.maxBytes) {
            throw IOException("Existing packet store exceeds configured limits")
        }
    }

    private fun calculateUsage(excluded: Set<Path> = emptySet()): StoreUsage {
        var packets = 0
        var bytes = 0L
        stateDirectories.forEach { directory ->
            packetFiles(directory).forEach { path ->
                if (path !in excluded) {
                    packets += 1
                    bytes += Files.size(path)
                }
            }
        }
        return StoreUsage(packets, bytes)
    }

    private fun packetFiles(directory: Path): List<Path> =
        Files.newDirectoryStream(directory, "*$PACKET_SUFFIX").use { stream ->
            stream.map { path ->
                val normalized = path.toAbsolutePath().normalize()
                if (Files.isSymbolicLink(normalized) ||
                    !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                ) {
                    throw IOException("Packet store entry is not a regular file: ${path.fileName}")
                }
                normalized
            }
                .sortedBy { it.fileName.toString() }
        }

    private fun removeIncompleteWrites(directory: Path) {
        Files.newDirectoryStream(directory, "$TEMPORARY_PREFIX*$TEMPORARY_SUFFIX").use { stream ->
            stream.forEach(Files::deleteIfExists)
        }
    }

    private fun ensureDirectory(directory: Path) {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory) ||
                !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
            ) {
                throw IOException("Packet store path is not a real directory: $directory")
            }
        } else {
            Files.createDirectories(directory)
        }
    }

    private fun packetPath(directory: Path, key: PacketKey): Path =
        directory.resolve(key.fileName())

    private fun receiptPath(messageId: FixedBytes16): Path = packetPath(
        receiptsDirectory,
        PacketKey(PacketType.DELIVERY_ACK, messageId),
    )

    private fun PacketKey.fileName(): String =
        type.wireValue.toString(HEX_RADIX).padStart(TYPE_HEX_WIDTH, '0') +
            "-" + messageId.toString() + PACKET_SUFFIX

    /** Hop and copy budgets are mutable routing state, not message identity. */
    private fun RoutedPacket.sameLogicalPacketAs(other: RoutedPacket): Boolean =
        type == other.type &&
            messageId == other.messageId &&
            recipientToken == other.recipientToken &&
            createdAtEpochMillis == other.createdAtEpochMillis &&
            expiresAtEpochMillis == other.expiresAtEpochMillis &&
            copyPayload().contentEquals(other.copyPayload())

    private fun forceDirectory(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }

    private data class StoredPacket(val path: Path, val packet: RoutedPacket)

    private data class StoreUsage(val packetCount: Int, val byteCount: Long)

    private companion object {
        private const val QUEUED_DIRECTORY = "queued"
        private const val DELIVERED_DIRECTORY = "delivered"
        private const val RECEIPTS_DIRECTORY = "receipts"
        private const val PACKET_SUFFIX = ".packet"
        private const val TEMPORARY_PREFIX = ".write-"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val HEX_RADIX = 16
        private const val TYPE_HEX_WIDTH = 2
    }
}
