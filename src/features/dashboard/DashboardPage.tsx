import { useState, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  AlertTriangle, ArrowRight, Banknote, CalendarClock, ClipboardList, Coins, FileSignature, HandCoins,
  Landmark, PackageCheck, PiggyBank, RefreshCw, TrendingUp, Users, Wallet,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EmptyState, ErrorState, LoanStatusBadge, PageHeader, Panel, StatCard } from '@/components/common'
import { Skeleton } from '@/components/ui/skeleton'
import { useAuth } from '@/features/auth/AuthProvider'
import { reportsService } from '@/services/payments'
import { fmtDate, fmtDateTime, num, peso, titleCase } from '@/lib/format'
import { exportCsv, exportPdfReport, exportXlsxSheets, type ExportColumn } from '@/lib/export'
import { cn } from '@/lib/utils'
import { DateRangeFilter, ExportMenu, makeRange, type DateRange } from '@/features/reports/shared'
import { AgingChart, CollectionsChart, ComponentsChart, PerformanceChart, ReleasesChart, type AgingPoint, type MonthPoint } from './charts'

type Dashboard = {
  range: { from: string; to: string; today: string }
  financial: {
    capital_released: number; capital_released_all_time: number; outstanding_principal: number
    remaining_receivables: number; interest_expected_remaining: number; interest_expected_contract: number
  }
  collections: { total: number; principal: number; interest: number; charges: number; penalties: number; count: number }
  interest_accrued: number
  overdue: { amount: number; loans: number; installments: number }
  operational: {
    borrowers: number; active_loans: number; pending_applications: number; awaiting_signature: number
    ready_for_release: number; due_today: number; due_today_amount: number
  }
  monthly: MonthPoint[]
  portfolio_aging: AgingPoint[]
  recent_applications: { id: string; loan_number: string; borrower_name: string; principal: number; status: string; created_at: string }[]
  upcoming_payments: { installment_id: string; loan_id: string; loan_number: string; borrower_name: string; installment_no: number; due_date: string; amount: number }[]
  recent_collections: { payment_id: string; payment_number: string; loan_id: string; loan_number: string; borrower_name: string; amount: number; payment_date: string; method: string; created_at: string }[]
  overdue_borrowers: { loan_id: string; loan_number: string; borrower_id: string; borrower_name: string; mobile_number: string; overdue_amount: number; days_overdue: number }[]
  recently_signed: { agreement_id: string; agreement_number: string; loan_id: string; loan_number: string; borrower_name: string; fully_signed_at: string }[]
}

const MONTH_COLUMNS: ExportColumn[] = [
  { key: 'label', label: 'Month' },
  { key: 'loans_released', label: 'Loans released', type: 'number', total: true },
  { key: 'released', label: 'Principal released', type: 'money', total: true },
  { key: 'collected', label: 'Total collected', type: 'money', total: true },
  { key: 'principal_collected', label: 'Principal collected', type: 'money', total: true },
  { key: 'interest_collected', label: 'Interest collected', type: 'money', total: true },
  { key: 'other_collected', label: 'Charges & penalties collected', type: 'money', total: true },
  { key: 'scheduled_due', label: 'Scheduled due', type: 'money', total: true },
  { key: 'paid_on_due', label: 'Paid toward scheduled', type: 'money', total: true },
]

const SUMMARY_COLUMNS: ExportColumn[] = [
  { key: 'group', label: 'Group' },
  { key: 'metric', label: 'Metric' },
  { key: 'value', label: 'Value' },
  { key: 'basis', label: 'Basis' },
]

