import { useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  Archive, ArrowLeft, Ban, Briefcase, Eye, FileSignature, FileText, HandCoins, Lock, Mail, MapPin, MoreHorizontal, Pencil, Phone,
  PlusCircle, RotateCcw, ShieldAlert, Trash2, Upload, UserPlus, Wallet,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  AgreementStatusBadge, DataTable, EmptyState, ErrorState, Field, KeyValue, LoanStatusBadge, NativeSelect, PageLoader, Panel, Pill,
  Spinner, StatCard, type Column,
} from '@/components/common'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { useAuth } from '@/features/auth/AuthProvider'
import { borrowersService, type BorrowerAgreement } from '@/services/borrowers'
import { loansService } from '@/services/loans'
import { agreementsService } from '@/services/agreements'
import { DOCUMENT_TYPES, RELATIONSHIPS } from '@/lib/constants'
import { durationLabel, fmtDate, fmtDateTime, normalizePhMobile, peso, phMobile, timeAgo, titleCase, toNumber } from '@/lib/format'
import { errorMessage } from '@/lib/errors'
import type { BorrowerDocument, Guarantor, LoanView } from '@/types/db'
import { BorrowerAvatar, BorrowerStatusBadge, RiskBadge, useBorrowerFileUrl } from './shared'

async function openInNewTab(getUrl: () => Promise<string>) {
  const w = window.open('', '_blank')
  try {
    const url = await getUrl()
    if (w) w.location.href = url
    else window.location.href = url
  } catch (e) {
    w?.close()
    toast.error(errorMessage(e))
  }
}

function IdImage({ path, label }: { path: string | null; label: string }) {
  const url = useBorrowerFileUrl(path)
  if (!path) return <div className="grid aspect-[1.6] place-items-center rounded-lg border border-dashed text-xs text-muted-foreground">{label}: not uploaded</div>
  const isPdf = path.toLowerCase().endsWith('.pdf')
  return (
    <button type="button" onClick={() => url && window.open(url, '_blank')} className="group relative block overflow-hidden rounded-lg border bg-slate-50 text-left">
      {url && !isPdf ? <img src={url} alt={label} className="aspect-[1.6] w-full object-cover" /> : (
        <div className="grid aspect-[1.6] place-items-center text-xs text-muted-foreground"><FileText className="mb-1 size-5" />{label} (PDF)</div>
      )}
      <span className="absolute bottom-1.5 left-1.5 rounded bg-black/60 px-1.5 py-0.5 text-[11px] text-white">{label}</span>
    </button>
  )
}

