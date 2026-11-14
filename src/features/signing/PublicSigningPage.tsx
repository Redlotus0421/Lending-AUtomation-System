import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useParams } from 'react-router-dom'
import {
  AlertTriangle, ArrowLeft, ArrowRight, CheckCircle2, Clock, Download, FileText, Lock, PenLine, ShieldCheck,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Spinner } from '@/components/common'
import { PdfViewer } from '@/components/common/PdfViewer'
import { SignaturePad, type SignaturePadHandle } from '@/components/common/SignaturePad'
import { publicSigning, type PublicSigningContext } from '@/services/agreements'
import { errorMessage } from '@/lib/errors'
import { fmtDate, fmtDateTime, METHOD_LABEL, peso, PERIOD_LABEL, SIGNER_LABEL, trimNumber } from '@/lib/format'
import { cn } from '@/lib/utils'

type Step = 'review' | 'consent' | 'sign' | 'done'

export default function PublicSigningPage() {
  const { token = '' } = useParams()
  const storageKey = `lf-sign-${token.slice(0, 16)}`
  const [ctx, setCtx] = useState<PublicSigningContext | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [nonce, setNonce] = useState<string | null>(() => sessionStorage.getItem(storageKey))
  const [step, setStep] = useState<Step>('review')

  const loadDocument = useCallback(async (n: string) => {
    try {
      const full = await publicSigning.document(token, n)
      setCtx(full)
      if (full.session.status === 'signed') setStep('done')
      return true
    } catch (e) {
      const msg = errorMessage(e)
      if (/verify your identity/i.test(msg)) {
        sessionStorage.removeItem(storageKey)
        setNonce(null)
        return false
      }
      setError(msg)
      return false
    }
  }, [token, storageKey])

  useEffect(() => {
    let alive = true
    ;(async () => {
      setLoading(true)
      try {
        const base = await publicSigning.load(token)
        if (!alive) return
        setCtx(base)
        if (nonce && ['verified', 'signed', 'opened', 'pending'].includes(base.session.status)) await loadDocument(nonce)
      } catch (e) {
        if (alive) setError(errorMessage(e))
      } finally {
        if (alive) setLoading(false)
      }
    })()
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token])

  const onVerified = async (n: string) => {
    sessionStorage.setItem(storageKey, n)
    setNonce(n)
    setLoading(true)
    await loadDocument(n)
    setLoading(false)
  }

  if (loading && !ctx) return <Shell><Centered><Spinner className="size-6" /><p className="mt-3 text-sm text-muted-foreground">Opening your agreement…</p></Centered></Shell>
  if (error && !ctx) return <Shell><Problem title="We couldn't open this link" message={error} /></Shell>
  if (!ctx) return null

  const s = ctx.session
  if (s.status === 'expired' || s.status === 'revoked') {
    return <Shell lender={ctx.lender.name}><Problem title={s.status === 'expired' ? 'This signing link has expired' : 'This signing link is no longer valid'}
      message="For your security, signing links are time-limited and single-use. Please ask your lender to send you a new link." /></Shell>
  }

  const hasDoc = !!ctx.loan
  if (!hasDoc || !nonce) {
    return <Shell lender={ctx.lender.name}><Verify ctx={ctx} token={token} onVerified={onVerified} /></Shell>
  }

  return (
    <Shell lender={ctx.lender.name} step={step}>
      {error && <p className="mb-3 rounded-lg bg-red-50 p-3 text-sm text-red-700">{error}</p>}
      {step === 'review' && <Review ctx={ctx} onNext={() => setStep('consent')} />}
      {step === 'consent' && <Consent ctx={ctx} onBack={() => setStep('review')} onNext={() => setStep('sign')} />}
      {step === 'sign' && (
        <Sign ctx={ctx} token={token} nonce={nonce} onBack={() => setStep('consent')}
          onSigned={async () => { await loadDocument(nonce); setStep('done') }} onError={setError} />
      )}
      {step === 'done' && <Done ctx={ctx} token={token} nonce={nonce} />}
    </Shell>
  )
}

