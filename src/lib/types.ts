export interface Profile {
  id: string
  username: string
  display_name: string
  created_at?: string
}

export interface Member extends Profile {
  last_read_at: string
  role: 'owner' | 'member'
}

export interface LastMessage {
  id: string
  body: string
  sender_id: string
  created_at: string
  edited_at: string | null
  deleted_at: string | null
}

export interface ConversationSummary {
  id: string
  kind: 'dm' | 'group'
  title: string | null
  created_at: string
  last_message_at: string
  members: Member[]
  last_message: LastMessage | null
  unread_count: number
  my_last_read_at: string
}

export type MessageStatus = 'sending' | 'sent' | 'failed'

export interface Message {
  id: string
  client_id: string
  conversation_id: string
  sender_id: string
  body: string
  created_at: string
  edited_at: string | null
  deleted_at: string | null
  status?: MessageStatus
}

export const MAX_MESSAGE_LEN = 4000
export const PAGE_SIZE = 50
export const USERNAME_RE = /^[a-zA-Z0-9_.]{3,24}$/
