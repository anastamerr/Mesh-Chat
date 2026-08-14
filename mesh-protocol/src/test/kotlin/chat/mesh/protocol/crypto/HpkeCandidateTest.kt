package chat.mesh.protocol.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HybridConfig
import java.security.GeneralSecurityException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Phase 0 evaluation of the cryptographic candidate from ADR 0003.
 *
 * Tink is intentionally test-scoped. These tests validate primitive behavior;
 * they do not establish the production key-storage or cross-platform format.
 */
class HpkeCandidateTest {
    @BeforeTest
    fun registerTink() {
        HybridConfig.register()
    }

    @Test
    fun `recipient decrypts a sealed message with authenticated context`() {
        val recipient = newRecipient()
        val plaintext = "phase-zero".encodeToByteArray()
        val context = "dm:v0:private-message".encodeToByteArray()

        val ciphertext = recipient.encryptor.encrypt(plaintext, context)
        val decrypted = recipient.decryptor.decrypt(ciphertext, context)

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `wrong recipient cannot decrypt`() {
        val recipient = newRecipient()
        val wrongRecipient = newRecipient()
        val context = "dm:v0:private-message".encodeToByteArray()
        val ciphertext = recipient.encryptor.encrypt("secret".encodeToByteArray(), context)

        assertFailsWith<GeneralSecurityException> {
            wrongRecipient.decryptor.decrypt(ciphertext, context)
        }
    }

    @Test
    fun `modified ciphertext is rejected`() {
        val recipient = newRecipient()
        val context = "dm:v0:private-message".encodeToByteArray()
        val ciphertext = recipient.encryptor
            .encrypt("secret".encodeToByteArray(), context)
            .also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        assertFailsWith<GeneralSecurityException> {
            recipient.decryptor.decrypt(ciphertext, context)
        }
    }

    @Test
    fun `modified context is rejected`() {
        val recipient = newRecipient()
        val ciphertext = recipient.encryptor.encrypt(
            "secret".encodeToByteArray(),
            "dm:v0:private-message".encodeToByteArray(),
        )

        assertFailsWith<GeneralSecurityException> {
            recipient.decryptor.decrypt(
                ciphertext,
                "dm:v0:delivery-ack".encodeToByteArray(),
            )
        }
    }

    private fun newRecipient(): RecipientPrimitives {
        val privateKeys = KeysetHandle.generateNew(HPKE_PARAMETERS)
        val publicKeys = privateKeys.publicKeysetHandle

        return RecipientPrimitives(
            encryptor = publicKeys.getPrimitive(
                RegistryConfiguration.get(),
                HybridEncrypt::class.java,
            ),
            decryptor = privateKeys.getPrimitive(
                RegistryConfiguration.get(),
                HybridDecrypt::class.java,
            ),
        )
    }

    private data class RecipientPrimitives(
        val encryptor: HybridEncrypt,
        val decryptor: HybridDecrypt,
    )

    companion object {
        private val HPKE_PARAMETERS: HpkeParameters = HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .setVariant(HpkeParameters.Variant.NO_PREFIX)
            .build()
    }
}
