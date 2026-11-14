import { randomUUID } from 'node:crypto'
import { beforeAll, describe, expect, it } from 'vitest'
import { asService, asUser, createDb, createOrganization, createUser, one, type Db } from './harness'

let db: Db
let owner: string
let collector: string
let officer: string
let otherOwner: string
let org: string
let otherOrg: string
let borrowerId: string
let borrower2Id: string
let loanId: string

const rpc = async <T = any>(user: string | null, fn: string, args: Record<string, unknown> = {}) => {
  const keys = Object.keys(args)
  const sql = `select public.${fn}(${keys.map((k, i) => `${k} => $${i + 1}`).join(', ')}) as r`
  return asUser(db, user, async () => (await one<{ r: T }>(db, sql, keys.map((k) => args[k]))).r)
}
const q = async <T = any>(user: string | null, sql: string, params: unknown[] = []) =>
  asUser(db, user, async () => (await db.query<T>(sql, params)).rows)

/** Drive an agreement from generation to full signature the way the Edge Functions do. */
async function signAgreementFully(loan: string) {
  const data = await rpc<any>(owner, 'agreement_begin', { p_loan_id: loan })
  const agreementId = data.agreement.id as string
  expect(data.template.body).toContain('{{repayment_schedule}}')
  expect(data.schedule.length).toBeGreaterThan(0)
  await asService(db, () =>
    db.query(`select public.agreement_complete($1, $2, $3, $4::jsonb, 3, '{}'::jsonb, 'body')`, [
      agreementId, `${org}/${agreementId}/unsigned-v1.pdf`, 'hash-unsigned', JSON.stringify({ borrower: { page: 2 }, lender: { page: 2 } }),
    ]),
  )
  // borrower signs through a secure link
  const session = await rpc<any>(owner, 'signing_session_create', { p_agreement_id: agreementId, p_signer_role: 'borrower' })
  expect(session.token).toBeTruthy()
  const lookup = await asService(db, async () =>
    (await one<{ r: any }>(db, `select public.signing_lookup(public.lf_sha256_hex($1)) as r`, [session.token])).r)
  expect(lookup.session.status).toBe('pending')
  const wrong = await asService(db, async () =>
    (await one<{ r: any }>(db, `select public.signing_verify($1, '1900-01-01', null, null) as r`, [lookup.session.id])).r)
  expect(wrong.ok).toBe(false)
  const ok = await asService(db, async () =>
    (await one<{ r: any }>(db, `select public.signing_verify($1, '1990-04-12', null, null) as r`, [lookup.session.id])).r)
  expect(ok.ok).toBe(true)

  // lender cannot sign first (borrower_first)
  await expect(asService(db, () => db.query(`select public.signature_commit($1::jsonb)`, [JSON.stringify({
    agreement_id: agreementId, signer_role: 'lender', signer_name: 'Owner', signer_user_id: owner,
    image_path: 'x', image_hash: 'y', consent_text: 'I agree' })]))).rejects.toThrow(/borrower must sign before/)

  const c1 = await asService(db, async () => (await one<{ r: any }>(db, `select public.signature_commit($1::jsonb) as r`, [JSON.stringify({
    agreement_id: agreementId, signer_role: 'borrower', session_id: lookup.session.id, signer_name: 'Juan Dela Cruz',
    image_path: `${org}/${agreementId}/sig-borrower.png`, image_hash: 'h1', consent_text: 'I agree to sign electronically' })])).r)
  expect(c1.duplicate).toBe(false)
  // idempotent re-submit
  const c1b = await asService(db, async () => (await one<{ r: any }>(db, `select public.signature_commit($1::jsonb) as r`, [JSON.stringify({
    agreement_id: agreementId, signer_role: 'borrower', session_id: lookup.session.id, signer_name: 'Juan Dela Cruz',
    image_path: 'other', image_hash: 'other', consent_text: 'x' })])).r)
  expect(c1b.duplicate).toBe(true)
  await asService(db, () => db.query(`select public.agreement_set_pdf($1, $2, 'hash-1', 1)`, [agreementId, `${org}/${agreementId}/signed-1.pdf`]))

  const c2 = await asService(db, async () => (await one<{ r: any }>(db, `select public.signature_commit($1::jsonb) as r`, [JSON.stringify({
    agreement_id: agreementId, signer_role: 'lender', signer_name: 'Maria Santos', signer_user_id: owner,
    image_path: `${org}/${agreementId}/sig-lender.png`, image_hash: 'h2', consent_text: 'I agree' })])).r)
  expect(c2.all_signed).toBe(true)
  // stale CAS (1 signature) is ignored, final (2 signatures) applies
  const stale = await asService(db, async () => (await one<{ r: any }>(db, `select public.agreement_set_pdf($1, 'stale.pdf', 'stale', 0) as r`, [agreementId])).r)
  expect(stale.applied).toBe(false)
  const fin = await asService(db, async () => (await one<{ r: any }>(db, `select public.agreement_set_pdf($1, $2, 'hash-final', 2) as r`, [agreementId, `${org}/${agreementId}/signed-2.pdf`])).r)
  expect(fin.finalized).toBe(true)
  return agreementId
}

