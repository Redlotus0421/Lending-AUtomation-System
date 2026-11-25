// Status and payment details of each scheduled installment for the Payment Records page.
import type { Tone } from '@/lib/constants'
import { toNumber } from '@/lib/format'
import type { InstallmentView } from '@/types/db'
import type { RecordAllocation } from '@/services/records'

export type RecordStatus = 'paid_on_time' | 'paid_late' | 'overdue' | 'partially_paid' | 'upcoming' | 'rescheduled' | 'waived'

export const RECORD_STATUS: Record<RecordStatus, { label: string; tone: Tone }> = {
  paid_on_time: { label: 'Paid on time', tone: 'green' },
  paid_late: { label: 'Paid late', tone: 'amber' },
  overdue: { label: 'Overdue', tone: 'red' },
  partially_paid: { label: 'Partially paid', tone: 'blue' },
  upcoming: { label: 'Upcoming', tone: 'slate' },
  rescheduled: { label: 'Rescheduled', tone: 'violet' },
  waived: { label: 'Waived', tone: 'teal' },
}

export type InstallmentPayment = { payment_id: string; payment_number: string; payment_date: string; amount: number }

export type RecordRow = {
  inst: InstallmentView
  status: RecordStatus
  /** days paid after the due date (paid late) or days past due (overdue) */
  days: number
  scheduled: number
  paid: number
  remaining: number
  /** date the installment was fully paid, else the latest partial payment */
  paidDate: string | null
  payments: InstallmentPayment[]
  rebate: number
}

const daysBetween = (from: string, to: string) =>
  Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000)

/**
 * On time means fully paid on or before the due date (penalty grace days do not count as on time).
 * An unpaid or partly paid installment past its due date is overdue, even if something was paid.
 */
export function recordStatus(i: Pick<InstallmentView, 'status' | 'due_date' | 'paid_at'>, today: string, lastPaid: string | null): { status: RecordStatus; days: number } {
  if (i.status === 'rescheduled') return { status: 'rescheduled', days: 0 }
  if (i.status === 'waived') return { status: 'waived', days: 0 }
  if (i.status === 'paid') {
    const paid = i.paid_at ?? lastPaid ?? i.due_date
    const late = daysBetween(i.due_date, paid)
    return late > 0 ? { status: 'paid_late', days: late } : { status: 'paid_on_time', days: 0 }
  }
  if (i.due_date < today) return { status: 'overdue', days: daysBetween(i.due_date, today) }
  if (i.status === 'partially_paid') return { status: 'partially_paid', days: 0 }
  return { status: 'upcoming', days: 0 }
}

/** One row per scheduled installment, with the posted payments applied to it (reversed payments excluded). */
export function buildRecordRows(installments: InstallmentView[], allocations: RecordAllocation[], today: string): RecordRow[] {
  const byInst = new Map<string, InstallmentPayment[]>()
  const rebates = new Map<string, number>()
  for (const a of allocations) {
    if (a.is_reversal || a.loan_payments?.status !== 'posted') continue
    rebates.set(a.installment_id, (rebates.get(a.installment_id) ?? 0) + toNumber(a.interest_rebate))
    const amount = toNumber(a.total_amount)
    if (amount <= 0) continue
    const list = byInst.get(a.installment_id) ?? []
    const same = list.find((p) => p.payment_id === a.payment_id)
    if (same) same.amount += amount
    else list.push({ payment_id: a.payment_id, payment_number: a.loan_payments.payment_number, payment_date: a.loan_payments.payment_date, amount })
    byInst.set(a.installment_id, list)
  }
  return installments.map((inst) => {
    const payments = (byInst.get(inst.id) ?? []).sort((x, y) => x.payment_date.localeCompare(y.payment_date))
    const lastPaid = payments.length ? payments[payments.length - 1].payment_date : null
    const { status, days } = recordStatus(inst, today, lastPaid)
    return {
      inst, status, days, payments,
      scheduled: toNumber(inst.scheduled_amount),
      paid: toNumber(inst.amount_paid),
      remaining: inst.status === 'rescheduled' ? 0 : Math.max(toNumber(inst.amount_remaining), 0),
      paidDate: inst.status === 'paid' ? (inst.paid_at ?? lastPaid) : lastPaid,
      rebate: rebates.get(inst.id) ?? 0,
    }
  })
}
