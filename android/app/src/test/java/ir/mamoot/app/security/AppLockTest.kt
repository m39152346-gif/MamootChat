package ir.mamoot.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {

    private fun unhex(text: String): ByteArray =
        ByteArray(text.length / 2) { i ->
            text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

    @Test
    fun pbkdf2StandardMatchesRfc7914Vectors() {
        // RFC 7914 PBKDF2-HMAC-SHA-256 vectors (first 32 bytes of dkLen=64).
        assertArrayEquals(
            unhex("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"),
            AppLock.pbkdf2Standard(
                "password".toCharArray(), "salt".toByteArray(Charsets.UTF_8), 1,
            ),
        )
        assertArrayEquals(
            unhex("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43"),
            AppLock.pbkdf2Standard(
                "password".toCharArray(), "salt".toByteArray(Charsets.UTF_8), 2,
            ),
        )
        assertArrayEquals(
            unhex("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"),
            AppLock.pbkdf2Standard(
                "password".toCharArray(), "salt".toByteArray(Charsets.UTF_8), 4096,
            ),
        )
    }

    @Test
    fun backoffScheduleIsExponentialAndCapped() {
        assertEquals(0L, AppLock.backoffMs(1))
        assertEquals(0L, AppLock.backoffMs(4))
        assertEquals(30_000L, AppLock.backoffMs(5))
        assertEquals(60_000L, AppLock.backoffMs(6))
        assertEquals(120_000L, AppLock.backoffMs(7))
        assertEquals(300_000L, AppLock.backoffMs(8))
        assertEquals(900_000L, AppLock.backoffMs(9))
        assertEquals(1_800_000L, AppLock.backoffMs(10))
        assertEquals(3_600_000L, AppLock.backoffMs(11))
        assertEquals(3_600_000L, AppLock.backoffMs(1000))
        assertTrue(AppLock.backoffMs(9) > AppLock.backoffMs(8))
    }
}
