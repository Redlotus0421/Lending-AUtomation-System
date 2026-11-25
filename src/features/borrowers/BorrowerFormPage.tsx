import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { AlertTriangle, ArrowLeft, Lock, Save } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Checkbox } from '@/components/ui/checkbox'
import { ErrorState, Field, NativeSelect, PageHeader, PageLoader, Panel, Spinner, useDebounced } from '@/components/common'
import { FileDrop } from '@/components/common/FileDrop'
import { useAuth } from '@/features/auth/AuthProvider'
import { borrowersService, type BorrowerInput } from '@/services/borrowers'
import { EMPLOYMENT_TYPES, PH_ID_TYPES, PH_REGIONS, RELATIONSHIPS } from '@/lib/constants'
import { normalizePhMobile, phMobile } from '@/lib/format'
import { errorMessage } from '@/lib/errors'
import { useBorrowerFileUrl } from './shared'

const PH_MOBILE = /^\+639\d{9}$/
const optionalMobile = z.string().trim().optional().refine((v) => !v || PH_MOBILE.test(normalizePhMobile(v)), 'Enter a valid PH mobile number (09XX XXX XXXX)')

function age(dob: string) {
  const d = new Date(`${dob}T12:00:00Z`)
  const now = new Date()
  let a = now.getUTCFullYear() - d.getUTCFullYear()
  const m = now.getUTCMonth() - d.getUTCMonth()
  if (m < 0 || (m === 0 && now.getUTCDate() < d.getUTCDate())) a--
  return a
}

const schema = z.object({
  first_name: z.string().trim().min(1, 'First name is required').max(80),
  middle_name: z.string().trim().max(80).optional(),
  last_name: z.string().trim().min(1, 'Last name is required').max(80),
  suffix: z.string().trim().max(10).optional(),
  date_of_birth: z.string().optional().refine((v) => !v || (age(v) >= 18 && age(v) <= 100), 'Borrower must be between 18 and 100 years old'),
  gender: z.string().optional(),
  civil_status: z.string().optional(),
  mobile_number: z.string().trim().min(1, 'Mobile number is required').refine((v) => PH_MOBILE.test(normalizePhMobile(v)), 'Enter a valid PH mobile number (09XX XXX XXXX)'),
  email: z.string().trim().email('Enter a valid email').optional().or(z.literal('')),
  address_line: z.string().trim().optional(),
  barangay: z.string().trim().optional(),
  city_municipality: z.string().trim().optional(),
  province: z.string().trim().optional(),
  region: z.string().optional(),
  zip_code: z.string().trim().optional().refine((v) => !v || /^\d{4}$/.test(v), 'ZIP codes have 4 digits'),
  occupation: z.string().trim().optional(),
  employer_name: z.string().trim().optional(),
  employment_type: z.string().optional(),
  monthly_income: z.string().optional().refine((v) => !v || (!Number.isNaN(Number(v.replace(/,/g, ''))) && Number(v.replace(/,/g, '')) >= 0), 'Enter a valid amount'),
  emergency_contact_name: z.string().trim().optional(),
  emergency_contact_relationship: z.string().optional(),
  emergency_contact_number: optionalMobile,
  internal_notes: z.string().optional(),
  risk_rating: z.enum(['unrated', 'low', 'medium', 'high']),
  assessment_notes: z.string().optional(),
  id_type: z.string().optional(),
  id_number: z.string().trim().optional(),
  id_expiry: z.string().optional(),
  guarantor_name: z.string().trim().optional(),
  guarantor_relationship: z.string().optional(),
  guarantor_mobile: optionalMobile,
  guarantor_address: z.string().trim().optional(),
  guarantor_occupation: z.string().trim().optional(),
}).refine((v) => !v.id_type || !!v.id_number, { path: ['id_number'], message: 'Enter the ID number' })
  .refine((v) => !v.id_number || !!v.id_type, { path: ['id_type'], message: 'Select the ID type' })

type FormValues = z.infer<typeof schema>

