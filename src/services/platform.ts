// Platform (super admin) console API. Read models and status changes are SQL RPCs that check
// platform_admins; creating logins goes through the platform-admin Edge Function (service role).
import type {
  AccountStatus, AgreementTemplate, AgreementTemplateVersion, AppRole, LendingMode, PlatformOrg, PlatformOrgDetail, PlatformOverview,
} from '@/types/db'
import { invoke, rpc } from './api'

export type NewAccount = {
  organization: {
    name: string
    legal_name?: string
    registration_no?: string
    tin?: string
    address?: string
    city?: string
    province?: string
    contact_phone?: string
    contact_email?: string
    lending_mode?: LendingMode
  }
  owner: { full_name: string; email: string; phone?: string; password: string }
}

export type NewAccountUser = {
  organization_id: string
  full_name: string
  email: string
  phone?: string
  role: AppRole
  password: string
}

export const platformService = {
  overview: () => rpc<PlatformOverview>('platform_overview'),
  listOrganizations: () => rpc<PlatformOrg[]>('platform_list_organizations'),
  getOrganization: (id: string) => rpc<PlatformOrgDetail>('platform_get_organization', { p_org: id }),
  updateOrganization: (id: string, patch: Record<string, string>) =>
    rpc<PlatformOrgDetail>('platform_update_organization', { p_org: id, p: patch }),
  setStatus: (id: string, status: AccountStatus, reason?: string) =>
    rpc<PlatformOrgDetail>('platform_set_organization_status', { p_org: id, p_status: status, p_reason: reason ?? null }),
  /** Decides which loan-creation workflow every user of the business gets. */
  setLendingMode: (id: string, mode: LendingMode) =>
    rpc<PlatformOrgDetail>('platform_set_lending_mode', { p_org: id, p_mode: mode }),
  // ----- agreement / terms templates of a lender account
  listTemplates: (orgId: string) =>
    rpc<(AgreementTemplate & { current: AgreementTemplateVersion | null })[]>('platform_list_templates', { p_org: orgId }),
  getTemplate: (orgId: string, id: string) =>
    rpc<{ template: AgreementTemplate; versions: AgreementTemplateVersion[] }>('platform_get_template', { p_org: orgId, p_template_id: id }),
  saveTemplate: (orgId: string, p: Record<string, unknown>) =>
    rpc<{ id: string; version_id: string; version_no: number }>('platform_save_template', { p_org: orgId, p }),
  setDefaultTemplate: (orgId: string, id: string) => rpc('platform_set_default_template', { p_org: orgId, p_template_id: id }),
  setTemplateActive: (orgId: string, id: string, active: boolean) =>
    rpc('platform_set_template_active', { p_org: orgId, p_template_id: id, p_active: active }),
  createAccount: (a: NewAccount) =>
    invoke<{ organization_id: string; user_id: string; email: string }>('platform-admin', { action: 'create_account', ...a }),
  addUser: (u: NewAccountUser) => invoke<{ user_id: string; email: string }>('platform-admin', { action: 'add_user', ...u }),
  resetPassword: (userId: string, password: string) =>
    invoke<{ ok: true }>('platform-admin', { action: 'reset_password', user_id: userId, password }),
}

/** Strong random temporary password (no look-alike characters). */
export function generatePassword(length = 14) {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%'
  const arr = new Uint32Array(length)
  crypto.getRandomValues(arr)
  return Array.from(arr, (n) => chars[n % chars.length]).join('')
}
