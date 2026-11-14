import { useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ArrowLeft, Download, ExternalLink, Paperclip, Printer, RotateCcw, ShieldAlert } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ErrorState, KeyValue, LoanStatusBadge, PageHeader, PageLoader, Panel, Pill, Spinner } from '@/components/common'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { useAuth } from '@/features/auth/AuthProvider'
import { paymentsService } from '@/services/payments'
import { errorMessage } from '@/lib/errors'
import { fmtDate, fmtDateTime, longDate, peso, titleCase, toNumber } from '@/lib/format'
import { printElement } from '@/lib/export'
import { cn } from '@/lib/utils'

type Allocation = {
  id: string; installment_id: string; principal_amount: number; interest_amount: number; charges_amount: number
  penalty_amount: number; interest_rebate: number; total_amount: number; is_reversal: boolean; created_at: string
  loan_installments: { installment_no: number; due_date: string } | null
}

export default function PaymentDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { can } = useAuth()
  const receiptRef = useRef<HTMLDivElement>(null)
  const [reverseOpen, setReverseOpen] = useState(false)
  const [pdfBusy, setPdfBusy] = useState(false)
  const [proofBusy, setProofBusy] = useState(false)

  const q = useQuery({ queryKey: ['payments', 'detail', id], queryFn: () => paymentsService.get(id), enabled: !!id })
  if (q.isLoading) return <PageLoader />
  if (q.error || !q.data) return <ErrorState error={q.error ?? 'Payment not found'} onRetry={() => void q.refetch()} />

  const p = q.data
  const receipt = Array.isArray(p.payment_receipts) ? p.payment_receipts[0] : p.payment_receipts
  const allocations = ((p.payment_allocations ?? []) as Allocation[])
    .slice()
    .sort((a, b) => Number(a.is_reversal) - Number(b.is_reversal) || (a.loan_installments?.installment_no ?? 0) - (b.loan_installments?.installment_no ?? 0))
  const reversed = p.status === 'reversed'
  const r = (receipt?.data ?? {}) as Record<string, any>

  const downloadPdf = async () => {
    setPdfBusy(true)
    try {
      const { url } = await paymentsService.receiptPdf(p.id, true)
      window.open(url, '_blank', 'noopener')
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setPdfBusy(false)
    }
  }
  const openProof = async () => {
    setProofBusy(true)
    try {
      window.open(await paymentsService.proofUrl(p.proof_path), '_blank', 'noopener')
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setProofBusy(false)
    }
  }

  return (
    <div className="space-y-5">
      <div className="no-print">
        <PageHeader
          back={
            <button onClick={() => navigate(-1)} className="mb-2 inline-flex items-center gap-1 text-xs font-medium text-muted-foreground hover:text-foreground">
              <ArrowLeft className="size-3.5" /> Back
            </button>
          }
          title={<span className="flex flex-wrap items-center gap-2">{p.payment_number} {reversed ? <Pill tone="red">Reversed</Pill> : <Pill tone="green">Posted</Pill>}</span>}
          description={<>Receipt {receipt?.receipt_number ?? '—'} · {p.borrowers?.full_name} · <Link className="text-blue-600 hover:underline" to={`/loans/${p.loan_id}`}>{p.loans?.loan_number}</Link></>}
          actions={
            <>
              <Button variant="outline" className="bg-white" onClick={() => printElement(receiptRef.current, `Receipt ${receipt?.receipt_number ?? ''}`)} disabled={!receipt}>
                <Printer /> Print receipt
              </Button>
              <Button variant="outline" className="bg-white" onClick={() => void downloadPdf()} disabled={!receipt || pdfBusy}>
                {pdfBusy ? <Spinner /> : <Download />} Download PDF
              </Button>
              {can('payments.reverse') && !reversed && (
                <Button variant="outline" className="border-red-200 bg-white text-red-700 hover:bg-red-50 hover:text-red-800" onClick={() => setReverseOpen(true)}>
                  <RotateCcw /> Reverse
                </Button>
              )}
            </>
          }
        />
      </div>

      {reversed && (
        <div className="no-print flex gap-3 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-900">
          <ShieldAlert className="mt-0.5 size-5 shrink-0" />
          <div>
            <p className="font-semibold">This payment was reversed on {fmtDateTime(p.reversed_at)}.</p>
            <p className="mt-0.5">Reason: {p.reversal_reason}</p>
            <p className="mt-1 text-xs text-red-800/80">The original entries are preserved; offsetting reversal entries restored the installment and loan balances. The receipt is void.</p>
          </div>
        </div>
      )}

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_420px]">
        <div className="no-print space-y-5">
          <Panel title="Payment details">
            <KeyValue columns={3} items={[
              { label: 'Amount', value: <span className="tabular text-base">{peso(p.amount)}</span> },
              { label: 'Payment date', value: fmtDate(p.payment_date, 'long') },
              { label: 'Type', value: p.payment_type === 'settlement' ? 'Early settlement' : 'Regular' },
              { label: 'Method', value: titleCase(p.method) },
              { label: 'Reference', value: p.reference_no || '—' },
              { label: 'Loan', value: <span className="flex items-center gap-2"><Link className="text-blue-600 hover:underline" to={`/loans/${p.loan_id}`}>{p.loans?.loan_number}</Link>{p.loans?.status && <LoanStatusBadge status={p.loans.status} />}</span> },
              { label: 'Borrower', value: <Link className="text-blue-600 hover:underline" to={`/borrowers/${p.borrower_id}`}>{p.borrowers?.full_name}</Link> },
              { label: 'Received by', value: r.collector_name ?? '—' },
              { label: 'Recorded', value: `${fmtDateTime(p.created_at)}${r.recorded_by ? ` by ${r.recorded_by}` : ''}` },
              { label: 'Balance after payment', value: p.balance_after !== null ? peso(p.balance_after) : '—' },
              { label: 'Notes', value: p.notes || '—', hidden: !p.notes },
            ]} />
            {p.proof_path && (
              <Button variant="outline" size="sm" className="mt-4" onClick={() => void openProof()} disabled={proofBusy}>
                {proofBusy ? <Spinner /> : <Paperclip />} View proof of payment <ExternalLink className="opacity-60" />
              </Button>
            )}
          </Panel>

          <Panel title="Allocation by installment" description="How the payment was applied, following the loan's allocation order" bodyClassName="p-0 sm:p-0">
            <div className="overflow-x-auto">
              <table className="w-full min-w-[620px] text-sm">
                <thead>
                  <tr className="border-b text-left text-xs uppercase tracking-wide text-muted-foreground">
                    <th className="px-4 py-2.5 font-medium">Installment</th>
                    <th className="px-3 py-2.5 text-right font-medium">Penalty</th>
                    <th className="px-3 py-2.5 text-right font-medium">Charges</th>
                    <th className="px-3 py-2.5 text-right font-medium">Interest</th>
                    <th className="px-3 py-2.5 text-right font-medium">Principal</th>
                    <th className="px-3 py-2.5 text-right font-medium">Rebate</th>
                    <th className="px-4 py-2.5 text-right font-medium">Total</th>
                  </tr>
                </thead>
                <tbody>
                  {allocations.map((a) => (
                    <tr key={a.id} className={cn('border-b last:border-0', a.is_reversal && 'bg-red-50/50 text-red-800')}>
                      <td className="px-4 py-2.5">
                        <span className="font-medium">#{a.loan_installments?.installment_no}</span>
                        <span className="ml-2 text-xs text-muted-foreground">due {fmtDate(a.loan_installments?.due_date)}</span>
                        {a.is_reversal && <Pill tone="red" className="ml-2">Reversal</Pill>}
                      </td>
                      {[a.penalty_amount, a.charges_amount, a.interest_amount, a.principal_amount, a.interest_rebate].map((v, i) => (
                        <td key={i} className={cn('tabular px-3 py-2.5 text-right', toNumber(v) === 0 && 'text-muted-foreground')}>{peso(v)}</td>
                      ))}
                      <td className="tabular px-4 py-2.5 text-right font-semibold">{peso(a.total_amount)}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr className="border-t-2 bg-slate-50 font-semibold">
                    <td className="px-4 py-2.5">Applied (original)</td>
                    <td className="tabular px-3 py-2.5 text-right">{peso(p.penalty_applied)}</td>
                    <td className="tabular px-3 py-2.5 text-right">{peso(p.charges_applied)}</td>
                    <td className="tabular px-3 py-2.5 text-right">{peso(p.interest_applied)}</td>
                    <td className="tabular px-3 py-2.5 text-right">{peso(p.principal_applied)}</td>
                    <td className="tabular px-3 py-2.5 text-right">{peso(p.interest_rebated)}</td>
                    <td className="tabular px-4 py-2.5 text-right">{peso(p.amount)}</td>
                  </tr>
                </tfoot>
              </table>
            </div>
          </Panel>
        </div>

        {/* printable receipt */}
        <div>
          {receipt ? (
            <div ref={receiptRef} className="print-area relative overflow-hidden rounded-xl border bg-white p-6 shadow-card">
              <ReceiptView r={r} isVoid={receipt.is_void} />
            </div>
          ) : (
            <Panel title="Receipt"><p className="text-sm text-muted-foreground">No receipt was issued for this payment.</p></Panel>
          )}
        </div>
      </div>

      <ConfirmDialog
        open={reverseOpen}
        onOpenChange={setReverseOpen}
        title={`Reverse ${p.payment_number}?`}
        destructive
        requireReason
        reasonLabel="Reason for reversal"
        confirmLabel="Reverse payment"
        description={
          <div className="space-y-2 text-sm">
            <p>This posts offsetting entries for <b>{peso(p.amount)}</b>, restores the installment and loan balances, and voids receipt {receipt?.receipt_number}. Nothing is deleted.</p>
            <p className="rounded-md bg-amber-50 p-2 text-xs text-amber-900">Only the most recent payment on a loan can be reversed (last in, first out). To correct an older payment, reverse the later payments first, then re-record them.</p>
          </div>
        }
        onConfirm={async (reason) => {
          await paymentsService.reverse(p.id, reason)
          toast.success(`${p.payment_number} reversed`)
          for (const key of ['payments', 'loan', 'loans', 'installments', 'dashboard', 'collections', 'report', 'borrower']) {
            void qc.invalidateQueries({ queryKey: [key] })
          }
        }}
      />
    </div>
  )
}

function ReceiptView({ r, isVoid }: { r: Record<string, any>; isVoid: boolean }) {
  const c = r.company ?? {}
  const rows: [string, unknown][] = [
    ['Principal', r.principal_paid],
    ['Interest', r.interest_paid],
    ['Charges', r.charges_paid],
    ['Penalties', r.penalty_paid],
  ]
  const inst = (r.installments ?? []) as { installment_no: number; due_date: string; amount: number }[]
  return (
    <div className="text-[13px] text-slate-900">
      {isVoid && (
        <div className="pointer-events-none absolute inset-0 grid place-items-center">
          <span className="-rotate-[25deg] select-none text-[96px] font-black tracking-widest text-red-500/15">VOID</span>
        </div>
      )}
      <div className="h-1.5 rounded-full bg-[#142B4A]" />
      <div className="mt-4 text-right">
        <p className="text-base font-semibold text-[#142B4A]">{c.legal_name || c.name}</p>
        {c.address && <p className="text-xs text-slate-500">{c.address}</p>}
        {(c.phone || c.email) && <p className="text-xs text-slate-500">{[c.phone, c.email].filter(Boolean).join(' · ')}</p>}
        {c.tin && <p className="text-xs text-slate-500">TIN {c.tin}</p>}
      </div>
      <div className="mt-5 flex items-end justify-between gap-3 border-b pb-3">
        <div>
          <p className="text-lg font-bold tracking-tight">PAYMENT RECEIPT</p>
          <p className="text-xs text-slate-500">Issued {fmtDateTime(r.issued_at)}</p>
        </div>
        <p className="text-sm font-bold text-red-600">No. {r.receipt_number}</p>
      </div>
      <dl className="mt-4 space-y-2">
        {([
          ['Received from', r.borrower_name, true],
          ['Borrower code', r.borrower_code],
          ['Loan reference', r.loan_number],
          ['Payment date', longDate(r.payment_date)],
          ['Method', titleCase(r.method)],
          ['Reference', r.reference_no],
          ['Installment(s)', inst.length ? inst.map((i) => `#${i.installment_no}`).join(', ') : null],
          ['Payment type', r.payment_type === 'settlement' ? 'Early full settlement' : null, true],
        ] as [string, unknown, boolean?][]).filter(([, v]) => v).map(([l, v, b]) => (
          <div key={l} className="flex justify-between gap-4">
            <dt className="text-slate-500">{l}</dt>
            <dd className={cn('text-right', b && 'font-semibold')}>{String(v)}</dd>
          </div>
        ))}
      </dl>
      <div className="mt-4 flex items-center justify-between rounded-lg bg-slate-100 px-4 py-3">
        <span className="text-xs font-bold uppercase tracking-wide text-[#142B4A]">Amount received</span>
        <span className="tabular text-xl font-bold">{peso(r.amount)}</span>
      </div>
      <p className="mt-4 text-[11px] font-bold uppercase tracking-wide text-[#142B4A]">Allocation</p>
      <dl className="mt-1.5 space-y-1.5">
        {rows.map(([l, v]) => (
          <div key={l} className="flex justify-between"><dt className="text-slate-500">{l}</dt><dd className="tabular">{peso(v)}</dd></div>
        ))}
        {toNumber(r.interest_rebated) > 0 && (
          <div className="flex justify-between"><dt className="text-slate-500">Unearned interest rebated</dt><dd className="tabular">{peso(r.interest_rebated)}</dd></div>
        )}
        <div className="flex justify-between border-t pt-1.5 font-semibold"><dt>Remaining loan balance</dt><dd className="tabular">{peso(r.remaining_balance)}</dd></div>
        <div className="flex justify-between text-slate-500"><dt>Outstanding principal</dt><dd className="tabular">{peso(r.outstanding_principal)}</dd></div>
      </dl>
      <div className="mt-8 flex justify-end">
        <div className="w-48 text-center">
          <div className="border-t border-slate-800 pt-1 text-sm font-semibold">{r.collector_name || r.recorded_by}</div>
          <p className="text-[11px] text-slate-500">Received by</p>
        </div>
      </div>
      {r.footer_note && <p className="mt-6 text-[11px] leading-relaxed text-slate-500">{r.footer_note}</p>}
      <p className="mt-2 text-[10px] text-slate-400">Payment {r.payment_number}</p>
    </div>
  )
}
