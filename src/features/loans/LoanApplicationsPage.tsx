import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { ClipboardList, PlusCircle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import {
  DataTable, EmptyState, ErrorState, LoanStatusBadge, PageHeader, Pagination, Panel, SearchInput, StatCard, useDebounced,
} from '@/components/common'
import { useAuth } from '@/features/auth/AuthProvider'
import { LOAN_PIPELINE, loansService } from '@/services/loans'
import { fmtDate, peso, timeAgo } from '@/lib/format'
import type { LoanStatus, LoanView } from '@/types/db'

const TABS: { value: string; label: string; statuses: LoanStatus[] }[] = [
  { value: 'pipeline', label: 'In progress', statuses: LOAN_PIPELINE },
  { value: 'review', label: 'Pending review', statuses: ['pending_review'] },
  { value: 'approved', label: 'Approved', statuses: ['approved'] },
  { value: 'signing', label: 'Awaiting signature', statuses: ['awaiting_borrower_signature', 'awaiting_lender_signature'] },
  { value: 'release', label: 'Ready for release', statuses: ['fully_signed', 'ready_for_release'] },
  { value: 'draft', label: 'Drafts', statuses: ['draft'] },
  { value: 'closed', label: 'Rejected / cancelled', statuses: ['rejected', 'cancelled'] },
]

export default function LoanApplicationsPage() {
  const [params, setParams] = useSearchParams()
  const tab = TABS.find((t) => t.value === params.get('tab')) ?? TABS[0]
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const term = useDebounced(search)
  const navigate = useNavigate()
  const { can } = useAuth()
  const q = useQuery({
    queryKey: ['loans', 'applications', tab.value, term, page],
    queryFn: () => loansService.list({ statuses: tab.statuses, search: term, page }),
    placeholderData: keepPreviousData,
  })
  const counts = useQuery({
    queryKey: ['loans', 'applications', 'counts'],
    queryFn: async () => {
      const r = await loansService.list({ statuses: LOAN_PIPELINE, pageSize: 500 })
      const c: Record<string, number> = {}
      r.rows.forEach((l) => (c[l.status] = (c[l.status] ?? 0) + 1))
      return c
    },
  })
  const c = counts.data ?? {}

  return (
    <div>
      <PageHeader title="Loan Applications" description="Applications move through review, approval and signing before funds are released."
        actions={can('loans.create') && <Button asChild><Link to="/loans/new"><PlusCircle /> New loan</Link></Button>} />
      <div className="mb-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard label="Pending review" value={c.pending_review ?? 0} tone="amber" onClick={() => setParams({ tab: 'review' })} />
        <StatCard label="Approved" value={c.approved ?? 0} tone="blue" onClick={() => setParams({ tab: 'approved' })} />
        <StatCard label="Awaiting signature" value={(c.awaiting_borrower_signature ?? 0) + (c.awaiting_lender_signature ?? 0)} tone="navy" onClick={() => setParams({ tab: 'signing' })} />
        <StatCard label="Ready for release" value={(c.ready_for_release ?? 0) + (c.fully_signed ?? 0)} tone="teal" onClick={() => setParams({ tab: 'release' })} />
      </div>
      <Panel bodyClassName="p-3 sm:p-4">
        <div className="mb-3 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div className="-mx-1 overflow-x-auto px-1">
            <Tabs value={tab.value} onValueChange={(v) => { setParams({ tab: v }); setPage(0) }}>
              <TabsList className="h-9">{TABS.map((t) => <TabsTrigger key={t.value} value={t.value}>{t.label}</TabsTrigger>)}</TabsList>
            </Tabs>
          </div>
          <SearchInput value={search} onChange={(v) => { setSearch(v); setPage(0) }} placeholder="Search loan #, borrower…" className="lg:w-72" />
        </div>
        {q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : (
          <>
            <DataTable<LoanView>
              rows={q.data?.rows}
              loading={q.isLoading}
              rowKey={(l) => l.id}
              onRowClick={(l) => navigate(`/loans/${l.id}`)}
              empty={<EmptyState icon={<ClipboardList />} title="No applications here" description="Create a loan to start a new application." />}
              columns={[
                { key: 'no', header: 'Loan', cell: (l) => <span className="font-medium">{l.loan_number}</span>, mobile: 'title' },
                { key: 'b', header: 'Borrower', cell: (l) => l.borrower_name, mobile: 'subtitle' },
                { key: 'amt', header: 'Amount', align: 'right', cell: (l) => <span className="font-semibold">{peso(l.principal)}</span>, mobile: 'value' },
                { key: 'terms', header: 'Terms', cell: (l) => <span className="text-xs text-muted-foreground">{l.calc_summary?.duration_label} · {l.interest_method} · {peso(l.installment_amount)}/{l.repayment_frequency.replace('ly', '')}</span>, mobile: 'meta' },
                { key: 'officer', header: 'Officer', cell: (l) => l.loan_officer_name ?? '—' },
                { key: 'approved', header: 'Approved', cell: (l) => l.approved_at ? fmtDate(l.approved_at) : <span className="text-muted-foreground">—</span> },
                { key: 'created', header: 'Updated', cell: (l) => <span className="text-xs text-muted-foreground">{timeAgo(l.updated_at)}</span>, mobile: 'meta' },
                { key: 's', header: 'Status', cell: (l) => <LoanStatusBadge status={l.status} />, mobile: 'meta' },
              ]}
            />
            <Pagination page={page} pageSize={25} count={q.data?.count ?? 0} onPage={setPage} />
          </>
        )}
      </Panel>
    </div>
  )
}
