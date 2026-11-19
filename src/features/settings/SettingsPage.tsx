import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  ArrowDown, ArrowUp, Building2, Database, ExternalLink, FileSignature, ImagePlus, Info, Lock, Percent, Receipt, Save, ScrollText, ShieldCheck, Timer, Wallet, Zap,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Switch } from '@/components/ui/switch'
import { Checkbox } from '@/components/ui/checkbox'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { ErrorState, Field, NativeSelect, PageHeader, PageLoader, Panel, Spinner } from '@/components/common'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { useAuth } from '@/features/auth/AuthProvider'
import { orgService } from '@/services/org'
import { templatesService } from '@/services/agreements'
import { TemplateEditor } from '@/features/agreements/TemplateEditorPage'
import { FREQUENCIES, PENALTY_TYPES, RATE_PERIODS } from '@/lib/constants'
import { errorMessage } from '@/lib/errors'
import type { Settings } from '@/types/db'

type SectionKey = keyof Settings

function useSaveSection() {
  const qc = useQueryClient()
  const { refresh } = useAuth()
  const [saving, setSaving] = useState(false)
  const save = async (section: SectionKey, values: Record<string, unknown>) => {
    setSaving(true)
    try {
      await orgService.updateSettings(section, values)
      await qc.invalidateQueries({ queryKey: ['settings'] })
      await refresh()
      toast.success('Settings saved')
      return true
    } catch (e) {
      toast.error(errorMessage(e))
      return false
    } finally {
      setSaving(false)
    }
  }
  return { save, saving }
}

function SaveBar({ saving, onSave, dirty = true }: { saving: boolean; onSave: () => void; dirty?: boolean }) {
  return (
    <div className="mt-5 flex justify-end border-t pt-4">
      <Button onClick={onSave} disabled={saving || !dirty}>{saving ? <Spinner /> : <Save />} Save changes</Button>
    </div>
  )
}

function Toggle({ label, description, checked, onChange }: { label: string; description?: ReactNode; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="flex cursor-pointer items-start justify-between gap-4 rounded-lg border p-3">
      <span>
        <span className="block text-sm font-medium">{label}</span>
        {description && <span className="mt-0.5 block text-xs text-muted-foreground">{description}</span>}
      </span>
      <Switch checked={checked} onCheckedChange={onChange} />
    </label>
  )
}

function numberOk(v: string | number, min: number, max: number, int = false) {
  const n = Number(v)
  return v !== '' && !Number.isNaN(n) && n >= min && n <= max && (!int || Number.isInteger(n))
}

export default function SettingsPage() {
  const { can, access } = useAuth()
  const canSettings = can('settings.manage')
  const q = useQuery({ queryKey: ['settings'], queryFn: orgService.getSettings, enabled: canSettings })

  if (!canSettings) {
    return (
      <div>
        <PageHeader title="Settings" description="Your role can manage the terms (agreement) templates." />
        <TermsTemplateSection />
      </div>
    )
  }
  if (q.isLoading) return <PageLoader />
  if (q.error || !q.data) return <ErrorState error={q.error ?? 'Settings unavailable'} onRetry={() => void q.refetch()} />
  const s = q.data

  const tabs: { value: string; label: string; icon: ReactNode }[] = [
    { value: 'company', label: 'Company', icon: <Building2 /> },
    { value: 'interest', label: 'Interest', icon: <Percent /> },
    { value: 'repayment', label: 'Repayment', icon: <Wallet /> },
    { value: 'penalty', label: 'Late payments', icon: <Timer /> },
    { value: 'agreement', label: 'Agreements', icon: <FileSignature /> },
    ...(can('templates.manage') ? [{ value: 'terms', label: 'Terms template', icon: <ScrollText /> }] : []),
    { value: 'simplified', label: 'Simplified lending', icon: <Zap /> },
    { value: 'receipt', label: 'Receipts', icon: <Receipt /> },
    { value: 'security', label: 'Security', icon: <ShieldCheck /> },
    ...(access?.role === 'owner' ? [{ value: 'data', label: 'Sample data', icon: <Database /> }] : []),
  ]

  return (
    <div>
      <PageHeader title="Settings" description="Company profile and the lending rules applied to new loans. Existing loans keep their frozen terms." />
      <Tabs defaultValue="company">
        <div className="-mx-3 overflow-x-auto px-3 sm:mx-0 sm:px-0">
          <TabsList className="w-max">
            {tabs.map((t) => <TabsTrigger key={t.value} value={t.value} className="gap-1.5 [&_svg]:size-4">{t.icon}{t.label}</TabsTrigger>)}
          </TabsList>
        </div>
        <div className="mt-4 max-w-4xl">
          <TabsContent value="company"><CompanySection /></TabsContent>
          <TabsContent value="interest"><InterestSection value={s.interest} /></TabsContent>
          <TabsContent value="repayment"><RepaymentSection value={s.repayment} /></TabsContent>
          <TabsContent value="penalty"><PenaltySection value={s.penalty} /></TabsContent>
          <TabsContent value="agreement"><AgreementSection value={s.agreement} /></TabsContent>
          {can('templates.manage') && <TabsContent value="terms"><TermsTemplateSection /></TabsContent>}
          <TabsContent value="simplified"><SimplifiedSection value={s.simplified ?? { allow_rate_override: false, default_term: 6, processing_fee_type: 'none', processing_fee_value: 0, approve_on_create: true }} settings={s} /></TabsContent>
          <TabsContent value="receipt"><ReceiptSection value={s.receipt} /></TabsContent>
          <TabsContent value="security"><SecuritySection value={s.security} /></TabsContent>
          {access?.role === 'owner' && <TabsContent value="data"><SampleDataSection /></TabsContent>}
        </div>
      </Tabs>
    </div>
  )
}

