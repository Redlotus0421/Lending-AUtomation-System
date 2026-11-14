import { beforeAll, describe, expect, it } from 'vitest'
import { asService, asUser, createDb, createOrganization, createUser, one, type Db } from './harness'

let db: Db
let owner: string
let superAdmin: string
let org: string
let borrower: string

const rpc = async <T = any>(user: string | null, fn: string, args: Record<string, unknown> = {}) => {
  const keys = Object.keys(args)
  const sql = `select public.${fn}(${keys.map((k, i) => `${k} => $${i + 1}`).join(', ')}) as r`
  return asUser(db, user, async () => (await one<{ r: T }>(db, sql, keys.map((k) => args[k]))).r)
}
const q = async <T = any>(user: string | null, sql: string, params: unknown[] = []) =>
  asUser(db, user, async () => (await db.query<T>(sql, params)).rows)
const schedule = async (p: Record<string, unknown>) =>
  (await one<{ r: any }>(db, `select public.compute_loan_schedule($1::jsonb) as r`, [JSON.stringify(p)])).r

beforeAll(async () => {
  db = await createDb()
  owner = await createUser(db, 'owner@kinsenas.ph', 'Rosa Cruz')
  superAdmin = await createUser(db, 'root@kinsenas.ph', 'Platform Admin')
  await asService(db, () => db.query('select public.lf_grant_platform_admin($1)', [superAdmin]))
  org = await createOrganization(db, owner, { name: 'Kinsenas Lending' })
  borrower = (await q(owner, `insert into public.borrowers (first_name, last_name, mobile_number) values ('Ana', 'Reyes', '09171230000') returning id`))[0].id
})

describe('semi-monthly schedule engine', () => {
  it('charges half the monthly rate per installment (flat)', async () => {
    const r = await schedule({ principal: 12000, interest_method: 'flat', interest_rate: 2, rate_period: 'monthly',
      repayment_frequency: 'semimonthly', term_count: 12, release_date: '2026-01-31' })
    expect(Number(r.summary.periodic_rate_pct)).toBe(1)
    expect(Number(r.summary.total_interest)).toBe(1440) // 2% x 6 months
    expect(Number(r.summary.installment_amount)).toBe(1120)
    expect(Number(r.summary.total_repayment)).toBe(13440)
    expect(r.summary.duration_label).toBe('12 semi-monthly payments (6 months)')
    // default first due 15 days after release, then the 15th / 30th (Feb clamps to the 28th)
    expect(r.schedule.slice(0, 5).map((x: any) => x.due_date)).toEqual(['2026-02-15', '2026-02-28', '2026-03-15', '2026-03-30', '2026-04-15'])
    expect(r.summary.last_due_date).toBe('2026-07-30')
  })

  it('anchors on the first due date: month end + 16th, leap years included', async () => {
    const r = await schedule({ principal: 10000, interest_method: 'flat', interest_rate: 2, rate_period: 'monthly',
      repayment_frequency: 'semimonthly', term_count: 6, release_date: '2028-01-10', first_due_date: '2028-01-31' })
    expect(r.schedule.map((x: any) => x.due_date)).toEqual(['2028-01-31', '2028-02-16', '2028-02-29', '2028-03-16', '2028-03-31', '2028-04-16'])
    const r2 = await schedule({ principal: 10000, interest_method: 'flat', interest_rate: 2, rate_period: 'monthly',
      repayment_frequency: 'semimonthly', term_count: 4, release_date: '2026-11-14', first_due_date: '2026-11-29' })
    expect(r2.schedule.map((x: any) => x.due_date)).toEqual(['2026-11-29', '2026-12-14', '2026-12-29', '2027-01-14'])
    expect(r2.summary.duration_label).toBe('4 semi-monthly payments (2 months)')
  })

  it('amortizes diminishing-balance loans to zero (30/360 and actual/365)', async () => {
    for (const day_count of ['30/360', 'actual/365']) {
      const r = await schedule({ principal: 24000, interest_method: 'diminishing', interest_rate: 24, rate_period: 'yearly',
        repayment_frequency: 'semimonthly', term_count: 24, release_date: '2026-03-01', day_count })
      expect(Number(r.schedule[r.schedule.length - 1].closing_balance)).toBe(0)
      expect(Number(r.summary.total_interest)).toBeGreaterThan(0)
      expect(Number(r.summary.total_interest)).toBeLessThan(24000 * 0.24)
    }
  })
})

describe('semi-monthly loans', () => {
  it('is offered by default and works through the complete workflow', async () => {
    const s = await rpc<any>(owner, 'get_settings')
    expect(s.repayment.frequencies).toContain('semimonthly')
    const created = await rpc<any>(owner, 'create_loan', { p: JSON.stringify({
      borrower_id: borrower, principal: 6000, interest_method: 'flat', interest_rate: 3, rate_period: 'monthly',
      repayment_frequency: 'semimonthly', term_count: 6, processing_fee_type: 'none', penalty_enabled: false, submit: true }) })
    const l = (await q(owner, `select repayment_frequency, total_interest, installment_amount from public.loans where id = $1`, [created.id]))[0]
    expect(l.repayment_frequency).toBe('semimonthly')
    expect(Number(l.total_interest)).toBe(540) // 1.5% per half-month x 6
    expect(Number(l.installment_amount)).toBe(1090)
  })

  it('lets simplified loans use an enabled frequency, and refuses a disabled one', async () => {
    await rpc(superAdmin, 'platform_set_lending_mode', { p_org: org, p_mode: 'simplified' })
    const pv = await rpc<any>(owner, 'preview_simplified_loan', { p: JSON.stringify({ principal: 10000, term_count: 6, repayment_frequency: 'semimonthly' }) })
    expect(pv.summary.repayment_frequency).toBe('semimonthly')
    expect(Number(pv.summary.total_interest)).toBe(600) // default 2%/month -> 1% x 6
    await rpc(owner, 'update_settings', { p_section: 'repayment', p_values: JSON.stringify({ frequencies: ['monthly'] }) })
    await expect(rpc(owner, 'preview_simplified_loan', { p: JSON.stringify({ principal: 10000, term_count: 6, repayment_frequency: 'semimonthly' }) }))
      .rejects.toThrow(/not enabled/)
  })
})