export default function BorrowerProfilePage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { can } = useAuth()
  const [tab, setTab] = useState('overview')
  const [statusAction, setStatusAction] = useState<null | 'archived' | 'blacklisted' | 'active'>(null)

  const borrower = useQuery({ queryKey: ['borrower', id], queryFn: () => borrowersService.get(id) })
  const identity = useQuery({ queryKey: ['borrower', id, 'identity'], queryFn: () => borrowersService.identity(id), enabled: can('borrowers.view_sensitive') })
  const loans = useQuery({ queryKey: ['borrower', id, 'loans'], queryFn: () => borrowersService.loans(id), enabled: can('loans.view') })

  if (borrower.isLoading) return <PageLoader />
  if (borrower.error || !borrower.data) return <ErrorState error={borrower.error ?? 'Borrower not found'} onRetry={() => void borrower.refetch()} />
  const b = borrower.data

  const loanRows = loans.data ?? []
  const booked = loanRows.filter((l) => ['active', 'completed', 'defaulted'].includes(l.status))
  const totalBorrowed = booked.reduce((a, l) => a + toNumber(l.principal), 0)
  const outstanding = loanRows.filter((l) => ['active', 'defaulted'].includes(l.status)).reduce((a, l) => a + toNumber(l.total_outstanding), 0)
  const overdue = loanRows.reduce((a, l) => a + toNumber(l.overdue_amount), 0)
  const overdueLoans = loanRows.filter((l) => toNumber(l.overdue_amount) > 0).length

  const statusCopy = {
    archived: { title: 'Archive borrower?', label: 'Archive', desc: 'Archived borrowers are hidden from lists and cannot receive new loans. Borrowers with open loans cannot be archived.' },
    blacklisted: { title: 'Blacklist borrower?', label: 'Blacklist', desc: 'Blacklisted borrowers cannot receive new loans. Their existing loans and history remain unchanged.' },
    active: { title: 'Reactivate borrower?', label: 'Reactivate', desc: 'The borrower will be able to apply for new loans again.' },
  }

  return (
    <div>
      <Link to="/borrowers" className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"><ArrowLeft className="size-4" /> Borrowers</Link>

      {/* header */}
      <div className="mb-5 rounded-xl border bg-card p-4 shadow-card sm:p-5">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-start">
          <BorrowerAvatar borrower={b} size={72} />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-xl font-semibold tracking-tight sm:text-2xl">{b.full_name}</h1>
              <BorrowerStatusBadge status={b.status} />
              <RiskBadge risk={b.risk_rating} />
            </div>
            <p className="mt-0.5 text-sm text-muted-foreground">{b.borrower_code} · Registered {fmtDate(b.created_at)}</p>
            <div className="mt-3 flex flex-wrap gap-x-5 gap-y-1.5 text-sm">
              <a href={`tel:${b.mobile_number}`} className="inline-flex items-center gap-1.5 text-blue-700 hover:underline"><Phone className="size-4" />{phMobile(b.mobile_number)}</a>
              {b.email && <a href={`mailto:${b.email}`} className="inline-flex items-center gap-1.5 text-blue-700 hover:underline"><Mail className="size-4" />{b.email}</a>}
              {b.city_municipality && <span className="inline-flex items-center gap-1.5 text-muted-foreground"><MapPin className="size-4" />{[b.city_municipality, b.province].filter(Boolean).join(', ')}</span>}
              {b.occupation && <span className="inline-flex items-center gap-1.5 text-muted-foreground"><Briefcase className="size-4" />{b.occupation}</span>}
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            {can('loans.create') && b.status === 'active' && (
              <Button onClick={() => navigate(`/loans/new?borrower=${b.id}`)}><PlusCircle /> New loan</Button>
            )}
            {can('borrowers.edit') && <Button variant="outline" onClick={() => navigate(`/borrowers/${b.id}/edit`)}><Pencil /> Edit</Button>}
            {can('borrowers.archive') && (
              <DropdownMenu>
                <DropdownMenuTrigger asChild><Button variant="outline" size="icon" aria-label="More actions"><MoreHorizontal /></Button></DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  {b.status !== 'active' && <DropdownMenuItem onClick={() => setStatusAction('active')}><RotateCcw /> Reactivate</DropdownMenuItem>}
                  {b.status !== 'blacklisted' && <DropdownMenuItem onClick={() => setStatusAction('blacklisted')}><Ban /> Blacklist</DropdownMenuItem>}
                  {b.status !== 'archived' && (<><DropdownMenuSeparator /><DropdownMenuItem variant="destructive" onClick={() => setStatusAction('archived')}><Archive /> Archive</DropdownMenuItem></>)}
                </DropdownMenuContent>
              </DropdownMenu>
            )}
          </div>
        </div>
        {b.status === 'blacklisted' && (
          <div className="mt-4 flex items-center gap-2 rounded-lg bg-red-50 p-3 text-sm text-red-800"><ShieldAlert className="size-4" /> This borrower is blacklisted and cannot receive new loans.</div>
        )}
      </div>

      {can('loans.view') && (
        <div className="mb-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
          <StatCard label="Total borrowed" value={peso(totalBorrowed)} hint={`${booked.length} released loan${booked.length === 1 ? '' : 's'}`} icon={<HandCoins />} tone="navy" />
          <StatCard label="Outstanding" value={peso(outstanding)} hint="Principal + interest + charges + penalties" icon={<Wallet />} tone="blue" />
          <StatCard label="Loans" value={loanRows.length} hint={`${loanRows.filter((l) => l.status === 'active').length} active`} icon={<FileSignature />} tone="teal" />
          <StatCard label="Overdue" value={peso(overdue)} hint={overdueLoans ? `${overdueLoans} loan${overdueLoans === 1 ? '' : 's'} past due` : 'No overdue amounts'} icon={<ShieldAlert />} tone={overdue > 0 ? 'red' : 'green'} />
        </div>
      )}

      <Tabs value={tab} onValueChange={setTab}>
        <div className="-mx-3 overflow-x-auto px-3 sm:mx-0 sm:px-0">
          <TabsList className="w-max">
            <TabsTrigger value="overview">Overview</TabsTrigger>
            {can('loans.view') && <TabsTrigger value="loans">Loans</TabsTrigger>}
            {can('payments.view') && <TabsTrigger value="payments">Payments</TabsTrigger>}
            <TabsTrigger value="documents">Documents</TabsTrigger>
            {can('agreements.view') && <TabsTrigger value="agreements">Agreements</TabsTrigger>}
            <TabsTrigger value="guarantors">Guarantors</TabsTrigger>
            <TabsTrigger value="notes">Notes & risk</TabsTrigger>
          </TabsList>
        </div>

        <TabsContent value="overview" className="mt-4 space-y-5">
          <div className="grid gap-5 lg:grid-cols-2">
            <Panel title="Personal information">
              <KeyValue items={[
                { label: 'Full name', value: b.full_name },
                { label: 'Date of birth', value: b.date_of_birth ? fmtDate(b.date_of_birth, 'long') : '—' },
                { label: 'Gender', value: titleCase(b.gender) || '—' },
                { label: 'Civil status', value: titleCase(b.civil_status) || '—' },
                { label: 'Mobile', value: phMobile(b.mobile_number) },
                { label: 'Email', value: b.email || '—' },
              ]} />
            </Panel>
            <Panel title="Residential address">
              <KeyValue items={[
                { label: 'Street', value: b.address_line || '—' },
                { label: 'Barangay', value: b.barangay || '—' },
                { label: 'City / municipality', value: b.city_municipality || '—' },
                { label: 'Province', value: b.province || '—' },
                { label: 'Region', value: b.region || '—' },
                { label: 'ZIP code', value: b.zip_code || '—' },
              ]} />
            </Panel>
            <Panel title="Employment & income">
              <KeyValue items={[
                { label: 'Occupation', value: b.occupation || '—' },
                { label: 'Employment type', value: b.employment_type || '—' },
                { label: 'Employer / business', value: b.employer_name || '—' },
                { label: 'Monthly income', value: b.monthly_income != null ? peso(b.monthly_income) : '—' },
              ]} />
            </Panel>
            <Panel title="Emergency contact">
              <KeyValue items={[
                { label: 'Name', value: b.emergency_contact_name || '—' },
                { label: 'Relationship', value: b.emergency_contact_relationship || '—' },
                { label: 'Mobile', value: b.emergency_contact_number ? phMobile(b.emergency_contact_number) : '—' },
              ]} />
            </Panel>
          </div>
          <Panel title="Identification" description="Restricted to authorized staff">
            {!can('borrowers.view_sensitive') ? (
              <p className="flex items-center gap-2 text-sm text-muted-foreground"><Lock className="size-4" /> You do not have access to identity documents.</p>
            ) : identity.isLoading ? <Spinner /> : identity.data ? (
              <div className="grid gap-5 lg:grid-cols-[1fr_1.4fr]">
                <KeyValue columns={1} items={[
                  { label: 'ID type', value: identity.data.id_type },
                  { label: 'ID number', value: <span className="font-mono">{identity.data.id_number}</span> },
                  { label: 'Expiry', value: identity.data.id_expiry ? fmtDate(identity.data.id_expiry) : '—' },
                  { label: 'Verification', value: identity.data.verified_at ? <Pill tone="green">Verified {fmtDate(identity.data.verified_at)}</Pill> : <Pill tone="amber">Not verified</Pill> },
                ]} />
                <div className="grid grid-cols-2 gap-3">
                  <IdImage path={identity.data.id_front_path} label="Front" />
                  <IdImage path={identity.data.id_back_path} label="Back" />
                </div>
              </div>
            ) : (
              <EmptyState className="py-6" title="No ID on file" description="Add a government-issued ID before releasing a loan."
                action={can('borrowers.edit') && <Button variant="outline" onClick={() => navigate(`/borrowers/${b.id}/edit`)}>Add ID</Button>} />
            )}
          </Panel>
        </TabsContent>

        {can('loans.view') && (
          <TabsContent value="loans" className="mt-4">
            <Panel title="Loan history" actions={can('loans.create') && b.status === 'active' && (
              <Button size="sm" onClick={() => navigate(`/loans/new?borrower=${b.id}`)}><PlusCircle /> New loan</Button>
            )}>
              <BorrowerLoansTable loans={loanRows} loading={loans.isLoading} onOpen={(l) => navigate(`/loans/${l.id}`)} />
            </Panel>
          </TabsContent>
        )}
        {can('payments.view') && <TabsContent value="payments" className="mt-4"><PaymentsTab borrowerId={b.id} /></TabsContent>}
        <TabsContent value="documents" className="mt-4"><DocumentsTab borrowerId={b.id} /></TabsContent>
        {can('agreements.view') && <TabsContent value="agreements" className="mt-4"><AgreementsTab borrowerId={b.id} /></TabsContent>}
        <TabsContent value="guarantors" className="mt-4"><GuarantorsTab borrowerId={b.id} /></TabsContent>
        <TabsContent value="notes" className="mt-4"><NotesTab borrowerId={b.id} assessment={b.assessment_notes} internal={b.internal_notes} risk={b.risk_rating} /></TabsContent>
      </Tabs>

      {statusAction && (
        <ConfirmDialog
          open={!!statusAction}
          onOpenChange={(o) => !o && setStatusAction(null)}
          title={statusCopy[statusAction].title}
          description={statusCopy[statusAction].desc}
          confirmLabel={statusCopy[statusAction].label}
          destructive={statusAction !== 'active'}
          onConfirm={async () => {
            await borrowersService.setStatus(b.id, statusAction)
            toast.success(`Borrower ${statusAction === 'active' ? 'reactivated' : statusAction}`)
            void qc.invalidateQueries({ queryKey: ['borrower', b.id] })
            void qc.invalidateQueries({ queryKey: ['borrowers'] })
          }}
        />
      )}
    </div>
  )
}

