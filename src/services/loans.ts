import { supabase } from '@/lib/supabase'
import type {
  InstallmentView, LedgerEntry, Loan, LoanCalculation, LoanNote, LoanProduct, LoanStatus, LoanView, Payment,
  SettlementQuote,
} from '@/types/db'
import { must, rpc } from './api'

export type LoanInput = {
  borrower_id?: string
  product_id?: string | null
  principal: number
  interest_method: 'flat' | 'diminishing'
  interest_rate: number
  rate_period: string
  repayment_frequency: string
  term_count: number
  release_date: string
  first_due_date?: string | null
  processing_fee_type: 'none' | 'fixed' | 'percent'
  processing_fee_value: number
  other_charges: number
  other_charges_description?: string | null
  charges_treatment: 'added' | 'deducted'
  day_count?: string
  rounding?: string
  penalty_enabled: boolean
  penalty_type?: string | null
  penalty_rate?: number
  penalty_grace_days?: number
  purpose?: string | null
  notes?: string | null
  collector_id?: string | null
  agreement_template_id?: string | null
  signing_order?: string
  witness_count?: number
  source?: string
  submit?: boolean
}

export const LOAN_PIPELINE: LoanStatus[] = [
  'draft', 'pending_review', 'approved', 'awaiting_borrower_signature', 'awaiting_lender_signature', 'fully_signed', 'ready_for_release',
]

