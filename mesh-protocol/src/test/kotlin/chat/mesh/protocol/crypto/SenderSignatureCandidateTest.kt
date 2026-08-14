package chat.mesh.protocol.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.signature.Ed25519Parameters
import com.google.crypto.tink.signature.SignatureConfig
import java.security.GeneralSecurityException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Phase 0 evaluation of Ed25519 sender authentication from ADR 0003.
 *
 * This deliberately tests primitive behavior only. Canonical signed-content
 * encoding and public-key serialization remain protocol decisions.
 */
class SenderSignatureCandidateTest {
    @BeforeTest
    fun registerTink() {
        SignatureConfig.register()
    }

    @Test
    fun `intended sender signature verifies`() {
        val sender = newSender()
        val signedContent = "dm:sender-auth:v0|message".encodeToByteArray()

        sender.verifier.verify(sender.signer.sign(signedContent), signedContent)
    }

    @Test
    fun `changed signed content is rejected`() {
        val sender = newSender()
        val signedContent = "dm:sender-auth:v0|message".encodeToByteArray()
        val signature = sender.signer.sign(signedContent)

        assertFailsWith<GeneralSecurityException> {
            sender.verifier.verify(
                signature,
                "dm:sender-auth:v0|changed".encodeToByteArray(),
            )
        }
    }

    @Test
    fun `changed signature is rejected`() {
        val sender = newSender()
        val signedContent = "dm:sender-auth:v0|message".encodeToByteArray()
        val signature = sender.signer.sign(signedContent).also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }

        assertFailsWith<GeneralSecurityException> {
            sender.verifier.verify(signature, signedContent)
        }
    }

    @Test
    fun `wrong sender key is rejected`() {
        val sender = newSender()
        val wrongSender = newSender()
        val signedContent = "dm:sender-auth:v0|message".encodeToByteArray()

        assertFailsWith<GeneralSecurityException> {
            wrongSender.verifier.verify(sender.signer.sign(signedContent), signedContent)
        }
    }

    private fun newSender(): SenderPrimitives {
        val privateKeys = KeysetHandle.generateNew(ED25519_PARAMETERS)
        val publicKeys = privateKeys.publicKeysetHandle

        return SenderPrimitives(
            signer = privateKeys.getPrimitive(
                RegistryConfiguration.get(),
                PublicKeySign::class.java,
            ),
            verifier = publicKeys.getPrimitive(
                RegistryConfiguration.get(),
                PublicKeyVerify::class.java,
            ),
        )
    }

    private data class SenderPrimitives(
        val signer: PublicKeySign,
        val verifier: PublicKeyVerify,
    )

    companion object {
        private val ED25519_PARAMETERS: Ed25519Parameters = Ed25519Parameters.create(
            Ed25519Parameters.Variant.NO_PREFIX,
        )
    }
}
