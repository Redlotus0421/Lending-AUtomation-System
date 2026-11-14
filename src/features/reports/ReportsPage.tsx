import { useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  BarChart3, Banknote, CalendarDays, CircleDollarSign, ClipboardCheck, Coins, FileWarning, HandCoins, Landmark,
  PiggyBank, RefreshCw, TrendingUp, UserCheck, Users, Wallet,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EmptyState, ErrorState, NativeSelect, PageHeader, Panel, TableSkeleton } from '@/components/common'
import { reportsService } from '@/services/payments'
import { usersService } from '@/services/org'
import { useAuth } from '@/features/auth/AuthProvider'
import { exportCsv, exportPdfTable, exportXlsx, printElement } from '@/lib/export'
import { fmtDate, fmtDateTime } from '@/lib/format'
import { cn } from '@/lib/utils'
import type { ReportColumn, ReportResult } from '@/types/db'
import { DateRangeFilter, ExportMenu, formatReportCell, makeRange, type DateRange } from './shared'

type ReportDef = { key: string; label: string; description: string; icon: ReactNode; usesRange: boolean; pointInTime?: boolean }

const CATALOGUE: { group: string; reports: ReportDef[] }[] = [
  {
    group: 'Portfolio',
    reports: [
      { key: 'loans_released', label: 'Loans released', icon: <HandCoins />, usesRange: true, description: 'Loans disbursed within the period with principal, net proceeds and contractual interest.' },
      { key: 'principal_outstanding', label: 'Principal outstanding', icon: <Landmark />, usesRange: false, pointInTime: true, description: 'Open loans with original principal, principal repaid and principal still outstanding — as of today.' },
      { key: 'outstanding_receivables', label: 'Outstanding receivables', icon: <PiggyBank />, usesRange: false, pointInTime: true, description: 'Everything still owed on open loans, split into principal, interest, charges and penalties, with the overdue portion.' },
      { key: 'completed_loans', label: 'Completed loans', icon: <ClipboardCheck />, usesRange: true, description: 'Loans fully paid (including early settlements) within the period.' },
      { key: 'defaulted_loans', label: 'Defaulted loans', icon: <FileWarning />, usesRange: false, pointInTime: true, description: 'Loans currently marked as defaulted, with amounts collected and still outstanding.' },
    ],
  },
  {
    group: 'Income',
    reports: [
      { key: 'interest_earned', label: 'Interest accrued vs collected', icon: <TrendingUp />, usesRange: true, description: 'Accrued = scheduled interest that fell due in the period (earned). Collected = interest actually received in the period. Expected = contractual interest still to fall due after the period. These are never added together.' },
      { key: 'interest_collected', label: 'Interest collected', icon: <Coins />, usesRange: true, description: 'Interest actually received through posted payments in the period (cash basis), per payment and receipt.' },
    ],
  },
  {
    group: 'Collections',
    reports: [
      { key: 'total_collections', label: 'Total collections', icon: <Wallet />, usesRange: true, description: 'All posted payments in the period with their allocation to principal, interest, charges and penalties.' },
      { key: 'daily_cash', label: 'Daily cash collection', icon: <CalendarDays />, usesRange: true, description: 'Collections per day by payment method, against cash released to borrowers, with net cash flow.' },
      { key: 'monthly_cash', label: 'Monthly cash collection', icon: <Banknote />, usesRange: true, description: 'Collections per month by payment method, against cash released to borrowers, with net cash flow.' },
      { key: 'overdue_accounts', label: 'Overdue accounts', icon: <CircleDollarSign />, usesRange: false, pointInTime: true, description: 'Loans with unpaid installments past due today, oldest due date, days overdue and penalties accrued to date.' },
    ],
  },
  {
    group: 'Performance',
    reports: [
      { key: 'collector_performance', label: 'Collector performance', icon: <UserCheck />, usesRange: true, description: 'Per collector: assigned loans, scheduled amounts due in the period, amounts collected, collection rate and current overdue.' },
      { key: 'borrower_performance', label: 'Borrower payment performance', icon: <Users />, usesRange: false, pointInTime: true, description: 'Per borrower: installments due to date, paid on time, paid late, currently overdue and on-time rate.' },
    ],
  },
]

