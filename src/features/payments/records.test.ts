import { expect, it } from 'vitest'
import type { InstallmentView } from '@/types/db'
import type { RecordAllocation } from '@/services/records'
import { buildRecordRows, recordStatus } from './records'

const TODAY = '2026-11-25'
const inst = (o: Partial<InstallmentView>) => ({
  id: 'i1', installment_no: 1, due_date: '2026-10-15', status: 'pending', paid_at: null,
  scheduled_amount: 2600, amount_paid: 0, amount_remaining: 2600, ...o,
}) as InstallmentView
const alloc = (o: Partial<RecordAllocation> & { date: string; pstatus?: string; no?: string }): RecordAllocation => ({
  installment_id: 'i1', payment_id: o.no ?? 'p1', total_amount: 2600, interest_rebate: 0, is_reversal: false,
  loan_payments: { payment_number: o.no ?? 'PAY-1', payment_date: o.date, status: o.pstatus ?? 'posted' }, ...o,
})

it('classifies each scheduled installment', () => {
  expect(recordStatus(inst({ status: 'paid', paid_at: '2026-10-15' }), TODAY, null)).toEqual({ status: 'paid_on_time', days: 0 })
  expect(recordStatus(inst({ status: 'paid', paid_at: '2026-10-01' }), TODAY, null)).toEqual({ status: 'paid_on_time', days: 0 })
  expect(recordStatus(inst({ status: 'paid', paid_at: '2026-10-20' }), TODAY, null)).toEqual({ status: 'paid_late', days: 5 })
  expect(recordStatus(inst({ status: 'partially_paid' }), TODAY, '2026-10-10')).toEqual({ status: 'overdue', days: 41 })
  expect(recordStatus(inst({ status: 'pending' }), TODAY, null)).toEqual({ status: 'overdue', days: 41 })
  expect(recordStatus(inst({ status: 'partially_paid', due_date: '2026-12-15' }), TODAY, '2026-11-20')).toEqual({ status: 'partially_paid', days: 0 })
  expect(recordStatus(inst({ status: 'pending', due_date: TODAY }), TODAY, null)).toEqual({ status: 'upcoming', days: 0 })
  expect(recordStatus(inst({ status: 'rescheduled' }), TODAY, null).status).toBe('rescheduled')
})

it('uses only posted payments and adds up split payments', () => {
  const rows = buildRecordRows(
    [inst({ status: 'paid', paid_at: '2026-10-18', amount_paid: 2600, amount_remaining: 0 })],
    [
      alloc({ no: 'PAY-1', date: '2026-10-10', total_amount: 1000 }),
      alloc({ no: 'PAY-2', date: '2026-10-18', total_amount: 1600 }),
      alloc({ no: 'PAY-3', date: '2026-10-12', total_amount: 500, pstatus: 'reversed' }),
      alloc({ no: 'PAY-3', date: '2026-10-12', total_amount: -500, is_reversal: true }),
    ],
    TODAY,
  )
  expect(rows[0].payments.map((p) => [p.payment_number, p.amount])).toEqual([['PAY-1', 1000], ['PAY-2', 1600]])
  expect(rows[0].paidDate).toBe('2026-10-18')
  expect(rows[0].status).toBe('paid_late')
  expect(rows[0].days).toBe(3)
})
