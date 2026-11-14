import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { HandCoins, PlusCircle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import {
  DataTable, EmptyState, ErrorState, LoanStatusBadge, PageHeader, Pagination, Panel, SearchInput, useDebounced,
} from '@/components/common'
import { useAuth } from '@/features/auth/AuthProvider'
import { loansService } from '@/services/loans'
import { fmtDate, peso, toNumber } from '@/lib/format'
import type { LoanStatus, LoanView } from '@/types/db'
import { useNewLoan } from './NewLoanChooser'

const TABS: { value: string; label: string; statuses: LoanStatus[]; overdue?: boolean; order?: string }[] = [
  { value: 'active', label: 'Active', statuses: ['active'], order: 'next_due' },
  { value: 'overdue', label: 'Overdue', statuses: ['active', 'defaulted'], overdue: true, order: 'overdue' },
  { value: 'completed', label: 'Completed', statuses: ['completed'] },
  { value: 'defaulted', label: 'Defaulted', statuses: ['defaulted'] },
  { value: 'all', label: 'All released', statuses: ['active', 'completed', 'defaulted'] },
]

export default function LoansPage() {
  const [params, setParams] = useSearchParams()
  const tab = TABS.find((t) => t.value === params.get('tab')) ?? TABS[0]
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const term = useDebounced(search)
  const { can } = useAuth()
  const newLoan = useNewLoan()
  const navigate = useNavigate()
  const q = useQuery({
    queryKey: ['loans', 'list', tab.value, term, page],
    queryFn: () => loansService.list({ statuses: tab.statuses, overdueOnly: tab.overdue, search: term, page, order: tab.order }),
    placeholderData: keepPreviousData,
  })
  const totals = (q.data?.rows ?? []).reduce((a, l) => ({ out: a.out + toNumber(l.total_outstanding), od: a.od + toNumber(l.overdue_amount) }), { out: 0, od: 0 })

  return (
    <div>
      <PageHeader title="Active Loans" description="Released loans with live balances, next due dates and overdue status."
        actions={can('loans.create') && <Button onClick={newLoan.open}><PlusCircle /> New loan</Button>} />
      <Panel bodyClassName="p-3 sm:p-4">
        <div className="mb-3 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <Tabs value={tab.value} onValueChange={(v) => { setParams({ tab: v }); setPage(0) }}>
            <TabsList className="h-9">{TABS.map((t) => <TabsTrigger key={t.value} value={t.value}>{t.label}</TabsTrigger>)}</TabsList>
          </Tabs>
          <SearchInput value={search} onChange={(v) => { setSearch(v); setPage(0) }} placeholder="Search loan #, borrower…" className="sm:w-72" />
        </div>
        {q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : (
          <>
            <DataTable<LoanView>
              rows={q.data?.rows}
              loading={q.isLoading}
              rowKey={(l) => l.id}
              onRowClick={(l) => navigate(`/loans/${l.id}`)}
              empty={<EmptyState icon={<HandCoins />} title="No loans in this view" description="Released loans appear here once funds are disbursed." />}
              columns={[
                { key: 'no', header: 'Loan', cell: (l) => <div><p className="font-medium">{l.loan_number}</p><p className="text-xs text-muted-foreground">{l.product_name ?? 'Custom'}</p></div>, mobile: 'title' },
                { key: 'b', header: 'Borrower', cell: (l) => <div><p>{l.borrower_name}</p><p className="text-xs text-muted-foreground">{l.borrower_code}</p></div>, mobile: 'subtitle' },
                { key: 'p', header: 'Principal', align: 'right', cell: (l) => peso(l.principal) },
                { key: 'o', header: 'Outstanding', align: 'right', cell: (l) => <span className="font-semibold">{peso(l.total_outstanding)}</span>, mobile: 'value' },
                { key: 'n', header: 'Next due', cell: (l) => l.next_due_date ? <div><p>{fmtDate(l.next_due_date)}</p><p className="text-xs text-muted-foreground">{peso(l.next_due_amount)}</p></div> : '—', mobile: 'meta' },
                { key: 'od', header: 'Overdue', align: 'right', cell: (l) => toNumber(l.overdue_amount) > 0 ? <span className="text-red-600">{peso(l.overdue_amount)}<span className="block text-xs">{l.days_overdue} days</span></span> : <span className="text-muted-foreground">—</span>, mobile: 'meta' },
                { key: 'prog', header: 'Progress', cell: (l) => (
                  <div className="w-24">
                    <div className="h-1.5 overflow-hidden rounded-full bg-slate-100"><div className="h-full rounded-full bg-teal" style={{ width: `${l.total_installments ? (l.paid_installments / l.total_installments) * 100 : 0}%` }} /></div>
                    <p className="mt-1 text-[11px] text-muted-foreground">{l.paid_installments}/{l.total_installments} paid</p>
                  </div>
                ) },
                { key: 'c', header: 'Collector', cell: (l) => l.collector_name ?? <span className="text-muted-foreground">—</span> },
                { key: 's', header: 'Status', cell: (l) => <LoanStatusBadge status={l.status} />, mobile: 'meta' },
              ]}
            />
            {!!q.data?.rows.length && (
              <p className="mt-3 text-right text-xs text-muted-foreground">Page totals — outstanding <b className="text-foreground">{peso(totals.out)}</b>{totals.od > 0 && <> · overdue <b className="text-red-600">{peso(totals.od)}</b></>}</p>
            )}
            <Pagination page={page} pageSize={25} count={q.data?.count ?? 0} onPage={setPage} />
          </>
        )}
      </Panel>
    </div>
  )
}
