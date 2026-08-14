package chat.mesh.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame

class PacketCodecV0Test {
    @Test
    fun `encoding matches the frozen golden vector`() {
        val encoded = PacketCodecV0.encode(goldenPacket())

        assertEquals(GOLDEN_PACKET_HEX, encoded.toHex())
    }

    @Test
    fun `golden vector decodes to the expected packet`() {
        val result = PacketCodecV0.decode(GOLDEN_PACKET_HEX.hexToBytes())
        val packet = assertIs<DecodeResult.Success>(result).packet

        assertEquals(PacketType.PRIVATE_MESSAGE, packet.type)
        assertEquals("000102030405060708090a0b0c0d0e0f", packet.messageId.toString())
        assertEquals("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff", packet.recipientToken.toString())
        assertEquals(1_700_000_000_000L, packet.createdAtEpochMillis)
        assertEquals(1_700_086_400_000L, packet.expiresAtEpochMillis)
        assertEquals(3, packet.hopLimit)
        assertEquals(2, packet.copyBudget)
        assertContentEquals("hello".encodeToByteArray(), packet.copyPayload())
    }

    @Test
    fun `decode rejects each structural boundary violation`() {
        val valid = GOLDEN_PACKET_HEX.hexToBytes()

        assertDecodeError(valid.copyOf(PacketCodecV0.HEADER_SIZE_BYTES - 1), DecodeError.TOO_SHORT)
        assertDecodeError(valid.copyOf().also { it[0] = 0 }, DecodeError.BAD_MAGIC)
        assertDecodeError(valid.copyOf().also { it[2] = 1 }, DecodeError.UNSUPPORTED_VERSION)
        assertDecodeError(valid.copyOf().also { it[3] = 127 }, DecodeError.UNKNOWN_PACKET_TYPE)
        assertDecodeError(valid.copyOf().also { it[4] = 1 }, DecodeError.UNSUPPORTED_FLAGS)
        assertDecodeError(valid.copyOf().also { it[7] = 1 }, DecodeError.NONZERO_RESERVED_FIELD)
        assertDecodeError(valid.copyOf(valid.size - 1), DecodeError.LENGTH_MISMATCH)
    }

    @Test
    fun `decode rejects invalid timestamps`() {
        val invalid = GOLDEN_PACKET_HEX.hexToBytes()

        // Make expiration equal creation.
        invalid.copyInto(
            destination = invalid,
            destinationOffset = 48,
            startIndex = 40,
            endIndex = 48,
        )

        assertDecodeError(invalid, DecodeError.INVALID_FIELD)
    }

    @Test
    fun `packet owns defensive copies of mutable byte arrays`() {
        val sourcePayload = byteArrayOf(1, 2, 3)
        val packet = RoutedPacket(
            type = PacketType.PRIVATE_MESSAGE,
            messageId = FixedBytes16.from(ByteArray(16) { it.toByte() }),
            recipientToken = FixedBytes16.from(ByteArray(16) { (it + 16).toByte() }),
            createdAtEpochMillis = 1,
            expiresAtEpochMillis = 2,
            hopLimit = 1,
            copyBudget = 1,
            payload = sourcePayload,
        )

        sourcePayload[0] = 99
        val firstRead = packet.copyPayload()
        firstRead[1] = 99
        val secondRead = packet.copyPayload()

        assertNotSame(firstRead, secondRead)
        assertContentEquals(byteArrayOf(1, 2, 3), secondRead)
    }

    private fun assertDecodeError(bytes: ByteArray, expected: DecodeError) {
        val failure = assertIs<DecodeResult.Failure>(PacketCodecV0.decode(bytes))
        assertEquals(expected, failure.error)
    }

    private fun goldenPacket(): RoutedPacket = RoutedPacket(
        type = PacketType.PRIVATE_MESSAGE,
        messageId = FixedBytes16.from(ByteArray(16) { it.toByte() }),
        recipientToken = FixedBytes16.from(ByteArray(16) { (0xf0 + it).toByte() }),
        createdAtEpochMillis = 1_700_000_000_000L,
        expiresAtEpochMillis = 1_700_086_400_000L,
        hopLimit = 3,
        copyBudget = 2,
        payload = "hello".encodeToByteArray(),
    )

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    companion object {
        private const val GOLDEN_PACKET_HEX =
            "444d000100030200" +
                "000102030405060708090a0b0c0d0e0f" +
                "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff" +
                "0000018bcfe56800" +
                "0000018bd50bc400" +
                "00000005" +
                "68656c6c6f"
    }
}
