// Regression tests for the fixes in 20261125000020_audit_fixes.sql
import { randomUUID } from 'node:crypto'
import { beforeAll, describe, expect, it } from 'vitest'
import { asService, asUser, createDb, createOrganization, createUser, one, type Db } from './harness'

let db: Db
let owner: string
let org: string

const rpc = async <T = any>(user: string | null, fn: string, args: Record<string, unknown> = {}) => {
  const keys = Object.keys(args)
  const sql = `select public.${fn}(${keys.map((k, i) => `${k} => $${i + 1}`).join(', ')}) as r`
  return asUser(db, user, async () => (await one<{ r: T }>(db, sql, keys.map((k) => args[k]))).r)
}
const q = async <T = any>(user: string | null, sql: string, params: unknown[] = []) =>
  asUser(db, user, async () => (await db.query<T>(sql, params)).rows)
const svc = (sql: string, params: unknown[] = []) => asService(db, () => one<any>(db, sql, params))

async function signFully(loan: string) {
  const data = await rpc<any>(owner, 'agreement_begin', { p_loan_id: loan })
  const id = data.agreement.id
  await svc(`select public.agreement_complete($1, $2, 'h', '{}'::jsonb, 3, '{}'::jsonb, 'body')`, [id, `${org}/${id}/u.pdf`])
  for (const role of data.agreement.required_signers) {
    await svc(`select public.signature_commit($1::jsonb)`, [JSON.stringify({
      agreement_id: id, signer_role: role, signer_name: 'X', signer_user_id: owner,
      verification_method: role === 'borrower' ? 'in_person' : 'none', witnessed_by: owner, image_path: 'x', image_hash: 'y', consent_text: 'ok',
    })])
  }
  await svc(`select public.agreement_set_pdf($1, 'f.pdf', 'hf', $2)`, [id, data.agreement.required_signers.length])
}

let bn = 0
async function borrower() {
  bn++
  const r = await q(owner, `insert into public.borrowers (first_name, last_name, date_of_birth, mobile_number)
    values ($1, 'Audit', '1990-01-01', $2) returning id`, [`B${bn}`, `091712345${String(bn).padStart(2, '0')}`])
  return r[0].id as string
}
async function activeLoan(p: Record<string, unknown>, release: string) {
  const c = await rpc<any>(owner, 'create_loan', { p: JSON.stringify({
    borrower_id: await borrower(), principal: 50000, interest_method: 'flat', interest_rate: 2, rate_period: 'monthly',
    repayment_frequency: 'monthly', term_count: 10, release_date: release, processing_fee_type: 'none', penalty_enabled: false,
    submit: true, ...p,
  }) })
  await rpc(owner, 'approve_loan', { p_loan_id: c.id })
  await signFully(c.id)
  await rpc(owner, 'release_loan', { p_loan_id: c.id, p: JSON.stringify({ release_date: release }) })
  return c.id as string
}
const daysAgo = async (n: number) => (await q(owner, `select (public.lf_today() - $1::int)::text d`, [n]))[0].d as string

beforeAll(async () => {
  db = await createDb()
  owner = await createUser(db, 'owner@audit.ph', 'Audit Owner')
  org = await createOrganization(db, owner, { name: 'Audit Lending' })
}, 60000)

