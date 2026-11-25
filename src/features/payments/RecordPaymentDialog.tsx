import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { toast } from 'sonner'
import {
  AlertTriangle, ArrowLeft, Banknote, CheckCircle2, Download, FileText, Info, Paperclip, Plus, Receipt, Search, X,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Field, LoanStatusBadge, NativeSelect, Spinner, useDebounced } from '@/components/common'
import { FileDrop } from '@/components/common/FileDrop'
import { useAuth } from '@/features/auth/AuthProvider'
import { loansService } from '@/services/loans'
import { paymentsService } from '@/services/payments'
import { usersService } from '@/services/org'
import { newIdempotencyKey } from '@/services/api'
import { PAYMENT_METHODS } from '@/lib/constants'
import { errorMessage } from '@/lib/errors'
import { fmtDate, peso, todayManila, toNumber } from '@/lib/format'
import { cn } from '@/lib/utils'
import type { InstallmentView, LoanView, PaymentResult } from '@/types/db'
import { useIsMobile } from './useIsMobile'

export type RecordPaymentDialogProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Pre-selected loan. When omitted the dialog lets the user search for a loan/borrower. */
  loanId?: string
  /** Pre-selected installment to allocate first */
  installmentId?: string
  defaultType?: 'regular' | 'settlement'
  onRecorded?: (result: PaymentResult) => void
}

export function RecordPaymentDialog(props: RecordPaymentDialogProps) {
  const { open, onOpenChange } = props
  const isMobile = useIsMobile()
  // Remount the body every time the dialog opens so each open gets ONE fresh idempotency key.
  const [session, setSession] = useState(0)
  const wasOpen = useRef(false)
  useEffect(() => {
    if (open && !wasOpen.current) setSession((s) => s + 1)
    wasOpen.current = open
  }, [open])

  const title = 'Record payment'
  const description = 'Payments are allocated server-side to penalties, charges, interest and principal per the loan’s allocation order.'
  const body = open ? <PaymentFlow key={session} {...props} /> : null

  if (isMobile) {
    return (
      <Sheet open={open} onOpenChange={onOpenChange}>
        <SheetContent side="bottom" className="h-[94dvh] gap-0 rounded-t-2xl p-0">
          <SheetHeader className="border-b px-4 py-3 text-left">
            <SheetTitle>{title}</SheetTitle>
            <SheetDescription className="text-xs">{description}</SheetDescription>
          </SheetHeader>
          <div className="flex-1 overflow-y-auto px-4 pb-6 pt-4">{body}</div>
        </SheetContent>
      </Sheet>
    )
  }
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[92dvh] gap-0 overflow-hidden p-0 sm:max-w-2xl">
        <DialogHeader className="border-b px-6 py-4">
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription className="text-xs">{description}</DialogDescription>
        </DialogHeader>
        <div className="max-h-[calc(92dvh-5rem)] overflow-y-auto px-6 pb-6 pt-4">{body}</div>
      </DialogContent>
    </Dialog>
  )
}

// ---------------------------------------------------------------------------

function PaymentFlow({ loanId: initialLoan, installmentId, defaultType, onRecorded, onOpenChange }: RecordPaymentDialogProps) {
  const [loanId, setLoanId] = useState<string | undefined>(initialLoan)
  const [result, setResult] = useState<PaymentResult | null>(null)
  const [round, setRound] = useState(0)

  if (result) {
    return (
      <SuccessView
        result={result}
        onClose={() => onOpenChange(false)}
        onAnother={() => {
          setResult(null)
          setRound((r) => r + 1)
          if (!initialLoan) setLoanId(undefined)
        }}
      />
    )
  }
  if (!loanId) return <LoanPicker onPick={setLoanId} />
  return (
    <PaymentForm
      key={`${loanId}-${round}`}
      loanId={loanId}
      installmentId={round === 0 ? installmentId : undefined}
      defaultType={defaultType}
      canChangeLoan={!initialLoan}
      onChangeLoan={() => setLoanId(undefined)}
      onDone={(r) => {
        setResult(r)
        onRecorded?.(r)
      }}
    />
  )
}

