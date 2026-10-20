package ir.mamoot.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoSafetyNumberTest {
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun pbkdf2MatchesRfcVector() {
        // RFC 7914 PBKDF2-HMAC-SHA256 test vector (first 32 bytes).
        val dk = Crypto.pbkdf2Sha256(
            "password".toCharArray(),
            "salt".toByteArray(Charsets.UTF_8),
            1,
            32,
        )
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(dk),
        )
    }

    @Test
    fun safetyNumberIsSymmetricAndFormatted() {
        val a = Crypto.generateEcKeyPair()
        val b = Crypto.generateEcKeyPair()
        val ab = Crypto.safetyNumber("user-a", a.public.encoded, "user-b", b.public.encoded)
        val ba = Crypto.safetyNumber("user-b", b.public.encoded, "user-a", a.public.encoded)
        assertEquals(ab, ba)
        val groups = ab.split(" ")
        assertEquals(12, groups.size)
        assertTrue(groups.all { it.length == 5 && it.all { ch -> ch.isDigit() } })
    }

    @Test
    fun safetyNumberChangesWithKeyOrIdentity() {
        val a = Crypto.generateEcKeyPair()
        val b = Crypto.generateEcKeyPair()
        val c = Crypto.generateEcKeyPair()
        val base = Crypto.safetyNumber("user-a", a.public.encoded, "user-b", b.public.encoded)
        assertEquals(base, Crypto.safetyNumber("user-a", a.public.encoded, "user-b", b.public.encoded))
        assertFalse(base == Crypto.safetyNumber("user-a", a.public.encoded, "user-b", c.public.encoded))
        assertFalse(base == Crypto.safetyNumber("user-x", a.public.encoded, "user-b", b.public.encoded))
    }
}
