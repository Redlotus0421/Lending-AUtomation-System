import { beforeAll, describe, expect, it } from 'vitest'
import { asService, asUser, createDb, createUser, one, type Db } from './harness'

let db: Db
let superAdmin: string
let ownerA: string
let ownerB: string
let staffA: string
let orgA: string
let orgB: string

const rpc = async <T = any>(user: string | null, fn: string, args: Record<string, unknown> = {}) => {
  const keys = Object.keys(args)
  const sql = `select public.${fn}(${keys.map((k, i) => `${k} => $${i + 1}`).join(', ')}) as r`
  return asUser(db, user, async () => (await one<{ r: T }>(db, sql, keys.map((k) => args[k]))).r)
}
const q = async <T = any>(user: string | null, sql: string, params: unknown[] = []) =>
  asUser(db, user, async () => (await db.query<T>(sql, params)).rows)

/** What the platform-admin Edge Function does: create the login, then the account in one transaction. */
async function createAccount(name: string, email: string) {
  const owner = await createUser(db, email, `${name} Owner`)
  const r = await asService(db, async () => (await one<{ r: any }>(db,
    `select public.lf_platform_create_organization($1::jsonb, $2, $3) as r`,
    [JSON.stringify({ name, owner_name: `${name} Owner`, city: 'Quezon City', contact_email: email }), owner, superAdmin])).r)
  return { owner, org: r.organization_id as string }
}

beforeAll(async () => {
  db = await createDb()
  superAdmin = await createUser(db, 'super@lendflow.test', 'Super Admin')
  await asService(db, () => db.query('select public.lf_grant_platform_admin($1)', [superAdmin]))
  ;({ owner: ownerA, org: orgA } = await createAccount('Alpha Lending', 'alpha@lendflow.test'))
  ;({ owner: ownerB, org: orgB } = await createAccount('Bravo Credit', 'bravo@lendflow.test'))
  staffA = await createUser(db, 'staff.alpha@lendflow.test', 'Alpha Collector', { organization_id: orgA, role: 'collector' })
})

