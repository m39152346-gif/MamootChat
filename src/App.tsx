import { useCallback, useEffect, useRef, useState } from 'react'
import type { Session, User } from '@supabase/supabase-js'
import { configMissing, supabase } from './lib/supabase'
import { fetchMyProfile, createProfile, listConversations } from './lib/api'
import type { ConversationSummary, Profile } from './lib/types'
import { AuthScreen, PENDING_PROFILE_KEY, ResetPasswordScreen } from './components/AuthScreen'
import { ProfileSetup } from './components/ProfileSetup'
import { ConversationList } from './components/ConversationList'
import { ChatWindow } from './components/ChatWindow'
import { NewChatModal } from './components/NewChatModal'
import { SettingsModal } from './components/SettingsModal'

interface PendingProfile {
  username: string
  display_name: string
}

function readPendingProfile(): PendingProfile | null {
  try {
    const raw = localStorage.getItem(PENDING_PROFILE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as PendingProfile
    return parsed.username && parsed.display_name ? parsed : null
  } catch {
    return null
  }
}

export default function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [user, setUser] = useState<User | null>(null)
  const [profile, setProfile] = useState<Profile | null>(null)
  const [booting, setBooting] = useState(true)
  const [profileLoading, setProfileLoading] = useState(false)
  const [recovery, setRecovery] = useState(false)

  const [conversations, setConversations] = useState<ConversationSummary[]>([])
  const [conversationsLoading, setConversationsLoading] = useState(false)
  const [activeId, setActiveId] = useState<string | null>(null)
  const [onlineIds, setOnlineIds] = useState<Set<string>>(new Set())
  const [typingByConv, setTypingByConv] = useState<Record<string, boolean>>({})
  const [showNewChat, setShowNewChat] = useState(false)
  const [showSettings, setShowSettings] = useState(false)
  const [showChatOnMobile, setShowChatOnMobile] = useState(false)

  const refreshTimer = useRef<number | undefined>(undefined)
  const activeIdRef = useRef<string | null>(null)
  activeIdRef.current = activeId

  const refreshConversations = useCallback(async () => {
    try {
      const rows = await listConversations()
      setConversations(rows)
    } catch {
      /* transient: keep last good list */
    }
  }, [])

  const refreshConversationsDebounced = useCallback(() => {
    window.clearTimeout(refreshTimer.current)
    refreshTimer.current = window.setTimeout(() => void refreshConversations(), 300)
  }, [refreshConversations])

  // ---- auth session
  useEffect(() => {
    let mounted = true
    supabase.auth
      .getSession()
      .then(({ data }) => {
        if (!mounted) return
        setSession(data.session)
        setUser(data.session?.user ?? null)
      })
      .finally(() => mounted && setBooting(false))

    const { data: sub } = supabase.auth.onAuthStateChange((event, nextSession) => {
      if (event === 'PASSWORD_RECOVERY') setRecovery(true)
      setSession(nextSession)
      setUser(nextSession?.user ?? null)
      if (!nextSession) {
        setProfile(null)
        setConversations([])
        setActiveId(null)
        setOnlineIds(new Set())
      }
    })
    return () => {
      mounted = false
      sub.subscription.unsubscribe()
    }
  }, [])

  // ---- my profile (create from pending signup data when possible)
  useEffect(() => {
    if (!user) return
    let cancelled = false
    setProfileLoading(true)
    ;(async () => {
      try {
        let mine = await fetchMyProfile(user.id)
        if (!mine) {
          const pending = readPendingProfile()
          if (pending) {
            try {
              mine = await createProfile(user.id, pending.username, pending.display_name)
              localStorage.removeItem(PENDING_PROFILE_KEY)
            } catch {
              // e.g. username taken in the meantime: fall back to the setup screen.
              mine = null
            }
          }
        }
        if (!cancelled) setProfile(mine)
      } catch {
        if (!cancelled) setProfile(null)
      } finally {
        if (!cancelled) setProfileLoading(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [user])

  // ---- conversations + global realtime inbox + presence
  useEffect(() => {
    if (!profile) return
    let cancelled = false
    setConversationsLoading(true)
    listConversations()
      .then((rows) => !cancelled && setConversations(rows))
      .catch(() => undefined)
      .finally(() => !cancelled && setConversationsLoading(false))

    const inbox = supabase
      .channel('mamootchat-inbox')
      .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'messages' }, () =>
        refreshConversationsDebounced(),
      )
      .on('postgres_changes', { event: 'UPDATE', schema: 'public', table: 'messages' }, () =>
        refreshConversationsDebounced(),
      )
      .on(
        'postgres_changes',
        { event: 'INSERT', schema: 'public', table: 'conversation_members', filter: `user_id=eq.${profile.id}` },
        () => refreshConversationsDebounced(),
      )
      .subscribe()

    const presence = supabase.channel('mamootchat-online', {
      config: { presence: { key: profile.id } },
    })
    presence
      .on('presence', { event: 'sync' }, () => {
        setOnlineIds(new Set(Object.keys(presence.presenceState())))
      })
      .subscribe((status) => {
        if (status === 'SUBSCRIBED') {
          void presence.track({ user_id: profile.id, online_at: new Date().toISOString() })
        }
      })

    const onVisible = () => {
      if (document.visibilityState === 'visible') refreshConversationsDebounced()
    }
    document.addEventListener('visibilitychange', onVisible)

    return () => {
      cancelled = true
      document.removeEventListener('visibilitychange', onVisible)
      void supabase.removeChannel(inbox)
      void supabase.removeChannel(presence)
    }
  }, [profile, refreshConversationsDebounced])

  const handleSelect = useCallback((id: string) => {
    setActiveId(id)
    setShowChatOnMobile(true)
    setConversations((prev) => prev.map((c) => (c.id === id ? { ...c, unread_count: 0 } : c)))
  }, [])

  const handleMarkedRead = useCallback((id: string) => {
    setConversations((prev) => prev.map((c) => (c.id === id ? { ...c, unread_count: 0 } : c)))
  }, [])

  const handleTypingChange = useCallback((id: string, typing: boolean) => {
    setTypingByConv((prev) => (prev[id] === typing ? prev : { ...prev, [id]: typing }))
  }, [])

  async function logout() {
    await supabase.auth.signOut()
    setShowSettings(false)
  }

  function handleCreated(id: string) {
    void refreshConversations().then(() => handleSelect(id))
  }

  function handleLeft(id: string) {
    setConversations((prev) => prev.filter((c) => c.id !== id))
    if (activeIdRef.current === id) {
      setActiveId(null)
      setShowChatOnMobile(false)
    }
    refreshConversationsDebounced()
  }

  if (configMissing) {
    return (
      <div className="auth-page">
        <div className="auth-card">
          <h1>پیکربندی ناقص است</h1>
          <p className="auth-sub">
            متغیرهای محیطی <code dir="ltr">VITE_SUPABASE_URL</code> و <code dir="ltr">VITE_SUPABASE_ANON_KEY</code> تنظیم نشده‌اند.
            فایل <code dir="ltr">.env.example</code> را ببینید.
          </p>
        </div>
      </div>
    )
  }

  if (booting || (session && profileLoading)) {
    return (
      <div className="boot-screen" role="status" aria-label="در حال بارگذاری">
        <div className="boot-logo" aria-hidden="true">💬</div>
        <span>مموت…</span>
      </div>
    )
  }

  if (recovery && session) {
    return <ResetPasswordScreen onDone={() => setRecovery(false)} />
  }

  if (!session || !user) return <AuthScreen />

  if (!profile) {
    return (
      <ProfileSetup
        userId={user.id}
        initial={readPendingProfile()}
        onReady={setProfile}
        onLogout={() => void logout()}
      />
    )
  }

  const activeConv = conversations.find((c) => c.id === activeId) ?? null

  return (
    <div className={`app ${showChatOnMobile && activeConv ? 'chat-open' : ''}`}>
      <ConversationList
        conversations={conversations}
        activeId={activeId}
        myProfile={profile}
        onlineIds={onlineIds}
        typingByConv={typingByConv}
        loading={conversationsLoading}
        onSelect={handleSelect}
        onNewChat={() => setShowNewChat(true)}
        onOpenSettings={() => setShowSettings(true)}
      />

      <main className="main-pane">
        {activeConv ? (
          <ChatWindow
            key={activeConv.id}
            conv={activeConv}
            myProfile={profile}
            onlineIds={onlineIds}
            onBack={() => setShowChatOnMobile(false)}
            onMarkedRead={handleMarkedRead}
            onTypingChange={handleTypingChange}
            onRefreshConversations={refreshConversationsDebounced}
            onLeft={handleLeft}
          />
        ) : (
          <div className="welcome-pane">
            <div className="welcome-emoji" aria-hidden="true">💬</div>
            <h2>به مموت خوش آمدید</h2>
            <p>یک گفتگو را از فهرست انتخاب کنید، یا یک گفتگوی تازه شروع کنید.</p>
            <button type="button" className="btn btn-primary" onClick={() => setShowNewChat(true)}>
              گفتگوی جدید
            </button>
            <p className="welcome-note">فقط متن؛ سریع و امن. بدون عکس، بدون فایل، بدون شلوغی.</p>
          </div>
        )}
      </main>

      {showNewChat && <NewChatModal onClose={() => setShowNewChat(false)} onCreated={handleCreated} />}
      {showSettings && (
        <SettingsModal
          profile={profile}
          email={user.email ?? ''}
          onClose={() => setShowSettings(false)}
          onSaved={(p) => {
            setProfile(p)
            refreshConversationsDebounced()
          }}
          onLogout={() => void logout()}
        />
      )}
    </div>
  )
}
