import { useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  Ban, CheckCircle2, Circle, Copy, Download, ExternalLink, FileSignature, FileText, Fingerprint, History, Link2,
  MessageSquare, PenLine, Printer, QrCode as QrIcon, RefreshCw, Share2, ShieldCheck, Smartphone, Wrench,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { AgreementStatusBadge, EmptyState, NativeSelect, Panel, Pill, Spinner } from '@/components/common'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { PdfViewer } from '@/components/common/PdfViewer'
import { QrCode } from '@/components/common/QrCode'
import { SignaturePad, type SignaturePadHandle } from '@/components/common/SignaturePad'
import { useAuth } from '@/features/auth/AuthProvider'
import { agreementsService } from '@/services/agreements'
import { borrowersService } from '@/services/borrowers'
import { errorMessage } from '@/lib/errors'
import { fmtDateTime, phMobile, SIGNER_LABEL, timeAgo } from '@/lib/format'
import { cn } from '@/lib/utils'
import type { Agreement, LoanView, SigningSession } from '@/types/db'

const OPEN = ['pending_signatures', 'partially_signed']

export function AgreementPanel({ loan }: { loan: LoanView }) {
  const qc = useQueryClient()
  const { can } = useAuth()
  const agreements = useQuery({ queryKey: ['loan', loan.id, 'agreements'], queryFn: () => agreementsService.forLoan(loan.id) })
  const list = agreements.data ?? []
  const current = list.find((a) => a.status !== 'superseded' && a.status !== 'voided' && a.status !== 'failed') ?? list[0]
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const shown = list.find((a) => a.id === selectedId) ?? current
  const [voidOpen, setVoidOpen] = useState(false)

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ['loan', loan.id] })
    void qc.invalidateQueries({ queryKey: ['loans'] })
  }

  const generate = useMutation({
    mutationFn: () => agreementsService.generate(loan.id),
    onSuccess: (r) => {
      toast.success(`Agreement ${r.agreement_number} generated`)
      if (r.missing_placeholders?.length) toast.warning(`Unknown placeholders left in template: ${r.missing_placeholders.join(', ')}`)
      setSelectedId(null)
      invalidate()
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  const canGenerateStatus = ['draft', 'pending_review', 'approved', 'awaiting_borrower_signature', 'awaiting_lender_signature'].includes(loan.status)
  const hasSigned = !!current && current.current_signature_count > 0
  const generateDisabled = !can('agreements.generate') || generate.isPending ||
    (!canGenerateStatus && !(['active', 'defaulted'].includes(loan.status)))

  if (agreements.isLoading) return <Panel title="Loan agreement"><Spinner /></Panel>

  if (!current) {
    return (
      <Panel title="Loan agreement">
        <EmptyState icon={<FileSignature />} title="No agreement generated yet"
          description={canGenerateStatus ? 'Generate the agreement PDF from the selected template. Borrower details, loan terms, today’s date and the full schedule are filled in automatically.' : 'Agreements can be generated while the loan is in draft, review or approved status.'}
          action={can('agreements.generate') && canGenerateStatus && (
            <Button onClick={() => generate.mutate()} disabled={generate.isPending}>{generate.isPending ? <Spinner /> : <FileSignature />} Generate agreement</Button>
          )} />
      </Panel>
    )
  }

  return (
    <div className="space-y-5">
      {list.length > 1 && (
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className="text-muted-foreground">Version:</span>
          {list.map((a) => (
            <button key={a.id} onClick={() => setSelectedId(a.id)}
              className={cn('rounded-full border px-3 py-1 text-xs', shown?.id === a.id ? 'border-[#2563EB] bg-blue-50 text-blue-700' : 'hover:bg-muted')}>
              v{a.version} · {a.status.replace(/_/g, ' ')}
            </button>
          ))}
        </div>
      )}
      {shown && <AgreementDetail agreement={shown} loan={loan} isCurrent={shown.id === current.id} onChanged={invalidate} />}

      {shown?.id === current.id && (
        <div className="flex flex-wrap gap-2">
          {canGenerateStatus && can('agreements.generate') && !hasSigned && (
            <Button variant="outline" onClick={() => generate.mutate()} disabled={generateDisabled}>
              {generate.isPending ? <Spinner /> : <RefreshCw />} Regenerate (new version)
            </Button>
          )}
          {OPEN.includes(current.status) && can('agreements.generate') && (
            <Button variant="outline" className="text-red-600 hover:text-red-700" onClick={() => setVoidOpen(true)}><Ban /> Void agreement</Button>
          )}
          {['active', 'defaulted'].includes(loan.status) && can('agreements.generate') && (
            <Button variant="outline" onClick={() => generate.mutate()} disabled={generate.isPending}>
              {generate.isPending ? <Spinner /> : <FileSignature />} Generate amendment agreement
            </Button>
          )}
        </div>
      )}
      <ConfirmDialog open={voidOpen} onOpenChange={setVoidOpen} title="Void this agreement?" destructive requireReason confirmLabel="Void agreement"
        description="Voided agreements are kept for the audit trail (including any signature already collected). You can then edit the loan and generate a new version."
        onConfirm={async (reason) => {
          await agreementsService.void(current.id, reason)
          toast.success('Agreement voided')
          invalidate()
        }} />
    </div>
  )
}

function AgreementDetail({ agreement: a, loan, isCurrent, onChanged }: { agreement: Agreement; loan: LoanView; isCurrent: boolean; onChanged: () => void }) {
  const { can } = useAuth()
  const qc = useQueryClient()
  const signatures = useQuery({ queryKey: ['agreement', a.id, 'signatures'], queryFn: () => agreementsService.signatures(a.id) })
  const sessions = useQuery({ queryKey: ['agreement', a.id, 'sessions'], queryFn: () => agreementsService.sessions(a.id), enabled: can('agreements.view') })
  const pdfPath = a.signed_pdf_path ?? a.current_pdf_path ?? a.unsigned_pdf_path
  const pdf = useQuery({ queryKey: ['agreement', a.id, 'pdf', pdfPath], queryFn: () => agreementsService.pdfUrl(pdfPath!), enabled: !!pdfPath, staleTime: 10 * 60_000 })
  const [linkFor, setLinkFor] = useState<string | null>(null)
  const [signRole, setSignRole] = useState<string | null>(null)
  const needsCoMaker = a.required_signers.includes('co_maker')
  const guarantors = useQuery({ queryKey: ['borrower', loan.borrower_id, 'guarantors'], queryFn: () => borrowersService.guarantors(loan.borrower_id), enabled: needsCoMaker })
  const coMaker = guarantors.data?.at(-1) ?? null
  const [showTrail, setShowTrail] = useState(false)
  const open = OPEN.includes(a.status)
  const signed = new Map((signatures.data ?? []).map((s) => [s.signer_role, s]))
  const needsRebuild = (signatures.data?.length ?? 0) > a.current_signature_count && a.status !== 'fully_signed'

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['agreement', a.id] })
    onChanged()
  }
  const rebuild = useMutation({
    mutationFn: () => agreementsService.rebuild(a.id),
    onSuccess: () => { toast.success('Signed PDF rebuilt from committed signatures'); refresh() },
    onError: (e) => toast.error(errorMessage(e)),
  })

  const orderBlocked = (role: string) => {
    if (a.signing_order === 'borrower_first' && role === 'lender' && !signed.has('borrower')) return 'The borrower signs first'
    if (a.signing_order === 'lender_first' && role === 'borrower' && a.required_signers.includes('lender') && !signed.has('lender')) return 'The lender signs first'
    return null
  }

  const downloadName = `${a.agreement_number}${a.status === 'fully_signed' ? '-signed' : ''}.pdf`

  return (
    <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_380px]">
      <div className="min-w-0 space-y-3">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="text-base font-semibold">{a.agreement_number}</h3>
          <AgreementStatusBadge status={a.status} />
          {a.kind === 'amendment' && <Pill tone="violet">Amendment</Pill>}
          <span className="text-xs text-muted-foreground">v{a.version} · {a.page_count ?? '—'} pages · generated {fmtDateTime(a.generated_at)}</span>
        </div>
        {a.status === 'failed' && <p className="rounded-lg bg-red-50 p-3 text-sm text-red-700">Generation failed: {a.error_message}</p>}
        {a.status === 'voided' && <p className="rounded-lg bg-red-50 p-3 text-sm text-red-700">Voided {fmtDateTime(a.voided_at)} — {a.void_reason}</p>}
        <PdfViewer url={pdf.data} fileName={downloadName} height="72vh" />
      </div>

      <div className="space-y-4">
        <Panel title="Signatures" description={`Signing order: ${a.signing_order.replace('_', ' ')}`}>
          <ul className="space-y-3">
            {a.required_signers.map((role) => {
              const s = signed.get(role)
              const session = sessions.data?.find((x) => x.signer_role === role && ['pending', 'opened', 'verified'].includes(x.status))
              const blocked = orderBlocked(role)
              return (
                <li key={role} className="rounded-lg border p-3">
                  <div className="flex items-start gap-2">
                    {s ? <CheckCircle2 className="mt-0.5 size-4 text-green-600" /> : <Circle className="mt-0.5 size-4 text-slate-300" />}
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-medium">{SIGNER_LABEL[role] ?? role}{role.startsWith('witness') ? ` ${role.slice(-1)}` : ''}</p>
                      {s ? (
                        <p className="text-xs text-muted-foreground">{s.signer_name} · {fmtDateTime(s.signed_at)}</p>
                      ) : session ? (
                        <p className="text-xs text-muted-foreground">Link {session.status} · expires {fmtDateTime(session.expires_at)}</p>
                      ) : (
                        <p className="text-xs text-muted-foreground">{blocked ?? 'Not signed yet'}</p>
                      )}
                    </div>
                  </div>
                  {!s && open && isCurrent && (
                    <div className="mt-2 flex flex-wrap gap-2">
                      {role === 'borrower' && can('agreements.generate') && (
                        <Button size="sm" variant={session ? 'outline' : 'default'} onClick={() => setLinkFor(role)}>
                          <Smartphone /> {session ? 'Share again' : 'Send signing link'}
                        </Button>
                      )}
                      {role === 'lender' && can('agreements.sign_lender') && (
                        <Button size="sm" disabled={!!blocked} onClick={() => setSignRole('lender')}><PenLine /> Sign as lender</Button>
                      )}
                      {role === 'co_maker' && can('agreements.generate') && (
                        <>
                          <Button size="sm" variant="outline" onClick={() => setSignRole(role)}><PenLine /> Sign in person</Button>
                          <Button size="sm" variant={session ? 'outline' : 'ghost'} onClick={() => setLinkFor(role)}><Smartphone /> {session ? 'Share again' : 'Send signing link'}</Button>
                        </>
                      )}
                      {role.startsWith('witness') && can('agreements.generate') && (
                        <>
                          <Button size="sm" variant="outline" onClick={() => setSignRole(role)}><PenLine /> Sign in person</Button>
                          <Button size="sm" variant="ghost" onClick={() => setLinkFor(role)}><Link2 /> Link</Button>
                        </>
                      )}
                    </div>
                  )}
                </li>
              )
            })}
          </ul>
          {needsRebuild && can('agreements.generate') && (
            <div className="mt-3 rounded-lg bg-amber-50 p-3 text-xs text-amber-900">
              A signature was committed but the signed PDF was not finalized (e.g. connection lost). The signature is safe.
              <Button size="sm" variant="outline" className="mt-2 w-full" onClick={() => rebuild.mutate()} disabled={rebuild.isPending}>
                {rebuild.isPending ? <Spinner /> : <Wrench />} Rebuild signed PDF
              </Button>
            </div>
          )}
        </Panel>

        <Panel title="Document integrity">
          <div className="space-y-3 text-xs">
            <HashRow label="Original (unsigned) SHA-256" value={a.unsigned_pdf_hash} />
            {a.signed_pdf_hash && <HashRow label="Final signed SHA-256" value={a.signed_pdf_hash} />}
            {a.fully_signed_at && <p className="flex items-center gap-1.5 text-green-700"><ShieldCheck className="size-3.5" /> Fully signed {fmtDateTime(a.fully_signed_at)} — locked against changes</p>}
          </div>
          <div className="mt-4 grid grid-cols-2 gap-2">
            {a.unsigned_pdf_path && <DownloadButton path={a.unsigned_pdf_path} name={`${a.agreement_number}-unsigned.pdf`} label="Unsigned" icon={<FileText />} />}
            {a.signed_pdf_path && <DownloadButton path={a.signed_pdf_path} name={`${a.agreement_number}-signed.pdf`} label="Signed PDF" icon={<Download />} primary />}
            {pdf.data && (
              <Button variant="outline" size="sm" onClick={() => { const w = window.open(pdf.data!, '_blank'); w?.focus() }}><Printer /> Print</Button>
            )}
            <Button variant="outline" size="sm" onClick={() => setShowTrail(true)}><History /> Audit trail</Button>
          </div>
        </Panel>
      </div>

      {linkFor && <SigningLinkDialog agreement={a} loan={loan} role={linkFor} coMaker={coMaker} onClose={() => { setLinkFor(null); refresh() }} />}
      {signRole && <InAppSignDialog agreement={a} role={signRole} coMakerName={coMaker?.full_name ?? ''} onClose={(done) => { setSignRole(null); if (done) refresh() }} />}
      {showTrail && <AuditTrailDialog agreement={a} onClose={() => setShowTrail(false)} />}
    </div>
  )
}