function BorrowerLoansTable({ loans, loading, onOpen }: { loans: LoanView[]; loading: boolean; onOpen: (l: LoanView) => void }) {
  const columns: Column<LoanView>[] = [
    { key: 'no', header: 'Loan #', mobile: 'title', cell: (l) => <span className="font-medium">{l.loan_number}</span> },
    { key: 'product', header: 'Product', mobile: 'subtitle', cell: (l) => <span className="text-sm">{l.product_name ?? titleCase(l.interest_method)} · {durationLabel(l.term_count, l.repayment_frequency)}</span> },
    { key: 'principal', header: 'Principal', align: 'right', mobile: 'value', cell: (l) => peso(l.principal) },
    { key: 'outstanding', header: 'Outstanding', align: 'right', mobile: 'meta', cell: (l) => ['active', 'defaulted'].includes(l.status) ? peso(l.total_outstanding) : '—' },
    { key: 'next', header: 'Next due', mobile: 'meta', cell: (l) => l.next_due_date ? fmtDate(l.next_due_date) : '—' },
    { key: 'overdue', header: 'Overdue', align: 'right', mobile: 'meta', cell: (l) => toNumber(l.overdue_amount) > 0 ? <span className="font-medium text-red-600">{peso(l.overdue_amount)}</span> : '—' },
    { key: 'status', header: 'Status', mobile: 'meta', cell: (l) => <LoanStatusBadge status={l.status} /> },
    { key: 'created', header: 'Created', mobile: false, cell: (l) => <span className="text-sm text-muted-foreground">{fmtDate(l.created_at)}</span> },
  ]
  return <DataTable columns={columns} rows={loans} loading={loading} rowKey={(l) => l.id} onRowClick={onOpen}
    empty={<EmptyState icon={<HandCoins />} title="No loans yet" description="Loans created for this borrower will appear here." />} />
}

