/** Translate known auth errors to friendly Persian messages. */
export function faError(err: unknown): string {
  const raw =
    (err as { message?: string } | null)?.message ??
    (typeof err === 'string' ? err : '')
  const m = raw.toLowerCase()

  if (m.includes('rate limit') || m.includes('rate_limit'))
    return 'درخواست‌های زیادی ارسال شده است؛ کمی صبر کنید و دوباره تلاش کنید.'
  if (m.includes('invalid login credentials')) return 'ایمیل یا رمز عبور اشتباه است.'
  if (m.includes('password should be'))
    return 'رمز عبور ضعیف است؛ حداقل ۸ نویسه با حرف و عدد انتخاب کنید.'
  if (m.includes('same password') || m.includes('should be different'))
    return 'رمز عبور جدید باید با رمز قبلی فرق داشته باشد.'
  if (m.includes('session') && (m.includes('expired') || m.includes('invalid') || m.includes('missing')))
    return 'لینک بازیابی منقضی شده یا نامعتبر است؛ دوباره لینک تازه بگیرید.'
  if (m.includes('failed to fetch') || m.includes('network'))
    return 'خطای شبکه؛ اتصال اینترنت را بررسی کنید.'
  return raw || 'خطای غیرمنتظره‌ای رخ داد. دوباره تلاش کنید.'
}

export function strongEnough(password: string): boolean {
  return password.length >= 8 && /[A-Za-z]/.test(password) && /[0-9]/.test(password)
}
