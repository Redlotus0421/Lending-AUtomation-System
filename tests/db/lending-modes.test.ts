import { randomUUID } from 'node:crypto'
import { beforeAll, describe, expect, it } from 'vitest'
import { asService, asUser, createDb, createOrganization, createUser, one, type Db } from './harness'

let db: Db
let owner: string
let officer: string
let superAdmin: string
let org: string
let borrowerA: string // existing borrower with a Complete Lending loan
let borrowerB: string // new borrower registered through Simplified Lending
let completeLoan: string
let simpleLoan: string
let renewalLoan: string

const rpc = async <T = any>(user: string | null, fn: string, args: Record<string, unknown> = {}) => {
  const keys = Object.keys(args)
  const sql = `select public.${fn}(${keys.map((k, i) => `${k} => $${i + 1}`).join(', ')}) as r`
  return asUser(db, user, async () => (await one<{ r: T }>(db, sql, keys.map((k) => args[k]))).r)
}
const q = async <T = any>(user: string | null, sql: string, params: unknown[] = []) =>
  asUser(db, user, async () => (await db.query<T>(sql, params)).rows)
const svc = async <T = any>(sql: string, params: unknown[] = []) => asService(db, async () => (await one<{ r: T }>(db, sql, params)).r)

/** Generate + sign an agreement the way the Edge Functions do; the borrower signs in person. */
async function signInPerson(loan: string) {
  const data = await rpc<any>(owner, 'agreement_begin', { p_loan_id: loan })
  const id = data.agreement.id as string
  await asService(db, () => db.query(`select public.agreement_complete($1, $2, 'h0', '{}'::jsonb, 3, '{}'::jsonb, 'body')`, [id, `${org}/${id}/u.pdf`]))
  // a borrower cannot sign without a link unless a staff member witnesses it in person
  await expect(svc(`select public.signature_commit($1::jsonb) as r`, [JSON.stringify({
    agreement_id: id, signer_role: 'borrower', signer_name: 'X', image_path: 'x', image_hash: 'x', consent_text: 'c' })])).rejects.toThrow(/in person/)
  const b = await svc<any>(`select public.signature_commit($1::jsonb) as r`, [JSON.stringify({
    agreement_id: id, signer_role: 'borrower', signer_name: 'Borrower', verification_method: 'in_person', witnessed_by: owner,
    image_path: `${org}/${id}/b.png`, image_hash: 'hb', consent_text: 'I agree' })])
  expect(b.duplicate).toBe(false)
  let n = 1
  await svc(`select public.agreement_set_pdf($1, 'p1.pdf', 'h1', 1) as r`, [id])
  if ((data.agreement.required_signers as string[]).includes('co_maker')) {
    // the co-maker also signs in person, witnessed by staff
    await expect(svc(`select public.signature_commit($1::jsonb) as r`, [JSON.stringify({
      agreement_id: id, signer_role: 'co_maker', signer_name: 'X', image_path: 'x', image_hash: 'x', consent_text: 'c' })])).rejects.toThrow(/co-maker signs/)
    await svc(`select public.signature_commit($1::jsonb) as r`, [JSON.stringify({
      agreement_id: id, signer_role: 'co_maker', signer_name: data.co_maker.full_name, verification_method: 'in_person', witnessed_by: owner,
      image_path: `${org}/${id}/c.png`, image_hash: 'hc', consent_text: 'I agree' })])
    n++
    const mid = await svc<any>(`select public.agreement_set_pdf($1, 'pc.pdf', 'hc', ${n}) as r`, [id])
    expect(mid.finalized).toBe(false)
  }
  await svc(`select public.signature_commit($1::jsonb) as r`, [JSON.stringify({
    agreement_id: id, signer_role: 'lender', signer_name: 'Maria Santos', signer_user_id: owner,
    image_path: `${org}/${id}/l.png`, image_hash: 'hl', consent_text: 'I agree' })])
  n++
  const fin = await svc<any>(`select public.agreement_set_pdf($1, 'p2.pdf', 'h2', ${n}) as r`, [id])
  expect(fin.finalized).toBe(true)
  return { id, data }
}

beforeAll(async () => {
  db = await createDb()
  owner = await createUser(db, 'owner@simple.ph', 'Maria Santos')
  superAdmin = await createUser(db, 'root@lendflow.ph', 'Platform Admin')
  await asService(db, () => db.query('select public.lf_grant_platform_admin($1)', [superAdmin]))
  org = await createOrganization(db, owner, { name: 'Simple Lending Co.', city: 'Pasig' })
  officer = await createUser(db, 'officer@simple.ph', 'Lea Bautista', { organization_id: org, role: 'loan_officer' })
  borrowerA = (await q(owner, `insert into public.borrowers (first_name, last_name, date_of_birth, mobile_number)
    values ('Juan', 'Dela Cruz', '1990-04-12', '09171234567') returning id`))[0].id
})