const blank: FormValues = {
  first_name: '', middle_name: '', last_name: '', suffix: '', date_of_birth: '', gender: '', civil_status: '', mobile_number: '', email: '',
  address_line: '', barangay: '', city_municipality: '', province: '', region: '', zip_code: '', occupation: '', employer_name: '',
  employment_type: '', monthly_income: '', emergency_contact_name: '', emergency_contact_relationship: '', emergency_contact_number: '',
  internal_notes: '', risk_rating: 'unrated', assessment_notes: '', id_type: '', id_number: '', id_expiry: '',
  guarantor_name: '', guarantor_relationship: '', guarantor_mobile: '', guarantor_address: '', guarantor_occupation: '',
}

const n = (v?: string | null) => (v && v.trim() ? v.trim() : null)

type PickedFile = { file: File; preview: string }

function useLocalPreview() {
  const [picked, setPicked] = useState<PickedFile | null>(null)
  useEffect(() => () => { if (picked) URL.revokeObjectURL(picked.preview) }, [picked])
  const pick = (file: File) => {
    if (file.size > 10 * 1024 * 1024) return toast.error('Files must be 10 MB or smaller')
    setPicked({ file, preview: file.type.startsWith('image/') ? URL.createObjectURL(file) : '' })
  }
  return [picked, pick] as const
}

