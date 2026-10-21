import { createClient, type SupabaseClient } from '@supabase/supabase-js'

const url = import.meta.env.VITE_SUPABASE_URL?.trim()
const anonKey = import.meta.env.VITE_SUPABASE_ANON_KEY?.trim()

export const configMissing = !url || !anonKey

// Only the publishable/anon key ever reaches the browser.
// All authorization is enforced server-side by Postgres RLS policies.
export const supabase: SupabaseClient = createClient(
  url || 'https://placeholder.supabase.co',
  anonKey || 'placeholder-anon-key',
  {
    auth: {
      persistSession: true,
      autoRefreshToken: true,
      detectSessionInUrl: true,
      flowType: 'pkce',
    },
    realtime: { params: { eventsPerSecond: 20 } },
    global: { headers: { 'x-client-info': 'mamootchat-web/1.0' } },
  },
)