beforeAll(async () => {
  db = await createDb()
  owner = await createUser(db, 'owner@lender.ph', 'Maria Santos')
  otherOwner = await createUser(db, 'owner@other.ph', 'Other Owner')
  org = await createOrganization(db, owner, { name: 'Bayanihan Lending Corp.', city: 'Quezon City' })
  otherOrg = await createOrganization(db, otherOwner, { name: 'Other Lending Inc.' })
  collector = await createUser(db, 'collector@lender.ph', 'Carlo Reyes', { organization_id: org, role: 'collector' })
  officer = await createUser(db, 'officer@lender.ph', 'Lea Bautista', { organization_id: org, role: 'loan_officer' })
})

describe('onboarding', () => {
  it('seeds products, template, permissions and settings', async () => {
    const products = await q(owner, `select code from public.loan_products order by code`)
    expect(products.map((p: any) => p.code)).toContain('PERSONAL')
    const tpl = await q(owner, `select t.name, v.version_no from public.loan_agreement_templates t join public.loan_agreement_template_versions v on v.id = t.current_version_id`)
    expect(tpl).toHaveLength(1)
    const access = await rpc<any>(collector, 'my_access')
    expect(access.role).toBe('collector')
    expect(access.permissions).toContain('payments.record')
    expect(access.permissions).not.toContain('settings.manage')
    const settings = await rpc<any>(owner, 'get_settings')
    expect(settings.agreement.signing_order).toBe('borrower_first')
  })
})

describe('borrowers', () => {
  it('creates borrowers with normalized mobile numbers and prevents duplicates', async () => {
    const rows = await q(owner, `insert into public.borrowers (first_name, last_name, date_of_birth, mobile_number, city_municipality, province)
      values ('Juan', 'Dela Cruz', '1990-04-12', '0917 123 4567', 'Quezon City', 'Metro Manila') returning id, borrower_code, mobile_number, organization_id`)
    borrowerId = rows[0].id
    expect(rows[0].mobile_number).toBe('+639171234567')
    expect(rows[0].borrower_code).toBe('BRW-000001')
    expect(rows[0].organization_id).toBe(org)
    await q(owner, `insert into public.borrower_identities (borrower_id, id_type, id_number) values ($1, 'PhilSys National ID', '1234-5678-9012-3456')`, [borrowerId])
    await expect(q(owner, `insert into public.borrowers (first_name, last_name, date_of_birth, mobile_number) values ('juan', 'dela cruz', '1990-04-12', '09170000000')`))
      .rejects.toThrow(/duplicate key/)
    const dups = await q(owner, `select * from public.check_borrower_duplicates('Juan', 'Dela Cruz', '1990-04-12', '09171234567')`)
    expect(dups.length).toBe(1)
    const b2 = await q(owner, `insert into public.borrowers (first_name, last_name, date_of_birth, mobile_number) values ('Ana', 'Lim', '1985-09-30', '09181112222') returning id`)
    borrower2Id = b2[0].id
  })
})

