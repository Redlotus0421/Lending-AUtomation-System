import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { AlertTriangle, CalendarClock, CalendarDays, CalendarRange, Landmark, Plus, Wallet } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DataTable, EmptyState, ErrorState, NativeSelect, PageHeader, Pagination, Panel, Pill, SearchInput, StatCard,
  useDebounced, type Column,
} from '@/components/common'
import { Skeleton } from '@/components/ui/skeleton'
import { useAuth } from '@/features/auth/AuthProvider'
import { paymentsService, type PaymentListItem } from '@/services/payments'
import { usersService } from '@/services/org'
import { PAYMENT_METHODS, SYSTEM_PAYMENT_METHODS } from '@/lib/constants'
import { fmtDate, peso, titleCase, toNumber } from '@/lib/format'
import { exportCsv, exportXlsx, type ExportColumn } from '@/lib/export'
import { DateRangeFilter, ExportMenu, makeRange, type DateRange } from '@/features/reports/shared'
import { RecordPaymentDialog } from './RecordPaymentDialog'

const PAGE_SIZE = 25

function receiptOf(p: PaymentListItem) {
  const r = p.payment_receipts
  return Array.isArray(r) ? r[0] : r
}

const EXPORT_COLUMNS: ExportColumn[] = [
  { key: 'payment_date', label: 'Date', type: 'date' },
  { key: 'payment_number', label: 'Payment #' },
  { key: 'receipt_number', label: 'Receipt #' },
  { key: 'loan_number', label: 'Loan #' },
  { key: 'borrower_name', label: 'Borrower' },
  { key: 'method', label: 'Method' },
  { key: 'reference_no', label: 'Reference' },
  { key: 'principal_applied', label: 'Principal', type: 'money', total: true },
  { key: 'interest_applied', label: 'Interest', type: 'money', total: true },
  { key: 'charges_applied', label: 'Charges', type: 'money', total: true },
  { key: 'penalty_applied', label: 'Penalties', type: 'money', total: true },
  { key: 'amount', label: 'Amount', type: 'money', total: true },
  { key: 'status', label: 'Status' },
  { key: 'received_by', label: 'Received by' },
]

function exportRows(rows: PaymentListItem[]) {
  return rows.map((p) => ({
    payment_date: p.payment_date,
    payment_number: p.payment_number,
    receipt_number: receiptOf(p)?.receipt_number ?? '',
    loan_number: p.loans?.loan_number ?? '',
    borrower_name: p.borrowers?.full_name ?? '',
    method: titleCase(p.method),
    reference_no: p.reference_no ?? '',
    principal_applied: p.status === 'posted' ? toNumber(p.principal_applied) : 0,
    interest_applied: p.status === 'posted' ? toNumber(p.interest_applied) : 0,
    charges_applied: p.status === 'posted' ? toNumber(p.charges_applied) : 0,
    penalty_applied: p.status === 'posted' ? toNumber(p.penalty_applied) : 0,
    amount: p.status === 'posted' ? toNumber(p.amount) : 0,
    status: p.status === 'reversed' ? 'Reversed' : 'Posted',
    received_by: p.receiver?.full_name ?? '',
  }))
}

