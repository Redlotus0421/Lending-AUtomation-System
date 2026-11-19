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
const base = { principal: 12000, interest_method: 'flat', interest_rate: 2, rate_period: 'monthly', repayment_frequency: 'semimonthly' }
const dates = (r: any) => r.schedule.map((x: any) => x.due_date)

beforeAll(async () => {
  db = await createDb()
  owner = await createUser(db, 'owner@payday.ph', 'Rosa Cruz')
  superAdmin = await createUser(db, 'root@payday.ph', 'Platform Admin')
  await asService(db, () => db.query('select public.lf_grant_platform_admin($1)', [superAdmin]))
  org = await createOrganization(db, owner, { name: 'Payday Lending' })
  borrower = (await q(owner, `insert into public.borrowers (first_name, last_name, mobile_number) values ('Ana', 'Reyes', '09171230000') returning id`))[0].id
})

describe('semi-monthly pay days', () => {
  it('falls due on the 5th and 20th; first payment is the first pay day at least 10 days after release', async () => {
    const r = await schedule({ ...base, term_months: 3, release_date: '2026-11-19', pay_days: [20, 5] })
    expect(dates(r)).toEqual(['2026-12-05', '2026-12-20', '2027-01-05', '2027-01-20', '2027-02-05', '2027-02-20'])
    expect(r.summary.pay_days).toEqual([5, 20])
    expect(r.summary.duration_label).toBe('3 months (6 semi-monthly payments, every 5th and 20th)')
    // flat interest is unchanged: 2% x 3 months
    expect(Number(r.summary.total_interest)).toBe(720)
    // released on the 8th: the 20th is 12 days away
    expect(dates(await schedule({ ...base, term_count: 2, release_date: '2026-11-08', pay_days: [5, 20] }))).toEqual(['2026-11-20', '2026-12-05'])
  })

  it('starts on a later pay day the lender picks, and clamps month ends (15th & 30th in February)', async () => {
    const r = await schedule({ ...base, term_count: 4, release_date: '2028-01-20', pay_days: [15, 30], first_due_date: '2028-02-15' })
    expect(dates(r)).toEqual(['2028-02-15', '2028-02-29', '2028-03-15', '2028-03-30'])
    const r2 = await schedule({ ...base, term_count: 3, release_date: '2027-01-20', pay_days: [10, 31], first_due_date: '2027-01-31' })
    expect(dates(r2)).toEqual(['2027-01-31', '2027-02-10', '2027-02-28'])
  })

  it('rejects pay days that are too close, and a first payment that is not a pay day', async () => {
    await expect(schedule({ ...base, term_count: 4, release_date: '2026-11-19', pay_days: [5, 8] })).rejects.toThrow(/7 days apart/)
    await expect(schedule({ ...base, term_count: 4, release_date: '2026-11-19', pay_days: [5] })).rejects.toThrow(/two different pay days/)
    await expect(schedule({ ...base, term_count: 4, release_date: '2026-11-19', pay_days: [5, 20], first_due_date: '2026-12-06' }))
      .rejects.toThrow(/5th and 20th/)
  })

  it('keeps the original 15-day pattern when no pay days are chosen, and ignores pay days for monthly loans', async () => {
    expect(dates(await schedule({ ...base, term_count: 2, release_date: '2026-11-14', first_due_date: '2026-11-29' }))).toEqual(['2026-11-29', '2026-12-14'])
    const m = await schedule({ ...base, repayment_frequency: 'monthly', term_count: 2, release_date: '2026-11-14', pay_days: [5, 20] })
    expect(m.summary.pay_days).toBeNull()
    expect(dates(m)).toEqual(['2026-12-14', '2027-01-14'])
  })

  it('stores the pay days on simplified loans and agreements recompute the same schedule', async () => {
    await rpc(superAdmin, 'platform_set_lending_mode', { p_org: org, p_mode: 'simplified' })
    const input = { transaction_type: 'new', borrower_id: borrower, principal: 10000, term_months: 2, repayment_frequency: 'semimonthly', pay_days: [5, 20] }
    const pv = await rpc<any>(owner, 'preview_simplified_loan', { p: JSON.stringify(input) })
    expect(pv.summary.pay_days).toEqual([5, 20])
    const pick = pv.schedule[1].due_date // start one pay day later
    const created = await rpc<any>(owner, 'create_simplified_loan', { p: JSON.stringify({ ...input, first_due_date: pick }) })
    const l = (await q(owner, `select pay_days, first_due_date::text as f from public.loans where id = $1`, [created.id]))[0]
    expect(l.pay_days).toEqual([5, 20])
    expect(l.f).toBe(pick)
    // the agreement freezes terms by recomputing from the loan record; it must match the saved figures
    const data = await rpc<any>(owner, 'agreement_begin', { p_loan_id: created.id })
    expect(data.summary.pay_days).toEqual([5, 20])
    const due = data.schedule.map((x: any) => x.due_date)
    expect(due[0]).toBe(pick)
    expect(due.length).toBe(4)
    expect(due.every((d: string) => ['05', '20'].includes(d.slice(8)))).toBe(true)
  })
})
