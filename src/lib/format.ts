import type { ConversationSummary, Member } from './types'

const timeFmt = new Intl.DateTimeFormat('fa-IR', { hour: '2-digit', minute: '2-digit' })
const weekdayFmt = new Intl.DateTimeFormat('fa-IR', { weekday: 'long' })
const dateFmt = new Intl.DateTimeFormat('fa-IR', { day: 'numeric', month: 'long', year: 'numeric' })
const shortDateFmt = new Intl.DateTimeFormat('fa-IR', { day: 'numeric', month: 'numeric', year: '2-digit' })

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}

function isYesterday(d: Date): boolean {
  const y = new Date()
  y.setDate(y.getDate() - 1)
  return sameDay(d, y)
}

export function formatTime(iso: string): string {
  return timeFmt.format(new Date(iso))
}

export function formatListTime(iso: string): string {
  const d = new Date(iso)
  const now = new Date()
  if (sameDay(d, now)) return formatTime(iso)
  if (isYesterday(d)) return 'دیروز'
  if (now.getTime() - d.getTime() < 7 * 24 * 3600_000) return weekdayFmt.format(d)
  return shortDateFmt.format(d)
}

export function formatDayLabel(iso: string): string {
  const d = new Date(iso)
  if (sameDay(d, new Date())) return 'امروز'
  if (isYesterday(d)) return 'دیروز'
  return dateFmt.format(d)
}

export function dayKey(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`
}

export function otherMember(conv: ConversationSummary, myId: string): Member | undefined {
  return conv.members.find((m) => m.id !== myId)
}

export function conversationTitle(conv: ConversationSummary, myId: string): string {
  if (conv.kind === 'group') return conv.title?.trim() || 'گروه'
  return otherMember(conv, myId)?.display_name ?? 'کاربر'
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0].slice(0, 2)
  return (parts[0][0] ?? '') + (parts[1][0] ?? '')
}

/** Deterministic 0..7 bucket for avatar color classes (no inline styles needed). */
export function avatarColorClass(seed: string): string {
  let h = 0
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) | 0
  return `avatar-c${Math.abs(h) % 8}`
}

export function faNum(n: number): string {
  return n.toLocaleString('fa-IR')
}
