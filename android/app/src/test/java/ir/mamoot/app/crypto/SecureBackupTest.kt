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
        assertTrue(blob.startsWith("MAMOOT-BACKUP-V1."))
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
        val ct = Crypto.unb64(parts[3])
        ct[0] = (ct[0].toInt() xor 1).toByte()
        parts[3] = Crypto.b64(ct)
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
    fun eachEncryptionUsesFreshSalt() {
        val one = SecureBackup.encrypt("same", "passphrase-123")
        val two = SecureBackup.encrypt("same", "passphrase-123")
        assertFalse(one == two)
        assertEquals("same", SecureBackup.decrypt(one, "passphrase-123"))
        assertEquals("same", SecureBackup.decrypt(two, "passphrase-123"))
    }
}
