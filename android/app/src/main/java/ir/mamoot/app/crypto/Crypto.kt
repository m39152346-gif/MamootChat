package ir.mamoot.app.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-JVM cryptography (java.security / javax.crypto only, works on the host
 * JVM for unit tests and on Android API 26+ through Conscrypt).
 *
 * Mamoot secret chats reuse the *architecture* of Telegram Secret Chats:
 * ephemeral Diffie-Hellman key exchange, a session key fingerprint,
 * per-direction sequence numbers, and DH re-keying for forward secrecy —
 * with modern primitives substituted for Telegram's 2048-bit MODP +
 * AES-256-IGE construction: ECDH/P-256, HKDF-SHA256 and AES-256-GCM (AEAD).
 */
object Crypto {
    private const val CURVE = "secp256r1"
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    // ------------------------------------------------------------ encoding
    fun b64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun unb64(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018), implemented on top of [hmacSha256] so it
     * behaves identically on Android and the host JVM. Used only to derive
     * the encrypted-backup key from a user passphrase.
     */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(iterations > 0 && dkLen > 0) { "bad pbkdf2 params" }
        require(password.isNotEmpty()) { "empty password" }
        // Single reusable Mac (doFinal resets it): at 600k iterations the
        // per-call Mac/KeySpec construction dominated wall-clock time.
        // Output is byte-identical to the previous implementation;
        // the RFC 7914 test vectors in CryptoTest pin this down.
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val out = ByteArray(dkLen)
        val blockSalt = ByteArray(salt.size + 4)
        salt.copyInto(blockSalt)
        var produced = 0
        var blockIndex = 1
        while (produced < dkLen) {
            blockSalt[salt.size] = (blockIndex ushr 24).toByte()
            blockSalt[salt.size + 1] = (blockIndex ushr 16).toByte()
            blockSalt[salt.size + 2] = (blockIndex ushr 8).toByte()
            blockSalt[salt.size + 3] = blockIndex.toByte()
            var u = mac.doFinal(blockSalt)
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(t.size, dkLen - produced)
            t.copyInto(out, produced, 0, n)
            produced += n
            blockIndex++
        }
        return out
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869 HKDF-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(actualSalt, ikm)
        var t = ByteArray(0)
        var okm = ByteArray(0)
        var counter = 1
        while (okm.size < length) {
            val input = t + info + byteArrayOf(counter.toByte())
            t = hmacSha256(prk, input)
            okm += t
            counter++
        }
        return okm.copyOf(length)
    }

    // ------------------------------------------------------------ EC keys
    fun generateEcKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec(CURVE), SecureRandom())
        return gen.generateKeyPair()
    }

    fun publicKeyToB64(key: PublicKey): String = b64(key.encoded)

    fun publicKeyFromB64(value: String): PublicKey {
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(X509EncodedKeySpec(unb64(value)))
    }

    fun privateKeyToBytes(key: PrivateKey): ByteArray = key.encoded

    fun privateKeyFromBytes(pkcs8: ByteArray): PrivateKey {
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    /** ECDH over P-256, normalized to 32 bytes (leading-zero safe). */
    fun ecdh(privateKey: PrivateKey, peerPublic: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublic, true)
        val secret = agreement.generateSecret()
        return when {
            secret.size == 32 -> secret
            secret.size > 32 -> secret.copyOfRange(secret.size - 32, secret.size)
            else -> ByteArray(32 - secret.size) + secret
        }
    }

    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    fun verify(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(data)
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------ AES-GCM
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Signal-style 60-digit safety number for a pair of long-term identities.
     * Each side contributes a 30-digit fingerprint: SHA-512 iterated 5200
     * times over (version || identityKey || stableUserId), first 30 bytes
     * split into six 5-byte big-endian chunks reduced mod 100000. The two
     * halves are sorted so both phones display the identical number. It
     * changes iff either identity key changes (not on session re-keys).
     */
    fun safetyNumber(
        myUserId: String,
        myIdentityPubDer: ByteArray,
        peerUserId: String,
        peerIdentityPubDer: ByteArray,
    ): String {
        val halves = listOf(
            identityFingerprintDigits(myUserId, myIdentityPubDer),
            identityFingerprintDigits(peerUserId, peerIdentityPubDer),
        ).sorted()
        return halves.joinToString(" ")
    }

    private fun identityFingerprintDigits(userId: String, identityPubDer: ByteArray): String {
        var hash = byteArrayOf(0, 1) + identityPubDer + userId.toByteArray(Charsets.UTF_8)
        repeat(5200) { hash = sha512(hash + identityPubDer) }
        val groups = ArrayList<String>(6)
        for (g in 0 until 6) {
            var value = 0L
            for (i in 0 until 5) {
                value = (value shl 8) or (hash[g * 5 + i].toLong() and 0xFF)
            }
            groups.add((value % 100_000).toString().padStart(5, '0'))
        }
        return groups.joinToString(" ")
    }

    /** Human-comparable safety code, Telegram-style (compare out-of-band). */
    fun safetyCode(myIdentityPubDer: ByteArray, peerIdentityPubDer: ByteArray, sessionFingerprintB64: String): String {
        val sorted = listOf(b64(myIdentityPubDer), b64(peerIdentityPubDer)).sorted()
        val digest = sha256((sorted[0] + "|" + sorted[1] + "|" + sessionFingerprintB64).toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(32).uppercase()
        return hex.chunked(4).joinToString(" ")
    }
}
