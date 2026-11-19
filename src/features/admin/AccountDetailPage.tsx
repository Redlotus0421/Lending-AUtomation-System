import { useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ArrowLeft, Ban, HandCoins, KeyRound, Pencil, PlayCircle, UserPlus, Users, Wallet, Contact } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  DataTable, EmptyState, ErrorState, Field, KeyValue, PageHeader, PageLoader, Panel, Pill, Spinner, StatCard, type Column,
} from '@/components/common'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { platformService } from '@/services/platform'
import { ROLE_LABEL } from '@/lib/constants'
import { fmtDate, fmtDateTime, initials, num, peso, timeAgo } from '@/lib/format'
import { errorMessage } from '@/lib/errors'
import type { LendingMode, PlatformMember, PlatformOrgDetail } from '@/types/db'
import {
  AccountStatusPill, AddUserDialog, CredentialsDialog, LENDING_MODES, LendingModePicker, LendingModePill, ResetPasswordDialog, type Credentials,
} from './dialogs'
import { AccountTemplatesPanel } from './AccountTemplates'

const ROLE_TONE: Record<string, 'navy' | 'blue' | 'teal' | 'amber'> = { owner: 'navy', admin: 'blue', loan_officer: 'teal', collector: 'amber' }

export default function AccountDetailPage() {
  const { id = '' } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['platform', 'organization', id], queryFn: () => platformService.getOrganization(id) })
  const [creds, setCreds] = useState<Credentials | null>((location.state as { creds?: Credentials } | null)?.creds ?? null)
  const [adding, setAdding] = useState(false)
  const [editing, setEditing] = useState(false)
  const [pwFor, setPwFor] = useState<PlatformMember | null>(null)
  const [statusAction, setStatusAction] = useState<'suspend' | 'reactivate' | null>(null)

  const refresh = () => qc.invalidateQueries({ queryKey: ['platform'] })
  const closeCreds = () => {
    setCreds(null)
    if (location.state) navigate(location.pathname, { replace: true, state: null })
  }

  if (q.isLoading) return <PageLoader />
  if (q.error || !q.data) return <ErrorState error={q.error ?? new Error('Account not found')} onRetry={() => void q.refetch()} />
  const o = q.data

  const columns: Column<PlatformMember>[] = [
    {
      key: 'name', header: 'Name', mobile: 'title',
      cell: (m) => (
        <div className="flex items-center gap-3">
          <span className="hidden size-9 shrink-0 place-items-center rounded-full bg-[#142B4A]/10 text-xs font-semibold text-[#142B4A] md:grid">{initials(m.full_name || m.email || '?')}</span>
          <div className="min-w-0">
            <p className="truncate font-medium">{m.full_name || '—'}</p>
            <p className="truncate text-xs text-muted-foreground">{m.email}</p>
          </div>
        </div>
      ),
    },
    { key: 'role', header: 'Role', mobile: 'meta', cell: (m) => m.role ? <Pill tone={ROLE_TONE[m.role]} dot={false}>{ROLE_LABEL[m.role]}</Pill> : <Pill>No role</Pill> },
    { key: 'status', header: 'Status', mobile: 'meta', cell: (m) => m.is_active ? <Pill tone="green">Active</Pill> : <Pill tone="slate">Deactivated</Pill> },
    { key: 'seen', header: 'Last sign-in', mobile: 'subtitle', cell: (m) => <span className="text-sm text-muted-foreground">{m.last_sign_in_at ? timeAgo(m.last_sign_in_at) : 'Never signed in'}</span> },
    { key: 'added', header: 'Added', mobile: false, cell: (m) => <span className="text-sm text-muted-foreground">{fmtDate(m.created_at)}</span> },
    {
      key: 'actions', header: '', align: 'right', mobile: 'value',
      cell: (m) => <Button variant="outline" size="sm" onClick={(e) => { e.stopPropagation(); setPwFor(m) }}><KeyRound /> Reset password</Button>,
    },
  ]

  return (
    <div className="space-y-5">
      <PageHeader
        back={<Link to="/admin/accounts" className="mb-2 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"><ArrowLeft className="size-4" /> Lender accounts</Link>}
        title={<span className="flex flex-wrap items-center gap-3">{o.name} <AccountStatusPill status={o.status} /> <LendingModePill mode={o.lending_mode ?? 'complete'} /></span>}
        description={`Created ${fmtDate(o.created_at, 'long')}${o.legal_name ? ` · ${o.legal_name}` : ''}`}
        actions={<>
          <Button variant="outline" onClick={() => setEditing(true)}><Pencil /> Edit details</Button>
          <Button variant="outline" onClick={() => setAdding(true)}><UserPlus /> Add login</Button>
          {o.status === 'active'
            ? <Button variant="destructive" onClick={() => setStatusAction('suspend')}><Ban /> Suspend</Button>
            : <Button onClick={() => setStatusAction('reactivate')}><PlayCircle /> Reactivate</Button>}
        </>}
      />

      {o.status === 'suspended' && (
        <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-900">
          <p className="font-semibold">This account is suspended{o.status_changed_at ? ` since ${fmtDateTime(o.status_changed_at)}` : ''}.</p>
          <p className="mt-0.5">None of its users can sign in to the workspace. Its records are kept unchanged. Reason: {o.status_reason || '—'}</p>
        </div>
      )}

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
        <StatCard label="Users" value={num(o.stats.users)} icon={<Users />} tone="navy" />
        <StatCard label="Borrowers" value={num(o.stats.borrowers)} icon={<Contact />} tone="blue" />
        <StatCard label="Active loans" value={num(o.stats.active_loans)} hint={`${num(o.stats.loans)} loans in total`} icon={<HandCoins />} tone="teal" />
        <StatCard label="Outstanding" value={peso(o.stats.outstanding, { compact: true })} icon={<Wallet />} tone="amber" />
        <StatCard label="Collected" value={peso(o.stats.collected, { compact: true })} hint="All posted payments" icon={<Wallet />} tone="green" className="col-span-2 lg:col-span-1" />
      </div>

      <LendingModePanel key={o.lending_mode} org={o} onChanged={() => void refresh()} />

      <AccountTemplatesPanel org={o} />

      <div className="grid gap-5 lg:grid-cols-[1fr_1.6fr]">
        <Panel title="Business details">
          <KeyValue columns={1} items={[
            { label: 'Business / trade name', value: o.name },
            { label: 'Registered legal name', value: o.legal_name },
            { label: 'SEC reg. / Certificate of Authority', value: o.registration_no },
            { label: 'TIN', value: o.tin },
            { label: 'Address', value: [o.address, o.city, o.province, o.zip_code].filter(Boolean).join(', ') || null },
            { label: 'Contact', value: [o.contact_email, o.contact_phone].filter(Boolean).join(' · ') || null },
            { label: 'Owner', value: o.owner ? `${o.owner.full_name} (${o.owner.email})` : null },
          ]} />
        </Panel>
        <Panel title="Logins" description="Staff added by the owner appear here too." bodyClassName="p-3 sm:p-4"
          actions={<Button size="sm" variant="outline" onClick={() => setAdding(true)}><UserPlus /> Add login</Button>}>
          <DataTable columns={columns} rows={o.members} rowKey={(m) => m.id} empty={<EmptyState title="No logins" />} />
        </Panel>
      </div>

      {adding && <AddUserDialog orgId={o.id} orgName={o.name} onClose={() => setAdding(false)} onCreated={(c) => { setAdding(false); setCreds(c) }} />}
      {pwFor && <ResetPasswordDialog user={pwFor} orgName={o.name} onClose={() => setPwFor(null)} onDone={(c) => { setPwFor(null); setCreds(c) }} />}
      {editing && <EditAccountDialog org={o} onClose={() => setEditing(false)} />}
      {creds && <CredentialsDialog creds={creds} onClose={closeCreds} />}
      <ConfirmDialog
        open={statusAction === 'suspend'}
        onOpenChange={(v) => !v && setStatusAction(null)}
        title={`Suspend ${o.name}?`}
        description="All of this account's users are blocked from the workspace until you reactivate it. No records are deleted."
        confirmLabel="Suspend account"
        destructive
        requireReason
        reasonLabel="Reason (visible to the account's users)"
        onConfirm={async (reason) => {
          await platformService.setStatus(o.id, 'suspended', reason)
          await refresh()
          toast.success(`${o.name} is suspended`)
        }}
      />
      <ConfirmDialog
        open={statusAction === 'reactivate'}
        onOpenChange={(v) => !v && setStatusAction(null)}
        title={`Reactivate ${o.name}?`}
        description="Its users can sign in and work again immediately."
        confirmLabel="Reactivate"
        onConfirm={async () => {
          await platformService.setStatus(o.id, 'active')
          await refresh()
          toast.success(`${o.name} is active again`)
        }}
      />
    </div>
  )
}

