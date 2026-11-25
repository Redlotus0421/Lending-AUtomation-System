// Thermal receipt content, built from the frozen receipt snapshot (payment_receipts.data).
// Printing or reprinting only reads it: no payment or receipt is ever created here.
import { fmtDate, peso, toNumber } from '@/lib/format'

export type PaperWidth = 58 | 80

export type ReceiptModel = {
  company: { name: string; address: string | null; phone: string | null; tin: string | null }
  receiptNo: string
  paymentNo: string
  /** payment date (YYYY-MM-DD) and the time it was recorded */
  date: string
  time: string | null
  borrower: string
  borrowerCode: string | null
  loanNo: string
  dueDates: { no: number; date: string }[]
  method: string
  reference: string | null
  amount: number
  atm: { withdrawal: number; payment: number; cashReturned: number } | null
  remaining: number
  status: string
  receivedBy: string | null
  footer: string | null
  isVoid: boolean
  /** set when the receipt is printed again later */
  reprintedAt: string | null
}

const METHOD: Record<string, string> = { cash: 'Cash', bank_transfer: 'Bank transfer', e_wallet: 'E-wallet', check: 'Check', other: 'Other', renewal: 'Renewal offset' }

const timeManila = (iso: string) => new Date(iso).toLocaleTimeString('en-PH', { timeZone: 'Asia/Manila', hour: 'numeric', minute: '2-digit' })
const dateTimeManila = (iso: string) => new Date(iso).toLocaleString('en-PH', { timeZone: 'Asia/Manila', month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit' })

type Inst = { installment_no: number; due_date: string; status?: string }

/** Payment status line: void, loan paid off, installment(s) paid, or a partial payment. */
export function receiptStatus(d: Record<string, any>, isVoid: boolean): string {
  if (isVoid) return 'VOID - payment reversed'
  if (d.payment_type === 'settlement') return 'Early settlement - loan paid'
  if (toNumber(d.remaining_balance) <= 0) return 'Loan fully paid'
  const inst = (d.installments ?? []) as Inst[]
  if (!inst.length) return 'Posted'
  const nos = (list: Inst[]) => list.map((i) => `#${i.installment_no}`).join(', ')
  const partial = inst.filter((i) => i.status === 'partially_paid')
  if (partial.length) return `Partial payment - installment ${nos(partial)}`
  if (inst.every((i) => i.status === 'paid')) return `Paid - installment${inst.length > 1 ? 's' : ''} ${nos(inst)}`
  return 'Posted'
}

export function buildReceiptModel(d: Record<string, any>, opts: { isVoid?: boolean; reprint?: boolean } = {}): ReceiptModel {
  const c = d.company ?? {}
  const amount = toNumber(d.amount)
  const atmW = d.atm_withdrawal == null ? null : toNumber(d.atm_withdrawal)
  const recordedAt = d.recorded_at ?? d.issued_at ?? null
  return {
    company: { name: c.legal_name || c.name || '', address: c.address || null, phone: c.phone || null, tin: c.tin || null },
    receiptNo: d.receipt_number,
    paymentNo: d.payment_number,
    date: d.payment_date,
    time: recordedAt ? timeManila(recordedAt) : null,
    borrower: d.borrower_name,
    borrowerCode: d.borrower_code ?? null,
    loanNo: d.loan_number,
    dueDates: ((d.installments ?? []) as Inst[]).map((i) => ({ no: i.installment_no, date: i.due_date })),
    method: METHOD[d.method] ?? String(d.method ?? ''),
    reference: d.reference_no || null,
    amount,
    // cash returned is always withdrawal - payment, never typed in
    atm: atmW == null ? null : { withdrawal: atmW, payment: amount, cashReturned: Math.round((atmW - amount) * 100) / 100 },
    remaining: toNumber(d.remaining_balance),
    status: receiptStatus(d, !!opts.isVoid),
    receivedBy: d.collector_name || d.recorded_by || null,
    footer: d.footer_note || null,
    isVoid: !!opts.isVoid,
    reprintedAt: opts.reprint ? dateTimeManila(new Date().toISOString()) : null,
  }
}

// ---------------------------------------------------------------------------
// Plain-text layout shared by the Bluetooth (ESC/POS) printout: 32 columns on 58mm, 48 on 80mm.
// ---------------------------------------------------------------------------
export type ReceiptLine = { text: string; align?: 'left' | 'center'; bold?: boolean; big?: boolean }

export const columnsFor = (w: PaperWidth) => (w === 58 ? 32 : 48)

/** ASCII only: thermal printers' default code page has no peso sign or curly punctuation. */
export const ascii = (s: string) => s
  .replace(/₱\s?/g, 'P').replace(/[–—]/g, '-').replace(/[“”]/g, '"').replace(/[‘’]/g, "'").replace(/…/g, '...')
  .replace(/ñ/g, 'n').replace(/Ñ/g, 'N').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^\x20-\x7E]/g, '')

