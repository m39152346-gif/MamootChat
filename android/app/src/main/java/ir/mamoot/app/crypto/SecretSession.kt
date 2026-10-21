package ir.mamoot.app.crypto

import java.security.PrivateKey
import java.security.PublicKey

class NoSessionException(message: String = "no secret session") : Exception(message)
class DecryptException(message: String = "decrypt failed") : Exception(message)
class MessageTooLongException : Exception("message too long")

/**
 * Telegram-Secret-Chat-style session, re-implemented for Mamoot's transport
 * (envelopes are carried as message bodies through the ordinary message API;
 * the server never sees keys or plaintext):
 *
 *  - Authenticated ephemeral ECDH key exchange (kx init/accept), the
 *    equivalent of Telegram's requestKey/acceptKey exchange. Ephemeral keys
 *    are signed by long-term identity keys (pinned, trust-on-first-use) —
 *    this closes the unauthenticated-DH gap of classic MTProto secret chats.
 *  - A session fingerprint derived from the DH secret (cf. Telegram's
 *    key_fingerprint), carried in every message envelope.
 *  - Per-direction symmetric chains with in/out sequence numbers: every
 *    message uses a one-time key and the chain ratchets forward (message
 *    keys are deleted after use), replayed sequence numbers are rejected,
 *    and a bounded number of skipped keys tolerates out-of-order delivery.
 *  - DH re-keying after [REKEY_AFTER_MESSAGES] sent messages or
 *    [REKEY_AFTER_MS], mirroring Telegram's rule (100 messages / 1 week),
 *    giving forward secrecy across epochs; the previous epoch stays
 *    readable briefly for in-flight messages.
 *
 * Envelope formats (base64url segments, stored as the message body):
 *  message : MAM1.m.<fingerprint>.<seq>.<nonce>.<ciphertext>
 *  kx init : MAM1.k1.<exchangeId>.<ephemeralPub>.<signature>
 *  kx accept: MAM1.k2.<exchangeId>.<ephemeralPub>.<signature>
 */
