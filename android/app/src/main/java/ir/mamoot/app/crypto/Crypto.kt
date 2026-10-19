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

    /** Human-comparable safety code, Telegram-style (compare out-of-band). */
    fun safetyCode(myIdentityPubDer: ByteArray, peerIdentityPubDer: ByteArray, sessionFingerprintB64: String): String {
        val sorted = listOf(b64(myIdentityPubDer), b64(peerIdentityPubDer)).sorted()
        val digest = sha256((sorted[0] + "|" + sorted[1] + "|" + sessionFingerprintB64).toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(32).uppercase()
        return hex.chunked(4).joinToString(" ")
    }
}