function Shell({ children, lender, step }: { children: ReactNode; lender?: string; step?: Step }) {
  const steps: Step[] = ['review', 'consent', 'sign', 'done']
  return (
    <div className="min-h-dvh bg-[#F5F7FB]">
      <header className="sticky top-0 z-20 border-b bg-[#142B4A] text-white">
        <div className="mx-auto flex max-w-2xl items-center gap-3 px-4 py-3">
          <span className="grid size-9 place-items-center rounded-lg bg-white/10"><ShieldCheck className="size-5 text-teal" /></span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold">{lender ?? 'Secure document signing'}</p>
            <p className="flex items-center gap-1 text-[11px] text-white/60"><Lock className="size-3" /> Secure, encrypted signing session</p>
          </div>
        </div>
        {step && (
          <div className="mx-auto flex max-w-2xl gap-1.5 px-4 pb-3">
            {steps.map((x, i) => <span key={x} className={cn('h-1 flex-1 rounded-full', steps.indexOf(step) >= i ? 'bg-teal' : 'bg-white/20')} />)}
          </div>
        )}
      </header>
      <main className="mx-auto max-w-2xl px-4 pb-32 pt-5">{children}</main>
    </div>
  )
}

function Centered({ children }: { children: ReactNode }) {
  return <div className="flex min-h-[60vh] flex-col items-center justify-center text-center">{children}</div>
}

function Problem({ title, message }: { title: string; message: string }) {
  return (
    <Centered>
      <span className="mb-4 grid size-14 place-items-center rounded-full bg-amber-100 text-amber-600"><AlertTriangle className="size-7" /></span>
      <h1 className="text-lg font-semibold">{title}</h1>
      <p className="mt-2 max-w-sm text-sm text-muted-foreground">{message}</p>
    </Centered>
  )
}

function BottomBar({ children }: { children: ReactNode }) {
  return (
    <div className="pb-safe fixed inset-x-0 bottom-0 z-20 border-t bg-white/95 backdrop-blur">
      <div className="mx-auto flex max-w-2xl gap-2 px-4 py-3">{children}</div>
    </div>
  )
}

