import { useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { CalendarCheck, CalendarClock, CalendarDays, CircleDollarSign, MessageSquareText, Phone, Wallet } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DataTable, EmptyState, ErrorState, InstallmentStatusBadge, NativeSelect, PageHeader, Pagination, Panel, SearchInput,
  useDebounced, type Column,
} from '@/components/common'
import { useAuth } from '@/features/auth/AuthProvider'
import { paymentsService } from '@/services/payments'
import { usersService } from '@/services/org'
import { fmtDate, peso, phMobile, todayManila, toNumber } from '@/lib/format'
import { exportCsv, type ExportColumn } from '@/lib/export'
import { cn } from '@/lib/utils'
import type { InstallmentView } from '@/types/db'
import { DateRangeFilter, ExportMenu, makeRange, smsReminderLink, telLink, type DateRange } from '@/features/reports/shared'
import { RecordPaymentDialog } from './RecordPaymentDialog'

type View = 'due_today' | 'upcoming' | 'partially_paid' | 'paid'
const PAGE_SIZE = 50

const TABS: { key: View; label: string; icon: ReactNode }[] = [
  { key: 'due_today', label: 'Due today', icon: <CalendarClock /> },
  { key: 'upcoming', label: 'Upcoming', icon: <CalendarDays /> },
  { key: 'partially_paid', label: 'Partially paid', icon: <CircleDollarSign /> },
  { key: 'paid', label: 'Completed', icon: <CalendarCheck /> },
]

const EXPORT_COLUMNS: ExportColumn[] = [
  { key: 'borrower_name', label: 'Borrower' },
  { key: 'mobile_number', label: 'Mobile' },
  { key: 'loan_number', label: 'Loan #' },
  { key: 'installment_no', label: 'Installment', type: 'number' },
  { key: 'due_date', label: 'Due date', type: 'date' },
  { key: 'scheduled_amount', label: 'Scheduled', type: 'money', total: true },
  { key: 'amount_paid', label: 'Paid', type: 'money', total: true },
  { key: 'amount_remaining', label: 'Remaining', type: 'money', total: true },
  { key: 'display_status', label: 'Status', type: 'status' },
  { key: 'paid_at', label: 'Paid on', type: 'date' },
  { key: 'collector_name', label: 'Collector' },
]

