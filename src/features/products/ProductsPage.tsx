import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Package, Pencil, Plus, Power } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Switch } from '@/components/ui/switch'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { EmptyState, ErrorState, Field, KeyValue, NativeSelect, PageHeader, Pill, Spinner, TableSkeleton } from '@/components/common'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { useAuth } from '@/features/auth/AuthProvider'
import { productsService } from '@/services/loans'
import { templatesService } from '@/services/agreements'
import { FREQUENCIES, PENALTY_TYPES, RATE_PERIODS } from '@/lib/constants'
import { FREQUENCY_LABEL, METHOD_LABEL, peso, trimNumber } from '@/lib/format'
import { errorMessage } from '@/lib/errors'
import type { LoanProduct } from '@/types/db'

const num = (msg = 'Enter a valid number', opts: { min?: number; max?: number; int?: boolean } = {}) =>
  z.string().trim().refine((v) => {
    if (v === '') return true
    const x = Number(v.replace(/,/g, ''))
    if (Number.isNaN(x)) return false
    if (opts.int && !Number.isInteger(x)) return false
    if (opts.min !== undefined && x < opts.min) return false
    if (opts.max !== undefined && x > opts.max) return false
    return true
  }, msg)

const schema = z.object({
  name: z.string().trim().min(2, 'Name is required'),
  code: z.string().trim().min(2, 'Code is required').max(30).regex(/^[A-Za-z0-9\- _]+$/, 'Letters, numbers and dashes only'),
  description: z.string().optional(),
  interest_method: z.enum(['flat', 'diminishing']),
  interest_rate: num('Enter a rate between 0 and 100', { min: 0, max: 100 }).refine((v) => v !== '', 'Interest rate is required'),
  rate_period: z.enum(['daily', 'weekly', 'monthly', 'yearly']),
  repayment_frequency: z.enum(['daily', 'weekly', 'semimonthly', 'monthly']),
  min_amount: num('Enter a positive amount', { min: 0.01 }),
  max_amount: num('Enter a positive amount', { min: 0.01 }),
  min_term: num('Whole number ≥ 1', { min: 1, int: true }),
  max_term: num('Whole number ≥ 1', { min: 1, int: true }),
  default_term: num('Whole number ≥ 1', { min: 1, int: true }),
  processing_fee_type: z.enum(['none', 'fixed', 'percent']),
  processing_fee_value: num('Enter a non-negative value', { min: 0 }),
  charges_treatment: z.enum(['added', 'deducted']),
  day_count: z.enum(['30/360', 'actual/365', 'actual/actual']),
  penalty_enabled: z.boolean(),
  penalty_type: z.string().optional(),
  penalty_rate: num('Enter a non-negative value', { min: 0 }),
  penalty_grace_days: num('Whole number ≥ 0', { min: 0, int: true }),
  agreement_template_id: z.string().optional(),
  is_active: z.boolean(),
})
  .refine((v) => !v.min_amount || !v.max_amount || Number(v.max_amount.replace(/,/g, '')) >= Number(v.min_amount.replace(/,/g, '')), { path: ['max_amount'], message: 'Maximum must be ≥ minimum' })
  .refine((v) => !v.min_term || !v.max_term || Number(v.max_term) >= Number(v.min_term), { path: ['max_term'], message: 'Maximum must be ≥ minimum' })
  .refine((v) => !v.default_term || ((!v.min_term || Number(v.default_term) >= Number(v.min_term)) && (!v.max_term || Number(v.default_term) <= Number(v.max_term))), { path: ['default_term'], message: 'Default must be within the min/max term' })
  .refine((v) => v.processing_fee_type !== 'percent' || Number(v.processing_fee_value || 0) <= 100, { path: ['processing_fee_value'], message: 'Percentage cannot exceed 100%' })
  .refine((v) => !v.penalty_enabled || !!v.penalty_type, { path: ['penalty_type'], message: 'Choose a penalty type' })
  .refine((v) => !v.penalty_enabled || Number(v.penalty_rate || 0) > 0, { path: ['penalty_rate'], message: 'Enter the penalty rate or amount' })

type FormValues = z.infer<typeof schema>

const s = (v: unknown) => (v === null || v === undefined ? '' : String(v))
const toNum = (v: string) => (v.trim() === '' ? null : Number(v.replace(/,/g, '')))

