package ir.mamoot.app.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ir.mamoot.app.crypto.Crypto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets vault: values are AES-256-GCM encrypted with a master key
 * that lives in the Android KeyStore (hardware-backed where available) and
 * never leaves it. Used for auth tokens, the E2EE identity private key,
 * secret-session state and locally cached message plaintext.
 */
class SecureStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mamoot_secure", Context.MODE_PRIVATE)

    fun putSecret(key: String, value: String) {
        prefs.edit().putString(key, encrypt(value)).apply()
    }

    fun getSecret(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            decrypt(raw)
        } catch (e: Exception) {
            // Key invalidated (e.g. device lock reset): treat as absent.
            null
        }
    }

    fun removeSecret(key: String) {
        prefs.edit().remove(key).apply()
    }

    /**
     * Synchronous write for crash-critical values (session ratchet state,
     * identity keys): `apply()` may lose the newest value if the process is
     * killed before the async disk flush, which could roll the ratchet back.
     */
    fun putSecretCommit(key: String, value: String) {
        prefs.edit().putString(key, encrypt(value)).commit()
    }

    fun keysWithPrefixes(prefixes: List<String>): List<String> =
        prefs.all.keys.filter { key -> prefixes.any { key.startsWith(it) } }.sorted()

    fun removeWithPrefixes(prefixes: List<String>) {
        val editor = prefs.edit()
        for (key in keysWithPrefixes(prefixes)) editor.remove(key)
        editor.commit()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /** Encrypts arbitrary text for storage elsewhere (e.g. SQLite columns). */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Crypto.b64(iv) + "." + Crypto.b64(ct)
    }

    fun decrypt(value: String): String {
        val parts = value.split(".")
        require(parts.size == 2) { "bad encrypted value" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            masterKey(),
            GCMParameterSpec(128, Crypto.unb64(parts[0])),
        )
        val plain = cipher.doFinal(Crypto.unb64(parts[1]))
        return String(plain, Charsets.UTF_8)
    }

    fun decryptOrNull(value: String?): String? {
        if (value == null) return null
        return try {
            decrypt(value)
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "mamoot_master_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
