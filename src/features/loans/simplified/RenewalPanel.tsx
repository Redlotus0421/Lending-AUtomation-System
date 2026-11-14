import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { RefreshCw } from 'lucide-react'
import { Panel, Pill } from '@/components/common'
import { simplifiedService, type RenewalWithLoans } from '@/services/simplified'
import { fmtDate, peso, toNumber } from '@/lib/format'
import type { LoanView } from '@/types/db'
import { NetReleaseTable } from './shared'

const STATUS: Record<RenewalWithLoans['status'], { label: string; tone: 'amber' | 'green' | 'slate' }> = {
  pending: { label: 'Settles at release', tone: 'amber' },
  settled: { label: 'Settled', tone: 'green' },
  cancelled: { label: 'Cancelled', tone: 'slate' },
  closed_before_release: { label: 'Already closed', tone: 'slate' },
}

export function useLoanRenewals(loanId: string) {
  return useQuery({ queryKey: ['loan', loanId, 'renewals'], queryFn: () => simplifiedService.renewals(loanId) })
}

/** Traceability of renewals: which previous loans this loan settles, or which renewal settled this loan. */
export function RenewalPanel({ loan }: { loan: LoanView }) {
  const q = useLoanRenewals(loan.id)
  const rows = q.data ?? []
  const asNew = rows.filter((r) => r.new_loan_id === loan.id && r.status !== 'cancelled')
  const asOld = rows.filter((r) => r.old_loan_id === loan.id && r.status === 'settled')
  if (!asNew.length && !asOld.length) return null

  const released = !!loan.released_at
  const deduction = released ? toNumber(loan.renewal_deduction) : asNew.reduce((t, r) => t + toNumber(r.quoted_amount), 0)
  const deductedCharges = loan.charges_treatment === 'deducted' ? toNumber(loan.total_charges) : 0

  return (
    <div className="space-y-5">
      {!!asNew.length && (
        <Panel title={<span className="flex items-center gap-2"><RefreshCw className="size-4" /> Renewal — previous balance offset</span>}
          description={released ? 'Previous loans were settled in full from this loan’s proceeds on release.' : 'The balance is recomputed and settled when this loan is released.'}>
          <div className="grid gap-5 lg:grid-cols-[1.4fr_1fr]">
            <div className="overflow-x-auto rounded-lg border">
              <table className="w-full text-sm">
                <thead className="bg-slate-50 text-xs text-muted-foreground"><tr>
                  <th className="px-3 py-2 text-left font-medium">Previous loan</th>
                  <th className="px-3 py-2 text-right font-medium">Quoted</th>
                  <th className="px-3 py-2 text-right font-medium">Settled</th>
                  <th className="px-3 py-2 text-left font-medium">Status</th>
                </tr></thead>
                <tbody className="divide-y">
                  {asNew.map((r) => (
                    <tr key={r.id}>
                      <td className="px-3 py-2"><Link to={`/loans/${r.old_loan_id}`} className="font-medium text-[#2563EB] hover:underline">{r.old_loan?.loan_number ?? 'Loan'}</Link></td>
                      <td className="tabular px-3 py-2 text-right">{peso(r.quoted_amount)}<span className="block text-[11px] text-muted-foreground">as of {fmtDate(r.quoted_as_of)}</span></td>
                      <td className="tabular px-3 py-2 text-right">{r.settled_amount != null ? <>{peso(r.settled_amount)}<span className="block text-[11px] text-muted-foreground">{fmtDate(r.settled_as_of)}</span></> : '—'}</td>
                      <td className="px-3 py-2">
                        <Pill tone={STATUS[r.status].tone}>{STATUS[r.status].label}</Pill>
                        {r.settlement_payment_id && <Link to={`/payments/${r.settlement_payment_id}`} className="mt-1 block text-[11px] text-[#2563EB] hover:underline">View settlement payment</Link>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <NetReleaseTable principal={loan.principal} deductedCharges={deductedCharges} previousBalance={deduction}
              previousLabel={released ? 'Less: previous balance settled' : 'Less: previous balance (estimate)'}
              netRelease={toNumber(loan.net_proceeds) - deduction} />
          </div>
        </Panel>
      )}
      {asOld.map((r) => (
        <div key={r.id} className="flex flex-wrap items-center gap-2 rounded-xl border border-green-200 bg-green-50 p-4 text-sm text-green-900">
          <RefreshCw className="size-4" />
          <span>Settled in full by renewal loan <Link to={`/loans/${r.new_loan_id}`} className="font-semibold underline">{r.new_loan?.loan_number}</Link> on {fmtDate(r.settled_as_of)} — {peso(r.settled_amount)} offset against its proceeds{toNumber(r.settled_interest_rebate) > 0 ? `, ${peso(r.settled_interest_rebate)} unearned interest rebated` : ''}.</span>
          {r.settlement_payment_id && <Link to={`/payments/${r.settlement_payment_id}`} className="ml-auto text-xs font-medium underline">Settlement payment</Link>}
        </div>
      ))}
    </div>
  )
}