// ---------------------------------------------------------------------------
const companySchema = z.object({
  name: z.string().trim().min(2, 'Business name is required'),
  legal_name: z.string().trim().optional(),
  registration_no: z.string().trim().optional(),
  tin: z.string().trim().optional().refine((v) => !v || /^\d{3}-?\d{3}-?\d{3}(-?\d{3,5})?$/.test(v), 'TIN format: 000-000-000-000'),
  address: z.string().trim().optional(),
  city: z.string().trim().optional(),
  province: z.string().trim().optional(),
  zip_code: z.string().trim().optional().refine((v) => !v || /^\d{4}$/.test(v), 'ZIP codes have 4 digits'),
  contact_email: z.string().trim().email('Enter a valid email').optional().or(z.literal('')),
  contact_phone: z.string().trim().optional(),
  website: z.string().trim().optional(),
})

function CompanySection() {
  const { organization, refresh } = useAuth()
  const qc = useQueryClient()
  const logo = useQuery({
    queryKey: ['settings', 'logo', organization?.logo_path],
    queryFn: () => orgService.logoUrl(organization!.logo_path!),
    enabled: !!organization?.logo_path,
    staleTime: 50 * 60_000,
  })
  const [uploading, setUploading] = useState(false)
  const form = useForm<z.infer<typeof companySchema>>({ resolver: zodResolver(companySchema) })
  const { register, handleSubmit, reset, formState: { errors, isSubmitting, isDirty } } = form
  useEffect(() => {
    if (!organization) return
    reset({
      name: organization.name, legal_name: organization.legal_name ?? '', registration_no: organization.registration_no ?? '',
      tin: organization.tin ?? '', address: organization.address ?? '', city: organization.city ?? '', province: organization.province ?? '',
      zip_code: organization.zip_code ?? '', contact_email: organization.contact_email ?? '', contact_phone: organization.contact_phone ?? '',
      website: organization.website ?? '',
    })
  }, [organization, reset])
  if (!organization) return null
  const onSubmit = handleSubmit(async (v) => {
    try {
      const blank = (x?: string) => (x && x.trim() ? x.trim() : null)
      await orgService.update(organization.id, {
        name: v.name.trim(), legal_name: blank(v.legal_name), registration_no: blank(v.registration_no), tin: blank(v.tin),
        address: blank(v.address), city: blank(v.city), province: blank(v.province), zip_code: blank(v.zip_code),
        contact_email: blank(v.contact_email), contact_phone: blank(v.contact_phone), website: blank(v.website),
      })
      await refresh()
      toast.success('Company profile saved')
    } catch (e) {
      toast.error(errorMessage(e))
    }
  })
  const onLogo = async (file: File) => {
    if (!file.type.startsWith('image/')) return toast.error('Upload a PNG, JPG, WEBP or SVG image')
    if (file.size > 2 * 1024 * 1024) return toast.error('Logo must be 2 MB or smaller')
    setUploading(true)
    try {
      await orgService.uploadLogo(organization.id, file)
      await refresh()
      void qc.invalidateQueries({ queryKey: ['settings', 'logo'] })
      toast.success('Logo updated')
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setUploading(false)
    }
  }

  return (
    <div className="space-y-5">
      <Panel title="Company logo" description="Printed on agreements and receipts (PNG or JPG recommended for PDFs).">
        <div className="flex flex-wrap items-center gap-4">
          <div className="grid size-24 place-items-center overflow-hidden rounded-xl border bg-slate-50">
            {logo.data ? <img src={logo.data} alt="Company logo" className="max-h-full max-w-full object-contain" /> : <Building2 className="size-8 text-slate-300" />}
          </div>
          <label className="inline-flex cursor-pointer items-center gap-2 rounded-md border bg-white px-3 py-2 text-sm font-medium shadow-xs hover:bg-muted">
            {uploading ? <Spinner /> : <ImagePlus className="size-4" />} {organization.logo_path ? 'Replace logo' : 'Upload logo'}
            <input type="file" accept="image/png,image/jpeg,image/webp,image/svg+xml" className="hidden" disabled={uploading}
              onChange={(e) => { const f = e.target.files?.[0]; if (f) void onLogo(f); e.target.value = '' }} />
          </label>
        </div>
      </Panel>
      <Panel title="Company profile">
        <form onSubmit={onSubmit} noValidate>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Business / trade name" required error={errors.name?.message}><Input className="h-10" {...register('name')} /></Field>
            <Field label="Registered legal name" hint="Shown as the lender on agreements"><Input className="h-10" {...register('legal_name')} /></Field>
            <Field label="SEC registration / Certificate of Authority no."><Input className="h-10" {...register('registration_no')} /></Field>
            <Field label="TIN" error={errors.tin?.message}><Input className="h-10" {...register('tin')} placeholder="000-000-000-000" /></Field>
            <Field label="Business address" className="sm:col-span-2"><Input className="h-10" {...register('address')} /></Field>
            <Field label="City / municipality" hint="Also used as the venue for disputes"><Input className="h-10" {...register('city')} /></Field>
            <Field label="Province"><Input className="h-10" {...register('province')} /></Field>
            <Field label="ZIP code" error={errors.zip_code?.message}><Input className="h-10" inputMode="numeric" maxLength={4} {...register('zip_code')} /></Field>
            <Field label="Contact number"><Input className="h-10" {...register('contact_phone')} /></Field>
            <Field label="Contact email" error={errors.contact_email?.message}><Input className="h-10" type="email" {...register('contact_email')} /></Field>
            <Field label="Website"><Input className="h-10" {...register('website')} placeholder="https://" /></Field>
            <Field label="Currency"><Input className="h-10" value="Philippine peso (PHP, ₱)" disabled /></Field>
            <Field label="Timezone"><Input className="h-10" value="Asia/Manila (PHT, UTC+8)" disabled /></Field>
          </div>
          <div className="mt-5 flex justify-end border-t pt-4">
            <Button type="submit" disabled={isSubmitting || !isDirty}>{isSubmitting ? <Spinner /> : <Save />} Save profile</Button>
          </div>
        </form>
      </Panel>
    </div>
  )
}