// ---------------------------------------------------------------------------
// Step 1: choose loan
// ---------------------------------------------------------------------------
function LoanPicker({ onPick }: { onPick: (id: string) => void }) {
  const [search, setSearch] = useState('')
  const term = useDebounced(search, 250)
  const q = useQuery({
    queryKey: ['loans', 'payable', term],
    queryFn: () => loansService.list({ statuses: ['active', 'defaulted'], search: term, pageSize: 12, order: 'next_due' }),
  })
  return (
    <div className="space-y-3">
      <div className="relative">
        <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input autoFocus value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search borrower, loan # or borrower code"
          className="h-12 pl-9 text-base sm:text-sm" />
      </div>
      {q.isLoading ? (
        <div className="flex items-center justify-center gap-2 py-10 text-sm text-muted-foreground"><Spinner /> Searching…</div>
      ) : q.error ? (
        <p className="rounded-lg bg-red-50 p-3 text-sm text-red-700">{errorMessage(q.error)}</p>
      ) : !q.data?.rows.length ? (
        <p className="py-10 text-center text-sm text-muted-foreground">No active loans found{term ? ` for “${term}”` : ''}.</p>
      ) : (
        <ul className="space-y-2">
          {q.data.rows.map((l) => (
            <li key={l.id}>
              <button onClick={() => onPick(l.id)}
                className="flex w-full items-center justify-between gap-3 rounded-xl border bg-white p-3 text-left transition hover:border-blue-300 hover:bg-blue-50/40 active:bg-blue-50">
                <div className="min-w-0">
                  <p className="truncate font-medium">{l.borrower_name}</p>
                  <p className="truncate text-xs text-muted-foreground">
                    {l.loan_number} · {l.borrower_code}
                    {l.next_due_date && <> · next due {fmtDate(l.next_due_date)}</>}
                  </p>
                  {toNumber(l.overdue_amount) > 0 && (
                    <p className="mt-0.5 text-xs font-medium text-red-600">{peso(l.overdue_amount)} overdue · {l.days_overdue} days</p>
                  )}
                </div>
                <div className="shrink-0 text-right">
                  <p className="tabular text-sm font-semibold">{peso(l.total_outstanding)}</p>
                  <p className="text-[11px] text-muted-foreground">outstanding</p>
                </div>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Step 2: payment form
// ---------------------------------------------------------------------------
const schema = z.object({
  payment_type: z.enum(['regular', 'settlement']),
  installment_id: z.string().optional(),
  amount: z.string().min(1, 'Enter the amount received')
    .refine((v) => Number(v) > 0, 'Amount must be greater than zero')
    .refine((v) => /^\d+(\.\d{1,2})?$/.test(v.trim()), 'Use at most 2 decimal places'),
  payment_date: z.string().min(1, 'Select the payment date').refine((v) => v <= todayManila(), 'Payment date cannot be in the future'),
  method: z.string().min(1),
  reference_no: z.string().max(80).optional(),
  notes: z.string().max(500).optional(),
  received_by: z.string().optional(),
}).superRefine((v, ctx) => {
  if (v.method !== 'cash' && !v.reference_no?.trim()) {
    ctx.addIssue({ code: 'custom', path: ['reference_no'], message: 'A transaction reference is required for non-cash payments' })
  }
})
type FormValues = z.infer<typeof schema>

const REF_PLACEHOLDER: Record<string, string> = {
  bank_transfer: 'Bank reference / transaction no.',
  e_wallet: 'GCash / Maya reference no.',
  check: 'Check number and bank',
  other: 'Reference no.',
  cash: 'Optional (e.g. collector slip no.)',
}

function PaymentForm({ loanId, installmentId, defaultType, canChangeLoan, onChangeLoan, onDone }: {
  loanId: string
  installmentId?: string
  defaultType?: 'regular' | 'settlement'
  canChangeLoan: boolean
  onChangeLoan: () => void
  onDone: (r: PaymentResult) => void
}) {
  const qc = useQueryClient()
  const { organization, can, access } = useAuth()
  // ONE idempotency key for this form instance — reused on every retry/double-click.
  const idemKey = useRef(newIdempotencyKey())
  const proofPath = useRef<string | null>(null)
  const [proof, setProof] = useState<File | null>(null)
  const [proofPreview, setProofPreview] = useState<string | null>(null)

  const loanQ = useQuery({ queryKey: ['loan', loanId], queryFn: () => loansService.get(loanId) })
  const instQ = useQuery({ queryKey: ['installments', 'open', loanId], queryFn: () => paymentsService.openInstallments(loanId) })
  const staffQ = useQuery({ queryKey: ['staff'], queryFn: usersService.list, enabled: can('collections.assign') })

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      payment_type: defaultType ?? 'regular',
      installment_id: installmentId ?? '',
      amount: '',
      payment_date: todayManila(),
      method: 'cash',
      reference_no: '',
      notes: '',
      received_by: access?.user_id ?? '',
    },
  })
  const [type, paymentDate, method, targetId, amount] = useWatch({ control: form.control, name: ['payment_type', 'payment_date', 'method', 'installment_id', 'amount'] })
  const loan = loanQ.data
  const installments = useMemo(() => instQ.data ?? [], [instQ.data])

  // projected (not yet posted) penalties as of the payment date — the server posts them before allocating
  const overdueIds = installments.filter((i) => i.due_date < (paymentDate || todayManila())).map((i) => i.id).slice(0, 24)
  const penQ = useQuery({
    queryKey: ['penalties', loanId, paymentDate, overdueIds.join(',')],
    enabled: !!loan?.penalty_enabled && overdueIds.length > 0 && !!paymentDate,
    queryFn: async () => {
      const entries = await Promise.all(overdueIds.map(async (id) => [id, toNumber(await paymentsService.projectedPenalty(id, paymentDate))] as const))
      return Object.fromEntries(entries) as Record<string, number>
    },
  })
  const projected = useMemo(() => penQ.data ?? {}, [penQ.data])
  const projectedTotal = Object.values(projected).reduce((a, b) => a + b, 0)

  const settleQ = useQuery({
    queryKey: ['settlement', loanId, paymentDate],
    enabled: type === 'settlement' && !!paymentDate && paymentDate <= todayManila(),
    queryFn: () => loansService.settlementQuote(loanId, paymentDate),
  })

  // lock the amount to the payoff quote in settlement mode
  useEffect(() => {
    if (type === 'settlement' && settleQ.data) {
      form.setValue('amount', toNumber(settleQ.data.total).toFixed(2), { shouldValidate: true })
      form.setValue('installment_id', '')
    }
  }, [type, settleQ.data, form])

  // default target: the requested installment, otherwise the oldest open one
  useEffect(() => {
    if (!installmentId && installments.length && !form.getValues('installment_id') && type === 'regular') {
      form.setValue('installment_id', installments[0].id)
    }
  }, [installments, installmentId, form, type])

  const remainingOf = (i: InstallmentView) => toNumber(i.amount_remaining) + (projected[i.id] ?? 0)
  const target = installments.find((i) => i.id === targetId)
  const overdueTotal = installments.filter((i) => i.due_date < (paymentDate || todayManila())).reduce((a, i) => a + remainingOf(i), 0)
  const fullBalance = toNumber(loan?.total_outstanding) + projectedTotal
  const amt = Number(amount) || 0
  const exceeds = type === 'regular' && amt > fullBalance + 0.004

  const chips: { label: string; value: number }[] = []
  if (target) chips.push({ label: `Installment #${target.installment_no}`, value: remainingOf(target) })
  if (overdueTotal > 0 && (!target || Math.abs(overdueTotal - remainingOf(target)) > 0.004)) chips.push({ label: 'All overdue', value: overdueTotal })
  if (fullBalance > 0) chips.push({ label: 'Full contract balance', value: fullBalance })

  const mutation = useMutation({
    mutationFn: async (v: FormValues) => {
      if (proof && !proofPath.current && organization) {
        proofPath.current = await paymentsService.uploadProof(organization.id, loanId, proof)
      }
      return paymentsService.record({
        loan_id: loanId,
        amount: Number(Number(v.amount).toFixed(2)),
        payment_date: v.payment_date,
        method: v.method,
        reference_no: v.reference_no?.trim() || null,
        notes: v.notes?.trim() || null,
        proof_path: proofPath.current,
        payment_type: v.payment_type,
        installment_id: v.payment_type === 'regular' ? v.installment_id || null : null,
        received_by: v.received_by || null,
        idempotency_key: idemKey.current,
      })
    },
    onSuccess: (r) => {
      for (const key of ['loan', 'loans', 'payments', 'installments', 'dashboard', 'collections', 'borrower', 'report', 'penalties', 'settlement']) {
        void qc.invalidateQueries({ queryKey: [key] })
      }
      if (r.duplicate) toast.info('This payment was already recorded — showing the original receipt.')
      else toast.success(`Payment ${r.payment_number} recorded · Receipt ${r.receipt_number}`)
      onDone(r)
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  const onSubmit = form.handleSubmit((v) => {
    if (mutation.isPending) return
    if (v.payment_type === 'settlement' && !settleQ.data) return toast.error('The settlement quote is still loading')
    mutation.mutate(v)
  })

  if (loanQ.isLoading) return <div className="flex items-center justify-center gap-2 py-16 text-sm text-muted-foreground"><Spinner /> Loading loan…</div>
  if (loanQ.error || !loan) return <p className="rounded-lg bg-red-50 p-3 text-sm text-red-700">{errorMessage(loanQ.error, 'Loan not found')}</p>

  const payable = loan.status === 'active' || loan.status === 'defaulted'
  const e = form.formState.errors
  const collectors = (staffQ.data ?? []).filter((s) => s.is_active)

  return (
    <form onSubmit={onSubmit} className="space-y-5">
      <LoanHeader loan={loan} projectedPenalty={projectedTotal} canChange={canChangeLoan} onChange={onChangeLoan} />

      {!payable ? (
        <div className="flex gap-2 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
          <AlertTriangle className="mt-0.5 size-4 shrink-0" /> Payments can only be recorded on active loans. This loan is <LoanStatusBadge status={loan.status} />.
        </div>
      ) : (
        <>
          {/* payment type */}
          <div className="grid grid-cols-2 gap-2" role="radiogroup" aria-label="Payment type">
            {(['regular', 'settlement'] as const).map((t) => (
              <button key={t} type="button" role="radio" aria-checked={type === t}
                onClick={() => {
                  form.setValue('payment_type', t)
                  if (t === 'regular') form.setValue('amount', '')
                }}
                className={cn('rounded-xl border-2 p-3 text-left transition',
                  type === t ? 'border-blue-600 bg-blue-50/60' : 'border-slate-200 bg-white hover:border-slate-300')}>
                <p className="text-sm font-semibold">{t === 'regular' ? 'Regular payment' : 'Early settlement'}</p>
                <p className="mt-0.5 text-xs text-muted-foreground">{t === 'regular' ? 'Installments, partial or advance payment' : 'Pay off the loan today — no unearned interest'}</p>
              </button>
            ))}
          </div>

          {type === 'settlement' ? (
            <SettlementQuoteBox loading={settleQ.isLoading} error={settleQ.error} quote={settleQ.data} paymentDate={paymentDate} />
          ) : (
            <Field label="Apply first to installment" hint="Any excess continues to the next installments (advance payment).">
              {instQ.isLoading ? (
                <div className="h-12 animate-pulse rounded-md bg-muted" />
              ) : (
                <NativeSelect className="h-12 text-base sm:text-sm" {...form.register('installment_id')}>
                  <option value="">Oldest unpaid installment first</option>
                  {installments.map((i) => {
                    const overdue = i.due_date < (paymentDate || todayManila())
                    const pen = projected[i.id] ?? 0
                    return (
                      <option key={i.id} value={i.id}>
                        #{i.installment_no} · due {fmtDate(i.due_date)} · {peso(remainingOf(i))}{overdue ? ' · OVERDUE' : ''}{pen > 0 ? ` (incl. ${peso(pen)} penalty)` : ''}{i.status === 'partially_paid' ? ' · partial' : ''}
                      </option>
                    )
                  })}
                </NativeSelect>
              )}
            </Field>
          )}

          {/* amount */}
          <Field label="Amount received (₱)" required error={e.amount?.message}>
            <div className="relative">
              <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-lg font-semibold text-muted-foreground">₱</span>
              <Input inputMode="decimal" autoComplete="off" readOnly={type === 'settlement'} placeholder="0.00"
                className={cn('tabular h-14 pl-8 text-2xl font-semibold', type === 'settlement' && 'bg-slate-50')}
                {...form.register('amount', { setValueAs: (v: string) => String(v ?? '').replace(/[,\s₱]/g, '') })} />
            </div>
          </Field>
          {type === 'regular' && chips.length > 0 && (
            <div className="-mt-2 flex flex-wrap gap-2">
              {chips.map((c) => (
                <button key={c.label} type="button" onClick={() => form.setValue('amount', c.value.toFixed(2), { shouldValidate: true })}
                  className="rounded-full border bg-white px-3 py-2 text-xs font-medium transition hover:border-blue-300 hover:bg-blue-50 active:bg-blue-100">
                  {c.label} · <span className="tabular">{peso(c.value)}</span>
                </button>
              ))}
            </div>
          )}
          {exceeds && (
            <p className="-mt-2 flex items-start gap-1.5 rounded-md bg-red-50 px-3 py-2 text-xs text-red-700">
              <AlertTriangle className="mt-0.5 size-3.5 shrink-0" /> The amount exceeds the total balance of {peso(fullBalance)}.
            </p>
          )}
          {type === 'regular' && amt > 0 && !exceeds && Math.abs(amt - fullBalance) < 0.005 && (
            <p className="-mt-2 flex items-start gap-1.5 rounded-md bg-blue-50 px-3 py-2 text-xs text-blue-800">
              <Info className="mt-0.5 size-3.5 shrink-0" /> This pays every remaining installment including future interest. If the borrower is paying off early, use <b>Early settlement</b> instead so unearned interest is not charged.
            </p>
          )}

          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Payment date" required error={e.payment_date?.message}>
              <Input type="date" max={todayManila()} className="h-12" {...form.register('payment_date')} />
            </Field>
            <Field label="Payment method" required>
              <NativeSelect className="h-12" {...form.register('method')}>
                {PAYMENT_METHODS.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
              </NativeSelect>
            </Field>
            <Field label="Transaction reference" required={method !== 'cash'} error={e.reference_no?.message} className="sm:col-span-2">
              <Input className="h-12" autoComplete="off" placeholder={REF_PLACEHOLDER[method] ?? 'Reference no.'} {...form.register('reference_no')} />
            </Field>
            {can('collections.assign') && collectors.length > 0 && (
              <Field label="Received by" className="sm:col-span-2">
                <NativeSelect className="h-12" {...form.register('received_by')}>
                  {collectors.map((s) => <option key={s.id} value={s.id}>{s.full_name}{s.id === access?.user_id ? ' (me)' : ''}</option>)}
                </NativeSelect>
              </Field>
            )}
          </div>

          <Field label="Proof of payment" hint="Photo of the deposit slip, e-wallet screenshot or signed collection slip (optional)">
            {proof ? (
              <div className="flex items-center gap-3 rounded-lg border bg-slate-50 p-3">
                {proofPreview ? <img src={proofPreview} alt="" className="size-14 rounded object-cover" /> : <FileText className="size-8 text-slate-400" />}
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{proof.name}</p>
                  <p className="text-xs text-muted-foreground">{(proof.size / 1024).toFixed(0)} KB</p>
                </div>
                <Button type="button" variant="ghost" size="icon" onClick={() => { setProof(null); setProofPreview(null); proofPath.current = null }} aria-label="Remove file"><X /></Button>
              </div>
            ) : (
              <FileDrop accept="image/*,application/pdf" label={<span className="inline-flex items-center gap-1.5"><Paperclip className="size-3.5" /> Attach photo or PDF</span>}
                hint="Max 10 MB" onFile={(f) => {
                  if (f.size > 10 * 1024 * 1024) return toast.error('File is larger than 10 MB')
                  setProof(f)
                  proofPath.current = null
                  setProofPreview(f.type.startsWith('image/') ? URL.createObjectURL(f) : null)
                }} />
            )}
          </Field>

          <Field label="Notes">
            <Textarea rows={2} placeholder="Optional remarks (e.g. paid at branch, promise for balance)" {...form.register('notes')} />
          </Field>

          <div className="sticky bottom-0 -mx-1 flex flex-col gap-2 border-t bg-background/95 px-1 pt-3 backdrop-blur sm:flex-row sm:items-center sm:justify-between">
            <p className="text-xs text-muted-foreground">
              {amt > 0 ? <>Recording <b className="tabular text-foreground">{peso(amt)}</b> · {fmtDate(paymentDate)}</> : 'Duplicate submissions are blocked automatically.'}
            </p>
            <Button type="submit" className="h-12 w-full text-base sm:w-auto sm:px-8"
              disabled={mutation.isPending || exceeds || (type === 'settlement' && !settleQ.data)}>
              {mutation.isPending ? <Spinner /> : <Banknote />} {mutation.isPending ? 'Posting…' : type === 'settlement' ? 'Post settlement' : 'Post payment'}
            </Button>
          </div>
        </>
      )}
    </form>
  )
}

function LoanHeader({ loan, projectedPenalty, canChange, onChange }: { loan: LoanView; projectedPenalty: number; canChange: boolean; onChange: () => void }) {
  const items: [string, number][] = [
    ['Principal', toNumber(loan.outstanding_principal)],
    ['Interest', toNumber(loan.outstanding_interest)],
    ['Charges', toNumber(loan.outstanding_charges)],
    ['Penalties', toNumber(loan.outstanding_penalties) + projectedPenalty],
  ]
  return (
    <div className="rounded-xl border bg-slate-50/70 p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate font-semibold">{loan.borrower_name}</p>
          <p className="truncate text-xs text-muted-foreground">{loan.loan_number} · {loan.borrower_code} · {loan.borrower_mobile}</p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <LoanStatusBadge status={loan.status} />
          {canChange && (
            <Button type="button" variant="ghost" size="sm" className="h-8" onClick={onChange}><ArrowLeft /> Change</Button>
          )}
        </div>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-5">
        {items.map(([label, v]) => (
          <div key={label} className="rounded-lg bg-white px-2.5 py-2 ring-1 ring-slate-200">
            <p className="text-[11px] text-muted-foreground">{label}</p>
            <p className="tabular text-sm font-semibold">{peso(v)}</p>
          </div>
        ))}
        <div className="col-span-2 rounded-lg bg-[#142B4A] px-2.5 py-2 text-white sm:col-span-1">
          <p className="text-[11px] text-white/70">Total balance</p>
          <p className="tabular text-sm font-semibold">{peso(toNumber(loan.total_outstanding) + projectedPenalty)}</p>
        </div>
      </div>
      {projectedPenalty > 0 && (
        <p className="mt-2 text-[11px] text-amber-800">Includes {peso(projectedPenalty)} of late penalties accrued up to the payment date (posted automatically when the payment is saved).</p>
      )}
      {toNumber(loan.overdue_amount) > 0 && (
        <p className="mt-1 text-[11px] font-medium text-red-600">{loan.overdue_count} installment(s) overdue · {peso(loan.overdue_amount)} · {loan.days_overdue} days</p>
      )}
    </div>
  )
}

function SettlementQuoteBox({ loading, error, quote, paymentDate }: {
  loading: boolean; error: unknown; quote?: import('@/types/db').SettlementQuote; paymentDate: string
}) {
  if (loading) return <div className="flex items-center gap-2 rounded-xl border p-4 text-sm text-muted-foreground"><Spinner /> Computing the payoff amount…</div>
  if (error) return <p className="rounded-lg bg-red-50 p-3 text-sm text-red-700">{errorMessage(error)}</p>
  if (!quote) return null
  const rows: [string, unknown][] = [
    ['Outstanding principal', quote.principal],
    ['Interest earned to date', quote.interest],
    ['Unpaid charges', quote.charges],
    ['Penalties', quote.penalties],
  ]
  return (
    <div className="rounded-xl border border-teal-200 bg-teal-50/50 p-4">
      <div className="flex items-center justify-between">
        <p className="text-sm font-semibold">Payoff as of {fmtDate(paymentDate)}</p>
        <span className="rounded-full bg-white px-2 py-0.5 text-[11px] font-medium text-teal-700 ring-1 ring-teal-200">
          {quote.rule === 'prorated' ? 'Interest prorated to date' : quote.rule === 'full_period' ? 'Current period interest' : 'Due interest only'}
        </span>
      </div>
      <dl className="mt-3 space-y-1.5 text-sm">
        {rows.map(([l, v]) => (
          <div key={l} className="flex justify-between"><dt className="text-muted-foreground">{l}</dt><dd className="tabular font-medium">{peso(v)}</dd></div>
        ))}
        <div className="flex justify-between border-t pt-1.5 text-base font-semibold"><dt>Settlement amount</dt><dd className="tabular">{peso(quote.total)}</dd></div>
      </dl>
      <div className="mt-3 flex items-start gap-2 rounded-lg bg-white p-2.5 text-xs text-teal-900 ring-1 ring-teal-100">
        <CheckCircle2 className="mt-0.5 size-3.5 shrink-0 text-teal-600" />
        <span>Unearned future interest of <b>{peso(quote.interest_rebate)}</b> is rebated. Remaining contract balance was {peso(quote.contract_remaining)}.</span>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Step 3: success
// ---------------------------------------------------------------------------
function SuccessView({ result, onClose, onAnother }: { result: PaymentResult; onClose: () => void; onAnother: () => void }) {
  const navigate = useNavigate()
  const [downloading, setDownloading] = useState(false)
  const lines: [string, unknown][] = [
    ['Penalties', result.penalty_applied],
    ['Charges', result.charges_applied],
    ['Interest', result.interest_applied],
    ['Principal', result.principal_applied],
  ]
  const download = async () => {
    setDownloading(true)
    try {
      const { url } = await paymentsService.receiptPdf(result.payment_id, true)
      window.open(url, '_blank', 'noopener')
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setDownloading(false)
    }
  }
  return (
    <div className="space-y-5">
      <div className="flex flex-col items-center text-center">
        <span className="grid size-14 place-items-center rounded-full bg-green-100 text-green-600"><CheckCircle2 className="size-8" /></span>
        <p className="mt-3 text-lg font-semibold">{result.duplicate ? 'Payment already recorded' : 'Payment posted'}</p>
        <p className="tabular mt-1 text-3xl font-semibold tracking-tight">{peso(result.amount)}</p>
        <p className="mt-1 text-sm text-muted-foreground">
          {result.payment_number} · Receipt <b className="text-foreground">{result.receipt_number}</b>
        </p>
        {result.duplicate && (
          <p className="mt-2 rounded-md bg-blue-50 px-3 py-1.5 text-xs text-blue-800">A payment with this submission ID already exists — no duplicate was created.</p>
        )}
      </div>

      <div className="rounded-xl border bg-white">
        <p className="border-b px-4 py-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Allocation</p>
        <dl className="space-y-1.5 px-4 py-3 text-sm">
          {lines.map(([l, v]) => (
            <div key={l} className={cn('flex justify-between', toNumber(v) === 0 && 'text-muted-foreground')}>
              <dt>{l}</dt><dd className="tabular">{peso(v)}</dd>
            </div>
          ))}
          {toNumber(result.interest_rebated) > 0 && (
            <div className="flex justify-between text-teal-700"><dt>Unearned interest rebated</dt><dd className="tabular">{peso(result.interest_rebated)}</dd></div>
          )}
        </dl>
        <div className="flex items-center justify-between rounded-b-xl bg-slate-50 px-4 py-3">
          <span className="text-sm font-medium">Remaining balance</span>
          <span className="tabular text-base font-semibold">{peso(result.total_outstanding)}</span>
        </div>
      </div>
      {result.loan_status === 'completed' && (
        <p className="rounded-lg bg-green-50 p-3 text-center text-sm font-medium text-green-800">🎉 The loan is fully paid and marked completed.</p>
      )}

      <div className="grid gap-2 sm:grid-cols-2">
        <ActionButton icon={<Receipt />} onClick={() => { onClose(); navigate(`/payments/${result.payment_id}`) }}>View / print receipt</ActionButton>
        <ActionButton icon={downloading ? <Spinner /> : <Download />} onClick={() => void download()} disabled={downloading}>Download PDF</ActionButton>
        <ActionButton icon={<Plus />} onClick={onAnother}>Record another</ActionButton>
        <Button className="h-12" onClick={onClose}>Done</Button>
      </div>
    </div>
  )
}

function ActionButton({ children, icon, onClick, disabled }: { children: ReactNode; icon: ReactNode; onClick: () => void; disabled?: boolean }) {
  return <Button variant="outline" className="h-12" onClick={onClick} disabled={disabled}>{icon}{children}</Button>
}
