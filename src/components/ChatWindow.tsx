import { useEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import type { RealtimeChannel } from '@supabase/supabase-js'
import { supabase } from '../lib/supabase'
import {
  addGroupMembers,
  deleteMessage,
  editMessage,
  faError,
  fetchMessages,
  leaveConversation,
  markRead,
  searchUsers,
  sendMessage,
} from '../lib/api'
import { avatarColorClass, conversationTitle, dayKey, faNum, formatDayLabel, formatTime, otherMember } from '../lib/format'
import { MAX_MESSAGE_LEN, PAGE_SIZE, type ConversationSummary, type Message, type Profile } from '../lib/types'
import { Avatar } from './Avatar'
import { RichText } from './RichText'

interface Props {
  conv: ConversationSummary
  myProfile: Profile
  onlineIds: Set<string>
  onBack: () => void
  onMarkedRead: (conversationId: string) => void
  onTypingChange: (conversationId: string, typing: boolean) => void
  onRefreshConversations: () => void
  onLeft: (conversationId: string) => void
}

export function ChatWindow({
  conv,
  myProfile,
  onlineIds,
  onBack,
  onMarkedRead,
  onTypingChange,
  onRefreshConversations,
  onLeft,
}: Props) {
  const myId = myProfile.id
  const [messages, setMessages] = useState<Message[]>([])
  const [loading, setLoading] = useState(true)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [hasMore, setHasMore] = useState(false)
  const [input, setInput] = useState('')
  const [editing, setEditing] = useState<Message | null>(null)
  const [menuFor, setMenuFor] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<Message | null>(null)
  const [bannerError, setBannerError] = useState<string | null>(null)
  const [connError, setConnError] = useState(false)
  const [showInfo, setShowInfo] = useState(false)
  const [typingAt, setTypingAt] = useState<Record<string, number>>({})
  const [nowTick, setNowTick] = useState(0)
  const [visibleTick, setVisibleTick] = useState(0)
  const [readMap, setReadMap] = useState<Record<string, string>>(() =>
    Object.fromEntries(conv.members.map((m) => [m.id, m.last_read_at])),
  )

  const scrollRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const channelRef = useRef<RealtimeChannel | null>(null)
  const stickRef = useRef(true)
  const initialScrollDone = useRef(false)
  const skipAutoScroll = useRef(false)
  const lastTypingSent = useRef(0)
  const typingStopTimer = useRef<number | undefined>(undefined)

  const title = conversationTitle(conv, myId)
  const memberById = useMemo(() => new Map(conv.members.map((m) => [m.id, m])), [conv.members])

  // Keep read receipts in sync when the conversation list refreshes.
  useEffect(() => {
    setReadMap((prev) => {
      const next = { ...prev }
      for (const m of conv.members) {
        if (!next[m.id] || next[m.id] < m.last_read_at) next[m.id] = m.last_read_at
      }
      return next
    })
  }, [conv.members])

  // ---- initial load (component is keyed by conversation id in App)
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    fetchMessages(conv.id)
      .then((rows) => {
        if (cancelled) return
        setMessages(rows.map((r) => ({ ...r, status: 'sent' as const })))
        setHasMore(rows.length >= PAGE_SIZE)
      })
      .catch((err) => !cancelled && setBannerError(faError(err)))
      .finally(() => !cancelled && setLoading(false))
    textareaRef.current?.focus()
    return () => {
      cancelled = true
    }
  }, [conv.id])

  // ---- realtime: messages + read receipts + typing (broadcast carries no message content)
  useEffect(() => {
    const upsert = (row: Message) => {
      setMessages((prev) => {
        const byId = prev.findIndex((m) => m.id === row.id)
        if (byId >= 0) {
          const next = [...prev]
          next[byId] = { ...row, status: 'sent' }
          return next
        }
        const byClient = prev.findIndex((m) => m.client_id === row.client_id)
        if (byClient >= 0) {
          const next = [...prev]
          next[byClient] = { ...row, status: 'sent' }
          return next
        }
        return [...prev, { ...row, status: 'sent' as const }]
      })
    }

    const channel = supabase.channel(`conv:${conv.id}`, { config: { broadcast: { self: false } } })
    channelRef.current = channel

    channel
      .on(
        'postgres_changes',
        { event: 'INSERT', schema: 'public', table: 'messages', filter: `conversation_id=eq.${conv.id}` },
        (payload) => upsert(payload.new as Message),
      )
      .on(
        'postgres_changes',
        { event: 'UPDATE', schema: 'public', table: 'messages', filter: `conversation_id=eq.${conv.id}` },
        (payload) => upsert(payload.new as Message),
      )
      .on(
        'postgres_changes',
        { event: 'UPDATE', schema: 'public', table: 'conversation_members', filter: `conversation_id=eq.${conv.id}` },
        (payload) => {
          const row = payload.new as { user_id: string; last_read_at: string }
          setReadMap((prev) => ({ ...prev, [row.user_id]: row.last_read_at }))
        },
      )
      .on('broadcast', { event: 'typing' }, (payload) => {
        const p = payload.payload as { user_id?: string; typing?: boolean }
        if (!p.user_id || p.user_id === myId) return
        setTypingAt((prev) => {
          const next = { ...prev }
          if (p.typing) next[p.user_id!] = Date.now()
          else delete next[p.user_id!]
          return next
        })
      })
      .subscribe((status) => {
        setConnError(status === 'CHANNEL_ERROR' || status === 'TIMED_OUT' || status === 'CLOSED')
      })

    return () => {
      channelRef.current = null
      void supabase.removeChannel(channel)
    }
  }, [conv.id, myId])

  // typing expiry ticker
  useEffect(() => {
    const t = setInterval(() => setNowTick((n) => n + 1), 1000)
    return () => clearInterval(t)
  }, [])

  const typingNames = useMemo(() => {
    const cutoff = Date.now() - 3500
    return Object.entries(typingAt)
      .filter(([uid, at]) => uid !== myId && at > cutoff)
      .map(([uid]) => memberById.get(uid)?.display_name ?? 'کاربر')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [typingAt, memberById, myId, nowTick])

  useEffect(() => {
    onTypingChange(conv.id, typingNames.length > 0)
  }, [conv.id, typingNames.length, onTypingChange])

  useEffect(() => () => onTypingChange(conv.id, false), [conv.id, onTypingChange])

  // ---- scroll behaviour
  useEffect(() => {
    const el = scrollRef.current
    if (!el || messages.length === 0) return
    if (skipAutoScroll.current) {
      skipAutoScroll.current = false
      return
    }
    if (!initialScrollDone.current) {
      initialScrollDone.current = true
      el.scrollTop = el.scrollHeight
      return
    }
    const last = messages[messages.length - 1]
    if (last.sender_id === myId || stickRef.current) {
      el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
    }
  }, [messages, myId])

  function handleScroll() {
    const el = scrollRef.current
    if (!el) return
    stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 140
    if (el.scrollTop < 80 && hasMore && !loadingOlder && messages.length > 0) {
      void loadOlder()
    }
  }

  async function loadOlder() {
    const el = scrollRef.current
    const oldest = messages[0]
    if (!el || !oldest) return
    setLoadingOlder(true)
    const prevHeight = el.scrollHeight
    try {
      const rows = await fetchMessages(conv.id, oldest.created_at)
      setHasMore(rows.length >= PAGE_SIZE)
      if (rows.length > 0) {
        skipAutoScroll.current = true
        setMessages((prev) => {
          const known = new Set(prev.map((m) => m.id))
          return [...rows.filter((r) => !known.has(r.id)).map((r) => ({ ...r, status: 'sent' as const })), ...prev]
        })
        requestAnimationFrame(() => {
          const node = scrollRef.current
          if (node) node.scrollTop = node.scrollHeight - prevHeight
        })
      }
    } catch (err) {
      setBannerError(faError(err))
    } finally {
      setLoadingOlder(false)
    }
  }

  // ---- mark as read (only while actually looking at the tab)
  useEffect(() => {
    const bump = () => document.visibilityState === 'visible' && setVisibleTick((n) => n + 1)
    document.addEventListener('visibilitychange', bump)
    window.addEventListener('focus', bump)
    return () => {
      document.removeEventListener('visibilitychange', bump)
      window.removeEventListener('focus', bump)
    }
  }, [])

  useEffect(() => {
    const last = messages[messages.length - 1]
    if (!last || last.sender_id === myId || document.visibilityState !== 'visible') return
    const t = setTimeout(() => {
      markRead(conv.id)
        .then(() => onMarkedRead(conv.id))
        .catch(() => undefined)
    }, 350)
    return () => clearTimeout(t)
  }, [messages, conv.id, myId, onMarkedRead, visibleTick])

  // ---- composer
  function autosize() {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 140)}px`
  }

  function signalTyping() {
    const now = Date.now()
    if (now - lastTypingSent.current > 2000) {
      lastTypingSent.current = now
      void channelRef.current?.send({
        type: 'broadcast',
        event: 'typing',
        payload: { user_id: myId, typing: true },
      })
    }
    window.clearTimeout(typingStopTimer.current)
    typingStopTimer.current = window.setTimeout(() => {
      void channelRef.current?.send({
        type: 'broadcast',
        event: 'typing',
        payload: { user_id: myId, typing: false },
      })
    }, 2500)
  }

  async function deliver(body: string, clientId: string) {
    try {
      const row = await sendMessage(conv.id, myId, body, clientId)
      setMessages((prev) => prev.map((m) => (m.client_id === clientId ? { ...row, status: 'sent' as const } : m)))
      onRefreshConversations()
    } catch (err) {
      setMessages((prev) => prev.map((m) => (m.client_id === clientId ? { ...m, status: 'failed' as const } : m)))
      setBannerError(faError(err))
    }
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    const body = input.trim()
    if (!body) return
    setBannerError(null)

    if (editing) {
      const target = editing
      setEditing(null)
      setInput('')
      requestAnimationFrame(autosize)
      setMessages((prev) =>
        prev.map((m) => (m.id === target.id ? { ...m, body, edited_at: new Date().toISOString() } : m)),
      )
      try {
        const row = await editMessage(target.id, body)
        setMessages((prev) => prev.map((m) => (m.id === row.id ? { ...row, status: 'sent' as const } : m)))
        onRefreshConversations()
      } catch (err) {
        setMessages((prev) => prev.map((m) => (m.id === target.id ? target : m)))
        setBannerError(faError(err))
      }
      return
    }

    const clientId = crypto.randomUUID()
    const optimistic: Message = {
      id: clientId,
      client_id: clientId,
      conversation_id: conv.id,
      sender_id: myId,
      body,
      created_at: new Date().toISOString(),
      edited_at: null,
      deleted_at: null,
      status: 'sending',
    }
    setMessages((prev) => [...prev, optimistic])
    setInput('')
    requestAnimationFrame(autosize)
    void channelRef.current?.send({
      type: 'broadcast',
      event: 'typing',
      payload: { user_id: myId, typing: false },
    })
    void deliver(body, clientId)
  }

  function onComposerKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      void onSubmit(e as unknown as FormEvent)
    }
    if (e.key === 'Escape' && editing) {
      setEditing(null)
      setInput('')
    }
  }

  async function doDelete() {
    const target = confirmDelete
    if (!target) return
    setConfirmDelete(null)
    try {
      const row = await deleteMessage(target.id)
      setMessages((prev) => prev.map((m) => (m.id === row.id ? { ...row, status: 'sent' as const } : m)))
      onRefreshConversations()
    } catch (err) {
      setBannerError(faError(err))
    }
  }

  function retry(msg: Message) {
    setBannerError(null)
    setMessages((prev) => prev.map((m) => (m.client_id === msg.client_id ? { ...m, status: 'sending' as const } : m)))
    void deliver(msg.body, msg.client_id)
  }

  function isSeenByOthers(msg: Message): boolean {
    const others = conv.members.filter((m) => m.id !== myId)
    if (others.length === 0) return false
    const at = new Date(msg.created_at).getTime()
    return others.every((m) => {
      const readAt = readMap[m.id] ?? m.last_read_at
      return Boolean(readAt) && new Date(readAt).getTime() >= at
    })
  }

  const other = conv.kind === 'dm' ? otherMember(conv, myId) : undefined
  const onlineCount = conv.members.filter((m) => m.id !== myId && onlineIds.has(m.id)).length
  const statusText =
    typingNames.length > 0
      ? conv.kind === 'dm'
        ? 'در حال نوشتن…'
        : `${typingNames.join('، ')} در حال نوشتن…`
      : conv.kind === 'dm'
        ? other && onlineIds.has(other.id)
          ? 'آنلاین'
          : 'آفلاین'
        : `${faNum(conv.members.length)} عضو${onlineCount > 0 ? `، ${faNum(onlineCount)} آنلاین` : ''}`

  return (
    <section className="chat-pane">
      <header className="chat-head">
        <button type="button" className="icon-btn back-btn" onClick={onBack} aria-label="بازگشت به فهرست گفتگوها">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="m9 6 6 6-6 6" />
          </svg>
        </button>
        <button type="button" className="chat-head-main" onClick={() => setShowInfo(true)} aria-label="اطلاعات گفتگو">
          <Avatar name={title} seed={other?.id ?? conv.id} online={other ? onlineIds.has(other.id) : undefined} />
          <span className="chat-head-text">
            <strong>{title}</strong>
            <small className={typingNames.length > 0 ? 'is-typing' : ''}>{statusText}</small>
          </span>
        </button>
      </header>

      {connError && <div className="conn-banner" role="status">اتصال لحظه‌ای قطع شده است؛ در حال تلاش برای اتصال دوباره…</div>}
      {bannerError && (
        <div className="conn-banner is-error" role="alert">
          {bannerError}
          <button type="button" className="banner-close" onClick={() => setBannerError(null)} aria-label="بستن پیام">✕</button>
        </div>
      )}

      <div className="messages" ref={scrollRef} onScroll={handleScroll}>
        {loading ? (
          <div className="empty-state">در حال بارگذاری پیام‌ها…</div>
        ) : messages.length === 0 ? (
          <div className="empty-state">
            <div className="empty-emoji" aria-hidden="true">👋</div>
            <p>هنوز پیامی اینجا نیست. اولین پیام را بفرستید!</p>
          </div>
        ) : (
          <>
            {loadingOlder && <div className="loading-older">…</div>}
            {messages.map((msg, i) => {
              const mine = msg.sender_id === myId
              const prev = messages[i - 1]
              const showDay = !prev || dayKey(prev.created_at) !== dayKey(msg.created_at)
              const sender = memberById.get(msg.sender_id)
              const showSender =
                conv.kind === 'group' && !mine && !msg.deleted_at &&
                (!prev || prev.sender_id !== msg.sender_id || showDay)
              return (
                <div key={msg.client_id + msg.id}>
                  {showDay && (
                    <div className="day-sep">
                      <span>{formatDayLabel(msg.created_at)}</span>
                    </div>
                  )}
                  <div className={`msg-row ${mine ? 'mine' : 'theirs'}`}>
                    <div className={`bubble ${msg.deleted_at ? 'is-deleted' : ''} ${msg.status === 'failed' ? 'is-failed' : ''}`}>
                      {showSender && sender && (
                        <div className={`msg-sender name-c${avatarColorClass(sender.id).slice(-1)}`}>{sender.display_name}</div>
                      )}
                      {msg.deleted_at ? (
                        <span className="deleted-text">این پیام حذف شد</span>
                      ) : (
                        <RichText text={msg.body} />
                      )}
                      <span className="msg-meta">
                        {msg.edited_at && !msg.deleted_at && <span className="edited">ویرایش شد</span>}
                        <time>{formatTime(msg.created_at)}</time>
                        {mine && !msg.deleted_at && (
                          <span className={`ticks ${isSeenByOthers(msg) ? 'seen' : ''}`} aria-label={isSeenByOthers(msg) ? 'خوانده شد' : 'ارسال شد'}>
                            {msg.status === 'sending' ? '🕐' : isSeenByOthers(msg) ? '✓✓' : '✓'}
                          </span>
                        )}
                      </span>
                      {!msg.deleted_at && msg.status !== 'sending' && (
                        <button type="button" className="msg-menu-btn" aria-label="گزینه‌های پیام" onClick={() => setMenuFor(menuFor === msg.id ? null : msg.id)}>
                          ⌄
                        </button>
                      )}
                      {menuFor === msg.id && (
                        <>
                          <div className="menu-backdrop" onClick={() => setMenuFor(null)} role="presentation" />
                          <div className="msg-menu" role="menu">
                            <button type="button" role="menuitem" onClick={() => { void navigator.clipboard?.writeText(msg.body).catch(() => undefined); setMenuFor(null) }}>
                              کپی متن
                            </button>
                            {mine && (
                              <button type="button" role="menuitem" onClick={() => { setEditing(msg); setInput(msg.body); setMenuFor(null); requestAnimationFrame(() => { autosize(); textareaRef.current?.focus() }) }}>
                                ویرایش
                              </button>
                            )}
                            {mine && (
                              <button type="button" role="menuitem" className="danger" onClick={() => { setConfirmDelete(msg); setMenuFor(null) }}>
                                حذف برای همه
                              </button>
                            )}
                          </div>
                        </>
                      )}
                    </div>
                    {msg.status === 'failed' && (
                      <button type="button" className="retry-btn" onClick={() => retry(msg)}>ارسال نشد؛ تلاش دوباره</button>
                    )}
                  </div>
                </div>
              )
            })}
          </>
        )}
      </div>

      {confirmDelete && (
        <div className="confirm-bar" role="alertdialog" aria-label="تأیید حذف پیام">
          <span>این پیام برای همه حذف شود؟</span>
          <button type="button" className="btn btn-danger btn-sm" onClick={() => void doDelete()}>حذف</button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setConfirmDelete(null)}>لغو</button>
        </div>
      )}

      {editing && (
        <div className="editing-bar">
          <span>در حال ویرایش پیام</span>
          <button type="button" className="link-btn" onClick={() => { setEditing(null); setInput('') }}>لغو</button>
        </div>
      )}

      <form className="composer" onSubmit={(e) => void onSubmit(e)}>
        <textarea
          ref={textareaRef}
          value={input}
          rows={1}
          maxLength={MAX_MESSAGE_LEN}
          placeholder="پیام بنویسید…"
          aria-label="متن پیام"
          onChange={(e) => {
            setInput(e.target.value)
            autosize()
            if (e.target.value.trim()) signalTyping()
          }}
          onKeyDown={onComposerKeyDown}
        />
        {input.length > 3500 && <span className="char-count">{faNum(input.length)}/{faNum(MAX_MESSAGE_LEN)}</span>}
        <button type="submit" className="send-btn" disabled={!input.trim()} aria-label={editing ? 'ذخیره ویرایش' : 'ارسال پیام'}>
          {editing ? '✓' : (
            <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true">
              <path d="M3.4 20.4 21.8 12 3.4 3.6 3.3 10l13 2-13 2z" transform="scale(-1,1) translate(-24,0)" />
            </svg>
          )}
        </button>
      </form>

      {showInfo && (
        <ConversationInfo
          conv={conv}
          myId={myId}
          onlineIds={onlineIds}
          onClose={() => setShowInfo(false)}
          onChanged={onRefreshConversations}
          onLeft={() => onLeft(conv.id)}
        />
      )}
    </section>
  )
}

function ConversationInfo({
  conv,
  myId,
  onlineIds,
  onClose,
  onChanged,
  onLeft,
}: {
  conv: ConversationSummary
  myId: string
  onlineIds: Set<string>
  onClose: () => void
  onChanged: () => void
  onLeft: () => void
}) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<Profile[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [confirmLeave, setConfirmLeave] = useState(false)
  const memberIds = new Set(conv.members.map((m) => m.id))

  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  useEffect(() => {
    const q = query.trim()
    if (q.length < 2) {
      setResults([])
      return
    }
    const t = setTimeout(() => {
      searchUsers(q).then(setResults).catch((err) => setError(faError(err)))
    }, 250)
    return () => clearTimeout(t)
  }, [query])

  async function addMember(user: Profile) {
    setBusy(true)
    setError(null)
    try {
      await addGroupMembers(conv.id, [user.id])
      setQuery('')
      setResults([])
      onChanged()
    } catch (err) {
      setError(faError(err))
    } finally {
      setBusy(false)
    }
  }

  async function leave() {
    setBusy(true)
    try {
      await leaveConversation(conv.id)
      onLeft()
      onClose()
    } catch (err) {
      setError(faError(err))
    } finally {
      setBusy(false)
    }
  }

  const title = conversationTitle(conv, myId)

  return (
    <div className="modal-overlay" onClick={onClose} role="presentation">
      <div className="modal" role="dialog" aria-modal="true" aria-label="اطلاعات گفتگو" onClick={(e) => e.stopPropagation()}>
        <header className="modal-head">
          <h2>{conv.kind === 'group' ? 'اطلاعات گروه' : 'اطلاعات گفتگو'}</h2>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="بستن">✕</button>
        </header>

        <div className="profile-hero">
          <Avatar name={title} seed={conv.id} size="lg" />
          <div>
            <strong>{title}</strong>
            <small>{faNum(conv.members.length)} عضو</small>
          </div>
        </div>

        <h3 className="section-title">اعضا</h3>
        <div className="member-list">
          {conv.members.map((m) => (
            <div key={m.id} className="member-row">
              <Avatar name={m.display_name} seed={m.id} size="sm" online={onlineIds.has(m.id)} />
              <span className="user-row-text">
                <strong>{m.display_name}{m.id === myId ? ' (شما)' : ''}</strong>
                <small dir="ltr">@{m.username}</small>
              </span>
              {m.role === 'owner' && <span className="role-badge">سازنده</span>}
              <span className={`online-label ${onlineIds.has(m.id) ? 'on' : ''}`}>{onlineIds.has(m.id) ? 'آنلاین' : 'آفلاین'}</span>
            </div>
          ))}
        </div>

        {conv.kind === 'group' && (
          <>
            <h3 className="section-title">افزودن عضو</h3>
            <label className="field">
              <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="جستجوی کاربر…" aria-label="جستجو برای افزودن عضو" />
            </label>
            <div className="user-results compact">
              {results.filter((u) => !memberIds.has(u.id)).map((user) => (
                <button key={user.id} type="button" className="user-row" disabled={busy} onClick={() => void addMember(user)}>
                  <Avatar name={user.display_name} seed={user.id} size="sm" />
                  <span className="user-row-text">
                    <strong>{user.display_name}</strong>
                    <small dir="ltr">@{user.username}</small>
                  </span>
                  <span className="add-label">افزودن</span>
                </button>
              ))}
            </div>
            {error && <p className="alert alert-error" role="alert">{error}</p>}
            {confirmLeave ? (
              <div className="confirm-inline">
                <span>از گروه خارج می‌شوید؟</span>
                <button type="button" className="btn btn-danger btn-sm" disabled={busy} onClick={() => void leave()}>خروج از گروه</button>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => setConfirmLeave(false)}>لغو</button>
              </div>
            ) : (
              <button type="button" className="btn btn-danger btn-block" onClick={() => setConfirmLeave(true)}>
                خروج از گروه
              </button>
            )}
          </>
        )}
      </div>
    </div>
  )
}