describe('lending mode configuration', () => {
  it('defaults to Complete Lending and only the platform admin can change it', async () => {
    expect((await q(owner, `select lending_mode from public.organizations`))[0].lending_mode).toBe('complete')
    await expect(q(owner, `update public.organizations set lending_mode = 'simplified' where id = $1`, [org]))
      .rejects.toThrow(/platform administrator/)
    await expect(rpc(owner, 'platform_set_lending_mode', { p_org: org, p_mode: 'simplified' })).rejects.toThrow(/Platform administrator/)
    // simplified RPCs are refused while the business uses Complete Lending
    await expect(rpc(owner, 'create_simplified_loan', { p: JSON.stringify({ borrower_id: borrowerA, principal: 5000, term_count: 3 }) }))
      .rejects.toThrow(/not enabled/)
  })

  it('Complete Lending keeps working (a loan released 45 days ago)', async () => {
    const d = (await q(owner, `select (public.lf_today() - 45) as d`))[0].d
    const created = await rpc<any>(owner, 'create_loan', { p: JSON.stringify({
      borrower_id: borrowerA, principal: 10000, interest_method: 'flat', interest_rate: 3, rate_period: 'monthly',
      repayment_frequency: 'monthly', term_count: 6, release_date: d, processing_fee_type: 'none', penalty_enabled: false, submit: true }) })
    completeLoan = created.id
    await rpc(owner, 'approve_loan', { p_loan_id: completeLoan })
    await signInPerson(completeLoan)
    await rpc(owner, 'release_loan', { p_loan_id: completeLoan, p: JSON.stringify({ release_date: d, method: 'cash' }) })
    const l = (await q(owner, `select status, lending_mode, total_outstanding from public.loans where id = $1`, [completeLoan]))[0]
    expect(l.status).toBe('active')
    expect(l.lending_mode).toBe('complete')
    expect(Number(l.total_outstanding)).toBe(11800)
  })

  it('switches the business to Simplified Lending (audited) and blocks the complete workflow', async () => {
    const r = await rpc<any>(superAdmin, 'platform_set_lending_mode', { p_org: org, p_mode: 'simplified' })
    expect(r.lending_mode).toBe('simplified')
    const list = await rpc<any[]>(superAdmin, 'platform_list_organizations')
    expect(list.find((o) => o.id === org)?.lending_mode).toBe('simplified')
    const audit = await q(owner, `select old_data, new_data from public.audit_logs where action = 'lending_mode_changed'`)
    expect(audit[0].new_data.lending_mode).toBe('simplified')
    await expect(rpc(owner, 'create_loan', { p: JSON.stringify({ borrower_id: borrowerA, principal: 5000, term_count: 3 }) }))
      .rejects.toThrow(/uses Simplified Lending/)
  })
})