export default function PaymentsPage() {
  const navigate = useNavigate()
  const { can } = useAuth()
  const [recordOpen, setRecordOpen] = useState(false)
  const [range, setRange] = useState<DateRange>(() => makeRange('this_month'))
  const [method, setMethod] = useState('all')
  const [status, setStatus] = useState('all')
  const [collector, setCollector] = useState('')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const term = useDebounced(search)

  const summary = useQuery({ queryKey: ['collections', 'summary'], queryFn: paymentsService.collectionsSummary })
  const staff = useQuery({ queryKey: ['staff'], queryFn: usersService.list, enabled: can('collections.assign') })
  const filters = { from: range.from, to: range.to, method, status, search: term, collectorId: collector || undefined }
  const list = useQuery({
    queryKey: ['payments', 'list', filters, page],
    queryFn: () => paymentsService.list({ ...filters, page, pageSize: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  })

  const resetPage = <T,>(fn: (v: T) => void) => (v: T) => { setPage(0); fn(v) }

  const fetchAll = async () => (await paymentsService.list({ ...filters, page: 0, pageSize: 5000 })).rows
  const fileBase = `payments-${range.from}-to-${range.to}`
  const s = summary.data

  const columns: Column<PaymentListItem>[] = [
    { key: 'date', header: 'Date', cell: (p) => fmtDate(p.payment_date), mobile: 'meta' },
    {
      key: 'number', header: 'Payment / receipt', mobile: 'subtitle',
      cell: (p) => (
        <span>
          <span className="font-medium">{p.payment_number}</span>
          {receiptOf(p) && <span className="text-muted-foreground"> · {receiptOf(p)!.receipt_number}</span>}
        </span>
      ),
    },
    {
      key: 'borrower', header: 'Borrower', mobile: 'title',
      cell: (p) => (
        <div className="min-w-0">
          <p className="truncate font-medium">{p.borrowers?.full_name}</p>
          <p className="truncate text-xs text-muted-foreground md:block">{p.loans?.loan_number}</p>
        </div>
      ),
    },
    { key: 'method', header: 'Method', cell: (p) => <span>{titleCase(p.method)}{p.reference_no && <span className="block text-xs text-muted-foreground">{p.reference_no}</span>}</span>, mobile: 'meta' },
    { key: 'type', header: 'Type', cell: (p) => (p.payment_type === 'settlement' ? <Pill tone="teal">Settlement</Pill> : <span className="text-muted-foreground">Regular</span>), mobile: false },
    { key: 'by', header: 'Received by', cell: (p) => p.receiver?.full_name ?? '—', mobile: false, className: 'hidden xl:table-cell' },
    { key: 'status', header: 'Status', cell: (p) => (p.status === 'reversed' ? <Pill tone="red">Reversed</Pill> : <Pill tone="green">Posted</Pill>), mobile: 'meta' },
    {
      key: 'amount', header: 'Amount', align: 'right', mobile: 'value',
      cell: (p) => <span className={p.status === 'reversed' ? 'text-muted-foreground line-through' : 'font-semibold'}>{peso(p.amount)}</span>,
    },
  ]

  const visible = list.data?.rows ?? []
  const visibleTotal = visible.filter((p) => p.status === 'posted').reduce((a, p) => a + toNumber(p.amount), 0)

  return (
    <div className="space-y-5">
      <PageHeader
        title="Payments & Collections"
        description="Every confirmed payment is permanent: corrections are made through authorized reversals."
        actions={can('payments.record') && (
          <Button className="h-10" onClick={() => setRecordOpen(true)}><Plus /> Record payment</Button>
        )}
      />

      <section className="grid grid-cols-2 gap-3 md:grid-cols-4 xl:grid-cols-7">
        {summary.isLoading ? Array.from({ length: 7 }).map((_, i) => <Skeleton key={i} className="h-28 rounded-xl" />) : s ? (
          <>
            <StatCard label="Collected today" value={peso(s.collected_today)} hint={`${s.count_today} payment${s.count_today === 1 ? '' : 's'}`} icon={<Wallet />} tone="green" />
            <StatCard label="This week" value={peso(s.collected_week)} icon={<CalendarDays />} tone="teal" />
            <StatCard label="This month" value={peso(s.collected_month)} icon={<CalendarRange />} tone="blue" />
            <StatCard label="Due today" value={peso(s.due_today)} icon={<CalendarClock />} tone="amber" onClick={() => navigate('/due')} />
            <StatCard label="Overdue" value={peso(s.overdue)} icon={<AlertTriangle />} tone="red" onClick={() => navigate('/overdue')} />
            <StatCard label="Next 7 days" value={peso(s.upcoming_7d)} icon={<CalendarDays />} tone="navy" onClick={() => navigate('/due')} />
            <StatCard label="Receivables" value={peso(s.receivables)} hint="Total still owed" icon={<Landmark />} tone="navy" className="col-span-2 md:col-span-1" />
          </>
        ) : summary.error ? <div className="col-span-full"><ErrorState error={summary.error} onRetry={() => void summary.refetch()} /></div> : null}
      </section>

      <Panel bodyClassName="p-3 sm:p-4">
        <div className="mb-4 flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-wrap items-center gap-2">
            <SearchInput value={search} onChange={resetPage(setSearch)} placeholder="Payment #, reference…" className="w-full sm:w-64" />
            <DateRangeFilter value={range} onChange={resetPage(setRange)} presets={['today', 'this_week', 'this_month', 'last_month', 'last_3', 'ytd', 'custom']} />
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <NativeSelect value={method} onChange={(e) => resetPage(setMethod)(e.target.value)} className="h-10 w-auto" aria-label="Method">
              <option value="all">All methods</option>
              {PAYMENT_METHODS.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
              {Object.entries(SYSTEM_PAYMENT_METHODS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </NativeSelect>
            <NativeSelect value={status} onChange={(e) => resetPage(setStatus)(e.target.value)} className="h-10 w-auto" aria-label="Status">
              <option value="all">Posted & reversed</option>
              <option value="posted">Posted</option>
              <option value="reversed">Reversed</option>
            </NativeSelect>
            {can('collections.assign') && (
              <NativeSelect value={collector} onChange={(e) => resetPage(setCollector)(e.target.value)} className="h-10 w-auto" aria-label="Collector">
                <option value="">All collectors</option>
                {(staff.data ?? []).filter((u) => u.is_active).map((u) => <option key={u.id} value={u.id}>{u.full_name}</option>)}
              </NativeSelect>
            )}
            <ExportMenu
              disabled={!list.data?.count}
              onCsv={async () => exportCsv(fileBase, EXPORT_COLUMNS, exportRows(await fetchAll()))}
              onXlsx={async () => exportXlsx(fileBase, 'Payments', EXPORT_COLUMNS, exportRows(await fetchAll()))}
            />
          </div>
        </div>

        {list.error ? (
          <ErrorState error={list.error} onRetry={() => void list.refetch()} />
        ) : (
          <>
            <DataTable
              columns={columns}
              rows={list.data?.rows}
              loading={list.isLoading}
              rowKey={(p) => p.id}
              onRowClick={(p) => navigate(`/payments/${p.id}`)}
              empty={
                <EmptyState
                  icon={<Wallet />}
                  title="No payments in this view"
                  description="Try another date range or filter."
                  action={can('payments.record') ? <Button onClick={() => setRecordOpen(true)}><Plus /> Record payment</Button> : undefined}
                />
              }
            />
            {visible.length > 0 && (
              <div className="mt-3 flex items-center justify-between rounded-lg bg-slate-50 px-3 py-2 text-sm">
                <span className="text-muted-foreground">Posted on this page</span>
                <span className="tabular font-semibold">{peso(visibleTotal)}</span>
              </div>
            )}
            <Pagination page={page} pageSize={PAGE_SIZE} count={list.data?.count ?? 0} onPage={setPage} />
          </>
        )}
      </Panel>

      <RecordPaymentDialog open={recordOpen} onOpenChange={setRecordOpen} />
    </div>
  )
}
