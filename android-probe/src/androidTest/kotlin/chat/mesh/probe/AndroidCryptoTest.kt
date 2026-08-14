package chat.mesh.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mesh.crypto.DeviceKeyMaterial
import chat.mesh.crypto.MessageOpenRejection
import chat.mesh.crypto.MessageOpenResult
import chat.mesh.crypto.PublicIdentity
import chat.mesh.crypto.TinkMessageCrypto
import chat.mesh.protocol.FixedBytes16
import chat.mesh.protocol.MessageMetadata
import chat.mesh.protocol.PacketBindingV0
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Properties

@RunWith(AndroidJUnit4::class)
class AndroidCryptoTest {
    @Test
    fun frozenVectorsOpenInsideAndroidProcess() {
        val vector = loadVector()
        val identity = PublicIdentity(
            vector.required("recipient_hpke_public").hexBytes(),
            vector.required("sender_signing_public").hexBytes(),
        )
        val crypto = TinkMessageCrypto.fromKeyMaterial(
            DeviceKeyMaterial(
                vector.required("recipient_hpke_private").hexBytes(),
                identity,
                vector.required("sender_signing_private").hexBytes(),
            ),
        )

        assertArrayEquals(
            vector.required("context").hexBytes(),
            PacketBindingV0.privateMessageContext(METADATA),
        )
        listOf("swift_ciphertext", "tink_ciphertext").forEach { field ->
            val opened = crypto.openPrivateMessage(
                METADATA,
                identity,
                vector.required(field).hexBytes(),
            )
            assertTrue("$field was rejected", opened is MessageOpenResult.Success)
            assertArrayEquals(
                "swift-tink-v0".encodeToByteArray(),
                (opened as MessageOpenResult.Success).copyPlaintext(),
            )
        }

        val tampered = vector.required("swift_ciphertext").hexBytes().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        val rejected = crypto.openPrivateMessage(METADATA, identity, tampered)
        assertEquals(
            MessageOpenRejection.DECRYPTION_FAILED,
            (rejected as MessageOpenResult.Rejected).reason,
        )
    }

    @Test
    fun generatedKeysSealAndOpenInsideAndroidProcess() {
        val sender = TinkMessageCrypto.generate()
        val recipient = TinkMessageCrypto.generate()
        val plaintext = "android-runtime-v0".encodeToByteArray()

        val ciphertext = sender.sealPrivateMessage(
            METADATA,
            recipient.publicIdentity,
            plaintext,
        )
        val opened = recipient.openPrivateMessage(
            METADATA,
            sender.publicIdentity,
            ciphertext,
        )

        assertTrue(opened is MessageOpenResult.Success)
        assertArrayEquals(plaintext, (opened as MessageOpenResult.Success).copyPlaintext())
    }

    private fun loadVector(): Properties = Properties().also { properties ->
        val context = InstrumentationRegistry.getInstrumentation().context
        context.assets.open("crypto-v0.properties").use(properties::load)
    }

    private fun Properties.required(name: String): String =
        requireNotNull(getProperty(name)) { "Missing vector field: $name" }

    private fun String.hexBytes(): ByteArray = chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private companion object {
        val METADATA = MessageMetadata(
            messageId = fixedBytes(0x40),
            recipientToken = fixedBytes(0x30),
            createdAtEpochMillis = 1_700_000_000_000L,
            expiresAtEpochMillis = 1_700_000_060_000L,
        )

        fun fixedBytes(start: Int): FixedBytes16 = FixedBytes16.from(
            ByteArray(FixedBytes16.SIZE_BYTES) { index -> (start + index).toByte() },
        )
    }
}
