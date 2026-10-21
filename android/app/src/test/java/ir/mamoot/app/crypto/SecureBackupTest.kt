package ir.mamoot.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecureBackupTest {
    @Test
    fun roundTripPreservesPayload() {
        val payload = "MAMOOT-DATA-V1\nuser=abc\nپیام محرمانه با کلید 🔐"
        val blob = SecureBackup.encrypt(payload, "گذرواژه محرمانه 123")
        assertTrue(blob.startsWith("MAMOOT-BACKUP-V2.600000."))
        assertFalse(blob.contains("محرمانه با کلید"))
        assertEquals(payload, SecureBackup.decrypt(blob, "گذرواژه محرمانه 123"))
    }

    @Test
    fun wrongPassphraseIsRejected() {
        val blob = SecureBackup.encrypt("secret", "correct horse battery staple")
        try {
            SecureBackup.decrypt(blob, "wrong passphrase!")
            fail("wrong passphrase must fail")
        } catch (expected: BackupException) {
            // expected
        }
    }

    @Test
    fun tamperedOrMalformedBlobIsRejected() {
        val blob = SecureBackup.encrypt("secret", "passphrase-123")
        val parts = blob.split(".").toMutableList()
        val ct = Crypto.unb64(parts[4])
        ct[0] = (ct[0].toInt() xor 1).toByte()
        parts[4] = Crypto.b64(ct)
        try {
            SecureBackup.decrypt(parts.joinToString("."), "passphrase-123")
            fail("tampered ciphertext must fail")
        } catch (expected: BackupException) {
            // expected
        }
        try {
            SecureBackup.decrypt("not a backup", "passphrase-123")
            fail("malformed blob must fail")
        } catch (expected: BackupException) {
            // expected
        }
    }

    @Test
    fun tamperedIterationCountIsRejected() {
        // The header (incl. iteration count) is GCM AAD in V2, and counts
        // far outside the plausible range are refused before any KDF work.
        val blob = SecureBackup.encrypt("secret", "passphrase-123")
        val downgraded = blob.replace(".600000.", ".100000.")
        try {
            SecureBackup.decrypt(downgraded, "passphrase-123")
            fail("downgraded iteration count must fail")
        } catch (expected: BackupException) {
            // expected
        }
        val wedge = blob.replace(".600000.", ".99999999.")
        try {
            SecureBackup.decrypt(wedge, "passphrase-123")
            fail("absurd iteration count must fail fast")
        } catch (expected: BackupException) {
            // expected
        }
    }

    @Test
    fun legacyV1BackupsStillDecrypt() {
        // Rebuild a <= 0.4.0 V1 container by hand and prove 0.4.1 opens it.
        val passphrase = "legacy passphrase 7"
        val payload = "MAMOOT-DATA-V1\nuser=abc\nold history"
        val salt = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (it * 3).toByte() }
        val key = Crypto.pbkdf2Sha256(passphrase.toByteArray(Charsets.UTF_8), salt, 210_000, 32)
        val ct = Crypto.aesGcmEncrypt(
            key, nonce, payload.toByteArray(Charsets.UTF_8),
            "MAMOOT-BACKUP-V1".toByteArray(Charsets.UTF_8),
        )
        val v1 = "MAMOOT-BACKUP-V1.${Crypto.b64(salt)}.${Crypto.b64(nonce)}.${Crypto.b64(ct)}"
        assertTrue(SecureBackup.isBackupBlob(v1))
        assertEquals(payload, SecureBackup.decrypt(v1, passphrase))
    }

    @Test
    fun eachEncryptionUsesFreshSalt() {
        val one = SecureBackup.encrypt("same", "passphrase-123")
        val two = SecureBackup.encrypt("same", "passphrase-123")
        assertFalse(one == two)
        assertEquals("same", SecureBackup.decrypt(one, "passphrase-123"))
        assertEquals("same", SecureBackup.decrypt(two, "passphrase-123"))
    }
}

class BackupRulesTest {
    @Test
    fun restoreDropsRatchetSessionsAndHandshakes() {
        // F6: these must never come back from a backup.
        assertFalse(BackupRules.restoresSecureKey("session:conv-1:peer-2"))
        assertFalse(BackupRules.restoresSecureKey("kxaccept:conv-1:fp"))
        assertFalse(BackupRules.restoresSecureKey("session2:whatever"))
        assertFalse(BackupRules.restoresSecureKey("pin_attempts"))
        // Identity material, peer pins and TTL choices do restore.
        assertTrue(BackupRules.restoresSecureKey("identity_priv_v1"))
        assertTrue(BackupRules.restoresSecureKey("identity_pub_v1"))
        assertTrue(BackupRules.restoresSecureKey("pin:user-123"))
        assertTrue(BackupRules.restoresSecureKey("ttl:conv-1"))
    }

    @Test
    fun passphrasePolicy() {
        assertTrue(BackupRules.passphraseIssues("abc123").isNotEmpty()) // too short
        assertTrue(BackupRules.passphraseIssues("123456789012").isNotEmpty()) // no letter
        assertTrue(BackupRules.passphraseIssues("abcdefghijkl").isNotEmpty()) // no digit
        assertTrue(BackupRules.passphraseIssues("aaaaaaaaaaa1").isEmpty()) // has letter+digit, 12 chars
        assertTrue(BackupRules.passphraseIssues("correct horse 9").isEmpty())
    }
}
