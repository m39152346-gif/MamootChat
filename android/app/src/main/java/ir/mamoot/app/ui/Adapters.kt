package ir.mamoot.app.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import ir.mamoot.app.R
import ir.mamoot.app.net.Profile
import ir.mamoot.app.storage.LocalDb
import ir.mamoot.app.sync.UiConversation
import ir.mamoot.app.sync.UiMessage
import ir.mamoot.app.util.UiUtil

class ConversationAdapter(
    private val context: Context,
    private val items: MutableList<UiConversation> = mutableListOf(),
) : BaseAdapter() {

    fun submit(newItems: List<UiConversation>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): UiConversation = items[position]
    override fun getItemId(position: Int): Long = items[position].conversation.id.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_conversation, parent, false)
        val item = items[position]
        val avatar = view.findViewById<TextView>(R.id.tvAvatar)
        val seed = item.peer?.id ?: item.conversation.id
        avatar.text = UiUtil.initials(item.title)
        avatar.background.mutate().setTint(UiUtil.avatarColor(seed))

        val onlineDot = view.findViewById<View>(R.id.viewOnlineDot)
        val peer = item.peer
        onlineDot.visibility = if (
            peer != null && peer.lastSeenPublic && UiUtil.isOnline(peer.lastSeenAt)
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        view.findViewById<TextView>(R.id.tvTitle).text = item.title
        val timeIso = item.conversation.lastMessage?.createdAt ?: item.conversation.createdAt
        val timeView = view.findViewById<TextView>(R.id.tvTime)
        timeView.text = UiUtil.formatListTime(timeIso)
        timeView.setTextColor(
            context.getColor(if (item.conversation.unreadCount > 0) R.color.brand else R.color.text2),
        )
        view.findViewById<TextView>(R.id.tvPreview).text = item.preview
        val unread = view.findViewById<TextView>(R.id.tvUnread)
        if (item.conversation.unreadCount > 0) {
            unread.visibility = View.VISIBLE
            unread.text = UiUtil.faNum(item.conversation.unreadCount)
        } else {
            unread.visibility = View.GONE
        }
        return view
    }
}

class UserAdapter(
    private val context: Context,
    private val items: MutableList<Profile> = mutableListOf(),
) : BaseAdapter() {

    var selectedIds: Set<String> = emptySet()

    fun submit(newItems: List<Profile>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Profile = items[position]
    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_user, parent, false)
        val user = items[position]
        val avatar = view.findViewById<TextView>(R.id.tvUserAvatar)
        val selected = user.id in selectedIds
        avatar.text = if (selected) "✓" else UiUtil.initials(user.displayName)
        avatar.background.mutate().setTint(
            if (selected) context.getColor(R.color.brand) else UiUtil.avatarColor(user.id),
        )
        view.findViewById<TextView>(R.id.tvUserName).text = user.displayName
        view.findViewById<TextView>(R.id.tvUserHandle).text = "@${user.username}"
        view.findViewById<TextView>(R.id.tvUserLock).text = if (user.e2eIdentityKey != null) "🔒" else ""
        return view
    }
}

