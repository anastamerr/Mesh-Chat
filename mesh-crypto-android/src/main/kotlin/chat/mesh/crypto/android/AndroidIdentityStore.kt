package chat.mesh.crypto.android

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import chat.mesh.crypto.DeviceKeyMaterial
import chat.mesh.crypto.PublicIdentity
import chat.mesh.crypto.TinkMessageCrypto
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** Protects one device identity at rest with a non-exportable Android Keystore key. */
public class AndroidIdentityStore(context: Context) {
    private val identityFile = AtomicFile(context.noBackupFilesDir.resolve(IDENTITY_FILE_NAME))

    public fun loadOrCreate(): DeviceKeyMaterial = synchronized(STORE_LOCK) {
        val fileExists = identityFile.baseFile.exists()
        val keyStore = loadKeyStore()
        val masterKey = keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
            ?: if (fileExists) {
                throw GeneralSecurityException("Identity master key is missing")
            } else {
                generateMasterKey()
            }

        if (fileExists) {
            decodeAndValidate(decrypt(masterKey, identityFile.readFully()))
        } else {
            TinkMessageCrypto.generate().copyKeyMaterial().also { material ->
                writeAtomically(encrypt(masterKey, encode(material)))
            }
        }
    }

    public fun masterKeyProtection(): MasterKeyProtection {
        val key = loadKeyStore().getKey(MASTER_KEY_ALIAS, null) as? SecretKey
            ?: return MasterKeyProtection.UNAVAILABLE
        val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEY_STORE)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> MasterKeyProtection.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ->
                    MasterKeyProtection.TRUSTED_ENVIRONMENT
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> MasterKeyProtection.SOFTWARE
                else -> MasterKeyProtection.UNKNOWN
            }
        } else {
            legacyProtection(info)
        }
    }

    private fun generateMasterKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(MASTER_KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(ASSOCIATED_DATA)
        val ciphertext = cipher.doFinal(plaintext)
        byteArrayOf(FORMAT_VERSION, cipher.iv.size.toByte()) + cipher.iv + ciphertext
    } finally {
        plaintext.fill(0)
    }

    private fun decrypt(key: SecretKey, stored: ByteArray): ByteArray {
        if (stored.size < HEADER_SIZE_BYTES + GCM_IV_SIZE_BYTES + GCM_TAG_SIZE_BYTES ||
            stored[0] != FORMAT_VERSION ||
            stored[1].toInt() and 0xff != GCM_IV_SIZE_BYTES
        ) {
            throw GeneralSecurityException("Invalid identity storage format")
        }
        val ivEnd = HEADER_SIZE_BYTES + GCM_IV_SIZE_BYTES
        val iv = stored.copyOfRange(HEADER_SIZE_BYTES, ivEnd)
        val ciphertext = stored.copyOfRange(ivEnd, stored.size)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE_BITS, iv))
            cipher.updateAAD(ASSOCIATED_DATA)
            cipher.doFinal(ciphertext)
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun encode(material: DeviceKeyMaterial): ByteArray {
        val parts = listOf(
            material.copyEncryptionPrivateKey(),
            material.publicIdentity.copyEncryptionPublicKey(),
            material.copySigningPrivateKey(),
            material.publicIdentity.copySigningPublicKey(),
        )
        return try {
            ByteArray(KEY_MATERIAL_SIZE_BYTES).also { encoded ->
                parts.forEachIndexed { index, part ->
                    part.copyInto(encoded, index * PublicIdentity.RAW_KEY_SIZE_BYTES)
                }
            }
        } finally {
            parts.forEach { it.fill(0) }
        }
    }

    private fun decodeAndValidate(plaintext: ByteArray): DeviceKeyMaterial = try {
        if (plaintext.size != KEY_MATERIAL_SIZE_BYTES) {
            throw GeneralSecurityException("Invalid identity key material size")
        }
        val encryptionPrivate = plaintext.keyAt(0)
        val encryptionPublic = plaintext.keyAt(1)
        val signingPrivate = plaintext.keyAt(2)
        val signingPublic = plaintext.keyAt(3)
        try {
            val material = DeviceKeyMaterial(
                encryptionPrivate,
                PublicIdentity(encryptionPublic, signingPublic),
                signingPrivate,
            )
            TinkMessageCrypto.fromKeyMaterial(material).copyKeyMaterial()
        } finally {
            encryptionPrivate.fill(0)
            encryptionPublic.fill(0)
            signingPrivate.fill(0)
            signingPublic.fill(0)
        }
    } finally {
        plaintext.fill(0)
    }

    private fun ByteArray.keyAt(index: Int): ByteArray {
        val start = index * PublicIdentity.RAW_KEY_SIZE_BYTES
        return copyOfRange(start, start + PublicIdentity.RAW_KEY_SIZE_BYTES)
    }

    private fun writeAtomically(encrypted: ByteArray) {
        val output = identityFile.startWrite()
        try {
            output.write(encrypted)
            identityFile.finishWrite(output)
        } catch (failure: Exception) {
            identityFile.failWrite(output)
            throw failure
        } finally {
            encrypted.fill(0)
        }
    }

    internal companion object {
        internal const val ANDROID_KEY_STORE: String = "AndroidKeyStore"
        internal const val MASTER_KEY_ALIAS: String = "chat.mesh.identity.master.v0"
        internal const val IDENTITY_FILE_NAME: String = "mesh-device-identity-v0"
        private const val CIPHER_TRANSFORMATION: String = "AES/GCM/NoPadding"
        private const val MASTER_KEY_SIZE_BITS: Int = 256
        private const val FORMAT_VERSION: Byte = 0
        private const val HEADER_SIZE_BYTES: Int = 2
        private const val GCM_IV_SIZE_BYTES: Int = 12
        private const val GCM_TAG_SIZE_BITS: Int = 128
        private const val GCM_TAG_SIZE_BYTES: Int = GCM_TAG_SIZE_BITS / 8
        private const val KEY_COUNT: Int = 4
        private const val KEY_MATERIAL_SIZE_BYTES: Int = PublicIdentity.RAW_KEY_SIZE_BYTES * KEY_COUNT
        private val ASSOCIATED_DATA: ByteArray = "mesh-device-identity:v0".encodeToByteArray()
        private val STORE_LOCK: Any = Any()

        internal fun loadKeyStore(): KeyStore =
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

        @Suppress("DEPRECATION")
        private fun legacyProtection(info: KeyInfo): MasterKeyProtection =
            if (info.isInsideSecureHardware) {
                MasterKeyProtection.SECURE_HARDWARE
            } else {
                MasterKeyProtection.SOFTWARE
            }
    }
}

public enum class MasterKeyProtection {
    STRONGBOX,
    TRUSTED_ENVIRONMENT,
    SECURE_HARDWARE,
    SOFTWARE,
    UNKNOWN,
    UNAVAILABLE,
}
