// Simplified Lending: New Loan / Reloan-Renew in a few guided steps. The business's lending mode
// (set by the platform administrator) decides that this page is used; staff never pick the mode.
// Every figure — rate, fees, outstanding balances, deductions — is computed by the database.
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useForm, useWatch, type UseFormReturn } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  AlertTriangle, ArrowLeft, ArrowRight, Banknote, Check, CheckCircle2, ChevronDown, ClipboardCheck, CreditCard, FileSignature, History, IdCard,
  Info, Lock, PenLine, RefreshCw, RotateCcw, Search, ShieldCheck, Smartphone, Users,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  EmptyState, ErrorState, Field, KeyValue, LoanStatusBadge, NativeSelect, PageHeader, PageLoader, Panel, Pill, Spinner, useDebounced,
} from '@/components/common'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import { FileDrop } from '@/components/common/FileDrop'
import { MoneyInput } from '@/components/common/MoneyInput'
import { PdfViewer } from '@/components/common/PdfViewer'
import { SignaturePad, type SignaturePadHandle } from '@/components/common/SignaturePad'
import { useAuth } from '@/features/auth/AuthProvider'
import { borrowersService } from '@/services/borrowers'
import { agreementsService } from '@/services/agreements'
import { loansService } from '@/services/loans'
import { simplifiedService, type SimplifiedLoanInput, type SimplifiedLoanResult, type TransactionType } from '@/services/simplified'
import { PH_ID_TYPES, RELATIONSHIPS } from '@/lib/constants'
import { fmtDate, FREQUENCY_LABEL, initials, METHOD_LABEL, normalizePhMobile, peso, phMobile, toNumber, trimNumber } from '@/lib/format'
import { FREQUENCIES } from '@/lib/constants'
import { errorMessage } from '@/lib/errors'
import { cn } from '@/lib/utils'
import type { Borrower, SimplifiedBorrowerStatus, SimplifiedPreview } from '@/types/db'
import { ScheduleTable } from '../LoanPreview'
import { ReleaseDialog } from '../LoanActions'
import { clearDraft, draftFiles, loadDraft, saveDraft, useAutosave, useWizardSteps } from '../wizard'
import { NetReleaseTable, paymentLabel } from './shared'
import { TransactionOptions } from './TransactionOptions'
import { DEFAULT_PAY_DAYS, nextPayDates, PayDaysPicker, type PayDays } from '../PayDaysPicker'

type StepKey = 'type' | 'borrower' | 'search' | 'balance' | 'loan' | 'review' | 'agreement'
const STEPS: Record<TransactionType, { key: StepKey; label: string }[]> = {
  new: [
    { key: 'type', label: 'Transaction' }, { key: 'borrower', label: 'Borrower' }, { key: 'loan', label: 'Loan details' },
    { key: 'review', label: 'Review' }, { key: 'agreement', label: 'Agreement & signature' },
  ],
  renewal: [
    { key: 'type', label: 'Transaction' }, { key: 'search', label: 'Find borrower' }, { key: 'balance', label: 'Balance check' },
    { key: 'loan', label: 'Loan details' }, { key: 'review', label: 'Review' }, { key: 'agreement', label: 'Agreement & signature' },
  ],
}

const mobile = (msg: string) => z.string().trim().refine((v) => /^\+639\d{9}$/.test(normalizePhMobile(v)), msg)
const borrowerSchema = z.object({
  first_name: z.string().trim().min(1, 'Enter the first name'),
  middle_name: z.string().trim(),
  last_name: z.string().trim().min(1, 'Enter the last name'),
  address: z.string().trim().min(8, 'Enter the complete address'),
  mobile: mobile('Use a PH mobile number, e.g. 0917 123 4567'),
  employer: z.string().trim().min(2, 'Enter the company or employer'),
  id_type: z.string().min(1, 'Select the ID type'),
  id_number: z.string().trim().min(3, 'Enter the ID number'),
  co_name: z.string().trim().min(3, "Enter the co-maker's full name"),
  co_relationship: z.string().min(1, 'Select the relationship'),
  co_mobile: mobile("Enter the co-maker's PH mobile number"),
  co_address: z.string().trim(),
})
type BorrowerDraft = z.infer<typeof borrowerSchema>
type Files = { photo: File | null; id: File | null; atm: File | null }

/** Preview URL for a picked file; created and revoked in the same effect so it survives remounts. */
function useObjectUrl(file: File | null) {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    if (!file) { setUrl(null); return }
    const u = URL.createObjectURL(file)
    setUrl(u)
    return () => URL.revokeObjectURL(u)
  }, [file])
  return url
}

type SimplifiedDraft = {
  v: 1
  step: StepKey
  type: TransactionType | null
  borrower: BorrowerDraft
  borrowerId: string | null
  principal: string
  term: string
  rate: string
  frequency?: string
  payDays?: PayDays
  firstDue?: string | null
  createdBorrowerId: string | null
  uploadsDone: boolean
  created: SimplifiedLoanResult | null
  agreementId: string | null
}
const EMPTY_BORROWER: BorrowerDraft = {
  first_name: '', middle_name: '', last_name: '', address: '', mobile: '', employer: '', id_type: '', id_number: '',
  co_name: '', co_relationship: '', co_mobile: '', co_address: '',
}
const NO_FILES: Files = { photo: null, id: null, atm: null }