function toForm(p?: LoanProduct | null): FormValues {
  return {
    name: p?.name ?? '', code: p?.code ?? '', description: p?.description ?? '',
    interest_method: p?.interest_method ?? 'flat', interest_rate: s(p?.interest_rate ?? 2), rate_period: p?.rate_period ?? 'monthly',
    repayment_frequency: p?.repayment_frequency ?? 'monthly',
    min_amount: s(p?.min_amount), max_amount: s(p?.max_amount), min_term: s(p?.min_term), max_term: s(p?.max_term), default_term: s(p?.default_term ?? 12),
    processing_fee_type: p?.processing_fee_type ?? 'none', processing_fee_value: s(p?.processing_fee_value ?? 0),
    charges_treatment: p?.charges_treatment ?? 'deducted', day_count: p?.day_count ?? '30/360',
    penalty_enabled: p?.penalty_enabled ?? true, penalty_type: p?.penalty_type ?? 'percent_monthly',
    penalty_rate: s(p?.penalty_rate ?? 3), penalty_grace_days: s(p?.penalty_grace_days ?? 3),
    agreement_template_id: p?.agreement_template_id ?? '', is_active: p?.is_active ?? true,
  }
}

function termUnit(freq: string) {
  return FREQUENCIES.find((f) => f.value === freq)?.unit ?? 'installments'
}

function feeText(p: LoanProduct) {
  if (p.processing_fee_type === 'none' || Number(p.processing_fee_value) === 0) return 'No processing fee'
  const v = p.processing_fee_type === 'percent' ? `${trimNumber(p.processing_fee_value)}%` : peso(p.processing_fee_value)
  return `${v} (${p.charges_treatment === 'deducted' ? 'deducted from proceeds' : 'added to installments'})`
}

function penaltyText(p: LoanProduct) {
  if (!p.penalty_enabled) return 'No penalty'
  const t = PENALTY_TYPES.find((x) => x.value === p.penalty_type)?.label ?? p.penalty_type
  const rate = p.penalty_type === 'fixed' ? peso(p.penalty_rate) : `${trimNumber(p.penalty_rate)}%`
  return `${rate} — ${t}; ${p.penalty_grace_days} grace day${p.penalty_grace_days === 1 ? '' : 's'}`
}