function Verify({ ctx, token, onVerified }: { ctx: PublicSigningContext; token: string; onVerified: (nonce: string) => void }) {
  const method = ctx.session.verification_method
  const [answer, setAnswer] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)
  const submit = async () => {
    setBusy(true)
    setMsg(null)
    try {
      const r = await publicSigning.verify(token, method === 'none' ? '' : answer)
      if (r.ok && r.nonce) onVerified(r.nonce)
      else setMsg(`${r.error ?? 'Verification failed.'}${r.attempts_left !== undefined && r.attempts_left > 0 ? ` ${r.attempts_left} attempt(s) left.` : ''}`)
    } catch (e) {
      setMsg(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="space-y-5">
      <div>
        <p className="text-sm text-muted-foreground">Hi {ctx.session.signer_name},</p>
        <h1 className="mt-1 text-xl font-semibold leading-snug">
          {ctx.lender.name} has sent you {ctx.session.status === 'signed' ? 'your signed' : 'a'} loan agreement {ctx.session.status === 'signed' ? '' : 'to review and sign'}
        </h1>
        <p className="mt-2 text-sm text-muted-foreground">Agreement {ctx.agreement.agreement_number} · You are signing as <b>{SIGNER_LABEL[ctx.session.signer_role] ?? ctx.session.signer_role}</b></p>
      </div>
      <div className="rounded-2xl border bg-white p-5 shadow-card">
        <p className="flex items-center gap-2 text-sm font-semibold"><ShieldCheck className="size-4 text-teal" /> Confirm it's you</p>
        {method === 'none' ? (
          <p className="mt-2 text-sm text-muted-foreground">Tap continue to open the document.</p>
        ) : (
          <div className="mt-3 space-y-2">
            <label className="text-sm font-medium">{method === 'dob' ? 'Your date of birth' : 'Last 4 characters of your ID number'}</label>
            <Input type={method === 'dob' ? 'date' : 'text'} inputMode={method === 'dob' ? undefined : 'text'} maxLength={method === 'dob' ? undefined : 12}
              value={answer} onChange={(e) => setAnswer(e.target.value)} className="h-12 text-base" autoComplete="off"
              placeholder={method === 'dob' ? undefined : 'e.g. 3456'} />
            <p className="text-xs text-muted-foreground">This protects your personal and loan information.</p>
          </div>
        )}
        {msg && <p className="mt-3 rounded-lg bg-red-50 p-3 text-sm text-red-700">{msg}</p>}
      </div>
      {ctx.session.expires_at && <p className="flex items-center gap-1.5 text-xs text-muted-foreground"><Clock className="size-3.5" /> Link valid until {fmtDateTime(ctx.session.expires_at)}</p>}
      <BottomBar>
        <Button className="h-12 flex-1 text-base" onClick={submit} disabled={busy || (method !== 'none' && !answer.trim())}>
          {busy ? <Spinner /> : null} Continue <ArrowRight />
        </Button>
      </BottomBar>
    </div>
  )
}

function Row({ label, value, strong }: { label: string; value: ReactNode; strong?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-1.5">
      <span className="text-sm text-muted-foreground">{label}</span>
      <span className={cn('tabular text-right text-sm', strong && 'text-base font-semibold')}>{value}</span>
    </div>
  )
}

function Review({ ctx, onNext }: { ctx: PublicSigningContext; onNext: () => void }) {
  const l = ctx.loan!
  const [opened, setOpened] = useState(false)
  const [read, setRead] = useState(false)
  const viewerRef = useRef<HTMLDivElement>(null)
  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-xl font-semibold">Review your loan</h1>
        <p className="text-sm text-muted-foreground">Please read the summary and the complete agreement before signing.</p>
      </div>
      <div className="rounded-2xl border bg-white p-5 shadow-card">
        <div className="rounded-xl bg-[#142B4A] p-4 text-white">
          <p className="text-xs uppercase tracking-wide text-white/60">You will pay</p>
          <p className="tabular text-2xl font-semibold">{peso(l.installment_amount)} <span className="text-sm font-normal text-white/70">× {l.installment_count} {l.repayment_frequency}</span></p>
          <p className="text-xs text-white/70">From {fmtDate(l.first_due_date)} to {fmtDate(l.last_due_date)}</p>
        </div>
        <div className="mt-3 divide-y">
          <Row label="Loan amount" value={peso(l.principal)} />
          <Row label="Amount you receive" value={peso(l.net_proceeds)} />
          <Row label="Interest" value={`${trimNumber(l.interest_rate)}% per ${PERIOD_LABEL[l.rate_period]} · ${METHOD_LABEL[l.interest_method]}`} />
          <Row label="Total interest" value={peso(l.total_interest)} />
          {Number(l.total_charges) > 0 && <Row label="Fees & charges" value={peso(l.total_charges)} />}
          <Row label="Effective interest rate" value={l.eir_monthly_pct != null ? `${trimNumber(l.eir_monthly_pct, 2)}% / month` : '—'} />
          <Row label="Total amount to repay" value={peso(l.total_repayment)} strong />
        </div>
      </div>
      <div ref={viewerRef} onPointerDown={() => setOpened(true)} onScroll={() => setOpened(true)}>
        <p className="mb-2 flex items-center gap-1.5 text-sm font-semibold"><FileText className="size-4" /> Full agreement</p>
        <PdfViewer url={ctx.pdf_url} height="60vh" fileName={`${ctx.agreement.agreement_number}.pdf`} />
        <p className="mt-1.5 text-xs text-muted-foreground">Tap ⤢ for full screen. Use + / − to zoom.</p>
      </div>
      <label className={cn('flex items-start gap-3 rounded-xl border bg-white p-4 text-sm', !opened && 'opacity-60')}>
        <Checkbox checked={read} disabled={!opened} onCheckedChange={(c) => setRead(!!c)} className="mt-0.5 size-5" />
        <span>I have opened and read the entire agreement, including the Disclosure Statement and Repayment Schedule.</span>
      </label>
      {!opened && <p className="-mt-3 text-xs text-muted-foreground">Open the agreement above to continue.</p>}
      <BottomBar>
        <Button className="h-12 flex-1 text-base" disabled={!read} onClick={onNext}>Continue <ArrowRight /></Button>
      </BottomBar>
    </div>
  )
}