export default function DuePaymentsPage() {
  const { can, organization } = useAuth()
  const today = todayManila()
  const [view, setView] = useState<View>('due_today')
  const [days, setDays] = useState(14)
  const [range, setRange] = useState<DateRange>(() => makeRange('this_month'))
  const [search, setSearch] = useState('')
  const [collector, setCollector] = useState('')
  const [page, setPage] = useState(0)
  const [collect, setCollect] = useState<{ loanId: string; installmentId: string } | null>(null)
  const term = useDebounced(search)

  const staff = useQuery({ queryKey: ['staff'], queryFn: usersService.list, enabled: can('collections.assign') })
  const filters = {
    view, today, days, search: term, collectorId: collector || undefined,
    from: view === 'paid' ? range.from : undefined, to: view === 'paid' ? range.to : undefined,
  }
  const q = useQuery({
    queryKey: ['installments', 'due', filters, page],
    queryFn: () => paymentsService.installments({ ...filters, page, pageSize: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  })
  const rows = q.data?.rows ?? []
  const totalRemaining = rows.reduce((a, r) => a + toNumber(r.amount_remaining), 0)
  const totalPaid = rows.reduce((a, r) => a + toNumber(r.amount_paid), 0)
  const lender = organization?.name

  const switchView = (v: View) => {
    setView(v)
    setPage(0)
  }

  const columns: Column<InstallmentView>[] = [
    {
      key: 'borrower', header: 'Borrower', mobile: 'title',
      cell: (r) => (
        <div className="min-w-0">
          <Link to={`/borrowers/${r.borrower_id}`} className="font-medium hover:text-blue-600" onClick={(e) => e.stopPropagation()}>{r.borrower_name}</Link>
          <p className="text-xs text-muted-foreground">{r.borrower_code}</p>
        </div>
      ),
    },
    {
      key: 'contact', header: 'Contact', mobile: 'meta',
      cell: (r) => (
        <span className="inline-flex items-center gap-1">
          <span className="tabular text-xs text-muted-foreground md:text-sm md:text-foreground">{phMobile(r.mobile_number)}</span>
          <a href={telLink(r.mobile_number)} className="grid size-8 place-items-center rounded-full text-blue-600 hover:bg-blue-50" aria-label={`Call ${r.borrower_name}`} onClick={(e) => e.stopPropagation()}>
            <Phone className="size-4" />
          </a>
          {view !== 'paid' && (
            <a
              href={smsReminderLink({ mobile: r.mobile_number, name: r.borrower_name, amount: r.amount_remaining, dueDate: r.due_date, loanNumber: r.loan_number, lender, overdue: r.due_date < today })}
              className="grid size-8 place-items-center rounded-full text-teal-700 hover:bg-teal-50" aria-label={`Text reminder to ${r.borrower_name}`} onClick={(e) => e.stopPropagation()}>
              <MessageSquareText className="size-4" />
            </a>
          )}
        </span>
      ),
    },
    {
      key: 'loan', header: 'Loan', mobile: 'subtitle',
      cell: (r) => (
        <span>
          <Link to={`/loans/${r.loan_id}`} className="font-medium text-blue-600 hover:underline" onClick={(e) => e.stopPropagation()}>{r.loan_number}</Link>
          <span className="text-muted-foreground"> · #{r.installment_no}</span>
        </span>
      ),
    },
    {
      key: 'due', header: view === 'paid' ? 'Due / paid' : 'Due date', mobile: 'meta',
      cell: (r) => (
        <span className={cn(r.display_status === 'overdue' && 'font-medium text-red-600')}>
          {fmtDate(r.due_date)}
          {view === 'paid' && r.paid_at && <span className="block text-xs text-muted-foreground">paid {fmtDate(r.paid_at)}</span>}
          {r.display_status === 'overdue' && <span className="block text-xs">{r.days_overdue}d overdue</span>}
        </span>
      ),
    },
    { key: 'status', header: 'Status', cell: (r) => <InstallmentStatusBadge status={r.display_status === 'upcoming' && r.is_partial ? 'partially_paid' : r.display_status} />, mobile: 'meta' },
    { key: 'collector', header: 'Collector', cell: (r) => r.collector_name ?? <span className="text-muted-foreground">—</span>, mobile: false, className: 'hidden xl:table-cell' },
    {
      key: 'amount', header: view === 'paid' ? 'Paid' : 'Remaining', align: 'right', mobile: 'value',
      cell: (r) => (
        <span>
          <span className="font-semibold">{peso(view === 'paid' ? r.amount_paid : r.amount_remaining)}</span>
          {view !== 'paid' && toNumber(r.amount_paid) > 0 && <span className="block text-xs font-normal text-muted-foreground">of {peso(r.scheduled_amount)}</span>}
        </span>
      ),
    },
    {
      key: 'action', header: '', align: 'right', mobile: 'meta',
      cell: (r) => can('payments.record') && view !== 'paid' && ['active', 'defaulted'].includes(r.loan_status) ? (
        <Button size="sm" className="h-9" onClick={(e) => { e.stopPropagation(); setCollect({ loanId: r.loan_id, installmentId: r.id }) }}>
          <Wallet /> Collect
        </Button>
      ) : null,
    },
  ]

  const fileBase = `${view.replace('_', '-')}-installments-${today}`
  const fetchAll = async () => (await paymentsService.installments({ ...filters, page: 0, pageSize: 5000 })).rows

  return (
    <div className="space-y-5">
      <PageHeader
        title="Due Payments"
        description="Installments due today and coming up, partial payments, and completed collections."
        actions={
          <ExportMenu
            disabled={!q.data?.count}
            onCsv={async () => exportCsv(fileBase, EXPORT_COLUMNS, (await fetchAll()) as unknown as Record<string, unknown>[])}
          />
        }
      />

      <div className="flex gap-1 overflow-x-auto rounded-xl border bg-white p-1 shadow-card">
        {TABS.map((t) => (
          <button key={t.key} onClick={() => switchView(t.key)}
            className={cn('flex flex-1 items-center justify-center gap-2 whitespace-nowrap rounded-lg px-3 py-2.5 text-sm font-medium transition [&_svg]:size-4',
              view === t.key ? 'bg-[#142B4A] text-white shadow' : 'text-muted-foreground hover:bg-muted')}>
            {t.icon}{t.label}
          </button>
        ))}
      </div>

      <Panel bodyClassName="p-3 sm:p-4">
        <div className="mb-4 flex flex-col gap-2 md:flex-row md:items-center md:justify-between">
          <div className="flex flex-wrap items-center gap-2">
            <SearchInput value={search} onChange={(v) => { setSearch(v); setPage(0) }} placeholder="Borrower or loan #" className="w-full sm:w-64" />
            {view === 'upcoming' && (
              <NativeSelect value={days} onChange={(e) => { setDays(Number(e.target.value)); setPage(0) }} className="h-10 w-auto" aria-label="Window">
                <option value={7}>Next 7 days</option>
                <option value={14}>Next 14 days</option>
                <option value={30}>Next 30 days</option>
              </NativeSelect>
            )}
            {view === 'paid' && <DateRangeFilter value={range} onChange={(r) => { setRange(r); setPage(0) }} presets={['today', 'this_week', 'this_month', 'last_month', 'last_3', 'custom']} />}
          </div>
          {can('collections.assign') && (
            <NativeSelect value={collector} onChange={(e) => { setCollector(e.target.value); setPage(0) }} className="h-10 w-auto" aria-label="Collector">
              <option value="">All collectors</option>
              {(staff.data ?? []).filter((u) => u.is_active).map((u) => <option key={u.id} value={u.id}>{u.full_name}</option>)}
            </NativeSelect>
          )}
        </div>

        {rows.length > 0 && (
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2 rounded-lg bg-slate-50 px-3 py-2.5 text-sm">
            <span className="text-muted-foreground">
              {q.data?.count} installment{q.data?.count === 1 ? '' : 's'}{(q.data?.count ?? 0) > rows.length ? ` · showing ${rows.length}` : ''}
            </span>
            <span className="flex items-center gap-4">
              {view === 'paid' ? (
                <span>Collected <b className="tabular">{peso(totalPaid)}</b></span>
              ) : (
                <>
                  {totalPaid > 0 && <span className="text-muted-foreground">Paid so far <span className="tabular">{peso(totalPaid)}</span></span>}
                  <span>To collect <b className="tabular">{peso(totalRemaining)}</b></span>
                </>
              )}
            </span>
          </div>
        )}

        {q.error ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : (
          <>
            <DataTable
              columns={columns}
              rows={q.data?.rows}
              loading={q.isLoading}
              rowKey={(r) => r.id}
              empty={
                <EmptyState
                  icon={view === 'paid' ? <CalendarCheck /> : <CalendarClock />}
                  title={view === 'due_today' ? 'Nothing due today' : view === 'upcoming' ? `Nothing due in the next ${days} days` : view === 'partially_paid' ? 'No partially paid installments' : 'No completed installments in this period'}
                  description={view === 'due_today' ? 'Overdue installments are listed on the Overdue Loans page.' : undefined}
                  action={view === 'due_today' ? <Button asChild variant="outline"><Link to="/overdue">View overdue loans</Link></Button> : undefined}
                />
              }
            />
            <Pagination page={page} pageSize={PAGE_SIZE} count={q.data?.count ?? 0} onPage={setPage} />
          </>
        )}
      </Panel>

      <RecordPaymentDialog
        open={!!collect}
        onOpenChange={(o) => !o && setCollect(null)}
        loanId={collect?.loanId}
        installmentId={collect?.installmentId}
      />
    </div>
  )
}
