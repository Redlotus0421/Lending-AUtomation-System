import { supabase } from '@/lib/supabase'
import type { Access, AppRole, Notification, Organization, Settings, UserProfile } from '@/types/db'
import { invoke, must, rpc, safeFileName, signedUrl, uploadFile } from './api'

// ---------- auth ----------
export const authService = {
  async signIn(email: string, password: string) {
    const { error } = await supabase.auth.signInWithPassword({ email, password })
    if (error) throw new Error(error.message === 'Invalid login credentials' ? 'Incorrect email or password.' : error.message)
  },
  async resetPassword(email: string) {
    const { error } = await supabase.auth.resetPasswordForEmail(email, { redirectTo: `${window.location.origin}/reset-password` })
    if (error) throw new Error(error.message)
  },
  async updatePassword(password: string) {
    const { error } = await supabase.auth.updateUser({ password, data: { must_change_password: false } })
    if (error) throw new Error(error.message)
  },
  async signOut() {
    await supabase.auth.signOut()
  },
}

// ---------- organization & settings ----------
export const orgService = {
  myAccess: () => rpc<Access>('my_access'),
  async get(): Promise<Organization | null> {
    return must(await supabase.from('organizations').select('*').maybeSingle()) as Organization | null
  },
  async update(id: string, patch: Partial<Organization>) {
    return must(await supabase.from('organizations').update(patch).eq('id', id).select().single()) as Organization
  },
  async uploadLogo(orgId: string, file: File) {
    const path = `${orgId}/${safeFileName(file.name)}`
    await uploadFile('branding', path, file, true)
    await orgService.update(orgId, { logo_path: path })
    return path
  },
  logoUrl: (path: string) => signedUrl('branding', path, 3600),
  getSettings: () => rpc<Settings>('get_settings'),
  updateSettings: (section: keyof Settings, values: Record<string, unknown>) =>
    rpc<Settings>('update_settings', { p_section: section, p_values: values }),
  async profile(): Promise<UserProfile | null> {
    const { data: u } = await supabase.auth.getUser()
    if (!u.user) return null
    return must(await supabase.from('user_profiles').select('*').eq('id', u.user.id).maybeSingle()) as UserProfile | null
  },
  updateMyProfile: (fullName: string, phone?: string) => rpc('update_my_profile', { p_full_name: fullName, p_phone: phone ?? null }),
  /** Owner only: loads realistic sample borrowers, loans and payments (development/demo). */
  seedDemoData: () => rpc<{ borrowers: number; loans: number }>('seed_demo_data'),
}

// ---------- staff ----------
export type StaffMember = UserProfile & { role: AppRole | null }

export const usersService = {
  async list(): Promise<StaffMember[]> {
    const profiles = must(await supabase.from('user_profiles').select('*').order('full_name')) as UserProfile[]
    const roles = must(await supabase.from('user_roles').select('user_id, role')) as { user_id: string; role: AppRole }[]
    const map = new Map(roles.map((r) => [r.user_id, r.role]))
    return profiles.filter((p) => p.organization_id).map((p) => ({ ...p, role: map.get(p.id) ?? null }))
  },
  create: (p: { email: string; password: string; full_name: string; role: AppRole; phone?: string }) =>
    invoke<{ user_id: string }>('admin-users', { action: 'create', ...p }),
  resetPassword: (userId: string, password: string) => invoke('admin-users', { action: 'reset_password', user_id: userId, password }),
  setRole: (userId: string, role: AppRole) => rpc('set_user_role', { p_user_id: userId, p_role: role }),
  setActive: (userId: string, active: boolean) => rpc('set_user_active', { p_user_id: userId, p_active: active }),
  async rolePermissions(): Promise<Record<string, string[]>> {
    const rows = must(await supabase.from('role_permissions').select('role, permission')) as { role: string; permission: string }[]
    const out: Record<string, string[]> = { owner: [], admin: [], loan_officer: [], collector: [] }
    rows.forEach((r) => (out[r.role] ??= []).push(r.permission))
    return out
  },
  setRolePermissions: (role: AppRole, permissions: string[]) => rpc('set_role_permissions', { p_role: role, p_permissions: permissions }),
  resetRolePermissions: (role: AppRole) => rpc('reset_role_permissions', { p_role: role }),
}

// ---------- notifications ----------
export const notificationsService = {
  async list(): Promise<Notification[]> {
    return must(await supabase.from('notifications').select('*').order('created_at', { ascending: false }).limit(30)) as Notification[]
  },
  markRead: (ids?: string[]) => rpc('mark_notifications_read', { p_ids: ids ?? null }),
}

// ---------- audit ----------
export const auditService = {
  async list(f: { table?: string; action?: string; search?: string; from?: string; to?: string; page?: number; pageSize?: number }) {
    const pageSize = f.pageSize ?? 50
    let q = supabase.from('audit_logs').select('*', { count: 'exact' }).order('created_at', { ascending: false })
    if (f.table) q = q.eq('table_name', f.table)
    if (f.action) q = q.eq('action', f.action)
    if (f.from) q = q.gte('created_at', `${f.from}T00:00:00+08:00`)
    if (f.to) q = q.lte('created_at', `${f.to}T23:59:59+08:00`)
    if (f.search) q = q.or(`actor_name.ilike.%${f.search}%,record_id.ilike.%${f.search}%`)
    const page = f.page ?? 0
    const res = await q.range(page * pageSize, page * pageSize + pageSize - 1)
    return { rows: must(res) as import('@/types/db').AuditLog[], count: res.count ?? 0 }
  },
}
