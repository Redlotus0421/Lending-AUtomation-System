import { useState, type ReactNode } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { toast } from 'sonner'
import { Ban, Building2, FileSignature, KeyRound, LineChart, ShieldCheck } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { PasswordInput } from '@/components/ui/password-input'
import { Field, Spinner } from '@/components/common'
import { authService, orgService } from '@/services/org'
import { errorMessage } from '@/lib/errors'
import { useAuth } from './AuthProvider'
import { isSupabaseConfigured } from '@/lib/supabase'

function AuthLayout({ children, title, subtitle }: { children: ReactNode; title: string; subtitle?: string }) {
  return (
    <div className="grid min-h-dvh lg:grid-cols-[1.05fr_1fr]">
      <div className="relative hidden overflow-hidden bg-[#142B4A] p-12 text-white lg:flex lg:flex-col">
        <div className="absolute -right-24 -top-24 size-96 rounded-full bg-[#2563EB]/30 blur-3xl" />
        <div className="absolute -bottom-32 -left-16 size-96 rounded-full bg-[#14B8A6]/20 blur-3xl" />
        <div className="relative flex items-center gap-3">
          <span className="grid size-10 place-items-center rounded-xl bg-white/10 ring-1 ring-white/20">
            <svg viewBox="0 0 32 32" className="size-7"><path d="M9 8v16h14" stroke="#14B8A6" strokeWidth="3.2" fill="none" strokeLinecap="round" strokeLinejoin="round" /><path d="M14 18l4-5 3 3" stroke="#fff" strokeWidth="2.4" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
          </span>
          <span className="text-xl font-semibold tracking-tight">LendFlow</span>
        </div>
        <div className="relative mt-auto max-w-lg">
          <h2 className="text-3xl font-semibold leading-tight tracking-tight">From application to collection — one secure lending workflow.</h2>
          <ul className="mt-8 space-y-4 text-sm text-white/80">
            {[
              [<LineChart key="a" />, 'Server-side flat and diminishing-balance calculations with full amortization schedules'],
              [<FileSignature key="b" />, 'Agreements generated as PDFs and signed on the borrower’s phone — signatures embedded permanently'],
              [<ShieldCheck key="c" />, 'Separate principal, interest, charges and penalty balances with an immutable ledger'],
            ].map(([icon, text], i) => (
              <li key={i} className="flex gap-3"><span className="mt-0.5 text-teal [&_svg]:size-5">{icon}</span>{text}</li>
            ))}
          </ul>
        </div>
        <p className="relative mt-12 text-xs text-white/40">Built for Philippine lenders · PHP · Asia/Manila</p>
      </div>
      <div className="flex items-center justify-center p-5 sm:p-10">
        <div className="w-full max-w-sm">
          <div className="mb-8 lg:hidden">
            <span className="text-2xl font-semibold tracking-tight text-[#142B4A]">LendFlow</span>
          </div>
          <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
          {subtitle && <p className="mt-1 text-sm text-muted-foreground">{subtitle}</p>}
          {!isSupabaseConfigured && (
            <p className="mt-4 rounded-md bg-amber-50 p-3 text-xs text-amber-800">Supabase is not configured. Set VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY in .env locally, or in your host's environment variables (e.g. Vercel), then rebuild.</p>
          )}
          <div className="mt-6">{children}</div>
        </div>
      </div>
    </div>
  )
}

const loginSchema = z.object({ email: z.string().email('Enter a valid email'), password: z.string().min(1, 'Password is required') })

