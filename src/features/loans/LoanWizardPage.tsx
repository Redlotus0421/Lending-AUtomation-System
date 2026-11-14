import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  ArrowLeft, ArrowRight, Calculator, Check, CheckCircle2, ChevronDown, FileSignature, History, Pencil, RefreshCw, RotateCcw, Search, UserPlus, X,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Switch } from '@/components/ui/switch'
import { Checkbox } from '@/components/ui/checkbox'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import {
  EmptyState, ErrorState, Field, LoanStatusBadge, NativeSelect, PageHeader, PageLoader, Panel, Spinner, useDebounced,
} from '@/components/common'
import { useAuth } from '@/features/auth/AuthProvider'
import { borrowersService } from '@/services/borrowers'
import { loansService, productsService, type LoanInput } from '@/services/loans'
import { agreementsService, templatesService } from '@/services/agreements'
import { usersService } from '@/services/org'
import { FREQUENCIES, PENALTY_TYPES, RATE_PERIODS } from '@/lib/constants'
import { addDays, addMonths, fmtDate, initials, peso, phMobile, todayManila, toNumber } from '@/lib/format'
import { errorMessage } from '@/lib/errors'
import { cn } from '@/lib/utils'
import type { Borrower, LoanProduct } from '@/types/db'
import { Disclosures, HeadlineFigures, ScheduleTable, SummaryGrid } from './LoanPreview'
import { clearDraft, loadDraft, saveDraft, useAutosave, useWizardSteps } from './wizard'

const num = (msg: string) => z.number({ error: msg }).refine((v) => Number.isFinite(v), msg)

const schema = z.object({
  product_id: z.string(),
  principal: num('Enter the loan amount').pipe(z.number().positive('Loan amount must be greater than zero').max(999_999_999, 'Amount is too large')),
  interest_method: z.enum(['flat', 'diminishing']),
  interest_rate: num('Enter the interest rate').pipe(z.number().min(0, 'Rate cannot be negative').max(100, 'Rate is too high')),
  rate_period: z.enum(['daily', 'weekly', 'monthly', 'yearly']),
  repayment_frequency: z.enum(['daily', 'weekly', 'monthly']),
  term_count: num('Enter the duration').pipe(z.number().int('Use whole numbers').min(1, 'At least 1').max(3660, 'Too long')),
  release_date: z.string().min(10, 'Select the release date'),
  first_due_date: z.string(),
  processing_fee_type: z.enum(['none', 'fixed', 'percent']),
  processing_fee_value: num('Enter a fee value').pipe(z.number().min(0)),
  other_charges: num('Enter charges (0 if none)').pipe(z.number().min(0)),
  other_charges_description: z.string(),
  charges_treatment: z.enum(['added', 'deducted']),
  penalty_enabled: z.boolean(),
  penalty_type: z.string(),
  penalty_rate: num('Enter the penalty rate').pipe(z.number().min(0)),
  penalty_grace_days: num('Enter grace days').pipe(z.number().int().min(0).max(365)),
  day_count: z.enum(['30/360', 'actual/365', 'actual/actual']),
  rounding: z.enum(['centavo', 'peso']),
  purpose: z.string(),
  notes: z.string(),
  collector_id: z.string(),
  agreement_template_id: z.string(),
  signing_order: z.enum(['borrower_first', 'lender_first', 'any']),
  witness_count: num('Select witnesses').pipe(z.number().int().min(0).max(2)),
}).superRefine((v, ctx) => {
  if (v.first_due_date && v.first_due_date <= v.release_date) {
    ctx.addIssue({ code: 'custom', path: ['first_due_date'], message: 'Must be after the release date' })
  }
  if (v.processing_fee_type === 'percent' && v.processing_fee_value > 50) {
    ctx.addIssue({ code: 'custom', path: ['processing_fee_value'], message: 'Fee percentage looks too high' })
  }
  if (v.penalty_enabled && !v.penalty_type) {
    ctx.addIssue({ code: 'custom', path: ['penalty_type'], message: 'Choose a penalty type' })
  }
})
type FormValues = z.infer<typeof schema>

function toPayload(v: FormValues): LoanInput {
  return {
    product_id: v.product_id || null,
    principal: v.principal,
    interest_method: v.interest_method,
    interest_rate: v.interest_rate,
    rate_period: v.rate_period,
    repayment_frequency: v.repayment_frequency,
    term_count: v.term_count,
    release_date: v.release_date,
    first_due_date: v.first_due_date || null,
    processing_fee_type: v.processing_fee_type,
    processing_fee_value: v.processing_fee_type === 'none' ? 0 : v.processing_fee_value,
    other_charges: v.other_charges,
    other_charges_description: v.other_charges_description || null,
    charges_treatment: v.charges_treatment,
    day_count: v.day_count,
    rounding: v.rounding,
    penalty_enabled: v.penalty_enabled,
    penalty_type: v.penalty_enabled ? v.penalty_type : null,
    penalty_rate: v.penalty_enabled ? v.penalty_rate : 0,
    penalty_grace_days: v.penalty_enabled ? v.penalty_grace_days : 0,
    purpose: v.purpose || null,
    notes: v.notes || null,
    collector_id: v.collector_id || null,
    agreement_template_id: v.agreement_template_id || null,
    signing_order: v.signing_order,
    witness_count: v.witness_count,
  }
}

