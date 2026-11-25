import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Download, FileSignature, LayoutTemplate } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import {
  AgreementStatusBadge, DataTable, EmptyState, ErrorState, PageHeader, Pagination, Panel, Pill, SearchInput, useDebounced,
} from '@/components/common'
import { useAuth } from '@/features/auth/AuthProvider'
import { agreementsService, type AgreementListItem } from '@/services/agreements'
import { errorMessage } from '@/lib/errors'
import { openInNewTab } from '@/lib/open'
import { fmtDateTime } from '@/lib/format'

const TABS = [
  { value: 'all', label: 'All' },
  { value: 'pending_signatures', label: 'Awaiting signatures' },
  { value: 'partially_signed', label: 'Partially signed' },
  { value: 'fully_signed', label: 'Fully signed' },
  { value: 'voided', label: 'Voided' },
  { value: 'superseded', label: 'Superseded' },
]

export default function AgreementsPage() {
  const [status, setStatus] = useState('all')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const term = useDebounced(search)
  const navigate = useNavigate()
  const { can } = useAuth()
  const q = useQuery({
    queryKey: ['agreements', status, term, page],
    queryFn: () => agreementsService.list({ status, search: term, page }),
    placeholderData: keepPreviousData,
  })
  const download = async (a: AgreementListItem) => {
    const path = a.signed_pdf_path ?? a.current_pdf_path ?? a.unsigned_pdf_path
    if (!path) return
    try {
      await openInNewTab(() => agreementsService.pdfUrl(path, `${a.agreement_number}${a.signed_pdf_path ? '-signed' : ''}.pdf`))
    } catch (e) {
      toast.error(errorMessage(e))
    }
  }
  return (
    <div>
      <PageHeader title="Loan Agreements" description="Every generated agreement version with its signing status. Signed agreements are immutable."
        actions={can('templates.manage') && <Button variant="outline" asChild><Link to="/agreements/templates"><LayoutTemplate /> Agreement templates</Link></Button>} />
      <Panel bodyClassName="p-3 sm:p-4">
        <div className="mb-3 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div className="-mx-1 overflow-x-auto px-1">
            <Tabs value={status} onValueChange={(v) => { setStatus(v); setPage(0) }}>
              <TabsList className="h-9">{TABS.map((t) => <TabsTrigger key={t.value} value={t.value}>{t.label}</TabsTrigger>)}</TabsList>
            </Tabs>
          </div>
          <SearchInput value={search} onChange={(v) => { setSearch(v); setPage(0) }} placeholder="Agreement number…" className="lg:w-64" />
        </div>
        {q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : (
          <>
            <DataTable<AgreementListItem>
              rows={q.data?.rows}
              loading={q.isLoading}
              rowKey={(a) => a.id}
              onRowClick={(a) => navigate(`/loans/${a.loan_id}?tab=agreement`)}
              empty={<EmptyState icon={<FileSignature />} title="No agreements" description="Agreements are generated from a loan’s Agreement tab or at the end of the loan wizard." />}
              columns={[
                { key: 'no', header: 'Agreement', cell: (a) => <div><p className="font-medium">{a.agreement_number}</p><p className="text-xs text-muted-foreground">v{a.version}{a.kind === 'amendment' ? ' · amendment' : ''}</p></div>, mobile: 'title' },
                { key: 'b', header: 'Borrower', cell: (a) => a.loans?.borrowers?.full_name ?? '—', mobile: 'subtitle' },
                { key: 'l', header: 'Loan', cell: (a) => a.loans?.loan_number ?? '—', mobile: 'meta' },
                { key: 'sig', header: 'Signatures', cell: (a) => <Pill tone={a.current_signature_count >= a.required_signers.length ? 'green' : 'slate'} dot={false}>{a.current_signature_count}/{a.required_signers.length}</Pill>, mobile: 'meta' },
                { key: 'gen', header: 'Generated', cell: (a) => <span className="text-xs">{fmtDateTime(a.generated_at)}</span> },
                { key: 'signed', header: 'Fully signed', cell: (a) => a.fully_signed_at ? <span className="text-xs">{fmtDateTime(a.fully_signed_at)}</span> : <span className="text-muted-foreground">—</span> },
                { key: 's', header: 'Status', cell: (a) => <AgreementStatusBadge status={a.status} />, mobile: 'value' },
                { key: 'dl', header: '', align: 'right', cell: (a) => (a.signed_pdf_path || a.current_pdf_path) ? (
                  <Button size="sm" variant="ghost" onClick={(e) => { e.stopPropagation(); void download(a) }}><Download /> PDF</Button>
                ) : null },
              ]}
            />
            <Pagination page={page} pageSize={25} count={q.data?.count ?? 0} onPage={setPage} />
          </>
        )}
      </Panel>
    </div>
  )
}
