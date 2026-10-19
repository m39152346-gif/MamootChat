package ir.mamoot.app.crypto

import java.security.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecretSessionTest {
    private val convId = "11111111-2222-3333-4444-555555555555"
    private val aId = "user-a"
    private val bId = "user-b"
    private val aIdentity: KeyPair = Crypto.generateEcKeyPair()
    private val bIdentity: KeyPair = Crypto.generateEcKeyPair()

    private fun handshake(a: SecretSession, b: SecretSession, nowMs: Long = 1_000L) {
        val init = a.startExchange(aIdentity.private, nowMs)
        val accept = b.handleKxInit(init, aIdentity.public, bIdentity.private, nowMs)
        assertNotNull("B must accept a correctly signed init", accept)
        assertTrue(a.handleKxAccept(accept!!, bIdentity.public, nowMs))
        assertTrue(a.hasSession())
        assertTrue(b.hasSession())
        assertEquals(a.currentFingerprint(), b.currentFingerprint())
    }

    private fun readyPair(): Pair<SecretSession, SecretSession> {
        val a = SecretSession(aId, bId, convId)
        val b = SecretSession(bId, aId, convId)
        handshake(a, b)
        return a to b
    }

    @Test
    fun twoWayConversation() {
        val (a, b) = readyPair()
        val e1 = a.encrypt("سلام مموت")
        assertFalse(e1.contains("سلام"))
        assertEquals("سلام مموت", b.decrypt(e1))
        val e2 = b.encrypt("سلام! چطوری؟")
        assertEquals("سلام! چطوری؟", a.decrypt(e2))
        repeat(10) { i ->
            assertEquals("msg $i", b.decrypt(a.encrypt("msg $i")))
        }
    }

    @Test
    fun outOfOrderDeliveryAndReplayRejection() {
        val (a, b) = readyPair()
        val m0 = a.encrypt("zero")
        val m1 = a.encrypt("one")
        val m2 = a.encrypt("two")
        assertEquals("one", b.decrypt(m1)) // gap tolerated, key for m0 is skipped-stored
        assertEquals("zero", b.decrypt(m0))
        assertEquals("two", b.decrypt(m2))
        try {
            b.decrypt(m0)
            fail("replayed message must be rejected")
        } catch (expected: DecryptException) {
            // expected
        }
    }

    @Test
    fun tamperingFailsAndDoesNotConsumeChain() {
        val (a, b) = readyPair()
        val good = a.encrypt("authentic")
        val parts = good.split(".").toMutableList()
        val ct = Crypto.unb64(parts[5])
        ct[0] = (ct[0].toInt() xor 1).toByte()
        parts[5] = Crypto.b64(ct)
        try {
            b.decrypt(parts.joinToString("."))
            fail("tampered message must fail AEAD")
        } catch (expected: DecryptException) {
            // expected
        }
        assertEquals("authentic", b.decrypt(good))
    }

    @Test
    fun forgedKeyExchangeIsRejected() {
        val attackerIdentity = Crypto.generateEcKeyPair()
        val attacker = SecretSession(aId, bId, convId)
        val forgedInit = attacker.startExchange(attackerIdentity.private, 5_000L)
        val b = SecretSession(bId, aId, convId)
        val accept = b.handleKxInit(forgedInit, aIdentity.public, bIdentity.private, 5_000L)
        assertNull("init signed by a non-pinned identity must be ignored", accept)
        assertFalse(b.hasSession())
    }

    @Test
    fun rekeySwitchesEpochAndOldInflightStillDecrypts() {
        val (a, b) = readyPair()
        val oldFingerprint = a.currentFingerprint()
        val oldInflightFromB = b.encrypt("old epoch message")

        // A initiates a Telegram-style re-key.
        val init = a.startExchange(aIdentity.private, 2_000L)
        val accept = b.handleKxInit(init, aIdentity.public, bIdentity.private, 2_000L)
        assertNotNull(accept)
        assertTrue(a.handleKxAccept(accept!!, bIdentity.public, 2_000L))
        assertFalse(oldFingerprint == a.currentFingerprint())

        // New-epoch message promotes the future key on B's side.
        assertEquals("new epoch message", b.decrypt(a.encrypt("new epoch message")))
        assertEquals(a.currentFingerprint(), b.currentFingerprint())

        // B's old-epoch in-flight message still decrypts on A via the previous epoch.
        assertEquals("old epoch message", a.decrypt(oldInflightFromB))
    }

    @Test
    fun serializationPreservesRatchetState() {
        val (a, b) = readyPair()
        assertEquals("first", b.decrypt(a.encrypt("first")))
        val restored = SecretSession.deserialize(b.serialize())
        assertEquals("second", restored.decrypt(a.encrypt("second")))
        assertEquals("reply", a.decrypt(restored.encrypt("reply")))
    }

    @Test
    fun tooLongMessageDoesNotConsumeSequence() {
        val (a, b) = readyPair()
        try {
            a.encrypt("x".repeat(5000))
            fail("oversized plaintext must be rejected")
        } catch (expected: MessageTooLongException) {
            // expected
        }
        assertEquals("still in sync", b.decrypt(a.encrypt("still in sync")))
    }

    @Test
    fun unknownFingerprintIsRejected() {
        val (a, b) = readyPair()
        val stranger = SecretSession(aId, bId, convId)
        val strangerB = SecretSession(bId, aId, convId)
        // A completely separate session between the same identities:
        val init = stranger.startExchange(aIdentity.private, 9_000L)
        val accept = strangerB.handleKxInit(init, aIdentity.public, bIdentity.private, 9_000L)
        assertNotNull(accept)
        assertTrue(stranger.handleKxAccept(accept!!, bIdentity.public, 9_000L))
        val foreign = stranger.encrypt("foreign session")
        try {
            b.decrypt(foreign)
            fail("message from an unknown session fingerprint must be rejected")
        } catch (expected: DecryptException) {
            // expected
        }
        // The real session is untouched.
        assertEquals("real", b.decrypt(a.encrypt("real")))
    }
}