function PaymentsTab({ borrowerId }: { borrowerId: string }) {
  const navigate = useNavigate()
  const q = useQuery({ queryKey: ['borrower', borrowerId, 'payments'], queryFn: () => borrowersService.payments(borrowerId) })
  type Row = NonNullable<typeof q.data>[number]
  const columns: Column<Row>[] = [
    { key: 'date', header: 'Date', mobile: 'subtitle', cell: (p) => fmtDate(p.payment_date) },
    { key: 'no', header: 'Payment #', mobile: 'title', cell: (p) => <span className="font-medium">{p.payment_number}</span> },
    { key: 'loan', header: 'Loan', mobile: 'meta', cell: (p) => p.loans?.loan_number ?? '—' },
    { key: 'method', header: 'Method', mobile: 'meta', cell: (p) => titleCase(p.method) },
    { key: 'breakdown', header: 'Principal / interest', mobile: false, cell: (p) => <span className="text-xs text-muted-foreground">{peso(p.principal_applied)} / {peso(p.interest_applied)}</span> },
    { key: 'amount', header: 'Amount', align: 'right', mobile: 'value', cell: (p) => <span className={p.status === 'reversed' ? 'text-muted-foreground line-through' : 'font-medium'}>{peso(p.amount)}</span> },
    { key: 'status', header: 'Status', mobile: 'meta', cell: (p) => p.status === 'reversed' ? <Pill tone="red">Reversed</Pill> : p.payment_type === 'settlement' ? <Pill tone="teal">Settlement</Pill> : <Pill tone="green">Posted</Pill> },
  ]
  if (q.error) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />
  const total = (q.data ?? []).filter((p) => p.status === 'posted').reduce((a, p) => a + toNumber(p.amount), 0)
  return (
    <Panel title="Payment history" description={q.data?.length ? `${peso(total)} collected across ${q.data.filter((p) => p.status === 'posted').length} payments` : undefined}>
      <DataTable columns={columns} rows={q.data} loading={q.isLoading} rowKey={(p) => p.id} onRowClick={(p) => navigate(`/payments/${p.id}`)}
        empty={<EmptyState icon={<Wallet />} title="No payments recorded" />} />
    </Panel>
  )
}