const STEPS = ['Borrower', 'Loan details', 'Preview', 'Agreement']

type CompleteDraft = { v: 1; step: number; values: FormValues; borrowerId: string | null; approveNow: boolean }

/** JSON turns NaN into null; give number fields back their "empty" value so validation messages show. */
function reviveValues(saved: Partial<FormValues>, defaults: FormValues): FormValues {
  const out = { ...defaults, ...saved } as Record<string, unknown>
  for (const [k, d] of Object.entries(defaults)) if (typeof d === 'number' && out[k] === null) out[k] = NaN
  return out as FormValues
}

export default function LoanWizardPage() {
  const { id: editId } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { settings, can, access } = useAuth()
  // The step lives in the URL so the browser Back / Forward buttons move between steps without
  // losing anything typed; new-loan entries are also autosaved for this tab (see ./wizard).
  const { step: stepParam, go: goStep, back: backStep, params } = useWizardSteps<string>(editId ? '1' : '0')
  const step = Math.min(3, Math.max(editId ? 1 : 0, Math.trunc(Number(stepParam)) || 0))
  const setStep = (n: number) => goStep(String(n))
  const backTo = (n: number) => backStep(String(n))
  const draftKey = `complete:${access?.user_id ?? 'anon'}`
  const [initial] = useState<CompleteDraft | null>(() => {
    if (editId) return null
    const d = loadDraft<CompleteDraft>(draftKey)
    if (!d || d.v !== 1) return null
    const linked = params.get('borrower')
    if (linked && d.borrowerId && d.borrowerId !== linked && !params.get('step')) {
      clearDraft(draftKey)
      return null
    }
    return d
  })
  const [restored, setRestored] = useState(!!initial && initial.step > 0)
  const [saved, setSaved] = useState(false)
  const [borrower, setBorrower] = useState<Borrower | null>(null)
  const today = todayManila()

  const editLoan = useQuery({ queryKey: ['loan', editId, 'raw'], queryFn: () => loansService.raw(editId!), enabled: !!editId })
  const products = useQuery({ queryKey: ['products', 'active'], queryFn: () => productsService.list(true) })
  const templates = useQuery({ queryKey: ['templates'], queryFn: templatesService.list, enabled: can('agreements.generate') || can('templates.manage') })
  const staff = useQuery({ queryKey: ['users'], queryFn: usersService.list })

  const defaults: FormValues = useMemo(() => ({
    product_id: '',
    principal: NaN,
    interest_method: settings?.interest.default_method ?? 'flat',
    interest_rate: settings?.interest.default_rate ?? 2,
    rate_period: settings?.interest.default_rate_period ?? 'monthly',
    repayment_frequency: settings?.repayment.default_frequency ?? 'monthly',
    term_count: 12,
    release_date: today,
    first_due_date: '',
    processing_fee_type: 'none',
    processing_fee_value: 0,
    other_charges: 0,
    other_charges_description: '',
    charges_treatment: settings?.repayment.charges_treatment ?? 'deducted',
    penalty_enabled: settings?.penalty.enabled ?? true,
    penalty_type: settings?.penalty.type ?? 'percent_monthly',
    penalty_rate: settings?.penalty.rate ?? 3,
    penalty_grace_days: settings?.penalty.grace_days ?? 3,
    day_count: (settings?.interest.day_count as FormValues['day_count']) ?? '30/360',
    rounding: (settings?.interest.rounding as FormValues['rounding']) ?? 'centavo',
    purpose: '',
    notes: '',
    collector_id: '',
    agreement_template_id: '',
    signing_order: settings?.agreement.signing_order ?? 'borrower_first',
    witness_count: settings?.agreement.witness_count ?? 0,
  }), [settings, today])

  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: initial ? reviveValues(initial.values, defaults) : defaults, mode: 'onChange' })
  const { register, setValue, control, formState: { errors } } = form
  const values = useWatch({ control }) as FormValues

  // preselect borrower (?borrower=) or load from the loan being edited
  const preselectId = editLoan.data?.borrower_id ?? params.get('borrower') ?? initial?.borrowerId ?? null
  const preselected = useQuery({ queryKey: ['borrower', preselectId], queryFn: () => borrowersService.get(preselectId!), enabled: !!preselectId })
  useEffect(() => {
    if (preselected.data && !borrower) {
      setBorrower(preselected.data)
      if (!editId && !params.get('step') && !initial?.step && params.get('borrower')) goStep('1', { replace: true })
    }
  }, [preselected.data, borrower, editId]) // eslint-disable-line react-hooks/exhaustive-deps

  // resume a saved draft on the step where it was left
  useEffect(() => {
    if (!params.get('step') && initial && initial.step > 0) goStep(String(initial.step), { replace: true })
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // prefill when editing
  useEffect(() => {
    const l = editLoan.data
    if (!l) return
    form.reset({
      ...defaults,
      product_id: l.product_id ?? '',
      principal: toNumber(l.principal),
      interest_method: l.interest_method,
      interest_rate: toNumber(l.interest_rate),
      rate_period: l.rate_period,
      repayment_frequency: l.repayment_frequency,
      term_count: l.term_count,
      release_date: l.release_date,
      first_due_date: l.first_due_date,
      processing_fee_type: l.processing_fee_type as FormValues['processing_fee_type'],
      processing_fee_value: toNumber(l.processing_fee_value),
      other_charges: toNumber(l.other_charges),
      other_charges_description: l.other_charges_description ?? '',
      charges_treatment: l.charges_treatment,
      penalty_enabled: l.penalty_enabled,
      penalty_type: l.penalty_type ?? 'percent_monthly',
      penalty_rate: toNumber(l.penalty_rate),
      penalty_grace_days: l.penalty_grace_days,
      day_count: l.day_count as FormValues['day_count'],
      rounding: l.rounding as FormValues['rounding'],
      purpose: l.purpose ?? '',
      notes: l.notes ?? '',
      collector_id: l.collector_id ?? '',
      agreement_template_id: l.agreement_template_id ?? '',
      signing_order: l.signing_order,
      witness_count: l.witness_count,
    })
  }, [editLoan.data]) // eslint-disable-line react-hooks/exhaustive-deps

  const product = products.data?.find((p) => p.id === values.product_id)
  const applyProduct = (p: LoanProduct | undefined) => {
    setValue('product_id', p?.id ?? '', { shouldValidate: true })
    if (!p) return
    const opts = { shouldValidate: true, shouldDirty: true }
    setValue('interest_method', p.interest_method, opts)
    setValue('interest_rate', toNumber(p.interest_rate), opts)
    setValue('rate_period', p.rate_period, opts)
    setValue('repayment_frequency', p.repayment_frequency, opts)
    if (p.default_term) setValue('term_count', p.default_term, opts)
    setValue('processing_fee_type', p.processing_fee_type, opts)
    setValue('processing_fee_value', toNumber(p.processing_fee_value), opts)
    setValue('charges_treatment', p.charges_treatment, opts)
    setValue('day_count', p.day_count, opts)
    setValue('penalty_enabled', p.penalty_enabled, opts)
    if (p.penalty_enabled) {
      setValue('penalty_type', p.penalty_type ?? 'percent_monthly', opts)
      setValue('penalty_rate', toNumber(p.penalty_rate), opts)
      setValue('penalty_grace_days', p.penalty_grace_days, opts)
    }
    if (p.agreement_template_id) setValue('agreement_template_id', p.agreement_template_id, opts)
    setValue('first_due_date', '', opts)
  }

  // ---- live server-side preview ----
  const parsed = schema.safeParse(values)
  const payload = parsed.success ? toPayload(parsed.data) : null
  const debounced = useDebounced(payload ? JSON.stringify(payload) : null, 450)
  const preview = useQuery({
    queryKey: ['loan-preview', debounced],
    queryFn: () => loansService.preview(JSON.parse(debounced!)),
    enabled: !!debounced,
    placeholderData: keepPreviousData,
    retry: false,
  })
  const previewFresh = !!payload && debounced === JSON.stringify(payload) && !preview.isFetching && preview.isSuccess

  const unit = FREQUENCIES.find((f) => f.value === values.repayment_frequency)?.unit ?? 'installments'
  const autoFirstDue = values.release_date
    ? values.repayment_frequency === 'monthly' ? addMonths(values.release_date, 1)
      : addDays(values.release_date, values.repayment_frequency === 'weekly' ? 7 : 1)
    : ''

  const save = useMutation({
    mutationFn: async (mode: 'draft' | 'submit' | 'agreement') => {
      const v = schema.parse(form.getValues())
      const p: LoanInput = { ...toPayload(v), borrower_id: borrower!.id, submit: mode !== 'draft' }
      const loan = editId ? await loansService.update(editId, p) : await loansService.create(p)
      if (approveNow && mode !== 'draft' && can('loans.approve')) {
        await loansService.approve(loan.id, 'Approved at loan creation')
      }
      if (mode === 'agreement') {
        try {
          await agreementsService.generate(loan.id)
        } catch (e) {
          toast.error(`Loan saved, but the agreement could not be generated: ${errorMessage(e)}`)
        }
      }
      return loan
    },
    onSuccess: (loan, mode) => {
      setSaved(true)
      clearDraft(draftKey)
      void qc.invalidateQueries({ queryKey: ['loans'] })
      void qc.invalidateQueries({ queryKey: ['loan', loan.id] })
      void qc.invalidateQueries({ queryKey: ['dashboard'] })
      toast.success(mode === 'draft' ? `Draft ${loan.loan_number} saved` : mode === 'agreement' ? `Loan ${loan.loan_number} created and agreement generated` : `Loan ${loan.loan_number} submitted for review`)
      navigate(`/loans/${loan.id}${mode === 'agreement' ? '?tab=agreement' : ''}`)
    },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const [approveNow, setApproveNow] = useState(initial?.approveNow ?? false)

  // autosave new-loan entries (sessionStorage: this tab only, cleared on sign-out)
  const draft: CompleteDraft = { v: 1, step, values, borrowerId: borrower?.id ?? null, approveNow }
  useAutosave(!editId && !saved, draft, () => saveDraft(draftKey, draft))

  // Browser history can land on any step: send it to the nearest step whose data exists.
  const formValid = parsed.success
  useEffect(() => {
    if (editId) return
    if (step > 0 && !borrower && !preselectId) goStep('0', { replace: true })
    else if (step >= 2 && !formValid) goStep('1', { replace: true })
  }, [step, borrower, preselectId, formValid, editId]) // eslint-disable-line react-hooks/exhaustive-deps

  const startOver = () => {
    clearDraft(draftKey)
    form.reset(defaults)
    setBorrower(null)
    setApproveNow(false)
    setRestored(false)
    goStep('0', { replace: true, extra: { borrower: null } })
  }

  if (editId && editLoan.isLoading) return <PageLoader />
  if (editId && editLoan.error) return <ErrorState error={editLoan.error} />
  if (editLoan.data && !['draft', 'pending_review', 'approved', 'awaiting_borrower_signature', 'awaiting_lender_signature'].includes(editLoan.data.status)) {
    return (
      <EmptyState title="This loan can no longer be edited" description="Contractual terms are frozen once signing has started or the loan is active. Use a reschedule amendment instead."
        action={<Button asChild variant="outline"><Link to={`/loans/${editId}`}>Back to loan</Link></Button>} />
    )
  }

  const goPreview = async () => {
    const ok = await form.trigger()
    if (!ok) return toast.error('Please fix the highlighted fields')
    setStep(2)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        title={editId ? `Edit loan ${editLoan.data?.loan_number ?? ''}` : 'New loan'}
        description={editId ? 'Editing returns the loan to draft and voids any unsigned agreement.' : 'Create a loan in four guided steps. Figures are calculated and validated on the server.'}
        back={<button onClick={() => navigate(-1)} className="mb-2 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"><ArrowLeft className="size-3.5" /> Back</button>}
        actions={editLoan.data ? <LoanStatusBadge status={editLoan.data.status} />
          : !editId && (step > 0 || borrower) && <Button variant="ghost" size="sm" onClick={startOver}><RotateCcw /> Start over</Button>}
      />

      {restored && (
        <div className="mb-4 flex flex-wrap items-center gap-2 rounded-xl border border-blue-200 bg-blue-50 px-4 py-2.5 text-sm text-blue-900">
          <History className="size-4" />
          <span className="flex-1">Your unsaved loan entries were restored.</span>
          <Button variant="outline" size="sm" onClick={startOver}>Start over</Button>
          <Button variant="ghost" size="sm" onClick={() => setRestored(false)}>Dismiss</Button>
        </div>
      )}

      {/* stepper */}
      <ol className="mb-5 grid grid-cols-4 gap-2">
        {STEPS.map((s, i) => (
          <li key={s}>
            <button type="button" disabled={i > step || (i === 0 && !!editId)} onClick={() => (i < step ? backTo(i) : setStep(i))}
              className={cn('flex w-full flex-col gap-1.5 text-left disabled:cursor-default')}>
              <span className={cn('h-1.5 rounded-full', i <= step ? 'bg-[#2563EB]' : 'bg-slate-200')} />
              <span className={cn('flex items-center gap-1.5 text-xs font-medium', i === step ? 'text-[#142B4A]' : 'text-muted-foreground')}>
                <span className={cn('grid size-5 place-items-center rounded-full text-[10px]', i < step ? 'bg-[#2563EB] text-white' : i === step ? 'bg-[#142B4A] text-white' : 'bg-slate-200 text-slate-600')}>
                  {i < step ? <Check className="size-3" /> : i + 1}
                </span>
                <span className="hidden sm:inline">{s}</span>
              </span>
            </button>
          </li>
        ))}
      </ol>

      {step === 0 && <BorrowerStep borrower={borrower} onSelect={(b) => { setBorrower(b); if (b) setStep(1) }} />}

      {step >= 1 && borrower && (
        <div className="mb-4 flex items-center gap-3 rounded-xl border bg-white px-4 py-3 shadow-card">
          <span className="grid size-10 place-items-center rounded-full bg-[#142B4A]/10 text-sm font-semibold text-[#142B4A]">{initials(borrower.full_name)}</span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold">{borrower.full_name}</p>
            <p className="text-xs text-muted-foreground">{borrower.borrower_code} · {phMobile(borrower.mobile_number)}</p>
          </div>
          {!editId && <Button variant="ghost" size="sm" onClick={() => backTo(0)}><Pencil /> Change</Button>}
        </div>
      )}

      {step === 1 && (
        <div className="grid gap-5 lg:grid-cols-[1fr_380px]">
          <form className="space-y-5" onSubmit={(e) => { e.preventDefault(); void goPreview() }}>
            <Panel title="Loan product & amount">
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="Loan product" hint={product ? product.description ?? undefined : 'Optional — fills in the product’s default terms'} className="sm:col-span-2">
                  <NativeSelect value={values.product_id} onChange={(e) => applyProduct(products.data?.find((p) => p.id === e.target.value))}>
                    <option value="">Custom terms (no product)</option>
                    {products.data?.map((p) => <option key={p.id} value={p.id}>{p.name} — {toNumber(p.interest_rate)}% / {p.rate_period} · {p.interest_method}</option>)}
                  </NativeSelect>
                </Field>
                <Field label="Loan amount (₱)" required error={errors.principal?.message}
                  hint={product && (product.min_amount || product.max_amount) ? `Product range ${peso(product.min_amount ?? 0, { decimals: 0 })} – ${product.max_amount ? peso(product.max_amount, { decimals: 0 }) : 'no limit'}` : undefined}>
                  <Input type="number" inputMode="decimal" step="0.01" min="0" placeholder="50,000.00" className="h-11 text-base" {...register('principal', { valueAsNumber: true })} />
                </Field>
                <Field label="Purpose of loan">
                  <Input className="h-11" placeholder="e.g. Business capital, tuition" {...register('purpose')} />
                </Field>
              </div>
            </Panel>

            <Panel title="Interest" description="The entered rate is a nominal rate for the chosen period. Conversions are disclosed in the preview.">
              <div className="grid gap-3 sm:grid-cols-2">
                {([
                  ['flat', 'Flat interest', 'Interest on the original principal for the whole term. Equal installments.'],
                  ['diminishing', 'Diminishing balance', 'Interest on the outstanding principal each period. Interest falls as you pay.'],
                ] as const).map(([v, title, desc]) => {
                  const disabled = (v === 'flat' && settings?.interest.flat_enabled === false) || (v === 'diminishing' && settings?.interest.diminishing_enabled === false)
                  return (
                    <button key={v} type="button" disabled={disabled} onClick={() => setValue('interest_method', v, { shouldValidate: true })}
                      className={cn('rounded-xl border-2 p-3 text-left transition disabled:opacity-40', values.interest_method === v ? 'border-[#2563EB] bg-blue-50/60' : 'border-slate-200 hover:border-slate-300')}>
                      <span className="flex items-center justify-between text-sm font-semibold">{title}{values.interest_method === v && <CheckCircle2 className="size-4 text-[#2563EB]" />}</span>
                      <span className="mt-1 block text-xs text-muted-foreground">{desc}</span>
                    </button>
                  )
                })}
              </div>
              <div className="mt-4 grid grid-cols-2 gap-4">
                <Field label="Interest rate (%)" required error={errors.interest_rate?.message}>
                  <Input type="number" inputMode="decimal" step="0.0001" className="h-11" {...register('interest_rate', { valueAsNumber: true })} />
                </Field>
                <Field label="Rate period">
                  <NativeSelect {...register('rate_period')}>
                    {RATE_PERIODS.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
                  </NativeSelect>
                </Field>
              </div>
              {!!settings?.interest.rate_presets?.length && (
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {settings.interest.rate_presets.map((r) => (
                    <button key={r} type="button" onClick={() => setValue('interest_rate', r, { shouldValidate: true })}
                      className={cn('rounded-full border px-2.5 py-1 text-xs', values.interest_rate === r ? 'border-[#2563EB] bg-blue-50 text-blue-700' : 'hover:bg-muted')}>{r}%</button>
                  ))}
                </div>
              )}
            </Panel>

            <Panel title="Repayment">
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="Repayment frequency">
                  <NativeSelect {...register('repayment_frequency', { onChange: () => setValue('first_due_date', '') })}>
                    {FREQUENCIES.filter((f) => !settings || settings.repayment.frequencies.includes(f.value)).map((f) => <option key={f.value} value={f.value}>{f.label}</option>)}
                  </NativeSelect>
                </Field>
                <Field label={`Repayment duration (${unit})`} required error={errors.term_count?.message}
                  hint={values.term_count > 0 ? `${values.term_count} ${values.repayment_frequency} installments` : undefined}>
                  <Input type="number" inputMode="numeric" min={1} className="h-11" {...register('term_count', { valueAsNumber: true })} />
                </Field>
                <Field label="Loan release date" required error={errors.release_date?.message}>
                  <Input type="date" className="h-11" {...register('release_date')} />
                </Field>
                <Field label="First repayment date" error={errors.first_due_date?.message} hint={!values.first_due_date && autoFirstDue ? `Automatic: ${fmtDate(autoFirstDue)}` : 'Leave blank for one period after release'}>
                  <div className="flex gap-2">
                    <Input type="date" className="h-11" {...register('first_due_date')} />
                    {values.first_due_date && <Button type="button" variant="ghost" size="icon" className="size-11" onClick={() => setValue('first_due_date', '', { shouldValidate: true })} aria-label="Use automatic date"><X /></Button>}
                  </div>
                </Field>
              </div>
            </Panel>

            <Panel title="Fees & charges">
              <div className="grid gap-4 sm:grid-cols-3">
                <Field label="Processing fee">
                  <NativeSelect {...register('processing_fee_type')}>
                    <option value="none">None</option>
                    <option value="percent">Percent of principal</option>
                    <option value="fixed">Fixed amount</option>
                  </NativeSelect>
                </Field>
                <Field label={values.processing_fee_type === 'percent' ? 'Fee (%)' : 'Fee (₱)'} error={errors.processing_fee_value?.message}>
                  <Input type="number" step="0.01" inputMode="decimal" className="h-11" disabled={values.processing_fee_type === 'none'} {...register('processing_fee_value', { valueAsNumber: true })} />
                </Field>
                <Field label="How charges are collected">
                  <NativeSelect {...register('charges_treatment')}>
                    <option value="deducted">Deducted from proceeds</option>
                    <option value="added">Added to installments</option>
                  </NativeSelect>
                </Field>
                <Field label="Other charges (₱)" error={errors.other_charges?.message}>
                  <Input type="number" step="0.01" inputMode="decimal" className="h-11" {...register('other_charges', { valueAsNumber: true })} />
                </Field>
                <Field label="Other charges description" className="sm:col-span-2">
                  <Input className="h-11" placeholder="e.g. Notarial fee, documentary stamp" {...register('other_charges_description')} />
                </Field>
              </div>
            </Panel>

            <Panel title="Late payment penalty" actions={<Switch checked={values.penalty_enabled} onCheckedChange={(c) => setValue('penalty_enabled', c, { shouldValidate: true })} aria-label="Enable penalties" />}>
              {values.penalty_enabled ? (
                <div className="grid gap-4 sm:grid-cols-3">
                  <Field label="Penalty type" error={errors.penalty_type?.message} className="sm:col-span-3">
                    <NativeSelect {...register('penalty_type')}>
                      {PENALTY_TYPES.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
                    </NativeSelect>
                  </Field>
                  <Field label={values.penalty_type === 'fixed' ? 'Fee (₱)' : 'Rate (%)'} error={errors.penalty_rate?.message}>
                    <Input type="number" step="0.01" className="h-11" {...register('penalty_rate', { valueAsNumber: true })} />
                  </Field>
                  <Field label="Grace period (days)" error={errors.penalty_grace_days?.message}>
                    <Input type="number" className="h-11" {...register('penalty_grace_days', { valueAsNumber: true })} />
                  </Field>
                </div>
              ) : <p className="text-sm text-muted-foreground">No late-payment penalty will be charged on this loan.</p>}
            </Panel>

            <Collapsible>
              <Panel title={
                <CollapsibleTrigger className="flex w-full items-center gap-2 text-left">Advanced & internal <ChevronDown className="size-4 text-muted-foreground" /></CollapsibleTrigger>
              }>
                <CollapsibleContent className="grid gap-4 sm:grid-cols-2">
                  <Field label="Day-count basis" hint="Diminishing loans: actual-day accrual handles leap years and irregular periods">
                    <NativeSelect {...register('day_count')}>
                      <option value="30/360">30/360 (equal periods)</option>
                      <option value="actual/365">Actual/365</option>
                      <option value="actual/actual">Actual/Actual (leap-year aware)</option>
                    </NativeSelect>
                  </Field>
                  <Field label="Installment rounding">
                    <NativeSelect {...register('rounding')}>
                      <option value="centavo">To the centavo</option>
                      <option value="peso">To the whole peso (final installment adjusts)</option>
                    </NativeSelect>
                  </Field>
                  <Field label="Assigned collector">
                    <NativeSelect {...register('collector_id')}>
                      <option value="">Unassigned</option>
                      {staff.data?.filter((s) => s.is_active && (s.role === 'collector' || s.role === 'loan_officer' || s.role === 'admin')).map((s) => (
                        <option key={s.id} value={s.id}>{s.full_name} ({s.role?.replace('_', ' ')})</option>
                      ))}
                    </NativeSelect>
                  </Field>
                  <Field label="Internal loan notes" className="sm:col-span-2">
                    <Textarea rows={3} placeholder="Visible to staff only" {...register('notes')} />
                  </Field>
                </CollapsibleContent>
              </Panel>
            </Collapsible>

            <div className="sticky bottom-20 z-10 flex gap-2 rounded-xl border bg-white/95 p-3 shadow-lg backdrop-blur lg:bottom-4">
              {!editId && <Button type="button" variant="outline" className="h-12" onClick={() => backTo(0)}><ArrowLeft /> Back</Button>}
              <Button type="submit" className="h-12 flex-1 bg-[#2563EB] text-base font-semibold hover:bg-[#1d4ed8]">
                <Calculator className="size-5" /> PREVIEW LOAN
              </Button>
            </div>
          </form>

          {/* live preview */}
          <aside className="space-y-3 lg:sticky lg:top-20 lg:self-start">
            <Panel title="Live calculation" actions={preview.isFetching ? <Spinner /> : previewFresh ? <span className="text-xs text-green-600">Up to date</span> : null}>
              {preview.error && payload ? (
                <p className="rounded-lg bg-red-50 p-3 text-sm text-red-700">{errorMessage(preview.error)}</p>
              ) : preview.data ? (
                <div className={cn('space-y-4 transition', !previewFresh && 'opacity-60')}>
                  <HeadlineFigures calc={preview.data} />
                  <SummaryGrid calc={preview.data} compact />
                </div>
              ) : (
                <p className="py-6 text-center text-sm text-muted-foreground">Enter the loan amount and terms to see the calculation.</p>
              )}
            </Panel>
          </aside>
        </div>
      )}

      {step === 2 && (
        <div className="space-y-5">
          {preview.isFetching && !preview.data && <PageLoader label="Calculating on the server…" />}
          {preview.error && <ErrorState error={preview.error} onRetry={() => void preview.refetch()} />}
          {preview.data && (
            <>
              <Panel title="Loan calculation preview" description="Server-calculated with decimal-safe arithmetic. Nothing has been saved yet."
                actions={<Button variant="outline" size="sm" onClick={() => void preview.refetch()} disabled={preview.isFetching}>{preview.isFetching ? <Spinner /> : <RefreshCw />} Recalculate</Button>}>
                <div className="space-y-5">
                  <HeadlineFigures calc={preview.data} />
                  <SummaryGrid calc={preview.data} />
                  <Disclosures calc={preview.data} />
                </div>
              </Panel>
              <Panel title="Amortization schedule" description={`${preview.data.schedule.length} installments · ${fmtDate(preview.data.summary.first_due_date)} to ${fmtDate(preview.data.summary.last_due_date)}`}>
                <ScheduleTable rows={preview.data.schedule} maxHeight={520} />
              </Panel>
              <div className="sticky bottom-20 z-10 flex flex-wrap gap-2 rounded-xl border bg-white/95 p-3 shadow-lg backdrop-blur lg:bottom-4">
                <Button variant="outline" className="h-12" onClick={() => backTo(1)}><Pencil /> Edit loan details</Button>
                <Button className="h-12 flex-1" onClick={() => setStep(3)} disabled={!previewFresh}>Continue to agreement <ArrowRight /></Button>
              </div>
            </>
          )}
        </div>
      )}

      {step === 3 && preview.data && borrower && (
        <div className="grid gap-5 lg:grid-cols-[1fr_360px]">
          <div className="space-y-5">
            <Panel title="Agreement settings" description="The agreement uses a frozen snapshot of the template and these exact loan terms.">
              <div className="grid gap-4 sm:grid-cols-3">
                <Field label="Agreement template" className="sm:col-span-3">
                  <NativeSelect {...register('agreement_template_id')}>
                    <option value="">Default template</option>
                    {templates.data?.filter((t) => t.is_active).map((t) => <option key={t.id} value={t.id}>{t.name}{t.is_default ? ' (default)' : ''} · v{t.current?.version_no}</option>)}
                  </NativeSelect>
                </Field>
                <Field label="Signing order">
                  <NativeSelect {...register('signing_order')}>
                    <option value="borrower_first">Borrower first</option>
                    <option value="lender_first">Lender first</option>
                    <option value="any">Any order</option>
                  </NativeSelect>
                </Field>
                <Field label="Witnesses">
                  <NativeSelect {...register('witness_count', { valueAsNumber: true })}>
                    <option value={0}>None</option>
                    <option value={1}>1 witness</option>
                    <option value={2}>2 witnesses</option>
                  </NativeSelect>
                </Field>
              </div>
            </Panel>
            <Panel title="What happens next">
              <ol className="space-y-3 text-sm">
                {[
                  ['Loan application is saved', 'A persistent loan record with server-validated figures is created and submitted for review.'],
                  ['Agreement PDF is generated', 'Borrower details, terms, today’s date and the full repayment schedule are filled in automatically.'],
                  ['Borrower signs on their phone', 'Share a secure, time-limited link or QR code. The borrower verifies their identity, reviews and signs.'],
                  ['Lender signs & approves', 'Both signatures are embedded permanently into the PDF and the document is hashed (SHA-256).'],
                  ['Release funds', 'Only after approval and all signatures can the loan be released and activated.'],
                ].map(([t, d], i) => (
                  <li key={t} className="flex gap-3">
                    <span className="grid size-6 shrink-0 place-items-center rounded-full bg-[#142B4A] text-xs font-semibold text-white">{i + 1}</span>
                    <span><span className="font-medium">{t}.</span> <span className="text-muted-foreground">{d}</span></span>
                  </li>
                ))}
              </ol>
              <p className="mt-4 rounded-lg bg-amber-50 p-3 text-xs text-amber-900">
                Generated agreements are templates and must be reviewed for compliance with applicable Philippine laws (SEC lending regulations, Truth in Lending Act, Data Privacy Act, E-Commerce Act). A signature alone does not make an agreement legally compliant.
              </p>
            </Panel>
          </div>
          <aside className="space-y-3 lg:sticky lg:top-20 lg:self-start">
            <Panel title="Confirm loan">
              <div className="space-y-4">
                <HeadlineFigures calc={preview.data} />
                <div className="text-sm">
                  <p className="font-medium">{borrower.full_name}</p>
                  <p className="text-muted-foreground">{peso(preview.data.summary.principal)} · {preview.data.summary.duration_label} · {preview.data.summary.interest_method}</p>
                </div>
                {can('loans.approve') && (
                  <label className="flex items-start gap-2 rounded-lg border p-3 text-sm">
                    <Checkbox checked={approveNow} onCheckedChange={(c) => setApproveNow(!!c)} className="mt-0.5" />
                    <span><span className="font-medium">Approve now</span><span className="block text-xs text-muted-foreground">You have approval rights. Approval is recorded separately from signing.</span></span>
                  </label>
                )}
                <div className="space-y-2">
                  {can('agreements.generate') && (
                    <Button className="h-12 w-full text-base" disabled={save.isPending} onClick={() => save.mutate('agreement')}>
                      {save.isPending && save.variables === 'agreement' ? <Spinner /> : <FileSignature />} Submit & generate agreement
                    </Button>
                  )}
                  <Button variant="outline" className="h-11 w-full" disabled={save.isPending} onClick={() => save.mutate('submit')}>
                    {save.isPending && save.variables === 'submit' && <Spinner />} Submit for review only
                  </Button>
                  <Button variant="ghost" className="h-11 w-full" disabled={save.isPending} onClick={() => save.mutate('draft')}>
                    {save.isPending && save.variables === 'draft' && <Spinner />} Save as draft
                  </Button>
                </div>
                <Button variant="link" className="w-full" onClick={() => backTo(2)}><ArrowLeft /> Back to preview</Button>
              </div>
            </Panel>
          </aside>
        </div>
      )}
    </div>
  )
}

function BorrowerStep({ borrower, onSelect }: { borrower: Borrower | null; onSelect: (b: Borrower | null) => void }) {
  const [term, setTerm] = useState('')
  const debounced = useDebounced(term, 250)
  const { can } = useAuth()
  const results = useQuery({ queryKey: ['borrowers', 'search', debounced], queryFn: () => borrowersService.search(debounced, 10) })
  const [picked, setPicked] = useState<Borrower | null>(borrower)
  const history = useQuery({ queryKey: ['borrower', picked?.id, 'loans'], queryFn: () => borrowersService.loans(picked!.id), enabled: !!picked })

  return (
    <div className="grid gap-5 lg:grid-cols-[1fr_380px]">
      <Panel title="Select borrower" description="Search by name, borrower code or mobile number."
        actions={can('borrowers.create') && <Button asChild variant="outline" size="sm"><Link to="/borrowers/new?return=/loans/new"><UserPlus /> Register new</Link></Button>}>
        <div className="relative mb-3">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input autoFocus value={term} onChange={(e) => setTerm(e.target.value)} placeholder="e.g. Dela Cruz, BRW-000012, 0917…" className="h-12 pl-9 text-base" />
        </div>
        {results.isLoading ? <PageLoader /> : !results.data?.length ? (
          <EmptyState title="No borrowers found" description="Register the borrower first — you'll come right back to this loan."
            action={can('borrowers.create') && <Button asChild><Link to="/borrowers/new?return=/loans/new"><UserPlus /> Register borrower</Link></Button>} />
        ) : (
          <ul className="divide-y rounded-lg border">
            {results.data.map((b) => (
              <li key={b.id}>
                <button onClick={() => setPicked(b)} className={cn('flex w-full items-center gap-3 px-3 py-3 text-left hover:bg-muted/50', picked?.id === b.id && 'bg-blue-50')}>
                  <span className="grid size-10 shrink-0 place-items-center rounded-full bg-slate-100 text-sm font-semibold text-slate-700">{initials(b.full_name)}</span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">{b.full_name}</span>
                    <span className="block truncate text-xs text-muted-foreground">{b.borrower_code} · {phMobile(b.mobile_number)} · {b.city_municipality ?? '—'}</span>
                  </span>
                  {b.risk_rating !== 'unrated' && <span className={cn('rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase', b.risk_rating === 'high' ? 'bg-red-50 text-red-700' : b.risk_rating === 'medium' ? 'bg-amber-50 text-amber-700' : 'bg-green-50 text-green-700')}>{b.risk_rating} risk</span>}
                  {picked?.id === b.id && <CheckCircle2 className="size-5 text-[#2563EB]" />}
                </button>
              </li>
            ))}
          </ul>
        )}
      </Panel>
      <aside className="lg:sticky lg:top-20 lg:self-start">
        <Panel title="Borrower summary">
          {!picked ? <p className="py-6 text-center text-sm text-muted-foreground">Select a borrower to see their history.</p> : (
            <div className="space-y-4">
              <div>
                <p className="font-semibold">{picked.full_name}</p>
                <p className="text-xs text-muted-foreground">{picked.occupation ?? 'Occupation not set'}{picked.employer_name ? ` · ${picked.employer_name}` : ''}</p>
                {picked.monthly_income && <p className="text-xs text-muted-foreground">Monthly income {peso(picked.monthly_income, { decimals: 0 })}</p>}
              </div>
              <div>
                <p className="mb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">Loan history</p>
                {history.isLoading ? <Spinner /> : !history.data?.length ? <p className="text-sm text-muted-foreground">First loan with you.</p> : (
                  <ul className="space-y-2">
                    {history.data.slice(0, 5).map((l) => (
                      <li key={l.id} className="flex items-center justify-between gap-2 text-sm">
                        <span className="min-w-0"><span className="block truncate font-medium">{l.loan_number}</span><span className="text-xs text-muted-foreground">{peso(l.principal, { decimals: 0 })} · {fmtDate(l.created_at)}</span></span>
                        <LoanStatusBadge status={l.status} />
                      </li>
                    ))}
                  </ul>
                )}
                {!!history.data?.some((l) => toNumber(l.overdue_amount) > 0) && (
                  <p className="mt-2 rounded bg-red-50 p-2 text-xs text-red-700">This borrower has overdue installments on an existing loan.</p>
                )}
              </div>
              <Button className="h-11 w-full" onClick={() => onSelect(picked)}>Continue with this borrower <ArrowRight /></Button>
            </div>
          )}
        </Panel>
      </aside>
    </div>
  )
}