function HashRow({ label, value }: { label: string; value: string | null }) {
  return (
    <div>
      <p className="mb-0.5 flex items-center gap-1 text-muted-foreground"><Fingerprint className="size-3.5" /> {label}</p>
      <p className="break-all rounded bg-slate-50 p-1.5 font-mono text-[10.5px] leading-snug text-slate-700">{value ?? '—'}</p>
    </div>
  )
}

function DownloadButton({ path, name, label, icon, primary }: { path: string; name: string; label: string; icon: React.ReactNode; primary?: boolean }) {
  const [busy, setBusy] = useState(false)
  return (
    <Button variant={primary ? 'default' : 'outline'} size="sm" disabled={busy} onClick={async () => {
      setBusy(true)
      try {
        window.location.href = await agreementsService.pdfUrl(path, name)
      } catch (e) {
        toast.error(errorMessage(e))
      } finally {
        setBusy(false)
      }
    }}>{busy ? <Spinner /> : icon} {label}</Button>
  )
}

function SigningLinkDialog({ agreement, loan, role, coMaker, onClose }: {
  agreement: Agreement; loan: LoanView; role: string; coMaker?: { full_name: string; mobile_number: string | null } | null; onClose: () => void
}) {
  // the borrower's and co-maker's names come from the borrower record on the server
  const fixedName = role === 'borrower' || role === 'co_maker'
  const recipient = role === 'co_maker'
    ? { name: coMaker?.full_name ?? '', mobile: coMaker?.mobile_number ?? '' }
    : { name: loan.borrower_name, mobile: loan.borrower_mobile ?? '' }
  const { settings } = useAuth()
  const [hours, setHours] = useState(settings?.agreement.link_expiry_hours ?? 72)
  const [name, setName] = useState(role === 'borrower' ? loan.borrower_name : '')
  const [link, setLink] = useState<{ url: string; expires: string; verification: string } | null>(null)
  const sessions = useQuery({ queryKey: ['agreement', agreement.id, 'sessions'], queryFn: () => agreementsService.sessions(agreement.id) })
  const create = useMutation({
    mutationFn: () => agreementsService.createLink(agreement.id, role, fixedName ? undefined : name, undefined, hours),
    onSuccess: (r) => setLink({ url: agreementsService.signingLink(r.token), expires: r.expires_at, verification: r.verification_method }),
    onError: (e) => toast.error(errorMessage(e)),
  })
  const revoke = useMutation({
    mutationFn: (s: SigningSession) => agreementsService.revokeLink(s.id),
    onSuccess: () => { toast.success('Link revoked'); void sessions.refetch() },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const message = !link ? '' : role === 'co_maker'
    ? `Hi ${recipient.name.split(' ')[0]}, please review and sign as co-maker the loan agreement ${agreement.agreement_number} of ${loan.borrower_name} here: ${link.url} (valid until ${fmtDateTime(link.expires)}).`
    : `Hi ${loan.borrower_name.split(' ')[0]}, please review and sign your loan agreement ${agreement.agreement_number} here: ${link.url} (valid until ${fmtDateTime(link.expires)}).`

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{role === 'borrower' ? 'Borrower signing link' : `${SIGNER_LABEL[role]} signing link`}</DialogTitle>
          <DialogDescription>A secure, single-use, time-limited link. The signer reviews the agreement on their phone and signs with their finger.</DialogDescription>
        </DialogHeader>
        {!link ? (
          <div className="space-y-4">
            {role === 'co_maker' && (
              <p className="rounded-lg bg-blue-50 p-3 text-xs text-blue-900">Co-maker: <b>{recipient.name || '—'}</b>{recipient.mobile ? ` · ${phMobile(recipient.mobile)}` : ''}. Creating a new link revokes any previous unused link.</p>
            )}
            {!fixedName && (
              <div className="space-y-1.5"><label className="text-sm font-medium">Signer name</label><Input value={name} onChange={(e) => setName(e.target.value)} className="h-10" /></div>
            )}
            <div className="space-y-1.5">
              <label className="text-sm font-medium">Link valid for</label>
              <NativeSelect value={hours} onChange={(e) => setHours(Number(e.target.value))}>
                {[2, 24, 48, 72, 168].map((h) => <option key={h} value={h}>{h < 24 ? `${h} hours` : `${h / 24} day${h > 24 ? 's' : ''}`}</option>)}
              </NativeSelect>
            </div>
            {role === 'borrower' && (
              <p className="rounded-lg bg-blue-50 p-3 text-xs text-blue-900">
                Identity check: <b>{settings?.agreement.identity_verification === 'dob' ? 'date of birth' : settings?.agreement.identity_verification === 'id_last4' ? 'last 4 characters of ID number' : 'none'}</b>. Creating a new link revokes any previous unused link. Mobile on file: {phMobile(loan.borrower_mobile)}.
              </p>
            )}
            {!!sessions.data?.filter((s) => s.signer_role === role).length && (
              <div className="space-y-1.5">
                <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Previous links</p>
                {sessions.data.filter((s) => s.signer_role === role).slice(0, 4).map((s) => (
                  <div key={s.id} className="flex items-center justify-between gap-2 rounded border px-2 py-1.5 text-xs">
                    <span>{s.status} · created {timeAgo(s.created_at)}{s.failed_attempts ? ` · ${s.failed_attempts} failed checks` : ''}</span>
                    {['pending', 'opened', 'verified'].includes(s.status) && (
                      <button className="text-red-600 hover:underline" onClick={() => revoke.mutate(s)}>Revoke</button>
                    )}
                  </div>
                ))}
              </div>
            )}
            <DialogFooter>
              <Button variant="outline" onClick={onClose}>Close</Button>
              <Button onClick={() => create.mutate()} disabled={create.isPending || (!fixedName && !name.trim())}>{create.isPending ? <Spinner /> : <Link2 />} Create link</Button>
            </DialogFooter>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="flex flex-col items-center gap-2 rounded-xl border bg-white p-4">
              <QrCode value={link.url} size={190} />
              <p className="flex items-center gap-1 text-xs text-muted-foreground"><QrIcon className="size-3.5" /> {role === 'co_maker' ? 'The co-maker' : 'Borrower'} can scan this with their phone camera</p>
            </div>
            <div className="flex gap-2">
              <Input readOnly value={link.url} className="h-10 font-mono text-xs" onFocus={(e) => e.target.select()} />
              <Button variant="outline" size="icon" className="size-10 shrink-0" aria-label="Copy link" onClick={async () => { await navigator.clipboard.writeText(link.url); toast.success('Link copied') }}><Copy /></Button>
            </div>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              {'share' in navigator && (
                <Button variant="outline" onClick={() => void navigator.share({ title: 'Loan agreement', text: message, url: link.url }).catch(() => undefined)}><Share2 /> Share</Button>
              )}
              <Button variant="outline" asChild><a href={`sms:${recipient.mobile}?&body=${encodeURIComponent(message)}`}><MessageSquare /> SMS</a></Button>
              <Button variant="outline" onClick={async () => { await navigator.clipboard.writeText(message); toast.success('Message copied — paste it in Messenger/Viber') }}><Copy /> Message</Button>
              <Button variant="outline" asChild><a href={link.url} target="_blank" rel="noreferrer"><ExternalLink /> Open</a></Button>
            </div>
            <p className="text-xs text-muted-foreground">Expires {fmtDateTime(link.expires)}. The link is shown only once — create a new one if it is lost.</p>
            <DialogFooter><Button onClick={onClose}>Done</Button></DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}

function InAppSignDialog({ agreement, role, coMakerName = '', onClose }: { agreement: Agreement; role: string; coMakerName?: string; onClose: (done: boolean) => void }) {
  const isCoMaker = role === 'co_maker'
  const { access } = useAuth()
  const pad = useRef<SignaturePadHandle>(null)
  const [empty, setEmpty] = useState(true)
  const [consent, setConsent] = useState(false)
  const [name, setName] = useState('')
  const [preview, setPreview] = useState<string | null>(null)
  const sign = useMutation({
    mutationFn: (png: string) => agreementsService.signInApp(isCoMaker
      ? { agreement_id: agreement.id, role, signature: png, in_person: true }
      : { agreement_id: agreement.id, role, signature: png, signer_name: role === 'lender' ? undefined : name }),
    onSuccess: (r) => {
      const res = r.result as { finalized?: boolean }
      toast.success(res?.finalized ? 'Agreement fully signed — final PDF sealed' : 'Signature embedded in the agreement')
      onClose(true)
    },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const signerName = role === 'lender' ? access?.full_name ?? '' : isCoMaker ? coMakerName : name
  return (
    <Dialog open onOpenChange={(o) => !o && !sign.isPending && onClose(false)}>
      <DialogContent className="max-h-[94dvh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{role === 'lender' ? 'Sign as lender' : isCoMaker ? 'Co-maker signature (in person)' : 'Witness signature (in person)'}</DialogTitle>
          <DialogDescription>{isCoMaker ? `Hand the device to ${coMakerName || 'the co-maker'}. ` : ''}{agreement.agreement_number} — the signature will be permanently embedded in the PDF with the signer's name and a timestamp.</DialogDescription>
        </DialogHeader>
        {isCoMaker && <p className="text-sm font-semibold">{coMakerName}</p>}
        {role !== 'lender' && !isCoMaker && (
          <div className="space-y-1.5"><label className="text-sm font-medium">Witness full name</label><Input value={name} onChange={(e) => setName(e.target.value)} className="h-11" /></div>
        )}
        {!preview ? (
          <SignaturePad ref={pad} onChange={setEmpty} />
        ) : (
          <div className="rounded-xl border bg-white p-4">
            <p className="mb-2 text-xs text-muted-foreground">Signature preview</p>
            <img src={preview} alt="Signature preview" className="mx-auto max-h-36" />
            <p className="mt-2 border-t pt-2 text-center text-sm font-semibold">{signerName}</p>
          </div>
        )}
        <label className="flex items-start gap-2 text-sm">
          <Checkbox checked={consent} onCheckedChange={(c) => setConsent(!!c)} className="mt-0.5" />
          <span>I have reviewed agreement {agreement.agreement_number} and agree to sign it electronically. My electronic signature has the same legal effect as a handwritten signature (R.A. 8792).</span>
        </label>
        <DialogFooter className="gap-2">
          {preview ? (
            <>
              <Button variant="outline" onClick={() => setPreview(null)} disabled={sign.isPending}>Redraw</Button>
              <Button onClick={() => sign.mutate(preview)} disabled={!consent || sign.isPending || !signerName.trim()}>{sign.isPending ? <Spinner /> : <PenLine />} Confirm & sign</Button>
            </>
          ) : (
            <>
              <Button variant="outline" onClick={() => onClose(false)}>Cancel</Button>
              <Button disabled={empty || !signerName.trim()} onClick={() => setPreview(pad.current?.toPng() ?? null)}>Preview signature</Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function AuditTrailDialog({ agreement, onClose }: { agreement: Agreement; onClose: () => void }) {
  const events = useQuery({ queryKey: ['agreement', agreement.id, 'events'], queryFn: () => agreementsService.events(agreement.id) })
  const label: Record<string, string> = {
    generated: 'Agreement generated', link_created: 'Signing link created', link_revoked: 'Signing link revoked', viewed: 'Signing page opened',
    verification_failed: 'Identity check failed', verified: 'Identity verified', consent_given: 'Consent to e-sign given', signed: 'Signed',
    pdf_finalized: 'Final PDF sealed', downloaded: 'Document downloaded', voided: 'Agreement voided',
  }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-lg">
        <DialogHeader><DialogTitle>Signing audit trail — {agreement.agreement_number}</DialogTitle></DialogHeader>
        {events.isLoading ? <Spinner /> : (
          <ol className="relative space-y-4 border-l pl-5">
            {events.data?.map((e) => (
              <li key={e.id} className="relative">
                <span className={cn('absolute -left-[25px] top-1 size-2.5 rounded-full ring-4 ring-white', e.event_type === 'verification_failed' || e.event_type === 'voided' ? 'bg-red-500' : e.event_type === 'signed' || e.event_type === 'pdf_finalized' ? 'bg-green-500' : 'bg-slate-400')} />
                <p className="text-sm font-medium">{label[e.event_type] ?? e.event_type}{e.metadata?.signer_role ? ` — ${SIGNER_LABEL[e.metadata.signer_role] ?? e.metadata.signer_role}` : ''}</p>
                <p className="text-xs text-muted-foreground">{fmtDateTime(e.created_at)}{e.actor_name ? ` · ${e.actor_name}` : ''}{e.ip_address ? ` · IP ${e.ip_address}` : ''}</p>
                {e.metadata?.reason && <p className="text-xs text-muted-foreground">Reason: {e.metadata.reason}</p>}
              </li>
            ))}
          </ol>
        )}
      </DialogContent>
    </Dialog>
  )
}
