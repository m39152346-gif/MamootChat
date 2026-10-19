import { useMemo, useState } from 'react'
import { conversationTitle, formatListTime, otherMember, faNum } from '../lib/format'
import type { ConversationSummary, Profile } from '../lib/types'
import { Avatar } from './Avatar'

export function ConversationList({
  conversations,
  activeId,
  myProfile,
  onlineIds,
  typingByConv,
  loading,
  onSelect,
  onNewChat,
  onOpenSettings,
}: {
  conversations: ConversationSummary[]
  activeId: string | null
  myProfile: Profile
  onlineIds: Set<string>
  typingByConv: Record<string, boolean>
  loading: boolean
  onSelect: (id: string) => void
  onNewChat: () => void
  onOpenSettings: () => void
}) {
  const [query, setQuery] = useState('')

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return conversations
    return conversations.filter((c) => {
      const title = conversationTitle(c, myProfile.id).toLowerCase()
      const preview = (c.last_message?.body ?? '').toLowerCase()
      const usernames = c.members.map((m) => m.username).join(' ')
      return title.includes(q) || preview.includes(q) || usernames.includes(q)
    })
  }, [conversations, query, myProfile.id])

  return (
    <aside className="sidebar">
      <header className="sidebar-head">
        <button type="button" className="me-chip" onClick={onOpenSettings} title="تنظیمات پروفایل">
          <Avatar name={myProfile.display_name} seed={myProfile.id} size="sm" />
          <span className="me-chip-text">
            <strong>{myProfile.display_name}</strong>
            <small dir="ltr">@{myProfile.username}</small>
          </span>
        </button>
        <button type="button" className="icon-btn" onClick={onNewChat} aria-label="گفتگوی جدید" title="گفتگوی جدید">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M12 20h9" /><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
          </svg>
        </button>
      </header>

      <div className="sidebar-search">
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="جستجو در گفتگوها…"
          aria-label="جستجو در گفتگوها"
        />
      </div>

      <nav className="conv-list" aria-label="فهرست گفتگوها">
        {loading && conversations.length === 0 ? (
          <div className="empty-state">در حال بارگذاری…</div>
        ) : filtered.length === 0 ? (
          <div className="empty-state">
            {conversations.length === 0 ? (
              <>
                <p>هنوز گفتگویی ندارید.</p>
                <button type="button" className="btn btn-primary" onClick={onNewChat}>شروع اولین گفتگو</button>
              </>
            ) : (
              <p>چیزی پیدا نشد.</p>
            )}
          </div>
        ) : (
          filtered.map((conv) => {
            const title = conversationTitle(conv, myProfile.id)
            const other = conv.kind === 'dm' ? otherMember(conv, myProfile.id) : undefined
            const isTyping = typingByConv[conv.id]
            const lm = conv.last_message
            const preview = isTyping
              ? 'در حال نوشتن…'
              : !lm
                ? 'گفتگو ایجاد شد'
                : lm.deleted_at
                  ? 'پیام حذف شد'
                  : `${lm.sender_id === myProfile.id ? 'شما: ' : ''}${lm.body}`
            const timeIso = lm?.created_at ?? conv.created_at
            return (
              <button
                key={conv.id}
                type="button"
                className={`conv-item ${conv.id === activeId ? 'active' : ''}`}
                onClick={() => onSelect(conv.id)}
              >
                <Avatar
                  name={title}
                  seed={other?.id ?? conv.id}
                  online={other ? onlineIds.has(other.id) : undefined}
                />
                <span className="conv-item-main">
                  <span className="conv-item-top">
                    <strong className="conv-title">{title}</strong>
                    <time className={`conv-time ${conv.unread_count > 0 ? 'has-unread' : ''}`}>{formatListTime(timeIso)}</time>
                  </span>
                  <span className="conv-item-bottom">
                    <span className={`conv-preview ${isTyping ? 'is-typing' : ''}`}>{preview}</span>
                    {conv.unread_count > 0 && <span className="unread-badge">{faNum(conv.unread_count)}</span>}
                  </span>
                </span>
              </button>
            )
          })
        )}
      </nav>
    </aside>
  )
}
