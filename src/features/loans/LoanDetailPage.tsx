import { useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  ArrowLeft, Ban, BadgeCheck, Banknote, CalendarClock, CheckCircle2, ChevronDown, Circle, ClipboardCheck, FileSignature,
  HandCoins, MoreHorizontal, Pencil, Phone, Receipt, RotateCcw, Send, ShieldAlert, Undo2, UserCog, Wallet, XCircle,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Input } from '@/components/ui/input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  DataTable, EmptyState, ErrorState, InstallmentStatusBadge, KeyValue, LoanStatusBadge, NativeSelect, PageHeader, PageLoader,
  Panel, Pill, Spinner, StatCard,
} from '@/components/common'
import { MoneyInput } from '@/components/common/MoneyInput'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { useAuth } from '@/features/auth/AuthProvider'
import { AgreementPanel } from '@/features/agreements/AgreementPanel'
import { RecordPaymentDialog } from '@/features/payments/RecordPaymentDialog'
import { loansService } from '@/services/loans'
import { borrowersService } from '@/services/borrowers'
import { paymentsService } from '@/services/payments'
import { errorMessage } from '@/lib/errors'
import { openInNewTab } from '@/lib/open'
import { fmtDate, fmtDateTime, METHOD_LABEL, peso, phMobile, timeAgo, titleCase, toNumber } from '@/lib/format'
import { cn } from '@/lib/utils'
import type { LoanCalculation, LoanView } from '@/types/db'
import { AdjustmentDialog, ApproveDialog, AssignStaffDialog, ReleaseDialog, RescheduleDialog, SettlementQuoteCard } from './LoanActions'
import { Disclosures, rateLabel, ScheduleTable, SummaryGrid } from './LoanPreview'
import { RenewalPanel } from './simplified/RenewalPanel'

type Dlg = null | 'approve' | 'reject' | 'cancel' | 'release' | 'pay' | 'settle' | 'reschedule' | 'adjust' | 'assign' | 'default' | 'reinstate' | 'close' | 'submit'

