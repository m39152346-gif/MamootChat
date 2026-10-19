import { useEffect, useRef, useState } from 'react'
import { createGroup, faError, getOrCreateDm, searchUsers } from '../lib/api'
import type { Profile } from '../lib/types'
import { Avatar } from './Avatar'

export function NewChatModal({
  onClose,
  onCreated,
}: {
  onClose: () => void
  onCreated: (conversationId: string) => void
}) {
  const [tab, setTab] = useState<'dm' | 'group'>('dm')
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<Profile[]>([])
  const [searching, setSearching] = useState(false)
  const [selected, setSelected] = useState<Profile[]>([])
  const [groupTitle, setGroupTitle] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const searchSeq = useRef(0)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    inputRef.current?.focus()
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  useEffect(() => {
    const q = query.trim()
    if (q.length < 2) {
      setResults([])
      setSearching(false)
      return
    }
    setSearching(true)
    const seq = ++searchSeq.current
    const t = setTimeout(async () => {
      try {
        const users = await searchUsers(q)
        if (searchSeq.current === seq) setResults(users)
      } catch (err) {
        if (searchSeq.current === seq) setError(faError(err))
      } finally {
        if (searchSeq.current === seq) setSearching(false)
      }
    }, 250)
    return () => clearTimeout(t)
  }, [query])

  async function startDm(user: Profile) {
    setBusy(true)
    setError(null)
    try {
      const id = await getOrCreateDm(user.id)
      onCreated(id)
      onClose()
    } catch (err) {
      setError(faError(err))
    } finally {
      setBusy(false)
    }
  }

  function toggleSelect(user: Profile) {
    setSelected((prev) =>
      prev.some((u) => u.id === user.id) ? prev.filter((u) => u.id !== user.id) : [...prev, user],
    )
  }

  async function submitGroup() {
    if (!groupTitle.trim()) return setError('نام گروه را وارد کنید.')
    if (selected.length === 0) return setError('حداقل یک عضو برای گروه انتخاب کنید.')
    setBusy(true)
    setError(null)
    try {
      const id = await createGroup(groupTitle, selected.map((u) => u.id))
      onCreated(id)
      onClose()
    } catch (err) {
      setError(faError(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose} role="presentation">
      <div className="modal" role="dialog" aria-modal="true" aria-label="گفتگوی جدید" onClick={(e) => e.stopPropagation()}>
        <header className="modal-head">
          <h2>گفتگوی جدید</h2>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="بستن">✕</button>
        </header>

        <div className="tabs">
          <button type="button" className={tab === 'dm' ? 'active' : ''} onClick={() => setTab('dm')}>گفتگوی خصوصی</button>
          <button type="button" className={tab === 'group' ? 'active' : ''} onClick={() => setTab('group')}>گروه جدید</button>
        </div>

        {tab === 'group' && (
          <label className="field">
            نام گروه
            <input value={groupTitle} onChange={(e) => setGroupTitle(e.target.value)} maxLength={80} placeholder="مثلاً تیم پروژه" />
          </label>
        )}

        <label className="field">
          جستجوی کاربر
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="نام یا نام کاربری (حداقل ۲ نویسه)…"
            aria-label="جستجوی کاربر"
          />
        </label>

        {tab === 'group' && selected.length > 0 && (
          <div className="chips">
            {selected.map((u) => (
              <button key={u.id} type="button" className="chip" onClick={() => toggleSelect(u)} title="حذف">
                {u.display_name} ✕
              </button>
            ))}
          </div>
        )}

        {error && <p className="alert alert-error" role="alert">{error}</p>}

        <div className="user-results">
          {query.trim().length < 2 ? (
            <p className="muted center">برای پیدا کردن مخاطب، حداقل ۲ نویسه از نام یا نام کاربری‌اش را بنویسید.</p>
          ) : searching ? (
            <p className="muted center">در حال جستجو…</p>
          ) : results.length === 0 ? (
            <p className="muted center">کاربری پیدا نشد.</p>
          ) : (
            results.map((user) => {
              const isSelected = selected.some((u) => u.id === user.id)
              return (
                <button
                  key={user.id}
                  type="button"
                  className={`user-row ${isSelected ? 'selected' : ''}`}
                  disabled={busy}
                  onClick={() => (tab === 'dm' ? void startDm(user) : toggleSelect(user))}
                >
                  <Avatar name={user.display_name} seed={user.id} size="sm" />
                  <span className="user-row-text">
                    <strong>{user.display_name}</strong>
                    <small dir="ltr">@{user.username}</small>
                  </span>
                  {tab === 'group' && <span className="check">{isSelected ? '✓' : ''}</span>}
                </button>
              )
            })
          )}
        </div>

        {tab === 'group' && (
          <button type="button" className="btn btn-primary btn-block" disabled={busy} onClick={() => void submitGroup()}>
            {busy ? 'در حال ساخت…' : 'ساخت گروه'}
          </button>
        )}
      </div>
    </div>
  )
}
