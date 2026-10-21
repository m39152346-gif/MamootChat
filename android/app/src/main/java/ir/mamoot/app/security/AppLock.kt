package ir.mamoot.app.security

import ir.mamoot.app.crypto.Crypto
import ir.mamoot.app.storage.SecureStore
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Optional app-lock credential (PIN or passphrase, min 6 chars).
 *
 * Security model (documented per audit F4): this is a **UI gate**, not a
 * cryptographic boundary. Chat keys and the local database are protected
 * by the Android sandbox and the Keystore-backed vault, not by this PIN —
 * the PIN stops shoulder-surfing and casual access to an unlocked phone.
 * Forgetting it can only be resolved by wiping this device's data
 * (offered on the lock screen).
 *
 * Hardening over the <= 0.4.0 implementation:
 *  - verification now uses PBKDF2WithHmacSHA256 (210k iterations, the
 *    OWASP recommendation for this construction) instead of 5,000 rounds
 *    of raw SHA-256;
 *  - the failed-attempt counter and exponential lockout are **persisted**
 *    in SecureStore, so killing/restarting the app no longer resets
 *    them;
 *  - comparison stays constant-time (MessageDigest.isEqual).
 *
 * Storage (unchanged keys, so upgrades keep working):
 *  - legacy: `applock_salt_v1` = base64 salt string, `applock_hash_v1` =
 *    base64( SHA-256^5001("$salt:$pin") ). Verified for upgrade
 *    compatibility only; LockActivity re-saves in v2 form after a
 *    successful legacy unlock.
 *  - v2: `applock_hash_v1` = "v2.iterations.saltB64.hashB64" (salt key
 *    removed). A legacy value is plain base64url and can never contain
 *    a '.', so the prefix discriminates safely.
 */
class AppLock(private val secure: SecureStore) {

    fun isPinSet(): Boolean = secure.getSecret(KEY_HASH) != null

    fun isLegacyHash(): Boolean {
        val raw = secure.getSecret(KEY_HASH) ?: return false
        return !raw.startsWith("v2.")
    }

    fun setPin(pin: String) {
        val salt = Crypto.randomBytes(16)
        val hash = pbkdf2Standard(pin.toCharArray(), salt, ITERATIONS)
        secure.putSecret(KEY_HASH, "v2.$ITERATIONS.${Crypto.b64(salt)}.${Crypto.b64(hash)}")
        secure.removeSecret(KEY_SALT)
        resetFailures()
    }

    fun verify(pin: String): Boolean {
        val raw = secure.getSecret(KEY_HASH) ?: return false
        val now = System.currentTimeMillis()
        if (now < lockedUntilMs()) return false
        val ok = if (raw.startsWith("v2.")) {
            verifyV2(pin, raw)
        } else {
            verifyLegacy(pin, raw)
        }
        if (ok) {
            resetFailures()
        } else {
            registerFailure(now)
        }
        return ok
    }

    fun clear() {
        secure.removeSecret(KEY_HASH)
        secure.removeSecret(KEY_SALT)
        secure.removeSecret(KEY_AUTO)
        secure.removeSecret(KEY_ATTEMPTS)
        secure.removeSecret(KEY_LOCK_UNTIL)
    }

    /** 0 = lock as soon as the app leaves the foreground; -1 = only on cold start. */
    var autoLockSeconds: Int
        get() = secure.getSecret(KEY_AUTO)?.toIntOrNull() ?: 0
        set(value) = secure.putSecret(KEY_AUTO, value.toString())

    /** Epoch millis until which verification is refused (0 = not locked out). */
    fun lockedUntilMs(): Long = secure.getSecret(KEY_LOCK_UNTIL)?.toLongOrNull() ?: 0L

    private fun resetFailures() {
        secure.putSecret(KEY_ATTEMPTS, "0")
        secure.putSecret(KEY_LOCK_UNTIL, "0")
    }

    private fun registerFailure(nowMs: Long) {
        val attempts = (secure.getSecret(KEY_ATTEMPTS)?.toIntOrNull() ?: 0) + 1
        secure.putSecret(KEY_ATTEMPTS, attempts.toString())
        val delay = backoffMs(attempts)
        if (delay > 0) secure.putSecret(KEY_LOCK_UNTIL, (nowMs + delay).toString())
    }

    private fun verifyV2(pin: String, raw: String): Boolean = try {
        val parts = raw.split(".")
        if (parts.size != 4) {
            false
        } else {
            val iterations = parts[1].toInt()
            val salt = Crypto.unb64(parts[2])
            val expected = Crypto.unb64(parts[3])
            MessageDigest.isEqual(
                pbkdf2Standard(pin.toCharArray(), salt, iterations), expected,
            )
        }
    } catch (e: Exception) {
        false
    }

    private fun verifyLegacy(pin: String, rawHashB64: String): Boolean {
        val salt = secure.getSecret(KEY_SALT) ?: return false
        val actual = Crypto.b64(legacyHashRaw(salt, pin))
        return MessageDigest.isEqual(
            actual.toByteArray(Charsets.UTF_8),
            rawHashB64.toByteArray(Charsets.UTF_8),
        )
    }

    /** Exact <= 0.4.0 digest; changing this would lock out existing users. */
    private fun legacyHashRaw(salt: String, pin: String): ByteArray {
        var h = Crypto.sha256("$salt:$pin".toByteArray(Charsets.UTF_8))
        repeat(LEGACY_ITERATIONS) { h = Crypto.sha256(h) }
        return h
    }

    companion object {
        const val ITERATIONS = 210_000
        private const val LEGACY_ITERATIONS = 5_000
        private const val KEY_HASH = "applock_hash_v1"
        private const val KEY_SALT = "applock_salt_v1"
        private const val KEY_AUTO = "applock_auto_v1"
        private const val KEY_ATTEMPTS = "applock_attempts_v1"
        private const val KEY_LOCK_UNTIL = "applock_lock_until_v1"

        fun pbkdf2Standard(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(secret, salt, iterations, 256)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        /** Exponential lockout schedule (audit F4); pure and unit-tested. */
        fun backoffMs(failures: Int): Long = when {
            failures <= 4 -> 0L
            failures == 5 -> 30_000L
            failures == 6 -> 60_000L
            failures == 7 -> 120_000L
            failures == 8 -> 300_000L
            failures == 9 -> 900_000L
            failures == 10 -> 1_800_000L
            else -> 3_600_000L
        }
    }
}
