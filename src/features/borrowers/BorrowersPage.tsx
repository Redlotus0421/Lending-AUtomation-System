import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Phone, UserPlus, Users } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DataTable, EmptyState, ErrorState, NativeSelect, PageHeader, Pagination, Panel, SearchInput, useDebounced, type Column,
} from '@/components/common'
import { useAuth } from '@/features/auth/AuthProvider'
import { borrowersService, type BorrowerListItem } from '@/services/borrowers'
import { fmtDate, peso, phMobile, toNumber } from '@/lib/format'
import { BorrowerAvatar, BorrowerStatusBadge, RiskBadge } from './shared'

const PAGE_SIZE = 25

function loanTotals(b: BorrowerListItem) {
  const loans = b.loans ?? []
  const open = loans.filter((l) => ['active', 'defaulted'].includes(l.status))
  const pipeline = loans.filter((l) => !['active', 'defaulted', 'completed', 'rejected', 'cancelled'].includes(l.status))
  return {
    open: open.length,
    pipeline: pipeline.length,
    total: loans.length,
    outstanding: open.reduce((a, l) => a + toNumber(l.total_outstanding), 0),
  }
}

export default function BorrowersPage() {
  const { can } = useAuth()
  const navigate = useNavigate()
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('')
  const [risk, setRisk] = useState('all')
  const [page, setPage] = useState(0)
  const debounced = useDebounced(search)

  const q = useQuery({
    queryKey: ['borrowers', { search: debounced, status, risk, page }],
    queryFn: () => borrowersService.list({ search: debounced, status, risk, page, pageSize: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  })

  const columns: Column<BorrowerListItem>[] = [
    {
      key: 'name', header: 'Borrower', mobile: 'title',
      cell: (b) => (
        <div className="flex items-center gap-3">
          <BorrowerAvatar borrower={b} size={36} className="hidden md:grid" />
          <div className="min-w-0">
            <p className="truncate font-medium text-foreground">{b.full_name}</p>
            <p className="text-xs text-muted-foreground">{b.borrower_code}</p>
          </div>
        </div>
      ),
    },
    {
      key: 'contact', header: 'Mobile', mobile: 'subtitle',
      cell: (b) => <span className="inline-flex items-center gap-1.5 text-sm"><Phone className="size-3.5 text-muted-foreground md:hidden" />{phMobile(b.mobile_number)}</span>,
    },
    { key: 'city', header: 'City', mobile: 'meta', cell: (b) => <span className="text-sm text-muted-foreground">{b.city_municipality || '—'}</span> },
    {
      key: 'loans', header: 'Loans', align: 'center', mobile: 'meta',
      cell: (b) => {
        const t = loanTotals(b)
        return (
          <span className="text-sm">
            {t.open} active{t.pipeline ? <span className="text-muted-foreground"> · {t.pipeline} in process</span> : null}
          </span>
        )
      },
    },
    {
      key: 'outstanding', header: 'Outstanding', align: 'right', mobile: 'value',
      cell: (b) => {
        const t = loanTotals(b)
        return t.outstanding > 0 ? <span className="font-medium">{peso(t.outstanding)}</span> : <span className="text-muted-foreground">—</span>
      },
    },
    { key: 'risk', header: 'Risk', mobile: 'meta', cell: (b) => <RiskBadge risk={b.risk_rating} /> },
    { key: 'status', header: 'Status', mobile: 'meta', cell: (b) => <BorrowerStatusBadge status={b.status} /> },
    { key: 'created', header: 'Registered', mobile: false, cell: (b) => <span className="text-sm text-muted-foreground">{fmtDate(b.created_at)}</span> },
  ]

  return (
    <div>
      <PageHeader
        title="Borrowers"
        description="Register borrowers, verify identities and review their loan history."
        actions={can('borrowers.create') && (
          <Button asChild><Link to="/borrowers/new"><UserPlus /> Register borrower</Link></Button>
        )}
      />
      <Panel bodyClassName="p-3 sm:p-4">
        <div className="mb-4 grid gap-2 sm:grid-cols-[1fr_180px_180px]">
          <SearchInput value={search} onChange={(v) => { setSearch(v); setPage(0) }} placeholder="Search name, code, mobile or email" />
          <NativeSelect value={status} onChange={(e) => { setStatus(e.target.value); setPage(0) }} aria-label="Status">
            <option value="">Active & blacklisted</option>
            <option value="active">Active</option>
            <option value="blacklisted">Blacklisted</option>
            <option value="archived">Archived</option>
            <option value="all">All statuses</option>
          </NativeSelect>
          <NativeSelect value={risk} onChange={(e) => { setRisk(e.target.value); setPage(0) }} aria-label="Risk rating">
            <option value="all">All risk ratings</option>
            <option value="low">Low risk</option>
            <option value="medium">Medium risk</option>
            <option value="high">High risk</option>
            <option value="unrated">Unrated</option>
          </NativeSelect>
        </div>
        {q.error ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : (
          <>
            <DataTable
              columns={columns}
              rows={q.data?.rows}
              loading={q.isLoading}
              rowKey={(b) => b.id}
              onRowClick={(b) => navigate(`/borrowers/${b.id}`)}
              empty={
                <EmptyState
                  icon={<Users />}
                  title={debounced || status || risk !== 'all' ? 'No borrowers match your filters' : 'No borrowers yet'}
                  description={debounced ? 'Try a different name, code or mobile number.' : 'Register your first borrower to start creating loans.'}
                  action={can('borrowers.create') && !debounced && (
                    <Button asChild><Link to="/borrowers/new"><UserPlus /> Register borrower</Link></Button>
                  )}
                />
              }
            />
            <Pagination page={page} pageSize={PAGE_SIZE} count={q.data?.count ?? 0} onPage={setPage} />
          </>
        )}
      </Panel>
    </div>
  )
}