export default function ProductsPage() {
  const { can } = useAuth()
  const canManage = can('products.manage')
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['products'], queryFn: () => productsService.list(false) })
  const [editing, setEditing] = useState<LoanProduct | 'new' | null>(null)
  const [toggle, setToggle] = useState<LoanProduct | null>(null)
  const [showInactive, setShowInactive] = useState(true)

  const rows = (q.data ?? []).filter((p) => showInactive || p.is_active)

  return (
    <div>
      <PageHeader
        title="Loan products"
        description="Preset terms that pre-fill new loans. Each loan keeps its own frozen terms once created."
        actions={canManage && <Button onClick={() => setEditing('new')}><Plus /> New product</Button>}
      />
      <div className="mb-4 flex items-center justify-end gap-2 text-sm text-muted-foreground">
        <Switch id="inactive" checked={showInactive} onCheckedChange={setShowInactive} />
        <label htmlFor="inactive">Show inactive</label>
      </div>
      {q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : q.isLoading ? <TableSkeleton rows={4} /> : !rows.length ? (
        <div className="rounded-xl border bg-white">
          <EmptyState icon={<Package />} title="No loan products" description="Create products to standardize rates, terms, fees and penalties."
            action={canManage && <Button onClick={() => setEditing('new')}><Plus /> New product</Button>} />
        </div>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {rows.map((p) => (
            <div key={p.id} className={`flex flex-col rounded-xl border bg-card p-4 shadow-card ${p.is_active ? '' : 'opacity-70'}`}>
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <p className="truncate font-semibold">{p.name}</p>
                  <p className="text-xs text-muted-foreground">{p.code}</p>
                </div>
                {p.is_active ? <Pill tone="green">Active</Pill> : <Pill tone="slate">Inactive</Pill>}
              </div>
              <div className="my-3 rounded-lg bg-slate-50 p-3">
                <p className="tabular text-2xl font-semibold text-[#142B4A]">
                  {trimNumber(p.interest_rate)}%<span className="text-sm font-normal text-muted-foreground"> {RATE_PERIODS.find((r) => r.value === p.rate_period)?.label}</span>
                </p>
                <p className="text-xs text-muted-foreground">{METHOD_LABEL[p.interest_method]} · paid {(FREQUENCY_LABEL[p.repayment_frequency] ?? p.repayment_frequency)}</p>
              </div>
              {p.description && <p className="mb-3 text-sm text-muted-foreground">{p.description}</p>}
              <KeyValue columns={2} items={[
                { label: 'Amount', value: p.min_amount || p.max_amount ? `${p.min_amount ? peso(p.min_amount, { decimals: 0 }) : '—'} – ${p.max_amount ? peso(p.max_amount, { decimals: 0 }) : '—'}` : 'Any' },
                { label: 'Term', value: `${p.min_term ?? '—'} – ${p.max_term ?? '—'} ${termUnit(p.repayment_frequency)}` },
                { label: 'Processing fee', value: feeText(p) },
                { label: 'Late payment', value: penaltyText(p) },
              ]} />
              {canManage && (
                <div className="mt-4 flex gap-2 border-t pt-3">
                  <Button size="sm" variant="outline" className="flex-1" onClick={() => setEditing(p)}><Pencil /> Edit</Button>
                  <Button size="sm" variant="ghost" className="flex-1" onClick={() => setToggle(p)}><Power /> {p.is_active ? 'Deactivate' : 'Activate'}</Button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {editing && <ProductDialog product={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
      <ConfirmDialog
        open={!!toggle}
        onOpenChange={(o) => !o && setToggle(null)}
        title={toggle?.is_active ? `Deactivate ${toggle?.name}?` : `Activate ${toggle?.name}?`}
        description={toggle?.is_active ? 'Inactive products cannot be selected for new loans. Existing loans are not affected.' : 'The product will be available for new loans.'}
        confirmLabel={toggle?.is_active ? 'Deactivate' : 'Activate'}
        destructive={toggle?.is_active}
        onConfirm={async () => {
          await productsService.setActive(toggle!.id, !toggle!.is_active)
          toast.success(`Product ${toggle!.is_active ? 'deactivated' : 'activated'}`)
          void qc.invalidateQueries({ queryKey: ['products'] })
        }}
      />
    </div>
  )
}

function ProductDialog({ product, onClose }: { product: LoanProduct | null; onClose: () => void }) {
  const qc = useQueryClient()
  const templates = useQuery({ queryKey: ['templates'], queryFn: templatesService.list })
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: toForm(product) })
  const { register, handleSubmit, watch, setValue, formState: { errors, isSubmitting }, reset } = form
  useEffect(() => reset(toForm(product)), [product, reset])
  const [feeType, penaltyEnabled, penaltyType, freq] = watch(['processing_fee_type', 'penalty_enabled', 'penalty_type', 'repayment_frequency'])
  const [busy, setBusy] = useState(false)

  const onSubmit = handleSubmit(async (v) => {
    setBusy(true)
    try {
      await productsService.save({
        id: product?.id,
        name: v.name.trim(), code: v.code.trim(), description: v.description?.trim() || null,
        interest_method: v.interest_method, interest_rate: Number(v.interest_rate), rate_period: v.rate_period, repayment_frequency: v.repayment_frequency,
        min_amount: toNum(v.min_amount), max_amount: toNum(v.max_amount), min_term: toNum(v.min_term), max_term: toNum(v.max_term), default_term: toNum(v.default_term),
        processing_fee_type: v.processing_fee_type, processing_fee_value: v.processing_fee_type === 'none' ? 0 : Number(v.processing_fee_value || 0),
        charges_treatment: v.charges_treatment, day_count: v.day_count,
        penalty_enabled: v.penalty_enabled, penalty_type: v.penalty_enabled ? v.penalty_type || null : null,
        penalty_rate: v.penalty_enabled ? Number(v.penalty_rate || 0) : 0, penalty_grace_days: Number(v.penalty_grace_days || 0),
        agreement_template_id: v.agreement_template_id || null, is_active: v.is_active,
      })
      toast.success(product ? 'Product updated' : 'Product created')
      void qc.invalidateQueries({ queryKey: ['products'] })
      onClose()
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setBusy(false)
    }
  })

  const unit = termUnit(freq)
  return (
    <Dialog open onOpenChange={(o) => !o && !busy && onClose()}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{product ? `Edit ${product.name}` : 'New loan product'}</DialogTitle>
          <DialogDescription>Changes apply to new loans only. Existing loans keep the terms they were created with.</DialogDescription>
        </DialogHeader>
        <form onSubmit={onSubmit} className="space-y-6" noValidate>
          <section className="grid gap-4 sm:grid-cols-2">
            <Field label="Product name" required error={errors.name?.message}><Input className="h-10" {...register('name')} /></Field>
            <Field label="Code" required error={errors.code?.message} hint="Unique short code, e.g. PERSONAL"><Input className="h-10 uppercase" {...register('code')} /></Field>
            <Field label="Description" className="sm:col-span-2"><Textarea rows={2} {...register('description')} /></Field>
          </section>

          <section>
            <h3 className="mb-3 text-sm font-semibold">Interest & repayment</h3>
            <div className="grid gap-4 sm:grid-cols-3">
              <Field label="Interest method">
                <NativeSelect {...register('interest_method')}>
                  <option value="flat">Flat interest</option>
                  <option value="diminishing">Diminishing balance</option>
                </NativeSelect>
              </Field>
              <Field label="Interest rate (%)" required error={errors.interest_rate?.message}><Input className="h-10" inputMode="decimal" {...register('interest_rate')} /></Field>
              <Field label="Rate period" hint="Nominal rate per period">
                <NativeSelect {...register('rate_period')}>
                  {RATE_PERIODS.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
                </NativeSelect>
              </Field>
              <Field label="Repayment frequency">
                <NativeSelect {...register('repayment_frequency')}>
                  {FREQUENCIES.map((f) => <option key={f.value} value={f.value}>{f.label}</option>)}
                </NativeSelect>
              </Field>
              <Field label="Day-count basis" hint="Used for daily rates & actual-day accrual">
                <NativeSelect {...register('day_count')}>
                  <option value="30/360">30/360 (periodic)</option>
                  <option value="actual/365">Actual/365</option>
                  <option value="actual/actual">Actual/actual (leap-year aware)</option>
                </NativeSelect>
              </Field>
              <Field label="Agreement template">
                <NativeSelect {...register('agreement_template_id')}>
                  <option value="">Organization default</option>
                  {templates.data?.filter((t) => t.is_active).map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
                </NativeSelect>
              </Field>
            </div>
          </section>

          <section>
            <h3 className="mb-3 text-sm font-semibold">Limits</h3>
            <div className="grid gap-4 sm:grid-cols-3">
              <Field label="Minimum amount (₱)" error={errors.min_amount?.message}><Input className="h-10" inputMode="decimal" {...register('min_amount')} /></Field>
              <Field label="Maximum amount (₱)" error={errors.max_amount?.message}><Input className="h-10" inputMode="decimal" {...register('max_amount')} /></Field>
              <div className="hidden sm:block" />
              <Field label={`Minimum term (${unit})`} error={errors.min_term?.message}><Input className="h-10" inputMode="numeric" {...register('min_term')} /></Field>
              <Field label={`Maximum term (${unit})`} error={errors.max_term?.message}><Input className="h-10" inputMode="numeric" {...register('max_term')} /></Field>
              <Field label={`Default term (${unit})`} error={errors.default_term?.message}><Input className="h-10" inputMode="numeric" {...register('default_term')} /></Field>
            </div>
          </section>

          <section>
            <h3 className="mb-3 text-sm font-semibold">Processing fee</h3>
            <div className="grid gap-4 sm:grid-cols-3">
              <Field label="Fee type">
                <NativeSelect {...register('processing_fee_type')}>
                  <option value="none">No fee</option>
                  <option value="percent">Percentage of principal</option>
                  <option value="fixed">Fixed amount</option>
                </NativeSelect>
              </Field>
              <Field label={feeType === 'percent' ? 'Fee (%)' : 'Fee (₱)'} error={errors.processing_fee_value?.message}>
                <Input className="h-10" inputMode="decimal" disabled={feeType === 'none'} {...register('processing_fee_value')} />
              </Field>
              <Field label="Charges treatment">
                <NativeSelect {...register('charges_treatment')}>
                  <option value="deducted">Deducted from proceeds</option>
                  <option value="added">Added to installments</option>
                </NativeSelect>
              </Field>
            </div>
          </section>

          <section>
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-sm font-semibold">Late payment penalty</h3>
              <label className="flex items-center gap-2 text-sm">
                <Switch checked={penaltyEnabled} onCheckedChange={(c) => setValue('penalty_enabled', c, { shouldValidate: true })} />
                Enabled
              </label>
            </div>
            {penaltyEnabled && (
              <div className="grid gap-4 sm:grid-cols-3">
                <Field label="Penalty type" className="sm:col-span-3" error={errors.penalty_type?.message}>
                  <NativeSelect {...register('penalty_type')}>
                    <option value="">Select</option>
                    {PENALTY_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                  </NativeSelect>
                </Field>
                <Field label={penaltyType === 'fixed' ? 'Fee per late installment (₱)' : 'Rate (%)'} error={errors.penalty_rate?.message}>
                  <Input className="h-10" inputMode="decimal" {...register('penalty_rate')} />
                </Field>
                <Field label="Grace period (days)" error={errors.penalty_grace_days?.message}><Input className="h-10" inputMode="numeric" {...register('penalty_grace_days')} /></Field>
              </div>
            )}
          </section>

          <label className="flex items-center gap-2 text-sm">
            <Switch checked={watch('is_active')} onCheckedChange={(c) => setValue('is_active', c)} /> Product is active
          </label>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
            <Button type="submit" disabled={busy || isSubmitting}>{busy && <Spinner />} {product ? 'Save changes' : 'Create product'}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
