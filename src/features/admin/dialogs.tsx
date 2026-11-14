import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Check, CheckCircle2, Copy, Eye, EyeOff, KeyRound, ListChecks, RefreshCw, Zap } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Field, NativeSelect, Pill, Spinner } from '@/components/common'
import { generatePassword, platformService } from '@/services/platform'
import { errorMessage } from '@/lib/errors'
import { ROLE_LABEL } from '@/lib/constants'
import { normalizePhMobile } from '@/lib/format'
import { cn } from '@/lib/utils'
import type { AccountStatus, AppRole, LendingMode } from '@/types/db'

export function AccountStatusPill({ status }: { status: AccountStatus }) {
  return status === 'active' ? <Pill tone="green">Active</Pill> : <Pill tone="red">Suspended</Pill>
}

export const LENDING_MODES: { value: LendingMode; label: string; icon: React.ReactNode; description: string; points: string[] }[] = [
  {
    value: 'complete', label: 'Complete Lending', icon: <ListChecks className="size-5" />,
    description: 'The comprehensive workflow: loan products, custom interest method and rate, fees, penalties, signing order and witnesses per loan.',
    points: ['Full borrower profiles', 'Per-loan terms & products', 'Review, approval, signing links, release'],
  },
  {
    value: 'simplified', label: 'Simplified Lending', icon: <Zap className="size-5" />,
    description: 'A fast New Loan / Reloan-Renew flow. Staff enter only the amount and duration; rates and fees follow the business settings.',
    points: ['Quick borrower registration', 'Automatic outstanding-balance deduction on renewal', 'In-person signature on the device'],
  },
]

export function LendingModePill({ mode }: { mode: LendingMode }) {
  return mode === 'simplified' ? <Pill tone="teal" dot={false}>Simplified</Pill> : <Pill tone="navy" dot={false}>Complete</Pill>
}

/** Two-card picker for the business's lending mode (platform administrators only). */
export function LendingModePicker({ value, onChange, disabled }: { value: LendingMode; onChange: (m: LendingMode) => void; disabled?: boolean }) {
  return (
    <div role="radiogroup" aria-label="Lending mode" className="grid gap-3 sm:grid-cols-2">
      {LENDING_MODES.map((m) => {
        const on = value === m.value
        return (
          <button key={m.value} type="button" role="radio" aria-checked={on} disabled={disabled} onClick={() => onChange(m.value)}
            className={cn('rounded-xl border-2 p-3 text-left transition disabled:opacity-60', on ? 'border-[#2563EB] bg-blue-50/60' : 'border-slate-200 hover:border-slate-300')}>
            <span className="flex items-center justify-between gap-2">
              <span className="flex items-center gap-2 text-sm font-semibold">{m.icon}{m.label}</span>
              {on && <CheckCircle2 className="size-4 text-[#2563EB]" />}
            </span>
            <span className="mt-1 block text-xs text-muted-foreground">{m.description}</span>
            <span className="mt-2 block space-y-0.5">
              {m.points.map((p) => <span key={p} className="flex items-center gap-1.5 text-xs"><Check className="size-3 text-teal-600" />{p}</span>)}
            </span>
          </button>
        )
      })}
    </div>
  )
}

function useCopy() {
  const [copied, setCopied] = useState<string | null>(null)
  return {
    copied,
    copy: async (key: string, text: string) => {
      try {
        await navigator.clipboard.writeText(text)
        setCopied(key)
        setTimeout(() => setCopied((c) => (c === key ? null : c)), 1500)
      } catch {
        toast.error('Could not copy. Select the text and copy it manually.')
      }
    },
  }
}

