// Platform (super) administration: create lender accounts and help their users regain access.
// Only callers listed in public.platform_admins may use it. Creating auth users needs the
// service role, so it happens here; everything else is enforced again in SQL.
//
// POST { action: 'create_account', organization: {...}, owner: { full_name, email, phone?, password } }
// POST { action: 'add_user', organization_id, full_name, email, phone?, role, password }
// POST { action: 'reset_password', user_id, password }
import { adminClient, HttpError, json, readJson, serve, userClient } from '../_shared/http.ts'

const ROLES = ['owner', 'admin', 'loan_officer', 'collector']
const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/
const MIN_PASSWORD = 10

type Body = {
  action?: string
  organization?: Record<string, string | undefined>
  owner?: { full_name?: string; email?: string; phone?: string; password?: string }
  organization_id?: string
  user_id?: string
  full_name?: string
  email?: string
  phone?: string
  role?: string
  password?: string
}

async function requirePlatformAdmin(req: Request): Promise<string> {
  const token = (req.headers.get('Authorization') ?? '').replace(/^Bearer\s+/i, '')
  if (!token) throw new HttpError(401, 'Missing authorization')
  const { data: u, error } = await adminClient().auth.getUser(token)
  if (error || !u?.user) throw new HttpError(401, 'Invalid or expired session')
  const { data: ok, error: e2 } = await userClient(req).rpc('is_platform_admin')
  if (e2 || ok !== true) throw new HttpError(403, 'Platform administrator access required')
  return u.user.id
}

function checkPassword(pw: string | undefined, min = MIN_PASSWORD): string {
  const p = pw ?? ''
  if (p.length < min) throw new HttpError(400, `Password must be at least ${min} characters`)
  if (p.length > 72) throw new HttpError(400, 'Password must be at most 72 characters')
  return p
}

function checkEmail(e: string | undefined): string {
  const email = (e ?? '').trim().toLowerCase()
  if (!EMAIL.test(email)) throw new HttpError(400, 'A valid email is required')
  return email
}

async function orgMinPassword(orgId: string): Promise<number> {
  const { data } = await adminClient().rpc('lf_settings', { p_org: orgId })
  return Math.max(MIN_PASSWORD, Number(data?.security?.password_min_length ?? MIN_PASSWORD))
}

async function createLogin(email: string, password: string, fullName: string, phone: string | null,
  appMeta: Record<string, unknown> = {}) {
  const { data, error } = await adminClient().auth.admin.createUser({
    email,
    password,
    email_confirm: true,
    user_metadata: { full_name: fullName, phone, must_change_password: true },
    app_metadata: appMeta,
  })
  if (error) {
    if (/already|exists|registered/i.test(error.message)) throw new HttpError(409, 'A login with this email already exists')
    throw new HttpError(400, error.message)
  }
  return data.user.id
}

serve(async (req) => {
  const adminId = await requirePlatformAdmin(req)
  const body = await readJson<Body>(req)
  const admin = adminClient()

  if (body.action === 'create_account') {
    const org = body.organization ?? {}
    const owner = body.owner ?? {}
    const name = (org.name ?? '').trim()
    if (name.length < 2) throw new HttpError(400, 'Business name is required')
    const fullName = (owner.full_name ?? '').trim()
    if (fullName.length < 2) throw new HttpError(400, "Owner's full name is required")
    const email = checkEmail(owner.email)
    const password = checkPassword(owner.password)
    const phone = owner.phone?.trim() || null

    const userId = await createLogin(email, password, fullName, phone)
    const { data, error } = await admin.rpc('lf_platform_create_organization', {
      p: { ...org, name, owner_name: fullName, owner_phone: phone, contact_email: org.contact_email || email },
      p_owner: userId,
      p_created_by: adminId,
    })
    if (error) {
      // nothing else references the brand-new login yet, so undo it to keep the email reusable
      await admin.auth.admin.deleteUser(userId)
      throw new HttpError(400, error.message)
    }
    return json({ organization_id: data.organization_id, user_id: userId, email })
  }

  if (body.action === 'add_user') {
    const orgId = body.organization_id ?? ''
    const { data: org } = await admin.from('organizations').select('id').eq('id', orgId).maybeSingle()
    if (!org) throw new HttpError(404, 'Account not found')
    if (!ROLES.includes(body.role ?? '')) throw new HttpError(400, 'Invalid role')
    const fullName = (body.full_name ?? '').trim()
    if (fullName.length < 2) throw new HttpError(400, 'Full name is required')
    const email = checkEmail(body.email)
    const password = checkPassword(body.password, await orgMinPassword(orgId))
    const phone = body.phone?.trim() || null

    const userId = await createLogin(email, password, fullName, phone, { organization_id: orgId, role: body.role })
    const { error } = await admin.rpc('lf_attach_user', {
      p_user_id: userId, p_org: orgId, p_role: body.role, p_full_name: fullName, p_phone: phone,
    })
    if (error) {
      await admin.auth.admin.deleteUser(userId)
      throw new HttpError(400, error.message)
    }
    return json({ user_id: userId, email })
  }

  if (body.action === 'reset_password') {
    const userId = body.user_id ?? ''
    const { data: target } = await admin.from('user_profiles').select('id, organization_id').eq('id', userId).maybeSingle()
    if (!target?.organization_id) throw new HttpError(404, 'User not found in any lender account')
    const password = checkPassword(body.password, await orgMinPassword(target.organization_id))
    const { data: existing } = await admin.auth.admin.getUserById(userId)
    const { error } = await admin.auth.admin.updateUserById(userId, {
      password,
      user_metadata: { ...(existing?.user?.user_metadata ?? {}), must_change_password: true },
    })
    if (error) throw new HttpError(400, error.message)
    return json({ ok: true })
  }

  throw new HttpError(400, 'Unknown action')
})
