import { useEffect, useState } from 'react'
import { configMissing, supabase } from './lib/supabase'
import { ForgotPasswordForm, ResetPasswordScreen } from './components/ResetScreens'

// Audit F17: the public website is no longer a chat client. Chat lives in
// the Android app only; this static site exists for the app download,
// password recovery (the redirect target for reset emails), and the
// legal pages. No chat code ships to browsers anymore.

const APK_URL =
  'https://raw.githubusercontent.com/m39152346-gif/MamootChat/apk-latest/mamoot.apk'
const PRIVACY_FULL =
  'https://github.com/m39152346-gif/MamootChat/blob/main/docs/PRIVACY.md'
const TERMS_FULL =
  'https://github.com/m39152346-gif/MamootChat/blob/main/docs/TERMS.md'

type Page = 'home' | 'reset' | 'privacy' | 'terms' | 'recovery'

function pageFromPath(pathname: string): Page {
  if (pathname.startsWith('/reset')) return 'reset'
  if (pathname.startsWith('/privacy')) return 'privacy'
  if (pathname.startsWith('/terms')) return 'terms'
  return 'home'
}

function LegalShell({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="auth-page">
      <div className="auth-card" style={{ maxWidth: 720, textAlign: 'start' }}>
        <h1>{title}</h1>
        <div className="legal-text">{children}</div>
        <p className="auth-sub" style={{ marginTop: 20 }}>
          <a href="/">← بازگشت به صفحه اصلی</a>
        </p>
      </div>
    </div>
  )
}

function Privacy() {
  return (
    <LegalShell title="حریم خصوصی مموت">
      <p>خلاصه فارسی؛ متن کامل و رسمی: <a href={PRIVACY_FULL}>docs/PRIVACY.md</a></p>
      <ul>
        <li>پیام‌های «چت امن» 🔒 با رمزنگاری سرتاسری روی دو گوشی رمز می‌شوند؛ سرور فقط متن رمز و ابردادهٔ حداقلی (فرستنده، گفتگو، زمان) را می‌بیند و پاسخ/بازارسال/واکنش داخل چت امن هم جزئی از همان متن رمز است.</li>
        <li>گفتگوهای عادی و گروهی برای همگام‌سازی روی سرور به‌صورت خوانا نگه داشته می‌شوند (با TLS و کنترل دسترسی سطح ردیف؛ فقط اعضای گفتگو می‌خوانند).</li>
        <li>روی سرور نگه می‌داریم: ایمیل و هش گذرواژه (سرویس احراز هویت)، نام کاربری و نام نمایشی، کلید عمومی هویت، زمان آخرین فعالیت، وضعیت خواندن پیام، واکنش‌های چت عادی، فهرست مسدودی‌ها و گزارش‌هایی که خودتان می‌فرستید.</li>
        <li>مخفی‌کردن «آخرین بازدید» را سرور اعمال می‌کند: زمان خام آخرین فعالیت برای هیچ کاربر دیگری خوانا نیست.</li>
        <li>نگهداری: متن پیام حذف‌شده فوراً از سرور پاک می‌شود و ردّ خالی آن حداکثر ۳۰ روز بعد حذف کامل می‌شود. متن رمز چت امن که همه خوانده‌اند، ۳۰ روز پس از ارسال از سرور حذف کامل می‌شود (نسخهٔ خوانا روی گوشی‌تان می‌ماند). پیام‌های عادی تا حذف شما یا حذف حساب می‌مانند.</li>
        <li>حذف حساب از داخل برنامه، حساب و همهٔ پیام‌هایتان را برای همیشه پاک می‌کند. خروجی داده و پشتیبان رمزنگاری‌شده (PBKDF2 با ۶۰۰هزار تکرار + AES-256-GCM) از داخل برنامه در دسترس است.</li>
        <li>مموت تبلیغ، ابزار ردیابی/آمار شخص ثالث و فروش داده ندارد و به Google Play Services وابسته نیست.</li>
      </ul>
    </LegalShell>
  )
}

