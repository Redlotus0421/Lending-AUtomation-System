import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Field, NativeSelect, Spinner } from '@/components/common'
import { MoneyInput } from '@/components/common/MoneyInput'
import { loansService } from '@/services/loans'
import { usersService } from '@/services/org'
import { paymentsService } from '@/services/payments'
import { errorMessage } from '@/lib/errors'
import { fmtDate, peso, todayManila, toNumber } from '@/lib/format'
import { FREQUENCIES, PAYMENT_METHODS, RATE_PERIODS } from '@/lib/constants'
import type { LoanView } from '@/types/db'
import { HeadlineFigures, ScheduleTable } from './LoanPreview'
import { useLoanRenewals } from './simplified/RenewalPanel'

type Props = { loan: LoanView; open: boolean; onOpenChange: (o: boolean) => void; onDone: () => void }

export function ApproveDialog({ loan, open, onOpenChange, onDone }: Props) {
  const [notes, setNotes] = useState('')
  const m = useMutation({
    mutationFn: () => loansService.approve(loan.id, notes),
    onSuccess: (r) => {
      toast.success(r.status === 'ready_for_release' ? 'Approved — the loan is ready for release' : 'Loan approved')
      onOpenChange(false)
      onDone()
    },
    onError: (e) => toast.error(errorMessage(e)),
  })
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Approve {loan.loan_number}</DialogTitle>
          <DialogDescription>Approval is recorded separately from signing and disbursement. The loan can only be released after approval and all signatures.</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-3 rounded-lg bg-slate-50 p-3 text-sm">
          <div><p className="text-xs text-muted-foreground">Borrower</p><p className="font-medium">{loan.borrower_name}</p></div>
          <div><p className="text-xs text-muted-foreground">Principal</p><p className="font-medium">{peso(loan.principal)}</p></div>
          <div><p className="text-xs text-muted-foreground">Total repayment</p><p className="font-medium">{peso(loan.total_repayment)}</p></div>
          <div><p className="text-xs text-muted-foreground">Installment</p><p className="font-medium">{peso(loan.installment_amount)} × {loan.installment_count}</p></div>
        </div>
        <Field label="Approval notes"><Textarea rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Credit assessment, conditions…" /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button onClick={() => m.mutate()} disabled={m.isPending}>{m.isPending && <Spinner />} Approve loan</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export function ReleaseDialog({ loan, open, onOpenChange, onDone }: Props) {
  const renewals = useLoanRenewals(loan.id)
  const pendingRenewals = (renewals.data ?? []).filter((r) => r.new_loan_id === loan.id && r.status === 'pending')
  const estimate = pendingRenewals.reduce((t, r) => t + toNumber(r.quoted_amount), 0)
  const [date, setDate] = useState(todayManila())
  const [method, setMethod] = useState('cash')
  const [reference, setReference] = useState('')
  const m = useMutation({
    mutationFn: () => loansService.release(loan.id, { release_date: date, method, reference }),
    onSuccess: (r) => {
      toast.success(toNumber(r.renewal_deduction) > 0
        ? `Released ${peso(r.net_release)} after settling ${peso(r.renewal_deduction)} of previous balance — ${r.installments} installments scheduled`
        : `Loan released and activated — ${r.installments} installments scheduled`)
      onOpenChange(false)
      onDone()
    },
    onError: (e) => toast.error(errorMessage(e)),
  })
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Release funds</DialogTitle>
          <DialogDescription>Records the disbursement in the ledger, generates the official repayment schedule from the signed agreement and activates the loan.</DialogDescription>
        </DialogHeader>
        <div className="rounded-xl bg-[#142B4A] p-4 text-white">
          <p className="text-xs uppercase tracking-wide text-white/60">Net proceeds to release</p>
          <p className="tabular text-2xl font-semibold">{peso(loan.net_proceeds)}</p>
          <p className="text-xs text-white/60">Principal {peso(loan.principal)}{toNumber(loan.total_charges) - toNumber(loan.financed_charges) > 0 ? ` less deducted charges ${peso(toNumber(loan.total_charges) - toNumber(loan.financed_charges))}` : ''}</p>
        </div>
        {pendingRenewals.length > 0 && (
          <div className="space-y-1.5 rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-950">
            <p className="font-semibold">Renewal: previous loan{pendingRenewals.length > 1 ? 's' : ''} {pendingRenewals.map((r) => r.old_loan?.loan_number).join(', ')} will be settled first</p>
            <div className="flex justify-between"><span>Previous balance (quoted {fmtDate(pendingRenewals[0].quoted_as_of)})</span><span className="tabular font-medium">− {peso(estimate)}</span></div>
            <div className="flex justify-between border-t border-amber-200 pt-1.5 font-semibold"><span>Estimated amount to hand over</span><span className="tabular">{peso(toNumber(loan.net_proceeds) - estimate)}</span></div>
            <p className="text-xs">The payoff is recomputed as of the release date and posted as a settlement payment on the previous loan.</p>
          </div>
        )}
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Release date"><Input type="date" value={date} max={todayManila()} onChange={(e) => setDate(e.target.value)} className="h-10" /></Field>
          <Field label="Release method">
            <NativeSelect value={method} onChange={(e) => setMethod(e.target.value)}>
              {PAYMENT_METHODS.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
            </NativeSelect>
          </Field>
          <Field label="Reference (optional)" className="sm:col-span-2"><Input value={reference} onChange={(e) => setReference(e.target.value)} className="h-10" placeholder="Transfer / check number" /></Field>
        </div>
        {date !== loan.release_date && (
          <p className="rounded-lg bg-amber-50 p-3 text-xs text-amber-900">The agreement states a release date of {fmtDate(loan.release_date)}. The repayment schedule follows the signed agreement.</p>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button onClick={() => m.mutate()} disabled={m.isPending}>{m.isPending && <Spinner />} Release & activate</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export function AssignStaffDialog({ loan, open, onOpenChange, onDone }: Props) {
  const staff = useQuery({ queryKey: ['users'], queryFn: usersService.list, enabled: open })
  const [officer, setOfficer] = useState(loan.loan_officer_id ?? '')
  const [collector, setCollector] = useState(loan.collector_id ?? '')
  const m = useMutation({
    mutationFn: () => loansService.assignStaff(loan.id, officer || null, collector || null),
    onSuccess: () => { toast.success('Assignment updated'); onOpenChange(false); onDone() },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const active = staff.data?.filter((s) => s.is_active) ?? []
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader><DialogTitle>Assign staff</DialogTitle><DialogDescription>Collectors only see borrowers and loans assigned to them.</DialogDescription></DialogHeader>
        <Field label="Loan officer">
          <NativeSelect value={officer} onChange={(e) => setOfficer(e.target.value)}>
            <option value="">—</option>
            {active.map((s) => <option key={s.id} value={s.id}>{s.full_name} ({s.role?.replace('_', ' ')})</option>)}
          </NativeSelect>
        </Field>
        <Field label="Collector">
          <NativeSelect value={collector} onChange={(e) => setCollector(e.target.value)}>
            <option value="">Unassigned</option>
            {active.map((s) => <option key={s.id} value={s.id}>{s.full_name} ({s.role?.replace('_', ' ')})</option>)}
          </NativeSelect>
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button onClick={() => m.mutate()} disabled={m.isPending}>{m.isPending && <Spinner />} Save</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export function AdjustmentDialog({ loan, open, onOpenChange, onDone, installmentId }: Props & { installmentId?: string }) {
  const inst = useQuery({ queryKey: ['installments', loan.id, 'open'], queryFn: () => paymentsService.openInstallments(loan.id), enabled: open })
  const [iid, setIid] = useState(installmentId ?? '')
  const [type, setType] = useState('waive_penalty')
  const [amount, setAmount] = useState('')
  const [reason, setReason] = useState('')
  const selected = inst.data?.find((i) => i.id === (iid || inst.data?.[0]?.id))
  const max = selected ? (
    type === 'waive_penalty' ? toNumber(selected.penalty_outstanding)
      : type === 'waive_interest' ? toNumber(selected.interest_due) - toNumber(selected.interest_paid) - toNumber(selected.interest_waived)
        : type === 'waive_charges' ? toNumber(selected.charges_due) - toNumber(selected.charges_paid) - toNumber(selected.charges_waived)
          : Infinity) : 0
  const m = useMutation({
    mutationFn: () => loansService.adjust({ loan_id: loan.id, installment_id: selected!.id, type, amount: Number(amount), reason }),
    onSuccess: () => { toast.success('Adjustment posted to the ledger'); onOpenChange(false); onDone() },
    onError: (e) => toast.error(errorMessage(e)),
  })
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Waiver / adjustment</DialogTitle>
          <DialogDescription>Every adjustment creates a permanent ledger entry and an audit record. Confirmed transactions are never edited.</DialogDescription>
        </DialogHeader>
        <Field label="Installment">
          <NativeSelect value={selected?.id ?? ''} onChange={(e) => setIid(e.target.value)}>
            {inst.data?.map((i) => <option key={i.id} value={i.id}>#{i.installment_no} · due {fmtDate(i.due_date)} · remaining {peso(i.amount_remaining)}</option>)}
          </NativeSelect>
        </Field>
        <Field label="Adjustment type">
          <NativeSelect value={type} onChange={(e) => setType(e.target.value)}>
            <option value="waive_penalty">Waive penalty</option>
            <option value="waive_interest">Waive interest</option>
            <option value="waive_charges">Waive charges</option>
            <option value="add_charge">Add a charge</option>
          </NativeSelect>
        </Field>
        <Field label="Amount (₱)" hint={Number.isFinite(max) ? `Up to ${peso(max)}` : undefined}>
          <MoneyInput value={amount} onValueChange={setAmount} className="h-10" />
        </Field>
        <Field label="Reason" required><Textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button onClick={() => m.mutate()} disabled={m.isPending || !selected || !reason.trim() || !(Number(amount) > 0) || Number(amount) > max}>
            {m.isPending && <Spinner />} Post adjustment
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export function RescheduleDialog({ loan, open, onOpenChange, onDone }: Props) {
  const [method, setMethod] = useState<string>(loan.interest_method)
  const [rate, setRate] = useState(String(toNumber(loan.interest_rate)))
  const [period, setPeriod] = useState<string>(loan.rate_period)
  const [freq, setFreq] = useState<string>(loan.repayment_frequency)
  const [term, setTerm] = useState('6')
  const [firstDue, setFirstDue] = useState('')
  const [reason, setReason] = useState('')
  const inst = useQuery({ queryKey: ['installments', loan.id, 'open'], queryFn: () => paymentsService.openInstallments(loan.id), enabled: open })
  const today = todayManila()
  const dueInterest = inst.data?.filter((i) => i.due_date <= today).reduce((a, i) => a + toNumber(i.interest_due) - toNumber(i.interest_paid) - toNumber(i.interest_waived), 0) ?? 0
  const openCharges = inst.data?.reduce((a, i) => a + toNumber(i.charges_due) - toNumber(i.charges_paid) - toNumber(i.charges_waived), 0) ?? 0
  const newPrincipal = toNumber(loan.outstanding_principal) + dueInterest + openCharges
  // same inputs reschedule_loan uses: the loan's own day count / rounding, and its semi-monthly pay days
  const payDays = freq === 'semimonthly' && loan.pay_days?.length === 2 ? (loan.pay_days as [number, number]) : undefined
  const params = { principal: Math.round(newPrincipal * 100) / 100, interest_method: method as 'flat', interest_rate: Number(rate), rate_period: period, repayment_frequency: freq, term_count: Number(term), release_date: today, first_due_date: firstDue || null, processing_fee_type: 'none' as const, processing_fee_value: 0, other_charges: 0, charges_treatment: 'added' as const, penalty_enabled: false, day_count: loan.day_count, rounding: loan.rounding, pay_days: payDays }
  const preview = useQuery({
    queryKey: ['reschedule-preview', loan.id, JSON.stringify(params)],
    queryFn: () => loansService.preview(params),
    enabled: open && !!inst.data && Number(term) > 0 && Number(rate) >= 0 && newPrincipal > 0,
    retry: false,
  })
  const m = useMutation({
    mutationFn: () => loansService.reschedule(loan.id, { interest_method: method, interest_rate: Number(rate), rate_period: period, repayment_frequency: freq, term_count: Number(term), first_due_date: firstDue || null, pay_days: payDays ?? null, reason }),
    onSuccess: (r) => { toast.success(`Loan rescheduled (amendment v${r.version}). Generate the amendment agreement for signing.`); onOpenChange(false); onDone() },
    onError: (e) => toast.error(errorMessage(e)),
  })
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Reschedule loan (amendment)</DialogTitle>
          <DialogDescription>
            Outstanding principal plus interest already due and open charges are re-amortized. Unearned future interest is removed. Penalties must be settled or waived first.
            A new agreement version must then be generated and signed.
          </DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-3 gap-3 rounded-lg bg-slate-50 p-3 text-sm">
          <div><p className="text-xs text-muted-foreground">Outstanding principal</p><p className="font-semibold">{peso(loan.outstanding_principal)}</p></div>
          <div><p className="text-xs text-muted-foreground">+ Due interest & charges</p><p className="font-semibold">{peso(dueInterest + openCharges)}</p></div>
          <div><p className="text-xs text-muted-foreground">New principal</p><p className="font-semibold text-blue-700">{peso(newPrincipal)}</p></div>
        </div>
        {toNumber(loan.outstanding_penalties) > 0 && <p className="rounded-lg bg-red-50 p-3 text-xs text-red-700">Outstanding penalties of {peso(loan.outstanding_penalties)} must be paid or waived before rescheduling.</p>}
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="Method"><NativeSelect value={method} onChange={(e) => setMethod(e.target.value)}><option value="flat">Flat</option><option value="diminishing">Diminishing</option></NativeSelect></Field>
          <Field label="Rate (%)"><Input type="number" step="0.01" value={rate} onChange={(e) => setRate(e.target.value)} className="h-10" /></Field>
          <Field label="Rate period"><NativeSelect value={period} onChange={(e) => setPeriod(e.target.value)}>{RATE_PERIODS.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}</NativeSelect></Field>
          <Field label="Frequency"><NativeSelect value={freq} onChange={(e) => setFreq(e.target.value)}>{FREQUENCIES.map((f) => <option key={f.value} value={f.value}>{f.label}</option>)}</NativeSelect></Field>
          <Field label="New duration (installments)"><Input type="number" value={term} onChange={(e) => setTerm(e.target.value)} className="h-10" /></Field>
          <Field label="First due date"><Input type="date" value={firstDue} min={today} onChange={(e) => setFirstDue(e.target.value)} className="h-10" /></Field>
        </div>
        {preview.data && (
          <div className="space-y-3">
            <HeadlineFigures calc={preview.data} />
            <ScheduleTable rows={preview.data.schedule} maxHeight={220} />
          </div>
        )}
        {preview.error && <p className="text-sm text-red-600">{errorMessage(preview.error)}</p>}
        <Field label="Reason (documented amendment)" required><Textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button onClick={() => m.mutate()} disabled={m.isPending || !reason.trim() || !preview.data}>{m.isPending && <Spinner />} Reschedule</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export function SettlementQuoteCard({ loan }: { loan: LoanView }) {
  const [asOf, setAsOf] = useState(todayManila())
  const q = useQuery({ queryKey: ['loan', loan.id, 'quote', asOf], queryFn: () => loansService.settlementQuote(loan.id, asOf) })
  return (
    <div className="space-y-3">
      <div className="flex items-end gap-2">
        <Field label="Payoff as of" className="flex-1"><Input type="date" value={asOf} min={todayManila()} onChange={(e) => setAsOf(e.target.value)} className="h-10" /></Field>
      </div>
      {q.isLoading ? <Spinner /> : q.error ? <p className="text-sm text-red-600">{errorMessage(q.error)}</p> : q.data && (
        <div className="space-y-1.5 text-sm">
          {[['Principal', q.data.principal], ['Interest earned to date', q.data.interest], ['Charges', q.data.charges], ['Penalties', q.data.penalties]].map(([l, v]) => (
            <div key={l as string} className="flex justify-between"><span className="text-muted-foreground">{l}</span><span className="tabular">{peso(v)}</span></div>
          ))}
          <div className="flex justify-between border-t pt-1.5 text-base font-semibold"><span>Payoff amount</span><span className="tabular">{peso(q.data.total)}</span></div>
          <p className="rounded bg-green-50 p-2 text-xs text-green-800">Unearned interest rebated: {peso(q.data.interest_rebate)} (contract balance {peso(q.data.contract_remaining)}). Rule: {q.data.rule.replace('_', ' ')}.</p>
        </div>
      )}
    </div>
  )
}
