package ir.mamoot.app.sync

import java.time.Instant
import java.time.OffsetDateTime

/**
 * Pure, JVM-testable guards for sync/control-message safety (audit F1/F8).
 *
 * Control payloads inside a secret session are authenticated as "came from
 * the session peer", but they carry attacker-chosen *target ids*. Without
 * these checks a malicious peer could rewrite or annotate arbitrary rows of
 * the victim's local database (other conversations included).
 */
object SyncGuards {

    /**
     * A peer may only revise (edit) a message that the peer itself sent in
     * this very conversation.
     */
    fun canRevise(
        targetSenderId: String?,
        targetConversationId: String?,
        controlSenderId: String,
        conversationId: String,
    ): Boolean =
        targetSenderId != null &&
            targetConversationId != null &&
            targetSenderId == controlSenderId &&
            targetConversationId == conversationId

    /**
     * Any participant may react to any message, but only inside the
     * conversation the reaction control arrived in.
     */
    fun canReact(targetConversationId: String?, conversationId: String): Boolean =
        targetConversationId != null && targetConversationId == conversationId

    /**
     * F8: `messages.created_at` defaults to `now()` (transaction start).
     * A row committed late can carry a timestamp older than our sync
     * watermark and would be skipped forever by a strict `>= watermark`
     * cursor. Re-fetching from watermark−30s is safe because row
     * processing is idempotent (dedupe by server id).
     */
    fun rewindWatermark(iso: String, seconds: Long = 30): String {
        val instant = parseInstant(iso) ?: return iso
        return instant.minusSeconds(seconds).toString()
    }

    fun parseInstant(iso: String): Instant? {
        val trimmed = iso.trim()
        if (trimmed.isEmpty()) return null
        return try {
            Instant.parse(trimmed)
        } catch (e: Exception) {
            try {
                OffsetDateTime.parse(trimmed).toInstant()
            } catch (e2: Exception) {
                null
            }
        }
    }
}
