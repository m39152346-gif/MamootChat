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
/**
 * Stored identity key material exists but cannot be read (corrupted, or
 * the Keystore master key was invalidated). The app must show the
 * recovery screen instead of silently generating a new identity and
 * auto-publishing it over the profile (audit F5).
 */
class IdentityRecoveryRequiredException :
    IllegalStateException("identity_recovery_required")

class IdentityManager(private val secure: SecureStore) {
    private var cached: KeyPair? = null

    @Synchronized
    fun ensureIdentity(): KeyPair {
        cached?.let { return it }
        loadStored()?.let {
            cached = it
            return it
        }
        if (secure.hasRawSecret(KEY_PRIV) || secure.hasRawSecret(KEY_PUB)) {
            // Never silently regenerate over unreadable material: doing so
            // republishes a new identity, stranding every secret chat and
            // making the user look like a different (possibly attacker)
            // device to every contact.
            throw IdentityRecoveryRequiredException()
        }
        val pair = Crypto.generateEcKeyPair()
        store(pair)
        cached = pair
        return pair
    }

    fun needsRecovery(): Boolean = synchronized(this) {
        cached == null && loadStored() == null &&
            (secure.hasRawSecret(KEY_PRIV) || secure.hasRawSecret(KEY_PUB))
    }

    private fun loadStored(): KeyPair? {
        val privB64 = secure.getSecret(KEY_PRIV) ?: return null
        val pubB64 = secure.getSecret(KEY_PUB) ?: return null
        return try {
            KeyPair(
                Crypto.publicKeyFromB64(pubB64),
                Crypto.privateKeyFromBytes(Crypto.unb64(privB64)),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun store(pair: KeyPair) {
        secure.putSecretCommit(KEY_PRIV, Crypto.b64(Crypto.privateKeyToBytes(pair.private)))
        secure.putSecretCommit(KEY_PUB, Crypto.publicKeyToB64(pair.public))
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

    /** Generates a brand-new identity keypair (deliberate key rotation). */
    @Synchronized
    fun rotateIdentity(): KeyPair {
        val pair = Crypto.generateEcKeyPair()
        store(pair)
        cached = pair
        return pair
    }

    /** Re-reads the identity from the vault (e.g. after a backup restore). */
    @Synchronized
    fun reloadIdentity() {
        cached = null
        ensureIdentity()
    }

    private companion object {
        const val KEY_PRIV = "identity_priv_v1"
        const val KEY_PUB = "identity_pub_v1"
    }
}
