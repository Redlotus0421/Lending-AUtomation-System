import { expect, it } from 'vitest'
import { buildReceiptModel, columnsFor, lr, receiptLines, receiptStatus } from './model'
import { escposBytes } from './print'

const DATA = {
  receipt_number: 'OR-2026-000010', payment_number: 'PAY-2026-000010', payment_date: '2026-11-25',
  recorded_at: '2026-11-25T05:30:00Z', amount: 2500, atm_withdrawal: 7500, cash_returned: 5000, method: 'cash',
  installments: [{ installment_no: 3, due_date: '2026-11-30', status: 'paid' }],
  remaining_balance: 7500, loan_number: 'LN-2026-000002', borrower_name: 'Juan Dela Cruz Ñoño', borrower_code: 'BRW-000002',
  collector_name: 'Maria Collector', company: { name: 'Pautang ni Cris', phone: '+639305992419' }, footer_note: 'Keep this receipt.',
}

it('computes the cash returned from the withdrawal and the payment', () => {
  const m = buildReceiptModel(DATA)
  expect(m.atm).toEqual({ withdrawal: 7500, payment: 2500, cashReturned: 5000 })
  expect(m.time).toBe('1:30 PM')
  expect(m.dueDates).toEqual([{ no: 3, date: '2026-11-30' }])
  expect(buildReceiptModel({ ...DATA, atm_withdrawal: null }).atm).toBeNull()
})

it('describes the payment status', () => {
  expect(receiptStatus(DATA, false)).toBe('Paid - installment #3')
  expect(receiptStatus({ ...DATA, installments: [{ installment_no: 3, due_date: 'x', status: 'partially_paid' }] }, false)).toBe('Partial payment - installment #3')
  expect(receiptStatus({ ...DATA, remaining_balance: 0 }, false)).toBe('Loan fully paid')
  expect(receiptStatus(DATA, true)).toBe('VOID - payment reversed')
  expect(receiptStatus({ ...DATA, installments: [{ installment_no: 1, due_date: 'x' }] }, false)).toBe('Posted')
})

it('fits every line on 58mm and 80mm rolls, in plain ASCII', () => {
  for (const paper of [58, 80] as const) {
    const lines = receiptLines(buildReceiptModel(DATA, { reprint: true }), paper)
    const W = columnsFor(paper)
    for (const l of lines) {
      expect(l.text.length).toBeLessThanOrEqual(l.big ? W / 2 : W)
      expect(l.text).toMatch(/^[\x20-\x7E]*$/)
    }
    const text = lines.map((l) => l.text).join('\n')
    expect(text).toContain('ATM Withdrawal')
    expect(text).toMatch(/Cash Returned\s+P5,000\.00/)
    expect(text).toContain('REPRINT')
    expect(text).toContain('Juan Dela Cruz Nono')
  }
  expect(lr('Borrower', 'A very long borrower name that will not fit', 32).every((x) => x.length <= 32)).toBe(true)
})

it('produces ESC/POS bytes that start with init and end with a cut', () => {
  const b = escposBytes(buildReceiptModel(DATA), 58)
  expect([...b.slice(0, 2)]).toEqual([0x1b, 0x40])
  expect([...b.slice(-4)]).toEqual([0x1d, 0x56, 0x42, 0x00])
})