describe('loan lifecycle', () => {
  it('previews without creating records', async () => {
    const before = await q(owner, `select count(*)::int as n from public.loans`)
    const pv = await rpc<any>(owner, 'preview_loan', { p: JSON.stringify({ principal: 50000, interest_method: 'flat', interest_rate: 2, rate_period: 'monthly', repayment_frequency: 'monthly', term_count: 10, processing_fee_type: 'none', penalty_enabled: false }) })
    expect(Number(pv.summary.total_interest)).toBe(10000)
    const after = await q(owner, `select count(*)::int as n from public.loans`)
    expect(after[0].n).toBe(before[0].n)
  })

  it('creates, approves, signs and releases a loan (TEST 1 terms)', async () => {
    const releaseDate = await q(owner, `select (public.lf_today() - 100) as d`)
    const created = await rpc<any>(owner, 'create_loan', { p: JSON.stringify({
      borrower_id: borrowerId, principal: 50000, interest_method: 'flat', interest_rate: 2, rate_period: 'monthly',
      repayment_frequency: 'monthly', term_count: 10, release_date: releaseDate[0].d, processing_fee_type: 'none',
      penalty_enabled: true, penalty_type: 'percent_monthly', penalty_rate: 3, penalty_grace_days: 3, collector_id: collector, submit: true }) })
    loanId = created.id
    expect(created.loan_number).toMatch(/^LN-\d{4}-000001$/)
    expect(created.status).toBe('pending_review')
    const loan = (await q(owner, `select * from public.loans where id = $1`, [loanId]))[0]
    expect(Number(loan.total_interest)).toBe(10000)
    expect(Number(loan.total_repayment)).toBe(60000)
    expect(Number(loan.installment_amount)).toBe(6000)

    // loan officer cannot approve
    await expect(rpc(officer, 'approve_loan', { p_loan_id: loanId })).rejects.toThrow(/permission/)
    await rpc(owner, 'approve_loan', { p_loan_id: loanId, p_notes: 'Good standing' })
    expect((await q(owner, `select status from public.loans where id = $1`, [loanId]))[0].status).toBe('approved')

    // release blocked before signing
    await expect(rpc(owner, 'release_loan', { p_loan_id: loanId, p: '{}' })).rejects.toThrow(/fully signed/)

    await signAgreementFully(loanId)
    expect((await q(owner, `select status from public.loans where id = $1`, [loanId]))[0].status).toBe('ready_for_release')

    const rel = await rpc<any>(owner, 'release_loan', { p_loan_id: loanId, p: JSON.stringify({ release_date: releaseDate[0].d, method: 'cash' }) })
    expect(rel.installments).toBe(10)
    const active = (await q(owner, `select * from public.loans where id = $1`, [loanId]))[0]
    expect(active.status).toBe('active')
    expect(Number(active.outstanding_principal)).toBe(50000)
    expect(Number(active.outstanding_interest)).toBe(10000)
    expect(Number(active.total_outstanding)).toBe(60000)
    const ledger = await q(owner, `select entry_type, balance_total from public.financial_ledger where loan_id = $1 order by entry_no`, [loanId])
    expect(ledger.map((x: any) => x.entry_type)).toEqual(['disbursement', 'loan_booked'])
    expect(Number(ledger[1].balance_total)).toBe(60000)
  })

  it('TEST 10: a signed agreement cannot be modified', async () => {
    const a = (await q(owner, `select id from public.loan_agreements where loan_id = $1 and status = 'fully_signed'`, [loanId]))[0]
    await expect(db.query(`update public.loan_agreements set signed_pdf_path = 'tampered.pdf' where id = $1`, [a.id])).rejects.toThrow(/cannot be modified/)
    await expect(db.query(`delete from public.agreement_signatures where agreement_id = $1`, [a.id])).rejects.toThrow(/immutable/)
    await expect(rpc(owner, 'agreement_void', { p_agreement_id: a.id, p_reason: 'test' })).rejects.toThrow(/cannot be voided/)
    await expect(rpc(owner, 'update_loan', { p_loan_id: loanId, p: JSON.stringify({ principal: 1000 }) })).rejects.toThrow(/cannot be edited/)
  })
})