function summaryRows(d: Dashboard) {
  const p = `${fmtDate(d.range.from)} – ${fmtDate(d.range.to)}`
  return [
    { group: 'Financial', metric: 'Capital released', value: peso(d.financial.capital_released), basis: p },
    { group: 'Financial', metric: 'Capital released (all time)', value: peso(d.financial.capital_released_all_time), basis: 'All time' },
    { group: 'Financial', metric: 'Outstanding principal', value: peso(d.financial.outstanding_principal), basis: 'As of today' },
    { group: 'Financial', metric: 'Remaining receivables', value: peso(d.financial.remaining_receivables), basis: 'As of today' },
    { group: 'Financial', metric: 'Interest expected (remaining, contractual)', value: peso(d.financial.interest_expected_remaining), basis: 'As of today' },
    { group: 'Financial', metric: 'Interest accrued (fell due)', value: peso(d.interest_accrued), basis: p },
    { group: 'Financial', metric: 'Interest collected', value: peso(d.collections.interest), basis: p },
    { group: 'Financial', metric: 'Payments collected', value: `${peso(d.collections.total)} (${d.collections.count} payments)`, basis: p },
    { group: 'Financial', metric: 'Overdue amount', value: peso(d.overdue.amount), basis: 'As of today' },
    { group: 'Operational', metric: 'Active borrowers', value: num(d.operational.borrowers), basis: 'As of today' },
    { group: 'Operational', metric: 'Active loans', value: num(d.operational.active_loans), basis: 'As of today' },
    { group: 'Operational', metric: 'Pending applications', value: num(d.operational.pending_applications), basis: 'As of today' },
    { group: 'Operational', metric: 'Agreements awaiting signature', value: num(d.operational.awaiting_signature), basis: 'As of today' },
    { group: 'Operational', metric: 'Ready for release', value: num(d.operational.ready_for_release), basis: 'As of today' },
    { group: 'Operational', metric: 'Loans due today', value: `${num(d.operational.due_today)} (${peso(d.operational.due_today_amount)})`, basis: 'Today' },
    { group: 'Operational', metric: 'Overdue loans', value: num(d.overdue.loans), basis: 'As of today' },
  ]
}

