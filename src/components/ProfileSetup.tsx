import { useState, type FormEvent } from 'react'
import { createProfile, faError } from '../lib/api'
import { USERNAME_RE, type Profile } from '../lib/types'
import { PENDING_PROFILE_KEY } from './AuthScreen'

export function ProfileSetup({
  userId,
  initial,
  onReady,
  onLogout,
}: {
  userId: string
  initial?: { username: string; display_name: string } | null
  onReady: (profile: Profile) => void
  onLogout: () => void
}) {
  const [displayName, setDisplayName] = useState(initial?.display_name ?? '')
  const [username, setUsername] = useState(initial?.username ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (!displayName.trim()) return setError('نام نمایشی را وارد کنید.')
    if (!USERNAME_RE.test(username.trim()))
      return setError('نام کاربری باید ۳ تا ۲۴ نویسه و فقط شامل حروف انگلیسی، عدد، نقطه یا _ باشد.')
    setBusy(true)
    try {
      const profile = await createProfile(userId, username, displayName)
      localStorage.removeItem(PENDING_PROFILE_KEY)
      onReady(profile)
    } catch (err) {
      setError(faError(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-logo" aria-hidden="true">🦣</div>
        <h1>تکمیل پروفایل</h1>
        <p className="auth-sub">یک نام برای نمایش و یک نام کاربری یکتا انتخاب کنید.</p>
        <form onSubmit={onSubmit} className="auth-form">
          <label>
            نام نمایشی
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={50} required autoComplete="name" />
          </label>
          <label>
            نام کاربری (انگلیسی)
            <input value={username} onChange={(e) => setUsername(e.target.value)} maxLength={24} required dir="ltr" placeholder="mammoth_42" />
          </label>
          {error && <p className="alert alert-error" role="alert">{error}</p>}
          <button className="btn btn-primary btn-block" disabled={busy} type="submit">
            {busy ? 'لطفاً صبر کنید…' : 'شروع گفتگو'}
          </button>
        </form>
        <button type="button" className="link-btn" onClick={onLogout}>خروج از حساب</button>
      </div>
    </div>
  )
}