describe('simplified new loan', () => {
  it('uses the business default rate and refuses a rate override unless allowed', async () => {
    const pv = await rpc<any>(owner, 'preview_simplified_loan', { p: JSON.stringify({ principal: 20000, term_count: 4 }) })
    // defaults: flat 2% per month, monthly, no simplified fee
    expect(Number(pv.summary.interest_rate)).toBe(2)
    expect(Number(pv.summary.total_interest)).toBe(1600)
    expect(Number(pv.summary.total_repayment)).toBe(21600)
    expect(Number(pv.summary.installment_amount)).toBe(5400)
    expect(Number(pv.net_release)).toBe(20000)
    await expect(rpc(owner, 'preview_simplified_loan', { p: JSON.stringify({ principal: 20000, term_count: 4, interest_rate: 5 }) }))
      .rejects.toThrow(/fixed by your business settings \(2%\)/)
    await rpc(owner, 'update_settings', { p_section: 'simplified', p_values: JSON.stringify({ allow_rate_override: true }) })
    const pv2 = await rpc<any>(owner, 'preview_simplified_loan', { p: JSON.stringify({ principal: 20000, term_count: 4, interest_rate: 5 }) })
    expect(Number(pv2.summary.total_interest)).toBe(4000)
    expect(pv2.rate_overridden).toBe(true)
    await rpc(owner, 'update_settings', { p_section: 'simplified', p_values: JSON.stringify({ allow_rate_override: false, processing_fee_type: 'percent', processing_fee_value: 2 }) })
    await expect(rpc(owner, 'update_settings', { p_section: 'simplified', p_values: JSON.stringify({ default_term: 0 }) })).rejects.toThrow(/Default duration/)
  })

  it('creates the loan for a newly registered borrower, approved at creation by an approver', async () => {
    borrowerB = (await q(officer, `insert into public.borrowers (first_name, last_name, mobile_number, address_line, employer_name)
      values ('Ana', 'Lim', '09181112222', '12 Rizal St., Pasig City', 'Acme Corp') returning id`))[0].id
    await q(officer, `insert into public.guarantors (borrower_id, full_name, relationship, mobile_number) values ($1, 'Ben Lim', 'Spouse', '09190001111')`, [borrowerB])
    // the loan officer creates it; officers cannot approve, so it waits for review
    const byOfficer = await rpc<any>(officer, 'create_simplified_loan', { p: JSON.stringify({ transaction_type: 'new', borrower_id: borrowerB, principal: 10000, term_count: 5 }) })
    expect(byOfficer.status).toBe('pending_review')
    expect(byOfficer.approved).toBe(false)
    await rpc(owner, 'cancel_loan', { p_loan_id: byOfficer.id, p_reason: 'Test' })

    const r = await rpc<any>(owner, 'create_simplified_loan', { p: JSON.stringify({ transaction_type: 'new', borrower_id: borrowerB, principal: 10000, term_count: 5 }) })
    simpleLoan = r.id
    expect(r.status).toBe('approved')
    expect(r.approved).toBe(true)
    const l = (await q(owner, `select lending_mode, interest_rate, processing_fee, net_proceeds, total_repayment from public.loans where id = $1`, [simpleLoan]))[0]
    expect(l.lending_mode).toBe('simplified')
    expect(Number(l.interest_rate)).toBe(2)
    expect(Number(l.processing_fee)).toBe(200) // 2% simplified processing fee, deducted
    expect(Number(l.net_proceeds)).toBe(9800)
    expect(Number(l.total_repayment)).toBe(11000)
    const audit = await q(owner, `select action from public.audit_logs where record_id = $1 and action = 'simplified_loan_created'`, [simpleLoan])
    expect(audit).toHaveLength(1)
  })

  it('freezes simplified loan terms after creation', async () => {
    await expect(rpc(owner, 'update_loan', { p_loan_id: simpleLoan, p: JSON.stringify({ principal: 12000 }) }))
      .rejects.toThrow(/Simplified loans cannot be edited/)
  })

  it('includes the co-maker in the agreement data and releases after an in-person signature', async () => {
    const { data } = await signInPerson(simpleLoan)
    expect(data.co_maker.full_name).toBe('Ben Lim')
    // a borrower with a co-maker on file: the co-maker is a required signer
    expect(data.agreement.required_signers).toEqual(['borrower', 'co_maker', 'lender'])
    expect(data.renewal).toBeNull()
    const sigs = await q(owner, `select verification_method from public.agreement_signatures s join public.loan_agreements a on a.id = s.agreement_id
      where a.loan_id = $1 and s.signer_role = 'borrower'`, [simpleLoan])
    expect(sigs[0].verification_method).toBe('in_person')
    const rel = await rpc<any>(owner, 'release_loan', { p_loan_id: simpleLoan, p: JSON.stringify({ method: 'cash' }) })
    expect(Number(rel.renewal_deduction)).toBe(0)
    expect(Number(rel.net_release)).toBe(9800)
  })
})

