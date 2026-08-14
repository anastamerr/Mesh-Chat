package chat.mesh.simulator

import chat.mesh.engine.AcknowledgementVerifier
import chat.mesh.engine.DirectoryPacketStore
import chat.mesh.engine.MeshNode
import chat.mesh.engine.OriginResult
import chat.mesh.engine.PacketStoreLimits
import chat.mesh.engine.ReceiveResult
import chat.mesh.protocol.FixedBytes16
import chat.mesh.protocol.MessageMetadata
import chat.mesh.protocol.PacketBindingV0
import chat.mesh.protocol.PacketCodecV0
import chat.mesh.protocol.PacketType
import chat.mesh.protocol.RoutedPacket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** Test-only process boundary for the localhost mesh experiment. */
internal object ProcessNodeMain {
    private const val MAX_STORED_PACKETS = 1_000
    private const val MAX_STORED_BYTES = 4_000_000L
    private const val SOCKET_TIMEOUT_MILLIS = 5_000
    private const val MAX_PACKET_BYTES =
        PacketCodecV0.HEADER_SIZE_BYTES + PacketCodecV0.MAX_PAYLOAD_BYTES

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == ARGUMENT_COUNT) { "Unexpected process-node arguments" }
        val storePath = Path.of(args[0])
        val routingToken = FixedBytes16.from(args[1].hexToBytes())
        val nowEpochMillis = args[2].toLong()
        val startupKeys = DataInputStream(System.`in`).use(::readStartupKeys)
        val acknowledgementPublicKey = startupKeys.publicKey?.decodePublicKey()
        val acknowledgementPrivateKey = startupKeys.privateKey?.decodePrivateKey()
        val node = MeshNode(
            routingToken = routingToken,
            store = DirectoryPacketStore(
                storePath,
                PacketStoreLimits(MAX_STORED_PACKETS, MAX_STORED_BYTES),
            ),
            acknowledgementVerifier = verifier(acknowledgementPublicKey),
        )

        ServerSocket(0, 16, InetAddress.getLoopbackAddress()).use { server ->
            println("READY ${server.localPort}")
            System.out.flush()
            var running = true
            while (running) {
                server.accept().use { socket ->
                    socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    running = handle(
                        command = input.readInt(),
                        input = input,
                        output = output,
                        node = node,
                        nowEpochMillis = nowEpochMillis,
                        acknowledgementPrivateKey = acknowledgementPrivateKey,
                    )
                    output.flush()
                }
            }
        }
    }

    private fun handle(
        command: Int,
        input: DataInputStream,
        output: DataOutputStream,
        node: MeshNode,
        nowEpochMillis: Long,
        acknowledgementPrivateKey: PrivateKey?,
    ): Boolean {
        when (command) {
            Command.RECEIVE -> output.writeUTF(
                node.receive(input.readFrame(), nowEpochMillis).wireName(),
            )

            Command.ORIGINATE_AND_SEND -> {
                val targetPort = input.readInt()
                val packet = input.readRoutedPacket()
                val origin = node.originate(packet, nowEpochMillis)
                output.writeUTF(origin.wireName())
                output.writeUTF(
                    if (origin is OriginResult.Queued) {
                        send(targetPort, PacketCodecV0.encode(origin.packet))
                    } else {
                        NOT_SENT
                    },
                )
            }

            Command.OFFER_QUEUED -> {
                val targetPort = input.readInt()
                val packets = node.queuedPackets(nowEpochMillis)
                output.writeInt(packets.size)
                packets.forEach { packet ->
                    output.writeUTF(send(targetPort, PacketCodecV0.encode(packet)))
                }
            }

            Command.CREATE_ACK_AND_SEND -> {
                val signingKey = requireNotNull(acknowledgementPrivateKey) {
                    "This node has no acknowledgement signing key"
                }
                val targetPort = input.readInt()
                val messageId = FixedBytes16.from(input.readNBytesExact(FixedBytes16.SIZE_BYTES))
                val recipientToken = FixedBytes16.from(
                    input.readNBytesExact(FixedBytes16.SIZE_BYTES),
                )
                val expiresAtEpochMillis = input.readLong()
                val content = PacketBindingV0.deliveryAcknowledgementSignature(
                    MessageMetadata(
                        messageId = messageId,
                        recipientToken = recipientToken,
                        createdAtEpochMillis = nowEpochMillis,
                        expiresAtEpochMillis = expiresAtEpochMillis,
                    ),
                )
                val signature = Signature.getInstance("Ed25519").run {
                    initSign(signingKey)
                    update(content)
                    sign()
                }
                val acknowledgement = RoutedPacket(
                    type = PacketType.DELIVERY_ACK,
                    messageId = messageId,
                    recipientToken = recipientToken,
                    createdAtEpochMillis = nowEpochMillis,
                    expiresAtEpochMillis = expiresAtEpochMillis,
                    hopLimit = 2,
                    copyBudget = 1,
                    payload = signature,
                )
                val origin = node.originate(acknowledgement, nowEpochMillis)
                output.writeUTF(origin.wireName())
                output.writeUTF(
                    if (origin is OriginResult.Queued) {
                        send(targetPort, PacketCodecV0.encode(origin.packet))
                    } else {
                        NOT_SENT
                    },
                )
            }

            Command.SNAPSHOT -> {
                output.writeInt(node.queuedPackets(nowEpochMillis).size)
                output.writeInt(node.deliveries().size)
            }

            Command.DELIVERIES -> {
                val deliveries = node.deliveries()
                output.writeInt(deliveries.size)
                deliveries.forEach { output.writeFrame(PacketCodecV0.encode(it)) }
            }

            Command.CONFIRMED -> {
                val messageId = FixedBytes16.from(input.readNBytesExact(FixedBytes16.SIZE_BYTES))
                output.writeBoolean(node.isDeliveryConfirmed(messageId))
            }

            Command.STOP -> {
                output.writeUTF("STOPPED")
                return false
            }

            else -> error("Unknown command: $command")
        }
        return true
    }

    private fun send(targetPort: Int, packet: ByteArray): String = try {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort),
                SOCKET_TIMEOUT_MILLIS,
            )
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val output = DataOutputStream(socket.getOutputStream())
            output.writeInt(Command.RECEIVE)
            output.writeFrame(packet)
            output.flush()
            DataInputStream(socket.getInputStream()).readUTF()
        }
    } catch (_: java.io.IOException) {
        UNREACHABLE
    }

    private fun verifier(publicKey: PublicKey?): AcknowledgementVerifier =
        if (publicKey == null) {
            AcknowledgementVerifier { false }
        } else {
            AcknowledgementVerifier { acknowledgement ->
                try {
                    Signature.getInstance("Ed25519").run {
                        initVerify(publicKey)
                        update(
                            PacketBindingV0.deliveryAcknowledgementSignature(
                                MessageMetadata(
                                    messageId = acknowledgement.messageId,
                                    recipientToken = acknowledgement.recipientToken,
                                    createdAtEpochMillis = acknowledgement.createdAtEpochMillis,
                                    expiresAtEpochMillis = acknowledgement.expiresAtEpochMillis,
                                ),
                            ),
                        )
                        verify(acknowledgement.copyPayload())
                    }
                } catch (_: GeneralSecurityException) {
                    false
                }
            }
        }

    private fun OriginResult.wireName(): String = when (this) {
        is OriginResult.Queued -> "QUEUED"
        OriginResult.CapacityExceeded -> "CAPACITY_EXCEEDED"
        OriginResult.Conflict -> "CONFLICT"
    }

    private fun ReceiveResult.wireName(): String = when (this) {
        is ReceiveResult.Delivered -> "DELIVERED"
        ReceiveResult.Duplicate -> "DUPLICATE"
        ReceiveResult.Exhausted -> "EXHAUSTED"
        is ReceiveResult.Retained -> "RETAINED"
        is ReceiveResult.Rejected -> "REJECTED"
        ReceiveResult.CapacityExceeded -> "CAPACITY_EXCEEDED"
        ReceiveResult.Conflict -> "CONFLICT"
    }

    private fun DataInputStream.readFrame(): ByteArray {
        val length = readInt()
        require(length in 0..MAX_PACKET_BYTES) { "Invalid packet length: $length" }
        return readNBytesExact(length)
    }

    private fun DataInputStream.readRoutedPacket(): RoutedPacket =
        when (val decoded = PacketCodecV0.decode(readFrame())) {
            is chat.mesh.protocol.DecodeResult.Success -> decoded.packet
            is chat.mesh.protocol.DecodeResult.Failure -> {
                error("Invalid originated packet: ${decoded.error}")
            }
        }

    private fun DataInputStream.readNBytesExact(length: Int): ByteArray =
        ByteArray(length).also(::readFully)

    private fun DataOutputStream.writeFrame(packet: ByteArray) {
        writeInt(packet.size)
        write(packet)
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Hex value must have an even length" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun readStartupKeys(input: DataInputStream): StartupKeys = StartupKeys(
        publicKey = input.readOptionalKey(),
        privateKey = input.readOptionalKey(),
    )

    private fun DataInputStream.readOptionalKey(): ByteArray? {
        val length = readInt()
        require(length in NO_KEY_LENGTH..MAX_ENCODED_KEY_BYTES) {
            "Invalid encoded key length: $length"
        }
        return if (length == NO_KEY_LENGTH) null else readNBytesExact(length)
    }

    private fun ByteArray.decodePublicKey(): PublicKey =
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(this))

    private fun ByteArray.decodePrivateKey(): PrivateKey =
        KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(this))

    private data class StartupKeys(
        val publicKey: ByteArray?,
        val privateKey: ByteArray?,
    )

    internal object Command {
        const val RECEIVE = 1
        const val ORIGINATE_AND_SEND = 2
        const val OFFER_QUEUED = 3
        const val CREATE_ACK_AND_SEND = 4
        const val SNAPSHOT = 5
        const val DELIVERIES = 6
        const val CONFIRMED = 7
        const val STOP = 8
    }

    internal const val UNREACHABLE = "UNREACHABLE"
    private const val NOT_SENT = "NOT_SENT"
    private const val ARGUMENT_COUNT = 3
    private const val NO_KEY_LENGTH = -1
    private const val MAX_ENCODED_KEY_BYTES = 1_024
}
