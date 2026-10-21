package ir.mamoot.app.crypto

class BackupException(message: String = "invalid backup") : Exception(message)

/**
 * Passphrase-encrypted backup container.
 *
 * Formats:
 *  - V1 (<= 0.4.0, decrypt only): "MAMOOT-BACKUP-V1.<b64 salt>.<b64 nonce>.<b64 ct>"
 *    PBKDF2-HMAC-SHA256, 210,000 iterations, AES-256-GCM, AAD = "MAMOOT-BACKUP-V1".
 *  - V2 (0.4.1+): "MAMOOT-BACKUP-V2.<iterations>.<b64 salt>.<b64 nonce>.<b64 ct>"
 *    Same construction with 600,000 iterations (audit F7); the header
 *    prefix string "MAMOOT-BACKUP-V2.<iterations>.<salt>.<nonce>" is the
 *    GCM AAD, so tampering with the iteration count breaks decryption.
 *    The embedded count is sanity-bounded on decrypt so a crafted file
 *    cannot wedge the phone.
 *
 * Nothing about the account password or the server can decrypt a backup:
 * losing the passphrase means losing the backup.
 */
object SecureBackup {
    private const val PREFIX_V1 = "MAMOOT-BACKUP-V1"
    private const val PREFIX_V2 = "MAMOOT-BACKUP-V2"
    private const val V1_ITERATIONS = 210_000
    const val V2_ITERATIONS = 600_000
    private const val MAX_V2_ITERATIONS = 5_000_000

    fun encrypt(payload: String, passphrase: String): String {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) { "passphrase too short" }
        val salt = Crypto.randomBytes(16)
        val nonce = Crypto.randomBytes(12)
        val header = "$PREFIX_V2.$V2_ITERATIONS.${Crypto.b64(salt)}.${Crypto.b64(nonce)}"
        val key = deriveKey(passphrase, salt, V2_ITERATIONS)
        val ciphertext = Crypto.aesGcmEncrypt(
            key, nonce, payload.toByteArray(Charsets.UTF_8), header.toByteArray(Charsets.UTF_8),
        )
        return "$header.${Crypto.b64(ciphertext)}"
    }

    fun decrypt(blob: String, passphrase: String): String {
        val trimmed = blob.trim()
        return when {
            trimmed.startsWith("$PREFIX_V2.") -> decryptV2(trimmed, passphrase)
            trimmed.startsWith("$PREFIX_V1.") -> decryptV1(trimmed, passphrase)
            else -> throw BackupException("bad backup format")
        }
    }

    fun isBackupBlob(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("$PREFIX_V1.") || trimmed.startsWith("$PREFIX_V2.")
    }

    private fun decryptV2(blob: String, passphrase: String): String {
        val parts = blob.split(".")
        if (parts.size != 5) throw BackupException("bad backup format")
        val iterations = parts[1].toIntOrNull() ?: throw BackupException("bad iterations")
        if (iterations < 100_000 || iterations > MAX_V2_ITERATIONS) {
            throw BackupException("bad iterations")
        }
        val salt = decode(parts[2], "bad salt")
        val nonce = decode(parts[3], "bad nonce")
        val ciphertext = decode(parts[4], "bad ciphertext")
        val header = parts.subList(0, 4).joinToString(".")
        val plain = try {
            Crypto.aesGcmDecrypt(
                deriveKey(passphrase, salt, iterations), nonce, ciphertext,
                header.toByteArray(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            throw BackupException("wrong passphrase or corrupted backup")
        }
        return String(plain, Charsets.UTF_8)
    }

    private fun decryptV1(blob: String, passphrase: String): String {
        val parts = blob.split(".")
        if (parts.size != 4 || parts[0] != PREFIX_V1) throw BackupException("bad backup format")
        val salt = decode(parts[1], "bad salt")
        val nonce = decode(parts[2], "bad nonce")
        val ciphertext = decode(parts[3], "bad ciphertext")
        val plain = try {
            Crypto.aesGcmDecrypt(
                deriveKey(passphrase, salt, V1_ITERATIONS), nonce, ciphertext,
                PREFIX_V1.toByteArray(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            throw BackupException("wrong passphrase or corrupted backup")
        }
        return String(plain, Charsets.UTF_8)
    }

    private fun decode(value: String, error: String): ByteArray = try {
        Crypto.unb64(value)
    } catch (e: Exception) {
        throw BackupException(error)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray =
        Crypto.pbkdf2Sha256(passphrase.toByteArray(Charsets.UTF_8), salt, iterations, 32)

    const val MIN_PASSPHRASE_LENGTH = 12
}

/**
 * Restore hygiene + passphrase policy (audit F6/F7), kept pure and
 * unit-testable. A restore must never resurrect another device-state's
 * Double-Ratchet sessions: two live copies of one session diverge, reuse
 * counters and silently lose messages. Only identity material, peer pins
 * and per-chat TTL choices carry over; sessions are re-established with a
 * fresh signed key exchange on next open, and restored history stays
 * readable because its plaintext is already in the backup.
 */
object BackupRules {
    private val RESTORE_PREFIXES = listOf("identity_", "pin:", "ttl:")

    fun restoresSecureKey(key: String): Boolean =
        RESTORE_PREFIXES.any { key.startsWith(it) }

    /** Persian, user-facing problems with a candidate backup passphrase. */
    fun passphraseIssues(passphrase: String): List<String> {
        val issues = mutableListOf<String>()
        if (passphrase.length < SecureBackup.MIN_PASSPHRASE_LENGTH) {
            issues.add("حداقل ۱۲ نویسه باشد")
        }
        if (passphrase.none { it.isLetter() }) issues.add("حداقل یک حرف داشته باشد")
        if (passphrase.none { it.isDigit() }) issues.add("حداقل یک عدد داشته باشد")
        if (passphrase.isNotEmpty() && passphrase.all { it == passphrase[0] }) {
            issues.add("همهٔ نویسه‌ها تکراری نباشند")
        }
        return issues
    }
}