const EDIT_FIELDS: { key: keyof PlatformOrgDetail; label: string; wide?: boolean }[] = [
  { key: 'name', label: 'Business / trade name' },
  { key: 'legal_name', label: 'Registered legal name' },
  { key: 'registration_no', label: 'SEC reg. / Certificate of Authority no.' },
  { key: 'tin', label: 'TIN' },
  { key: 'address', label: 'Business address', wide: true },
  { key: 'city', label: 'City / municipality' },
  { key: 'province', label: 'Province' },
  { key: 'contact_email', label: 'Contact email' },
  { key: 'contact_phone', label: 'Contact phone' },
]

function EditAccountDialog({ org, onClose }: { org: PlatformOrgDetail; onClose: () => void }) {
  const qc = useQueryClient()
  const [values, setValues] = useState<Record<string, string>>(
    Object.fromEntries(EDIT_FIELDS.map((f) => [f.key, (org[f.key] as string | null) ?? ''])),
  )
  const [busy, setBusy] = useState(false)
  const save = async () => {
    if (values.name.trim().length < 2) return toast.error('Business name is required')
    setBusy(true)
    try {
      await platformService.updateOrganization(org.id, values)
      await qc.invalidateQueries({ queryKey: ['platform'] })
      toast.success('Account details saved')
      onClose()
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(o) => !o && !busy && onClose()}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Edit account details</DialogTitle>
          <DialogDescription>These details print on the account's agreements and receipts. The owner can also change them in Settings.</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          {EDIT_FIELDS.map((f) => (
            <Field key={f.key} label={f.label} required={f.key === 'name'} className={f.wide ? 'sm:col-span-2' : undefined}>
              <Input className="h-10" value={values[f.key]} onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))} />
            </Field>
          ))}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button onClick={() => void save()} disabled={busy}>{busy && <Spinner />} Save</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function LendingModePanel({ org, onChanged }: { org: PlatformOrgDetail; onChanged: () => void }) {
  const current = org.lending_mode ?? 'complete'
  const [mode, setMode] = useState<LendingMode>(current)
  const [confirm, setConfirm] = useState(false)
  const target = LENDING_MODES.find((m) => m.value === mode)!
  return (
    <Panel title="Lending mode" description="Controls the loan-creation workflow for every lender and staff member of this business. Users are never asked to choose it themselves.">
      <LendingModePicker value={mode} onChange={setMode} />
      <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs text-muted-foreground">
          {org.lending_mode_changed_at ? `Last changed ${fmtDateTime(org.lending_mode_changed_at)}. ` : ''}Loans already in progress keep their workflow; only new loans follow the selected mode.
        </p>
        <Button disabled={mode === current} onClick={() => setConfirm(true)}>Apply {target.label}</Button>
      </div>
      <ConfirmDialog
        open={confirm}
        onOpenChange={setConfirm}
        title={`Switch ${org.name} to ${target.label}?`}
        description={mode === 'simplified'
          ? 'All users will create loans with the simplified New Loan / Reloan-Renew workflow, using the interest rate and fees in the business settings. Existing loans are not changed.'
          : 'All users will create loans with the comprehensive workflow. Existing simplified loans keep their terms and continue through approval, signing and release.'}
        confirmLabel={`Switch to ${target.label}`}
        onConfirm={async () => {
          await platformService.setLendingMode(org.id, mode)
          onChanged()
          toast.success(`${org.name} now uses ${target.label}`)
        }}
      />
    </Panel>
  )
}
