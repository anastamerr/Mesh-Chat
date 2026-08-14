package chat.mesh.simulator

import chat.mesh.crypto.MessageOpenResult
import chat.mesh.crypto.TinkMessageCrypto
import chat.mesh.protocol.DecodeResult
import chat.mesh.protocol.FixedBytes16
import chat.mesh.protocol.MessageMetadata
import chat.mesh.protocol.PacketCodecV0
import chat.mesh.protocol.PacketType
import chat.mesh.protocol.RoutedPacket
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MultiProcessMeshTest {
    private val processes = mutableListOf<NodeProcess>()
    private val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        processes.asReversed().forEach(NodeProcess::close)
        temporaryRoots.forEach { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `three processes survive partition and forced relay restart`() {
        val root = Files.createTempDirectory("mesh-process-lab-").also(temporaryRoots::add)
        val signingKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val senderCrypto = TinkMessageCrypto.generate()
        val recipientCrypto = TinkMessageCrypto.generate()
        val messageId = fixedBytes(0x40)
        val plaintext = "three operating-system processes".encodeToByteArray()
        val metadata = metadata(messageId, RECIPIENT_TOKEN)
        val message = packet(
            type = PacketType.PRIVATE_MESSAGE,
            messageId = messageId,
            recipientToken = RECIPIENT_TOKEN,
            payload = senderCrypto.sealPrivateMessage(
                metadata,
                recipientCrypto.publicIdentity,
                plaintext,
            ),
        )
        val sender = startNode(
            store = root.resolve("sender"),
            token = SENDER_TOKEN,
            acknowledgementPublicKey = signingKeys.public.encoded,
        )
        var relay = startNode(root.resolve("relay"), RELAY_TOKEN)

        assertEquals(
            SendResult(origin = "QUEUED", receive = "RETAINED"),
            sender.originateAndSend(relay.port, message),
        )
        assertEquals(NodeSnapshot(queued = 1, delivered = 0), relay.snapshot())

        val unavailablePort = unusedLoopbackPort()
        assertEquals(listOf(ProcessNodeMain.UNREACHABLE), relay.offerQueued(unavailablePort))
        assertEquals(NodeSnapshot(queued = 1, delivered = 0), relay.snapshot())

        relay.killForcibly()
        relay = startNode(root.resolve("relay"), RELAY_TOKEN)
        assertEquals(NodeSnapshot(queued = 1, delivered = 0), relay.snapshot())

        var recipient = startNode(
            store = root.resolve("recipient"),
            token = RECIPIENT_TOKEN,
            acknowledgementPrivateKey = signingKeys.private.encoded,
        )
        assertEquals(listOf("DELIVERED"), relay.offerQueued(recipient.port))
        val delivered = recipient.deliveries().single()
        val opened = assertIs<MessageOpenResult.Success>(
            recipientCrypto.openPrivateMessage(
                metadata,
                senderCrypto.publicIdentity,
                delivered.copyPayload(),
            ),
        )
        assertContentEquals(plaintext, opened.copyPlaintext())

        assertEquals(
            SendResult(origin = "QUEUED", receive = "RETAINED"),
            recipient.createAcknowledgementAndSend(
                targetPort = relay.port,
                messageId = messageId,
                recipientToken = SENDER_TOKEN,
                expiresAtEpochMillis = EXPIRES_AT_EPOCH_MILLIS,
            ),
        )
        assertFalse(sender.confirmed(messageId))
        assertEquals(listOf("DUPLICATE", "DELIVERED"), relay.offerQueued(sender.port))
        assertTrue(sender.confirmed(messageId))
        assertEquals(NodeSnapshot(queued = 0, delivered = 1), sender.snapshot())

        recipient.killForcibly()
        recipient = startNode(
            store = root.resolve("recipient"),
            token = RECIPIENT_TOKEN,
            acknowledgementPrivateKey = signingKeys.private.encoded,
        )
        assertEquals(listOf("DUPLICATE", "DUPLICATE"), relay.offerQueued(recipient.port))
        assertEquals(1, recipient.deliveries().size)
    }

    private fun startNode(
        store: Path,
        token: FixedBytes16,
        acknowledgementPublicKey: ByteArray? = null,
        acknowledgementPrivateKey: ByteArray? = null,
    ): NodeProcess = NodeProcess.start(
        store = store,
        token = token,
        nowEpochMillis = NOW_EPOCH_MILLIS,
        acknowledgementPublicKey = acknowledgementPublicKey,
        acknowledgementPrivateKey = acknowledgementPrivateKey,
    ).also(processes::add)

    private fun packet(
        type: PacketType,
        messageId: FixedBytes16,
        recipientToken: FixedBytes16,
        payload: ByteArray,
    ): RoutedPacket = RoutedPacket(
        type = type,
        messageId = messageId,
        recipientToken = recipientToken,
        createdAtEpochMillis = NOW_EPOCH_MILLIS,
        expiresAtEpochMillis = EXPIRES_AT_EPOCH_MILLIS,
        hopLimit = 2,
        copyBudget = 1,
        payload = payload,
    )

    private fun metadata(messageId: FixedBytes16, recipientToken: FixedBytes16): MessageMetadata =
        MessageMetadata(
            messageId = messageId,
            recipientToken = recipientToken,
            createdAtEpochMillis = NOW_EPOCH_MILLIS,
            expiresAtEpochMillis = EXPIRES_AT_EPOCH_MILLIS,
        )

    companion object {
        private const val NOW_EPOCH_MILLIS = 1_700_000_000_000L
        private const val EXPIRES_AT_EPOCH_MILLIS = NOW_EPOCH_MILLIS + 60_000L
        private val SENDER_TOKEN = fixedBytes(0x10)
        private val RELAY_TOKEN = fixedBytes(0x20)
        private val RECIPIENT_TOKEN = fixedBytes(0x30)
        private fun fixedBytes(start: Int): FixedBytes16 = FixedBytes16.from(
            ByteArray(FixedBytes16.SIZE_BYTES) { index -> (start + index).toByte() },
        )

        private fun unusedLoopbackPort(): Int = ServerSocket(
            0,
            1,
            InetAddress.getLoopbackAddress(),
        ).use(ServerSocket::getLocalPort)
    }
}

private class NodeProcess private constructor(
    private val process: Process,
    private val errorLog: Path,
    val port: Int,
) : AutoCloseable {
    fun originateAndSend(targetPort: Int, packet: RoutedPacket): SendResult = request { input, output ->
        output.writeInt(ProcessNodeMain.Command.ORIGINATE_AND_SEND)
        output.writeInt(targetPort)
        output.writeFrame(PacketCodecV0.encode(packet))
        output.flush()
        SendResult(origin = input.readUTF(), receive = input.readUTF())
    }

    fun offerQueued(targetPort: Int): List<String> = request { input, output ->
        output.writeInt(ProcessNodeMain.Command.OFFER_QUEUED)
        output.writeInt(targetPort)
        output.flush()
        List(input.readInt()) { input.readUTF() }
    }

    fun createAcknowledgementAndSend(
        targetPort: Int,
        messageId: FixedBytes16,
        recipientToken: FixedBytes16,
        expiresAtEpochMillis: Long,
    ): SendResult = request { input, output ->
        output.writeInt(ProcessNodeMain.Command.CREATE_ACK_AND_SEND)
        output.writeInt(targetPort)
        output.write(messageId.copyBytes())
        output.write(recipientToken.copyBytes())
        output.writeLong(expiresAtEpochMillis)
        output.flush()
        SendResult(origin = input.readUTF(), receive = input.readUTF())
    }

    fun snapshot(): NodeSnapshot = request { input, output ->
        output.writeInt(ProcessNodeMain.Command.SNAPSHOT)
        output.flush()
        NodeSnapshot(queued = input.readInt(), delivered = input.readInt())
    }

    fun deliveries(): List<RoutedPacket> = request { input, output ->
        output.writeInt(ProcessNodeMain.Command.DELIVERIES)
        output.flush()
        List(input.readInt()) {
            val decoded = PacketCodecV0.decode(input.readFrame())
            check(decoded is DecodeResult.Success) { "Child returned a malformed delivery" }
            decoded.packet
        }
    }

    fun confirmed(messageId: FixedBytes16): Boolean = request { input, output ->
        output.writeInt(ProcessNodeMain.Command.CONFIRMED)
        output.write(messageId.copyBytes())
        output.flush()
        input.readBoolean()
    }

    fun killForcibly() {
        if (process.isAlive) {
            process.destroyForcibly()
            check(process.waitFor(PROCESS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Child process did not terminate"
            }
        }
    }

    override fun close() {
        if (!process.isAlive) {
            return
        }
        runCatching {
            request<String> { input, output ->
                output.writeInt(ProcessNodeMain.Command.STOP)
                output.flush()
                input.readUTF()
            }
        }
        if (!process.waitFor(PROCESS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            killForcibly()
        }
    }

    private fun <T> request(block: (DataInputStream, DataOutputStream) -> T): T {
        check(process.isAlive) { "Child process exited: ${errorLog.readIfPresent()}" }
        return Socket().use { socket ->
            socket.connect(
                InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                SOCKET_TIMEOUT_MILLIS,
            )
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            block(
                DataInputStream(socket.getInputStream()),
                DataOutputStream(socket.getOutputStream()),
            )
        }
    }

    private fun DataInputStream.readFrame(): ByteArray {
        val length = readInt()
        check(length in 0..MAX_PACKET_BYTES) { "Invalid child packet length: $length" }
        return ByteArray(length).also(::readFully)
    }

    private fun DataOutputStream.writeFrame(packet: ByteArray) {
        writeInt(packet.size)
        write(packet)
    }

    companion object {
        private const val READY_TIMEOUT_MILLIS = 10_000L
        private const val READY_POLL_MILLIS = 10L
        private const val PROCESS_EXIT_TIMEOUT_SECONDS = 5L
        private const val SOCKET_TIMEOUT_MILLIS = 5_000
        private const val MAX_PACKET_BYTES =
            PacketCodecV0.HEADER_SIZE_BYTES + PacketCodecV0.MAX_PAYLOAD_BYTES

        fun start(
            store: Path,
            token: FixedBytes16,
            nowEpochMillis: Long,
            acknowledgementPublicKey: ByteArray?,
            acknowledgementPrivateKey: ByteArray?,
        ): NodeProcess {
            Files.createDirectories(store)
            val errorLog = store.resolve("process.stderr")
            val process = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ProcessNodeMain::class.java.name,
                store.toString(),
                token.toString(),
                nowEpochMillis.toString(),
            )
                .redirectError(errorLog.toFile())
                .start()

            return try {
                DataOutputStream(process.outputStream).use { startup ->
                    startup.writeOptionalKey(acknowledgementPublicKey)
                    startup.writeOptionalKey(acknowledgementPrivateKey)
                }
                val port = awaitReady(process, process.inputReader(), errorLog)
                NodeProcess(process, errorLog, port)
            } catch (failure: Throwable) {
                process.destroyForcibly()
                throw failure
            }
        }

        private fun awaitReady(process: Process, output: BufferedReader, errorLog: Path): Int {
            val deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                if (output.ready()) {
                    val line = output.readLine()
                    check(line.startsWith("READY ")) { "Unexpected child output: $line" }
                    return line.substringAfter("READY ").toInt()
                }
                check(process.isAlive) { "Child failed to start: ${errorLog.readIfPresent()}" }
                Thread.sleep(READY_POLL_MILLIS)
            }
            error("Child startup timed out: ${errorLog.readIfPresent()}")
        }

        private fun DataOutputStream.writeOptionalKey(key: ByteArray?) {
            if (key == null) {
                writeInt(-1)
            } else {
                writeInt(key.size)
                write(key)
            }
        }
    }
}

private data class SendResult(val origin: String, val receive: String)

private data class NodeSnapshot(val queued: Int, val delivered: Int)

private fun Path.readIfPresent(): String =
    if (Files.exists(this)) Files.readString(this) else "no error log"