export default function LoanDetailPage() {
  const { id } = useParams()
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') ?? 'overview'
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { can } = useAuth()
  const [dlg, setDlg] = useState<Dlg>(null)
  const [payInstallment, setPayInstallment] = useState<string | undefined>()

  const loanQ = useQuery({ queryKey: ['loan', id], queryFn: () => loansService.get(id!) })
  const loan = loanQ.data
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['loan', id] })
    void qc.invalidateQueries({ queryKey: ['loans'] })
    void qc.invalidateQueries({ queryKey: ['installments'] })
    void qc.invalidateQueries({ queryKey: ['dashboard'] })
    // payment-side views: reversals, settlements and releases change these too
    void qc.invalidateQueries({ queryKey: ['payments'] })
    void qc.invalidateQueries({ queryKey: ['collections'] })
    void qc.invalidateQueries({ queryKey: ['report'] })
  }

  const submit = useMutation({
    mutationFn: () => loansService.submit(id!),
    onSuccess: () => { toast.success('Submitted for review'); refresh() },
    onError: (e) => toast.error(errorMessage(e)),
  })

  if (loanQ.isLoading) return <PageLoader />
  if (loanQ.error || !loan) return <ErrorState error={loanQ.error ?? new Error('Loan not found')} onRetry={() => void loanQ.refetch()} />

  const s = loan.status
  const isOpen = s === 'active' || s === 'defaulted'
  const preActive = ['draft', 'pending_review', 'approved', 'awaiting_borrower_signature', 'awaiting_lender_signature', 'fully_signed', 'ready_for_release'].includes(s)
  // simplified loans are never edited after creation (enforced in the database too)
  const editable = loan.lending_mode !== 'simplified' && ['draft', 'pending_review', 'approved', 'awaiting_borrower_signature', 'awaiting_lender_signature'].includes(s)
  const canApprove = can('loans.approve') && !loan.approved_at && ['pending_review', 'awaiting_borrower_signature', 'awaiting_lender_signature', 'fully_signed'].includes(s)

  const primary = (() => {
    if (s === 'draft' && can('loans.create')) return <Button onClick={() => submit.mutate()} disabled={submit.isPending}>{submit.isPending ? <Spinner /> : <Send />} Submit for review</Button>
    if (canApprove) return <Button onClick={() => setDlg('approve')}><BadgeCheck /> Approve</Button>
    if (s === 'ready_for_release' && can('loans.release')) return <Button onClick={() => setDlg('release')}><Banknote /> Release funds</Button>
    if (['awaiting_borrower_signature', 'awaiting_lender_signature'].includes(s)) return <Button onClick={() => setParams({ tab: 'agreement' })}><FileSignature /> Signing</Button>
    if (isOpen && can('payments.record')) return <Button onClick={() => { setPayInstallment(undefined); setDlg('pay') }}><Wallet /> Record payment</Button>
    return null
  })()

  return (
    <div>
      <PageHeader
        back={<button onClick={() => navigate(-1)} className="mb-2 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"><ArrowLeft className="size-3.5" /> Back</button>}
        title={<span className="flex flex-wrap items-center gap-2">{loan.loan_number} <LoanStatusBadge status={s} />{loan.lending_mode === 'simplified' && <Pill tone="teal" dot={false}>Simplified</Pill>}</span>}
        description={<><Link to={`/borrowers/${loan.borrower_id}`} className="font-medium text-foreground hover:underline">{loan.borrower_name}</Link> · {loan.borrower_code} · {peso(loan.principal)} · {METHOD_LABEL[loan.interest_method]} {rateLabel(loan.interest_rate, loan.rate_period)}</>}
        actions={
          <>
            {primary}
            <DropdownMenu>
              <DropdownMenuTrigger asChild><Button variant="outline"><MoreHorizontal /> Actions <ChevronDown className="size-3.5" /></Button></DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-60">
                {isOpen && can('payments.record') && <DropdownMenuItem onClick={() => { setPayInstallment(undefined); setDlg('pay') }}><Wallet /> Record payment</DropdownMenuItem>}
                {isOpen && can('payments.record') && <DropdownMenuItem onClick={() => setDlg('settle')}><HandCoins /> Early settlement</DropdownMenuItem>}
                <DropdownMenuItem onClick={() => setParams({ tab: 'agreement' })}><FileSignature /> View agreement</DropdownMenuItem>
                {editable && can('loans.create') && <DropdownMenuItem onClick={() => navigate(`/loans/${loan.id}/edit`)}><Pencil /> Edit loan terms</DropdownMenuItem>}
                {s === 'draft' && can('loans.create') && <DropdownMenuItem onClick={() => submit.mutate()}><Send /> Submit for review</DropdownMenuItem>}
                {canApprove && <DropdownMenuItem onClick={() => setDlg('approve')}><BadgeCheck /> Approve</DropdownMenuItem>}
                {s === 'ready_for_release' && can('loans.release') && <DropdownMenuItem onClick={() => setDlg('release')}><Banknote /> Release funds</DropdownMenuItem>}
                {isOpen && can('loans.manage') && <DropdownMenuItem onClick={() => setDlg('reschedule')}><CalendarClock /> Reschedule (amendment)</DropdownMenuItem>}
                {isOpen && can('payments.adjust') && <DropdownMenuItem onClick={() => setDlg('adjust')}><ClipboardCheck /> Waiver / adjustment</DropdownMenuItem>}
                {can('collections.assign') && <DropdownMenuItem onClick={() => setDlg('assign')}><UserCog /> Assign staff</DropdownMenuItem>}
                <DropdownMenuSeparator />
                {s === 'active' && can('loans.manage') && <DropdownMenuItem onClick={() => setDlg('default')} className="text-red-600"><ShieldAlert /> Mark as defaulted</DropdownMenuItem>}
                {s === 'defaulted' && can('loans.manage') && <DropdownMenuItem onClick={() => setDlg('reinstate')}><RotateCcw /> Reinstate</DropdownMenuItem>}
                {isOpen && can('loans.manage') && <DropdownMenuItem onClick={() => setDlg('close')} disabled={toNumber(loan.total_outstanding) > 0}><CheckCircle2 /> Close loan</DropdownMenuItem>}
                {preActive && can('loans.approve') && s !== 'draft' && <DropdownMenuItem onClick={() => setDlg('reject')} className="text-red-600"><XCircle /> Reject</DropdownMenuItem>}
                {preActive && can('loans.cancel') && <DropdownMenuItem onClick={() => setDlg('cancel')} className="text-red-600"><Ban /> Cancel loan</DropdownMenuItem>}
              </DropdownMenuContent>
            </DropdownMenu>
          </>
        }
      />

      <WorkflowStrip loan={loan} />

      <Tabs value={tab} onValueChange={(v) => setParams({ tab: v }, { replace: true })} className="mt-5">
        <div className="-mx-3 overflow-x-auto px-3 sm:mx-0 sm:px-0">
          <TabsList className="h-10">
            <TabsTrigger value="overview">Overview</TabsTrigger>
            <TabsTrigger value="schedule">Repayment schedule</TabsTrigger>
            <TabsTrigger value="payments">Payment history</TabsTrigger>
            <TabsTrigger value="agreement">Agreement documents</TabsTrigger>
            <TabsTrigger value="borrower">Borrower</TabsTrigger>
            <TabsTrigger value="notes">Notes & activities</TabsTrigger>
            <TabsTrigger value="audit">Audit history</TabsTrigger>
          </TabsList>
        </div>
        <TabsContent value="overview" className="mt-4"><OverviewTab loan={loan} onSettle={() => setDlg('settle')} /></TabsContent>
        <TabsContent value="schedule" className="mt-4">
          <ScheduleTab loan={loan} onCollect={(iid) => { setPayInstallment(iid); setDlg('pay') }} />
        </TabsContent>
        <TabsContent value="payments" className="mt-4"><PaymentsTab loan={loan} onChanged={refresh} /></TabsContent>
        <TabsContent value="agreement" className="mt-4"><AgreementPanel loan={loan} /></TabsContent>
        <TabsContent value="borrower" className="mt-4"><BorrowerTab loan={loan} /></TabsContent>
        <TabsContent value="notes" className="mt-4"><NotesTab loan={loan} /></TabsContent>
        <TabsContent value="audit" className="mt-4"><AuditTab loan={loan} /></TabsContent>
      </Tabs>

      <ApproveDialog loan={loan} open={dlg === 'approve'} onOpenChange={(o) => setDlg(o ? 'approve' : null)} onDone={refresh} />
      <ReleaseDialog loan={loan} open={dlg === 'release'} onOpenChange={(o) => setDlg(o ? 'release' : null)} onDone={refresh} />
      <AssignStaffDialog loan={loan} open={dlg === 'assign'} onOpenChange={(o) => setDlg(o ? 'assign' : null)} onDone={refresh} />
      {dlg === 'adjust' && <AdjustmentDialog loan={loan} open onOpenChange={(o) => setDlg(o ? 'adjust' : null)} onDone={refresh} />}
      {dlg === 'reschedule' && <RescheduleDialog loan={loan} open onOpenChange={(o) => setDlg(o ? 'reschedule' : null)} onDone={refresh} />}
      <RecordPaymentDialog open={dlg === 'pay' || dlg === 'settle'} onOpenChange={(o) => !o && setDlg(null)} loanId={loan.id}
        installmentId={payInstallment} defaultType={dlg === 'settle' ? 'settlement' : 'regular'} onRecorded={refresh} />
      <ConfirmDialog open={dlg === 'reject'} onOpenChange={(o) => setDlg(o ? 'reject' : null)} title={`Reject ${loan.loan_number}?`} destructive requireReason confirmLabel="Reject loan"
        description="Any open agreement is voided and preserved in the audit trail." onConfirm={async (r) => { await loansService.reject(loan.id, r); toast.success('Loan rejected'); refresh() }} />
      <ConfirmDialog open={dlg === 'cancel'} onOpenChange={(o) => setDlg(o ? 'cancel' : null)} title={`Cancel ${loan.loan_number}?`} destructive requireReason confirmLabel="Cancel loan"
        onConfirm={async (r) => { await loansService.cancel(loan.id, r); toast.success('Loan cancelled'); refresh() }} />
      <ConfirmDialog open={dlg === 'default'} onOpenChange={(o) => setDlg(o ? 'default' : null)} title="Mark loan as defaulted?" destructive requireReason confirmLabel="Mark defaulted"
        description="Payments can still be recorded on defaulted loans." onConfirm={async (r) => { await loansService.markDefault(loan.id, r); toast.success('Loan marked as defaulted'); refresh() }} />
      <ConfirmDialog open={dlg === 'reinstate'} onOpenChange={(o) => setDlg(o ? 'reinstate' : null)} title="Reinstate loan to active?" requireReason confirmLabel="Reinstate"
        onConfirm={async (r) => { await loansService.reinstate(loan.id, r); toast.success('Loan reinstated'); refresh() }} />
      <ConfirmDialog open={dlg === 'close'} onOpenChange={(o) => setDlg(o ? 'close' : null)} title="Close this loan?" confirmLabel="Close loan"
        description="Only loans with a zero balance can be closed." onConfirm={async () => { await loansService.close(loan.id); toast.success('Loan closed'); refresh() }} />
    </div>
  )
}