const money = (n: number) => peso(n).replace(/₱\s?/, 'P')

function wrap(s: string, width: number): string[] {
  const out: string[] = []
  for (const para of ascii(s).split('\n')) {
    let line = ''
    for (const word of para.split(/\s+/).filter(Boolean)) {
      if (!line) line = word
      else if (line.length + 1 + word.length <= width) line += ` ${word}`
      else { out.push(line); line = word }
      while (line.length > width) { out.push(line.slice(0, width)); line = line.slice(width) }
    }
    out.push(line)
  }
  return out
}

/** Label on the left, value on the right; wraps the label when both do not fit. */
export function lr(label: string, value: string, width: number): string[] {
  const l = ascii(label), v = ascii(value)
  if (l.length + 1 + v.length <= width) return [l + ' '.repeat(width - l.length - v.length) + v]
  return [...wrap(l, width), ' '.repeat(Math.max(width - v.length, 0)) + v.slice(-width)]
}

export function receiptLines(m: ReceiptModel, paper: PaperWidth): ReceiptLine[] {
  const W = columnsFor(paper)
  const rule = '-'.repeat(W)
  const out: ReceiptLine[] = []
  const center = (t: string, o: Partial<ReceiptLine> = {}) => wrap(t, o.big ? Math.floor(W / 2) : W).forEach((x) => out.push({ text: x, align: 'center', ...o }))
  const row = (l: string, v: string, bold = false) => lr(l, v, W).forEach((x) => out.push({ text: x, bold }))

  if (m.company.name) center(m.company.name, { bold: true })
  if (m.company.address) center(m.company.address)
  if (m.company.phone) center(m.company.phone)
  if (m.company.tin) center(`TIN ${m.company.tin}`)
  out.push({ text: rule })
  center('PAYMENT RECEIPT', { bold: true })
  if (m.isVoid) center('*** VOID ***', { bold: true, big: true })
  if (m.reprintedAt) center(`REPRINT ${m.reprintedAt}`)
  out.push({ text: rule })
  row('Receipt No.', m.receiptNo, true)
  row('Payment No.', m.paymentNo)
  row('Date', `${fmtDate(m.date)}${m.time ? ` ${m.time}` : ''}`)
  row('Borrower', m.borrower, true)
  if (m.borrowerCode) row('Borrower code', m.borrowerCode)
  row('Loan', m.loanNo)
  for (const d of m.dueDates) row(`Due date #${d.no}`, fmtDate(d.date))
  row('Method', m.method)
  if (m.reference) row('Reference', m.reference)
  out.push({ text: rule })
  if (m.atm) {
    row('ATM Withdrawal', money(m.atm.withdrawal))
    row('Loan Payment', money(m.atm.payment))
    out.push({ text: rule })
    row('Cash Returned', money(m.atm.cashReturned), true)
  } else {
    row('Amount Paid', money(m.amount), true)
  }
  out.push({ text: rule })
  row('Remaining Balance', money(m.remaining), true)
  row('Status', m.status)
  if (m.receivedBy) row('Received by', m.receivedBy)
  if (m.footer) {
    out.push({ text: '' })
    center(m.footer)
  }
  out.push({ text: '' })
  center('Thank you!')
  return out
}
