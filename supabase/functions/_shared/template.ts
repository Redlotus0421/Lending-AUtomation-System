// Agreement template engine — shared by the PDF renderer (Edge Functions) and
// the template builder preview (web app). Pure TypeScript, no runtime imports.
//
// Template syntax (a deliberately small, legal-document-friendly subset of Markdown):
//   # Heading / ## Section / ### Sub-section
//   blank line separates paragraphs;  **bold** inline
//   "1. item" numbered list; "- item" bullet list;  "---" horizontal rule
//   {{placeholder}} inline values
//   {{repayment_schedule}}, {{disclosure_table}}, {{signature_block}} on their own line = blocks

import {
  allocationOrderText, amountInWords, durationLabel, FREQUENCY_LABEL, longDate, METHOD_LABEL, money,
  penaltyTerms, PERIOD_LABEL, shortDate, toNumber, trimNumber,
} from './format.ts'

export type Run = { text: string; bold: boolean }
export type Block =
  | { type: 'heading'; level: 1 | 2 | 3; runs: Run[] }
  | { type: 'paragraph'; runs: Run[] }
  | { type: 'list'; ordered: boolean; items: Run[][] }
  | { type: 'rule' }
  | { type: 'schedule' }
  | { type: 'disclosure' }
  | { type: 'signatures' }

export const BLOCK_PLACEHOLDERS = ['repayment_schedule', 'disclosure_table', 'signature_block'] as const

export const PLACEHOLDERS: { key: string; label: string; group: string }[] = [
  { key: 'agreement_number', label: 'Agreement number', group: 'Agreement' },
  { key: 'agreement_date', label: 'Agreement date', group: 'Agreement' },
  { key: 'loan_number', label: 'Loan reference', group: 'Agreement' },
  { key: 'lender_name', label: 'Lender name', group: 'Lender' },
  { key: 'lender_address', label: 'Lender address', group: 'Lender' },
  { key: 'lender_city', label: 'Lender city (venue)', group: 'Lender' },
  { key: 'lender_registration_no', label: 'SEC registration / CA no.', group: 'Lender' },
  { key: 'lender_representative', label: 'Lender representative', group: 'Lender' },
  { key: 'borrower_name', label: 'Borrower name', group: 'Borrower' },
  { key: 'borrower_address', label: 'Borrower address', group: 'Borrower' },
  { key: 'borrower_id_type', label: 'Borrower ID type', group: 'Borrower' },
  { key: 'borrower_id_number', label: 'Borrower ID number', group: 'Borrower' },
  { key: 'borrower_mobile', label: 'Borrower mobile', group: 'Borrower' },
  { key: 'borrower_email', label: 'Borrower email', group: 'Borrower' },
  { key: 'loan_amount', label: 'Loan amount', group: 'Loan terms' },
  { key: 'loan_amount_words', label: 'Loan amount in words', group: 'Loan terms' },
  { key: 'interest_rate', label: 'Interest rate', group: 'Loan terms' },
  { key: 'interest_method', label: 'Interest method', group: 'Loan terms' },
  { key: 'effective_interest_rate', label: 'Effective interest rate', group: 'Loan terms' },
  { key: 'loan_duration', label: 'Loan duration', group: 'Loan terms' },
  { key: 'payment_frequency', label: 'Payment frequency', group: 'Loan terms' },
  { key: 'installment_count', label: 'Number of installments', group: 'Loan terms' },
  { key: 'installment_amount', label: 'Installment amount', group: 'Loan terms' },
  { key: 'total_interest', label: 'Total interest', group: 'Loan terms' },
  { key: 'processing_fee', label: 'Processing fee', group: 'Loan terms' },
  { key: 'total_charges', label: 'Total fees & charges', group: 'Loan terms' },
  { key: 'net_proceeds', label: 'Net proceeds', group: 'Loan terms' },
  { key: 'total_repayment', label: 'Total repayment', group: 'Loan terms' },
  { key: 'release_date', label: 'Release date', group: 'Loan terms' },
  { key: 'first_payment_date', label: 'First payment date', group: 'Loan terms' },
  { key: 'last_payment_date', label: 'Last payment date', group: 'Loan terms' },
  { key: 'penalty_terms', label: 'Late payment terms', group: 'Loan terms' },
  { key: 'allocation_order', label: 'Payment allocation order', group: 'Loan terms' },
  { key: 'repayment_schedule', label: 'Repayment schedule (table)', group: 'Blocks' },
  { key: 'disclosure_table', label: 'Disclosure statement (table)', group: 'Blocks' },
  { key: 'signature_block', label: 'Signature section', group: 'Blocks' },
]

export type ScheduleRow = {
  installment_no: number; due_date: string; opening_balance: number | string; principal: number | string
  interest: number | string; charges: number | string; total: number | string; closing_balance: number | string
}

