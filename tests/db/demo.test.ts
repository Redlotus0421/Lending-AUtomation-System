import { beforeAll, describe, expect, it } from 'vitest'
import { asUser, createDb, createOrganization, createUser, one, type Db } from './harness'

let db: Db
let owner: string
let admin: string

const rpc = async <T = any>(user: string, fn: string, args: Record<string, unknown> = {}) => {
  const keys = Object.keys(args)
  const sql = `select public.${fn}(${keys.map((k, i) => `${k} => $${i + 1}`).join(', ')}) as r`
  return asUser(db, user, async () => (await one<{ r: T }>(db, sql, keys.map((k) => args[k]))).r)
}
const q = async <T = any>(user: string, sql: string, params: unknown[] = []) =>
  asUser(db, user, async () => (await db.query<T>(sql, params)).rows)

beforeAll(async () => {
  db = await createDb()
  owner = await createUser(db, 'owner@demo.ph', 'Demo Owner')
  const org = await createOrganization(db, owner, { name: 'Demo Lending', city: 'Makati City' })
  admin = await createUser(db, 'admin@demo.ph', 'Demo Admin', { organization_id: org, role: 'admin' })
})

describe('seed_demo_data', () => {
  it('is restricted to the owner', async () => {
    await expect(rpc(admin, 'seed_demo_data')).rejects.toThrow(/permission|owner/i)
  })

  it('creates borrowers, loans in every state and consistent ledgers', async () => {
    const r = await rpc<any>(owner, 'seed_demo_data')
    expect(r.borrowers).toBe(13)
    expect(r.loans).toBe(14)

    const statuses = await q(owner, `select status, count(*)::int as n from public.loans group by status`)
    const by = Object.fromEntries(statuses.map((s: any) => [s.status, s.n]))
    expect(by.active).toBeGreaterThanOrEqual(6)
    expect(by.completed).toBe(2)
    expect(by.defaulted).toBe(1)
    expect(by.draft).toBe(1)
    expect(by.pending_review).toBe(2)
    expect(by.approved).toBe(1)

    const d = await rpc<any>(owner, 'dashboard_summary', {})
    expect(Number(d.financial.capital_released)).toBeGreaterThan(0)
    expect(Number(d.collections.total)).toBeGreaterThan(0)
    expect(Number(d.collections.interest)).toBeGreaterThan(0)
    expect(Number(d.overdue.amount)).toBeGreaterThan(0)

    const loans = await q(owner, `select l.id, l.loan_number, l.total_outstanding,
        (select balance_total from public.financial_ledger f where f.loan_id = l.id order by entry_no desc limit 1) as ledger_total,
        (select coalesce(sum(public.lf_inst_remaining(i)), 0) from public.loan_installments i where i.loan_id = l.id and i.status in ('pending','partially_paid')) as inst_total
      from public.loans l where l.is_demo`)
    for (const l of loans) {
      if (l.ledger_total === null) {
        expect(Number(l.total_outstanding), l.loan_number).toBe(0)
      } else {
        expect(Number(l.ledger_total), l.loan_number).toBe(Number(l.total_outstanding))
        expect(Number(l.inst_total), l.loan_number).toBe(Number(l.total_outstanding))
      }
    }

    const penalties = await q(owner, `select coalesce(sum(penalties_assessed), 0) as p from public.loans where is_demo`)
    expect(Number(penalties[0].p)).toBeGreaterThan(0)
    const settled = await q(owner, `select count(*)::int as n from public.loan_payments where payment_type = 'settlement'`)
    expect(settled[0].n).toBe(1)
  })

  it('refuses to run twice', async () => {
    await expect(rpc(owner, 'seed_demo_data')).rejects.toThrow(/already been loaded/)
  })

  it('keeps internal helpers private', async () => {
    const loan = (await q(owner, `select id from public.loans where status = 'approved' limit 1`))[0]
    await expect(rpc(owner, 'lf_demo_activate', { p_loan_id: loan.id })).rejects.toThrow(/permission denied/)
  })
})
