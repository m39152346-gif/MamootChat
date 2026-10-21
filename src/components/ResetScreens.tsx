import { useState, type FormEvent } from 'react'
import { supabase } from '../lib/supabase'
import { faError, strongEnough } from '../lib/util'

export function ForgotPasswordForm() {
  const [email, setEmail] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const { error } = await supabase.auth.resetPasswordForEmail(email.trim(), {
        redirectTo: window.location.origin + '/reset',
      })
      if (error) throw error
      setNotice('اگر این ایمیل ثبت شده باشد، لینک بازیابی رمز عبور برایتان ارسال می‌شود.')
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
        <h1>بازیابی رمز عبور</h1>
        <p className="auth-sub">
          ایمیل حسابتان را وارد کنید تا لینک تغییر رمز عبور برایتان ارسال شود. بعد از
          تغییر رمز، در برنامه اندروید مموت با رمز جدید وارد شوید.
        </p>
        <form onSubmit={onSubmit} className="auth-form">
          <label>
            ایمیل
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              autoComplete="email"
              dir="ltr"
            />
          </label>
          {error && <p className="alert alert-error" role="alert">{error}</p>}
          {notice && <p className="alert alert-ok" role="status">{notice}</p>}
          <button className="btn btn-primary btn-block" disabled={busy} type="submit">
            {busy ? 'لطفاً صبر کنید…' : 'ارسال لینک بازیابی'}
          </button>
        </form>
        <p className="auth-sub" style={{ marginTop: 16 }}>
          <a href="/">← بازگشت</a>
        </p>
      </div>
    </div>
  )
}

export function ResetPasswordScreen({ onDone }: { onDone: () => void }) {
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (!strongEnough(password)) return setError('رمز عبور باید حداقل ۸ نویسه و شامل حرف و عدد باشد.')
    setBusy(true)
    try {
      const { error } = await supabase.auth.updateUser({ password })
      if (error) throw error
      setDone(true)
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
        {done ? (
          <p className="alert alert-ok" role="status">
            رمز عبور با موفقیت تغییر کرد. حالا در برنامه اندروید مموت با رمز جدید وارد شوید.
          </p>
        ) : (
          <form onSubmit={onSubmit} className="auth-form">
            <label>
              رمز عبور جدید
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                autoComplete="new-password"
                dir="ltr"
              />
            </label>
            {error && <p className="alert alert-error" role="alert">{error}</p>}
            <button className="btn btn-primary btn-block" disabled={busy} type="submit">
              {busy ? 'لطفاً صبر کنید…' : 'ذخیره رمز عبور'}
            </button>
          </form>
        )}
      </div>
    </div>
  )
}