describe('reloan / renew', () => {
  it('reports the outstanding balance from the database', async () => {
    const st = await rpc<any>(owner, 'simplified_borrower_status', { p_borrower_id: borrowerA })
    expect(st.borrower.full_name).toBe('Juan Dela Cruz')
    expect(st.has_outstanding).toBe(true)
    expect(st.open_loans).toHaveLength(1)
    const o = st.open_loans[0]
    expect(Number(o.contract_balance)).toBe(11800)
    // prorated early settlement: principal 10,000 + installment 1 interest (due) + earned part of installment 2
    expect(Number(o.payoff)).toBeGreaterThan(10300)
    expect(Number(o.payoff)).toBeLessThan(11800)
    expect(Number(o.payoff) + Number(o.interest_rebate)).toBe(11800)
    expect(st.history).toHaveLength(1)
  })

  it('refuses a "new loan" for a borrower with an open loan and an amount below the balance', async () => {
    await expect(rpc(owner, 'create_simplified_loan', { p: JSON.stringify({ transaction_type: 'new', borrower_id: borrowerA, principal: 20000, term_count: 6 }) }))
      .rejects.toThrow(/Use Reloan \/ Renew/)
    await expect(rpc(owner, 'create_simplified_loan', { p: JSON.stringify({ transaction_type: 'renewal', borrower_id: borrowerA, principal: 5000, term_count: 6 }) }))
      .rejects.toThrow(/must be more than the outstanding balance/)
  })

  it('creates the renewal with the deduction computed by the backend', async () => {
    const st = await rpc<any>(owner, 'simplified_borrower_status', { p_borrower_id: borrowerA })
    const payoff = Number(st.total_payoff)
    const pv = await rpc<any>(owner, 'preview_simplified_loan', { p: JSON.stringify({ transaction_type: 'renewal', borrower_id: borrowerA, principal: 20000, term_count: 6 }) })
    expect(Number(pv.renewal_deduction)).toBe(payoff)
    // 2% processing fee (400) is deducted too
    expect(Number(pv.net_release)).toBeCloseTo(20000 - 400 - payoff, 2)

    const r = await rpc<any>(owner, 'create_simplified_loan', { p: JSON.stringify({ transaction_type: 'renewal', borrower_id: borrowerA, principal: 20000, term_count: 6 }) })
    renewalLoan = r.id
    expect(Number(r.renewal_deduction)).toBe(payoff)
    const rn = await q(owner, `select status, quoted_amount from public.loan_renewals where new_loan_id = $1`, [renewalLoan])
    expect(rn).toHaveLength(1)
    expect(rn[0].status).toBe('pending')
    expect(Number(rn[0].quoted_amount)).toBe(payoff)
    const app = (await q(owner, `select source from public.loan_applications where loan_id = $1`, [renewalLoan]))[0]
    expect(app.source).toBe('renewal')
    // the same previous loan cannot be renewed twice at once
    await expect(rpc(owner, 'create_simplified_loan', { p: JSON.stringify({ transaction_type: 'renewal', borrower_id: borrowerA, principal: 30000, term_count: 6 }) }))
      .rejects.toThrow(/already being renewed/)
    // renewal settlements cannot be recorded by hand
    await expect(rpc(owner, 'record_payment', { p: JSON.stringify({ loan_id: completeLoan, amount: 100, method: 'renewal', idempotency_key: randomUUID() }) }))
      .rejects.toThrow(/posted automatically/)
  })

  it('settles the previous loan at release and disburses only the net amount', async () => {
    const { data } = await signInPerson(renewalLoan)
    expect(data.renewal.loans[0].loan_number).toMatch(/^LN-/)
    const quoted = Number(data.renewal.total)

    const rel = await rpc<any>(owner, 'release_loan', { p_loan_id: renewalLoan, p: JSON.stringify({ method: 'cash' }) })
    expect(rel.settled_loans).toBe(1)
    expect(Number(rel.renewal_deduction)).toBe(quoted) // same day: the quote is exact
    expect(Number(rel.net_release)).toBeCloseTo(19600 - quoted, 2)

    const old = (await q(owner, `select status, total_outstanding, interest_waived from public.loans where id = $1`, [completeLoan]))[0]
    expect(old.status).toBe('completed')
    expect(Number(old.total_outstanding)).toBe(0)
    expect(Number(old.interest_waived)).toBeGreaterThan(0) // unearned interest rebated

    const pay = (await q(owner, `select method, payment_type, amount, reference_no from public.loan_payments where loan_id = $1`, [completeLoan]))[0]
    expect(pay.method).toBe('renewal')
    expect(pay.payment_type).toBe('settlement')
    expect(Number(pay.amount)).toBe(quoted)

    const rn = (await q(owner, `select status, settled_amount, settlement_payment_id from public.loan_renewals where new_loan_id = $1`, [renewalLoan]))[0]
    expect(rn.status).toBe('settled')
    expect(Number(rn.settled_amount)).toBe(quoted)
    expect(rn.settlement_payment_id).toBeTruthy()

    const payId = (await q(owner, `select id from public.loan_payments where loan_id = $1`, [completeLoan]))[0].id
    await expect(rpc(owner, 'reverse_payment', { p_payment_id: payId, p_reason: 'test' })).rejects.toThrow(/cannot be reversed on its own/)

    const nl = (await q(owner, `select status, renewal_deduction from public.v_loans where id = $1`, [renewalLoan]))[0]
    expect(nl.status).toBe('active')
    expect(Number(nl.renewal_deduction)).toBe(quoted)

    // ledger history of both loans is kept; net cash out = net proceeds - settlement
    const cash = await q(owner, `select coalesce(sum(cash_delta), 0) as c from public.financial_ledger where loan_id in ($1, $2) and entry_date = public.lf_today()`, [completeLoan, renewalLoan])
    expect(Number(cash[0].c)).toBeCloseTo(-(19600 - quoted), 2)
    const actions = (await q(owner, `select action from public.audit_logs where action in ('loan_renewal_created', 'renewal_balance_settled', 'loan_disbursed')`)).map((a: any) => a.action)
    expect(actions).toEqual(expect.arrayContaining(['loan_renewal_created', 'renewal_balance_settled', 'loan_disbursed']))
  })

  it('releases its hold on the previous loan when a renewal is cancelled', async () => {
    const r = await rpc<any>(owner, 'create_simplified_loan', { p: JSON.stringify({ transaction_type: 'renewal', borrower_id: borrowerB, principal: 15000, term_count: 3 }) })
    await rpc(owner, 'cancel_loan', { p_loan_id: r.id, p_reason: 'Borrower changed their mind' })
    const rn = (await q(owner, `select status from public.loan_renewals where new_loan_id = $1`, [r.id]))[0]
    expect(rn.status).toBe('cancelled')
    const again = await rpc<any>(owner, 'create_simplified_loan', { p: JSON.stringify({ transaction_type: 'renewal', borrower_id: borrowerB, principal: 15000, term_count: 3 }) })
    expect(again.id).toBeTruthy()
  })

  it('a regular renewal without an outstanding balance has no deduction', async () => {
    const c = (await q(owner, `insert into public.borrowers (first_name, last_name, mobile_number) values ('Carla', 'Reyes', '09201234567') returning id`))[0].id
    const st = await rpc<any>(owner, 'simplified_borrower_status', { p_borrower_id: c })
    expect(st.has_outstanding).toBe(false)
    const r = await rpc<any>(owner, 'create_simplified_loan', { p: JSON.stringify({ transaction_type: 'renewal', borrower_id: c, principal: 8000, term_count: 2 }) })
    expect(Number(r.renewal_deduction)).toBe(0)
    expect((await q(owner, `select count(*)::int as n from public.loan_renewals where new_loan_id = $1`, [r.id]))[0].n).toBe(0)
  })
})