/** Shape returned by public.lf_agreement_render_data / agreement_begin */
export type RenderData = {
  agreement: { id: string; agreement_number: string; version: number; kind: string; generated_at: string; required_signers: string[]; signing_order: string }
  organization: Record<string, any>
  borrower: Record<string, any>
  loan: Record<string, any>
  summary: Record<string, any>
  schedule: ScheduleRow[]
  template: { title: string; body: string; settings: Record<string, any>; version_no?: number }
  officer: { full_name: string | null }
  settings?: Record<string, any>
  signatures?: any[]
}

export function orgAddress(o: Record<string, any>): string {
  return [o.address, o.city, o.province, o.zip_code].filter((x) => x && String(x).trim()).join(', ')
}

export function interestRateText(s: Record<string, any>): string {
  const rate = trimNumber(s.interest_rate, 4)
  const period = PERIOD_LABEL[s.rate_period] ?? s.rate_period
  const annual = toNumber(s.annual_nominal_rate)
  return s.rate_period === 'yearly'
    ? `${rate}% per annum`
    : `${rate}% per ${period} (${trimNumber(annual, 2)}% per annum, nominal)`
}

export function effectiveRateText(s: Record<string, any>): string {
  if (s.eir_monthly_pct === null || s.eir_monthly_pct === undefined) return 'Not applicable'
  return `${trimNumber(s.eir_monthly_pct, 2)}% per month (${trimNumber(s.effective_annual_rate_pct, 2)}% effective per annum)`
}

export function buildPlaceholderValues(d: RenderData): Record<string, string> {
  const s = d.summary
  const o = d.organization
  const b = d.borrower
  return {
    agreement_number: d.agreement.agreement_number,
    agreement_date: longDate(d.agreement.generated_at),
    loan_number: d.loan.loan_number,
    lender_name: o.legal_name || o.name,
    lender_address: orgAddress(o) || 'the Lender\'s principal office',
    lender_city: o.city || 'the city where the Lender\'s principal office is located',
    lender_registration_no: o.registration_no || '—',
    lender_representative: d.officer?.full_name || '',
    borrower_name: b.full_name,
    borrower_address: b.address_full || '—',
    borrower_id_type: b.id_type || 'a valid government-issued ID',
    borrower_id_number: b.id_number || '—',
    borrower_mobile: b.mobile_number || '—',
    borrower_email: b.email || '—',
    loan_amount: money(s.principal),
    loan_amount_words: amountInWords(s.principal).toUpperCase(),
    interest_rate: interestRateText(s),
    interest_method: (METHOD_LABEL[s.interest_method] ?? s.interest_method).toLowerCase(),
    effective_interest_rate: effectiveRateText(s),
    loan_duration: durationLabel(toNumber(s.installment_count), s.repayment_frequency),
    payment_frequency: FREQUENCY_LABEL[s.repayment_frequency] ?? s.repayment_frequency,
    installment_count: String(s.installment_count),
    installment_amount: money(s.installment_amount),
    total_interest: money(s.total_interest),
    processing_fee: money(s.processing_fee),
    total_charges: money(s.total_charges),
    net_proceeds: money(s.net_proceeds),
    total_repayment: money(s.total_repayment),
    release_date: longDate(s.release_date),
    first_payment_date: longDate(s.first_due_date),
    last_payment_date: longDate(s.last_due_date),
    penalty_terms: penaltyTerms(d.loan),
    allocation_order: allocationOrderText(d.loan.allocation_order),
  }
}

/** Disclosure statement rows (label, value) — Truth in Lending style. */
export function disclosureRows(d: RenderData): [string, string][] {
  const s = d.summary
  const deducted = s.charges_treatment === 'deducted'
  return [
    ['Principal amount of the loan', money(s.principal)],
    [`Fees and charges (${deducted ? 'deducted from proceeds' : 'added to installments'})`, money(s.total_charges)],
    ['Net proceeds to the Borrower', money(s.net_proceeds)],
    ['Total interest', money(s.total_interest)],
    ['Finance charge (interest + fees and charges)', money(s.finance_charge)],
    ['Total amount payable', money(s.total_repayment)],
    ['Nominal interest rate', interestRateText(s)],
    ['Interest computation method', METHOD_LABEL[s.interest_method] ?? s.interest_method],
    ['Effective interest rate (EIR)', effectiveRateText(s)],
    ['Number and frequency of installments', `${s.installment_count} ${FREQUENCY_LABEL[s.repayment_frequency] ?? ''} installments`],
    ['Regular installment amount', money(s.installment_amount) + (toNumber(s.last_installment_amount) !== toNumber(s.installment_amount) ? ` (final: ${money(s.last_installment_amount)})` : '')],
    ['First / last due date', `${shortDate(s.first_due_date)} / ${shortDate(s.last_due_date)}`],
    ['Late payment charges', penaltyTerms(d.loan)],
  ]
}

