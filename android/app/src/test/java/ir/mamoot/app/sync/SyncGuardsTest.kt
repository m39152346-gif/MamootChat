package ir.mamoot.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit F1 (malicious-peer control messages) + F8 (sync cursor rewind).
 * The guards are the exact predicates ChatRepository applies before any
 * control-driven local mutation; the DB layer repeats the scoping in SQL.
 */
class SyncGuardsTest {
    private val conv = "conv-1"
    private val otherConv = "conv-2"
    private val me = "user-me"
    private val peer = "user-peer"

    @Test
    fun peerCanReviseOnlyOwnMessageInSameConversation() {
        // Legit: peer edits the peer's own message here.
        assertTrue(SyncGuards.canRevise(peer, conv, peer, conv))
        // Attack: peer tries to rewrite MY sent message.
        assertFalse(SyncGuards.canRevise(me, conv, peer, conv))
        // Attack: peer targets a message in another conversation.
        assertFalse(SyncGuards.canRevise(peer, otherConv, peer, conv))
        // Unknown target row.
        assertFalse(SyncGuards.canRevise(null, conv, peer, conv))
        assertFalse(SyncGuards.canRevise(peer, null, peer, conv))
    }

    @Test
    fun reactionMustStayInsideItsConversation() {
        assertTrue(SyncGuards.canReact(conv, conv))
        // Attack: annotate a message of a different conversation.
        assertFalse(SyncGuards.canReact(otherConv, conv))
        assertFalse(SyncGuards.canReact(null, conv))
    }

    @Test
    fun watermarkRewindsThirtySeconds() {
        assertEquals(
            "2026-10-21T10:00:00Z",
            SyncGuards.rewindWatermark("2026-10-21T10:00:30Z"),
        )
        // Fractional seconds + offset forms from PostgREST also parse.
        assertEquals(
            "2026-10-21T10:00:00.500Z",
            SyncGuards.rewindWatermark("2026-10-21T10:00:30.500+00:00"),
        )
        // Unparseable values pass through untouched (fetch will just work
        // with the original cursor instead of crashing the sync).
        assertEquals("not-a-date", SyncGuards.rewindWatermark("not-a-date"))
        assertEquals("", SyncGuards.rewindWatermark(""))
    }
}
