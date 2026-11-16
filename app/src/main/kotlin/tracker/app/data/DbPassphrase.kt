package tracker.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The database passphrase is 32 random bytes generated once on this device. It is stored only
 * in wrapped form: AES-256-GCM under a key that lives in the Android Keystore and cannot be
 * exported. The wrapped blob sits in app-private no-backup storage.
 *
 * The Keystore key is intentionally NOT bound to user authentication: the SMS receiver has to
 * open the database in the background while the phone is locked. The biometric gate protects
 * the UI; the Keystore protects the file at rest (e.g. against copying the app's data).
 */
object DbPassphrase {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "card_tracker_db_wrap_v1"
    private const val FILE = "db_passphrase.bin"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val HEX = "0123456789abcdef"

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE)
        keyStore.load(null)
        val existing = keyStore.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /**
     * Returns the passphrase as 64 ASCII hex characters (so it can never contain a zero byte).
     * Synchronized: the SMS receiver and the UI may both ask on first launch.
     */
    @Synchronized
    fun getOrCreate(context: Context): ByteArray {
        val file = File(context.noBackupFilesDir, FILE)
        val key = wrappingKey()
        if (file.exists()) {
            val blob = file.readBytes()
            check(blob.size > IV_BYTES) { "stored database key is corrupt" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            return toHex(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES))
        }
        val raw = ByteArray(32)
        SecureRandom().nextBytes(raw)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key) // the Keystore generates a fresh random IV
        val wrapped = cipher.doFinal(raw)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV length" }
        val tmp = File(context.noBackupFilesDir, "$FILE.tmp")
        tmp.writeBytes(iv + wrapped)
        check(tmp.renameTo(file)) { "could not store the database key" }
        return toHex(raw)
    }

    private fun toHex(raw: ByteArray): ByteArray {
        val out = ByteArray(raw.size * 2)
        for (i in raw.indices) {
            val v = raw[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4].code.toByte()
            out[i * 2 + 1] = HEX[v and 0x0f].code.toByte()
        }
        raw.fill(0)
        return out
    }
}