export default function DashboardPage() {
  const navigate = useNavigate()
  const { access, organization, can } = useAuth()
  const [range, setRange] = useState<DateRange>(() => makeRange('last_12'))
  const q = useQuery({
    queryKey: ['dashboard', range.from, range.to],
    queryFn: () => reportsService.dashboard(range.from, range.to) as Promise<Dashboard>,
  })
  const d = q.data
  const firstName = (access?.full_name ?? '').split(' ')[0]
  const periodLabel = `${fmtDate(range.from)} – ${fmtDate(range.to)}`
  const fileBase = `dashboard-${range.from}-to-${range.to}`

  return (
    <div className="space-y-5">
      <PageHeader
        title={firstName ? `Good day, ${firstName}` : 'Dashboard'}
        description={`Lending performance for ${organization?.name ?? 'your organization'} · amounts in PHP`}
        actions={
          <>
            <DateRangeFilter value={range} onChange={setRange} presets={['this_month', 'last_3', 'last_6', 'last_12', 'ytd', 'custom']} />
            <Button variant="outline" size="icon" className="size-10 bg-white" onClick={() => void q.refetch()} disabled={q.isFetching} aria-label="Refresh">
              <RefreshCw className={cn(q.isFetching && 'animate-spin')} />
            </Button>
            <ExportMenu
              disabled={!d}
              onPdf={() => d && exportPdfReport(fileBase, {
                title: 'Lending dashboard summary',
                subtitle: periodLabel,
                organization: organization?.legal_name || organization?.name,
                sections: [
                  { title: 'Key figures', columns: SUMMARY_COLUMNS, rows: summaryRows(d), note: 'Interest expected = contractual interest not yet paid on open loans. Interest accrued = scheduled interest that fell due in the period. Interest collected = interest actually received in the period.' },
                  { title: 'Monthly series', columns: MONTH_COLUMNS, rows: d.monthly },
                ],
                orientation: 'landscape',
              })}
              onXlsx={() => d && exportXlsxSheets(fileBase, [
                { name: 'Summary', columns: SUMMARY_COLUMNS, rows: summaryRows(d) },
                { name: 'Monthly', columns: MONTH_COLUMNS, rows: d.monthly },
              ])}
              onCsv={() => d && exportCsv(fileBase, MONTH_COLUMNS, d.monthly)}
            />
          </>
        }
      />

      {q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : !d ? (
        <DashboardSkeleton />
      ) : (
        <>
          {/* financial summary */}
          <section className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <StatCard label="Capital released" value={peso(d.financial.capital_released)} icon={<HandCoins />} tone="blue"
              hint={<>In period · {peso(d.financial.capital_released_all_time, { compact: true })} all time</>} />
            <StatCard label="Outstanding principal" value={peso(d.financial.outstanding_principal)} icon={<Landmark />} tone="navy" hint="Unpaid principal on open loans" />
            <StatCard label="Remaining receivables" value={peso(d.financial.remaining_receivables)} icon={<PiggyBank />} tone="teal"
              hint="Principal + interest + charges + penalties" />
            <StatCard label="Overdue amount" value={peso(d.overdue.amount)} icon={<AlertTriangle />} tone="red"
              hint={`${d.overdue.installments} installment${d.overdue.installments === 1 ? '' : 's'} on ${d.overdue.loans} loan${d.overdue.loans === 1 ? '' : 's'}`}
              onClick={can('payments.view') ? () => navigate('/overdue') : undefined} />
          </section>

          <section className="grid gap-3 lg:grid-cols-[1fr_1.4fr]">
            <StatCard label="Payments collected" value={peso(d.collections.total)} icon={<Wallet />} tone="green"
              hint={`${d.collections.count} posted payment${d.collections.count === 1 ? '' : 's'} in period`}
              onClick={can('payments.view') ? () => navigate('/payments') : undefined} />
            <div className="rounded-xl border bg-card p-4 shadow-card">
              <div className="flex items-start justify-between gap-2">
                <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Interest — three different measures</p>
                <span className="grid size-8 place-items-center rounded-lg bg-teal-50 text-teal-600 [&_svg]:size-4"><TrendingUp /></span>
              </div>
              <div className="mt-2 grid grid-cols-3 divide-x">
                <InterestMeasure label="Expected" sub="Remaining contractual, not yet paid" value={d.financial.interest_expected_remaining} />
                <InterestMeasure label="Accrued" sub="Fell due in period (earned)" value={d.interest_accrued} />
                <InterestMeasure label="Collected" sub="Actually received in period" value={d.collections.interest} strong />
              </div>
            </div>
          </section>

          {/* operational */}
          <section className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-7">
            <OpCard label="Borrowers" value={d.operational.borrowers} icon={<Users />} to="/borrowers" />
            <OpCard label="Active loans" value={d.operational.active_loans} icon={<Banknote />} to="/loans" />
            <OpCard label="Pending applications" value={d.operational.pending_applications} icon={<ClipboardList />} to="/applications" />
            <OpCard label="Awaiting signature" value={d.operational.awaiting_signature} icon={<FileSignature />} to="/applications" />
            <OpCard label="Ready for release" value={d.operational.ready_for_release} icon={<PackageCheck />} to="/applications" />
            <OpCard label="Due today" value={d.operational.due_today} sub={peso(d.operational.due_today_amount)} icon={<CalendarClock />} to="/due" tone="amber" />
            <OpCard label="Overdue loans" value={d.overdue.loans} icon={<AlertTriangle />} to="/overdue" tone="red" />
          </section>

          {/* charts */}
          <section className="grid gap-4 xl:grid-cols-2">
            <Panel title="Monthly loan releases" description="Principal released per month">
              {d.monthly.some((m) => m.released > 0) ? <ReleasesChart data={d.monthly} /> : <ChartEmpty text="No loans were released in this period." />}
            </Panel>
            <Panel title="Monthly collections" description="Total posted payments per month">
              {d.monthly.some((m) => m.collected > 0) ? <CollectionsChart data={d.monthly} /> : <ChartEmpty text="No payments were collected in this period." />}
            </Panel>
            <Panel title="Principal vs interest collected" description="Where each month's collections were applied">
              {d.monthly.some((m) => m.collected > 0) ? <ComponentsChart data={d.monthly} /> : <ChartEmpty text="No payments were collected in this period." />}
            </Panel>
            <Panel title="Payment performance" description={<PerformanceSummary data={d.monthly} />}>
              {d.monthly.some((m) => m.scheduled_due > 0) ? <PerformanceChart data={d.monthly} /> : <ChartEmpty text="No installments were scheduled in this period." />}
            </Panel>
          </section>

          <section className="grid gap-4 xl:grid-cols-[1fr_1.6fr]">
            <Panel title="Outstanding portfolio" description="Outstanding principal by days past due (portfolio at risk)">
              {d.portfolio_aging.length ? (
                <>
                  <AgingChart data={d.portfolio_aging} />
                  <PortfolioAtRisk data={d.portfolio_aging} />
                </>
              ) : <ChartEmpty text="No open loans yet." />}
            </Panel>
            <Panel title="Overdue borrowers" description="Largest overdue balances"
              actions={can('payments.view') && <ViewAll to="/overdue" />} bodyClassName="p-0 sm:p-0">
              <MiniList empty="No overdue accounts — great job!" rows={d.overdue_borrowers.map((r) => ({
                key: r.loan_id,
                to: `/loans/${r.loan_id}`,
                title: r.borrower_name,
                sub: `${r.loan_number} · ${r.days_overdue} day${r.days_overdue === 1 ? '' : 's'} overdue`,
                value: peso(r.overdue_amount),
                valueClass: 'text-red-600',
              }))} />
            </Panel>
          </section>

          <section className="grid gap-4 lg:grid-cols-2 xl:grid-cols-3">
            <Panel title="Recent loan applications" actions={can('loans.view') && <ViewAll to="/applications" />} bodyClassName="p-0 sm:p-0">
              <MiniList empty="No applications in the pipeline." rows={d.recent_applications.map((r) => ({
                key: r.id, to: `/loans/${r.id}`, title: r.borrower_name, sub: `${r.loan_number} · ${fmtDate(r.created_at)}`,
                value: peso(r.principal), badge: <LoanStatusBadge status={r.status} />,
              }))} />
            </Panel>
            <Panel title="Upcoming payments" description="Next 7 days" actions={can('payments.view') && <ViewAll to="/due" />} bodyClassName="p-0 sm:p-0">
              <MiniList empty="Nothing due in the next 7 days." rows={d.upcoming_payments.map((r) => ({
                key: r.installment_id, to: `/loans/${r.loan_id}`, title: r.borrower_name,
                sub: `${r.loan_number} · #${r.installment_no} · due ${fmtDate(r.due_date)}`, value: peso(r.amount),
              }))} />
            </Panel>
            <Panel title="Recent collections" actions={can('payments.view') && <ViewAll to="/payments" />} bodyClassName="p-0 sm:p-0">
              <MiniList empty="No payments recorded yet." rows={d.recent_collections.map((r) => ({
                key: r.payment_id, to: `/payments/${r.payment_id}`, title: r.borrower_name,
                sub: `${r.payment_number} · ${titleCase(r.method)} · ${fmtDate(r.payment_date)}`, value: peso(r.amount), valueClass: 'text-green-700',
              }))} />
            </Panel>
            <Panel title="Recently signed agreements" actions={can('agreements.view') && <ViewAll to="/agreements" />} bodyClassName="p-0 sm:p-0" className="lg:col-span-2 xl:col-span-3">
              <MiniList empty="No fully signed agreements yet." rows={d.recently_signed.map((r) => ({
                key: r.agreement_id, to: `/loans/${r.loan_id}`, title: r.borrower_name,
                sub: `${r.agreement_number} · ${r.loan_number}`, value: <span className="text-xs font-normal text-muted-foreground">{fmtDateTime(r.fully_signed_at)}</span>,
                badge: <span className="inline-flex items-center gap-1 text-xs text-green-700"><FileSignature className="size-3.5" /> Signed</span>,
              }))} />
            </Panel>
          </section>
          <p className="text-center text-[11px] text-muted-foreground">
            Period figures cover {periodLabel}; balances and overdue figures are as of {fmtDate(d.range.today, 'long')} (Asia/Manila).
          </p>
        </>
      )}
    </div>
  )
}