export const loansService = {
  preview: (p: Partial<LoanInput>) => rpc<LoanCalculation>('preview_loan', { p }),
  create: (p: LoanInput) => rpc<{ id: string; loan_number: string; status: LoanStatus }>('create_loan', { p }),
  update: (id: string, p: Partial<LoanInput>) => rpc<{ id: string; loan_number: string; status: LoanStatus }>('update_loan', { p_loan_id: id, p }),
  submit: (id: string) => rpc('submit_loan', { p_loan_id: id }),
  approve: (id: string, notes?: string) => rpc<{ status: LoanStatus }>('approve_loan', { p_loan_id: id, p_notes: notes || null }),
  reject: (id: string, reason: string) => rpc('reject_loan', { p_loan_id: id, p_reason: reason }),
  cancel: (id: string, reason: string) => rpc('cancel_loan', { p_loan_id: id, p_reason: reason }),
  release: (id: string, p: { release_date: string; method: string; reference?: string }) => rpc<{ status: string; installments: number; renewal_deduction: number | string; net_release: number | string; settled_loans: number }>('release_loan', { p_loan_id: id, p }),
  markDefault: (id: string, reason: string) => rpc('mark_loan_default', { p_loan_id: id, p_reason: reason }),
  reinstate: (id: string, reason: string) => rpc('reinstate_loan', { p_loan_id: id, p_reason: reason }),
  close: (id: string) => rpc('close_loan', { p_loan_id: id }),
  assignStaff: (id: string, officer: string | null, collector: string | null) =>
    rpc('assign_loan_staff', { p_loan_id: id, p_loan_officer: officer, p_collector: collector }),
  settlementQuote: (id: string, asOf?: string) => rpc<SettlementQuote>('get_settlement_quote', { p_loan_id: id, p_as_of: asOf || null }),
  reschedule: (id: string, p: Record<string, unknown>) => rpc<{ version: number; summary: LoanCalculation['summary'] }>('reschedule_loan', { p_loan_id: id, p }),
  adjust: (p: { loan_id: string; installment_id: string; type: string; amount: number; reason: string }) => rpc('apply_loan_adjustment', { p }),

  async list(f: { statuses?: LoanStatus[]; search?: string; collectorId?: string; overdueOnly?: boolean; page?: number; pageSize?: number; order?: string } = {}) {
    const pageSize = f.pageSize ?? 25
    let q = supabase.from('v_loans').select('*', { count: 'exact' })
    if (f.statuses?.length) q = q.in('status', f.statuses)
    if (f.collectorId) q = q.eq('collector_id', f.collectorId)
    if (f.overdueOnly) q = q.gt('overdue_count', 0)
    if (f.search?.trim()) {
      const s = f.search.trim().replace(/[%,()]/g, ' ')
      q = q.or(`loan_number.ilike.%${s}%,borrower_name.ilike.%${s}%,borrower_code.ilike.%${s}%`)
    }
    if (f.order === 'overdue') q = q.order('days_overdue', { ascending: false })
    else if (f.order === 'next_due') q = q.order('next_due_date', { ascending: true, nullsFirst: false })
    else q = q.order('created_at', { ascending: false })
    const page = f.page ?? 0
    const res = await q.range(page * pageSize, page * pageSize + pageSize - 1)
    return { rows: must(res) as LoanView[], count: res.count ?? 0 }
  },

  async get(id: string): Promise<LoanView> {
    return must(await supabase.from('v_loans').select('*').eq('id', id).single()) as LoanView
  },
  async raw(id: string): Promise<Loan> {
    return must(await supabase.from('loans').select('*').eq('id', id).single()) as Loan
  },
  async installments(loanId: string): Promise<InstallmentView[]> {
    return must(await supabase.from('v_installments').select('*').eq('loan_id', loanId).order('installment_no')) as InstallmentView[]
  },
  async payments(loanId: string): Promise<Payment[]> {
    return must(await supabase.from('loan_payments').select('*').eq('loan_id', loanId)
      .order('payment_date', { ascending: false }).order('created_at', { ascending: false })) as Payment[]
  },
  async ledger(loanId: string): Promise<LedgerEntry[]> {
    return must(await supabase.from('financial_ledger').select('*').eq('loan_id', loanId).order('entry_no')) as LedgerEntry[]
  },
  async notes(loanId: string): Promise<LoanNote[]> {
    return must(await supabase.from('loan_notes').select('*, author:user_profiles(full_name)').eq('loan_id', loanId)
      .order('created_at', { ascending: false })) as LoanNote[]
  },
  async addNote(p: { loan_id?: string | null; borrower_id?: string | null; body: string; note_type: string; promise_date?: string | null; promise_amount?: number | null }) {
    const { data: u } = await supabase.auth.getUser()
    return must(await supabase.from('loan_notes').insert({ ...p, created_by: u.user?.id }).select().single()) as LoanNote
  },
  async snapshots(loanId: string) {
    return must(await supabase.from('loan_terms_snapshots').select('id, version, kind, terms_hash, reason, created_at, summary').eq('loan_id', loanId)
      .order('version', { ascending: false })) as { id: string; version: number; kind: string; terms_hash: string; reason: string | null; created_at: string; summary: LoanCalculation['summary'] }[]
  },
  async snapshotSchedule(snapshotId: string) {
    return must(await supabase.from('loan_terms_snapshots').select('schedule, summary').eq('id', snapshotId).single()) as Pick<LoanCalculation, 'schedule' | 'summary'>
  },
  async auditHistory(loanId: string) {
    return must(await supabase.from('audit_logs').select('*').eq('table_name', 'loans').eq('record_id', loanId)
      .order('created_at', { ascending: false }).limit(100)) as import('@/types/db').AuditLog[]
  },
  async application(loanId: string) {
    return must(await supabase.from('loan_applications').select('*').eq('loan_id', loanId).maybeSingle()) as Record<string, any> | null
  },
}

export const productsService = {
  async list(activeOnly = false): Promise<LoanProduct[]> {
    let q = supabase.from('loan_products').select('*').order('name')
    if (activeOnly) q = q.eq('is_active', true)
    return must(await q) as LoanProduct[]
  },
  async save(p: Partial<LoanProduct>): Promise<LoanProduct> {
    const { id, ...rest } = p
    if (id) return must(await supabase.from('loan_products').update(rest).eq('id', id).select().single()) as LoanProduct
    return must(await supabase.from('loan_products').insert(rest).select().single()) as LoanProduct
  },
  setActive: (id: string, active: boolean) => productsService.save({ id, is_active: active }),
}