/** Replace inline placeholders. Block placeholders are preserved for the layout stage. */
export function fillPlaceholders(body: string, values: Record<string, string>): { text: string; missing: string[] } {
  const missing = new Set<string>()
  const text = body.replace(/\{\{\s*([a-z0-9_]+)\s*\}\}/gi, (m, key: string) => {
    const k = key.toLowerCase()
    if ((BLOCK_PLACEHOLDERS as readonly string[]).includes(k)) return `{{${k}}}`
    if (k in values) return values[k]
    missing.add(k)
    return m
  })
  return { text, missing: [...missing] }
}

export function parseInline(text: string): Run[] {
  const runs: Run[] = []
  const parts = text.split('**')
  parts.forEach((p, i) => {
    if (p) runs.push({ text: p, bold: i % 2 === 1 })
  })
  return runs.length ? runs : [{ text: '', bold: false }]
}

export function parseTemplate(text: string): Block[] {
  const blocks: Block[] = []
  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  let para: string[] = []
  let list: { ordered: boolean; items: Run[][] } | null = null

  const flushPara = () => {
    if (para.length) {
      blocks.push({ type: 'paragraph', runs: parseInline(para.join(' ').replace(/\s+/g, ' ').trim()) })
      para = []
    }
  }
  const flushList = () => {
    if (list) {
      blocks.push({ type: 'list', ordered: list.ordered, items: list.items })
      list = null
    }
  }

  for (const raw of lines) {
    const line = raw.trim()
    if (!line) {
      flushPara()
      flushList()
      continue
    }
    const block = line.match(/^\{\{\s*(repayment_schedule|disclosure_table|signature_block)\s*\}\}$/i)
    if (block) {
      flushPara(); flushList()
      const k = block[1].toLowerCase()
      blocks.push({ type: k === 'repayment_schedule' ? 'schedule' : k === 'disclosure_table' ? 'disclosure' : 'signatures' })
      continue
    }
    const h = line.match(/^(#{1,3})\s+(.*)$/)
    if (h) {
      flushPara(); flushList()
      blocks.push({ type: 'heading', level: h[1].length as 1 | 2 | 3, runs: parseInline(h[2]) })
      continue
    }
    if (/^(-{3,}|\*{3,}|_{3,})$/.test(line)) {
      flushPara(); flushList()
      blocks.push({ type: 'rule' })
      continue
    }
    const ol = line.match(/^(\d+)[.)]\s+(.*)$/)
    const ul = line.match(/^[-*•]\s+(.*)$/)
    if (ol || ul) {
      flushPara()
      const ordered = !!ol
      if (!list || list.ordered !== ordered) {
        flushList()
        list = { ordered, items: [] }
      }
      list.items.push(parseInline((ol ? ol[2] : ul![1]).trim()))
      continue
    }
    if (list) flushList()
    para.push(line)
  }
  flushPara()
  flushList()
  if (!blocks.some((b) => b.type === 'signatures')) blocks.push({ type: 'signatures' })
  return blocks
}

/** Sample data for template previews in the builder. */
export function sampleRenderData(org?: Record<string, any>): RenderData {
  const schedule: ScheduleRow[] = Array.from({ length: 10 }, (_, i) => ({
    installment_no: i + 1,
    due_date: `2026-${String(i + 2).padStart(2, '0')}-15`,
    opening_balance: 50000 - i * 5000,
    principal: 5000,
    interest: 1000,
    charges: 0,
    total: 6000,
    closing_balance: 45000 - i * 5000,
  }))
  return {
    agreement: { id: 'sample', agreement_number: 'LA-2026-000123', version: 1, kind: 'original', generated_at: new Date().toISOString(), required_signers: ['borrower', 'lender'], signing_order: 'borrower_first' },
    organization: org ?? { name: 'Your Lending Company', address: '123 Rizal Avenue', city: 'Quezon City', province: 'Metro Manila', zip_code: '1100' },
    borrower: { full_name: 'Juan Santos Dela Cruz', address_full: '45 Mabini St., Brgy. San Roque, Marikina City, Metro Manila 1801', id_type: 'PhilSys National ID', id_number: '1234-5678-9012-3456', mobile_number: '+639171234567', email: 'juan@example.com' },
    loan: { loan_number: 'LN-2026-000123', penalty_enabled: true, penalty_type: 'percent_monthly', penalty_rate: 3, penalty_grace_days: 3, allocation_order: ['penalty', 'charges', 'interest', 'principal'] },
    summary: {
      principal: 50000, interest_method: 'flat', interest_rate: 2, rate_period: 'monthly', annual_nominal_rate: 24,
      repayment_frequency: 'monthly', installment_count: 10, installment_amount: 6000, last_installment_amount: 6000,
      total_interest: 10000, processing_fee: 0, total_charges: 0, charges_treatment: 'deducted', net_proceeds: 50000,
      total_repayment: 60000, finance_charge: 10000, first_due_date: '2026-02-15', last_due_date: '2026-11-15', release_date: '2026-01-15',
      eir_monthly_pct: 3.46, effective_annual_rate_pct: 50.54,
    },
    schedule,
    template: { title: 'Loan Agreement', body: '', settings: {} },
    officer: { full_name: 'Maria Santos' },
  }
}
