import { useEffect, useState, type FormEvent } from 'react'
import { faError, updateProfile } from '../lib/api'
import { USERNAME_RE, type Profile } from '../lib/types'
import { Avatar } from './Avatar'

export function SettingsModal({
  profile,
  email,
  onClose,
  onSaved,
  onLogout,
}: {
  profile: Profile
  email: string
  onClose: () => void
  onSaved: (profile: Profile) => void
  onLogout: () => void
}) {
  const [displayName, setDisplayName] = useState(profile.display_name)
  const [username, setUsername] = useState(profile.username)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setSaved(false)
    if (!displayName.trim()) return setError('نام نمایشی را وارد کنید.')
    if (!USERNAME_RE.test(username.trim()))
      return setError('نام کاربری باید ۳ تا ۲۴ نویسه و فقط شامل حروف انگلیسی، عدد، نقطه یا _ باشد.')
    setBusy(true)
    try {
      const updated = await updateProfile(profile.id, username, displayName)
      onSaved(updated)
      setSaved(true)
    } catch (err) {
      setError(faError(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose} role="presentation">
      <div className="modal" role="dialog" aria-modal="true" aria-label="تنظیمات پروفایل" onClick={(e) => e.stopPropagation()}>
        <header className="modal-head">
          <h2>پروفایل من</h2>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="بستن">✕</button>
        </header>

        <div className="profile-hero">
          <Avatar name={displayName || profile.display_name} seed={profile.id} size="lg" />
          <div>
            <strong>{profile.display_name}</strong>
            <small dir="ltr">@{profile.username}</small>
            <small dir="ltr">{email}</small>
          </div>
        </div>

        <form onSubmit={onSubmit} className="auth-form">
          <label>
            نام نمایشی
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={50} required />
          </label>
          <label>
            نام کاربری (انگلیسی)
            <input value={username} onChange={(e) => setUsername(e.target.value)} maxLength={24} required dir="ltr" />
          </label>
          {error && <p className="alert alert-error" role="alert">{error}</p>}
          {saved && <p className="alert alert-ok" role="status">ذخیره شد.</p>}
          <button className="btn btn-primary btn-block" disabled={busy} type="submit">
            {busy ? 'در حال ذخیره…' : 'ذخیره تغییرات'}
          </button>
        </form>

        <button type="button" className="btn btn-danger btn-block" onClick={onLogout}>
          خروج از حساب
        </button>
      </div>
    </div>
  )
}
