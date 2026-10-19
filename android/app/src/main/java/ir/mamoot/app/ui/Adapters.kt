package ir.mamoot.app.ui

import android.content.Context
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
        avatar.text = UiUtil.initials(user.displayName)
        avatar.background.mutate().setTint(UiUtil.avatarColor(user.id))
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

    var peerLastReadAt: String = ""
    var isGroup: Boolean = false
    var memberNames: Map<String, String> = emptyMap()

    fun submit(newItems: List<UiMessage>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

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
        val maxWidth = (context.resources.displayMetrics.widthPixels * 0.79).toInt()
        bubble.maxWidth = maxWidth

        val gravity = if (msg.mine) Gravity.END else Gravity.START
        (bubble.layoutParams as LinearLayout.LayoutParams).gravity = gravity
        (meta.layoutParams as LinearLayout.LayoutParams).gravity = gravity

        when {
            msg.deleted -> {
                bubble.setBackgroundResource(R.drawable.bubble_deleted)
                bubble.setTextColor(context.getColor(R.color.text2))
                bubble.text = "این پیام حذف شد"
            }
            msg.text != null -> {
                bubble.setBackgroundResource(if (msg.mine) R.drawable.bubble_mine else R.drawable.bubble_theirs)
                val senderPrefix = if (isGroup && !msg.mine) {
                    memberNames[msg.senderId]?.let { "$it\n" } ?: ""
                } else {
                    ""
                }
                bubble.text = senderPrefix + msg.text
                bubble.setTextColor(context.getColor(if (msg.mine) R.color.white else R.color.text))
            }
            else -> {
                bubble.setBackgroundResource(R.drawable.bubble_deleted)
                bubble.setTextColor(context.getColor(R.color.text2))
                bubble.text = "🔒 پیام رمزنگاری‌شده — روی این دستگاه قابل خواندن نیست"
            }
        }

        val statusSuffix = if (msg.mine && !msg.deleted) {
            when (msg.status) {
                LocalDb.STATUS_SENDING -> " · 🕐"
                LocalDb.STATUS_FAILED -> " · ⚠️ ارسال نشد"
                else -> if (peerLastReadAt.isNotEmpty() && peerLastReadAt >= msg.createdAt) " · ✓✓" else " · ✓"
            }
        } else {
            ""
        }
        meta.text = UiUtil.formatTime(msg.createdAt) + statusSuffix
        return view
    }
}