describe('payments', () => {
  it('TEST 7: full installment payment updates installment, ledger, receipt and balances', async () => {
    const inst1 = (await q(owner, `select id, due_date from public.loan_installments where loan_id = $1 and installment_no = 1`, [loanId]))[0]
    const r = await rpc<any>(collector, 'record_payment', { p: JSON.stringify({
      loan_id: loanId, amount: 6000, payment_date: inst1.due_date, method: 'cash', idempotency_key: randomUUID() }) })
    expect(Number(r.principal_applied)).toBe(5000)
    expect(Number(r.interest_applied)).toBe(1000)
    expect(r.receipt_number).toMatch(/^OR-/)
    expect(Number(r.total_outstanding)).toBe(54000)
    expect(Number(r.outstanding_principal)).toBe(45000)
    expect(Number(r.outstanding_interest)).toBe(9000)
    const i1 = (await q(owner, `select status, paid_at from public.loan_installments where id = $1`, [inst1.id]))[0]
    expect(i1.status).toBe('paid')
    const receipt = (await q(owner, `select data from public.payment_receipts where payment_id = $1`, [r.payment_id]))[0]
    expect(Number(receipt.data.remaining_balance)).toBe(54000)
    expect(receipt.data.collector_name).toBe('Carlo Reyes')
    const last = (await q(owner, `select entry_type, cash_delta, balance_total from public.financial_ledger where loan_id = $1 order by entry_no desc limit 1`, [loanId]))[0]
    expect(last.entry_type).toBe('payment')
    expect(Number(last.balance_total)).toBe(54000)
  })

  it('TEST 8: partial payment leaves the correct remainder (with late penalty)', async () => {
    const inst2 = (await q(owner, `select id, due_date from public.loan_installments where loan_id = $1 and installment_no = 2`, [loanId]))[0]
    const payDate = (await q(owner, `select ($1::date + 10) as d`, [inst2.due_date]))[0].d
    const r = await rpc<any>(collector, 'record_payment', { p: JSON.stringify({
      loan_id: loanId, amount: 2500, payment_date: payDate, method: 'e_wallet', reference_no: 'GC-0001', idempotency_key: randomUUID() }) })
    // 10 days late, 3 grace days -> 7 days of 3%/month on 6000 = 42.00 penalty, paid first
    expect(Number(r.penalty_applied)).toBe(42)
    expect(Number(r.interest_applied)).toBe(1000)
    expect(Number(r.principal_applied)).toBe(1458)
    const i2 = (await q(owner, `select * from public.v_installments where id = $1`, [inst2.id]))[0]
    expect(i2.status).toBe('partially_paid')
    expect(Number(i2.base_remaining)).toBe(6000 - 2458)
  })

  it('TEST 9: duplicate submissions are prevented', async () => {
    const key = randomUUID()
    const today = (await q(owner, `select public.lf_today() as d`))[0].d
    const payload = JSON.stringify({ loan_id: loanId, amount: 1000, payment_date: today, method: 'bank_transfer', reference_no: 'BDO-7788', idempotency_key: key })
    const a = await rpc<any>(owner, 'record_payment', { p: payload })
    const b = await rpc<any>(owner, 'record_payment', { p: payload })
    expect(b.duplicate).toBe(true)
    expect(b.payment_id).toBe(a.payment_id)
    // same bank reference with a different key is also rejected
    await expect(rpc(owner, 'record_payment', { p: JSON.stringify({ loan_id: loanId, amount: 1000, payment_date: today, method: 'bank_transfer', reference_no: 'bdo-7788', idempotency_key: randomUUID() }) }))
      .rejects.toThrow(/duplicate/)
    const count = await q(owner, `select count(*)::int as n from public.loan_payments where idempotency_key = $1`, [key])
    expect(count[0].n).toBe(1)
  })

  it('reverses only the latest payment and restores balances', async () => {
    const latest = (await q(owner, `select id, amount from public.loan_payments where loan_id = $1 and status = 'posted' order by payment_date desc, created_at desc limit 1`, [loanId]))[0]
    const first = (await q(owner, `select id from public.loan_payments where loan_id = $1 order by created_at limit 1`, [loanId]))[0]
    await expect(rpc(owner, 'reverse_payment', { p_payment_id: first.id, p_reason: 'x' })).rejects.toThrow(/most recent/)
    await expect(rpc(collector, 'reverse_payment', { p_payment_id: latest.id, p_reason: 'x' })).rejects.toThrow(/permission/)
    const before = (await q(owner, `select total_outstanding from public.loans where id = $1`, [loanId]))[0]
    await rpc(owner, 'reverse_payment', { p_payment_id: latest.id, p_reason: 'Wrong amount encoded' })
    const after = (await q(owner, `select total_outstanding from public.loans where id = $1`, [loanId]))[0]
    expect(Number(after.total_outstanding)).toBe(Number(before.total_outstanding) + Number(latest.amount))
    await expect(db.query(`delete from public.loan_payments where id = $1`, [latest.id])).rejects.toThrow(/immutable/)
    await expect(db.query(`update public.financial_ledger set amount = 0 where loan_id = $1`, [loanId])).rejects.toThrow(/immutable/)
  })

  it('quotes and posts an early settlement without unearned interest', async () => {
    const quote = await rpc<any>(owner, 'get_settlement_quote', { p_loan_id: loanId })
    expect(Number(quote.interest_rebate)).toBeGreaterThan(0)
    expect(Number(quote.total)).toBeLessThan(Number(quote.contract_remaining))
    const r = await rpc<any>(owner, 'record_payment', { p: JSON.stringify({
      loan_id: loanId, amount: quote.total, payment_type: 'settlement', method: 'cash', idempotency_key: randomUUID() }) })
    expect(r.loan_status).toBe('completed')
    expect(Number(r.total_outstanding)).toBe(0)
    const loan = (await q(owner, `select status, outstanding_interest, interest_waived from public.loans where id = $1`, [loanId]))[0]
    expect(Number(loan.outstanding_interest)).toBe(0)
    expect(Number(loan.interest_waived)).toBeCloseTo(Number(quote.interest_rebate), 2)
  })
})