export function LoginPage() {
  const { session, loading } = useAuth()
  const navigate = useNavigate()
  const [busy, setBusy] = useState(false)
  const [forgot, setForgot] = useState(false)
  const form = useForm<z.infer<typeof loginSchema>>({ resolver: zodResolver(loginSchema), defaultValues: { email: '', password: '' } })
  if (!loading && session) return <Navigate to="/" replace />

  const onSubmit = form.handleSubmit(async (v) => {
    setBusy(true)
    try {
      await authService.signIn(v.email, v.password)
      navigate('/')
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setBusy(false)
    }
  })

  if (forgot) return <ForgotPassword onBack={() => setForgot(false)} />

  return (
    <AuthLayout title="Sign in" subtitle="Welcome back. Sign in to your lending workspace.">
      <form onSubmit={onSubmit} className="space-y-4">
        <Field label="Email" error={form.formState.errors.email?.message}>
          <Input type="email" autoComplete="email" className="h-11" {...form.register('email')} />
        </Field>
        <Field label="Password" error={form.formState.errors.password?.message}>
          <PasswordInput autoComplete="current-password" className="h-11" {...form.register('password')} />
        </Field>
        <div className="flex justify-end">
          <button type="button" onClick={() => setForgot(true)} className="text-xs font-medium text-blue-600 hover:underline">Forgot password?</button>
        </div>
        <Button type="submit" className="h-11 w-full" disabled={busy}>{busy && <Spinner />} Sign in</Button>
      </form>
      <p className="mt-6 text-center text-sm text-muted-foreground">
        Need an account for your lending business? Contact your LendFlow administrator.
      </p>
    </AuthLayout>
  )
}

function ForgotPassword({ onBack }: { onBack: () => void }) {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const [busy, setBusy] = useState(false)
  return (
    <AuthLayout title="Reset password" subtitle="We'll email you a link to set a new password.">
      {sent ? (
        <div className="rounded-lg bg-green-50 p-4 text-sm text-green-800">If an account exists for {email}, a reset link is on its way.</div>
      ) : (
        <form className="space-y-4" onSubmit={async (e) => {
          e.preventDefault()
          setBusy(true)
          try {
            await authService.resetPassword(email)
            setSent(true)
          } catch (err) {
            toast.error(errorMessage(err))
          } finally {
            setBusy(false)
          }
        }}>
          <Field label="Email"><Input type="email" required className="h-11" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
          <Button className="h-11 w-full" disabled={busy}>{busy && <Spinner />} Send reset link</Button>
        </form>
      )}
      <button onClick={onBack} className="mt-6 text-sm font-medium text-blue-600 hover:underline">Back to sign in</button>
    </AuthLayout>
  )
}

export function ResetPasswordPage() {
  const [pw, setPw] = useState('')
  const [busy, setBusy] = useState(false)
  const navigate = useNavigate()
  return (
    <AuthLayout title="Set a new password">
      <form className="space-y-4" onSubmit={async (e) => {
        e.preventDefault()
        if (pw.length < 10) return toast.error('Use at least 10 characters')
        setBusy(true)
        try {
          await authService.updatePassword(pw)
          toast.success('Password updated')
          navigate('/')
        } catch (err) {
          toast.error(errorMessage(err))
        } finally {
          setBusy(false)
        }
      }}>
        <Field label="New password" hint="At least 10 characters"><PasswordInput className="h-11" value={pw} onChange={(e) => setPw(e.target.value)} /></Field>
        <Button className="h-11 w-full" disabled={busy}>{busy && <Spinner />} Update password</Button>
      </form>
    </AuthLayout>
  )
}

/** A signed-in login that is not attached to any lender account (accounts are created by the platform admin). */
export function NoAccountPage() {
  const { session, access, signOut } = useAuth()
  if (!session) return <Navigate to="/login" replace />
  if (access?.organization_id || access?.suspended_organization) return <Navigate to="/" replace />
  if (access?.is_platform_admin) return <Navigate to="/admin" replace />
  return (
    <AuthLayout title="No lender account yet">
      <div className="rounded-lg border bg-white p-5 text-sm">
        <Building2 className="mb-2 size-6 text-[#142B4A]" />
        <p>You're signed in as <b>{session.user.email}</b>, but this login isn't linked to a lending business.</p>
        <p className="mt-2 text-muted-foreground">
          LendFlow accounts are created by the LendFlow administrator, and staff logins by the business owner. Ask them to set up
          your access, then sign in again.
        </p>
      </div>
      <Button variant="outline" className="mt-6 h-11 w-full" onClick={() => void signOut()}>Sign out</Button>
    </AuthLayout>
  )
}

