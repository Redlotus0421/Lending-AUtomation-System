// Staff administration (requires users.manage). Creating auth users needs the service role,
// so it happens here; roles and permissions are then managed through RLS-protected RPCs.
// POST { action: 'create', email, password, full_name, role, phone? }
// POST { action: 'reset_password', user_id, password }
import { adminClient, HttpError, json, readJson, requirePermission, requireUser, serve } from '../_shared/http.ts'

const ROLES = ['admin', 'loan_officer', 'collector', 'owner']

serve(async (req) => {
  const { access } = await requireUser(req)
  requirePermission(access, 'users.manage')
  const body = await readJson<Record<string, string>>(req)
  const admin = adminClient()
  const { data: settings } = await admin.rpc('lf_settings', { p_org: access.organization_id })
  const minLen = Number(settings?.security?.password_min_length ?? 10)

  if (body.action === 'create') {
    const email = (body.email ?? '').trim().toLowerCase()
    const fullName = (body.full_name ?? '').trim()
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) throw new HttpError(400, 'A valid email is required')
    if (!fullName) throw new HttpError(400, 'Full name is required')
    if (!ROLES.includes(body.role)) throw new HttpError(400, 'Invalid role')
    if (body.role === 'owner' && access.role !== 'owner') throw new HttpError(403, 'Only an owner can create another owner')
    if ((body.password ?? '').length < minLen) throw new HttpError(400, `Password must be at least ${minLen} characters`)

    const { data, error } = await admin.auth.admin.createUser({
      email,
      password: body.password,
      email_confirm: true,
      user_metadata: { full_name: fullName, phone: body.phone ?? null },
      app_metadata: { organization_id: access.organization_id, role: body.role },
    })
    if (error) {
      if (/already/i.test(error.message)) throw new HttpError(409, 'A user with this email already exists')
      throw new HttpError(400, error.message)
    }
    // ensure the profile and role exist even if the auth trigger did not run
    const { error: e2 } = await admin.rpc('lf_attach_user', {
      p_user_id: data.user.id, p_org: access.organization_id, p_role: body.role, p_full_name: fullName, p_phone: body.phone ?? null,
    })
    if (e2) throw new HttpError(400, e2.message)
    return json({ user_id: data.user.id, email })
  }

  if (body.action === 'reset_password') {
    const { data: target } = await admin.from('user_profiles').select('id, organization_id').eq('id', body.user_id).maybeSingle()
    if (!target || target.organization_id !== access.organization_id) throw new HttpError(404, 'User not found')
    // same rule as set_user_role / set_user_active: only an owner may act on an owner's login
    const { data: targetRole, error: roleErr } = await admin.from('user_roles').select('role')
      .eq('user_id', body.user_id).eq('organization_id', access.organization_id).maybeSingle()
    if (roleErr) throw new HttpError(500, roleErr.message)
    if (targetRole?.role === 'owner' && access.role !== 'owner') throw new HttpError(403, 'Only an owner can reset another owner’s password')
    if ((body.password ?? '').length < minLen) throw new HttpError(400, `Password must be at least ${minLen} characters`)
    const { error } = await admin.auth.admin.updateUserById(body.user_id, { password: body.password })
    if (error) throw new HttpError(400, error.message)
    return json({ ok: true })
  }

  throw new HttpError(400, 'Unknown action')
})