function Consent({ ctx, onBack, onNext }: { ctx: PublicSigningContext; onBack: () => void; onNext: () => void }) {
  const [agree, setAgree] = useState(false)
  const order = ctx.agreement.signing_order
  const lenderSigned = ctx.signatures?.some((x) => x.signer_role === 'lender')
  const blocked = order === 'lender_first' && ctx.session.signer_role === 'borrower' && ctx.agreement.required_signers.includes('lender') && !lenderSigned
  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-xl font-semibold">Consent to sign electronically</h1>
        <p className="text-sm text-muted-foreground">Your electronic signature will be permanently embedded in the PDF.</p>
      </div>
      <div className="rounded-2xl border bg-white p-5 text-sm leading-relaxed shadow-card">{ctx.consent_text}</div>
      <label className="flex items-start gap-3 rounded-xl border bg-white p-4 text-sm">
        <Checkbox checked={agree} onCheckedChange={(c) => setAgree(!!c)} className="mt-0.5 size-5" />
        <span>I agree and consent to sign electronically.</span>
      </label>
      {blocked && <p className="rounded-lg bg-amber-50 p-3 text-sm text-amber-900">The lender must sign first. Please come back to this link once you're notified.</p>}
      <BottomBar>
        <Button variant="outline" className="h-12" onClick={onBack}><ArrowLeft /></Button>
        <Button className="h-12 flex-1 text-base" disabled={!agree || blocked} onClick={onNext}>Continue to sign <PenLine /></Button>
      </BottomBar>
    </div>
  )
}

