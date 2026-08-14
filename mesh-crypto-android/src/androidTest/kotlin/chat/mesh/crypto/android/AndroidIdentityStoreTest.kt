package chat.mesh.crypto.android

import android.content.Context
import android.util.Log
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mesh.crypto.DeviceKeyMaterial
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException

@RunWith(AndroidJUnit4::class)
class AndroidIdentityStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        clearIdentity()
    }

    @After
    fun tearDown() {
        clearIdentity()
    }

    @Test
    fun identityPersistsWithoutRawPrivateKeysOnDisk() {
        val store = AndroidIdentityStore(context)
        val created = store.loadOrCreate()
        val loaded = AndroidIdentityStore(context).loadOrCreate()

        assertSameKeyMaterial(created, loaded)
        val stored = identityFile().readBytes()
        assertFalse(stored.containsSequence(created.copyEncryptionPrivateKey()))
        assertFalse(stored.containsSequence(created.copySigningPrivateKey()))

        val masterKey = AndroidIdentityStore.loadKeyStore().getKey(
            AndroidIdentityStore.MASTER_KEY_ALIAS,
            null,
        )
        assertNull(masterKey.encoded)
        val protection = store.masterKeyProtection()
        Log.i(LOG_TAG, "Master key protection: $protection")
        assertNotEquals(MasterKeyProtection.UNAVAILABLE, protection)
        assertNotEquals(MasterKeyProtection.UNKNOWN, protection)
    }

    @Test
    fun tamperedIdentityFailsClosed() {
        val store = AndroidIdentityStore(context)
        store.loadOrCreate()
        val file = identityFile()
        file.writeBytes(file.readBytes().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        })

        assertThrows(GeneralSecurityException::class.java) {
            store.loadOrCreate()
        }
    }

    @Test
    fun missingMasterKeyDoesNotSilentlyReplaceIdentity() {
        val store = AndroidIdentityStore(context)
        store.loadOrCreate()
        val keyStore = AndroidIdentityStore.loadKeyStore()
        keyStore.deleteEntry(AndroidIdentityStore.MASTER_KEY_ALIAS)

        assertThrows(GeneralSecurityException::class.java) {
            store.loadOrCreate()
        }
        assertFalse(AndroidIdentityStore.loadKeyStore().containsAlias(AndroidIdentityStore.MASTER_KEY_ALIAS))
        assertTrue(identityFile().exists())
    }

    private fun clearIdentity() {
        AtomicFile(identityFile()).delete()
        val keyStore = AndroidIdentityStore.loadKeyStore()
        if (keyStore.containsAlias(AndroidIdentityStore.MASTER_KEY_ALIAS)) {
            keyStore.deleteEntry(AndroidIdentityStore.MASTER_KEY_ALIAS)
        }
    }

    private fun identityFile() = context.noBackupFilesDir.resolve(AndroidIdentityStore.IDENTITY_FILE_NAME)

    private fun assertSameKeyMaterial(expected: DeviceKeyMaterial, actual: DeviceKeyMaterial) {
        assertArrayEquals(expected.copyEncryptionPrivateKey(), actual.copyEncryptionPrivateKey())
        assertArrayEquals(
            expected.publicIdentity.copyEncryptionPublicKey(),
            actual.publicIdentity.copyEncryptionPublicKey(),
        )
        assertArrayEquals(expected.copySigningPrivateKey(), actual.copySigningPrivateKey())
        assertArrayEquals(
            expected.publicIdentity.copySigningPublicKey(),
            actual.publicIdentity.copySigningPublicKey(),
        )
    }

    private fun ByteArray.containsSequence(candidate: ByteArray): Boolean =
        indices.any { start ->
            start + candidate.size <= size && candidate.indices.all { offset ->
                this[start + offset] == candidate[offset]
            }
        }

    private companion object {
        const val LOG_TAG = "MeshPhase0Identity"
    }
}