function WorkflowStrip({ loan }: { loan: LoanView }) {
  const signed = ['fully_signed', 'ready_for_release', 'active', 'completed', 'defaulted'].includes(loan.status)
  const steps = [
    { label: 'Application', done: !!loan.submitted_at || loan.status !== 'draft', at: loan.submitted_at ?? loan.created_at },
    { label: 'Approval', done: !!loan.approved_at, at: loan.approved_at },
    { label: 'Signatures', done: signed, at: null },
    { label: 'Released', done: !!loan.released_at, at: loan.released_at },
    { label: loan.status === 'defaulted' ? 'Defaulted' : 'Completed', done: loan.status === 'completed', at: loan.completed_at },
  ]
  if (['rejected', 'cancelled'].includes(loan.status)) {
    return (
      <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800">
        <p className="font-semibold">{loan.status === 'rejected' ? 'Rejected' : 'Cancelled'} {fmtDateTime(loan.rejected_at ?? loan.cancelled_at)}</p>
        <p>{loan.rejection_reason ?? loan.cancellation_reason}</p>
      </div>
    )
  }
  return (
    <div className="grid grid-cols-5 gap-1 rounded-xl border bg-white p-3 shadow-card sm:gap-3 sm:p-4">
      {steps.map((st, i) => (
        <div key={st.label} className="min-w-0">
          <div className="flex items-center gap-1.5">
            {st.done ? <CheckCircle2 className="size-4 shrink-0 text-green-600" /> : <Circle className="size-4 shrink-0 text-slate-300" />}
            <span className={cn('truncate text-xs font-medium sm:text-sm', st.done ? 'text-foreground' : 'text-muted-foreground')}>{st.label}</span>
          </div>
          <p className="mt-0.5 hidden truncate pl-5.5 text-[11px] text-muted-foreground sm:block">{st.at ? fmtDate(st.at) : i === 2 && loan.agreement_status ? titleCase(loan.agreement_status) : ' '}</p>
        </div>
      ))}
    </div>
  )
}

