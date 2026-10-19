import { useState, type FormEvent } from 'react'
import { supabase } from '../lib/supabase'
import { faError } from '../lib/api'
import { USERNAME_RE } from '../lib/types'

export const PENDING_PROFILE_KEY = 'mamootchat:pending_profile'

type Mode = 'login' | 'signup' | 'forgot'

function strongEnough(password: string): boolean {
  return password.length >= 8 && /[a-zA-Z]/.test(password) && /\d/.test(password)
}

export function AuthScreen() {
  const [mode, setMode] = useState<Mode>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [username, setUsername] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setNotice(null)

    if (mode === 'signup') {
      if (!displayName.trim()) return setError('نام نمایشی را وارد کنید.')
      if (!USERNAME_RE.test(username.trim()))
        return setError('نام کاربری باید ۳ تا ۲۴ نویسه و فقط شامل حروف انگلیسی، عدد، نقطه یا _ باشد.')
      if (!strongEnough(password))
        return setError('رمز عبور باید حداقل ۸ نویسه و شامل حرف و عدد باشد.')
    }
    if (mode !== 'forgot' && password.length < 1) return setError('رمز عبور را وارد کنید.')

    setBusy(true)
    try {
      if (mode === 'login') {
        const { error } = await supabase.auth.signInWithPassword({ email: email.trim(), password })
        if (error) throw error
      } else if (mode === 'signup') {
        // Keep only non-sensitive profile hints so we can create the profile
        // right after the session exists (also works if email confirmation is on).
        localStorage.setItem(
          PENDING_PROFILE_KEY,
          JSON.stringify({ username: username.trim().toLowerCase(), display_name: displayName.trim() }),
        )
        const { data, error } = await supabase.auth.signUp({ email: email.trim(), password })
        if (error) {
          localStorage.removeItem(PENDING_PROFILE_KEY)
          throw error
        }
        if (!data.session) setNotice('حساب ساخته شد؛ برای تأیید، ایمیلتان را بررسی کنید و سپس وارد شوید.')
      } else {
        const { error } = await supabase.auth.resetPasswordForEmail(email.trim(), {
          redirectTo: window.location.origin,
        })
        if (error) throw error
        setNotice('اگر این ایمیل ثبت شده باشد، لینک بازیابی رمز عبور برایتان ارسال می‌شود.')
      }
    } catch (err) {
      setError(faError(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-logo" aria-hidden="true">💬</div>
        <h1>مموت</h1>
        <p className="auth-sub">پیام‌رسان متنی سریع و امن؛ فقط متن، بدون حاشیه.</p>

        <div className="tabs" role="tablist" aria-label="ورود یا ثبت‌نام">
          <button type="button" role="tab" aria-selected={mode === 'login'} className={mode === 'login' ? 'active' : ''} onClick={() => setMode('login')}>
            ورود
          </button>
          <button type="button" role="tab" aria-selected={mode === 'signup'} className={mode === 'signup' ? 'active' : ''} onClick={() => setMode('signup')}>
            ثبت‌نام
          </button>
        </div>

        <form onSubmit={onSubmit} className="auth-form" noValidate={false}>
          {mode === 'signup' && (
            <>
              <label>
                نام نمایشی
                <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={50} required autoComplete="name" placeholder="مثلاً علی رضایی" />
              </label>
              <label>
                نام کاربری (انگلیسی)
                <input value={username} onChange={(e) => setUsername(e.target.value)} maxLength={24} required autoComplete="username" dir="ltr" placeholder="mamoot_42" />
                <small className="hint">دیگران با این نام شما را پیدا می‌کنند: <span dir="ltr">@{username.trim().toLowerCase() || 'username'}</span></small>
              </label>
            </>
          )}

          <label>
            ایمیل
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" dir="ltr" placeholder="you@example.com" />
          </label>

          {mode !== 'forgot' && (
            <label>
              رمز عبور
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                autoComplete={mode === 'signup' ? 'new-password' : 'current-password'}
                dir="ltr"
              />
              {mode === 'signup' && <small className="hint">حداقل ۸ نویسه، شامل حرف و عدد.</small>}
            </label>
          )}

          {error && <p className="alert alert-error" role="alert">{error}</p>}
          {notice && <p className="alert alert-ok" role="status">{notice}</p>}

          <button className="btn btn-primary btn-block" disabled={busy} type="submit">
            {busy ? 'لطفاً صبر کنید…' : mode === 'login' ? 'ورود' : mode === 'signup' ? 'ساخت حساب' : 'ارسال لینک بازیابی'}
          </button>
        </form>

        {mode === 'login' ? (
          <button type="button" className="link-btn" onClick={() => setMode('forgot')}>رمز عبور را فراموش کرده‌ام</button>
        ) : mode === 'forgot' ? (
          <button type="button" className="link-btn" onClick={() => setMode('login')}>بازگشت به ورود</button>
        ) : null}

        <p className="auth-foot">🔒 پیام‌ها روی بستر امن (TLS) منتقل و دسترسی هر کاربر در سطح دیتابیس محدود می‌شود.</p>
      </div>
    </div>
  )
}

export function ResetPasswordScreen({ onDone }: { onDone: () => void }) {
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (!strongEnough(password)) return setError('رمز عبور باید حداقل ۸ نویسه و شامل حرف و عدد باشد.')
    setBusy(true)
    try {
      const { error } = await supabase.auth.updateUser({ password })
      if (error) throw error
      onDone()
    } catch (err) {
      setError(faError(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <h1>رمز عبور جدید</h1>
        <form onSubmit={onSubmit} className="auth-form">
          <label>
            رمز عبور جدید
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required autoComplete="new-password" dir="ltr" />
          </label>
          {error && <p className="alert alert-error" role="alert">{error}</p>}
          <button className="btn btn-primary btn-block" disabled={busy} type="submit">
            {busy ? 'لطفاً صبر کنید…' : 'ذخیره رمز عبور'}
          </button>
        </form>
      </div>
    </div>
  )
}