function Sign({ ctx, token, nonce, onBack, onSigned, onError }: {
  ctx: PublicSigningContext; token: string; nonce: string; onBack: () => void; onSigned: () => Promise<void>; onError: (m: string | null) => void
}) {
  const pad = useRef<SignaturePadHandle>(null)
  const [empty, setEmpty] = useState(true)
  const [preview, setPreview] = useState<string | null>(null)
  const [typed, setTyped] = useState('')
  const [busy, setBusy] = useState(false)
  const nameOk = ctx.session.signer_role !== 'borrower' || typed.trim().toLowerCase().replace(/\s+/g, ' ') === ctx.session.signer_name.trim().toLowerCase().replace(/\s+/g, ' ')

  const submit = async () => {
    if (!preview) return
    setBusy(true)
    onError(null)
    try {
      await publicSigning.sign(token, nonce, preview, typed)
      await onSigned()
    } catch (e) {
      onError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-xl font-semibold">{preview ? 'Confirm your signature' : 'Draw your signature'}</h1>
        <p className="text-sm text-muted-foreground">{preview ? 'This exact signature will be placed on the agreement.' : 'Use your finger or a stylus. Turn your phone sideways for more space.'}</p>
      </div>
      {!preview ? (
        <SignaturePad ref={pad} onChange={setEmpty} height={260} />
      ) : (
        <div className="rounded-2xl border bg-white p-5 shadow-card">
          <img src={preview} alt="Your signature" className="mx-auto max-h-40" />
          <div className="mt-3 border-t pt-2 text-center">
            <p className="font-semibold">{ctx.session.signer_name}</p>
            <p className="text-xs text-muted-foreground">{SIGNER_LABEL[ctx.session.signer_role]} · {ctx.agreement.agreement_number}</p>
          </div>
        </div>
      )}
      {preview && ctx.session.signer_role === 'borrower' && (
        <div className="space-y-1.5">
          <label className="text-sm font-medium">Type your full name to confirm</label>
          <Input value={typed} onChange={(e) => setTyped(e.target.value)} className="h-12 text-base" placeholder={ctx.session.signer_name} autoCapitalize="words" />
          {typed && !nameOk && <p className="text-xs text-red-600">Must match: {ctx.session.signer_name}</p>}
        </div>
      )}
      <BottomBar>
        {preview ? (
          <>
            <Button variant="outline" className="h-12" disabled={busy} onClick={() => setPreview(null)}>Redraw</Button>
            <Button className="h-12 flex-1 bg-teal text-base hover:bg-teal/90" disabled={busy || !nameOk} onClick={submit}>
              {busy ? <Spinner /> : <CheckCircle2 />} {busy ? 'Signing…' : 'Confirm & sign'}
            </Button>
          </>
        ) : (
          <>
            <Button variant="outline" className="h-12" onClick={onBack}><ArrowLeft /></Button>
            <Button className="h-12 flex-1 text-base" disabled={empty} onClick={() => setPreview(pad.current?.toPng() ?? null)}>Preview signature</Button>
          </>
        )}
      </BottomBar>
    </div>
  )
}

function Done({ ctx, token, nonce }: { ctx: PublicSigningContext; token: string; nonce: string }) {
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const fully = ctx.agreement.status === 'fully_signed'
  const pending = ctx.agreement.required_signers.filter((r) => !ctx.signatures?.some((s) => s.signer_role === r))
  return (
    <div className="space-y-5">
      <Centered>
        <span className="mb-4 grid size-16 place-items-center rounded-full bg-green-100 text-green-600"><CheckCircle2 className="size-9" /></span>
        <h1 className="text-xl font-semibold">Thank you — you've signed</h1>
        <p className="mt-2 max-w-sm text-sm text-muted-foreground">
          Your signature is permanently embedded in agreement {ctx.agreement.agreement_number}.
          {fully ? ' All parties have signed. Download your copy below.' : ` Waiting for: ${pending.map((p) => SIGNER_LABEL[p] ?? p).join(', ')}.`}
        </p>
      </Centered>
      <div className="rounded-2xl border bg-white p-5 shadow-card">
        <p className="mb-2 text-sm font-semibold">Signing status</p>
        <ul className="space-y-2">
          {ctx.agreement.required_signers.map((r) => {
            const s = ctx.signatures?.find((x) => x.signer_role === r)
            return (
              <li key={r} className="flex items-center justify-between text-sm">
                <span className="flex items-center gap-2">{s ? <CheckCircle2 className="size-4 text-green-600" /> : <Clock className="size-4 text-slate-400" />}{SIGNER_LABEL[r] ?? r}</span>
                <span className="text-xs text-muted-foreground">{s ? fmtDateTime(s.signed_at) : 'Pending'}</span>
              </li>
            )
          })}
        </ul>
      </div>
      {err && <p className="rounded-lg bg-red-50 p-3 text-sm text-red-700">{err}</p>}
      <p className="text-center text-xs text-muted-foreground">You can return to this link until it expires to download your agreement.</p>
      <BottomBar>
        <Button className="h-12 flex-1 text-base" disabled={busy} onClick={async () => {
          setBusy(true)
          setErr(null)
          try {
            const r = await publicSigning.download(token, nonce)
            window.location.href = r.url
          } catch (e) {
            setErr(errorMessage(e))
          } finally {
            setBusy(false)
          }
        }}>{busy ? <Spinner /> : <Download />} Download {fully ? 'signed agreement' : 'agreement (so far)'}</Button>
      </BottomBar>
    </div>
  )
}