function OverviewTab({ loan, onSettle }: { loan: LoanView; onSettle: () => void }) {
  const isOpen = loan.status === 'active' || loan.status === 'defaulted'
  const calc: LoanCalculation = { summary: loan.calc_summary, schedule: [], disclosures: [], warnings: [] }
  return (
    <div className="space-y-5">
      <RenewalPanel loan={loan} />
      {(isOpen || loan.status === 'completed') && (
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
          <StatCard label="Remaining payable" value={peso(loan.total_outstanding)} tone="navy" hint={toNumber(loan.overdue_amount) > 0 ? <span className="text-red-600">{peso(loan.overdue_amount)} overdue</span> : 'Nothing overdue'} />
          <StatCard label="Outstanding principal" value={peso(loan.outstanding_principal)} tone="blue" hint={`Paid ${peso(loan.principal_paid)}`} />
          <StatCard label="Outstanding interest" value={peso(loan.outstanding_interest)} tone="teal" hint={`Collected ${peso(loan.interest_paid)}`} />
          <StatCard label="Charges / penalties" value={peso(toNumber(loan.outstanding_charges) + toNumber(loan.outstanding_penalties))} tone="amber" hint={`Penalties assessed ${peso(loan.penalties_assessed)}`} />
          <StatCard label="Total paid" value={peso(loan.total_paid)} tone="green" hint={`${loan.paid_installments}/${loan.total_installments} installments paid`} />
        </div>
      )}
      <div className="grid gap-5 lg:grid-cols-[1fr_340px]">
        <Panel title="Loan terms" description="Frozen contractual terms calculated by the server">
          <SummaryGrid calc={calc} />
          {loan.purpose && <p className="mt-4 text-sm"><span className="text-muted-foreground">Purpose:</span> {loan.purpose}</p>}
          {loan.notes && <p className="mt-2 rounded-lg bg-slate-50 p-3 text-sm text-slate-700">{loan.notes}</p>}
        </Panel>
        <div className="space-y-5">
          {isOpen && (
            <Panel title="Next payment">
              {loan.next_due_date ? (
                <div>
                  <p className="tabular text-2xl font-semibold">{peso(loan.next_due_amount)}</p>
                  <p className="text-sm text-muted-foreground">Due {fmtDate(loan.next_due_date)}</p>
                </div>
              ) : <p className="text-sm text-muted-foreground">{toNumber(loan.overdue_amount) > 0 ? 'All remaining installments are overdue.' : 'No upcoming installments.'}</p>}
              {loan.days_overdue > 0 && <p className="mt-2 rounded bg-red-50 p-2 text-xs text-red-700">Oldest overdue installment: {loan.days_overdue} days</p>}
            </Panel>
          )}
          {isOpen && (
            <Panel title="Early settlement quote" actions={<Button size="sm" variant="outline" onClick={onSettle}>Settle</Button>}>
              <SettlementQuoteCard loan={loan} />
            </Panel>
          )}
          <Panel title="Assignment & dates">
            <KeyValue columns={1} items={[
              { label: 'Loan officer', value: loan.loan_officer_name ?? '—' },
              { label: 'Collector', value: loan.collector_name ?? 'Unassigned' },
              { label: 'Product', value: loan.product_name ?? 'Custom terms' },
              { label: 'Release date', value: loan.actual_release_date ? `${fmtDate(loan.actual_release_date)} (${titleCase(loan.release_method ?? '')})` : `Planned ${fmtDate(loan.release_date)}` },
              { label: 'Maturity date', value: fmtDate(loan.maturity_date) },
              { label: 'Late payment', value: loan.penalty_enabled ? `${toNumber(loan.penalty_rate)}${loan.penalty_type === 'fixed' ? ' PHP' : '%'} · ${titleCase(loan.penalty_type)} · ${loan.penalty_grace_days}-day grace` : 'No penalty' },
              { label: 'Allocation order', value: loan.allocation_order.join(' → ') },
            ]} />
          </Panel>
        </div>
      </div>
    </div>
  )
}