function DocumentsTab({ borrowerId }: { borrowerId: string }) {
  const { can, organization } = useAuth()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['borrower', borrowerId, 'documents'], queryFn: () => borrowersService.documents(borrowerId) })
  const [type, setType] = useState('proof_of_income')
  const [title, setTitle] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [toDelete, setToDelete] = useState<BorrowerDocument | null>(null)
  const upload = useMutation({
    mutationFn: async () => {
      if (!file || !organization) throw new Error('Choose a file to upload')
      if (file.size > 10 * 1024 * 1024) throw new Error('Files must be 10 MB or smaller')
      return borrowersService.addDocument(organization.id, borrowerId, file, type, title.trim() || undefined)
    },
    onSuccess: () => {
      toast.success('Document uploaded')
      setFile(null)
      setTitle('')
      void qc.invalidateQueries({ queryKey: ['borrower', borrowerId, 'documents'] })
    },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const canUpload = can('borrowers.edit') || can('borrowers.create')
  const typeLabel = (t: string) => DOCUMENT_TYPES.find((d) => d.value === t)?.label ?? titleCase(t)

  return (
    <div className="grid gap-5 lg:grid-cols-[1fr_340px]">
      <Panel title="Supporting documents">
        {q.error ? <ErrorState error={q.error} /> : q.isLoading ? <Spinner /> : !q.data?.length ? (
          <EmptyState icon={<FileText />} title="No documents" description="Upload payslips, proof of billing, business permits and other requirements." />
        ) : (
          <ul className="divide-y">
            {q.data.map((d) => (
              <li key={d.id} className="flex items-center gap-3 py-3">
                <span className="grid size-10 shrink-0 place-items-center rounded-lg bg-blue-50 text-blue-600"><FileText className="size-5" /></span>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{d.title || d.file_name}</p>
                  <p className="text-xs text-muted-foreground">{typeLabel(d.document_type)} · {d.file_size ? `${Math.max(1, Math.round(d.file_size / 1024))} KB · ` : ''}{fmtDate(d.created_at)}</p>
                </div>
                <Button size="icon" variant="ghost" aria-label="Open document" onClick={() => void openInNewTab(() => borrowersService.fileUrl(d.file_path))}><Eye /></Button>
                {can('borrowers.edit') && <Button size="icon" variant="ghost" aria-label="Delete document" onClick={() => setToDelete(d)}><Trash2 className="text-red-600" /></Button>}
              </li>
            ))}
          </ul>
        )}
      </Panel>
      {canUpload && (
        <Panel title="Upload document">
          <div className="space-y-3">
            <Field label="Document type">
              <NativeSelect value={type} onChange={(e) => setType(e.target.value)}>
                {DOCUMENT_TYPES.map((d) => <option key={d.value} value={d.value}>{d.label}</option>)}
              </NativeSelect>
            </Field>
            <Field label="Title" hint="Optional — defaults to the file name"><Input value={title} onChange={(e) => setTitle(e.target.value)} className="h-10" /></Field>
            <label className="flex cursor-pointer items-center gap-3 rounded-lg border-2 border-dashed p-3 text-sm hover:border-blue-300">
              <Upload className="size-5 text-muted-foreground" />
              <span className="min-w-0 truncate">{file ? file.name : 'Choose an image or PDF (max 10 MB)'}</span>
              <input type="file" accept="image/*,application/pdf" className="hidden" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            </label>
            <Button className="w-full" disabled={!file || upload.isPending} onClick={() => upload.mutate()}>{upload.isPending ? <Spinner /> : <Upload />} Upload</Button>
          </div>
        </Panel>
      )}
      <ConfirmDialog open={!!toDelete} onOpenChange={(o) => !o && setToDelete(null)} title="Delete document?" destructive confirmLabel="Delete"
        description={`"${toDelete?.title || toDelete?.file_name}" will be permanently removed.`}
        onConfirm={async () => {
          await borrowersService.deleteDocument(toDelete!)
          toast.success('Document deleted')
          void qc.invalidateQueries({ queryKey: ['borrower', borrowerId, 'documents'] })
        }} />
    </div>
  )
}

function AgreementsTab({ borrowerId }: { borrowerId: string }) {
  const navigate = useNavigate()
  const q = useQuery({ queryKey: ['borrower', borrowerId, 'agreements'], queryFn: () => borrowersService.agreements(borrowerId) })
  const columns: Column<BorrowerAgreement>[] = [
    { key: 'no', header: 'Agreement #', mobile: 'title', cell: (a) => <span className="font-medium">{a.agreement_number}</span> },
    { key: 'loan', header: 'Loan', mobile: 'subtitle', cell: (a) => <Link className="text-blue-700 hover:underline" to={`/loans/${a.loan_id}`} onClick={(e) => e.stopPropagation()}>{a.loans?.loan_number}</Link> },
    { key: 'ver', header: 'Version', mobile: 'meta', cell: (a) => `v${a.version}${a.kind === 'amendment' ? ' (amendment)' : ''}` },
    { key: 'gen', header: 'Generated', mobile: 'meta', cell: (a) => fmtDate(a.generated_at) },
    { key: 'signed', header: 'Fully signed', mobile: 'meta', cell: (a) => a.fully_signed_at ? fmtDateTime(a.fully_signed_at) : '—' },
    { key: 'status', header: 'Status', mobile: 'meta', cell: (a) => <AgreementStatusBadge status={a.status} /> },
    {
      key: 'open', header: '', align: 'right', mobile: 'value',
      cell: (a) => {
        const path = a.signed_pdf_path ?? a.current_pdf_path ?? a.unsigned_pdf_path
        return path ? (
          <Button size="sm" variant="outline" onClick={(e) => { e.stopPropagation(); void openInNewTab(() => agreementsService.pdfUrl(path)) }}>
            <Eye /> {a.signed_pdf_path ? 'Signed PDF' : 'PDF'}
          </Button>
        ) : null
      },
    },
  ]
  if (q.error) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />
  return (
    <Panel title="Loan agreements" description="Signed agreements are stored privately with a SHA-256 fingerprint.">
      <DataTable columns={columns} rows={q.data} loading={q.isLoading} rowKey={(a) => a.id} onRowClick={(a) => navigate(`/loans/${a.loan_id}`)}
        empty={<EmptyState icon={<FileSignature />} title="No agreements yet" />} />
    </Panel>
  )
}

const blankGuarantor: Partial<Guarantor> = { full_name: '', relationship: '', mobile_number: '', email: '', address: '', occupation: '', monthly_income: null, id_type: '', id_number: '', notes: '' }

function GuarantorsTab({ borrowerId }: { borrowerId: string }) {
  const { can } = useAuth()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['borrower', borrowerId, 'guarantors'], queryFn: () => borrowersService.guarantors(borrowerId) })
  const [editing, setEditing] = useState<Partial<Guarantor> | null>(null)
  const [toDelete, setToDelete] = useState<Guarantor | null>(null)
  const [busy, setBusy] = useState(false)
  const canEdit = can('borrowers.edit')

  const save = async () => {
    if (!editing?.full_name?.trim()) return toast.error('Guarantor name is required')
    const mobile = editing.mobile_number ? normalizePhMobile(editing.mobile_number) : null
    if (mobile && !/^\+639\d{9}$/.test(mobile)) return toast.error('Enter a valid PH mobile number')
    setBusy(true)
    try {
      const income = editing.monthly_income === '' || editing.monthly_income == null ? null : Number(String(editing.monthly_income).replace(/,/g, ''))
      if (income !== null && (Number.isNaN(income) || income < 0)) throw new Error('Enter a valid monthly income')
      await borrowersService.saveGuarantor(borrowerId, {
        ...editing, full_name: editing.full_name.trim(), mobile_number: mobile, monthly_income: income,
        relationship: editing.relationship || null, email: editing.email || null, address: editing.address || null,
        occupation: editing.occupation || null, id_type: editing.id_type || null, id_number: editing.id_number || null, notes: editing.notes || null,
      })
      toast.success('Guarantor saved')
      setEditing(null)
      void qc.invalidateQueries({ queryKey: ['borrower', borrowerId, 'guarantors'] })
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }
  const set = (k: keyof Guarantor) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
    setEditing((g) => ({ ...g, [k]: e.target.value }))

  return (
    <Panel title="Guarantors / co-makers" actions={canEdit && <Button size="sm" onClick={() => setEditing({ ...blankGuarantor })}><UserPlus /> Add guarantor</Button>}>
      {q.isLoading ? <Spinner /> : !q.data?.length ? (
        <EmptyState icon={<UserPlus />} title="No guarantors" description="Add a co-maker who agrees to answer for the loan if the borrower defaults." />
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {q.data.map((g) => (
            <div key={g.id} className="rounded-lg border p-4">
              <div className="flex items-start justify-between gap-2">
                <div>
                  <p className="font-medium">{g.full_name}</p>
                  <p className="text-xs text-muted-foreground">{g.relationship || 'Guarantor'}</p>
                </div>
                {canEdit && (
                  <div className="flex">
                    <Button size="icon" variant="ghost" aria-label="Edit guarantor" onClick={() => setEditing({ ...g, mobile_number: g.mobile_number ? phMobile(g.mobile_number) : '' })}><Pencil /></Button>
                    <Button size="icon" variant="ghost" aria-label="Delete guarantor" onClick={() => setToDelete(g)}><Trash2 className="text-red-600" /></Button>
                  </div>
                )}
              </div>
              <KeyValue className="mt-3" items={[
                { label: 'Mobile', value: g.mobile_number ? phMobile(g.mobile_number) : '—' },
                { label: 'Occupation', value: g.occupation || '—' },
                { label: 'Monthly income', value: g.monthly_income != null ? peso(g.monthly_income) : '—' },
                { label: 'ID', value: g.id_type ? `${g.id_type}${g.id_number ? ` · ${g.id_number}` : ''}` : '—' },
                { label: 'Address', value: g.address || '—' },
              ]} />
            </div>
          ))}
        </div>
      )}
      <Dialog open={!!editing} onOpenChange={(o) => !o && !busy && setEditing(null)}>
        <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-lg">
          <DialogHeader><DialogTitle>{editing?.id ? 'Edit guarantor' : 'Add guarantor'}</DialogTitle></DialogHeader>
          {editing && (
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Full name" required className="sm:col-span-2"><Input className="h-10" value={editing.full_name ?? ''} onChange={set('full_name')} /></Field>
              <Field label="Relationship">
                <NativeSelect value={editing.relationship ?? ''} onChange={set('relationship')}>
                  <option value="">Select</option>
                  {RELATIONSHIPS.map((r) => <option key={r} value={r}>{r}</option>)}
                </NativeSelect>
              </Field>
              <Field label="Mobile number"><Input className="h-10" inputMode="tel" value={editing.mobile_number ?? ''} onChange={set('mobile_number')} /></Field>
              <Field label="Email"><Input className="h-10" type="email" value={editing.email ?? ''} onChange={set('email')} /></Field>
              <Field label="Occupation"><Input className="h-10" value={editing.occupation ?? ''} onChange={set('occupation')} /></Field>
              <Field label="Monthly income (₱)"><Input className="h-10" inputMode="decimal" value={editing.monthly_income ?? ''} onChange={set('monthly_income')} /></Field>
              <Field label="Address"><Input className="h-10" value={editing.address ?? ''} onChange={set('address')} /></Field>
              <Field label="ID type"><Input className="h-10" value={editing.id_type ?? ''} onChange={set('id_type')} /></Field>
              <Field label="ID number"><Input className="h-10" value={editing.id_number ?? ''} onChange={set('id_number')} /></Field>
              <Field label="Notes" className="sm:col-span-2"><Textarea rows={2} value={editing.notes ?? ''} onChange={set('notes')} /></Field>
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditing(null)} disabled={busy}>Cancel</Button>
            <Button onClick={() => void save()} disabled={busy}>{busy && <Spinner />} Save</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <ConfirmDialog open={!!toDelete} onOpenChange={(o) => !o && setToDelete(null)} title="Remove guarantor?" destructive confirmLabel="Remove"
        description={`${toDelete?.full_name} will be removed from this borrower.`}
        onConfirm={async () => {
          await borrowersService.deleteGuarantor(toDelete!.id)
          toast.success('Guarantor removed')
          void qc.invalidateQueries({ queryKey: ['borrower', borrowerId, 'guarantors'] })
        }} />
    </Panel>
  )
}

const NOTE_TYPES: { value: string; label: string; icon: ReactNode }[] = [
  { value: 'note', label: 'Note', icon: <FileText /> },
  { value: 'call', label: 'Phone call', icon: <Phone /> },
  { value: 'visit', label: 'Field visit', icon: <MapPin /> },
  { value: 'promise_to_pay', label: 'Promise to pay', icon: <HandCoins /> },
]

function NotesTab({ borrowerId, assessment, internal, risk }: { borrowerId: string; assessment: string | null; internal: string | null; risk: string }) {
  const { can } = useAuth()
  const qc = useQueryClient()
  const navigate = useNavigate()
  const q = useQuery({ queryKey: ['borrower', borrowerId, 'notes'], queryFn: () => borrowersService.notes(borrowerId) })
  const [type, setType] = useState('note')
  const [body, setBody] = useState('')
  const [promiseDate, setPromiseDate] = useState('')
  const [promiseAmount, setPromiseAmount] = useState('')
  const add = useMutation({
    mutationFn: () => {
      if (!body.trim()) throw new Error('Write a note first')
      if (type === 'promise_to_pay' && !promiseDate) throw new Error('Enter the promised payment date')
      return loansService.addNote({
        borrower_id: borrowerId, body: body.trim(), note_type: type,
        promise_date: type === 'promise_to_pay' ? promiseDate : null,
        promise_amount: type === 'promise_to_pay' && promiseAmount ? Number(promiseAmount.replace(/,/g, '')) : null,
      })
    },
    onSuccess: () => {
      setBody(''); setPromiseDate(''); setPromiseAmount('')
      toast.success('Note added')
      void qc.invalidateQueries({ queryKey: ['borrower', borrowerId, 'notes'] })
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  return (
    <div className="grid gap-5 lg:grid-cols-[1fr_360px]">
      <Panel title="Activity & notes">
        <div className="mb-4 space-y-3 rounded-lg border bg-slate-50/60 p-3">
          <div className="flex flex-wrap gap-1.5">
            {NOTE_TYPES.map((t) => (
              <button key={t.value} type="button" onClick={() => setType(t.value)}
                className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium [&_svg]:size-3.5 ${type === t.value ? 'border-blue-600 bg-blue-600 text-white' : 'bg-white hover:bg-muted'}`}>
                {t.icon}{t.label}
              </button>
            ))}
          </div>
          <Textarea rows={3} value={body} onChange={(e) => setBody(e.target.value)} placeholder="What happened? e.g. Called borrower, will pay on Friday." className="bg-white" />
          {type === 'promise_to_pay' && (
            <div className="grid gap-2 sm:grid-cols-2">
              <Field label="Promised date"><Input type="date" className="h-10 bg-white" value={promiseDate} onChange={(e) => setPromiseDate(e.target.value)} /></Field>
              <Field label="Promised amount (₱)"><Input inputMode="decimal" className="h-10 bg-white" value={promiseAmount} onChange={(e) => setPromiseAmount(e.target.value)} /></Field>
            </div>
          )}
          <div className="flex justify-end"><Button size="sm" onClick={() => add.mutate()} disabled={add.isPending}>{add.isPending && <Spinner />} Add note</Button></div>
        </div>
        {q.isLoading ? <Spinner /> : !q.data?.length ? <EmptyState title="No activity yet" /> : (
          <ol className="relative space-y-4 border-l pl-5">
            {q.data.map((n) => (
              <li key={n.id} className="relative">
                <span className={`absolute -left-[27px] top-1 size-3 rounded-full ring-4 ring-white ${n.note_type === 'system' ? 'bg-slate-300' : n.note_type === 'promise_to_pay' ? 'bg-amber-500' : 'bg-blue-500'}`} />
                <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                  <span className="font-medium text-foreground">{n.note_type === 'system' ? 'System' : n.author?.full_name ?? 'Staff'}</span>
                  <span>{titleCase(n.note_type)}</span>
                  <span title={fmtDateTime(n.created_at)}>{timeAgo(n.created_at)}</span>
                </div>
                <p className="mt-1 whitespace-pre-wrap text-sm">{n.body}</p>
                {n.note_type === 'promise_to_pay' && (
                  <p className="mt-1 text-xs text-amber-800">Promised {n.promise_amount ? peso(n.promise_amount) : 'payment'} on {fmtDate(n.promise_date)}</p>
                )}
              </li>
            ))}
          </ol>
        )}
      </Panel>
      <div className="space-y-5">
        <Panel title="Risk assessment" actions={can('borrowers.view_all') && can('borrowers.edit') && (
          <Button size="sm" variant="outline" onClick={() => navigate(`/borrowers/${borrowerId}/edit`)}><Pencil /> Edit</Button>
        )}>
          <div className="mb-3"><RiskBadge risk={risk} /></div>
          <p className="whitespace-pre-wrap text-sm text-muted-foreground">{assessment || 'No assessment notes recorded.'}</p>
        </Panel>
        <Panel title="Internal notes">
          <p className="whitespace-pre-wrap text-sm text-muted-foreground">{internal || 'No internal notes.'}</p>
        </Panel>
      </div>
    </div>
  )
}
