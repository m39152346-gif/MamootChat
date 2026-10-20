package ir.mamoot.app.crypto

class BackupException(message: String = "invalid backup") : Exception(message)

/**
 * Passphrase-encrypted backup container (format "MAMOOT-BACKUP-V1").
 *
 * The payload (identity keys, secret-session state and the locally cached
 * plaintext) is encrypted with AES-256-GCM under a key derived from the
 * user's passphrase via PBKDF2-HMAC-SHA256 (210k iterations, random salt).
 * Nothing about the account password or the server can decrypt it: losing
 * the passphrase means losing the backup, exactly like Signal's model.
 *
 * Container text: MAMOOT-BACKUP-V1.<b64 salt>.<b64 nonce>.<b64 ciphertext>
 */
object SecureBackup {
    private const val PREFIX = "MAMOOT-BACKUP-V1"
    private const val ITERATIONS = 210_000
    private val AAD = PREFIX.toByteArray(Charsets.UTF_8)

    fun encrypt(payload: String, passphrase: String): String {
        require(passphrase.length >= 8) { "passphrase too short" }
        val salt = Crypto.randomBytes(16)
        val nonce = Crypto.randomBytes(12)
        val key = deriveKey(passphrase, salt)
        val ciphertext = Crypto.aesGcmEncrypt(key, nonce, payload.toByteArray(Charsets.UTF_8), AAD)
        return "$PREFIX.${Crypto.b64(salt)}.${Crypto.b64(nonce)}.${Crypto.b64(ciphertext)}"
    }

    fun decrypt(blob: String, passphrase: String): String {
        val parts = blob.trim().split(".")
        if (parts.size != 4 || parts[0] != PREFIX) throw BackupException("bad backup format")
        val salt = try {
            Crypto.unb64(parts[1])
        } catch (e: Exception) {
            throw BackupException("bad salt")
        }
        val nonce = try {
            Crypto.unb64(parts[2])
        } catch (e: Exception) {
            throw BackupException("bad nonce")
        }
        val ciphertext = try {
            Crypto.unb64(parts[3])
        } catch (e: Exception) {
            throw BackupException("bad ciphertext")
        }
        val plain = try {
            Crypto.aesGcmDecrypt(deriveKey(passphrase, salt), nonce, ciphertext, AAD)
        } catch (e: Exception) {
            throw BackupException("wrong passphrase or corrupted backup")
        }
        return String(plain, Charsets.UTF_8)
    }

    fun isBackupBlob(text: String): Boolean = text.trim().startsWith("$PREFIX.")

    private fun deriveKey(passphrase: String, salt: ByteArray): ByteArray =
        Crypto.pbkdf2Sha256(passphrase.toByteArray(Charsets.UTF_8), salt, ITERATIONS, 32)
}
