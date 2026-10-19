import { supabase } from './supabase'
import { PAGE_SIZE, type ConversationSummary, type Message, type Profile } from './types'

/** Translate known server/client errors to friendly Persian messages. */
export function faError(err: unknown): string {
  const raw =
    (err as { message?: string } | null)?.message ??
    (typeof err === 'string' ? err : '')
  const code = (err as { code?: string } | null)?.code ?? ''
  const m = raw.toLowerCase()

  if (m.includes('rate_limit_exceeded')) return 'خیلی سریع پیام می‌فرستید! کمی صبر کنید و دوباره تلاش کنید.'
  if (m.includes('empty_message')) return 'پیام خالی نمی‌تواند ارسال شود.'
  if (m.includes('invalid login credentials')) return 'ایمیل یا رمز عبور اشتباه است.'
  if (m.includes('user already registered') || m.includes('already been registered'))
    return 'این ایمیل قبلاً ثبت شده است؛ وارد شوید.'
  if (m.includes('email not confirmed')) return 'ایمیل شما هنوز تأیید نشده است.'
  if (code === '23505' && m.includes('username')) return 'این نام کاربری قبلاً گرفته شده است.'
  if (code === '23505') return 'این مورد قبلاً ثبت شده است.'
  if (m.includes('password should be')) return 'رمز عبور ضعیف است؛ حداقل ۸ نویسه با حرف و عدد انتخاب کنید.'
  if (m.includes('user_not_found')) return 'کاربر یافت نشد.'
  if (m.includes('profile_required')) return 'ابتدا پروفایل خود را کامل کنید.'
  if (m.includes('cannot_dm_self')) return 'نمی‌توانید با خودتان گفتگو کنید.'
  if (m.includes('invalid_member_count')) return 'برای ساخت گروه حداقل یک عضو انتخاب کنید (حداکثر ۹۹ نفر).'
  if (m.includes('group_full')) return 'ظرفیت گروه تکمیل است (حداکثر ۱۰۰ نفر).'
  if (m.includes('not_a_member') || m.includes('not_a_group')) return 'به این گفتگو دسترسی ندارید.'
  if (m.includes('message_deleted')) return 'این پیام حذف شده و قابل ویرایش نیست.'
  if (m.includes('failed to fetch') || m.includes('network')) return 'خطای شبکه؛ اتصال اینترنت را بررسی کنید.'
  return raw || 'خطای غیرمنتظره‌ای رخ داد. دوباره تلاش کنید.'
}

export async function fetchMyProfile(userId: string): Promise<Profile | null> {
  const { data, error } = await supabase
    .from('profiles')
    .select('id, username, display_name, created_at')
    .eq('id', userId)
    .maybeSingle()
  if (error) throw error
  return data as Profile | null
}

export async function createProfile(userId: string, username: string, displayName: string): Promise<Profile> {
  const { data, error } = await supabase
    .from('profiles')
    .insert({ id: userId, username: username.trim().toLowerCase(), display_name: displayName.trim() })
    .select('id, username, display_name, created_at')
    .single()
  if (error) throw error
  return data as Profile
}

export async function updateProfile(userId: string, username: string, displayName: string): Promise<Profile> {
  const { data, error } = await supabase
    .from('profiles')
    .update({ username: username.trim().toLowerCase(), display_name: displayName.trim() })
    .eq('id', userId)
    .select('id, username, display_name, created_at')
    .single()
  if (error) throw error
  return data as Profile
}

/** One indexed RPC instead of N+1 queries: members + last message + unread counts. */
export async function listConversations(): Promise<ConversationSummary[]> {
  const { data, error } = await supabase.rpc('list_conversations')
  if (error) throw error
  return (data ?? []).map((row: ConversationSummary) => ({
    ...row,
    members: row.members ?? [],
    unread_count: Number(row.unread_count ?? 0),
  }))
}

export async function fetchMessages(
  conversationId: string,
  beforeCreatedAt?: string,
): Promise<Message[]> {
  let query = supabase
    .from('messages')
    .select('id, client_id, conversation_id, sender_id, body, created_at, edited_at, deleted_at')
    .eq('conversation_id', conversationId)
    .order('created_at', { ascending: false })
    .order('id', { ascending: false })
    .limit(PAGE_SIZE)
  if (beforeCreatedAt) query = query.lt('created_at', beforeCreatedAt)
  const { data, error } = await query
  if (error) throw error
  return ((data ?? []) as Message[]).reverse()
}

export async function sendMessage(
  conversationId: string,
  senderId: string,
  body: string,
  clientId: string,
): Promise<Message> {
  const { data, error } = await supabase
    .from('messages')
    .insert({ conversation_id: conversationId, sender_id: senderId, body: body.trim(), client_id: clientId })
    .select('id, client_id, conversation_id, sender_id, body, created_at, edited_at, deleted_at')
    .single()
  if (!error) return data as Message

  // Idempotent retry: the first attempt may have landed despite a network error.
  if ((error as { code?: string }).code === '23505') {
    const { data: existing, error: fetchError } = await supabase
      .from('messages')
      .select('id, client_id, conversation_id, sender_id, body, created_at, edited_at, deleted_at')
      .eq('sender_id', senderId)
      .eq('client_id', clientId)
      .maybeSingle()
    if (!fetchError && existing) return existing as Message
  }
  throw error
}

export async function editMessage(id: string, body: string): Promise<Message> {
  const { data, error } = await supabase
    .from('messages')
    .update({ body: body.trim() })
    .eq('id', id)
    .select('id, client_id, conversation_id, sender_id, body, created_at, edited_at, deleted_at')
    .single()
  if (error) throw error
  return data as Message
}

export async function deleteMessage(id: string): Promise<Message> {
  const { data, error } = await supabase
    .from('messages')
    .update({ deleted_at: new Date().toISOString() })
    .eq('id', id)
    .select('id, client_id, conversation_id, sender_id, body, created_at, edited_at, deleted_at')
    .single()
  if (error) throw error
  return data as Message
}

export async function markRead(conversationId: string): Promise<void> {
  const { error } = await supabase.rpc('mark_read', { p_conversation: conversationId })
  if (error) throw error
}

export async function searchUsers(query: string): Promise<Profile[]> {
  const { data, error } = await supabase.rpc('search_users', { p_query: query, p_limit: 20 })
  if (error) throw error
  return (data ?? []) as Profile[]
}

export async function getOrCreateDm(otherId: string): Promise<string> {
  const { data, error } = await supabase.rpc('get_or_create_dm', { p_other: otherId })
  if (error) throw error
  return data as string
}

export async function createGroup(title: string, memberIds: string[]): Promise<string> {
  const { data, error } = await supabase.rpc('create_group', {
    p_title: title.trim(),
    p_member_ids: memberIds,
  })
  if (error) throw error
  return data as string
}

export async function addGroupMembers(conversationId: string, userIds: string[]): Promise<void> {
  const { error } = await supabase.rpc('add_group_members', {
    p_conversation: conversationId,
    p_user_ids: userIds,
  })
  if (error) throw error
}

export async function leaveConversation(conversationId: string): Promise<void> {
  const { error } = await supabase.rpc('leave_conversation', { p_conversation: conversationId })
  if (error) throw error
}