describe('platform super admin', () => {
  it('reports platform-admin status through my_access without an organization', async () => {
    const a = await rpc(superAdmin, 'my_access')
    expect(a.is_platform_admin).toBe(true)
    expect(a.organization_id).toBeNull()
    const o = await rpc(ownerA, 'my_access')
    expect(o.is_platform_admin).toBe(false)
    expect(o.organization_id).toBe(orgA)
    expect(o.role).toBe('owner')
  })

  it('creates a ready-to-use lender account with owner, permissions, products and template', async () => {
    const products = await q(ownerA, 'select name from public.loan_products')
    expect(products.length).toBe(4)
    const templates = await q(ownerA, 'select name from public.loan_agreement_templates')
    expect(templates.length).toBe(1)
    const org = await q(ownerA, 'select name, created_by from public.organizations')
    expect(org).toEqual([{ name: 'Alpha Lending', created_by: superAdmin }])
    const preview = await rpc(ownerA, 'preview_loan', { p: JSON.stringify({
      principal: 50000, interest_method: 'flat', interest_rate: 2, rate_period: 'monthly', repayment_frequency: 'monthly',
      term_count: 10, processing_fee_type: 'none', penalty_enabled: false }) })
    expect(Number(preview.summary.total_interest)).toBe(10000)
  })

  it('lists accounts with owner and counts, and nobody else can call the console functions', async () => {
    const list = await rpc<any[]>(superAdmin, 'platform_list_organizations')
    expect(list.map((o) => o.name).sort()).toEqual(['Alpha Lending', 'Bravo Credit'])
    const alpha = list.find((o) => o.id === orgA)
    expect(alpha.owner.email).toBe('alpha@lendflow.test')
    expect(alpha.stats.users).toBe(2)
    const detail = await rpc(superAdmin, 'platform_get_organization', { p_org: orgA })
    expect(detail.members.map((m: any) => m.role).sort()).toEqual(['collector', 'owner'])
    const overview = await rpc(superAdmin, 'platform_overview')
    expect(overview.organizations).toBe(2)

    for (const fn of ['platform_overview', 'platform_list_organizations']) {
      await expect(rpc(ownerA, fn)).rejects.toThrow(/Platform administrator access required/)
      await expect(rpc(null, fn)).rejects.toThrow()
    }
    await expect(rpc(ownerA, 'platform_set_organization_status', { p_org: orgB, p_status: 'suspended', p_reason: 'x' }))
      .rejects.toThrow(/Platform administrator access required/)
  })

  it('gives the super admin no access to lender records', async () => {
    expect(await q(superAdmin, 'select id from public.organizations')).toEqual([])
    expect(await q(superAdmin, 'select id from public.loan_products')).toEqual([])
    expect(await q(superAdmin, 'select id from public.borrowers')).toEqual([])
    await expect(rpc(superAdmin, 'create_organization', { p: JSON.stringify({ name: 'Sneaky Co' }) }))
      .rejects.toThrow(/created by the LendFlow administrator/)
  })

  it('keeps service-only functions away from API users', async () => {
    await expect(q(superAdmin, `select public.lf_grant_platform_admin($1)`, [ownerA])).rejects.toThrow(/permission denied/)
    await expect(q(superAdmin, `select public.lf_platform_create_organization('{"name":"X"}'::jsonb, $1, $1)`, [superAdmin]))
      .rejects.toThrow(/permission denied/)
    await expect(q(ownerA, `insert into public.platform_admins (user_id, email) values ($1, 'x')`, [ownerA])).rejects.toThrow()
    await expect(asService(db, () => db.query('select public.lf_grant_platform_admin($1)', [ownerA])))
      .rejects.toThrow(/belongs to a lender account/)
  })

  it('stops an owner from changing their own account status', async () => {
    await expect(q(ownerA, `update public.organizations set status = 'suspended' where id = $1`, [orgA]))
      .rejects.toThrow(/platform administrator/)
    // ordinary settings edits still work
    await q(ownerA, `update public.organizations set contact_phone = '+63 2 8123 4567' where id = $1`, [orgA])
  })

  it('suspending an account blocks all of its users until reactivated', async () => {
    await expect(rpc(superAdmin, 'platform_set_organization_status', { p_org: orgA, p_status: 'suspended' }))
      .rejects.toThrow(/reason/)
    const s = await rpc(superAdmin, 'platform_set_organization_status', { p_org: orgA, p_status: 'suspended', p_reason: 'Unpaid subscription' })
    expect(s.status).toBe('suspended')

    const a = await rpc(ownerA, 'my_access')
    expect(a.organization_id).toBeNull()
    expect(a.suspended_organization).toEqual({ name: 'Alpha Lending', reason: 'Unpaid subscription' })
    expect(await q(ownerA, 'select id from public.loan_products')).toEqual([])
    expect(await q(staffA, 'select id from public.organizations')).toEqual([])
    await expect(rpc(ownerA, 'preview_loan', { p: '{}' })).rejects.toThrow(/Not authenticated or not a member/)
    await expect(rpc(ownerA, 'create_organization', { p: JSON.stringify({ name: 'Escape Co' }) })).rejects.toThrow(/created by the LendFlow administrator/)
    // other accounts are unaffected
    expect((await q(ownerB, 'select id from public.loan_products')).length).toBe(4)

    await rpc(superAdmin, 'platform_set_organization_status', { p_org: orgA, p_status: 'active' })
    expect((await rpc(ownerA, 'my_access')).organization_id).toBe(orgA)
    expect((await q(ownerA, 'select id from public.loan_products')).length).toBe(4)
    const log = await q(ownerA, `select action from public.audit_logs where action like 'account_%' order by id`)
    expect(log.map((l: any) => l.action)).toEqual(['account_created', 'account_suspended', 'account_reactivated'])
  })

  it('does not let a login without an account create one for itself', async () => {
    const stranger = await createUser(db, 'stranger@example.test', 'Self Signup')
    await expect(rpc(stranger, 'create_organization', { p: JSON.stringify({ name: 'My Own Lending' }) }))
      .rejects.toThrow(/created by the LendFlow administrator/)
    const a = await rpc(stranger, 'my_access')
    expect(a.organization_id).toBeNull()
    expect(await q(stranger, 'select id from public.organizations')).toEqual([])
  })

  it('refuses to attach a login that already belongs to an account', async () => {
    await expect(asService(db, () => db.query(`select public.lf_platform_create_organization('{"name":"Dup"}'::jsonb, $1, $2)`, [ownerB, superAdmin])))
      .rejects.toThrow(/already belongs/)
    await expect(asService(db, () => db.query(`select public.lf_platform_create_organization('{"name":"Dup"}'::jsonb, $1, $1)`, [superAdmin])))
      .rejects.toThrow(/platform administrator/)
  })
})
