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
        assertEquals("سلام مموت", b.decrypt(e1).text)
        val e2 = b.encrypt("سلام! چطوری؟")
        assertEquals("سلام! چطوری؟", a.decrypt(e2).text)
        repeat(10) { i ->
            assertEquals("msg $i", b.decrypt(a.encrypt("msg $i")).text)
        }
    }

    @Test
    fun outOfOrderDeliveryAndReplayRejection() {
        val (a, b) = readyPair()
        val m0 = a.encrypt("zero")
        val m1 = a.encrypt("one")
        val m2 = a.encrypt("two")
        assertEquals("one", b.decrypt(m1).text) // gap tolerated, key for m0 is skipped-stored
        assertEquals("zero", b.decrypt(m0).text)
        assertEquals("two", b.decrypt(m2).text)
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
        assertEquals("authentic", b.decrypt(good).text)
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
        assertEquals("new epoch message", b.decrypt(a.encrypt("new epoch message")).text)
        assertEquals(a.currentFingerprint(), b.currentFingerprint())

        // B's old-epoch in-flight message still decrypts on A via the previous epoch.
        assertEquals("old epoch message", a.decrypt(oldInflightFromB).text)
    }

    @Test
    fun serializationPreservesRatchetState() {
        val (a, b) = readyPair()
        assertEquals("first", b.decrypt(a.encrypt("first")).text)
        val restored = SecretSession.deserialize(b.serialize())
        assertEquals("second", restored.decrypt(a.encrypt("second")).text)
        assertEquals("reply", a.decrypt(restored.encrypt("reply")).text)
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
        assertEquals("still in sync", b.decrypt(a.encrypt("still in sync")).text)
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
        assertEquals("real", b.decrypt(a.encrypt("real")).text)
    }

    @Test
    fun timedControlAndRevisePayloads() {
        val (a, b) = readyPair()
        val timed = b.decrypt(a.encrypt("زود پاک می‌شود", ttlSeconds = 60))
        assertEquals("زود پاک می‌شود", timed.text)
        assertEquals(60, timed.ttlSeconds)
        val plain = b.decrypt(a.encrypt("معمولی"))
        assertEquals(0, plain.ttlSeconds)
        val end = b.decrypt(a.encryptControlEnd())
        assertTrue(end.endOfSecret)
        val revise = b.decrypt(a.encryptRevise("srv-123", "متن ویرایش‌شده"))
        assertEquals("srv-123", revise.reviseTargetId)
        assertEquals("متن ویرایش‌شده", revise.text)
        assertTrue(SecretSession.isControl(a.encryptRevise("x", "y")))
    }

    @Test
    fun replyForwardAndReactionPayloadsStayEncrypted() {
        val (a, b) = readyPair()
        val reply = b.decrypt(a.encrypt("پاسخ من", replyToId = "srv-42"))
        assertEquals("پاسخ من", reply.text)
        assertEquals("srv-42", reply.replyTargetId)
        assertFalse(reply.forwarded)

        val forwarded = b.decrypt(a.encrypt("بازارسالی", forwarded = true))
        assertEquals("بازارسالی", forwarded.text)
        assertTrue(forwarded.forwarded)

        val timedReply = b.decrypt(a.encrypt("پاسخ موقت", ttlSeconds = 30, replyToId = "srv-7"))
        assertEquals(30, timedReply.ttlSeconds)
        assertEquals("srv-7", timedReply.replyTargetId)

        val reactionEnv = a.encryptReaction("srv-42", "❤️")
        assertTrue(SecretSession.isControl(reactionEnv))
        val reaction = b.decrypt(reactionEnv)
        assertEquals("srv-42", reaction.reactTargetId)
        assertEquals("❤️", reaction.reaction)

        val cleared = b.decrypt(a.encryptReaction("srv-42", ""))
        assertEquals("srv-42", cleared.reactTargetId)
        assertEquals("", cleared.reaction)
    }

    @Test
    fun stageBindingRejectsRelabeledEnvelope() {
        val (a, b) = readyPair()
        val control = a.encryptReaction("srv-1", "👍")
        assertTrue(control.startsWith("MAM1.c."))
        val relabeled = "MAM1.m." + control.removePrefix("MAM1.c.")
        try {
            b.decrypt(relabeled)
            fail("an envelope relabeled from control to message stage must fail AEAD")
        } catch (expected: DecryptException) {
            // expected: the stage byte is bound into the AAD since v0.4
        }
        // The untampered control still decrypts (chain not consumed by failure).
        assertEquals("👍", b.decrypt(control).reaction)
    }

    @Test
    fun oversizedGapIsRejected() {
        val (a, b) = readyPair()
        var last = ""
        repeat(1105) { last = a.encrypt("m$it") }
        try {
            b.decrypt(last)
            fail("a >1000-message gap must be rejected instead of skipping forever")
        } catch (expected: DecryptException) {
            // expected
        }
    }

    @Test
    fun skippedKeysAreCappedAndOldestEvicted() {
        val (a, b) = readyPair()
        val envelopes = (0 until 300).map { a.encrypt("m$it") }
        assertEquals("m299", b.decrypt(envelopes[299]).text)
        // Keys far behind the 250-key cap were evicted.
        try {
            b.decrypt(envelopes[0])
            fail("evicted skipped key must not decrypt")
        } catch (expected: DecryptException) {
            // expected
        }
        // Keys inside the retained window still work.
        assertEquals("m200", b.decrypt(envelopes[200]).text)
    }

    @Test
    fun decryptingDiscardedMessageStillAdvancesChainOnce() {
        // The repository decrypts even server-deleted envelopes exactly once
        // to consume the ratchet step, then discards the content.
        val (a, b) = readyPair()
        val env = a.encrypt("حذف‌شده")
        assertEquals("حذف‌شده", b.decrypt(env).text)
        try {
            b.decrypt(env)
            fail("second decrypt of the same envelope must be rejected as replay")
        } catch (expected: DecryptException) {
            // expected
        }
        assertEquals("بعدی", b.decrypt(a.encrypt("بعدی")).text)
    }

    @Test
    fun pendingKeyExchangeExpires() {
        val a = SecretSession(aId, bId, convId)
        val b = SecretSession(bId, aId, convId)
        val init = a.startExchange(aIdentity.private, 1_000L)
        val accept = b.handleKxInit(init, aIdentity.public, bIdentity.private, 1_000L)
        assertNotNull(accept)
        val afterTtl = 1_000L + 31L * 24 * 3600 * 1000
        assertFalse("stale pending KX must be dropped", a.handleKxAccept(accept!!, bIdentity.public, afterTtl))
        assertFalse(a.hasSession())
    }

    @Test
    fun rekeyNeededAfter100MessagesOr7Days() {
        val (a, _) = readyPair()
        assertFalse(a.needsRekey(2_000L))
        repeat(100) { a.encrypt("m$it") }
        assertTrue(a.needsRekey(2_000L))

        val (c, _) = readyPair()
        val eightDaysLater = 1_000L + 8L * 24 * 3600 * 1000
        assertTrue(c.needsRekey(eightDaysLater))
    }
}