describe('audit fixes', () => {
  it('refuses to reverse a payment made before a reschedule, and the loan can still be paid off', async () => {
    const loan = await activeLoan({}, await daysAgo(40))
    const due1 = (await q(owner, `select due_date::text d from public.loan_installments where loan_id = $1 and installment_no = 1`, [loan]))[0].d
    const pay = await rpc<any>(owner, 'record_payment', { p: JSON.stringify({ loan_id: loan, amount: 3000, payment_date: due1, method: 'cash', idempotency_key: randomUUID() }) })
    await rpc(owner, 'reschedule_loan', { p_loan_id: loan, p: JSON.stringify({ term_count: 6, reason: 'hardship' }) })

    await expect(rpc(owner, 'reverse_payment', { p_payment_id: pay.payment_id, p_reason: 'bounced' }))
      .rejects.toThrow(/before the loan was rescheduled/)

    const st = (await q(owner, `select l.total_outstanding::numeric t,
        (select sum(public.lf_inst_remaining(i)) from public.loan_installments i
          where i.loan_id = l.id and i.status in ('pending', 'partially_paid'))::numeric s
      from public.loans l where l.id = $1`, [loan]))[0]
    expect(Number(st.s)).toBeCloseTo(Number(st.t), 2)
    const today = (await q(owner, `select public.lf_today()::text d`))[0].d
    const full = await rpc<any>(owner, 'record_payment', { p: JSON.stringify({ loan_id: loan, amount: Number(st.t), payment_date: today, method: 'cash', idempotency_key: randomUUID() }) })
    expect(full.loan_status).toBe('completed')
  })

  it('never produces a negative last installment when spreading interest or charges', async () => {
    for (const p of [
      { principal: 5000, interest_rate: 0.1, other_charges: 10, charges_treatment: 'added' },
      { principal: 5000, interest_rate: 0.0033 },
    ]) {
      const r = await svc(`select public.compute_loan_schedule($1::jsonb) r`, [JSON.stringify({
        rate_period: 'daily', repayment_frequency: 'daily', term_count: 60, release_date: '2026-01-15', interest_method: 'flat', ...p,
      })])
      for (const row of r.r.schedule) {
        expect(Number(row.interest)).toBeGreaterThanOrEqual(0)
        expect(Number(row.charges)).toBeGreaterThanOrEqual(0)
      }
      const sum = r.r.schedule.reduce((a: number, x: any) => a + Number(x.charges), 0)
      expect(sum).toBeCloseTo(Number(p.other_charges ?? 0), 2)
    }
    // and such a loan can now be released
    const id = await activeLoan({ principal: 5000, interest_rate: 0.1, rate_period: 'daily', repayment_frequency: 'daily', term_count: 60,
      other_charges: 10, charges_treatment: 'added' }, (await q(owner, `select public.lf_today()::text d`))[0].d)
    expect((await q(owner, `select status from public.loans where id = $1`, [id]))[0].status).toBe('active')
  })

  it('keeps the semi-monthly pay days when a loan is rescheduled', async () => {
    const loan = await activeLoan({ repayment_frequency: 'semimonthly', term_count: 6, pay_days: [5, 20] }, await daysAgo(3))
    await rpc(owner, 'reschedule_loan', { p_loan_id: loan, p: JSON.stringify({ term_count: 4, reason: 'hardship' }) })
    const days = await q(owner, `select extract(day from due_date)::int d from public.loan_installments
      where loan_id = $1 and status = 'pending' order by installment_no`, [loan])
    expect(days.length).toBe(4)
    for (const { d } of days) expect([5, 20]).toContain(d)
  })

  it('does not let a user create demo loans to get around the lending-mode lock', async () => {
    await asService(db, () => db.query(`update public.organizations set lending_mode = 'simplified' where id = $1`, [org]))
    const base = { borrower_id: await borrower(), principal: 5000, interest_method: 'flat', interest_rate: 9, rate_period: 'monthly',
      repayment_frequency: 'monthly', term_count: 3, submit: true }
    await expect(rpc(owner, 'create_loan', { p: JSON.stringify(base) })).rejects.toThrow(/Simplified Lending/)
    await expect(rpc(owner, 'create_loan', { p: JSON.stringify({ ...base, is_demo: true }) })).rejects.toThrow(/sample-data loader/)
    // and an existing loan cannot be flipped to demo (users cannot update loans directly; the trigger stops everyone else)
    await expect(asService(db, () => db.query(`update public.loans set is_demo = true where organization_id = $1`, [org])))
      .rejects.toThrow(/sample-data loader/)
    await asService(db, () => db.query(`update public.organizations set lending_mode = 'complete' where id = $1`, [org]))
  })
})