describe('reports', () => {
  it('builds the dashboard and reports from real records', async () => {
    const d = await rpc<any>(owner, 'dashboard_summary', {})
    expect(Number(d.financial.capital_released_all_time)).toBe(50000)
    expect(d.operational.borrowers).toBe(2)
    expect(d.monthly.length).toBe(12)
    const rep = await rpc<any>(owner, 'get_report', { p_report: 'total_collections', p_from: '2000-01-01', p_to: '2100-01-01' })
    expect(rep.rows.length).toBeGreaterThan(0)
    for (const r of ['loans_released', 'principal_outstanding', 'outstanding_receivables', 'interest_earned', 'interest_collected', 'overdue_accounts', 'completed_loans', 'defaulted_loans', 'collector_performance', 'borrower_performance', 'daily_cash', 'monthly_cash']) {
      const x = await rpc<any>(owner, 'get_report', { p_report: r, p_from: '2026-01-01', p_to: '2026-12-31' })
      expect(Array.isArray(x.rows)).toBe(true)
    }
  })
})

describe('security', () => {
  it('TEST 11: collectors cannot change settings or see unassigned borrowers', async () => {
    await expect(rpc(collector, 'update_settings', { p_section: 'interest', p_values: '{"default_rate": 9}' })).rejects.toThrow(/permission/)
    await expect(rpc(collector, 'create_loan', { p: JSON.stringify({ borrower_id: borrower2Id, principal: 1000, term_count: 2 }) })).rejects.toThrow(/permission/)
    const visible = await q(collector, `select id from public.borrowers`)
    expect(visible.map((b: any) => b.id)).toEqual([borrowerId]) // assigned via loan only
    const ids = await q(collector, `select borrower_id from public.borrower_identities`)
    expect(ids).toHaveLength(0) // no sensitive access
    const audit = await q(collector, `select id from public.audit_logs`)
    expect(audit).toHaveLength(0)
    await expect(q(collector, `update public.organizations set name = 'hacked'`)).resolves.toEqual([])
    expect((await q(owner, `select name from public.organizations`))[0].name).toBe('Bayanihan Lending Corp.')
  })

  it('TEST 12: organizations are fully isolated', async () => {
    for (const t of ['borrowers', 'loans', 'loan_payments', 'loan_agreements', 'financial_ledger', 'payment_receipts', 'loan_installments', 'audit_logs', 'loan_products', 'system_settings']) {
      const rows = await q(otherOwner, `select * from public.${t} where organization_id = $1`, [org])
      expect(rows, t).toHaveLength(0)
    }
    await expect(rpc(otherOwner, 'record_payment', { p: JSON.stringify({ loan_id: loanId, amount: 1, idempotency_key: randomUUID() }) })).rejects.toThrow(/not found/)
    await expect(q(otherOwner, `insert into public.borrowers (organization_id, first_name, last_name, mobile_number) values ($1, 'X', 'Y', '09170000001')`, [org]))
      .rejects.toThrow(/row-level security/)
    const anon = await q(null, `select * from public.loans`).catch((e) => e)
    expect(anon instanceof Error || (Array.isArray(anon) && anon.length === 0)).toBe(true)
  })

  it('internal functions are not callable by API roles', async () => {
    await expect(rpc(owner, 'signature_commit', { p: '{}' })).rejects.toThrow(/permission denied/)
    await expect(rpc(owner, 'lf_post_ledger', { p_loan: loanId, p_type: 'adjustment', p_date: '2026-01-01', p_amount: 1, p_cash: 0, p_dp: -100, p_di: 0, p_dc: 0, p_dpen: 0, p_desc: 'x' }))
      .rejects.toThrow(/permission denied/)
  })
})
