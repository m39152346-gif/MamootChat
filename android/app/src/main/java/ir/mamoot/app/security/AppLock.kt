package ir.mamoot.app.security

import ir.mamoot.app.crypto.Crypto
import ir.mamoot.app.storage.SecureStore

/**
 * Optional in-app lock (PIN, optionally unlocked by device biometrics).
 * Only a salted, iterated SHA-256 hash of the PIN is stored, itself inside
 * the KeyStore-encrypted vault. Forgetting the PIN can only be resolved by
 * signing out and wiping this device's data (offered on the lock screen).
 */
class AppLock(private val secure: SecureStore) {

    fun isPinSet(): Boolean = secure.getSecret(KEY_HASH) != null

    fun setPin(pin: String) {
        val salt = Crypto.b64(Crypto.randomBytes(16))
        secure.putSecret(KEY_SALT, salt)
        secure.putSecret(KEY_HASH, hash(salt, pin))
    }

    fun verify(pin: String): Boolean {
        val salt = secure.getSecret(KEY_SALT) ?: return false
        val expected = secure.getSecret(KEY_HASH) ?: return false
        val actual = hash(salt, pin)
        return java.security.MessageDigest.isEqual(
            actual.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    fun clear() {
        secure.removeSecret(KEY_HASH)
        secure.removeSecret(KEY_SALT)
        secure.removeSecret(KEY_AUTO)
    }

    /** 0 = lock as soon as the app leaves the foreground; -1 = only on cold start. */
    var autoLockSeconds: Int
        get() = secure.getSecret(KEY_AUTO)?.toIntOrNull() ?: 0
        set(value) = secure.putSecret(KEY_AUTO, value.toString())

    private fun hash(salt: String, pin: String): String = Crypto.b64(hashRaw(salt, pin))

    private fun hashRaw(salt: String, pin: String): ByteArray {
        var h = Crypto.sha256("$salt:$pin".toByteArray(Charsets.UTF_8))
        repeat(ITERATIONS) { h = Crypto.sha256(h) }
        return h
    }

    private companion object {
        const val KEY_HASH = "applock_hash_v1"
        const val KEY_SALT = "applock_salt_v1"
        const val KEY_AUTO = "applock_auto_v1"
        const val ITERATIONS = 5_000
    }
}