export default function BorrowerFormPage() {
  const { id } = useParams()
  const isEdit = !!id
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { can, organization } = useAuth()
  const canSensitive = can('borrowers.view_sensitive')
  const canSupervise = can('borrowers.view_all')
  const ret = params.get('return')
  const safeReturn = ret && ret.startsWith('/') && !ret.startsWith('//') ? ret : null

  const existing = useQuery({
    queryKey: ['borrower', id, 'edit'],
    enabled: isEdit,
    queryFn: async () => {
      const [b, identity, guarantors] = await Promise.all([
        borrowersService.get(id!), canSensitive ? borrowersService.identity(id!) : Promise.resolve(null), borrowersService.guarantors(id!),
      ])
      return { b, identity, guarantor: guarantors[0] ?? null }
    },
  })

  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: blank })
  const { register, handleSubmit, formState: { errors, isSubmitting }, reset, watch } = form

  useEffect(() => {
    if (!existing.data) return
    const { b, identity, guarantor } = existing.data
    reset({
      ...blank,
      first_name: b.first_name, middle_name: b.middle_name ?? '', last_name: b.last_name, suffix: b.suffix ?? '',
      date_of_birth: b.date_of_birth ?? '', gender: b.gender ?? '', civil_status: b.civil_status ?? '', mobile_number: phMobile(b.mobile_number),
      email: b.email ?? '', address_line: b.address_line ?? '', barangay: b.barangay ?? '', city_municipality: b.city_municipality ?? '',
      province: b.province ?? '', region: b.region ?? '', zip_code: b.zip_code ?? '', occupation: b.occupation ?? '', employer_name: b.employer_name ?? '',
      employment_type: b.employment_type ?? '', monthly_income: b.monthly_income != null ? String(b.monthly_income) : '',
      emergency_contact_name: b.emergency_contact_name ?? '', emergency_contact_relationship: b.emergency_contact_relationship ?? '',
      emergency_contact_number: b.emergency_contact_number ? phMobile(b.emergency_contact_number) : '', internal_notes: b.internal_notes ?? '',
      risk_rating: b.risk_rating, assessment_notes: b.assessment_notes ?? '',
      id_type: identity?.id_type ?? '', id_number: identity?.id_number ?? '', id_expiry: identity?.id_expiry ?? '',
      guarantor_name: guarantor?.full_name ?? '', guarantor_relationship: guarantor?.relationship ?? '',
      guarantor_mobile: guarantor?.mobile_number ? phMobile(guarantor.mobile_number) : '', guarantor_address: guarantor?.address ?? '',
      guarantor_occupation: guarantor?.occupation ?? '',
    })
  }, [existing.data, reset])

  const [photo, pickPhoto] = useLocalPreview()
  const [idFront, pickIdFront] = useLocalPreview()
  const [idBack, pickIdBack] = useLocalPreview()
  const photoUrl = useBorrowerFileUrl(existing.data?.b.profile_photo_path)
  const idFrontUrl = useBorrowerFileUrl(existing.data?.identity?.id_front_path)
  const idBackUrl = useBorrowerFileUrl(existing.data?.identity?.id_back_path)

  // ---- duplicate detection (debounced) ----
  const [fn, ln, dob, mobile, idType, idNumber] = watch(['first_name', 'last_name', 'date_of_birth', 'mobile_number', 'id_type', 'id_number'])
  const dupKey = useDebounced(useMemo(() => ({ fn, ln, dob, mobile, idType, idNumber }), [fn, ln, dob, mobile, idType, idNumber]), 500)
  const dupEnabled = (dupKey.fn?.trim().length ?? 0) >= 2 && (dupKey.ln?.trim().length ?? 0) >= 2 || PH_MOBILE.test(normalizePhMobile(dupKey.mobile ?? ''))
  const dups = useQuery({
    queryKey: ['borrowers', 'duplicates', dupKey, id],
    enabled: dupEnabled && can('borrowers.create'),
    queryFn: () => borrowersService.checkDuplicates({
      first_name: dupKey.fn ?? '', last_name: dupKey.ln ?? '', date_of_birth: dupKey.dob || null,
      mobile: normalizePhMobile(dupKey.mobile ?? ''), id_type: dupKey.idType, id_number: dupKey.idNumber, exclude: id,
    }),
  })
  const matches = dups.data ?? []
  const hardDuplicate = matches.some((m) => m.match_reason === 'Same ID document' || m.match_reason === 'Same name and date of birth')
  const [ackDuplicate, setAckDuplicate] = useState(false)
  useEffect(() => setAckDuplicate(false), [matches.length])

  const onSubmit = handleSubmit(async (v) => {
    if (matches.length && !hardDuplicate && !ackDuplicate) {
      toast.warning('Possible duplicate found. Review the matches and confirm this is a different person.')
      document.getElementById('duplicates')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
      return
    }
    if (hardDuplicate) {
      toast.error('A borrower with the same ID or the same name and birth date already exists.')
      document.getElementById('duplicates')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
      return
    }
    const input: BorrowerInput = {
      first_name: v.first_name.trim(), middle_name: n(v.middle_name), last_name: v.last_name.trim(), suffix: n(v.suffix),
      date_of_birth: n(v.date_of_birth), gender: n(v.gender), civil_status: n(v.civil_status),
      mobile_number: normalizePhMobile(v.mobile_number), email: n(v.email),
      address_line: n(v.address_line), barangay: n(v.barangay), city_municipality: n(v.city_municipality), province: n(v.province),
      region: n(v.region), zip_code: n(v.zip_code), occupation: n(v.occupation), employer_name: n(v.employer_name),
      employment_type: n(v.employment_type), monthly_income: v.monthly_income ? Number(v.monthly_income.replace(/,/g, '')) : null,
      emergency_contact_name: n(v.emergency_contact_name), emergency_contact_relationship: n(v.emergency_contact_relationship),
      emergency_contact_number: v.emergency_contact_number ? normalizePhMobile(v.emergency_contact_number) : null,
      internal_notes: n(v.internal_notes),
    }
    if (canSupervise) {
      input.risk_rating = v.risk_rating
      input.assessment_notes = n(v.assessment_notes)
    }
    const identity = canSensitive && v.id_type && v.id_number
      ? { id_type: v.id_type, id_number: v.id_number.trim(), id_expiry: n(v.id_expiry) }
      : null
    const guarantor = v.guarantor_name
      ? { full_name: v.guarantor_name.trim(), relationship: n(v.guarantor_relationship), mobile_number: v.guarantor_mobile ? normalizePhMobile(v.guarantor_mobile) : null,
          address: n(v.guarantor_address), occupation: n(v.guarantor_occupation) }
      : null

    try {
      let borrowerId = id
      if (isEdit) {
        await borrowersService.update(id!, input)
        if (identity) await borrowersService.saveIdentity(id!, identity)
        if (guarantor) await borrowersService.saveGuarantor(id!, { ...guarantor, id: existing.data?.guarantor?.id })
      } else {
        const created = await borrowersService.create(input, identity, guarantor)
        borrowerId = created.id
      }

      // uploads need the borrower id in the storage path
      const orgId = organization?.id
      let uploadFailed = false
      if (orgId && borrowerId) {
        try {
          if (photo) {
            const path = await borrowersService.uploadBorrowerFile(orgId, borrowerId, 'profile', photo.file)
            await borrowersService.update(borrowerId, { profile_photo_path: path })
          }
          if (canSensitive && (idFront || idBack) && !(identity || existing.data?.identity)) {
            toast.warning('ID images were not saved — enter the ID type and number first.')
          } else if (canSensitive && (idFront || idBack)) {
            const patch: Record<string, string> = {}
            if (idFront) patch.id_front_path = await borrowersService.uploadBorrowerFile(orgId, borrowerId, 'identity', idFront.file)
            if (idBack) patch.id_back_path = await borrowersService.uploadBorrowerFile(orgId, borrowerId, 'identity', idBack.file)
            const hasIdentity = identity || existing.data?.identity
            if (hasIdentity) await borrowersService.saveIdentity(borrowerId, patch)
          }
        } catch (e) {
          uploadFailed = true
          toast.error(`Borrower saved, but a file upload failed: ${errorMessage(e)}`)
        }
      }

      void qc.invalidateQueries({ queryKey: ['borrowers'] })
      void qc.invalidateQueries({ queryKey: ['borrower', borrowerId] })
      if (!uploadFailed) toast.success(isEdit ? 'Borrower updated' : 'Borrower registered')
      if (!isEdit && safeReturn) {
        navigate(`${safeReturn}${safeReturn.includes('?') ? '&' : '?'}borrower=${borrowerId}`)
      } else {
        navigate(`/borrowers/${borrowerId}`)
      }
    } catch (e) {
      toast.error(errorMessage(e))
    }
  })

  if (isEdit && existing.isLoading) return <PageLoader />
  if (isEdit && existing.error) return <ErrorState error={existing.error} onRetry={() => void existing.refetch()} />

  const backTo = isEdit ? `/borrowers/${id}` : safeReturn ?? '/borrowers'

  return (
    <form onSubmit={onSubmit} noValidate>
      <PageHeader
        back={<Link to={backTo} className="mb-2 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"><ArrowLeft className="size-4" /> Back</Link>}
        title={isEdit ? `Edit ${existing.data?.b.full_name ?? 'borrower'}` : 'Register borrower'}
        description={isEdit ? existing.data?.b.borrower_code : 'A borrower ID is generated automatically when you save.'}
      />

      {matches.length > 0 && (
        <div id="duplicates" className={`mb-5 rounded-xl border p-4 ${hardDuplicate ? 'border-red-200 bg-red-50' : 'border-amber-200 bg-amber-50'}`}>
          <div className="flex items-start gap-3">
            <AlertTriangle className={`mt-0.5 size-5 shrink-0 ${hardDuplicate ? 'text-red-600' : 'text-amber-600'}`} />
            <div className="min-w-0 flex-1">
              <p className={`text-sm font-semibold ${hardDuplicate ? 'text-red-900' : 'text-amber-900'}`}>
                {hardDuplicate ? 'This borrower is already registered' : 'Possible duplicate borrower'}
              </p>
              <ul className="mt-2 space-y-1.5">
                {matches.map((m) => (
                  <li key={m.borrower_id} className="flex flex-wrap items-center gap-x-2 text-sm">
                    <Link to={`/borrowers/${m.borrower_id}`} target="_blank" className="font-medium text-blue-700 hover:underline">{m.full_name}</Link>
                    <span className="text-muted-foreground">{m.borrower_code} · {phMobile(m.mobile_number)}</span>
                    <span className="rounded bg-white/70 px-1.5 py-0.5 text-xs">{m.match_reason}</span>
                  </li>
                ))}
              </ul>
              {!hardDuplicate && (
                <label className="mt-3 flex items-center gap-2 text-sm text-amber-900">
                  <Checkbox checked={ackDuplicate} onCheckedChange={(c) => setAckDuplicate(c === true)} />
                  I checked the matches — this is a different person.
                </label>
              )}
            </div>
          </div>
        </div>
      )}

      <div className="grid gap-5 lg:grid-cols-2">
        <Panel title="Personal information" className="lg:col-span-2">
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Field label="First name" required error={errors.first_name?.message}><Input className="h-10" autoComplete="given-name" {...register('first_name')} /></Field>
            <Field label="Middle name" error={errors.middle_name?.message}><Input className="h-10" autoComplete="additional-name" {...register('middle_name')} /></Field>
            <Field label="Last name" required error={errors.last_name?.message}><Input className="h-10" autoComplete="family-name" {...register('last_name')} /></Field>
            <Field label="Suffix" hint="Jr., Sr., III"><Input className="h-10" {...register('suffix')} /></Field>
            <Field label="Date of birth" error={errors.date_of_birth?.message}><Input type="date" className="h-10" {...register('date_of_birth')} /></Field>
            <Field label="Gender">
              <NativeSelect {...register('gender')}>
                <option value="">Prefer not to say</option>
                <option value="male">Male</option>
                <option value="female">Female</option>
                <option value="other">Other</option>
              </NativeSelect>
            </Field>
            <Field label="Civil status">
              <NativeSelect {...register('civil_status')}>
                <option value="">—</option>
                <option value="single">Single</option>
                <option value="married">Married</option>
                <option value="widowed">Widowed</option>
                <option value="separated">Separated</option>
                <option value="annulled">Annulled</option>
              </NativeSelect>
            </Field>
            <Field label="Mobile number" required error={errors.mobile_number?.message}>
              <Input className="h-10" inputMode="tel" autoComplete="tel" placeholder="0917 123 4567" {...register('mobile_number')} />
            </Field>
            <Field label="Email" error={errors.email?.message} className="sm:col-span-2"><Input type="email" className="h-10" autoComplete="email" {...register('email')} /></Field>
            <div className="sm:col-span-2">
              <Field label="Profile photo" hint="Take a clear photo of the borrower's face">
                <FileDrop accept="image/*" label={photo ? photo.file.name : photoUrl ? 'Replace photo' : 'Take or upload a photo'}
                  hint="JPG or PNG, up to 10 MB" preview={photo?.preview || photoUrl} onFile={pickPhoto} />
              </Field>
            </div>
          </div>
        </Panel>

        <Panel title="Residential address">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="House no. / street / subdivision" className="sm:col-span-2"><Input className="h-10" autoComplete="address-line1" {...register('address_line')} /></Field>
            <Field label="Barangay"><Input className="h-10" {...register('barangay')} /></Field>
            <Field label="City / municipality"><Input className="h-10" autoComplete="address-level2" {...register('city_municipality')} /></Field>
            <Field label="Province"><Input className="h-10" autoComplete="address-level1" {...register('province')} /></Field>
            <Field label="ZIP code" error={errors.zip_code?.message}><Input className="h-10" inputMode="numeric" maxLength={4} autoComplete="postal-code" {...register('zip_code')} /></Field>
            <Field label="Region" className="sm:col-span-2">
              <NativeSelect {...register('region')}>
                <option value="">Select region</option>
                {PH_REGIONS.map((r) => <option key={r} value={r}>{r}</option>)}
              </NativeSelect>
            </Field>
          </div>
        </Panel>

        <Panel title="Employment & income">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Occupation"><Input className="h-10" {...register('occupation')} /></Field>
            <Field label="Employment type">
              <NativeSelect {...register('employment_type')}>
                <option value="">Select</option>
                {EMPLOYMENT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
              </NativeSelect>
            </Field>
            <Field label="Employer / business name" className="sm:col-span-2"><Input className="h-10" {...register('employer_name')} /></Field>
            <Field label="Monthly income (₱)" hint="Optional" error={errors.monthly_income?.message}>
              <Input className="h-10" inputMode="decimal" placeholder="25,000" {...register('monthly_income')} />
            </Field>
          </div>
        </Panel>

        <Panel title="Identification" description="Government-issued ID used to verify identity" className="lg:col-span-2">
          {canSensitive ? (
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              <Field label="ID type" error={errors.id_type?.message}>
                <NativeSelect {...register('id_type')}>
                  <option value="">Select ID type</option>
                  {PH_ID_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                </NativeSelect>
              </Field>
              <Field label="ID number" error={errors.id_number?.message}><Input className="h-10" autoComplete="off" {...register('id_number')} /></Field>
              <Field label="Expiry date" hint="If applicable"><Input type="date" className="h-10" {...register('id_expiry')} /></Field>
              <Field label="Front of ID" className="sm:col-span-1 lg:col-span-1">
                <FileDrop accept="image/*,application/pdf" label={idFront ? idFront.file.name : idFrontUrl ? 'Replace front image' : 'Capture front of ID'}
                  hint="Ensure all text is readable" preview={idFront?.preview || idFrontUrl} onFile={pickIdFront} />
              </Field>
              <Field label="Back of ID">
                <FileDrop accept="image/*,application/pdf" label={idBack ? idBack.file.name : idBackUrl ? 'Replace back image' : 'Capture back of ID'}
                  hint="Optional for single-sided IDs" preview={idBack?.preview || idBackUrl} onFile={pickIdBack} />
              </Field>
              <div className="flex items-start gap-2 rounded-lg bg-slate-50 p-3 text-xs text-muted-foreground">
                <Lock className="mt-0.5 size-3.5 shrink-0" />
                ID images are stored privately and visible only to staff with permission to view sensitive data.
              </div>
            </div>
          ) : (
            <p className="flex items-center gap-2 text-sm text-muted-foreground"><Lock className="size-4" /> Your role cannot view or edit identity documents.</p>
          )}
        </Panel>

        <Panel title="Emergency contact">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Full name" className="sm:col-span-2"><Input className="h-10" {...register('emergency_contact_name')} /></Field>
            <Field label="Relationship">
              <NativeSelect {...register('emergency_contact_relationship')}>
                <option value="">Select</option>
                {RELATIONSHIPS.map((r) => <option key={r} value={r}>{r}</option>)}
              </NativeSelect>
            </Field>
            <Field label="Mobile number" error={errors.emergency_contact_number?.message}><Input className="h-10" inputMode="tel" placeholder="09XX XXX XXXX" {...register('emergency_contact_number')} /></Field>
          </div>
        </Panel>

        <Panel title="Guarantor / co-maker" description="Optional — more can be added from the borrower profile">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Full name" className="sm:col-span-2"><Input className="h-10" {...register('guarantor_name')} /></Field>
            <Field label="Relationship">
              <NativeSelect {...register('guarantor_relationship')}>
                <option value="">Select</option>
                {RELATIONSHIPS.map((r) => <option key={r} value={r}>{r}</option>)}
              </NativeSelect>
            </Field>
            <Field label="Mobile number" error={errors.guarantor_mobile?.message}><Input className="h-10" inputMode="tel" {...register('guarantor_mobile')} /></Field>
            <Field label="Occupation"><Input className="h-10" {...register('guarantor_occupation')} /></Field>
            <Field label="Address"><Input className="h-10" {...register('guarantor_address')} /></Field>
          </div>
        </Panel>

        <Panel title="Internal notes & assessment" className="lg:col-span-2">
          <div className="grid gap-4 lg:grid-cols-3">
            <Field label="Internal notes" hint="Visible to staff only" className={canSupervise ? '' : 'lg:col-span-3'}>
              <Textarea rows={4} {...register('internal_notes')} />
            </Field>
            {canSupervise && (
              <>
                <Field label="Risk rating">
                  <NativeSelect {...register('risk_rating')}>
                    <option value="unrated">Unrated</option>
                    <option value="low">Low risk</option>
                    <option value="medium">Medium risk</option>
                    <option value="high">High risk</option>
                  </NativeSelect>
                </Field>
                <Field label="Assessment notes" hint="Credit investigation, character references, capacity to pay">
                  <Textarea rows={4} {...register('assessment_notes')} />
                </Field>
              </>
            )}
          </div>
        </Panel>
      </div>

      <div className="mt-5 border-t pt-4">
        <div className="flex items-center justify-end gap-2">
          <Button type="button" variant="outline" onClick={() => navigate(backTo)} disabled={isSubmitting}>Cancel</Button>
          <Button type="submit" className="min-w-36" disabled={isSubmitting || hardDuplicate}>
            {isSubmitting ? <Spinner /> : <Save />} {isEdit ? 'Save changes' : safeReturn ? 'Register & continue' : 'Register borrower'}
          </Button>
        </div>
      </div>
    </form>
  )
}