function Terms() {
  return (
    <LegalShell title="شرایط استفاده از مموت">
      <p>خلاصهٔ فارسی؛ متن کامل و رسمی: <a href={TERMS_FULL}>docs/TERMS.md</a></p>
      <ul>
        <li>چت امن به یک گوشی برای هر حساب وابسته است. سرویس رایگان و «همان‌طور که هست» ارائه می‌شود و دسترسی همیشگی تضمین نمی‌شود.</li>
        <li>مسئولیت گذرواژهٔ حساب، رمز قفل برنامه و گذرواژهٔ پشتیبان با شماست؛ کلیدهای چت امن را هیچ‌کس — از جمله ما — نمی‌تواند بازیابی کند.</li>
        <li>اسپم، کلاهبرداری، آزار، جعل هویت، محتوای غیرقانونی و تلاش برای دورزدن امنیت سرویس ممنوع است. برای نگه‌داشتن سرویس رایگان، محدودیت‌های خودکار نرخ (مثل سقف ساخت گفتگوی تازه و جست‌وجو در روز) اعمال می‌شود و تغییر اعضای گروه فقط دست سازندهٔ گروه است.</li>
        <li>حساب متخلف ممکن است محدود یا حذف شود. محتوای چت امن به‌خاطر رمزنگاری سرتاسری قابل بررسی نیست، ولی گزارش‌های تکراری دربارهٔ یک حساب می‌تواند به محدودیت منجر شود.</li>
        <li>پیام‌ها متعلق به خودتان‌اند؛ سرور طبق سند حریم خصوصی آن‌ها را نگه می‌دارد و تحویل می‌دهد. حذف حساب، پیام‌ها را برای همیشه پاک می‌کند.</li>
      </ul>
    </LegalShell>
  )
}

function Home() {
  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-logo" aria-hidden="true">💬</div>
        <h1>مموت</h1>
        <p className="auth-sub">
          پیام‌رسان متنی سریع و امن برای اندروید — چت عادی و «چت امن» با رمزنگاری
          سرتاسری مثل چت مخفی تلگرام. فقط متن، بدون حاشیه، بدون تبلیغ و ردیابی.
        </p>
        <a className="btn btn-primary btn-block" href={APK_URL}>
          ⬇️ دانلود برنامه اندروید (APK)
        </a>
        <p className="auth-sub" style={{ marginTop: 12 }}>
          گفتگو فقط داخل برنامه انجام می‌شود؛ این وب‌سایت نسخهٔ وب ندارد و فقط برای
          دانلود برنامه و بازیابی رمز عبور است.
        </p>
        <div style={{ display: 'flex', gap: 16, justifyContent: 'center', marginTop: 18, flexWrap: 'wrap' }}>
          <a href="/reset">بازیابی رمز عبور</a>
          <a href="/privacy">حریم خصوصی</a>
          <a href="/terms">شرایط استفاده</a>
        </div>
      </div>
    </div>
  )
}

export default function App() {
  const [page, setPage] = useState<Page>(() => pageFromPath(window.location.pathname))

  useEffect(() => {
    const { data } = supabase.auth.onAuthStateChange((event) => {
      if (event === 'PASSWORD_RECOVERY') setPage('recovery')
    })
    return () => data.subscription.unsubscribe()
  }, [])

  if (configMissing) {
    return (
      <div className="auth-page">
        <div className="auth-card">
          <h1>مموت</h1>
          <p className="alert alert-error" role="alert">
            پیکربندی سرویس در این نمونه کامل نیست.
          </p>
        </div>
      </div>
    )
  }

  switch (page) {
    case 'recovery':
      return <ResetPasswordScreen onDone={() => undefined} />
    case 'reset':
      return <ForgotPasswordForm />
    case 'privacy':
      return <Privacy />
    case 'terms':
      return <Terms />
    default:
      return <Home />
  }
}