/** Password input with show/hide and a generator. */
function PasswordInput({ value, onChange, invalid }: { value: string; onChange: (v: string) => void; invalid?: boolean }) {
  const [show, setShow] = useState(true)
  return (
    <div className="flex gap-2">
      <div className="relative flex-1">
        <Input type={show ? 'text' : 'password'} value={value} onChange={(e) => onChange(e.target.value)} autoComplete="new-password"
          aria-invalid={invalid} className="h-10 pr-10 font-mono" />
        <button type="button" onClick={() => setShow((s) => !s)} aria-label={show ? 'Hide password' : 'Show password'}
          className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1 text-muted-foreground hover:text-foreground">
          {show ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
        </button>
      </div>
      <Button type="button" variant="outline" className="h-10" onClick={() => { onChange(generatePassword()); setShow(true) }}>
        <RefreshCw /> Generate
      </Button>
    </div>
  )
}

export type Credentials = { title: string; business?: string; email: string; password: string }

/** Shown once after creating a login or resetting a password, so the admin can hand it over. */
export function CredentialsDialog({ creds, onClose }: { creds: Credentials; onClose: () => void }) {
  const { copied, copy } = useCopy()
  const loginUrl = `${window.location.origin}/login`
  const message = [
    creds.business ? `Your LendFlow account for ${creds.business} is ready.` : 'Your LendFlow login is ready.',
    `Sign in: ${loginUrl}`,
    `Email: ${creds.email}`,
    `Temporary password: ${creds.password}`,
    'You will be asked to choose your own password the first time you sign in.',
  ].join('\n')
  const rows: [string, string, string][] = [['url', 'Sign-in page', loginUrl], ['email', 'Email', creds.email], ['password', 'Temporary password', creds.password]]
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><span className="grid size-8 place-items-center rounded-full bg-green-100 text-green-700"><Check className="size-4" /></span>{creds.title}</DialogTitle>
          <DialogDescription>Send these details to the user. The temporary password is shown only now; they must change it when they first sign in.</DialogDescription>
        </DialogHeader>
        <div className="divide-y rounded-lg border">
          {rows.map(([key, label, value]) => (
            <div key={key} className="flex items-center gap-3 px-3 py-2.5">
              <div className="min-w-0 flex-1">
                <p className="text-xs text-muted-foreground">{label}</p>
                <p className={key === 'password' ? 'break-all font-mono text-sm font-semibold' : 'break-all text-sm font-medium'}>{value}</p>
              </div>
              <Button type="button" variant="ghost" size="icon" aria-label={`Copy ${label}`} onClick={() => void copy(key, value)}>
                {copied === key ? <Check className="text-green-600" /> : <Copy />}
              </Button>
            </div>
          ))}
        </div>
        <DialogFooter className="gap-2 sm:justify-between">
          <Button type="button" variant="outline" onClick={() => void copy('all', message)}>
            {copied === 'all' ? <Check /> : <Copy />} Copy message
          </Button>
          <Button type="button" onClick={onClose}>Done</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

const optional = z.string().trim().optional()
const accountSchema = z.object({
  name: z.string().trim().min(2, 'Enter the business name'),
  legal_name: optional,
  registration_no: optional,
  tin: optional,
  address: optional,
  city: optional,
  province: optional,
  contact_phone: optional,
  owner_name: z.string().trim().min(2, "Enter the owner's full name"),
  owner_email: z.string().trim().toLowerCase().email('Enter a valid email'),
  owner_phone: z.string().trim().optional().refine((v) => !v || !!normalizePhMobile(v), 'Use a PH mobile number, e.g. 0917 123 4567'),
  password: z.string().min(10, 'At least 10 characters').max(72, 'At most 72 characters'),
})
type AccountForm = z.infer<typeof accountSchema>

/** Create a lender account: the business (organization) and its owner login. */
export function CreateAccountDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (orgId: string, creds: Credentials) => void }) {
  const qc = useQueryClient()
  const [busy, setBusy] = useState(false)
  const form = useForm<AccountForm>({
    resolver: zodResolver(accountSchema),
    defaultValues: { name: '', owner_name: '', owner_email: '', password: generatePassword() },
  })
  const [mode, setMode] = useState<LendingMode>('complete')
  const e = form.formState.errors
  const submit = form.handleSubmit(async (v) => {
    setBusy(true)
    try {
      const r = await platformService.createAccount({
        organization: {
          name: v.name, legal_name: v.legal_name, registration_no: v.registration_no, tin: v.tin,
          address: v.address, city: v.city, province: v.province, contact_phone: v.contact_phone, contact_email: v.owner_email,
          lending_mode: mode,
        },
        owner: { full_name: v.owner_name, email: v.owner_email, phone: v.owner_phone ? normalizePhMobile(v.owner_phone) ?? v.owner_phone : undefined, password: v.password },
      })
      void qc.invalidateQueries({ queryKey: ['platform'] })
      toast.success(`${v.name} is ready`)
      onCreated(r.organization_id, { title: 'Account created', business: v.name, email: r.email, password: v.password })
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setBusy(false)
    }
  })
  return (
    <Dialog open onOpenChange={(o) => !o && !busy && onClose()}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Create lender account</DialogTitle>
          <DialogDescription>
            Sets up a new lending business with its own private workspace: default loan products, an agreement template and role
            permissions. The owner can then add their own staff.
          </DialogDescription>
        </DialogHeader>
        <form id="create-account" onSubmit={submit} className="space-y-5">
          <section className="space-y-3">
            <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Business</h3>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Business / trade name" required error={e.name?.message}><Input className="h-10" placeholder="e.g. Bayanihan Lending" {...form.register('name')} /></Field>
              <Field label="Registered legal name" hint="As registered with SEC/DTI"><Input className="h-10" {...form.register('legal_name')} /></Field>
              <Field label="SEC reg. / Certificate of Authority no."><Input className="h-10" {...form.register('registration_no')} /></Field>
              <Field label="TIN"><Input className="h-10" placeholder="000-000-000-000" {...form.register('tin')} /></Field>
              <Field label="Business address" className="sm:col-span-2"><Input className="h-10" placeholder="Unit / street / barangay" {...form.register('address')} /></Field>
              <Field label="City / municipality"><Input className="h-10" {...form.register('city')} /></Field>
              <Field label="Province"><Input className="h-10" {...form.register('province')} /></Field>
              <Field label="Business phone"><Input className="h-10" placeholder="+63 2 8xxx xxxx" {...form.register('contact_phone')} /></Field>
            </div>
          </section>
          <section className="space-y-3">
            <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Lending mode</h3>
            <LendingModePicker value={mode} onChange={setMode} disabled={busy} />
            <p className="text-xs text-muted-foreground">Decides the loan-creation workflow for every user of this business. You can change it later.</p>
          </section>
          <section className="space-y-3">
            <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Owner login</h3>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Owner's full name" required error={e.owner_name?.message}><Input className="h-10" autoComplete="off" {...form.register('owner_name')} /></Field>
              <Field label="Mobile number" error={e.owner_phone?.message}><Input className="h-10" placeholder="0917 123 4567" {...form.register('owner_phone')} /></Field>
              <Field label="Email (used to sign in)" required error={e.owner_email?.message}><Input type="email" className="h-10" autoComplete="off" {...form.register('owner_email')} /></Field>
              <Field label="Temporary password" required error={e.password?.message} hint="They must change it at first sign-in.">
                <PasswordInput value={form.watch('password')} invalid={!!e.password}
                  onChange={(v) => form.setValue('password', v, { shouldValidate: form.formState.isSubmitted })} />
              </Field>
            </div>
          </section>
        </form>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button type="submit" form="create-account" disabled={busy}>{busy && <Spinner />} Create account</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

const ROLES: AppRole[] = ['owner', 'admin', 'loan_officer', 'collector']
const userSchema = z.object({
  full_name: z.string().trim().min(2, 'Enter the full name'),
  email: z.string().trim().toLowerCase().email('Enter a valid email'),
  phone: z.string().trim().optional().refine((v) => !v || !!normalizePhMobile(v), 'Use a PH mobile number'),
  role: z.enum(['owner', 'admin', 'loan_officer', 'collector']),
  password: z.string().min(10, 'At least 10 characters').max(72, 'At most 72 characters'),
})
type UserForm = z.infer<typeof userSchema>

/** Add another login to an existing account (e.g. a second owner, or when the owner is locked out). */
export function AddUserDialog({ orgId, orgName, onClose, onCreated }: {
  orgId: string; orgName: string; onClose: () => void; onCreated: (creds: Credentials) => void
}) {
  const qc = useQueryClient()
  const [busy, setBusy] = useState(false)
  const form = useForm<UserForm>({
    resolver: zodResolver(userSchema),
    defaultValues: { full_name: '', email: '', role: 'admin', password: generatePassword() },
  })
  const e = form.formState.errors
  const submit = form.handleSubmit(async (v) => {
    setBusy(true)
    try {
      const r = await platformService.addUser({
        organization_id: orgId, full_name: v.full_name, email: v.email, role: v.role, password: v.password,
        phone: v.phone ? normalizePhMobile(v.phone) ?? v.phone : undefined,
      })
      void qc.invalidateQueries({ queryKey: ['platform'] })
      onCreated({ title: 'Login created', business: orgName, email: r.email, password: v.password })
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setBusy(false)
    }
  })
  return (
    <Dialog open onOpenChange={(o) => !o && !busy && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Add login to {orgName}</DialogTitle>
          <DialogDescription>Owners normally add their own staff. Use this to add a second owner or restore access.</DialogDescription>
        </DialogHeader>
        <form id="add-user" onSubmit={submit} className="grid gap-3 sm:grid-cols-2">
          <Field label="Full name" required error={e.full_name?.message}><Input className="h-10" autoComplete="off" {...form.register('full_name')} /></Field>
          <Field label="Role" required>
            <NativeSelect {...form.register('role')}>
              {ROLES.map((r) => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
            </NativeSelect>
          </Field>
          <Field label="Email" required error={e.email?.message}><Input type="email" className="h-10" autoComplete="off" {...form.register('email')} /></Field>
          <Field label="Mobile number" error={e.phone?.message}><Input className="h-10" {...form.register('phone')} /></Field>
          <Field label="Temporary password" required error={e.password?.message} className="sm:col-span-2">
            <PasswordInput value={form.watch('password')} invalid={!!e.password}
              onChange={(v) => form.setValue('password', v, { shouldValidate: form.formState.isSubmitted })} />
          </Field>
        </form>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button type="submit" form="add-user" disabled={busy}>{busy && <Spinner />} Create login</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export function ResetPasswordDialog({ user, orgName, onClose, onDone }: {
  user: { id: string; full_name: string; email: string | null }; orgName: string; onClose: () => void; onDone: (creds: Credentials) => void
}) {
  const [pw, setPw] = useState(generatePassword())
  const [busy, setBusy] = useState(false)
  const submit = async () => {
    if (pw.length < 10) return toast.error('Use at least 10 characters')
    setBusy(true)
    try {
      await platformService.resetPassword(user.id, pw)
      onDone({ title: 'Password reset', business: orgName, email: user.email ?? '', password: pw })
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(o) => !o && !busy && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><KeyRound className="size-5" /> Reset password</DialogTitle>
          <DialogDescription>Set a temporary password for {user.full_name || user.email}. Their current password stops working immediately.</DialogDescription>
        </DialogHeader>
        <Field label="Temporary password" hint="They must change it at next sign-in."><PasswordInput value={pw} onChange={setPw} /></Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button onClick={() => void submit()} disabled={busy}>{busy && <Spinner />} Reset password</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
