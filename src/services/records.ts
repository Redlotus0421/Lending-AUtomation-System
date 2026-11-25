// Payment Records: a borrower's repayment schedule next to what was actually paid.
// Read-only and built entirely from existing transactions (loan_installments, payment_allocations,
// loan_payments), so it stays in step with Payments & Collections and nothing is entered twice.
import { supabase } from '@/lib/supabase'
import type { Borrower, InstallmentView, LoanView, Payment } from '@/types/db'
import { must } from './api'

/** Loans that have a repayment schedule (released at some point). */
export const RECORD_LOAN_STATUSES = ['active', 'defaulted', 'completed'] as const

export type RecordAllocation = {
  installment_id: string
  payment_id: string
  total_amount: number | string
  interest_rebate: number | string
  is_reversal: boolean
  loan_payments: { payment_number: string; payment_date: string; status: string } | null
}

export type RecordPayment = Payment & {
  payment_receipts: { receipt_number: string; is_void: boolean }[] | { receipt_number: string; is_void: boolean } | null
  payment_allocations: { is_reversal: boolean; loan_installments: { installment_no: number } | null }[]
}

export const recordsService = {
  /** Borrowers of any status (an archived borrower still has records). Empty term lists the first ones by name. */
  async searchBorrowers(term: string, limit = 10): Promise<Borrower[]> {
    const s = term.trim().replace(/[%,()]/g, ' ')
    let q = supabase.from('borrowers').select('*').order('full_name').limit(limit)
    if (s) {
      const digits = s.replace(/\D/g, '')
      const parts = [`full_name.ilike.%${s}%`, `borrower_code.ilike.%${s}%`]
      if (digits.length >= 4) parts.push(`mobile_number.ilike.%${digits.replace(/^0/, '')}%`)
      q = q.or(parts.join(','))
    }
    return must(await q) as Borrower[]
  },
  async borrower(id: string): Promise<Borrower> {
    return must(await supabase.from('borrowers').select('*').eq('id', id).single()) as Borrower
  },
  /** The borrower's released loans, newest first. */
  async loans(borrowerId: string): Promise<LoanView[]> {
    return must(await supabase.from('v_loans').select('*').eq('borrower_id', borrowerId)
      .in('status', [...RECORD_LOAN_STATUSES]).order('created_at', { ascending: false })) as LoanView[]
  },
  async installments(loanId: string): Promise<InstallmentView[]> {
    return must(await supabase.from('v_installments').select('*').eq('loan_id', loanId).order('installment_no')) as InstallmentView[]
  },
  async allocations(loanId: string): Promise<RecordAllocation[]> {
    return must(await supabase.from('payment_allocations')
      .select('installment_id, payment_id, total_amount, interest_rebate, is_reversal, loan_payments(payment_number, payment_date, status)')
      .eq('loan_id', loanId).order('created_at')) as unknown as RecordAllocation[]
  },
  async payments(loanId: string): Promise<RecordPayment[]> {
    return must(await supabase.from('loan_payments')
      .select('*, payment_receipts(receipt_number, is_void), payment_allocations(is_reversal, loan_installments(installment_no))')
      .eq('loan_id', loanId).order('payment_date', { ascending: false }).order('created_at', { ascending: false })) as RecordPayment[]
  },
}
