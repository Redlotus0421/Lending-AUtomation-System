import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import type { Session } from '@supabase/supabase-js'
import { useQueryClient } from '@tanstack/react-query'
import { supabase } from '@/lib/supabase'
import { orgService } from '@/services/org'
import { clearAllDrafts } from '@/features/loans/wizard'
import type { Access, Organization, Settings } from '@/types/db'

type AuthState = {
  loading: boolean
  session: Session | null
  access: Access | null
  organization: Organization | null
  settings: Settings | null
  can: (permission: string) => boolean
  canAny: (...permissions: string[]) => boolean
  refresh: () => Promise<void>
  signOut: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)
  const [access, setAccess] = useState<Access | null>(null)
  const [organization, setOrganization] = useState<Organization | null>(null)
  const [settings, setSettings] = useState<Settings | null>(null)
  const [loading, setLoading] = useState(true)
  const qc = useQueryClient()
  const loadedFor = useRef<string | null>(null)

  const loadAccess = useCallback(async (s: Session | null) => {
    if (!s) {
      setAccess(null)
      setOrganization(null)
      setSettings(null)
      loadedFor.current = null
      return
    }
    try {
      const a = await orgService.myAccess()
      setAccess(a)
      if (a.organization_id) {
        const [org, st] = await Promise.all([orgService.get(), orgService.getSettings()])
        setOrganization(org)
        setSettings(st)
      } else {
        setOrganization(null)
        setSettings(null)
      }
      loadedFor.current = s.user.id
    } catch (e) {
      console.error('Failed to load access', e)
      setAccess({ user_id: s.user.id, organization_id: null, role: null, full_name: null, permissions: [] })
    }
  }, [])

  useEffect(() => {
    let mounted = true
    supabase.auth.getSession().then(async ({ data }) => {
      if (!mounted) return
      setSession(data.session)
      await loadAccess(data.session)
      if (mounted) setLoading(false)
    })
    const { data: sub } = supabase.auth.onAuthStateChange((event, s) => {
      setSession(s)
      if (event === 'SIGNED_OUT') {
        qc.clear()
        clearAllDrafts() // unsaved loan drafts contain borrower details
        void loadAccess(null)
      } else if ((event === 'SIGNED_IN' || event === 'USER_UPDATED') && s && loadedFor.current !== s.user.id) {
        setLoading(true)
        void loadAccess(s).finally(() => setLoading(false))
      }
    })
    return () => {
      mounted = false
      sub.subscription.unsubscribe()
    }
  }, [loadAccess, qc])

  // Session timeout (Settings → Security)
  useEffect(() => {
    if (!session || !settings) return
    const minutes = settings.security?.session_timeout_minutes ?? 60
    if (!minutes) return
    let timer: ReturnType<typeof setTimeout>
    const reset = () => {
      clearTimeout(timer)
      timer = setTimeout(() => void supabase.auth.signOut(), minutes * 60_000)
    }
    const events = ['mousemove', 'keydown', 'touchstart', 'click'] as const
    events.forEach((e) => window.addEventListener(e, reset, { passive: true }))
    reset()
    return () => {
      clearTimeout(timer)
      events.forEach((e) => window.removeEventListener(e, reset))
    }
  }, [session, settings])

  const value = useMemo<AuthState>(() => {
    const perms = new Set(access?.permissions ?? [])
    const can = (p: string) => access?.role === 'owner' || perms.has(p)
    return {
      loading,
      session,
      access,
      organization,
      settings,
      can,
      canAny: (...ps: string[]) => ps.some(can),
      refresh: async () => {
        loadedFor.current = null
        await loadAccess(session)
      },
      signOut: async () => {
        await supabase.auth.signOut()
      },
    }
  }, [loading, session, access, organization, settings, loadAccess])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