describe('co-maker signature', () => {
  it('creates a co-maker signing link with the name from the borrower record, and can be turned off', async () => {
    // a fresh borrower with a co-maker on file
    const bor = (await q(owner, `insert into public.borrowers (first_name, last_name, mobile_number) values ('Carla', 'Diaz', '09175550000') returning id`))[0].id
    await q(owner, `insert into public.guarantors (borrower_id, full_name, relationship, mobile_number) values ($1, 'Dan Diaz', 'Brother', '09176660000')`, [bor])
    const l = await rpc<any>(owner, 'create_simplified_loan', { p: JSON.stringify({ transaction_type: 'new', borrower_id: bor, principal: 5000, term_count: 2 }) })
    const data = await rpc<any>(owner, 'agreement_begin', { p_loan_id: l.id })
    const id = data.agreement.id
    expect(data.agreement.required_signers).toContain('co_maker')
    await asService(db, () => db.query(`select public.agreement_complete($1, $2, 'h0', '{}'::jsonb, 3, '{}'::jsonb, 'body')`, [id, `${org}/${id}/u.pdf`]))
    const link = await rpc<any>(owner, 'signing_session_create', { p_agreement_id: id, p_signer_role: 'co_maker', p_signer_name: 'Someone Else' })
    expect(link.signer_name).toBe('Dan Diaz')
    const sess = (await q(owner, `select signer_contact from public.agreement_signing_sessions where id = $1`, [link.session_id]))[0]
    expect(sess.signer_contact).toBe('09176660000')

    // turning the setting off: new agreements no longer ask for the co-maker
    await rpc(owner, 'update_settings', { p_section: 'agreement', p_values: JSON.stringify({ require_co_maker_signature: false }) })
    await rpc(owner, 'cancel_loan', { p_loan_id: l.id, p_reason: 'Test' })
    const l2 = await rpc<any>(owner, 'create_simplified_loan', { p: JSON.stringify({ transaction_type: 'new', borrower_id: bor, principal: 5000, term_count: 2 }) })
    const d2 = await rpc<any>(owner, 'agreement_begin', { p_loan_id: l2.id })
    expect(d2.agreement.required_signers).not.toContain('co_maker')
    await rpc(owner, 'update_settings', { p_section: 'agreement', p_values: JSON.stringify({ require_co_maker_signature: true }) })
  })
})