class MessageAdapter(
    private val context: Context,
    private val items: MutableList<UiMessage> = mutableListOf(),
) : BaseAdapter() {

    private var lastAnimatedLocalId = Long.MIN_VALUE

    var peerLastReadAt: String = ""
    var isGroup: Boolean = false
    var memberNames: Map<String, String> = emptyMap()
    var groupMemberIds: List<String> = emptyList()
    var memberReadAt: Map<String, String> = emptyMap()

    fun submit(newItems: List<UiMessage>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun prepend(older: List<UiMessage>) {
        val known = items.map { it.localId }.toSet()
        items.addAll(0, older.filter { it.localId !in known })
        notifyDataSetChanged()
    }

    fun snapshot(): List<UiMessage> = items.toList()

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): UiMessage = items[position]
    override fun getItemId(position: Int): Long = items[position].localId

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_message, parent, false)
        val msg = items[position]
        val dayView = view.findViewById<TextView>(R.id.tvDay)
        val showDay = position == 0 || UiUtil.dayKey(items[position - 1].createdAt) != UiUtil.dayKey(msg.createdAt)
        if (showDay) {
            dayView.visibility = View.VISIBLE
            dayView.text = UiUtil.formatDayLabel(msg.createdAt)
        } else {
            dayView.visibility = View.GONE
        }

        val bubble = view.findViewById<TextView>(R.id.tvBubble)
        val meta = view.findViewById<TextView>(R.id.tvMeta)
        val quote = view.findViewById<TextView>(R.id.tvQuote)
        val reactionsView = view.findViewById<TextView>(R.id.tvReactions)
        quote.visibility = View.GONE
        reactionsView.visibility = View.GONE
        val maxWidth = (context.resources.displayMetrics.widthPixels * 0.76).toInt()
        bubble.maxWidth = maxWidth
        meta.visibility = View.VISIBLE

        // iMessage-style grouping: consecutive messages from the same
        // sender on the same day sit 1dp apart, groups get real air,
        // the bubble "tail" and the time/status line appear only on
        // the last bubble of a group. Keeps long chats calm to read.
        fun sameGroup(a: UiMessage, b: UiMessage): Boolean =
            !a.system && !b.system && a.mine == b.mine && a.senderId == b.senderId &&
                UiUtil.dayKey(a.createdAt) == UiUtil.dayKey(b.createdAt)
        val prevMsg = items.getOrNull(position - 1)
        val nextMsg = items.getOrNull(position + 1)
        val sameAsPrev = prevMsg != null && sameGroup(prevMsg, msg)
        val sameAsNext = nextMsg != null && sameGroup(msg, nextMsg)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        view.setPadding(
            dp(16),
            if (showDay || !sameAsPrev) dp(7) else dp(1),
            dp(16),
            if (!sameAsNext) dp(3) else dp(1),
        )

        if (msg.system) {
            (bubble.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
            bubble.setBackgroundResource(R.drawable.day_bg)
            bubble.setTextColor(context.getColor(R.color.text2))
            bubble.text = msg.text ?: ""
            meta.visibility = View.GONE
            return view
        }

        val gravity = if (msg.mine) Gravity.END else Gravity.START
        (bubble.layoutParams as LinearLayout.LayoutParams).gravity = gravity
        (meta.layoutParams as LinearLayout.LayoutParams).gravity = gravity
        (quote.layoutParams as LinearLayout.LayoutParams).gravity = gravity
        (reactionsView.layoutParams as LinearLayout.LayoutParams).gravity = gravity
        quote.maxWidth = maxWidth

        if (msg.replySnippet != null && !msg.deleted && !msg.expired) {
            quote.visibility = View.VISIBLE
            quote.text = "↩ " + msg.replySnippet
        }
        if (msg.reactions.isNotEmpty() && !msg.deleted && !msg.expired) {
            val grouped = msg.reactions.values.groupingBy { it }.eachCount()
            reactionsView.visibility = View.VISIBLE
            reactionsView.text = grouped.entries.joinToString("  ") { (emoji, count) ->
                if (count > 1) "$emoji ${UiUtil.faNum(count)}" else emoji
            }
        }

        when {
            msg.expired -> {
                bubble.setBackgroundResource(R.drawable.bubble_deleted)
                bubble.setTextColor(context.getColor(R.color.text2))
                bubble.text = "⏱ این پیام خودکار حذف شد"
            }
            msg.deleted -> {
                bubble.setBackgroundResource(R.drawable.bubble_deleted)
                bubble.setTextColor(context.getColor(R.color.text2))
                bubble.text = "این پیام حذف شد"
            }
            msg.text != null -> {
                val isRtl = context.resources.configuration.layoutDirection ==
                    View.LAYOUT_DIRECTION_RTL
                val bubbleRes = when {
                    msg.mine && !sameAsNext && isRtl -> R.drawable.bubble_mine_rtl
                    msg.mine && !sameAsNext -> R.drawable.bubble_mine_ltr
                    msg.mine -> R.drawable.bubble_mine
                    !sameAsNext && isRtl -> R.drawable.bubble_theirs_rtl
                    !sameAsNext -> R.drawable.bubble_theirs_ltr
                    else -> R.drawable.bubble_theirs
                }
                bubble.setBackgroundResource(bubbleRes)
                bubble.setTextColor(context.getColor(if (msg.mine) R.color.white else R.color.text))
                val senderName = if (isGroup && !msg.mine) memberNames[msg.senderId] else null
                if (senderName != null) {
                    // Small bold brand-colored name line inside group bubbles.
                    val styled = SpannableStringBuilder()
                    styled.append(senderName)
                    styled.setSpan(StyleSpan(Typeface.BOLD), 0, senderName.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    styled.setSpan(RelativeSizeSpan(0.82f), 0, senderName.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    styled.setSpan(
                        ForegroundColorSpan(UiUtil.avatarColor(msg.senderId)),
                        0, senderName.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    styled.append("\n")
                    styled.append(msg.text)
                    bubble.text = styled
                } else {
                    bubble.text = msg.text
                }
            }
            else -> {
                bubble.setBackgroundResource(R.drawable.bubble_deleted)
                bubble.setTextColor(context.getColor(R.color.text2))
                bubble.text = "🔒 پیام رمزنگاری‌شده — روی این دستگاه قابل خواندن نیست"
            }
        }

        val createdInstant = UiUtil.parseInstant(msg.createdAt)
        val isRead = if (isGroup) {
            createdInstant != null && groupMemberIds.isNotEmpty() && groupMemberIds.all { memberId ->
                val readAt = memberReadAt[memberId]?.let { UiUtil.parseInstant(it) }
                readAt != null && !readAt.isBefore(createdInstant)
            }
        } else {
            val readInstant = UiUtil.parseInstant(peerLastReadAt)
            readInstant != null && createdInstant != null && !readInstant.isBefore(createdInstant)
        }
        val statusSuffix = if (msg.mine && !msg.deleted && !msg.expired) {
            when (msg.status) {
                LocalDb.STATUS_SENDING -> " · 🕐"
                LocalDb.STATUS_FAILED -> " · ⚠️ ارسال نشد"
                else -> if (isRead) " · ✓✓" else " · ✓"
            }
        } else {
            ""
        }
        val lockMark = if (msg.encrypted && !msg.deleted && !msg.expired) "🔒 " else ""
        val ttlMark = if (msg.ttlSeconds > 0 && !msg.expired && !msg.deleted) " · ⏱" else ""
        val editedMark = if (msg.edited && !msg.deleted && !msg.expired) " · ویرایش شد" else ""
        val forwardedMark = if (msg.forwarded && !msg.deleted && !msg.expired) " · ↪ بازارسال‌شده" else ""
        meta.text = lockMark + UiUtil.formatTime(msg.createdAt) + forwardedMark + editedMark + ttlMark + statusSuffix
        // Hide the time/status line inside a tight group (like iOS);
        // always keep it for failures and annotated messages.
        val needsMeta = !sameAsNext || msg.status == LocalDb.STATUS_FAILED ||
            msg.status == LocalDb.STATUS_SENDING || msg.edited || msg.forwarded ||
            msg.ttlSeconds > 0 || msg.deleted || msg.expired
        meta.visibility = if (needsMeta) View.VISIBLE else View.GONE
        // Gentle entrance for genuinely new tail messages (recycling-safe:
        // every bind first cancels and resets the transform).
        bubble.animate().cancel()
        bubble.alpha = 1f
        bubble.translationY = 0f
        bubble.scaleX = 1f
        bubble.scaleY = 1f
        if (msg.localId > lastAnimatedLocalId && position >= items.size - 3) {
            lastAnimatedLocalId = msg.localId
            bubble.alpha = 0f
            bubble.translationY = 26f
            bubble.scaleX = 0.96f
            bubble.scaleY = 0.96f
            bubble.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(230).start()
        }
        return view
    }
}
