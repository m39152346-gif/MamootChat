package ir.mamoot.app.crypto

import ir.mamoot.app.storage.SecureStore
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey

/**
 * Long-term device identity for secret chats (Telegram secret chats are
 * likewise bound to one device). The private key is generated on-device and
 * stored only inside the KeyStore-encrypted vault; just the public key is
 * published on the profile. Peer identity keys are pinned on first use
 * (TOFU): a later change is treated as a security event, not silently
 * accepted.
 */
class IdentityManager(private val secure: SecureStore) {
    private var cached: KeyPair? = null

    @Synchronized
    fun ensureIdentity(): KeyPair {
        cached?.let { return it }
        val privB64 = secure.getSecret(KEY_PRIV)
        val pubB64 = secure.getSecret(KEY_PUB)
        if (privB64 != null && pubB64 != null) {
            try {
                val pair = KeyPair(
                    Crypto.publicKeyFromB64(pubB64),
                    Crypto.privateKeyFromBytes(Crypto.unb64(privB64)),
                )
                cached = pair
                return pair
            } catch (e: Exception) {
                // Corrupt/unreadable key material: fall through and regenerate.
            }
        }
        val pair = Crypto.generateEcKeyPair()
        secure.putSecret(KEY_PRIV, Crypto.b64(Crypto.privateKeyToBytes(pair.private)))
        secure.putSecret(KEY_PUB, Crypto.publicKeyToB64(pair.public))
        cached = pair
        return pair
    }

    fun publicKeyB64(): String = Crypto.publicKeyToB64(ensureIdentity().public)
    fun publicKey(): PublicKey = ensureIdentity().public
    fun privateKey(): PrivateKey = ensureIdentity().private

    fun peerPin(userId: String): String? = secure.getSecret("pin:$userId")

    fun pinPeer(userId: String, publicKeyB64: String) {
        secure.putSecret("pin:$userId", publicKeyB64)
    }

    fun clearPeerPin(userId: String) {
        secure.removeSecret("pin:$userId")
    }

    fun wipe() {
        cached = null
    }

    private companion object {
        const val KEY_PRIV = "identity_priv_v1"
        const val KEY_PUB = "identity_pub_v1"
    }
}