class SecretSession(
    val myUserId: String,
    val peerUserId: String,
    val conversationId: String,
) {
    class Epoch(
        val fingerprint: String,
        var sendChain: ByteArray,
        var recvChain: ByteArray,
        var sendSeq: Long = 0,
        var recvNext: Long = 0,
        var createdAtMs: Long = 0,
        var sentCount: Int = 0,
        val skipped: MutableMap<Long, ByteArray> = mutableMapOf(),
    ) {
        /** Best-effort erasure of key material (JVM copies may still exist). */
        fun wipeKeys() {
            sendChain.fill(0)
            recvChain.fill(0)
            skipped.values.forEach { it.fill(0) }
            skipped.clear()
        }
    }

    class Pending(
        val exchangeId: String,
        val ephemeralPrivatePkcs8: ByteArray,
        val initiator: Boolean,
        val createdAtMs: Long,
    )

    data class KxEnvelope(val stage: String, val exchangeId: String, val ephemeralPubB64: String, val signatureB64: String)
    data class MsgEnvelope(
        val fingerprint: String,
        val seq: Long,
        val nonceB64: String,
        val ciphertextB64: String,
        val control: Boolean = false,
    )

    /** Decrypted payload. Control payloads ride in `MAM1.c.` envelopes. */
    data class Plain(
        val text: String,
        val ttlSeconds: Int = 0,
        val endOfSecret: Boolean = false,
        val reviseTargetId: String? = null,
        val replyTargetId: String? = null,
        val forwarded: Boolean = false,
        val reactTargetId: String? = null,
        val reaction: String? = null,
    )

    var current: Epoch? = null
        private set
    var future: Epoch? = null
        private set
    private val previous: MutableList<Epoch> = mutableListOf()
    var pending: Pending? = null
        private set

    fun hasSession(): Boolean = current != null
    fun isWaitingForPeer(): Boolean = pending?.initiator == true && current == null
    fun currentFingerprint(): String? = current?.fingerprint

    fun needsRekey(nowMs: Long): Boolean {
        val c = current ?: return false
        if (pending != null || future != null) return false
        return c.sentCount >= REKEY_AFTER_MESSAGES || nowMs - c.createdAtMs >= REKEY_AFTER_MS
    }

    fun reset() {
        current?.wipeKeys()
        future?.wipeKeys()
        previous.forEach { it.wipeKeys() }
        current = null
        future = null
        previous.clear()
        pending = null
    }

    // ---------------------------------------------------------- key exchange
    fun startExchange(myIdentityPrivate: PrivateKey, nowMs: Long): String {
        val ephemeral = Crypto.generateEcKeyPair()
        val ephemeralPubB64 = Crypto.publicKeyToB64(ephemeral.public)
        val exchangeId = Crypto.b64(Crypto.randomBytes(16))
        val signature = Crypto.sign(myIdentityPrivate, kxSignData(myUserId, exchangeId, ephemeralPubB64))
        pending = Pending(exchangeId, Crypto.privateKeyToBytes(ephemeral.private), true, nowMs)
        return "MAM1.k1.$exchangeId.$ephemeralPubB64.${Crypto.b64(signature)}"
    }

    /** Returns the accept envelope to send back, or null to ignore this init. */
    fun handleKxInit(body: String, peerIdentityPublic: PublicKey, myIdentityPrivate: PrivateKey, nowMs: Long): String? {
        val kx = parseKx(body) ?: return null
        if (kx.stage != "k1") return null
        val ok = Crypto.verify(
            peerIdentityPublic,
            kxSignData(peerUserId, kx.exchangeId, kx.ephemeralPubB64),
            Crypto.unb64(kx.signatureB64),
        )
        if (!ok) return null

        // Crossed simultaneous exchanges: deterministic winner (lower user id).
        val myPending = pending
        if (myPending != null && myPending.initiator) {
            if (myUserId < peerUserId) return null
            pending = null
        }

        val peerEphemeral = try {
            Crypto.publicKeyFromB64(kx.ephemeralPubB64)
        } catch (e: Exception) {
            return null
        }
        val myEphemeral = Crypto.generateEcKeyPair()
        val authSecret = Crypto.ecdh(myEphemeral.private, peerEphemeral)
        val epoch = deriveEpoch(authSecret, nowMs)
        if (current == null) {
            current = epoch
        } else {
            // Never drop an existing future epoch silently: the peer may
            // already have sent messages under it (crossed re-keys).
            future?.let { addPrevious(it) }
            future = epoch
        }

        val myPubB64 = Crypto.publicKeyToB64(myEphemeral.public)
        val signature = Crypto.sign(myIdentityPrivate, kxSignData(myUserId, kx.exchangeId, myPubB64))
        return "MAM1.k2.${kx.exchangeId}.$myPubB64.${Crypto.b64(signature)}"
    }

    fun handleKxAccept(body: String, peerIdentityPublic: PublicKey, nowMs: Long): Boolean {
        val kx = parseKx(body) ?: return false
        if (kx.stage != "k2") return false
        val myPending = pending ?: return false
        if (nowMs - myPending.createdAtMs > PENDING_TTL_MS) {
            pending = null // abandoned exchange; a fresh start is required
            return false
        }
        if (!myPending.initiator || myPending.exchangeId != kx.exchangeId) return false
        val ok = Crypto.verify(
            peerIdentityPublic,
            kxSignData(peerUserId, kx.exchangeId, kx.ephemeralPubB64),
            Crypto.unb64(kx.signatureB64),
        )
        if (!ok) return false
        val peerEphemeral = try {
            Crypto.publicKeyFromB64(kx.ephemeralPubB64)
        } catch (e: Exception) {
            return false
        }
        val myPrivate = Crypto.privateKeyFromBytes(myPending.ephemeralPrivatePkcs8)
        val epoch = deriveEpoch(Crypto.ecdh(myPrivate, peerEphemeral), nowMs)
        current?.let { addPrevious(it) }
        future?.let { addPrevious(it) }
        current = epoch
        future = null
        pending = null
        return true
    }

    private fun addPrevious(epoch: Epoch) {
        if (previous.any { it.fingerprint == epoch.fingerprint }) return
        previous.add(0, epoch)
        while (previous.size > MAX_PREVIOUS) {
            previous.removeAt(previous.size - 1).wipeKeys()
        }
    }

    private fun promoteFuture() {
        val f = future ?: return
        current?.let { addPrevious(it) }
        current = f
        future = null
    }

    private fun kxSignData(senderUserId: String, exchangeId: String, ephemeralPubB64: String): ByteArray =
        "MAMOOT-KX-v1|$conversationId|$senderUserId|$exchangeId|$ephemeralPubB64".toByteArray(Charsets.UTF_8)

    private fun deriveEpoch(authSecret: ByteArray, nowMs: Long): Epoch {
        val master = Crypto.hkdf(
            ikm = authSecret,
            salt = Crypto.sha256(conversationId.toByteArray(Charsets.UTF_8)),
            info = "mamoot-secret-v1".toByteArray(Charsets.UTF_8),
            length = 32,
        )
        val fingerprintBytes = Crypto.sha256(authSecret).copyOf(8)
        val sendChain = Crypto.hkdf(master, fingerprintBytes, "chain:$myUserId".toByteArray(Charsets.UTF_8), 32)
        val recvChain = Crypto.hkdf(master, fingerprintBytes, "chain:$peerUserId".toByteArray(Charsets.UTF_8), 32)
        return Epoch(
            fingerprint = Crypto.b64(fingerprintBytes),
            sendChain = sendChain,
            recvChain = recvChain,
            createdAtMs = nowMs,
        )
    }

    // ---------------------------------------------------------- messages
    fun encrypt(
        plaintext: String,
        ttlSeconds: Int = 0,
        replyToId: String? = null,
        forwarded: Boolean = false,
    ): String {
        val payload = when {
            replyToId != null ->
                byteArrayOf(0x51) + "$replyToId|$ttlSeconds\n$plaintext".toByteArray(Charsets.UTF_8) // 'Q' = quote/reply
            forwarded ->
                byteArrayOf(0x46) + "$ttlSeconds\n$plaintext".toByteArray(Charsets.UTF_8) // 'F' = forwarded
            ttlSeconds > 0 ->
                byteArrayOf(0x44) + "$ttlSeconds\n$plaintext".toByteArray(Charsets.UTF_8) // 'D' = timed
            else ->
                byteArrayOf(0x54) + plaintext.toByteArray(Charsets.UTF_8) // 'T' = text
        }
        return encryptPayload(payload, 'm')
    }

    /** Control: set/replace my reaction on an earlier message ("" removes it). */
    fun encryptReaction(targetServerId: String, emoji: String): String =
        encryptPayload(byteArrayOf(0x41) + "$targetServerId\n$emoji".toByteArray(Charsets.UTF_8), 'c') // 'A' = react

    /** Control: "end this secret session" (both sides fall back to plain chat). */
    fun encryptControlEnd(): String = encryptPayload(byteArrayOf(0x58), 'c') // 'X'

    /** Control: replace the text of one earlier encrypted message. */
    fun encryptRevise(targetServerId: String, newText: String): String =
        encryptPayload(byteArrayOf(0x52) + "$targetServerId\n$newText".toByteArray(Charsets.UTF_8), 'c') // 'R'

    private fun encryptPayload(payload: ByteArray, stage: Char): String {
        val epoch = current ?: throw NoSessionException()
        val seq = epoch.sendSeq
        val messageKey = Crypto.hmacSha256(epoch.sendChain, byteArrayOf(1))
        val nextChain = Crypto.hmacSha256(epoch.sendChain, byteArrayOf(2))
        val nonce = Crypto.randomBytes(12)
        val ciphertext = Crypto.aesGcmEncrypt(messageKey, nonce, payload, aad(stage, myUserId, epoch.fingerprint, seq))
        val envelope = "MAM1.$stage.${epoch.fingerprint}.$seq.${Crypto.b64(nonce)}.${Crypto.b64(ciphertext)}"
        if (envelope.length > MAX_ENVELOPE_CHARS) throw MessageTooLongException()
        val oldChain = epoch.sendChain
        epoch.sendChain = nextChain
        oldChain.fill(0)
        messageKey.fill(0)
        epoch.sendSeq = seq + 1
        epoch.sentCount += 1
        return envelope
    }

    fun decrypt(body: String): Plain {
        val msg = parseMsg(body) ?: throw DecryptException("bad envelope")
        var promoteAfterSuccess = false
        val epoch: Epoch = when {
            current?.fingerprint == msg.fingerprint -> current!!
            future?.fingerprint == msg.fingerprint -> {
                promoteAfterSuccess = true
                future!!
            }
            else -> previous.firstOrNull { it.fingerprint == msg.fingerprint }
                ?: throw DecryptException("unknown session fingerprint")
        }

        val nonce = Crypto.unb64(msg.nonceB64)
        val ciphertext = Crypto.unb64(msg.ciphertextB64)
        val stage = if (msg.control) 'c' else 'm'
        val aad = aad(stage, peerUserId, msg.fingerprint, msg.seq)

        if (msg.seq < epoch.recvNext) {
            val skippedKey = epoch.skipped[msg.seq] ?: throw DecryptException("replayed sequence")
            val plain = tryDecrypt(skippedKey, nonce, ciphertext, aad)
            epoch.skipped.remove(msg.seq)?.fill(0)
            if (promoteAfterSuccess) promoteFuture()
            return plain
        }

        val gap = msg.seq - epoch.recvNext
        if (gap > MAX_SKIPPED_GAP) throw DecryptException("sequence gap too large")

        var chain = epoch.recvChain
        val newSkipped = mutableMapOf<Long, ByteArray>()
        var cursor = epoch.recvNext
        while (cursor < msg.seq) {
            newSkipped[cursor] = Crypto.hmacSha256(chain, byteArrayOf(1))
            chain = Crypto.hmacSha256(chain, byteArrayOf(2))
            cursor++
        }
        val messageKey = Crypto.hmacSha256(chain, byteArrayOf(1))
        val nextChain = Crypto.hmacSha256(chain, byteArrayOf(2))
        val plain = tryDecrypt(messageKey, nonce, ciphertext, aad)

        // Commit ratchet state only after successful authentication.
        if (promoteAfterSuccess) promoteFuture()
        epoch.skipped.putAll(newSkipped)
        // Bound stored skipped keys (memory DoS via sparse sequences):
        // evict the oldest sequence numbers beyond the cap.
        while (epoch.skipped.size > MAX_SKIPPED_KEYS) {
            val oldest = epoch.skipped.keys.minOrNull() ?: break
            epoch.skipped.remove(oldest)?.fill(0)
        }
        val oldRecvChain = epoch.recvChain
        epoch.recvChain = nextChain
        if (oldRecvChain !== nextChain) oldRecvChain.fill(0)
        epoch.recvNext = msg.seq + 1
        return plain
    }

    private fun tryDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): Plain {
        // Envelopes bind the stage ('m'/'c') into the AEAD AAD, so a relay
        // flipping a message<->control stage is detected. The pre-v0.4
        // legacy-AAD fallback was removed in v0.4.1 (audit F12): unread
        // envelopes from pre-0.4 builds no longer decrypt; already-read
        // history lives as local plaintext and is unaffected.
        val raw = try {
            Crypto.aesGcmDecrypt(key, nonce, ciphertext, aad)
        } catch (e: Exception) {
            throw DecryptException("authentication failed")
        }
        if (raw.isEmpty()) throw DecryptException("bad payload")
        val rest = String(raw, 1, raw.size - 1, Charsets.UTF_8)
        return when (raw[0]) {
            0x54.toByte() -> Plain(rest) // 'T'
            0x44.toByte() -> { // 'D' timed: "<ttlSeconds>\n<text>"
                val nl = rest.indexOf('\n')
                if (nl <= 0) throw DecryptException("bad timed payload")
                val ttl = rest.substring(0, nl).toIntOrNull() ?: throw DecryptException("bad ttl")
                Plain(rest.substring(nl + 1), ttlSeconds = ttl)
            }
            0x58.toByte() -> Plain("", endOfSecret = true) // 'X'
            0x52.toByte() -> { // 'R' revise: "<targetServerId>\n<newText>"
                val nl = rest.indexOf('\n')
                if (nl <= 0) throw DecryptException("bad revise payload")
                Plain(rest.substring(nl + 1), reviseTargetId = rest.substring(0, nl))
            }
            0x51.toByte() -> { // 'Q' reply: "<targetServerId>|<ttlSeconds>\n<text>"
                val nl = rest.indexOf('\n')
                if (nl <= 0) throw DecryptException("bad reply payload")
                val head = rest.substring(0, nl).split("|")
                if (head.isEmpty() || head[0].isEmpty()) throw DecryptException("bad reply target")
                Plain(
                    rest.substring(nl + 1),
                    ttlSeconds = head.getOrNull(1)?.toIntOrNull() ?: 0,
                    replyTargetId = head[0],
                )
            }
            0x46.toByte() -> { // 'F' forwarded: "<ttlSeconds>\n<text>"
                val nl = rest.indexOf('\n')
                if (nl <= 0) throw DecryptException("bad forward payload")
                val ttl = rest.substring(0, nl).toIntOrNull() ?: throw DecryptException("bad ttl")
                Plain(rest.substring(nl + 1), ttlSeconds = ttl, forwarded = true)
            }
            0x41.toByte() -> { // 'A' reaction: "<targetServerId>\n<emoji or empty>"
                val nl = rest.indexOf('\n')
                if (nl <= 0) throw DecryptException("bad reaction payload")
                Plain("", reactTargetId = rest.substring(0, nl), reaction = rest.substring(nl + 1))
            }
            else -> throw DecryptException("bad payload")
        }
    }

    private fun aad(stage: Char, senderUserId: String, fingerprint: String, seq: Long): ByteArray =
        "MAM1|$stage|$conversationId|$senderUserId|$fingerprint|$seq".toByteArray(Charsets.UTF_8)


    // ---------------------------------------------------------- serialization
    fun serialize(): String {
        val sb = StringBuilder()
        sb.append("v=1\n")
        sb.append("my=").append(myUserId).append('\n')
        sb.append("peer=").append(peerUserId).append('\n')
        sb.append("conv=").append(conversationId).append('\n')
        current?.let { appendEpoch(sb, "cur", it) }
        future?.let { appendEpoch(sb, "fut", it) }
        sb.append("prev.n=").append(previous.size).append('\n')
        previous.forEachIndexed { index, epoch -> appendEpoch(sb, "prev$index", epoch) }
        pending?.let {
            sb.append("pend.xid=").append(it.exchangeId).append('\n')
            sb.append("pend.priv=").append(Crypto.b64(it.ephemeralPrivatePkcs8)).append('\n')
            sb.append("pend.init=").append(if (it.initiator) "1" else "0").append('\n')
            sb.append("pend.ct=").append(it.createdAtMs).append('\n')
        }
        return sb.toString()
    }

    private fun appendEpoch(sb: StringBuilder, prefix: String, e: Epoch) {
        sb.append(prefix).append(".fp=").append(e.fingerprint).append('\n')
        sb.append(prefix).append(".sc=").append(Crypto.b64(e.sendChain)).append('\n')
        sb.append(prefix).append(".rc=").append(Crypto.b64(e.recvChain)).append('\n')
        sb.append(prefix).append(".ss=").append(e.sendSeq).append('\n')
        sb.append(prefix).append(".rn=").append(e.recvNext).append('\n')
        sb.append(prefix).append(".ct=").append(e.createdAtMs).append('\n')
        sb.append(prefix).append(".cnt=").append(e.sentCount).append('\n')
        val skip = e.skipped.entries.joinToString(",") { "${it.key}:${Crypto.b64(it.value)}" }
        sb.append(prefix).append(".skip=").append(skip).append('\n')
    }

    companion object {
        const val REKEY_AFTER_MESSAGES = 100
        const val REKEY_AFTER_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_SKIPPED_GAP = 100L
        const val MAX_SKIPPED_KEYS = 250
        const val MAX_PREVIOUS = 2
        const val PENDING_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_ENVELOPE_CHARS = 3900
        const val MAX_PLAINTEXT_CHARS = 1500

        fun isKx(body: String): Boolean = body.startsWith("MAM1.k")
        fun isControl(body: String): Boolean = body.startsWith("MAM1.c.")
        fun isEncryptedMessage(body: String): Boolean =
            body.startsWith("MAM1.m.") || body.startsWith("MAM1.c.")

        fun parseKx(body: String): KxEnvelope? {
            val parts = body.split(".")
            if (parts.size != 5 || parts[0] != "MAM1") return null
            if (parts[1] != "k1" && parts[1] != "k2") return null
            return KxEnvelope(parts[1], parts[2], parts[3], parts[4])
        }

        fun parseMsg(body: String): MsgEnvelope? {
            val parts = body.split(".")
            if (parts.size != 6 || parts[0] != "MAM1") return null
            if (parts[1] != "m" && parts[1] != "c") return null
            val seq = parts[3].toLongOrNull() ?: return null
            return MsgEnvelope(parts[2], seq, parts[4], parts[5], control = parts[1] == "c")
        }

        fun deserialize(serialized: String): SecretSession {
            val map = mutableMapOf<String, String>()
            for (line in serialized.split("\n")) {
                val idx = line.indexOf('=')
                if (idx > 0) map[line.substring(0, idx)] = line.substring(idx + 1)
            }
            val session = SecretSession(
                myUserId = map["my"] ?: error("missing my"),
                peerUserId = map["peer"] ?: error("missing peer"),
                conversationId = map["conv"] ?: error("missing conv"),
            )
            session.current = readEpoch(map, "cur")
            session.future = readEpoch(map, "fut")
            val prevCount = map["prev.n"]?.toIntOrNull() ?: 0
            for (i in 0 until prevCount) {
                readEpoch(map, "prev$i")?.let { session.previous.add(it) }
            }
            map["pend.xid"]?.let { xid ->
                session.pending = Pending(
                    exchangeId = xid,
                    ephemeralPrivatePkcs8 = Crypto.unb64(map["pend.priv"] ?: ""),
                    initiator = map["pend.init"] == "1",
                    createdAtMs = map["pend.ct"]?.toLongOrNull() ?: 0,
                )
            }
            return session
        }

        private fun readEpoch(map: Map<String, String>, prefix: String): Epoch? {
            val fp = map["$prefix.fp"] ?: return null
            val skipped = mutableMapOf<Long, ByteArray>()
            val rawSkip = map["$prefix.skip"].orEmpty()
            if (rawSkip.isNotEmpty()) {
                for (entry in rawSkip.split(",")) {
                    val kv = entry.split(":")
                    if (kv.size == 2) {
                        val seq = kv[0].toLongOrNull()
                        if (seq != null) skipped[seq] = Crypto.unb64(kv[1])
                    }
                }
            }
            return Epoch(
                fingerprint = fp,
                sendChain = Crypto.unb64(map["$prefix.sc"] ?: ""),
                recvChain = Crypto.unb64(map["$prefix.rc"] ?: ""),
                sendSeq = map["$prefix.ss"]?.toLongOrNull() ?: 0,
                recvNext = map["$prefix.rn"]?.toLongOrNull() ?: 0,
                createdAtMs = map["$prefix.ct"]?.toLongOrNull() ?: 0,
                sentCount = map["$prefix.cnt"]?.toIntOrNull() ?: 0,
                skipped = skipped,
            )
        }
    }
}
