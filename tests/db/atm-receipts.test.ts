// ATM withdrawals on payments and the receipt snapshot (20261125000021_atm_withdrawal_receipts.sql)
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
  owner = await createUser(db, 'owner@atm.ph', 'ATM Owner')
  org = await createOrganization(db, owner, { name: 'ATM Lending' })
}, 60000)

describe('ATM withdrawal receipts', () => {
  it('records the withdrawal, posts only the payment and freezes the breakdown on the receipt', async () => {
    const loan = await activeLoan({ principal: 5000, term_count: 2 }, await daysAgo(3))
    const today = (await q(owner, `select public.lf_today()::text d`))[0].d
    const before = (await q(owner, `select total_outstanding::numeric t from public.loans where id = $1`, [loan]))[0].t

    await expect(rpc(owner, 'record_payment', { p: JSON.stringify({ loan_id: loan, amount: 2600, atm_withdrawal: 2000,
      payment_date: today, method: 'cash', idempotency_key: randomUUID() }) })).rejects.toThrow(/cannot be less than the loan payment/)

    const pay = await rpc<any>(owner, 'record_payment', { p: JSON.stringify({ loan_id: loan, amount: 2600, atm_withdrawal: 7500,
      payment_date: today, method: 'cash', idempotency_key: randomUUID() }) })
    const row = (await q(owner, `select p.amount::numeric a, p.atm_withdrawal::numeric w, r.data d, l.total_outstanding::numeric t
      from public.loan_payments p join public.payment_receipts r on r.payment_id = p.id join public.loans l on l.id = p.loan_id
      where p.id = $1`, [pay.payment_id]))[0]
    expect(Number(row.a)).toBe(2600)
    expect(Number(row.w)).toBe(7500)
    expect(Number(before) - Number(row.t)).toBeCloseTo(2600, 2)
    expect(Number(row.d.atm_withdrawal)).toBe(7500)
    expect(Number(row.d.cash_returned)).toBe(4900)
    expect(row.d.recorded_at).toBeTruthy()
    expect(row.d.installments[0].status).toBe('paid')
    expect(row.d.installments[0].due_date).toBeTruthy()

    // the next payment without a withdrawal stays a plain payment (the setting does not leak)
    const pay2 = await rpc<any>(owner, 'record_payment', { p: JSON.stringify({ loan_id: loan, amount: 100,
      payment_date: today, method: 'cash', idempotency_key: randomUUID() }) })
    const r2 = (await q(owner, `select p.atm_withdrawal w, r.data d from public.loan_payments p join public.payment_receipts r on r.payment_id = p.id where p.id = $1`, [pay2.payment_id]))[0]
    expect(r2.w).toBeNull()
    expect(r2.d.cash_returned).toBeNull()
    expect(r2.d.installments[0].status).toBe('partially_paid')

    // posted withdrawals cannot be edited
    await expect(asService(db, () => db.query(`update public.loan_payments set atm_withdrawal = 9000 where id = $1`, [pay.payment_id])))
      .rejects.toThrow(/cannot be edited/)
  })
})