function ScheduleTab({ loan, onCollect }: { loan: LoanView; onCollect: (installmentId: string) => void }) {
  const { can } = useAuth()
  const active = !!loan.activated_at
  const inst = useQuery({ queryKey: ['installments', loan.id], queryFn: () => loansService.installments(loan.id), enabled: active })
  const proposed = useQuery({
    queryKey: ['loan', loan.id, 'proposed'],
    queryFn: () => loansService.preview({
      product_id: loan.product_id, principal: toNumber(loan.principal), interest_method: loan.interest_method, interest_rate: toNumber(loan.interest_rate),
      rate_period: loan.rate_period, repayment_frequency: loan.repayment_frequency, term_count: loan.term_count, release_date: loan.release_date,
      first_due_date: loan.first_due_date, processing_fee_type: loan.processing_fee_type as 'none', processing_fee_value: toNumber(loan.processing_fee_value),
      other_charges: toNumber(loan.other_charges), charges_treatment: loan.charges_treatment, day_count: loan.day_count, rounding: loan.rounding,
      penalty_enabled: false,
    }),
    enabled: !active,
  })
  if (!active) {
    return (
      <Panel title="Proposed repayment schedule" description="Becomes the official schedule when the loan is released.">
        {proposed.isLoading ? <Spinner /> : proposed.error ? <ErrorState error={proposed.error} /> : proposed.data && (
          <div className="space-y-4"><ScheduleTable rows={proposed.data.schedule} maxHeight={600} /><Disclosures calc={proposed.data} /></div>
        )}
      </Panel>
    )
  }
  const rows = inst.data ?? []
  return (
    <Panel title="Official repayment schedule" description="Balances update automatically as payments are posted" bodyClassName="p-0 sm:p-0">
      {inst.isLoading ? <div className="p-5"><Spinner /></div> : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[900px] text-sm">
            <thead className="bg-slate-50 text-xs uppercase tracking-wide text-muted-foreground">
              <tr>
                {['#', 'Due date', 'Principal', 'Interest', 'Charges', 'Penalty', 'Scheduled', 'Paid', 'Remaining', 'Status', ''].map((h) => (
                  <th key={h} className={cn('px-3 py-2 font-medium', ['#', 'Due date', 'Status', ''].includes(h) ? 'text-left' : 'text-right')}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="tabular">
              {rows.map((r) => (
                <tr key={r.id} className={cn('border-t', r.display_status === 'overdue' && 'bg-red-50/40', r.status === 'rescheduled' && 'opacity-50')}>
                  <td className="px-3 py-2 text-muted-foreground">{r.installment_no}</td>
                  <td className="px-3 py-2">{fmtDate(r.due_date)}{r.days_overdue > 0 && <span className="ml-1 text-xs text-red-600">({r.days_overdue}d)</span>}</td>
                  <td className="px-3 py-2 text-right">{peso(r.principal_due)}</td>
                  <td className="px-3 py-2 text-right">{peso(r.interest_due)}{toNumber(r.interest_waived) > 0 && <span className="block text-[10px] text-green-700">−{peso(r.interest_waived)} waived</span>}</td>
                  <td className="px-3 py-2 text-right">{peso(r.charges_due)}</td>
                  <td className="px-3 py-2 text-right">{toNumber(r.penalty_due) > 0 ? peso(r.penalty_due) : '—'}</td>
                  <td className="px-3 py-2 text-right">{peso(r.scheduled_amount)}</td>
                  <td className="px-3 py-2 text-right text-green-700">{toNumber(r.amount_paid) > 0 ? peso(r.amount_paid) : '—'}</td>
                  <td className="px-3 py-2 text-right font-semibold">{toNumber(r.amount_remaining) > 0 && r.status !== 'rescheduled' ? peso(r.amount_remaining) : '—'}</td>
                  <td className="px-3 py-2"><InstallmentStatusBadge status={r.display_status} /></td>
                  <td className="px-3 py-2 text-right">
                    {['pending', 'partially_paid'].includes(r.status) && can('payments.record') && ['active', 'defaulted'].includes(loan.status) && (
                      <Button size="sm" variant="outline" onClick={() => onCollect(r.id)}>Collect</Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  )
}

function PaymentsTab({ loan, onChanged }: { loan: LoanView; onChanged: () => void }) {
  const { can } = useAuth()
  const navigate = useNavigate()
  const pays = useQuery({ queryKey: ['payments', 'loan', loan.id], queryFn: () => loansService.payments(loan.id) })
  const ledger = useQuery({ queryKey: ['loan', loan.id, 'ledger'], queryFn: () => loansService.ledger(loan.id) })
  const [reverse, setReverse] = useState<string | null>(null)
  const [busyReceipt, setBusyReceipt] = useState<string | null>(null)
  return (
    <div className="space-y-5">
      <Panel title="Payments" description="Posted payments are permanent; corrections are made through reversals.">
        <DataTable
          rows={pays.data}
          loading={pays.isLoading}
          rowKey={(p) => p.id}
          onRowClick={(p) => navigate(`/payments/${p.id}`)}
          empty={<EmptyState icon={<Receipt />} title="No payments recorded yet" />}
          columns={[
            { key: 'date', header: 'Date', cell: (p) => fmtDate(p.payment_date), mobile: 'subtitle' },
            { key: 'no', header: 'Payment #', cell: (p) => <span className="font-medium">{p.payment_number}</span>, mobile: 'title' },
            { key: 'method', header: 'Method', cell: (p) => titleCase(p.method) + (p.reference_no ? ` · ${p.reference_no}` : ''), mobile: 'meta' },
            { key: 'alloc', header: 'Principal / Interest / Other', cell: (p) => <span className="text-xs text-muted-foreground">{peso(p.principal_applied)} / {peso(p.interest_applied)} / {peso(toNumber(p.charges_applied) + toNumber(p.penalty_applied))}</span> },
            { key: 'amount', header: 'Amount', align: 'right', cell: (p) => <span className={cn('font-semibold', p.status === 'reversed' && 'text-muted-foreground line-through')}>{peso(p.amount)}</span>, mobile: 'value' },
            { key: 'status', header: 'Status', cell: (p) => p.status === 'reversed' ? <Pill tone="red">Reversed</Pill> : p.payment_type === 'settlement' ? <Pill tone="teal">Settlement</Pill> : <Pill tone="green">Posted</Pill>, mobile: 'meta' },
            {
              key: 'act', header: '', align: 'right', cell: (p) => (
                <div className="flex justify-end gap-1" onClick={(e) => e.stopPropagation()}>
                  <Button size="sm" variant="ghost" disabled={busyReceipt === p.id} onClick={async () => {
                    setBusyReceipt(p.id)
                    try { await openInNewTab(async () => (await paymentsService.receiptPdf(p.id)).url) } catch (e) { toast.error(errorMessage(e)) } finally { setBusyReceipt(null) }
                  }}>{busyReceipt === p.id ? <Spinner /> : <Receipt />} Receipt</Button>
                  {p.status === 'posted' && can('payments.reverse') && <Button size="sm" variant="ghost" className="text-red-600" onClick={() => setReverse(p.id)}><Undo2 /> Reverse</Button>}
                </div>
              ),
            },
          ]}
        />
      </Panel>
      <Panel title="Financial ledger" description="Every balance change is recorded here permanently (running balances after each entry)" bodyClassName="p-0 sm:p-0">
        {ledger.isLoading ? <div className="p-5"><Spinner /></div> : !ledger.data?.length ? <EmptyState title="No ledger entries yet" description="Entries appear when the loan is released." /> : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[860px] text-sm">
              <thead className="bg-slate-50 text-xs uppercase tracking-wide text-muted-foreground">
                <tr>{['Date', 'Entry', 'Description', 'Cash', 'Principal', 'Interest', 'Charges', 'Penalty', 'Balance'].map((h, i) => <th key={h} className={cn('px-3 py-2 font-medium', i < 3 ? 'text-left' : 'text-right')}>{h}</th>)}</tr>
              </thead>
              <tbody className="tabular">
                {ledger.data.map((e) => (
                  <tr key={e.id} className="border-t">
                    <td className="px-3 py-2 whitespace-nowrap">{fmtDate(e.entry_date)}</td>
                    <td className="px-3 py-2"><span className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] font-medium">{titleCase(e.entry_type)}</span></td>
                    <td className="max-w-64 truncate px-3 py-2 text-muted-foreground" title={e.description ?? ''}>{e.description}</td>
                    <td className={cn('px-3 py-2 text-right', toNumber(e.cash_delta) < 0 ? 'text-red-600' : toNumber(e.cash_delta) > 0 ? 'text-green-700' : 'text-muted-foreground')}>{toNumber(e.cash_delta) ? peso(e.cash_delta) : '—'}</td>
                    {[e.principal_delta, e.interest_delta, e.charges_delta, e.penalty_delta].map((d, i) => (
                      <td key={i} className="px-3 py-2 text-right text-muted-foreground">{toNumber(d) ? (toNumber(d) > 0 ? '+' : '') + peso(d) : '—'}</td>
                    ))}
                    <td className="px-3 py-2 text-right font-semibold">{peso(e.balance_total)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
      <ConfirmDialog open={!!reverse} onOpenChange={(o) => !o && setReverse(null)} title="Reverse this payment?" destructive requireReason confirmLabel="Reverse payment"
        description="A reversal entry restores the balances and voids the receipt. Only the most recent payment on a loan can be reversed."
        onConfirm={async (r) => { await paymentsService.reverse(reverse!, r); toast.success('Payment reversed'); onChanged() }} />
    </div>
  )
}

function BorrowerTab({ loan }: { loan: LoanView }) {
  const b = useQuery({ queryKey: ['borrower', loan.borrower_id], queryFn: () => borrowersService.get(loan.borrower_id) })
  if (b.isLoading) return <Spinner />
  if (!b.data) return <ErrorState error={b.error} />
  const x = b.data
  return (
    <Panel title={x.full_name} description={x.borrower_code} actions={<Button asChild variant="outline" size="sm"><Link to={`/borrowers/${x.id}`}>Open profile</Link></Button>}>
      <KeyValue columns={3} items={[
        { label: 'Mobile', value: <a href={`tel:${x.mobile_number}`} className="inline-flex items-center gap-1 text-blue-600"><Phone className="size-3.5" />{phMobile(x.mobile_number)}</a> },
        { label: 'Email', value: x.email ?? '—' },
        { label: 'Date of birth', value: fmtDate(x.date_of_birth) },
        { label: 'Address', value: [x.address_line, x.barangay, x.city_municipality, x.province].filter(Boolean).join(', ') || '—' },
        { label: 'Occupation', value: [x.occupation, x.employer_name].filter(Boolean).join(' · ') || '—' },
        { label: 'Monthly income', value: x.monthly_income ? peso(x.monthly_income) : '—' },
        { label: 'Emergency contact', value: x.emergency_contact_name ? `${x.emergency_contact_name} (${x.emergency_contact_relationship ?? '—'}) ${phMobile(x.emergency_contact_number)}` : '—' },
        { label: 'Risk rating', value: titleCase(x.risk_rating) },
        { label: 'Status', value: titleCase(x.status) },
      ]} />
    </Panel>
  )
}

function NotesTab({ loan }: { loan: LoanView }) {
  const qc = useQueryClient()
  const notes = useQuery({ queryKey: ['loan', loan.id, 'notes'], queryFn: () => loansService.notes(loan.id) })
  const [body, setBody] = useState('')
  const [type, setType] = useState('note')
  const [promiseDate, setPromiseDate] = useState('')
  const [promiseAmount, setPromiseAmount] = useState('')
  const add = useMutation({
    mutationFn: () => loansService.addNote({
      loan_id: loan.id, borrower_id: loan.borrower_id, body, note_type: type,
      promise_date: type === 'promise_to_pay' ? promiseDate || null : null,
      promise_amount: type === 'promise_to_pay' && promiseAmount ? Number(promiseAmount) : null,
    }),
    onSuccess: () => { setBody(''); setPromiseAmount(''); setPromiseDate(''); void qc.invalidateQueries({ queryKey: ['loan', loan.id, 'notes'] }) },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const icon: Record<string, string> = { note: '📝', call: '📞', visit: '🏠', promise_to_pay: '🤝', system: '⚙️' }
  return (
    <div className="grid gap-5 lg:grid-cols-[360px_1fr]">
      <Panel title="Log activity">
        <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); if (body.trim()) add.mutate() }}>
          <NativeSelect value={type} onChange={(e) => setType(e.target.value)}>
            <option value="note">Note</option>
            <option value="call">Phone call</option>
            <option value="visit">Field visit</option>
            <option value="promise_to_pay">Promise to pay</option>
          </NativeSelect>
          {type === 'promise_to_pay' && (
            <div className="grid grid-cols-2 gap-2">
              <Input type="date" value={promiseDate} onChange={(e) => setPromiseDate(e.target.value)} className="h-10" />
              <MoneyInput placeholder="Amount" value={promiseAmount} onValueChange={setPromiseAmount} className="h-10" />
            </div>
          )}
          <Textarea rows={4} value={body} onChange={(e) => setBody(e.target.value)} placeholder="What happened?" />
          <Button disabled={!body.trim() || add.isPending} className="w-full">{add.isPending && <Spinner />} Add to timeline</Button>
        </form>
      </Panel>
      <Panel title="Timeline">
        {notes.isLoading ? <Spinner /> : !notes.data?.length ? <EmptyState title="No activity yet" /> : (
          <ol className="space-y-4">
            {notes.data.map((n) => (
              <li key={n.id} className="flex gap-3">
                <span className="grid size-8 shrink-0 place-items-center rounded-full bg-slate-100 text-sm">{icon[n.note_type]}</span>
                <div className="min-w-0">
                  <p className="text-sm">{n.body}</p>
                  {n.note_type === 'promise_to_pay' && (n.promise_date || n.promise_amount) && (
                    <p className="text-xs font-medium text-blue-700">Promised {n.promise_amount ? peso(n.promise_amount) : ''} {n.promise_date ? `by ${fmtDate(n.promise_date)}` : ''}</p>
                  )}
                  <p className="text-xs text-muted-foreground">{n.note_type === 'system' ? 'System' : n.author?.full_name ?? 'Staff'} · {timeAgo(n.created_at)}</p>
                </div>
              </li>
            ))}
          </ol>
        )}
      </Panel>
    </div>
  )
}

function AuditTab({ loan }: { loan: LoanView }) {
  const { can } = useAuth()
  const snaps = useQuery({ queryKey: ['loan', loan.id, 'snapshots'], queryFn: () => loansService.snapshots(loan.id) })
  const audit = useQuery({ queryKey: ['loan', loan.id, 'audit'], queryFn: () => loansService.auditHistory(loan.id), enabled: can('audit.view') })
  return (
    <div className="space-y-5">
      <Panel title="Frozen terms snapshots" description="Each agreement and amendment references an immutable snapshot of the loan terms">
        {snaps.isLoading ? <Spinner /> : !snaps.data?.length ? <p className="text-sm text-muted-foreground">No snapshot yet — one is created when the agreement is generated.</p> : (
          <ul className="space-y-2">
            {snaps.data.map((s) => (
              <li key={s.id} className="rounded-lg border p-3 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">Version {s.version}</span>
                  <Pill tone={s.kind === 'amendment' ? 'violet' : 'blue'}>{titleCase(s.kind)}</Pill>
                  <span className="text-xs text-muted-foreground">{fmtDateTime(s.created_at)}</span>
                  {loan.terms_snapshot_id === s.id && <Pill tone="green">Official</Pill>}
                </div>
                <p className="mt-1 text-xs text-muted-foreground">{peso(s.summary.principal)} · {s.summary.duration_label} · total {peso(s.summary.total_repayment)}{s.reason ? ` · ${s.reason}` : ''}</p>
                <p className="mt-1 break-all font-mono text-[10.5px] text-slate-500">SHA-256 {s.terms_hash}</p>
              </li>
            ))}
          </ul>
        )}
      </Panel>
      {can('audit.view') && (
        <Panel title="Change history">
          {audit.isLoading ? <Spinner /> : !audit.data?.length ? <p className="text-sm text-muted-foreground">No changes recorded.</p> : (
            <ol className="space-y-3">
              {audit.data.map((a) => (
                <li key={a.id} className="text-sm">
                  <p><span className="font-medium">{a.actor_name ?? 'System'}</span> <span className="text-muted-foreground">{a.action}d</span>{' '}
                    {a.changed_fields?.length ? <span className="text-muted-foreground">— {a.changed_fields.slice(0, 8).map(titleCase).join(', ')}{a.changed_fields.length > 8 ? '…' : ''}</span> : null}</p>
                  <p className="text-xs text-muted-foreground">{fmtDateTime(a.created_at)}{a.new_data?.status && a.old_data?.status !== a.new_data.status ? ` · status → ${titleCase(a.new_data.status)}` : ''}</p>
                </li>
              ))}
            </ol>
          )}
        </Panel>
      )}
    </div>
  )
}
