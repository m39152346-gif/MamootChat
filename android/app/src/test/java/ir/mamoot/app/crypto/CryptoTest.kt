package ir.mamoot.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CryptoTest {
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun hkdfIsDeterministicAndInfoSeparated() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = unhex("000102030405060708090a0b0c")
        val a = Crypto.hkdf(ikm, salt, "info-a".toByteArray(Charsets.UTF_8), 42)
        val a2 = Crypto.hkdf(ikm, salt, "info-a".toByteArray(Charsets.UTF_8), 42)
        val b = Crypto.hkdf(ikm, salt, "info-b".toByteArray(Charsets.UTF_8), 42)
        assertEquals(42, a.size)
        assertArrayEquals(a, a2)
        assertFalse(a.contentEquals(b))
        // HKDF expand prefix property: first 32 bytes are a prefix of 42-byte output.
        assertArrayEquals(a.copyOf(32), Crypto.hkdf(ikm, salt, "info-a".toByteArray(Charsets.UTF_8), 32))
    }

    @Test
    fun ecdhBothSidesAgree() {
        val a = Crypto.generateEcKeyPair()
        val b = Crypto.generateEcKeyPair()
        assertArrayEquals(Crypto.ecdh(a.private, b.public), Crypto.ecdh(b.private, a.public))
        assertEquals(32, Crypto.ecdh(a.private, b.public).size)
    }

    @Test
    fun signAndVerify() {
        val kp = Crypto.generateEcKeyPair()
        val data = "mamoot".toByteArray(Charsets.UTF_8)
        val sig = Crypto.sign(kp.private, data)
        assertTrue(Crypto.verify(kp.public, data, sig))
        assertFalse(Crypto.verify(kp.public, "tampered".toByteArray(Charsets.UTF_8), sig))
    }

    @Test
    fun aesGcmDetectsTampering() {
        val key = Crypto.randomBytes(32)
        val nonce = Crypto.randomBytes(12)
        val aad = "aad".toByteArray(Charsets.UTF_8)
        val ct = Crypto.aesGcmEncrypt(key, nonce, "hello".toByteArray(Charsets.UTF_8), aad)
        assertEquals("hello", String(Crypto.aesGcmDecrypt(key, nonce, ct, aad), Charsets.UTF_8))
        ct[0] = (ct[0].toInt() xor 1).toByte()
        try {
            Crypto.aesGcmDecrypt(key, nonce, ct, aad)
            fail("tampered ciphertext must not decrypt")
        } catch (expected: Exception) {
            // AEAD failure is the assertion.
        }
    }

    @Test
    fun publicKeyEncodingRoundTripsAndDbConstraintFits() {
        val kp = Crypto.generateEcKeyPair()
        val b64 = Crypto.publicKeyToB64(kp.public)
        assertTrue(b64.length in 80..200)
        assertTrue(b64.matches(Regex("^[A-Za-z0-9_-]+$")))
        val restored = Crypto.publicKeyFromB64(b64)
        assertArrayEquals(kp.public.encoded, restored.encoded)
    }
}