function InterestMeasure({ label, sub, value, strong }: { label: string; sub: string; value: number; strong?: boolean }) {
  return (
    <div className="px-3 first:pl-0 last:pr-0">
      <p className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className={cn('tabular mt-1 text-base font-semibold sm:text-lg', strong ? 'text-teal-700' : 'text-[#0f1b2d]')}>{peso(value, { compact: value >= 1_000_000 })}</p>
      <p className="mt-0.5 text-[11px] leading-snug text-muted-foreground">{sub}</p>
    </div>
  )
}

function OpCard({ label, value, sub, icon, to, tone = 'slate' }: { label: string; value: number; sub?: string; icon: ReactNode; to: string; tone?: 'slate' | 'amber' | 'red' }) {
  const tones = { slate: 'text-[#142B4A] bg-[#142B4A]/8', amber: 'text-amber-700 bg-amber-50', red: 'text-red-600 bg-red-50' }
  return (
    <Link to={to} className="group rounded-xl border bg-card p-3 shadow-card transition hover:border-blue-200 hover:shadow-md">
      <div className="flex items-center justify-between">
        <span className={cn('grid size-8 place-items-center rounded-lg [&_svg]:size-4', tones[tone])}>{icon}</span>
        <ArrowRight className="size-3.5 text-muted-foreground opacity-0 transition group-hover:opacity-100" />
      </div>
      <p className="tabular mt-2 text-xl font-semibold text-[#0f1b2d]">{num(value)}</p>
      <p className="text-xs text-muted-foreground">{label}</p>
      {sub && <p className="tabular text-[11px] font-medium text-amber-700">{sub}</p>}
    </Link>
  )
}