// ---------------------------------------------------------------------------
function InterestSection({ value }: { value: Settings['interest'] }) {
  const { save, saving } = useSaveSection()
  const [v, setV] = useState(value)
  const [presets, setPresets] = useState(value.rate_presets.join(', '))
  useEffect(() => { setV(value); setPresets(value.rate_presets.join(', ')) }, [value])
  const set = <K extends keyof Settings['interest']>(k: K, x: Settings['interest'][K]) => setV((p) => ({ ...p, [k]: x }))

  const onSave = () => {
    if (!v.flat_enabled && !v.diminishing_enabled) return toast.error('Enable at least one interest method')
    if ((v.default_method === 'flat' && !v.flat_enabled) || (v.default_method === 'diminishing' && !v.diminishing_enabled)) {
      return toast.error('The default method must be enabled')
    }
    if (!numberOk(v.default_rate, 0, 100)) return toast.error('Default rate must be between 0 and 100')
    const list = presets.split(',').map((x) => x.trim()).filter(Boolean).map(Number)
    if (list.some((x) => Number.isNaN(x) || x < 0 || x > 100)) return toast.error('Rate presets must be numbers between 0 and 100')
    if (!v.rate_periods.length) return toast.error('Select at least one rate period')
    if (!v.rate_periods.includes(v.default_rate_period)) return toast.error('The default rate period must be one of the allowed periods')
    void save('interest', { ...v, default_rate: Number(v.default_rate), rate_presets: [...new Set(list)].sort((a, b) => a - b) })
  }

  return (
    <Panel title="Interest settings" description="Defaults offered when creating a loan. Rates are nominal rates for the selected period.">
      <div className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <Toggle label="Flat interest" description="Interest on the original principal for the whole term." checked={v.flat_enabled} onChange={(x) => set('flat_enabled', x)} />
          <Toggle label="Diminishing balance" description="Interest on the outstanding principal each period." checked={v.diminishing_enabled} onChange={(x) => set('diminishing_enabled', x)} />
        </div>
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="Default method">
            <NativeSelect value={v.default_method} onChange={(e) => set('default_method', e.target.value as 'flat' | 'diminishing')}>
              <option value="flat">Flat interest</option>
              <option value="diminishing">Diminishing balance</option>
            </NativeSelect>
          </Field>
          <Field label="Default rate (%)"><Input className="h-10" inputMode="decimal" value={v.default_rate} onChange={(e) => set('default_rate', e.target.value as unknown as number)} /></Field>
          <Field label="Default rate period">
            <NativeSelect value={v.default_rate_period} onChange={(e) => set('default_rate_period', e.target.value as Settings['interest']['default_rate_period'])}>
              {RATE_PERIODS.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
            </NativeSelect>
          </Field>
        </div>
        <Field label="Quick-pick rates (%)" hint="Comma-separated values shown as shortcuts in the loan wizard">
          <Input className="h-10" value={presets} onChange={(e) => setPresets(e.target.value)} />
        </Field>
        <Field label="Allowed rate periods">
          <div className="flex flex-wrap gap-4 pt-1">
            {RATE_PERIODS.map((r) => (
              <label key={r.value} className="flex items-center gap-2 text-sm">
                <Checkbox checked={v.rate_periods.includes(r.value)} onCheckedChange={(c) =>
                  set('rate_periods', c ? [...v.rate_periods, r.value] : v.rate_periods.filter((x) => x !== r.value))} />
                {r.label}
              </label>
            ))}
          </div>
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Day-count basis" hint="30/360 treats months as equal periods; actual bases accrue on real calendar days (leap years included).">
            <NativeSelect value={v.day_count} onChange={(e) => set('day_count', e.target.value)}>
              <option value="30/360">30/360 (periodic)</option>
              <option value="actual/365">Actual/365</option>
              <option value="actual/actual">Actual/actual</option>
            </NativeSelect>
          </Field>
          <Field label="Installment rounding" hint="Amounts are always stored to the centavo; the final installment absorbs rounding differences.">
            <NativeSelect value={v.rounding} onChange={(e) => set('rounding', e.target.value)}>
              <option value="centavo">Round to the centavo (₱0.01)</option>
              <option value="peso">Round installments to the whole peso</option>
            </NativeSelect>
          </Field>
        </div>
      </div>
      <SaveBar saving={saving} onSave={onSave} />
    </Panel>
  )
}

// ---------------------------------------------------------------------------
const ALLOCATION_LABEL: Record<string, string> = { penalty: 'Penalties', charges: 'Charges & fees', interest: 'Interest', principal: 'Principal' }
const SETTLEMENT_RULES: { value: Settings['repayment']['early_settlement_rule']; label: string; desc: string }[] = [
  { value: 'prorated', label: 'Prorated (recommended)', desc: 'Charge interest earned up to the settlement date: past-due interest in full plus a day-based share of the current period. Future interest is rebated.' },
  { value: 'full_period', label: 'Full current period', desc: 'Charge past-due interest plus the full interest of the current period. Later periods are rebated.' },
  { value: 'due_only', label: 'Due interest only', desc: 'Charge only interest of installments already due. The current period\'s interest is rebated.' },
]

function RepaymentSection({ value }: { value: Settings['repayment'] }) {
  const { save, saving } = useSaveSection()
  const [v, setV] = useState(value)
  useEffect(() => setV(value), [value])
  const set = <K extends keyof Settings['repayment']>(k: K, x: Settings['repayment'][K]) => setV((p) => ({ ...p, [k]: x }))
  const move = (i: number, d: -1 | 1) => {
    const order = [...v.allocation_order]
    const j = i + d
    if (j < 0 || j >= order.length) return
    ;[order[i], order[j]] = [order[j], order[i]]
    set('allocation_order', order)
  }
  const onSave = () => {
    if (!v.frequencies.length) return toast.error('Select at least one repayment frequency')
    if (!v.frequencies.includes(v.default_frequency)) return toast.error('The default frequency must be one of the allowed frequencies')
    if (!numberOk(v.grace_days, 0, 60, true)) return toast.error('Grace days must be a whole number from 0 to 60')
    void save('repayment', { ...v, grace_days: Number(v.grace_days) })
  }
  return (
    <div className="space-y-5">
      <Panel title="Repayment schedule">
        <div className="space-y-4">
          <Field label="Allowed repayment frequencies">
            <div className="flex flex-wrap gap-4 pt-1">
              {FREQUENCIES.map((f) => (
                <label key={f.value} className="flex items-center gap-2 text-sm">
                  <Checkbox checked={v.frequencies.includes(f.value)} onCheckedChange={(c) =>
                    set('frequencies', c ? [...v.frequencies, f.value] : v.frequencies.filter((x) => x !== f.value))} />
                  {f.label}
                </label>
              ))}
            </div>
          </Field>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Default frequency">
              <NativeSelect value={v.default_frequency} onChange={(e) => set('default_frequency', e.target.value as Settings['repayment']['default_frequency'])}>
                {FREQUENCIES.map((f) => <option key={f.value} value={f.value}>{f.label}</option>)}
              </NativeSelect>
            </Field>
            <Field label="First due date rule" hint="Can be overridden per loan">
              <NativeSelect value={v.first_due_rule} onChange={(e) => set('first_due_rule', e.target.value)}>
                <option value="one_period">One repayment period after release</option>
              </NativeSelect>
            </Field>
            <Field label="Payment grace period (days)" hint="Informational; penalty grace is set under Late payments">
              <Input className="h-10" inputMode="numeric" value={v.grace_days} onChange={(e) => set('grace_days', e.target.value as unknown as number)} />
            </Field>
            <Field label="Fees & charges" className="sm:col-span-2">
              <NativeSelect value={v.charges_treatment} onChange={(e) => set('charges_treatment', e.target.value as 'added' | 'deducted')}>
                <option value="deducted">Deduct from loan proceeds</option>
                <option value="added">Add to installments (financed)</option>
              </NativeSelect>
            </Field>
          </div>
          <Toggle label="Require a separate approver" description="The staff member who created a loan cannot approve it (owners are exempt)."
            checked={v.require_separate_approver} onChange={(x) => set('require_separate_approver', x)} />
        </div>
      </Panel>
      <Panel title="Payment allocation order" description="How each payment is applied within an installment, oldest installment first.">
        <ol className="space-y-2">
          {v.allocation_order.map((c, i) => (
            <li key={c} className="flex items-center gap-3 rounded-lg border bg-white p-2.5">
              <span className="grid size-7 place-items-center rounded-full bg-[#142B4A] text-xs font-semibold text-white">{i + 1}</span>
              <span className="flex-1 text-sm font-medium">{ALLOCATION_LABEL[c] ?? c}</span>
              <Button size="icon" variant="ghost" className="size-8" disabled={i === 0} onClick={() => move(i, -1)} aria-label="Move up"><ArrowUp /></Button>
              <Button size="icon" variant="ghost" className="size-8" disabled={i === v.allocation_order.length - 1} onClick={() => move(i, 1)} aria-label="Move down"><ArrowDown /></Button>
            </li>
          ))}
        </ol>
      </Panel>
      <Panel title="Early settlement" description="Unearned future interest is never charged automatically.">
        <div className="space-y-2">
          {SETTLEMENT_RULES.map((r) => (
            <label key={r.value} className={`flex cursor-pointer items-start gap-3 rounded-lg border p-3 ${v.early_settlement_rule === r.value ? 'border-blue-500 bg-blue-50/50' : ''}`}>
              <input type="radio" className="mt-1" checked={v.early_settlement_rule === r.value} onChange={() => set('early_settlement_rule', r.value)} />
              <span>
                <span className="block text-sm font-medium">{r.label}</span>
                <span className="block text-xs text-muted-foreground">{r.desc}</span>
              </span>
            </label>
          ))}
        </div>
        <SaveBar saving={saving} onSave={onSave} />
      </Panel>
    </div>
  )
}

// ---------------------------------------------------------------------------
function PenaltySection({ value }: { value: Settings['penalty'] }) {
  const { save, saving } = useSaveSection()
  const [v, setV] = useState(value)
  useEffect(() => setV(value), [value])
  const isFixed = v.type === 'fixed'
  const onSave = () => {
    if (v.enabled) {
      if (!numberOk(v.rate, 0.0001, isFixed ? 1_000_000 : 100)) return toast.error(isFixed ? 'Enter the penalty fee amount' : 'Penalty rate must be between 0 and 100%')
      if (!numberOk(v.grace_days, 0, 90, true)) return toast.error('Grace period must be a whole number from 0 to 90')
    }
    void save('penalty', { ...v, rate: Number(v.rate), grace_days: Number(v.grace_days) })
  }
  return (
    <Panel title="Late payment penalties" description="Defaults for new loans; each loan product can override them. Penalties accrue only on the unpaid installment amount.">
      <div className="space-y-4">
        <Toggle label="Charge late payment penalties" checked={v.enabled} onChange={(x) => setV({ ...v, enabled: x })} />
        {v.enabled && (
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Penalty type" className="sm:col-span-3">
              <NativeSelect value={v.type} onChange={(e) => setV({ ...v, type: e.target.value })}>
                {PENALTY_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
              </NativeSelect>
            </Field>
            <Field label={isFixed ? 'Fee per late installment (₱)' : 'Rate (%)'}>
              <Input className="h-10" inputMode="decimal" value={v.rate} onChange={(e) => setV({ ...v, rate: e.target.value as unknown as number })} />
            </Field>
            <Field label="Grace period (days)" hint="Days after the due date before penalties start">
              <Input className="h-10" inputMode="numeric" value={v.grace_days} onChange={(e) => setV({ ...v, grace_days: e.target.value as unknown as number })} />
            </Field>
          </div>
        )}
        <div className="flex gap-2 rounded-lg bg-amber-50 p-3 text-xs text-amber-900">
          <Info className="mt-0.5 size-4 shrink-0" />
          Late payment charges must be disclosed in the agreement and should be reviewed for compliance with applicable SEC rules on interest and penalty caps.
        </div>
      </div>
      <SaveBar saving={saving} onSave={onSave} />
    </Panel>
  )
}

// ---------------------------------------------------------------------------
/** Edit the terms printed on every loan agreement, with a live preview (Settings -> Terms template). */
function TermsTemplateSection() {
  const templates = useQuery({ queryKey: ['templates'], queryFn: templatesService.list })
  const [picked, setPicked] = useState<string | null>(null)
  if (templates.isLoading) return <PageLoader />
  if (templates.error) return <ErrorState error={templates.error} onRetry={() => void templates.refetch()} />
  const list = templates.data ?? []
  const fallback = list.find((t) => t.is_default && t.is_active) ?? list.find((t) => t.is_active) ?? list[0]
  const selected = picked ?? fallback?.id ?? 'new'
  return (
    <div className="space-y-4">
      <Panel>
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
          <Field label="Template to edit" className="min-w-0 flex-1" hint="The default template is used for new agreements unless a loan product names another one.">
            <NativeSelect value={selected} onChange={(e) => setPicked(e.target.value)}>
              {list.map((t) => <option key={t.id} value={t.id}>{t.name}{t.is_default ? ' (default)' : ''}{t.is_active ? '' : ' — inactive'}</option>)}
              <option value="new">+ New template</option>
            </NativeSelect>
          </Field>
          <Button asChild variant="ghost" className="h-10"><Link to="/agreements/templates">All templates <ExternalLink /></Link></Button>
        </div>
      </Panel>
      <TemplateEditor key={selected} id={selected} embedded onSaved={(id) => setPicked(id)} />
    </div>
  )
}

function TemplatesLinkCard() {
  return (
    <Panel>
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-start gap-3">
          <span className="grid size-10 shrink-0 place-items-center rounded-lg bg-blue-50 text-blue-600"><FileSignature className="size-5" /></span>
          <div>
            <p className="text-sm font-semibold">Agreement template builder</p>
            <p className="text-sm text-muted-foreground">Create, version, preview and set the default loan agreement template.</p>
          </div>
        </div>
        <Button asChild variant="outline"><Link to="/agreements/templates">Open templates</Link></Button>
      </div>
    </Panel>
  )
}

function AgreementSection({ value }: { value: Settings['agreement'] }) {
  const { can } = useAuth()
  const { save, saving } = useSaveSection()
  const [v, setV] = useState(value)
  useEffect(() => setV(value), [value])
  const templates = useQuery({ queryKey: ['templates'], queryFn: templatesService.list })
  const onSave = () => {
    if (!numberOk(v.link_expiry_hours, 1, 720, true)) return toast.error('Link expiry must be between 1 and 720 hours')
    void save('agreement', { ...v, witness_count: Number(v.witness_count), link_expiry_hours: Number(v.link_expiry_hours), default_template_id: v.default_template_id || null })
  }
  return (
    <div className="space-y-5">
      <Panel title="Agreement & signing" description="Applied when agreements are generated for new loans.">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Default agreement template" hint="Products can specify their own template">
            <NativeSelect value={v.default_template_id ?? ''} onChange={(e) => setV({ ...v, default_template_id: e.target.value || null })}>
              <option value="">Organization default template</option>
              {templates.data?.filter((t) => t.is_active).map((t) => <option key={t.id} value={t.id}>{t.name}{t.is_default ? ' (default)' : ''}</option>)}
            </NativeSelect>
          </Field>
          <Field label="Signing order">
            <NativeSelect value={v.signing_order} onChange={(e) => setV({ ...v, signing_order: e.target.value as Settings['agreement']['signing_order'] })}>
              <option value="borrower_first">Borrower signs first</option>
              <option value="lender_first">Lender signs first</option>
              <option value="any">Any order</option>
            </NativeSelect>
          </Field>
          <Field label="Borrower identity verification" hint="Asked before the borrower can view and sign">
            <NativeSelect value={v.identity_verification} onChange={(e) => setV({ ...v, identity_verification: e.target.value as Settings['agreement']['identity_verification'] })}>
              <option value="dob">Confirm date of birth</option>
              <option value="id_last4">Confirm last 4 characters of ID number</option>
              <option value="none">No verification (link only)</option>
            </NativeSelect>
          </Field>
          <Field label="Witnesses">
            <NativeSelect value={v.witness_count} onChange={(e) => setV({ ...v, witness_count: Number(e.target.value) })}>
              <option value={0}>No witnesses</option>
              <option value={1}>1 witness</option>
              <option value={2}>2 witnesses</option>
            </NativeSelect>
          </Field>
          <Field label="Signing link expiry (hours)" hint="1 – 720 hours">
            <Input className="h-10" inputMode="numeric" value={v.link_expiry_hours} onChange={(e) => setV({ ...v, link_expiry_hours: e.target.value as unknown as number })} />
          </Field>
          <div className="sm:col-span-2">
            <Toggle label="Require lender signature" description="Both borrower and lender must sign before the loan can be released."
              checked={v.require_lender_signature} onChange={(x) => setV({ ...v, require_lender_signature: x })} />
          </div>
          <div className="sm:col-span-2">
            <Toggle label="Co-maker signs the agreement" description="When the borrower has a co-maker on file, the co-maker must also sign — in person on your device or through a signing link."
              checked={v.require_co_maker_signature !== false} onChange={(x) => setV({ ...v, require_co_maker_signature: x })} />
          </div>
        </div>
        <SaveBar saving={saving} onSave={onSave} />
      </Panel>
      {can('templates.manage') && <TemplatesLinkCard />}
    </div>
  )
}

// ---------------------------------------------------------------------------
function ReceiptSection({ value }: { value: Settings['receipt'] }) {
  const { save, saving } = useSaveSection()
  const [note, setNote] = useState(value.footer_note ?? '')
  useEffect(() => setNote(value.footer_note ?? ''), [value])
  return (
    <Panel title="Payment receipts" description="Footer text printed on every receipt.">
      <Field label="Receipt footer note" hint={`${note.length}/300 characters`}>
        <Textarea rows={3} maxLength={300} value={note} onChange={(e) => setNote(e.target.value)} />
      </Field>
      <SaveBar saving={saving} onSave={() => void save('receipt', { footer_note: note.trim() })} dirty={note !== (value.footer_note ?? '')} />
    </Panel>
  )
}

function SecuritySection({ value }: { value: Settings['security'] }) {
  const { save, saving } = useSaveSection()
  const [v, setV] = useState(value)
  useEffect(() => setV(value), [value])
  const onSave = () => {
    if (!numberOk(v.session_timeout_minutes, 0, 1440, true)) return toast.error('Session timeout must be 0–1440 minutes (0 disables it)')
    if (!numberOk(v.password_min_length, 8, 64, true)) return toast.error('Minimum password length must be 8–64')
    void save('security', { ...v, session_timeout_minutes: Number(v.session_timeout_minutes), password_min_length: Number(v.password_min_length) })
  }
  return (
    <Panel title="Security" description="Row Level Security isolates each organization; these settings control sessions and staff passwords.">
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Idle session timeout (minutes)" hint="Users are signed out after inactivity. 0 = never.">
          <Input className="h-10" inputMode="numeric" value={v.session_timeout_minutes} onChange={(e) => setV({ ...v, session_timeout_minutes: e.target.value as unknown as number })} />
        </Field>
        <Field label="Minimum staff password length">
          <Input className="h-10" inputMode="numeric" value={v.password_min_length} onChange={(e) => setV({ ...v, password_min_length: e.target.value as unknown as number })} />
        </Field>
        <div className="sm:col-span-2">
          <Toggle label="Audit logging" description="Financial and document changes are always recorded in the immutable ledger and audit trail; this flag is kept for reporting."
            checked={v.audit_logging} onChange={(x) => setV({ ...v, audit_logging: x })} />
        </div>
      </div>
      <SaveBar saving={saving} onSave={onSave} />
    </Panel>
  )
}

function SampleDataSection() {
  const qc = useQueryClient()
  const [open, setOpen] = useState(false)
  return (
    <Panel title="Sample data" description="For training and demos only.">
      <p className="text-sm text-muted-foreground">
        Loads 13 sample borrowers and 14 loans in every stage — drafts, applications, active loans with on-time, late and partial payments,
        an early settlement, a completed loan and a defaulted loan. Payments go through the real payment engine, so dashboards and reports
        show realistic figures. Demo loans are activated without signed agreements and are marked as sample data.
      </p>
      <div className="mt-4 flex gap-2 rounded-lg bg-amber-50 p-3 text-xs text-amber-900">
        <Info className="mt-0.5 size-4 shrink-0" />
        Sample financial records follow the same no-delete rules as real records. Load them only in a test or training workspace.
      </div>
      <div className="mt-4 flex justify-end"><Button variant="outline" onClick={() => setOpen(true)}><Database /> Load sample data</Button></div>
      <ConfirmDialog open={open} onOpenChange={setOpen} title="Load sample data?" confirmLabel="Load sample data"
        description="Sample borrowers, loans and payments will be added to this organization. This cannot be undone."
        onConfirm={async () => {
          const r = await orgService.seedDemoData()
          toast.success(`Loaded ${r.borrowers} borrowers and ${r.loans} loans`)
          await qc.invalidateQueries()
        }} />
    </Panel>
  )
}

// ---------------------------------------------------------------------------
function SimplifiedSection({ value, settings }: { value: Settings['simplified']; settings: Settings }) {
  const { organization } = useAuth()
  const { save, saving } = useSaveSection()
  const [v, setV] = useState(value)
  useEffect(() => setV(value), [value])
  const active = organization?.lending_mode === 'simplified'
  const onSave = () => {
    if (!numberOk(v.default_term, 1, 360, true)) return toast.error('Default repayment period must be a whole number of months from 1 to 360')
    if (v.processing_fee_type !== 'none' && !numberOk(v.processing_fee_value, 0, v.processing_fee_type === 'percent' ? 50 : 1_000_000)) {
      return toast.error(v.processing_fee_type === 'percent' ? 'Fee percentage must be between 0 and 50' : 'Enter the fee amount')
    }
    void save('simplified', {
      ...v,
      default_term: Number(v.default_term),
      processing_fee_value: v.processing_fee_type === 'none' ? 0 : Number(v.processing_fee_value),
    })
  }
  return (
    <div className="space-y-4">
      <div className={`flex gap-3 rounded-xl border p-4 text-sm ${active ? 'border-teal-200 bg-teal-50 text-teal-900' : 'bg-slate-50 text-slate-700'}`}>
        <Lock className="mt-0.5 size-4 shrink-0" />
        <div>
          <p className="font-semibold">Lending mode: {active ? 'Simplified Lending' : 'Complete Lending'}</p>
          <p className="text-xs">
            Set by your LendFlow platform administrator. {active
              ? 'Every user creates loans with the New Loan / Reloan-Renew workflow below.'
              : 'These settings apply only if your account is switched to Simplified Lending.'}
          </p>
        </div>
      </div>
      <Panel title="Simplified lending rules" description="Applied automatically to every simplified loan. Staff enter only the amount and the duration.">
        <div className="space-y-4">
          <div className="grid gap-3 rounded-lg bg-slate-50 p-3 text-sm sm:grid-cols-3">
            <div><p className="text-xs text-muted-foreground">Interest rate (Interest tab)</p><p className="font-semibold">{settings.interest.default_rate}% per {settings.interest.default_rate_period.replace('ly', '').replace('dai', 'day')}</p></div>
            <div><p className="text-xs text-muted-foreground">Method</p><p className="font-semibold">{settings.interest.default_method === 'flat' ? 'Flat' : 'Diminishing balance'}</p></div>
            <div><p className="text-xs text-muted-foreground">Payments (Repayment tab)</p><p className="font-semibold capitalize">{settings.repayment.default_frequency}</p></div>
          </div>
          <Toggle label="Allow staff to change the interest rate per loan"
            description="Off: every simplified loan uses the default rate above and the server rejects any other rate."
            checked={v.allow_rate_override} onChange={(x) => setV({ ...v, allow_rate_override: x })} />
          <Toggle label="Approve at creation when the user has approval rights"
            description="Owners and admins skip the separate review step. Segregation-of-duties rules in Repayment still apply."
            checked={v.approve_on_create} onChange={(x) => setV({ ...v, approve_on_create: x })} />
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Default repayment period (months)" hint="Suggested on new loans; installments follow the payment frequency">
              <Input className="h-10" inputMode="numeric" value={v.default_term} onChange={(e) => setV({ ...v, default_term: e.target.value as unknown as number })} />
            </Field>
            <Field label="Processing fee">
              <NativeSelect value={v.processing_fee_type} onChange={(e) => setV({ ...v, processing_fee_type: e.target.value as Settings['simplified']['processing_fee_type'] })}>
                <option value="none">None</option>
                <option value="percent">Percent of principal</option>
                <option value="fixed">Fixed amount</option>
              </NativeSelect>
            </Field>
            <Field label={v.processing_fee_type === 'percent' ? 'Fee (%)' : 'Fee (₱)'} hint={`Collected: ${settings.repayment.charges_treatment === 'added' ? 'added to installments' : 'deducted from proceeds'}`}>
              <Input className="h-10" inputMode="decimal" disabled={v.processing_fee_type === 'none'} value={v.processing_fee_value}
                onChange={(e) => setV({ ...v, processing_fee_value: e.target.value as unknown as number })} />
            </Field>
          </div>
          <div className="flex gap-2 rounded-lg bg-blue-50 p-3 text-xs text-blue-900">
            <Info className="mt-0.5 size-4 shrink-0" />
            Renewals deduct the borrower's outstanding balance (early-settlement payoff, Repayment tab) from the new loan and settle the previous loan in full when the new loan is released.
          </div>
        </div>
        <SaveBar saving={saving} onSave={onSave} />
      </Panel>
    </div>
  )
}
