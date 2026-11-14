import { supabase } from '@/lib/supabase'
import type { InstallmentView, LoanView, Payment, PaymentResult, Receipt, ReportResult } from '@/types/db'
import { invoke, must, rpc, safeFileName, signedUrl, uploadFile } from './api'

export type PaymentInput = {
  loan_id: string
  amount: number
  payment_date: string
  method: string
  method_detail?: string | null
  reference_no?: string | null
  proof_path?: string | null
  notes?: string | null
  payment_type?: 'regular' | 'settlement'
  installment_id?: string | null
  received_by?: string | null
  idempotency_key: string
}

export type PaymentListItem = Payment & {
  loans: { loan_number: string } | null
  borrowers: { full_name: string; borrower_code: string } | null
  payment_receipts: { receipt_number: string; is_void: boolean }[] | { receipt_number: string; is_void: boolean } | null
  receiver: { full_name: string } | null
}

export const paymentsService = {
  record: (p: PaymentInput) => rpc<PaymentResult>('record_payment', { p }),
  reverse: (id: string, reason: string) => rpc<PaymentResult>('reverse_payment', { p_payment_id: id, p_reason: reason }),
  async uploadProof(orgId: string, loanId: string, file: File) {
    const path = `${orgId}/${loanId}/${safeFileName(file.name)}`
    await uploadFile('payment-proofs', path, file)
    return path
  },
  proofUrl: (path: string) => signedUrl('payment-proofs', path, 600),
  receiptPdf: (paymentId: string, download = false) => invoke<{ url: string; receipt_number: string }>('receipt-pdf', { payment_id: paymentId, download }),
  async receipt(paymentId: string): Promise<Receipt | null> {
    return must(await supabase.from('payment_receipts').select('*').eq('payment_id', paymentId).maybeSingle()) as Receipt | null
  },
  async list(f: { from?: string; to?: string; method?: string; status?: string; search?: string; collectorId?: string; page?: number; pageSize?: number } = {}) {
    const pageSize = f.pageSize ?? 25
    let q = supabase.from('loan_payments')
      .select('*, loans(loan_number), borrowers(full_name, borrower_code), payment_receipts(receipt_number, is_void), receiver:user_profiles!loan_payments_received_by_fkey(full_name)', { count: 'exact' })
      .order('payment_date', { ascending: false }).order('created_at', { ascending: false })
    if (f.from) q = q.gte('payment_date', f.from)
    if (f.to) q = q.lte('payment_date', f.to)
    if (f.method && f.method !== 'all') q = q.eq('method', f.method)
    if (f.status && f.status !== 'all') q = q.eq('status', f.status)
    if (f.collectorId) q = q.eq('received_by', f.collectorId)
    if (f.search?.trim()) {
      const s = f.search.trim().replace(/[%,()]/g, ' ')
      q = q.or(`payment_number.ilike.%${s}%,reference_no.ilike.%${s}%`)
    }
    const page = f.page ?? 0
    const res = await q.range(page * pageSize, page * pageSize + pageSize - 1)
    return { rows: must(res) as PaymentListItem[], count: res.count ?? 0 }
  },
  async get(id: string) {
    return must(await supabase.from('loan_payments')
      .select('*, loans(loan_number, status), borrowers(full_name, borrower_code, mobile_number), payment_receipts(*), payment_allocations(*, loan_installments(installment_no, due_date))')
      .eq('id', id).single()) as Record<string, any>
  },
  collectionsSummary: () => rpc<{
    today: string; collected_today: number; collected_week: number; collected_month: number; count_today: number
    due_today: number; overdue: number; receivables: number; upcoming_7d: number
  }>('collections_summary'),

  /** Installments for the Due / Upcoming / Overdue / Partially paid / Completed pages */
  async installments(f: {
    view: 'due_today' | 'upcoming' | 'overdue' | 'partially_paid' | 'paid'
    today: string; days?: number; search?: string; collectorId?: string; from?: string; to?: string
    page?: number; pageSize?: number
  }) {
    const pageSize = f.pageSize ?? 50
    let q = supabase.from('v_installments').select('*', { count: 'exact' }).in('loan_status', ['active', 'defaulted', 'completed'])
    switch (f.view) {
      case 'due_today':
        q = q.eq('due_date', f.today).in('status', ['pending', 'partially_paid']).order('borrower_name')
        break
      case 'upcoming': {
        const end = new Date(`${f.today}T12:00:00Z`)
        end.setUTCDate(end.getUTCDate() + (f.days ?? 14))
        q = q.gt('due_date', f.today).lte('due_date', end.toISOString().slice(0, 10)).in('status', ['pending', 'partially_paid'])
          .order('due_date').order('borrower_name')
        break
      }
      case 'overdue':
        q = q.lt('due_date', f.today).in('status', ['pending', 'partially_paid']).order('due_date')
        break
      case 'partially_paid':
        q = q.eq('status', 'partially_paid').order('due_date')
        break
      case 'paid':
        q = q.eq('status', 'paid').order('paid_at', { ascending: false })
        if (f.from) q = q.gte('paid_at', f.from)
        if (f.to) q = q.lte('paid_at', f.to)
        break
    }
    if (f.collectorId) q = q.eq('collector_id', f.collectorId)
    if (f.search?.trim()) {
      const s = f.search.trim().replace(/[%,()]/g, ' ')
      q = q.or(`loan_number.ilike.%${s}%,borrower_name.ilike.%${s}%,borrower_code.ilike.%${s}%`)
    }
    const page = f.page ?? 0
    const res = await q.range(page * pageSize, page * pageSize + pageSize - 1)
    return { rows: must(res) as InstallmentView[], count: res.count ?? 0 }
  },
  async openInstallments(loanId: string): Promise<InstallmentView[]> {
    return must(await supabase.from('v_installments').select('*').eq('loan_id', loanId).in('status', ['pending', 'partially_paid'])
      .order('installment_no')) as InstallmentView[]
  },
  projectedPenalty: (installmentId: string, asOf: string) => rpc<number>('get_projected_penalty', { p_installment: installmentId, p_as_of: asOf }),

  /** Loans with overdue installments, optionally filtered by ageing bucket (days past the oldest due date). */
  async overdueLoans(f: { search?: string; collectorId?: string; minDays?: number; maxDays?: number; page?: number; pageSize?: number } = {}) {
    const pageSize = f.pageSize ?? 25
    let q = supabase.from('v_loans').select('*', { count: 'exact' })
      .in('status', ['active', 'defaulted']).gt('overdue_count', 0)
      .order('days_overdue', { ascending: false }).order('overdue_amount', { ascending: false })
    if (f.minDays !== undefined) q = q.gte('days_overdue', f.minDays)
    if (f.maxDays !== undefined) q = q.lte('days_overdue', f.maxDays)
    if (f.collectorId === 'none') q = q.is('collector_id', null)
    else if (f.collectorId) q = q.eq('collector_id', f.collectorId)
    if (f.search?.trim()) {
      const s = f.search.trim().replace(/[%,()]/g, ' ')
      q = q.or(`loan_number.ilike.%${s}%,borrower_name.ilike.%${s}%,borrower_code.ilike.%${s}%`)
    }
    const page = f.page ?? 0
    const res = await q.range(page * pageSize, page * pageSize + pageSize - 1)
    return { rows: must(res) as LoanView[], count: res.count ?? 0 }
  },
}

export const reportsService = {
  dashboard: (from?: string, to?: string) => rpc<any>('dashboard_summary', { p_from: from || null, p_to: to || null }),
  get: (report: string, from: string, to: string, filters: Record<string, unknown> = {}) =>
    rpc<ReportResult>('get_report', { p_report: report, p_from: from, p_to: to, p_filters: filters }),
}