function ViewAll({ to }: { to: string }) {
  return (
    <Button asChild variant="ghost" size="sm" className="h-7 text-xs text-blue-600">
      <Link to={to}>View all <ArrowRight className="size-3" /></Link>
    </Button>
  )
}

type MiniRow = { key: string; to: string; title: string; sub: string; value: ReactNode; valueClass?: string; badge?: ReactNode }

function MiniList({ rows, empty }: { rows: MiniRow[]; empty: string }) {
  if (!rows.length) return <EmptyState className="py-8" title={empty} />
  return (
    <ul className="divide-y">
      {rows.map((r) => (
        <li key={r.key}>
          <Link to={r.to} className="flex items-center justify-between gap-3 px-4 py-3 transition hover:bg-muted/50 sm:px-5">
            <div className="min-w-0">
              <p className="truncate text-sm font-medium">{r.title}</p>
              <p className="truncate text-xs text-muted-foreground">{r.sub}</p>
            </div>
            <div className="flex shrink-0 flex-col items-end gap-1">
              <span className={cn('tabular text-sm font-semibold', r.valueClass)}>{r.value}</span>
              {r.badge}
            </div>
          </Link>
        </li>
      ))}
    </ul>
  )
}

function ChartEmpty({ text }: { text: string }) {
  return (
    <div className="grid h-[220px] place-items-center rounded-lg border border-dashed text-center text-sm text-muted-foreground">
      <div>
        <Coins className="mx-auto mb-2 size-5 opacity-60" />
        {text}
      </div>
    </div>
  )
}

function PerformanceSummary({ data }: { data: MonthPoint[] }) {
  const due = data.reduce((a, m) => a + Number(m.scheduled_due), 0)
  const paid = data.reduce((a, m) => a + Number(m.paid_on_due), 0)
  return (
    <span>
      Scheduled installments vs amounts paid toward them
      {due > 0 && <> · <b className="text-foreground">{((paid / due) * 100).toFixed(1)}%</b> collection rate</>}
    </span>
  )
}

function PortfolioAtRisk({ data }: { data: AgingPoint[] }) {
  const total = data.reduce((a, b) => a + Number(b.outstanding_principal), 0)
  const atRisk = data.filter((b) => b.bucket !== 'Current').reduce((a, b) => a + Number(b.outstanding_principal), 0)
  const par30 = data.filter((b) => !['Current', '1–30 days'].includes(b.bucket)).reduce((a, b) => a + Number(b.outstanding_principal), 0)
  const pct = (v: number) => (total > 0 ? `${((v / total) * 100).toFixed(1)}%` : '0%')
  return (
    <div className="mt-3 grid grid-cols-3 gap-2 rounded-lg bg-slate-50 p-3 text-center">
      <div><p className="text-[11px] text-muted-foreground">Portfolio</p><p className="tabular text-sm font-semibold">{peso(total, { compact: true })}</p></div>
      <div><p className="text-[11px] text-muted-foreground">PAR 1+</p><p className="tabular text-sm font-semibold text-amber-700">{pct(atRisk)}</p></div>
      <div><p className="text-[11px] text-muted-foreground">PAR 30+</p><p className="tabular text-sm font-semibold text-red-600">{pct(par30)}</p></div>
    </div>
  )
}

function DashboardSkeleton() {
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-28 rounded-xl" />)}</div>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-7">{Array.from({ length: 7 }).map((_, i) => <Skeleton key={i} className="h-24 rounded-xl" />)}</div>
      <div className="grid gap-4 xl:grid-cols-2">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-72 rounded-xl" />)}</div>
    </div>
  )
}