export function ProfilePage() {
  const { access, session, refresh } = useAuth()
  const [name, setName] = useState(access?.full_name ?? '')
  const [phone, setPhone] = useState('')
  const [pw, setPw] = useState('')
  const [busy, setBusy] = useState(false)
  return (
    <div className="mx-auto max-w-xl space-y-5">
      <h1 className="text-xl font-semibold">My profile</h1>
      <form className="space-y-4 rounded-xl border bg-white p-5 shadow-card" onSubmit={async (e) => {
        e.preventDefault()
        setBusy(true)
        try {
          await orgService.updateMyProfile(name, phone)
          await refresh()
          toast.success('Profile updated')
        } catch (err) { toast.error(errorMessage(err)) } finally { setBusy(false) }
      }}>
        <Field label="Email"><Input value={session?.user.email ?? ''} disabled className="h-10" /></Field>
        <Field label="Full name"><Input value={name} onChange={(e) => setName(e.target.value)} className="h-10" /></Field>
        <Field label="Mobile number"><Input value={phone} onChange={(e) => setPhone(e.target.value)} className="h-10" placeholder="09XX XXX XXXX" /></Field>
        <Button disabled={busy}>{busy && <Spinner />} Save</Button>
      </form>
      <form className="space-y-4 rounded-xl border bg-white p-5 shadow-card" onSubmit={async (e) => {
        e.preventDefault()
        if (pw.length < 10) return toast.error('Use at least 10 characters')
        try { await authService.updatePassword(pw); setPw(''); toast.success('Password changed') } catch (err) { toast.error(errorMessage(err)) }
      }}>
        <Field label="New password" hint="At least 10 characters"><PasswordInput value={pw} onChange={(e) => setPw(e.target.value)} className="h-10" /></Field>
        <Button variant="outline">Change password</Button>
      </form>
    </div>
  )
}

/** Shown after signing in with a temporary password set by an administrator. */
export function ForcePasswordChangePage() {
  const { session, signOut } = useAuth()
  const [pw, setPw] = useState('')
  const [pw2, setPw2] = useState('')
  const [busy, setBusy] = useState(false)
  return (
    <AuthLayout title="Choose your own password" subtitle={`You signed in as ${session?.user.email ?? ''} with a temporary password. Set a new one to continue.`}>
      <form className="space-y-4" onSubmit={async (e) => {
        e.preventDefault()
        if (pw.length < 10) return toast.error('Use at least 10 characters')
        if (pw !== pw2) return toast.error('The passwords do not match')
        setBusy(true)
        try {
          await authService.updatePassword(pw)
          toast.success('Password updated')
        } catch (err) {
          toast.error(errorMessage(err))
        } finally {
          setBusy(false)
        }
      }}>
        <Field label="New password" hint="At least 10 characters"><PasswordInput autoComplete="new-password" className="h-11" value={pw} onChange={(e) => setPw(e.target.value)} /></Field>
        <Field label="Confirm new password"><PasswordInput autoComplete="new-password" className="h-11" value={pw2} onChange={(e) => setPw2(e.target.value)} /></Field>
        <Button className="h-11 w-full" disabled={busy}>{busy ? <Spinner /> : <KeyRound />} Save and continue</Button>
      </form>
      <button onClick={() => void signOut()} className="mt-6 text-sm font-medium text-blue-600 hover:underline">Sign out</button>
    </AuthLayout>
  )
}

/** Shown to users whose lender account was suspended by the platform administrator. */
export function AccountSuspendedPage() {
  const { access, signOut } = useAuth()
  const org = access?.suspended_organization
  return (
    <AuthLayout title="Account suspended">
      <div className="rounded-lg border border-red-200 bg-red-50 p-5 text-sm text-red-900">
        <Ban className="mb-2 size-6 text-red-600" />
        <p>The LendFlow account for <b>{org?.name ?? 'your company'}</b> is suspended, so its workspace can't be opened right now. Your records are kept safe and unchanged.</p>
        {org?.reason && <p className="mt-2"><span className="font-medium">Reason:</span> {org.reason}</p>}
        <p className="mt-2">Please contact your LendFlow administrator.</p>
      </div>
      <Button variant="outline" className="mt-6 h-11 w-full" onClick={() => void signOut()}>Sign out</Button>
    </AuthLayout>
  )
}
