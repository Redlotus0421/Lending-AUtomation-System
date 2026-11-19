import { beforeAll, describe, expect, it } from 'vitest'
import { asService, asUser, createDb, createOrganization, createUser, one, type Db } from './harness'

let db: Db
let owner: string
let adminUser: string
let superAdmin: string
let org: string

const rpc = async <T = any>(user: string | null, fn: string, args: Record<string, unknown> = {}) => {
  const keys = Object.keys(args)
  const sql = `select public.${fn}(${keys.map((k, i) => `${k} => $${i + 1}`).join(', ')}) as r`
  return asUser(db, user, async () => (await one<{ r: T }>(db, sql, keys.map((k) => args[k]))).r)
}
const q = async <T = any>(user: string | null, sql: string, params: unknown[] = []) =>
  asUser(db, user, async () => (await db.query<T>(sql, params)).rows)

const tpl = { name: 'Platform Terms', title: 'Loan Agreement', body: 'The BORROWER {{borrower_name}} borrows {{loan_amount}}.\n\n{{signature_block}}' }

beforeAll(async () => {
  db = await createDb()
  owner = await createUser(db, 'owner@tpl.ph', 'Rosa Cruz')
  superAdmin = await createUser(db, 'root@tpl.ph', 'Platform Admin')
  await asService(db, () => db.query('select public.lf_grant_platform_admin($1)', [superAdmin]))
  org = await createOrganization(db, owner, { name: 'Template Lending' })
  adminUser = await createUser(db, 'admin@tpl.ph', 'Ana Admin', { organization_id: org, role: 'admin' })
})

describe('platform administrator manages lender templates', () => {
  it('lists the account templates, creates a new one and makes it the default', async () => {
    const before = await rpc<any[]>(superAdmin, 'platform_list_templates', { p_org: org })
    expect(before.length).toBeGreaterThan(0) // the seeded standard template
    expect(before[0].current.body).toBeTruthy()

    const saved = await rpc<any>(superAdmin, 'platform_save_template', { p_org: org, p: JSON.stringify({ ...tpl, is_default: true }) })
    expect(saved.version_no).toBe(1)
    const after = await rpc<any[]>(superAdmin, 'platform_list_templates', { p_org: org })
    const mine = after.find((t) => t.id === saved.id)
    expect(mine.is_default).toBe(true)
    expect(after.filter((t) => t.is_default)).toHaveLength(1)

    // a new version, then read it back with its history
    await rpc(superAdmin, 'platform_save_template', { p_org: org, p: JSON.stringify({ ...tpl, id: saved.id, body: tpl.body + '\nExtra clause.', change_note: 'Edited by platform' }) })
    const got = await rpc<any>(superAdmin, 'platform_get_template', { p_org: org, p_template_id: saved.id })
    expect(got.versions.map((v: any) => v.version_no)).toEqual([2, 1])
    expect(got.template.current_version_id).toBe(got.versions[0].id)

    // the lender sees it and new agreements would use it
    const seen = await q(owner, `select name, is_default from public.loan_agreement_templates where id = $1`, [saved.id])
    expect(seen[0]).toMatchObject({ name: 'Platform Terms', is_default: true })
    const audit = await q(owner, `select action from public.audit_logs where record_id = $1 order by created_at`, [saved.id])
    expect(audit.map((a: any) => a.action)).toContain('template_saved_by_platform')
  })

  it('cannot deactivate the default; can deactivate another template', async () => {
    const list = await rpc<any[]>(superAdmin, 'platform_list_templates', { p_org: org })
    const def = list.find((t) => t.is_default)
    const other = list.find((t) => !t.is_default)
    await expect(rpc(superAdmin, 'platform_set_template_active', { p_org: org, p_template_id: def.id, p_active: false })).rejects.toThrow(/default template cannot be deactivated/)
    await rpc(superAdmin, 'platform_set_template_active', { p_org: org, p_template_id: other.id, p_active: false })
    await expect(rpc(superAdmin, 'platform_set_default_template', { p_org: org, p_template_id: other.id })).rejects.toThrow(/inactive/)
    await rpc(superAdmin, 'platform_set_template_active', { p_org: org, p_template_id: other.id, p_active: true })
    await rpc(superAdmin, 'platform_set_default_template', { p_org: org, p_template_id: other.id })
    const now = await rpc<any[]>(superAdmin, 'platform_list_templates', { p_org: org })
    expect(now.find((t) => t.id === other.id).is_default).toBe(true)
  })

  it('is refused to lender users', async () => {
    await expect(rpc(owner, 'platform_list_templates', { p_org: org })).rejects.toThrow(/Platform administrator/)
    await expect(rpc(owner, 'platform_save_template', { p_org: org, p: JSON.stringify(tpl) })).rejects.toThrow(/Platform administrator/)
  })
})

describe('lenders manage their own templates', () => {
  it('lets the owner and the admin role save templates', async () => {
    const a = await rpc<any>(owner, 'save_agreement_template', { p: JSON.stringify({ ...tpl, name: 'Owner Terms' }) })
    expect(a.version_no).toBe(1)
    const b = await rpc<any>(adminUser, 'save_agreement_template', { p: JSON.stringify({ ...tpl, name: 'Admin Terms', is_default: true }) })
    expect(b.version_no).toBe(1)
    const d = await q(owner, `select name from public.loan_agreement_templates where is_default`)
    expect(d[0].name).toBe('Admin Terms')
  })
})
