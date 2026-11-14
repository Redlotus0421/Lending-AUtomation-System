import { beforeAll, describe, expect, it } from 'vitest'
import { asService, asUser, createDb, createOrganization, createUser, one, type Db } from './harness'

let db: Db
let owner: string
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
const base = { interest_method: 'flat', interest_rate: 5, rate_period: 'monthly', release_date: '2026-11-14' }

beforeAll(async () => {
  db = await createDb()
  owner = await createUser(db, 'owner@months.ph', 'Mila Torres')
  const admin = await createUser(db, 'root@months.ph', 'Platform Admin')
  await asService(db, () => db.query('select public.lf_grant_platform_admin($1)', [admin]))
  org = await createOrganization(db, owner, { name: 'Months Lending' })
  await rpc(admin, 'platform_set_lending_mode', { p_org: org, p_mode: 'simplified' })
  await rpc(owner, 'update_settings', { p_section: 'interest', p_values: JSON.stringify({ default_rate: 5, default_method: 'flat', default_rate_period: 'monthly' }) })
  borrower = (await q(owner, `insert into public.borrowers (first_name, last_name, mobile_number) values ('Jose', 'Rizal', '09171239999') returning id`))[0].id
})

describe('repayment period in months', () => {
  it('ACCEPTANCE: ₱60,000 · semi-monthly · 12 months · 5% monthly flat', async () => {
    const pv = await rpc<any>(owner, 'preview_simplified_loan', { p: JSON.stringify({ principal: 60000, term_months: 12, repayment_frequency: 'semimonthly' }) })
    const s = pv.summary
    expect(Number(s.total_interest)).toBe(36000)
    expect(Number(s.total_repayment)).toBe(96000)
    expect(s.installment_count).toBe(24)
    expect(Number(s.installment_amount)).toBe(4000)
    expect(Number(s.last_installment_amount)).toBe(4000)
    expect(s.term_months).toBe(12)
    expect(Number(s.monthly_rate_pct)).toBe(5)
    expect(s.duration_label).toBe('12 months (24 semi-monthly payments)')
    expect(pv.schedule).toHaveLength(24)
  })

  it('derives installments from the frequency', async () => {
    const m = await schedule({ ...base, principal: 12000, term_months: 12, repayment_frequency: 'monthly' })
    expect(m.summary.installment_count).toBe(12)
    expect(Number(m.summary.total_interest)).toBe(7200)
    const sm = await schedule({ ...base, principal: 12000, term_months: 6, repayment_frequency: 'semimonthly' })
    expect(sm.summary.installment_count).toBe(12)
    expect(Number(sm.summary.total_interest)).toBe(3600)
  })

  it('weekly and daily use the actual due dates inside the period; interest stays principal × monthly rate × months', async () => {
    // release Nov 14 + 1 month = Dec 14; weekly from Nov 21: Nov 21, 28, Dec 5, 12
    const w = await schedule({ ...base, principal: 10000, term_months: 1, repayment_frequency: 'weekly' })
    expect(w.schedule.map((x: any) => x.due_date)).toEqual(['2026-11-21', '2026-11-28', '2026-12-05', '2026-12-12'])
    expect(Number(w.summary.total_interest)).toBe(500)
    expect(Number(w.summary.installment_amount)).toBe(2625)
    // daily from Nov 15 through Dec 14 = 30 days
    const d = await schedule({ ...base, principal: 10000, term_months: 1, repayment_frequency: 'daily' })
    expect(d.summary.installment_count).toBe(30)
    expect(Number(d.summary.total_interest)).toBe(500)
    expect(d.summary.last_due_date).toBe('2026-12-14')
  })

  it('puts rounding differences in the final installment', async () => {
    const r = await schedule({ ...base, principal: 10000, term_months: 3, repayment_frequency: 'semimonthly' }) // 11,500 / 6
    expect(Number(r.summary.installment_amount)).toBe(1916.67)
    expect(Number(r.summary.last_installment_amount)).toBe(1916.65)
    const sum = r.schedule.reduce((t: number, x: any) => t + Number(x.total), 0)
    expect(Math.round(sum * 100) / 100).toBe(11500)
  })

  it('validates the repayment period', async () => {
    for (const bad of [0, -3, 6.5, 'abc', 361]) {
      await expect(schedule({ ...base, principal: 10000, term_months: bad, repayment_frequency: 'monthly' })).rejects.toThrow(/Repayment period/)
    }
  })

  it('stores the months on the loan and the agreement recomputes identical figures', async () => {
    const r = await rpc<any>(owner, 'create_simplified_loan', { p: JSON.stringify({ transaction_type: 'new', borrower_id: borrower, principal: 60000, term_months: 12, repayment_frequency: 'semimonthly' }) })
    const l = (await q(owner, `select term_months, term_count, total_interest, total_repayment, installment_amount from public.v_loans where id = $1`, [r.id]))[0]
    expect(l.term_months).toBe(12)
    expect(l.term_count).toBe(24)
    expect(Number(l.total_repayment)).toBe(96000)
    const a = await rpc<any>(owner, 'agreement_begin', { p_loan_id: r.id })
    expect(Number(a.summary.total_repayment)).toBe(96000)
    expect(Number(a.summary.installment_amount)).toBe(4000)
    expect(a.summary.term_months).toBe(12)
    expect(a.schedule).toHaveLength(24)
  })

  it('leaves installment-based loans unchanged', async () => {
    const r = await schedule({ ...base, principal: 12000, term_count: 12, repayment_frequency: 'semimonthly' })
    expect(r.summary.term_months).toBeNull()
    expect(Number(r.summary.total_interest)).toBe(3600) // 2.5% x 12 half-months
  })
})