export default function SimplifiedLoanPage() {
  const navigate = useNavigate()
  const { settings, can, access } = useAuth()
  const { step, go, back, params } = useWizardSteps<StepKey>('type')
  const type = (params.get('type') as TransactionType | null) ?? null
  const draftKey = `simplified:${access?.user_id ?? 'anon'}`

  // Restore saved entries once. A "Reloan" link for a specific borrower starts fresh unless the
  // saved draft is for that same borrower.
  const [initial] = useState<SimplifiedDraft | null>(() => {
    const d = loadDraft<SimplifiedDraft>(draftKey)
    if (!d || d.v !== 1) return null
    const linked = params.get('borrower')
    if (linked && d.borrowerId && d.borrowerId !== linked && !params.get('step')) {
      clearDraft(draftKey)
      return null
    }
    // Picked a transaction in the New loan popup: a draft of the other kind is discarded, unless
    // its loan was already created (that one must be finished first).
    const wanted = params.get('type')
    if (wanted && !params.get('step') && d.type && d.type !== wanted && !d.created) {
      clearDraft(draftKey)
      return null
    }
    return d
  })
  // only announce a restore when something was actually entered
  const [restored, setRestored] = useState(!!initial && initial.step !== 'type' && (
    !!initial.created || !!initial.borrowerId || initial.principal !== '' || Object.values(initial.borrower).some((v) => v.trim() !== '')
  ))

  // new borrower
  const form = useForm<BorrowerDraft>({
    resolver: zodResolver(borrowerSchema),
    mode: 'onTouched',
    defaultValues: initial?.borrower ?? EMPTY_BORROWER,
  })
  const [files, setFilesState] = useState<Files>(() => draftFiles.get<Files>(draftKey) ?? NO_FILES)
  const setFiles: React.Dispatch<React.SetStateAction<Files>> = (u) => setFilesState((prev) => {
    const next = typeof u === 'function' ? u(prev) : u
    draftFiles.set(draftKey, next)
    return next
  })
  const [fileErrors, setFileErrors] = useState<Partial<Record<keyof Files, string>>>({})
  const [createdBorrowerId, setCreatedBorrowerId] = useState<string | null>(initial?.createdBorrowerId ?? null)
  const [uploadsDone, setUploadsDone] = useState(initial?.uploadsDone ?? false)

  // renewal borrower
  const [borrowerId, setBorrowerId] = useState<string | null>(initial?.borrowerId ?? params.get('borrower'))

  // loan
  const [principal, setPrincipal] = useState(initial?.principal ?? '')
  const [term, setTerm] = useState(initial?.term ?? String(settings?.simplified?.default_term ?? 6))
  const [rate, setRate] = useState(initial?.rate ?? String(settings?.interest.default_rate ?? ''))
  const [frequency, setFrequency] = useState<string>(initial?.frequency ?? settings?.repayment.default_frequency ?? 'monthly')
  const [payDays, setPayDays] = useState<PayDays>(initial?.payDays ?? DEFAULT_PAY_DAYS)
  const [firstDue, setFirstDue] = useState<string | null>(initial?.firstDue ?? null)
  const [created, setCreated] = useState<SimplifiedLoanResult | null>(initial?.created ?? null)
  const [agreementId, setAgreementId] = useState<string | null>(initial?.agreementId ?? null)

  const startType = (t: TransactionType, replace = false) => {
    if (t === 'new' && !(can('borrowers.create') && can('borrowers.view_sensitive'))) {
      toast.error('Registering a new borrower needs permission to create borrowers and handle identity documents.')
      if (replace) go('type', { replace: true, extra: { type: null } })
      return
    }
    go(t === 'new' ? 'borrower' : borrowerId ? 'balance' : 'search', { replace, extra: { type: t } })
  }

  // entry point: resume a saved draft, open a "Reloan" link on the balance step, or start the
  // transaction picked in the New loan popup
  useEffect(() => {
    if (params.get('step')) return
    if (initial && initial.step !== 'type' && initial.type) {
      go(initial.step, { replace: true, extra: { type: initial.type } })
    } else if (params.get('borrower')) {
      go('balance', { replace: true, extra: { type: 'renewal' } })
    } else if (type) {
      startType(type, true)
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (settings) {
      setTerm((t) => t || String(settings.simplified?.default_term ?? 6))
      setRate((r) => r || String(settings.interest.default_rate))
    }
  }, [settings])

  // autosave everything typed so far (sessionStorage: this tab only, cleared on sign-out)
  const borrowerValues = useWatch({ control: form.control }) as BorrowerDraft
  const draft: SimplifiedDraft = {
    v: 1, step, type, borrower: { ...EMPTY_BORROWER, ...borrowerValues }, borrowerId, principal, term, rate, frequency, payDays, firstDue,
    createdBorrowerId, uploadsDone, created, agreementId,
  }
  const [finished, setFinished] = useState(false)
  useAutosave(!finished, draft, () => saveDraft(draftKey, draft))

  const startOver = () => {
    clearDraft(draftKey)
    form.reset(EMPTY_BORROWER)
    setFilesState(NO_FILES)
    setFileErrors({})
    setCreatedBorrowerId(null)
    setUploadsDone(false)
    setBorrowerId(null)
    setPrincipal('')
    setTerm(String(settings?.simplified?.default_term ?? 6))
    setRate(String(settings?.interest.default_rate ?? ''))
    setFrequency(settings?.repayment.default_frequency ?? 'monthly')
    setPayDays(DEFAULT_PAY_DAYS)
    setFirstDue(null)
    setCreated(null)
    setAgreementId(null)
    setRestored(false)
    go('type', { replace: true, extra: { type: null, borrower: null } })
  }

  const steps = type ? STEPS[type] : STEPS.new.slice(0, 1)
  const stepIndex = steps.findIndex((s) => s.key === step)
  const locked = !!created // once the loan exists, earlier steps are read-only

  const status = useQuery({
    queryKey: ['simplified', 'borrower-status', borrowerId],
    queryFn: () => simplifiedService.borrowerStatus(borrowerId!),
    enabled: type === 'renewal' && !!borrowerId,
  })

  const allowOverride = !!settings?.simplified?.allow_rate_override
  const input: SimplifiedLoanInput | null = useMemo(() => {
    const p = Number(principal)
    const t = Number(term)
    if (!type || !(p > 0) || !Number.isInteger(t) || t < 1 || t > 360) return null
    if (type === 'renewal' && !borrowerId) return null
    const r = Number(rate)
    const semi = frequency === 'semimonthly'
    return {
      transaction_type: type,
      borrower_id: type === 'renewal' ? borrowerId! : undefined,
      principal: Math.round(p * 100) / 100,
      term_months: t,
      repayment_frequency: frequency,
      pay_days: semi ? payDays : undefined,
      // a saved first payment date that is no longer offered (e.g. a draft resumed days later) falls back to the earliest
      first_due_date: semi && firstDue && nextPayDates(payDays).includes(firstDue) ? firstDue : null,
      interest_rate: allowOverride && rate !== '' && Number.isFinite(r) ? r : null,
    }
  }, [type, principal, term, rate, frequency, payDays, firstDue, borrowerId, allowOverride])

  // Browser history can land on any step: send it to the nearest step whose data exists.
  useEffect(() => {
    const fix = (k: StepKey) => go(k, { replace: true })
    if (created && step !== 'agreement') return fix('agreement')
    if (step === 'type') return
    if (!type) return fix('type')
    if (!steps.some((s) => s.key === step)) return fix(type === 'new' ? 'borrower' : 'search')
    if (step === 'balance' && !borrowerId) return fix('search')
    if ((step === 'loan' || step === 'review') && type === 'renewal' && !borrowerId) return fix('search')
    if (step === 'review' && !input) return fix('loan')
    if (step === 'agreement' && !created) return fix(input ? 'review' : 'loan')
  }, [step, type, created, borrowerId, input]) // eslint-disable-line react-hooks/exhaustive-deps

  const debounced = useDebounced(input ? JSON.stringify(input) : null, 400)
  const preview = useQuery({
    queryKey: ['simplified', 'preview', debounced],
    queryFn: () => simplifiedService.preview(JSON.parse(debounced!)),
    enabled: !!debounced && (step === 'loan' || step === 'review'),
    placeholderData: keepPreviousData,
    retry: false,
  })
  const previewFresh = !!input && debounced === JSON.stringify(input) && preview.isSuccess && !preview.isFetching

  const borrowerName = type === 'renewal'
    ? status.data?.borrower.full_name ?? ''
    : [borrowerValues.first_name, borrowerValues.middle_name, borrowerValues.last_name].filter((x) => x?.trim()).join(' ')

  if (!settings) return <PageLoader />

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        title={type === 'renewal' ? 'Reloan / Renew' : type === 'new' ? 'New loan' : 'Create a loan'}
        description={<span className="inline-flex flex-wrap items-center gap-2">Simplified lending <Pill tone="teal" dot={false}>Rate {trimNumber(settings.interest.default_rate, 4)}% / {settings.interest.default_rate_period}</Pill> <span className="text-muted-foreground">Rates and fees follow your business settings; every figure is computed on the server.</span></span>}
        back={<button onClick={() => navigate(-1)} className="mb-2 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"><ArrowLeft className="size-3.5" /> Back</button>}
        actions={step !== 'type' && !created && <Button variant="ghost" size="sm" onClick={startOver}><RotateCcw /> Start over</Button>}
      />

      {restored && (
        <div className="mb-4 flex flex-wrap items-center gap-2 rounded-xl border border-blue-200 bg-blue-50 px-4 py-2.5 text-sm text-blue-900">
          <History className="size-4" />
          <span className="flex-1">{created ? `Resumed loan ${created.loan_number} where you left off.` : 'Your unsaved entries were restored.'}{!created && type === 'new' && !files.photo && !files.id && !files.atm && (initial?.borrower.first_name ?? '') !== '' ? ' Attached files are not kept after a page reload — check the photo, ID and ATM card.' : ''}</span>
          {!created && <Button variant="outline" size="sm" onClick={startOver}>Start over</Button>}
          <Button variant="ghost" size="sm" onClick={() => setRestored(false)}>Dismiss</Button>
        </div>
      )}

      {type && (
        <ol className={cn('mb-5 grid gap-2', type === 'new' ? 'grid-cols-5' : 'grid-cols-6')}>
          {steps.map((s, i) => (
            <li key={s.key}>
              <button type="button" disabled={i > stepIndex || (locked && s.key !== 'agreement')} onClick={() => i < stepIndex ? back(s.key) : go(s.key)}
                className="flex w-full flex-col gap-1.5 text-left disabled:cursor-default">
                <span className={cn('h-1.5 rounded-full', i <= stepIndex ? 'bg-[#2563EB]' : 'bg-slate-200')} />
                <span className={cn('flex items-center gap-1.5 text-xs font-medium', i === stepIndex ? 'text-[#142B4A]' : 'text-muted-foreground')}>
                  <span className={cn('grid size-5 shrink-0 place-items-center rounded-full text-[10px]', i < stepIndex ? 'bg-[#2563EB] text-white' : i === stepIndex ? 'bg-[#142B4A] text-white' : 'bg-slate-200 text-slate-600')}>
                    {i < stepIndex ? <Check className="size-3" /> : i + 1}
                  </span>
                  <span className="hidden truncate md:inline">{s.label}</span>
                </span>
              </button>
            </li>
          ))}
        </ol>
      )}

      {step === 'type' && <TypeStep onPick={(t) => startType(t)} />}

      {step === 'borrower' && type === 'new' && (
        <NewBorrowerStep form={form} files={files} setFiles={setFiles} fileErrors={fileErrors} setFileErrors={setFileErrors}
          onBack={() => back('type')}
          onRenewInstead={(id) => { setBorrowerId(id); go('balance', { extra: { type: 'renewal' } }) }}
          onNext={() => go('loan')} />
      )}

      {step === 'search' && type === 'renewal' && (
        <SearchStep selected={borrowerId} onBack={() => back('type')}
          onSelect={(b) => { setBorrowerId(b.id); go('balance') }} />
      )}

      {step === 'balance' && type === 'renewal' && (
        status.isLoading ? <PageLoader label="Checking outstanding balance…" /> :
        status.error ? <ErrorState error={status.error} onRetry={() => void status.refetch()} /> :
        status.data && <BalanceStep data={status.data} onBack={() => back('search')} onNext={() => go('loan')} />
      )}

      {step === 'loan' && type && (
        <LoanStep type={type} principal={principal} setPrincipal={setPrincipal} term={term} setTerm={setTerm} rate={rate} setRate={setRate}
          frequency={frequency} setFrequency={setFrequency}
          payDays={payDays} setPayDays={setPayDays} firstDue={firstDue} setFirstDue={setFirstDue}
          allowOverride={allowOverride} borrowerName={borrowerName} preview={preview.data} previewError={input ? preview.error : null}
          fetching={preview.isFetching} fresh={previewFresh}
          onBack={() => back(type === 'new' ? 'borrower' : 'balance')}
          onNext={() => {
            if (!input) return toast.error('Enter the loan amount and a repayment period of 1 to 360 whole months')
            if (preview.error) return toast.error(errorMessage(preview.error))
            go('review')
          }} />
      )}

      {step === 'review' && type && input && (
        <ReviewStep type={type} input={input} preview={preview.data} fresh={previewFresh} fetching={preview.isFetching}
          refetch={() => void preview.refetch()}
          draft={type === 'new' ? form.getValues() : null} files={files} status={status.data ?? null}
          onBack={() => back('loan')}
          finalize={async () => {
            if (type === 'new' && (!files.photo || !files.id || !files.atm) && !uploadsDone) {
              back('borrower')
              throw new Error('Re-attach the borrower photo, ID and ATM card before saving.')
            }
            // 1. register the borrower once (retries reuse the record), then the documents
            let bid = type === 'renewal' ? borrowerId! : createdBorrowerId
            if (type === 'new') {
              const v = form.getValues()
              if (!bid) {
                const b = await borrowersService.create(
                  {
                    first_name: v.first_name.trim(), middle_name: v.middle_name.trim() || null, last_name: v.last_name.trim(),
                    mobile_number: normalizePhMobile(v.mobile), address_line: v.address.trim(), employer_name: v.employer.trim(),
                  },
                  { id_type: v.id_type, id_number: v.id_number.trim() },
                  { full_name: v.co_name.trim(), relationship: v.co_relationship, mobile_number: normalizePhMobile(v.co_mobile), address: v.co_address.trim() || null },
                )
                bid = b.id
                setCreatedBorrowerId(b.id)
              }
              if (!uploadsDone) {
                const org = (await borrowersService.get(bid)).organization_id
                if (files.photo) {
                  const path = await borrowersService.uploadBorrowerFile(org, bid, 'profile', files.photo)
                  await borrowersService.update(bid, { profile_photo_path: path })
                }
                if (files.id) {
                  const path = await borrowersService.uploadBorrowerFile(org, bid, 'identity', files.id)
                  await borrowersService.saveIdentity(bid, { id_front_path: path })
                }
                if (files.atm) await borrowersService.addDocument(org, bid, files.atm, 'atm_card', 'ATM card', 'identity')
                setUploadsDone(true)
              }
            }
            // 2. create the loan (server computes everything and settles nothing until release)
            let loan = created
            if (!loan) {
              loan = await simplifiedService.create({ ...input, borrower_id: bid! })
              setCreated(loan)
            }
            // 3. generate the agreement from the existing template
            if (!agreementId) {
              const a = await agreementsService.generate(loan.id)
              setAgreementId(a.id)
            }
            go('agreement')
          }} />
      )}

      {step === 'agreement' && created && (
        agreementId
          ? <AgreementStep loan={created} agreementId={agreementId} borrowerName={borrowerName}
              onFinished={() => { setFinished(true); clearDraft(draftKey) }} />
          : <RetryAgreement loan={created} onDone={(id) => setAgreementId(id)} />
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
function TypeStep({ onPick }: { onPick: (t: TransactionType) => void }) {
  return <TransactionOptions onPick={onPick} />
}

// ---------------------------------------------------------------------------
function NewBorrowerStep({ form, files, setFiles, fileErrors, setFileErrors, onBack, onNext, onRenewInstead }: {
  form: UseFormReturn<BorrowerDraft>
  files: Files
  setFiles: React.Dispatch<React.SetStateAction<Files>>
  fileErrors: Partial<Record<keyof Files, string>>
  setFileErrors: React.Dispatch<React.SetStateAction<Partial<Record<keyof Files, string>>>>
  onBack: () => void
  onNext: () => void
  onRenewInstead: (borrowerId: string) => void
}) {
  const { register, formState: { errors } } = form
  const photoUrl = useObjectUrl(files.photo)
  const idUrl = useObjectUrl(files.id?.type.startsWith('image') ? files.id : null)
  const atmUrl = useObjectUrl(files.atm?.type.startsWith('image') ? files.atm : null)
  const [dups, setDups] = useState<Awaited<ReturnType<typeof borrowersService.checkDuplicates>> | null>(null)
  const [checking, setChecking] = useState(false)

  const pick = (k: keyof Files) => (f: File) => {
    if (f.size > 10 * 1024 * 1024) return toast.error('Files must be 10 MB or smaller')
    if (k === 'photo' && !f.type.startsWith('image')) return toast.error('The borrower photo must be an image')
    setFiles((x) => ({ ...x, [k]: f }))
    setFileErrors((e) => ({ ...e, [k]: undefined }))
  }

  const next = async () => {
    const ok = await form.trigger()
    const fe: Partial<Record<keyof Files, string>> = {}
    if (!files.photo) fe.photo = "Add the borrower's photo"
    if (!files.id) fe.id = 'Upload the ID'
    if (!files.atm) fe.atm = 'Upload the ATM card'
    setFileErrors(fe)
    if (!ok || Object.keys(fe).length) return toast.error('Please complete the highlighted fields')
    const v = form.getValues()
    setChecking(true)
    try {
      const found = await borrowersService.checkDuplicates({
        first_name: v.first_name, last_name: v.last_name, mobile: normalizePhMobile(v.mobile), id_type: v.id_type, id_number: v.id_number,
      })
      if (found.length) setDups(found)
      else onNext()
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setChecking(false)
    }
  }

  return (
    <form className="space-y-5" onSubmit={(e) => { e.preventDefault(); void next() }}>
      <Panel title="Borrower information" description="Registered once and reused for every future renewal.">
        <div className="grid gap-5 md:grid-cols-[180px_1fr]">
          <div>
            <p className="mb-1.5 text-sm font-medium">Borrower's photo <span className="text-red-500">*</span></p>
            <button type="button" onClick={() => document.getElementById('photo-input')?.click()}
              className={cn('grid aspect-square w-full place-items-center overflow-hidden rounded-xl border-2 border-dashed bg-slate-50 text-slate-400 hover:border-blue-300', fileErrors.photo && 'border-red-300')}>
              {photoUrl ? <img src={photoUrl} alt="Borrower" className="size-full object-cover" /> : <span className="flex flex-col items-center gap-1 text-xs"><Smartphone className="size-6" /> Take or upload photo</span>}
            </button>
            <input id="photo-input" type="file" accept="image/*" className="hidden"
              onChange={(e) => { const f = e.target.files?.[0]; if (f) pick('photo')(f); e.target.value = '' }} />
            {fileErrors.photo && <p className="mt-1 text-xs text-red-600">{fileErrors.photo}</p>}
          </div>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="First name" required error={errors.first_name?.message}><Input className="h-11" autoComplete="off" {...register('first_name')} /></Field>
            <Field label="Middle name"><Input className="h-11" autoComplete="off" {...register('middle_name')} /></Field>
            <Field label="Last name" required error={errors.last_name?.message}><Input className="h-11" autoComplete="off" {...register('last_name')} /></Field>
            <Field label="Complete address" required error={errors.address?.message} className="sm:col-span-3">
              <Textarea rows={2} placeholder="House no., street, barangay, city / municipality, province" {...register('address')} />
            </Field>
            <Field label="Contact number" required error={errors.mobile?.message}><Input className="h-11" inputMode="tel" placeholder="0917 123 4567" {...register('mobile')} /></Field>
            <Field label="Company / employer" required error={errors.employer?.message} className="sm:col-span-2"><Input className="h-11" {...register('employer')} /></Field>
          </div>
        </div>
      </Panel>

      <Panel title="Identification & ATM card" description="Stored privately; only staff allowed to view identity documents can open them.">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="ID type" required error={errors.id_type?.message}>
            <NativeSelect {...register('id_type')}>
              <option value="">Select ID…</option>
              {PH_ID_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </NativeSelect>
          </Field>
          <Field label="ID number" required error={errors.id_number?.message}><Input className="h-11" autoComplete="off" {...register('id_number')} /></Field>
          <div>
            <FileDrop onFile={pick('id')} preview={idUrl} label={files.id ? files.id.name : 'Upload ID'} hint="Photo or PDF of the ID · max 10 MB"
              className={cn(fileErrors.id && 'border-red-300')} />
            {fileErrors.id && <p className="mt-1 text-xs text-red-600">{fileErrors.id}</p>}
          </div>
          <div>
            <FileDrop onFile={pick('atm')} preview={atmUrl} label={files.atm ? files.atm.name : 'Upload ATM card'} hint="Front of the card · max 10 MB"
              className={cn(fileErrors.atm && 'border-red-300')} />
            {fileErrors.atm && <p className="mt-1 text-xs text-red-600">{fileErrors.atm}</p>}
          </div>
        </div>
      </Panel>

      <Panel title="Co-maker information">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Co-maker full name" required error={errors.co_name?.message}><Input className="h-11" autoComplete="off" {...register('co_name')} /></Field>
          <Field label="Relationship to borrower" required error={errors.co_relationship?.message}>
            <NativeSelect {...register('co_relationship')}>
              <option value="">Select…</option>
              {RELATIONSHIPS.map((r) => <option key={r} value={r}>{r}</option>)}
            </NativeSelect>
          </Field>
          <Field label="Co-maker contact number" required error={errors.co_mobile?.message}><Input className="h-11" inputMode="tel" placeholder="0918 765 4321" {...register('co_mobile')} /></Field>
          <Field label="Co-maker address"><Input className="h-11" {...register('co_address')} /></Field>
        </div>
      </Panel>

      <div className="flex gap-2 rounded-xl border bg-white p-3">
        <Button type="button" variant="outline" className="h-12" onClick={onBack}><ArrowLeft /> Back</Button>
        <Button type="submit" className="h-12 flex-1 text-base font-semibold" disabled={checking}>{checking ? <Spinner /> : null} Continue to loan details <ArrowRight /></Button>
      </div>

      <Dialog open={!!dups} onOpenChange={(o) => !o && setDups(null)}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2"><AlertTriangle className="size-5 text-amber-500" /> Possible existing borrower</DialogTitle>
            <DialogDescription>Borrower records must not be duplicated. If this person is already registered, use Reloan / Renew instead.</DialogDescription>
          </DialogHeader>
          <ul className="divide-y rounded-lg border">
            {dups?.map((d) => (
              <li key={d.borrower_id} className="flex items-center gap-3 p-3">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{d.full_name}</p>
                  <p className="text-xs text-muted-foreground">{d.borrower_code} · {phMobile(d.mobile_number)} · matched on {d.match_reason.replace(/_/g, ' ')}</p>
                </div>
                <Button size="sm" onClick={() => { setDups(null); onRenewInstead(d.borrower_id) }}><RefreshCw /> Reloan</Button>
              </li>
            ))}
          </ul>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDups(null)}>Edit details</Button>
            {!dups?.some((d) => /id/i.test(d.match_reason)) && (
              <Button variant="ghost" onClick={() => { setDups(null); onNext() }}>It's a different person — continue</Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </form>
  )
}

// ---------------------------------------------------------------------------
function SearchStep({ selected, onSelect, onBack }: { selected: string | null; onSelect: (b: Borrower) => void; onBack: () => void }) {
  const [term, setTerm] = useState('')
  const debounced = useDebounced(term, 250)
  const results = useQuery({ queryKey: ['borrowers', 'search', debounced], queryFn: () => borrowersService.search(debounced, 12) })
  return (
    <Panel title="Search existing borrower" description="Search by name (or borrower code / mobile number). The borrower is not registered again.">
      <div className="relative mb-3">
        <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input autoFocus value={term} onChange={(e) => setTerm(e.target.value)} placeholder="e.g. Dela Cruz" className="h-12 pl-9 text-base" />
      </div>
      {results.isLoading ? <PageLoader /> : !results.data?.length ? (
        <EmptyState icon={<Users />} title="No matching borrowers" description="Check the spelling, or go back and choose New loan for a first-time borrower." />
      ) : (
        <ul className="divide-y rounded-lg border">
          {results.data.map((b) => (
            <li key={b.id}>
              <button onClick={() => onSelect(b)} className={cn('flex w-full items-center gap-3 px-3 py-3 text-left hover:bg-muted/50', selected === b.id && 'bg-blue-50')}>
                <span className="grid size-10 shrink-0 place-items-center rounded-full bg-slate-100 text-sm font-semibold text-slate-700">{initials(b.full_name)}</span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-medium">{b.full_name}</span>
                  <span className="block truncate text-xs text-muted-foreground">{b.borrower_code} · {phMobile(b.mobile_number)}{b.employer_name ? ` · ${b.employer_name}` : ''}</span>
                </span>
                <ArrowRight className="size-4 text-muted-foreground" />
              </button>
            </li>
          ))}
        </ul>
      )}
      <Button variant="outline" className="mt-4 h-11" onClick={onBack}><ArrowLeft /> Back</Button>
    </Panel>
  )
}

// ---------------------------------------------------------------------------
function BorrowerPhoto({ path, name, className }: { path: string | null | undefined; name: string; className?: string }) {
  const url = useQuery({ queryKey: ['borrower-photo', path], queryFn: () => borrowersService.fileUrl(path!), enabled: !!path, staleTime: 9 * 60_000 })
  return url.data
    ? <img src={url.data} alt={name} className={cn('size-14 rounded-full object-cover ring-2 ring-white', className)} />
    : <span className={cn('grid size-14 place-items-center rounded-full bg-[#142B4A]/10 text-base font-semibold text-[#142B4A]', className)}>{initials(name)}</span>
}

function BalanceStep({ data, onBack, onNext }: { data: SimplifiedBorrowerStatus; onBack: () => void; onNext: () => void }) {
  const b = data.borrower
  const blocked = data.open_loans.find((l) => l.pending_renewal_loan)
  return (
    <div className="grid gap-5 *:min-w-0 lg:grid-cols-[1fr_360px]">
      <div className="space-y-5">
        <Panel title="Borrower">
          <div className="flex items-start gap-4">
            <BorrowerPhoto path={b.profile_photo_path} name={b.full_name} />
            <div className="min-w-0 flex-1">
              <p className="text-lg font-semibold">{b.full_name}</p>
              <p className="text-sm text-muted-foreground">{b.borrower_code} · {phMobile(b.mobile_number)}{b.email ? ` · ${b.email}` : ''}</p>
              <p className="text-sm text-muted-foreground">{b.address || 'No address on file'}{b.employer_name ? ` · ${b.employer_name}` : ''}</p>
              {data.co_maker && <p className="mt-1 text-xs text-muted-foreground">Co-maker: {data.co_maker.full_name}{data.co_maker.relationship ? ` (${data.co_maker.relationship})` : ''}</p>}
            </div>
            <Button asChild variant="ghost" size="sm"><Link to={`/borrowers/${b.id}`} target="_blank">Profile</Link></Button>
          </div>
        </Panel>

        <Panel title="Current active loan" description={`Balances read from the ledger as of ${fmtDate(data.as_of)}.`}>
          {!data.open_loans.length ? (
            <div className="flex gap-3 rounded-lg bg-green-50 p-4 text-sm text-green-900">
              <CheckCircle2 className="mt-0.5 size-5 shrink-0" />
              <div><p className="font-semibold">No outstanding balance</p><p>This borrower has no active loan. Continue with a regular renewal — nothing will be deducted.</p></div>
            </div>
          ) : (
            <div className="space-y-3">
              {data.open_loans.map((l) => (
                <div key={l.loan_id} className="rounded-xl border p-4">
                  <div className="flex flex-wrap items-center gap-2">
                    <Link to={`/loans/${l.loan_id}`} target="_blank" className="font-semibold text-[#2563EB] hover:underline">{l.loan_number}</Link>
                    <LoanStatusBadge status={l.status} />
                    {l.pending_renewal_loan && <Pill tone="amber">Already being renewed by {l.pending_renewal_loan}</Pill>}
                  </div>
                  <KeyValue columns={3} className="mt-3" items={[
                    { label: 'Original loan amount', value: peso(l.principal) },
                    { label: 'Released', value: fmtDate(l.release_date) },
                    { label: 'Maturity', value: fmtDate(l.maturity_date) },
                    { label: 'Installment', value: peso(l.installment_amount) },
                    { label: 'Remaining on contract', value: peso(l.contract_balance) },
                    { label: 'Unearned interest rebated', value: peso(l.interest_rebate) },
                  ]} />
                  <div className="mt-3 flex items-center justify-between rounded-lg bg-amber-50 px-3 py-2.5 text-amber-950">
                    <span className="text-sm font-medium">Remaining outstanding balance (payoff today)</span>
                    <span className="tabular text-lg font-semibold">{peso(l.payoff)}</span>
                  </div>
                  <p className="mt-1.5 text-[11px] text-muted-foreground">
                    Principal {peso(l.payoff_principal)} · interest earned {peso(l.payoff_interest)}{toNumber(l.payoff_charges) > 0 ? ` · charges ${peso(l.payoff_charges)}` : ''}{toNumber(l.payoff_penalties) > 0 ? ` · penalties ${peso(l.payoff_penalties)}` : ''}
                  </p>
                </div>
              ))}
            </div>
          )}
          {!!data.pipeline_loans.length && (
            <div className="mt-3 flex gap-2 rounded-lg bg-blue-50 p-3 text-xs text-blue-900">
              <Info className="mt-0.5 size-4 shrink-0" />
              <span>Not yet released: {data.pipeline_loans.map((l) => `${l.loan_number} (${l.status.replace(/_/g, ' ')})`).join(', ')}. Make sure you are not creating a duplicate loan.</span>
            </div>
          )}
        </Panel>

        <Panel title="Previous loan details" actions={<History className="size-4 text-muted-foreground" />}>
          {!data.history.length ? <p className="text-sm text-muted-foreground">No previous loans.</p> : (
            <ul className="divide-y">
              {data.history.map((l) => (
                <li key={l.id} className="flex items-center justify-between gap-3 py-2.5 text-sm">
                  <span className="min-w-0">
                    <Link to={`/loans/${l.id}`} target="_blank" className="font-medium hover:underline">{l.loan_number}</Link>
                    <span className="block text-xs text-muted-foreground">{peso(l.principal, { decimals: 0 })} · released {fmtDate(l.release_date)} · paid {peso(l.total_paid, { decimals: 0 })}{toNumber(l.renewal_deduction) > 0 ? ` · offset ${peso(l.renewal_deduction, { decimals: 0 })}` : ''}</span>
                  </span>
                  <LoanStatusBadge status={l.status} />
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>

      <aside className="space-y-3 lg:sticky lg:top-20 lg:self-start">
        <Panel title="Balance check">
          <div className="space-y-3">
            <div className={cn('rounded-xl p-4', data.has_outstanding ? 'bg-amber-500 text-white' : 'bg-green-600 text-white')}>
              <p className="text-xs uppercase tracking-wide text-white/80">Existing outstanding balance</p>
              <p className="tabular text-2xl font-semibold">{peso(data.total_payoff)}</p>
              <p className="text-xs text-white/80">{data.has_outstanding ? 'Will be deducted from the new loan' : 'Nothing to deduct'}</p>
            </div>
            {data.has_outstanding && (
              <p className="text-xs text-muted-foreground">
                The payoff follows your early-settlement rule ({data.settlement_rule.replace('_', ' ')}). It is recomputed on the release date, when the previous loan is settled in full.
              </p>
            )}
            {blocked && (
              <p className="rounded-lg bg-red-50 p-3 text-xs text-red-700">{blocked.loan_number} is already being renewed by {blocked.pending_renewal_loan}. Release or cancel that loan first.</p>
            )}
            {b.status !== 'active' && <p className="rounded-lg bg-red-50 p-3 text-xs text-red-700">This borrower is {b.status}; loans can only be created for active borrowers.</p>}
            <Button className="h-12 w-full text-base" disabled={!!blocked || b.status !== 'active'} onClick={onNext}>
              {data.has_outstanding ? 'Create renewal loan' : 'Continue with renewal'} <ArrowRight />
            </Button>
            <Button variant="outline" className="h-11 w-full" onClick={onBack}><ArrowLeft /> Choose another borrower</Button>
          </div>
        </Panel>
      </aside>
    </div>
  )
}

// ---------------------------------------------------------------------------
function LoanStep({ type, principal, setPrincipal, term, setTerm, rate, setRate, frequency, setFrequency, payDays, setPayDays, firstDue, setFirstDue, allowOverride, borrowerName, preview, previewError, fetching, fresh, onBack, onNext }: {
  type: TransactionType
  principal: string; setPrincipal: (v: string) => void
  term: string; setTerm: (v: string) => void
  rate: string; setRate: (v: string) => void
  frequency: string; setFrequency: (v: string) => void
  payDays: PayDays; setPayDays: (v: PayDays) => void
  firstDue: string | null; setFirstDue: (v: string | null) => void
  allowOverride: boolean
  borrowerName: string
  preview: SimplifiedPreview | undefined
  previewError: unknown
  fetching: boolean
  fresh: boolean
  onBack: () => void
  onNext: () => void
}) {
  const { settings } = useAuth()
  const freq = frequency
  const enabled = FREQUENCIES.filter((f) => settings?.repayment.frequencies.includes(f.value) || f.value === settings?.repayment.default_frequency)
  const n = Number(term)
  const fee = settings?.simplified
  return (
    <div className="grid gap-5 *:min-w-0 lg:grid-cols-[1fr_400px]">
      <form className="space-y-5" onSubmit={(e) => { e.preventDefault(); onNext() }}>
        <Panel title={type === 'renewal' ? 'New loan details' : 'Loan details'} description={borrowerName ? `For ${borrowerName}` : undefined}>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label={type === 'renewal' ? 'New loan amount (₱)' : 'Loan amount (₱)'} required>
              <MoneyInput placeholder="20,000.00" className="h-12 text-lg" value={principal} onValueChange={setPrincipal} />
            </Field>
            {enabled.length > 1 && (
              <Field label="Collection / payment frequency" className="sm:col-span-2">
                <div role="radiogroup" aria-label="Payment frequency" className="flex flex-wrap gap-2">
                  {enabled.map((f) => (
                    <button key={f.value} type="button" role="radio" aria-checked={freq === f.value} onClick={() => setFrequency(f.value)}
                      className={cn('h-11 rounded-lg border-2 px-4 text-sm font-medium transition', freq === f.value ? 'border-[#2563EB] bg-blue-50 text-[#142B4A]' : 'border-slate-200 hover:border-slate-300')}>
                      {f.label}
                    </button>
                  ))}
                </div>
              </Field>
            )}
            {freq === 'semimonthly' && (
              <div className="sm:col-span-2">
                <PayDaysPicker value={payDays} onChange={setPayDays} firstDue={firstDue} onFirstDueChange={setFirstDue} />
              </div>
            )}
            <Field label="Repayment period (months)" required
              error={term !== '' && !(Number.isInteger(n) && n >= 1 && n <= 360) ? 'Enter a whole number of months from 1 to 360' : undefined}
              hint={installmentsHint(freq, n, preview)}>
              <Input type="number" inputMode="numeric" min={1} max={360} step={1} className="h-12 text-lg" value={term} onChange={(e) => setTerm(e.target.value)} />
            </Field>
            <Field label="Interest rate (%)" hint={allowOverride ? 'Prefilled from your business settings; your settings allow changing it.' : 'Fixed by your business settings'}>
              <div className="relative">
                <Input type="number" inputMode="decimal" step="0.0001" className="h-12 pr-28 text-lg" value={rate} readOnly={!allowOverride}
                  onChange={(e) => setRate(e.target.value)} />
                <span className="pointer-events-none absolute right-3 top-1/2 flex -translate-y-1/2 items-center gap-1 text-xs text-muted-foreground">
                  {!allowOverride && <Lock className="size-3.5" />} per {({ daily: 'day', weekly: 'week', monthly: 'month', yearly: 'year' } as Record<string, string>)[settings?.interest.default_rate_period ?? 'monthly']}
                </span>
              </div>
            </Field>
            <Field label="Method & fees">
              <div className="flex h-12 items-center rounded-md border bg-slate-50 px-3 text-sm text-muted-foreground">
                {METHOD_LABEL[settings?.interest.default_method ?? 'flat']} · {fee?.processing_fee_type === 'percent' ? `${fee.processing_fee_value}% fee` : fee?.processing_fee_type === 'fixed' ? `${peso(fee.processing_fee_value)} fee` : 'no processing fee'}
              </div>
            </Field>
          </div>
          <p className="mt-3 flex gap-2 text-xs text-muted-foreground"><ShieldCheck className="size-4 shrink-0 text-teal-600" /> Interest, fees, penalties and the schedule come from your business settings and are computed on the server.</p>
        </Panel>
        <div className="flex gap-2 rounded-xl border bg-white p-3">
          <Button type="button" variant="outline" className="h-12" onClick={onBack}><ArrowLeft /> Back</Button>
          <Button type="submit" className="h-12 flex-1 text-base font-semibold" disabled={!preview || !!previewError}>
            <ClipboardCheck className="size-5" /> REVIEW LOAN
          </Button>
        </div>
      </form>

      <aside className="space-y-3 lg:sticky lg:top-20 lg:self-start">
        <Panel title="Loan computation" actions={fetching ? <Spinner /> : fresh ? <span className="text-xs text-green-600">Up to date</span> : null}>
          {previewError ? (
            <p className="rounded-lg bg-red-50 p-3 text-sm text-red-700">{errorMessage(previewError)}</p>
          ) : preview ? (
            <div className={cn('space-y-4 transition', !fresh && 'opacity-60')}>
              <ComputationFigures p={preview} />
              {type === 'renewal' && <RenewalTable p={preview} />}
              <Collapsible>
                <CollapsibleTrigger className="flex w-full items-center justify-between rounded-lg border px-3 py-2 text-sm font-medium hover:bg-muted/50">
                  Payment schedule ({preview.schedule.length} payments) <ChevronDown className="size-4 text-muted-foreground" />
                </CollapsibleTrigger>
                <CollapsibleContent className="pt-2"><ScheduleTable rows={preview.schedule} maxHeight={320} /></CollapsibleContent>
              </Collapsible>
            </div>
          ) : (
            <p className="py-6 text-center text-sm text-muted-foreground">Enter the amount and repayment period to see the computation.</p>
          )}
        </Panel>
      </aside>
    </div>
  )
}

/** Hint under the months field: how many collections the period produces (from the server preview when available). */
function installmentsHint(freq: string, months: number, preview: SimplifiedPreview | undefined) {
  if (!(Number.isInteger(months) && months > 0)) return freq === 'semimonthly' ? 'Collected twice a month on the pay days above' : undefined
  const s = preview?.summary
  if (s && s.term_months === months && s.repayment_frequency === freq) {
    return `${s.installment_count} ${FREQUENCY_LABEL[freq] ?? freq} payment${s.installment_count === 1 ? '' : 's'}`
  }
  if (freq === 'monthly') return `${months} monthly payment${months === 1 ? '' : 's'}`
  if (freq === 'semimonthly') return `${months * 2} semi-monthly payments (twice a month)`
  return `${FREQUENCY_LABEL[freq] ?? freq} payments on every due date within ${months} month${months === 1 ? '' : 's'}`
}

function ComputationFigures({ p }: { p: SimplifiedPreview }) {
  const s = p.summary
  const monthly = s.monthly_rate_pct ?? (s.rate_period === 'monthly' ? s.interest_rate : null)
  const rows: [string, string][] = [
    ['Principal amount', peso(s.principal)],
    ['Monthly interest rate', monthly != null
      ? `${trimNumber(monthly, 4)}% per month${s.rate_period !== 'monthly' ? ` (${trimNumber(s.interest_rate, 4)}% per ${s.rate_period.replace('ly', '')})` : ''}${p.rate_overridden ? ' (override)' : ''}`
      : `${trimNumber(s.interest_rate, 4)}% per ${s.rate_period}`],
    ['Total interest', peso(s.total_interest)],
    ['Fees & charges', toNumber(s.total_charges) > 0 ? `${peso(s.total_charges)} (${s.charges_treatment === 'added' ? 'added to installments' : 'deducted'})` : 'None'],
    ['Total repayment amount', peso(s.total_repayment)],
    ['Repayment period', s.term_months ? `${s.term_months} month${s.term_months === 1 ? '' : 's'}` : s.duration_label],
    ['Number of installments', `${s.installment_count} · ${FREQUENCY_LABEL[s.repayment_frequency] ?? s.repayment_frequency}`],
    ['Payment dates', `${fmtDate(s.first_due_date)} – ${fmtDate(s.last_due_date)}`],
  ]
  return (
    <div className="space-y-3">
      <div className="rounded-xl bg-[#142B4A] p-4 text-white">
        <p className="text-xs uppercase tracking-wide text-white/60">Payment per collection · {FREQUENCY_LABEL[s.repayment_frequency] ?? s.repayment_frequency}</p>
        <p className="tabular text-3xl font-semibold">{peso(s.installment_amount)}</p>
        {toNumber(s.last_installment_amount) !== toNumber(s.installment_amount) && <p className="text-xs text-white/60">Final payment {peso(s.last_installment_amount)}</p>}
      </div>
      <dl className="divide-y rounded-xl border text-sm">
        {rows.map(([k, v]) => (
          <div key={k} className="flex items-start justify-between gap-3 px-3 py-2">
            <dt className="text-muted-foreground">{k}</dt>
            <dd className="tabular text-right font-medium">{v}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

function RenewalTable({ p }: { p: SimplifiedPreview }) {
  const s = p.summary
  const deducted = s.charges_treatment === 'deducted' ? s.total_charges : 0
  const loans = p.renewal?.loans ?? []
  return (
    <NetReleaseTable principal={s.principal} deductedCharges={deducted} previousBalance={p.renewal_deduction}
      previousLabel={`Less: existing outstanding balance${loans.length ? ` (${loans.map((l) => l.loan_number).join(', ')})` : ''}`}
      netRelease={p.net_release}
      footnote={toNumber(p.renewal_deduction) > 0
        ? `Amount deducted: ${peso(p.renewal_deduction)}. The previous loan is settled in full when this loan is released.`
        : 'No outstanding balance — nothing is deducted.'} />
  )
}

// ---------------------------------------------------------------------------
function ReviewStep({ type, preview, fresh, fetching, refetch, draft, files, status, onBack, finalize }: {
  type: TransactionType
  input: SimplifiedLoanInput
  preview: SimplifiedPreview | undefined
  fresh: boolean
  fetching: boolean
  refetch: () => void
  draft: BorrowerDraft | null
  files: Files
  status: SimplifiedBorrowerStatus | null
  onBack: () => void
  finalize: () => Promise<void>
}) {
  const { settings, can } = useAuth()
  const photoUrl = useObjectUrl(files.photo)
  const [agree, setAgree] = useState(false)
  const run = useMutation({ mutationFn: finalize, onError: (e) => toast.error(errorMessage(e)) })
  if (!preview) return fetching ? <PageLoader label="Computing on the server…" /> : <ErrorState error="Computation unavailable" onRetry={refetch} />
  const s = preview.summary
  const name = draft ? [draft.first_name, draft.middle_name, draft.last_name].filter((x) => x?.trim()).join(' ') : status?.borrower.full_name ?? ''

  return (
    <div className="grid gap-5 *:min-w-0 lg:grid-cols-[1fr_380px]">
      <div className="space-y-5">
        <Panel title="Borrower information">
          <div className="flex items-start gap-4">
            {draft ? (photoUrl ? <img src={photoUrl} alt={name} className="size-16 rounded-full object-cover" /> : null)
              : <BorrowerPhoto path={status?.borrower.profile_photo_path} name={name} className="size-16" />}
            <div className="min-w-0 flex-1">
              <p className="text-lg font-semibold">{name}</p>
              <p className="text-sm text-muted-foreground">{type === 'renewal' ? `${status?.borrower.borrower_code} · Reloan / Renew` : 'New borrower'}</p>
            </div>
          </div>
          <KeyValue columns={2} className="mt-4" items={draft ? [
            { label: 'Complete address', value: draft.address },
            { label: 'Contact number', value: phMobile(normalizePhMobile(draft.mobile)) },
            { label: 'Company / employer', value: draft.employer },
            { label: 'ID', value: `${draft.id_type} · ${draft.id_number}` },
            { label: 'Uploads', value: [files.photo && 'Photo', files.id && 'ID', files.atm && 'ATM card'].filter(Boolean).join(', ') },
            { label: 'Co-maker', value: `${draft.co_name} (${draft.co_relationship}) · ${phMobile(normalizePhMobile(draft.co_mobile))}${draft.co_address ? ` · ${draft.co_address}` : ''}` },
          ] : [
            { label: 'Address', value: status?.borrower.address || '—' },
            { label: 'Contact number', value: phMobile(status?.borrower.mobile_number) },
            { label: 'Company / employer', value: status?.borrower.employer_name || '—' },
            { label: 'Co-maker', value: status?.co_maker ? `${status.co_maker.full_name}${status.co_maker.relationship ? ` (${status.co_maker.relationship})` : ''}` : '—' },
          ]} />
        </Panel>

        <Panel title="Loan details & repayment computation" description="Server-calculated; nothing has been saved yet."
          actions={<Button variant="outline" size="sm" onClick={refetch} disabled={fetching}>{fetching ? <Spinner /> : <RefreshCw />} Recalculate</Button>}>
          <div className={cn('grid gap-5 md:grid-cols-2', !fresh && 'opacity-60')}>
            <ComputationFigures p={preview} />
            {type === 'renewal' ? <RenewalTable p={preview} /> : (
              <NetReleaseTable principal={s.principal} deductedCharges={s.charges_treatment === 'deducted' ? s.total_charges : 0}
                previousBalance={0} netRelease={preview.net_release} />
            )}
          </div>
          {type === 'renewal' && !!preview.renewal?.loans.length && (
            <div className="mt-4 overflow-x-auto rounded-lg border">
              <table className="w-full text-sm">
                <thead className="bg-slate-50 text-xs text-muted-foreground"><tr>
                  <th className="px-3 py-2 text-left font-medium">Previous loan</th><th className="px-3 py-2 text-right font-medium">Contract balance</th>
                  <th className="px-3 py-2 text-right font-medium">Rebate</th><th className="px-3 py-2 text-right font-medium">Settled from new loan</th>
                </tr></thead>
                <tbody className="divide-y">
                  {preview.renewal.loans.map((l) => (
                    <tr key={l.loan_id}><td className="px-3 py-2">{l.loan_number}</td><td className="tabular px-3 py-2 text-right">{peso(l.contract_balance)}</td>
                      <td className="tabular px-3 py-2 text-right">{peso(l.interest_rebate)}</td><td className="tabular px-3 py-2 text-right font-semibold">{peso(l.payoff)}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>

        <Panel title="Payment schedule" description={`${preview.schedule.length} ${(FREQUENCY_LABEL[s.repayment_frequency] ?? s.repayment_frequency)} payments`}>
          <ScheduleTable rows={preview.schedule} maxHeight={360} />
        </Panel>
      </div>

      <aside className="space-y-3 lg:sticky lg:top-20 lg:self-start">
        <Panel title="Confirm loan">
          <div className="space-y-4">
            <div className="rounded-xl bg-[#142B4A] p-4 text-white">
              <p className="text-xs uppercase tracking-wide text-white/60">Borrower receives</p>
              <p className="tabular text-3xl font-semibold">{peso(preview.net_release)}</p>
              <p className="text-xs text-white/60">{paymentLabel(s.repayment_frequency)} {peso(s.installment_amount)} × {s.installment_count}</p>
            </div>
            <label className="flex items-start gap-2 rounded-lg border p-3 text-sm">
              <Checkbox checked={agree} onCheckedChange={(c) => setAgree(!!c)} className="mt-0.5" />
              <span>The borrower reviewed the loan amount, interest, total repayment{type === 'renewal' && toNumber(preview.renewal_deduction) > 0 ? ', the deduction of the previous balance' : ''} and the payment schedule, and agrees to the terms.</span>
            </label>
            <ol className="space-y-1.5 text-xs text-muted-foreground">
              <li>1. The {draft ? 'borrower is registered and the ' : ''}loan is saved{settings?.simplified?.approve_on_create && can('loans.approve') ? ' and approved' : ' and sent for approval'}.</li>
              <li>2. The agreement is generated from your template.</li>
              <li>3. The borrower signs on this device.</li>
            </ol>
            <Button className="h-12 w-full text-base" disabled={!agree || !fresh || run.isPending || !can('agreements.generate')} onClick={() => run.mutate()}>
              {run.isPending ? <Spinner /> : <FileSignature />} Confirm & generate agreement
            </Button>
            {!can('agreements.generate') && <p className="text-xs text-red-600">Your role cannot generate agreements.</p>}
            <Button variant="link" className="w-full" onClick={onBack} disabled={run.isPending}><ArrowLeft /> Edit loan details</Button>
          </div>
        </Panel>
      </aside>
    </div>
  )
}

// ---------------------------------------------------------------------------
function RetryAgreement({ loan, onDone }: { loan: SimplifiedLoanResult; onDone: (id: string) => void }) {
  const gen = useMutation({ mutationFn: () => agreementsService.generate(loan.id), onSuccess: (a) => onDone(a.id), onError: (e) => toast.error(errorMessage(e)) })
  return (
    <EmptyState icon={<FileSignature />} title={`Loan ${loan.loan_number} saved`} description="The agreement has not been generated yet."
      action={<Button onClick={() => gen.mutate()} disabled={gen.isPending}>{gen.isPending ? <Spinner /> : <FileSignature />} Generate agreement</Button>} />
  )
}

function AgreementStep({ loan, agreementId, borrowerName, onFinished }: { loan: SimplifiedLoanResult; agreementId: string; borrowerName: string; onFinished: () => void }) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const { can, access } = useAuth()
  const agreement = useQuery({ queryKey: ['agreement', agreementId], queryFn: () => agreementsService.get(agreementId) })
  const signatures = useQuery({ queryKey: ['agreement', agreementId, 'signatures'], queryFn: () => agreementsService.signatures(agreementId) })
  const a = agreement.data
  const pdfPath = a ? a.signed_pdf_path ?? a.current_pdf_path ?? a.unsigned_pdf_path : null
  const pdf = useQuery({ queryKey: ['agreement', agreementId, 'pdf', pdfPath], queryFn: () => agreementsService.pdfUrl(pdfPath!), enabled: !!pdfPath })
  const loanView = useQuery({ queryKey: ['loan', loan.id], queryFn: () => loansService.get(loan.id) })
  const borrowerId = loanView.data?.borrower_id
  const guarantors = useQuery({ queryKey: ['borrower', borrowerId, 'guarantors'], queryFn: () => borrowersService.guarantors(borrowerId!), enabled: !!borrowerId })
  const coMakerName = guarantors.data?.at(-1)?.full_name ?? ''
  const [releaseOpen, setReleaseOpen] = useState(false)
  const fullySigned = agreement.data?.status === 'fully_signed'
  useEffect(() => { if (fullySigned) onFinished() }, [fullySigned]) // eslint-disable-line react-hooks/exhaustive-deps

  if (agreement.isLoading || signatures.isLoading) return <PageLoader label="Preparing the agreement…" />
  if (agreement.error || !a) return <ErrorState error={agreement.error ?? 'Agreement not found'} onRetry={() => void agreement.refetch()} />

  const signed = new Set((signatures.data ?? []).map((s) => s.signer_role))
  // the co-maker signs right after the borrower, while the device is still with them
  const order = a.signing_order === 'lender_first' ? ['lender', 'borrower', 'co_maker'] : ['borrower', 'co_maker', 'lender']
  const pending = order.filter((r) => a.required_signers.includes(r) && !signed.has(r))
  const next = pending[0]
  const othersPending = a.required_signers.filter((r) => !signed.has(r) && !order.includes(r))
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['agreement', agreementId] })
    void qc.invalidateQueries({ queryKey: ['loan', loan.id] })
    void qc.invalidateQueries({ queryKey: ['loans'] })
  }
  const lv = loanView.data
  const done = a.status === 'fully_signed'

  return (
    <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_400px]">
      <div className="min-w-0 space-y-3">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="text-base font-semibold">{a.agreement_number}</h3>
          <Pill tone={done ? 'green' : 'amber'}>{done ? 'Fully signed' : 'Awaiting signatures'}</Pill>
          <span className="text-xs text-muted-foreground">Loan {loan.loan_number} · {a.page_count ?? '—'} pages</span>
        </div>
        <PdfViewer url={pdf.data} fileName={`${a.agreement_number}.pdf`} height="74vh" />
      </div>

      <div className="space-y-4">
        <Panel title="Loan saved">
          <KeyValue columns={2} items={[
            { label: 'Loan number', value: loan.loan_number },
            { label: 'Status', value: lv ? <LoanStatusBadge status={lv.status} /> : <LoanStatusBadge status={loan.status} /> },
            { label: 'Net to release', value: peso(loan.net_release) },
            { label: 'Deducted balance', value: peso(loan.renewal_deduction) },
          ]} />
        </Panel>

        {!done && next === 'borrower' && (
          can('agreements.generate')
            ? <SignBox key="borrower" title="Borrower signature" signerName={borrowerName} agreementNumber={a.agreement_number}
                consent={`I, ${borrowerName}, have reviewed loan agreement ${a.agreement_number}, including the loan amount, interest, total repayment${toNumber(loan.renewal_deduction) > 0 ? ', the deduction of my previous loan balance' : ''} and the payment schedule, and I sign it electronically. My electronic signature has the same legal effect as a handwritten signature (R.A. 8792).`}
                sign={(png) => agreementsService.signInApp({ agreement_id: a.id, role: 'borrower', signature: png, in_person: true })}
                onSigned={refresh} hint="Hand the device to the borrower. They sign with a finger or stylus." />
            : <Panel title="Borrower signature"><p className="text-sm text-muted-foreground">Your role cannot collect signatures.</p></Panel>
        )}

        {!done && next === 'co_maker' && (
          can('agreements.generate')
            ? <SignBox key="co_maker" title="Co-maker signature" signerName={coMakerName} agreementNumber={a.agreement_number}
                consent={`I, ${coMakerName || 'the co-maker'}, have reviewed loan agreement ${a.agreement_number} of ${borrowerName}, including the loan amount, interest, total repayment and the payment schedule, and I sign it electronically as co-maker. My electronic signature has the same legal effect as a handwritten signature (R.A. 8792).`}
                sign={(png) => agreementsService.signInApp({ agreement_id: a.id, role: 'co_maker', signature: png, in_person: true })}
                onSigned={refresh} hint="Now hand the device to the co-maker. They sign with a finger or stylus." />
            : <Panel title="Co-maker signature"><p className="text-sm text-muted-foreground">Your role cannot collect signatures.</p></Panel>
        )}

        {!done && next === 'lender' && (
          can('agreements.sign_lender')
            ? <SignBox key="lender" title="Lender signature" signerName={access?.full_name ?? ''} agreementNumber={a.agreement_number}
                consent={`I have reviewed agreement ${a.agreement_number} and agree to sign it electronically on behalf of the lender (R.A. 8792).`}
                sign={(png) => agreementsService.signInApp({ agreement_id: a.id, role: 'lender', signature: png })}
                onSigned={refresh} />
            : (
              <Panel title="Lender signature">
                <p className="text-sm text-muted-foreground">The borrower has signed. A user with lender-signing rights completes the agreement from the loan page.</p>
              </Panel>
            )
        )}

        {!done && !next && othersPending.length > 0 && (
          <Panel title="Witness signatures">
            <p className="text-sm text-muted-foreground">Witness signatures are collected from the loan's Agreement tab.</p>
          </Panel>
        )}

        {done && (
          <Panel title="Agreement signed">
            <div className="space-y-3">
              <p className="flex items-center gap-2 text-sm text-green-700"><CheckCircle2 className="size-5" /> Signed agreement saved to the borrower's loan record.</p>
              {lv?.status === 'ready_for_release'
                ? <p className="text-sm text-muted-foreground">The loan is approved and fully signed — ready for disbursement.</p>
                : <p className="text-sm text-muted-foreground">Next: {lv?.status === 'fully_signed' ? 'approval by a user with approval rights, then release.' : 'continue on the loan page.'}</p>}
              {lv?.status === 'ready_for_release' && can('loans.release') && (
                <Button className="h-12 w-full text-base" onClick={() => setReleaseOpen(true)}><Banknote /> Release {peso(loan.net_release)}</Button>
              )}
            </div>
          </Panel>
        )}

        <div className="grid gap-2">
          <Button variant="outline" className="h-11" onClick={() => navigate(`/loans/${loan.id}`)}>Open loan {loan.loan_number} <ArrowRight /></Button>
          {!done && (next === 'borrower' || next === 'co_maker') && (
            <Button variant="ghost" className="h-11" onClick={() => navigate(`/loans/${loan.id}?tab=agreement`)}><Smartphone /> Send a signing link instead</Button>
          )}
        </div>
      </div>
      {lv && <ReleaseDialog loan={lv} open={releaseOpen} onOpenChange={setReleaseOpen} onDone={() => { refresh(); navigate(`/loans/${loan.id}`) }} />}
    </div>
  )
}

function SignBox({ title, signerName, agreementNumber, consent, sign, onSigned, hint }: {
  title: string
  signerName: string
  agreementNumber: string
  consent: string
  sign: (png: string) => Promise<unknown>
  onSigned: () => void
  hint?: string
}) {
  const pad = useRef<SignaturePadHandle>(null)
  const [empty, setEmpty] = useState(true)
  const [ok, setOk] = useState(false)
  const m = useMutation({
    mutationFn: async () => {
      const png = pad.current?.toPng()
      if (!png) throw new Error('Draw the signature first')
      return sign(png)
    },
    onSuccess: () => { toast.success(`${title} embedded in ${agreementNumber}`); onSigned() },
    onError: (e) => toast.error(errorMessage(e)),
  })
  return (
    <Panel title={<span className="flex items-center gap-2"><PenLine className="size-4" /> {title}</span>} description={hint}>
      <div className="space-y-3">
        <p className="text-sm font-semibold">{signerName}</p>
        <SignaturePad ref={pad} onChange={setEmpty} />
        <label className="flex items-start gap-2 text-xs">
          <Checkbox checked={ok} onCheckedChange={(c) => setOk(!!c)} className="mt-0.5" />
          <span>{consent}</span>
        </label>
        <Button className="h-12 w-full" disabled={empty || !ok || m.isPending || !signerName.trim()} onClick={() => m.mutate()}>
          {m.isPending ? <Spinner /> : <IdCard />} Sign agreement
        </Button>
        <p className="flex items-center gap-1 text-[11px] text-muted-foreground"><CreditCard className="size-3" /> The signature, time and witnessing staff member are recorded in the audit trail.</p>
      </div>
    </Panel>
  )
}
