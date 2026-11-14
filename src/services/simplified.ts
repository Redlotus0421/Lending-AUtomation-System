// Simplified Lending API. Rates, fees, outstanding balances and renewal deductions are all
// computed by the database (preview_simplified_loan / create_simplified_loan); the client only
// sends the borrower, the amount, the duration and — if the business allows it — a rate override.
import { supabase } from '@/lib/supabase'
import type { LoanRenewal, LoanStatus, Money, SimplifiedBorrowerStatus, SimplifiedPreview } from '@/types/db'
import { must, rpc } from './api'

export type TransactionType = 'new' | 'renewal'

export type SimplifiedLoanInput = {
  transaction_type: TransactionType
  borrower_id?: string
  principal: number
  term_count: number
  interest_rate?: number | null
  purpose?: string | null
}

export type SimplifiedLoanResult = {
  id: string
  loan_number: string
  status: LoanStatus
  approved: boolean
  transaction_type: TransactionType
  renewal_deduction: Money
  net_release: Money
  net_proceeds: Money
}

export type RenewalWithLoans = LoanRenewal & {
  old_loan: { loan_number: string; status: LoanStatus } | null
  new_loan: { loan_number: string; status: LoanStatus } | null
}

export const simplifiedService = {
  borrowerStatus: (borrowerId: string) =>
    rpc<SimplifiedBorrowerStatus>('simplified_borrower_status', { p_borrower_id: borrowerId }),
  preview: (p: SimplifiedLoanInput) => rpc<SimplifiedPreview>('preview_simplified_loan', { p }),
  create: (p: SimplifiedLoanInput) => rpc<SimplifiedLoanResult>('create_simplified_loan', { p }),

  /** Renewal settlements in which this loan is the new loan or one of the settled previous loans. */
  async renewals(loanId: string): Promise<RenewalWithLoans[]> {
    return must(await supabase.from('loan_renewals')
      .select('*, old_loan:loans!loan_renewals_old_loan_id_fkey(loan_number, status), new_loan:loans!loan_renewals_new_loan_id_fkey(loan_number, status)')
      .or(`new_loan_id.eq.${loanId},old_loan_id.eq.${loanId}`)
      .order('created_at')) as RenewalWithLoans[]
  },
}