const ALL = CATALOGUE.flatMap((g) => g.reports)

export default function ReportsPage() {
  const navigate = useNavigate()
  const { can, organization } = useAuth()
  const [reportKey, setReportKey] = useState('total_collections')
  const [range, setRange] = useState<DateRange>(() => makeRange('this_month'))
  const [collector, setCollector] = useState('')
  const printRef = useRef<HTMLDivElement>(null)
  const def = ALL.find((r) => r.key === reportKey)!

  const staff = useQuery({ queryKey: ['staff'], queryFn: usersService.list, enabled: can('collections.assign') || can('users.manage') })
  const collectors = (staff.data ?? []).filter((s) => s.is_active && (s.role === 'collector' || s.role === 'loan_officer' || s.role === 'admin'))

  const q = useQuery({
    queryKey: ['report', reportKey, range.from, range.to, collector],
    queryFn: () => reportsService.get(reportKey, range.from, range.to, collector ? { collector_id: collector } : {}),
  })
  const result = q.data
  const columns: ReportColumn[] = result?.columns ?? []
  const rows = result?.rows ?? []

  const periodText = def.pointInTime ? `As of ${fmtDate(result?.to ?? range.to, 'long')}` : `${fmtDate(range.from, 'long')} – ${fmtDate(range.to, 'long')}`
  const collectorName = collectors.find((c) => c.id === collector)?.full_name
  const subtitle = `${periodText}${collectorName ? ` · Collector: ${collectorName}` : ''}`
  const fileBase = `${def.label}-${range.from}-to-${range.to}`

  return (
    <div className="space-y-5">
      <div className="no-print">
        <PageHeader
          title="Reports & Analytics"
          description="Financial and operational reports computed from posted transactions. Export to PDF, Excel or CSV."
          actions={
            <>
              <Button variant="outline" className="bg-white" onClick={() => void q.refetch()} disabled={q.isFetching}>
                <RefreshCw className={cn(q.isFetching && 'animate-spin')} /> Refresh
              </Button>
              <ExportMenu
                disabled={!rows.length}
                onPdf={() => exportPdfTable(fileBase, { title: def.label, subtitle, organization: organization?.legal_name || organization?.name, columns, rows, totals: result?.totals, note: def.description })}
                onXlsx={() => exportXlsx(fileBase, def.label, columns, rows, result?.totals)}
                onCsv={() => exportCsv(fileBase, columns, rows, result?.totals)}
                onPrint={() => printElement(printRef.current, def.label)}
              />
            </>
          }
        />
      </div>

      <div className="grid gap-5 lg:grid-cols-[280px_minmax(0,1fr)]">
        {/* catalogue */}
        <aside className="no-print">
          <div className="lg:hidden">
            <NativeSelect value={reportKey} onChange={(e) => setReportKey(e.target.value)} aria-label="Report">
              {CATALOGUE.map((g) => (
                <optgroup key={g.group} label={g.group}>
                  {g.reports.map((r) => <option key={r.key} value={r.key}>{r.label}</option>)}
                </optgroup>
              ))}
            </NativeSelect>
          </div>
          <div className="hidden space-y-4 rounded-xl border bg-card p-3 shadow-card lg:block">
            {CATALOGUE.map((g) => (
              <div key={g.group}>
                <p className="mb-1 px-2 text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground">{g.group}</p>
                <ul className="space-y-0.5">
                  {g.reports.map((r) => (
                    <li key={r.key}>
                      <button
                        onClick={() => setReportKey(r.key)}
                        className={cn('flex w-full items-center gap-2.5 rounded-lg px-2 py-2 text-left text-sm transition [&_svg]:size-4',
                          r.key === reportKey ? 'bg-blue-50 font-medium text-blue-700 [&_svg]:text-blue-600' : 'text-foreground hover:bg-muted [&_svg]:text-muted-foreground')}
                      >
                        {r.icon}
                        {r.label}
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </aside>

        <div className="min-w-0 space-y-4">
          <div className="no-print flex flex-wrap items-center gap-2 rounded-xl border bg-card p-3 shadow-card">
            {def.usesRange ? (
              <DateRangeFilter value={range} onChange={setRange} presets={['today', 'this_week', 'this_month', 'last_month', 'last_3', 'last_6', 'last_12', 'ytd', 'custom']} />
            ) : (
              <span className="inline-flex items-center gap-1.5 rounded-md bg-muted px-3 py-2 text-xs font-medium text-muted-foreground">
                <BarChart3 className="size-3.5" /> Point-in-time report — as of today
              </span>
            )}
            {(can('collections.assign') || can('users.manage')) && collectors.length > 0 && (
              <NativeSelect value={collector} onChange={(e) => setCollector(e.target.value)} className="h-10 w-auto min-w-44" aria-label="Collector">
                <option value="">All collectors</option>
                {collectors.map((c) => <option key={c.id} value={c.id}>{c.full_name}</option>)}
              </NativeSelect>
            )}
          </div>

          <div ref={printRef} className="print-area">
            <Panel
              title={<span className="text-base">{def.label}</span>}
              description={<span>{subtitle}{result ? ` · Generated ${fmtDateTime(result.generated_at)}` : ''}</span>}
              bodyClassName="p-0 sm:p-0"
            >
              <p className="border-b bg-slate-50/70 px-4 py-2.5 text-xs leading-relaxed text-muted-foreground sm:px-5">{def.description}</p>
              <div className="p-3 sm:p-4">
                {q.isLoading ? (
                  <TableSkeleton rows={8} />
                ) : q.error ? (
                  <ErrorState error={q.error} onRetry={() => void q.refetch()} />
                ) : !rows.length ? (
                  <EmptyState title="No records for this report" description={def.usesRange ? 'Try a wider date range or a different collector.' : 'There is nothing to report right now.'} />
                ) : (
                  <ReportTable result={result!} onRowClick={(r) => r.loan_id && navigate(`/loans/${r.loan_id}`)} />
                )}
              </div>
            </Panel>
          </div>
        </div>
      </div>
    </div>
  )
}

function ReportTable({ result, onRowClick }: { result: ReportResult; onRowClick: (row: Record<string, any>) => void }) {
  const { columns, rows, totals } = result
  const hasTotals = columns.some((c) => c.total)
  const numeric = (c: ReportColumn) => c.type === 'money' || c.type === 'number' || c.type === 'percent'
  const clickable = useMemo(() => rows.some((r) => r.loan_id), [rows])
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[640px] text-sm">
        <thead>
          <tr className="border-b text-left text-xs uppercase tracking-wide text-muted-foreground">
            {columns.map((c) => (
              <th key={c.key} className={cn('whitespace-nowrap px-3 py-2.5 font-medium', numeric(c) && 'text-right')}>{c.label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i} onClick={() => onRowClick(r)} className={cn('border-b last:border-0', clickable && r.loan_id && 'cursor-pointer hover:bg-muted/50')}>
              {columns.map((c) => (
                <td key={c.key} className={cn('px-3 py-2.5', numeric(c) && 'tabular whitespace-nowrap text-right')}>{formatReportCell(c, r[c.key], r)}</td>
              ))}
            </tr>
          ))}
        </tbody>
        {hasTotals && (
          <tfoot>
            <tr className="border-t-2 bg-slate-50 font-semibold">
              {columns.map((c, i) => (
                <td key={c.key} className={cn('px-3 py-2.5', numeric(c) && 'tabular whitespace-nowrap text-right')}>
                  {c.total ? formatReportCell(c, totals?.[c.key] ?? 0) : i === 0 ? `Total (${rows.length})` : ''}
                </td>
              ))}
            </tr>
          </tfoot>
        )}
      </table>
    </div>
  )
}
