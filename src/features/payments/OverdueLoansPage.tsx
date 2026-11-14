import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { AlertTriangle, MessageSquareText, Phone, ShieldAlert, UserPlus, Wallet } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  DataTable, EmptyState, ErrorState, Field, LoanStatusBadge, NativeSelect, PageHeader, Pagination, Panel, Pill, SearchInput,
  Spinner, StatCard, useDebounced, type Column,
} from '@/components/common'
import { useAuth } from '@/features/auth/AuthProvider'
import { paymentsService, reportsService } from '@/services/payments'
import { loansService } from '@/services/loans'
import { usersService } from '@/services/org'
import { errorMessage } from '@/lib/errors'
import { ROLE_LABEL } from '@/lib/constants'
import { fmtDate, peso, phMobile, todayManila, toNumber } from '@/lib/format'
import { exportCsv, type ExportColumn } from '@/lib/export'
import { cn } from '@/lib/utils'
import type { LoanView } from '@/types/db'
import { ExportMenu, smsReminderLink, telLink } from '@/features/reports/shared'
import { RecordPaymentDialog } from './RecordPaymentDialog'

const PAGE_SIZE = 25

const BUCKETS = [
  { key: 'all', label: 'All overdue', min: undefined, max: undefined },
  { key: '1-30', label: '1–30 days', min: 1, max: 30 },
  { key: '31-60', label: '31–60 days', min: 31, max: 60 },
  { key: '61-90', label: '61–90 days', min: 61, max: 90 },
  { key: '90+', label: '90+ days', min: 91, max: undefined },
] as const
type BucketKey = (typeof BUCKETS)[number]['key']

function bucketTone(days: number) {
  return days > 60 ? ('red' as const) : ('amber' as const)
}

const EXPORT_COLUMNS: ExportColumn[] = [
  { key: 'borrower_name', label: 'Borrower' },
  { key: 'borrower_mobile', label: 'Mobile' },
  { key: 'loan_number', label: 'Loan #' },
  { key: 'status', label: 'Status', type: 'status' },
  { key: 'oldest_overdue_date', label: 'Oldest due', type: 'date' },
  { key: 'days_overdue', label: 'Days overdue', type: 'number' },
  { key: 'overdue_count', label: 'Installments overdue', type: 'number', total: true },
  { key: 'overdue_amount', label: 'Overdue amount', type: 'money', total: true },
  { key: 'penalties', label: 'Penalties (to date)', type: 'money', total: true },
  { key: 'total_outstanding', label: 'Total outstanding', type: 'money', total: true },
  { key: 'collector_name', label: 'Collector' },
]

export default function OverdueLoansPage() {
  const navigate = useNavigate()
  const { can, organization } = useAuth()
  const [bucket, setBucket] = useState<BucketKey>('all')
  const [search, setSearch] = useState('')
  const [collector, setCollector] = useState('')
  const [page, setPage] = useState(0)
  const [collect, setCollect] = useState<string | null>(null)
  const [assign, setAssign] = useState<LoanView | null>(null)
  const term = useDebounced(search)
  const b = BUCKETS.find((x) => x.key === bucket)!

  const staff = useQuery({ queryKey: ['staff'], queryFn: usersService.list, enabled: can('collections.assign') })
  const filters = { search: term, collectorId: collector || undefined, minDays: b.min, maxDays: b.max }
  const q = useQuery({
    queryKey: ['loans', 'overdue', filters, page],
    queryFn: () => paymentsService.overdueLoans({ ...filters, page, pageSize: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  })
  // Penalties accrued to date (posted + not yet posted) come from the overdue accounts report when permitted.
  const penaltiesQ = useQuery({
    queryKey: ['report', 'overdue_accounts', 'penalties'],
    enabled: can('reports.view'),
    queryFn: () => reportsService.get('overdue_accounts', todayManila(), todayManila()),
  })
  const penaltyMap = useMemo(() => {
    const m = new Map<string, number>()
    penaltiesQ.data?.rows.forEach((r) => m.set(String(r.loan_id), toNumber(r.penalties)))
    return m
  }, [penaltiesQ.data])
  const penaltyOf = (l: LoanView) => penaltyMap.get(l.id) ?? toNumber(l.outstanding_penalties)

  const buckets = useMemo(() => {
    const rows = penaltiesQ.data?.rows ?? []
    const by = (min: number, max?: number) => rows.filter((r) => toNumber(r.days_overdue) >= min && (max === undefined || toNumber(r.days_overdue) <= max))
    const sum = (rs: typeof rows) => rs.reduce((a, r) => a + toNumber(r.overdue_amount), 0)
    return {
      total: sum(rows), count: rows.length,
      b1: sum(by(1, 30)), b2: sum(by(31, 60)), b3: sum(by(61, 90)), b4: sum(by(91)),
    }
  }, [penaltiesQ.data])

  const rows = q.data?.rows ?? []
  const lender = organization?.name

  const columns: Column<LoanView>[] = [
    {
      key: 'borrower', header: 'Borrower', mobile: 'title',
      cell: (l) => (
        <div className="min-w-0">
          <Link to={`/borrowers/${l.borrower_id}`} className="font-medium hover:text-blue-600" onClick={(e) => e.stopPropagation()}>{l.borrower_name}</Link>
          <p className="text-xs text-muted-foreground">{l.loan_number}{l.status === 'defaulted' && ' · defaulted'}</p>
        </div>
      ),
    },
    {
      key: 'contact', header: 'Contact', mobile: 'meta',
      cell: (l) => (
        <span className="inline-flex items-center gap-1">
          <span className="tabular text-xs text-muted-foreground md:text-sm md:text-foreground">{phMobile(l.borrower_mobile)}</span>
          <a href={telLink(l.borrower_mobile)} onClick={(e) => e.stopPropagation()} className="grid size-8 place-items-center rounded-full text-blue-600 hover:bg-blue-50" aria-label={`Call ${l.borrower_name}`}>
            <Phone className="size-4" />
          </a>
          <a
            href={smsReminderLink({ mobile: l.borrower_mobile, name: l.borrower_name, amount: l.overdue_amount, dueDate: l.oldest_overdue_date ?? todayManila(), loanNumber: l.loan_number, lender, overdue: true })}
            onClick={(e) => e.stopPropagation()} className="grid size-8 place-items-center rounded-full text-teal-700 hover:bg-teal-50" aria-label={`Text ${l.borrower_name}`}>
            <MessageSquareText className="size-4" />
          </a>
        </span>
      ),
    },
    {
      key: 'days', header: 'Overdue since', mobile: 'subtitle',
      cell: (l) => (
        <span className="inline-flex flex-wrap items-center gap-1.5">
          <Pill tone={bucketTone(l.days_overdue)}>{l.days_overdue} days</Pill>
          <span className="text-xs text-muted-foreground">since {fmtDate(l.oldest_overdue_date)} · {l.overdue_count} inst.</span>
        </span>
      ),
    },
    {
      key: 'penalties', header: can('reports.view') ? 'Penalties to date' : 'Penalties posted', align: 'right', mobile: 'meta',
      cell: (l) => <span className={cn(penaltyOf(l) > 0 ? 'text-amber-700' : 'text-muted-foreground')}>{peso(penaltyOf(l))}</span>,
    },
    { key: 'collector', header: 'Collector', mobile: 'meta', cell: (l) => l.collector_name ?? <span className="text-muted-foreground">Unassigned</span> },
    {
      key: 'amount', header: 'Overdue', align: 'right', mobile: 'value',
      cell: (l) => (
        <span>
          <span className="font-semibold text-red-600">{peso(l.overdue_amount)}</span>
          <span className="block text-xs font-normal text-muted-foreground">of {peso(l.total_outstanding)}</span>
        </span>
      ),
    },
    {
      key: 'actions', header: '', align: 'right', mobile: 'meta',
      cell: (l) => (
        <span className="inline-flex gap-1.5">
          {can('collections.assign') && (
            <Button size="sm" variant="outline" className="h-9" onClick={(e) => { e.stopPropagation(); setAssign(l) }} aria-label="Assign collector">
              <UserPlus /> <span className="hidden xl:inline">Assign</span>
            </Button>
          )}
          {can('payments.record') && (
            <Button size="sm" className="h-9" onClick={(e) => { e.stopPropagation(); setCollect(l.id) }}><Wallet /> Collect</Button>
          )}
        </span>
      ),
    },
  ]

  const fetchAll = async () => (await paymentsService.overdueLoans({ ...filters, page: 0, pageSize: 5000 })).rows
    .map((l) => ({ ...l, penalties: penaltyOf(l) }))

  return (
    <div className="space-y-5">
      <PageHeader
        title="Overdue Loans"
        description="Loans with installments past their due date, oldest first. Penalties accrue automatically per each loan's late-payment terms."
        actions={<ExportMenu disabled={!q.data?.count} onCsv={async () => exportCsv(`overdue-loans-${todayManila()}`, EXPORT_COLUMNS, await fetchAll())} />}
      />

      {can('reports.view') && penaltiesQ.data && (
        <section className="grid grid-cols-2 gap-3 md:grid-cols-5">
          <StatCard label="Total overdue" value={peso(buckets.total)} hint={`${buckets.count} loan${buckets.count === 1 ? '' : 's'}`} icon={<AlertTriangle />} tone="red" className="col-span-2 md:col-span-1" />
          <StatCard label="1–30 days" value={peso(buckets.b1)} tone="amber" onClick={() => { setBucket('1-30'); setPage(0) }} />
          <StatCard label="31–60 days" value={peso(buckets.b2)} tone="amber" onClick={() => { setBucket('31-60'); setPage(0) }} />
          <StatCard label="61–90 days" value={peso(buckets.b3)} tone="red" onClick={() => { setBucket('61-90'); setPage(0) }} />
          <StatCard label="90+ days" value={peso(buckets.b4)} tone="red" onClick={() => { setBucket('90+'); setPage(0) }} />
        </section>
      )}

      <Panel bodyClassName="p-3 sm:p-4">
        <div className="mb-4 flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-wrap gap-1.5">
            {BUCKETS.map((x) => (
              <button key={x.key} onClick={() => { setBucket(x.key); setPage(0) }}
                className={cn('rounded-full border px-3 py-1.5 text-xs font-medium transition',
                  bucket === x.key ? 'border-[#142B4A] bg-[#142B4A] text-white' : 'bg-white text-muted-foreground hover:bg-muted')}>
                {x.label}
              </button>
            ))}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <SearchInput value={search} onChange={(v) => { setSearch(v); setPage(0) }} placeholder="Borrower or loan #" className="w-full sm:w-60" />
            {can('collections.assign') && (
              <NativeSelect value={collector} onChange={(e) => { setCollector(e.target.value); setPage(0) }} className="h-10 w-auto" aria-label="Collector">
                <option value="">All collectors</option>
                <option value="none">Unassigned</option>
                {(staff.data ?? []).filter((u) => u.is_active).map((u) => <option key={u.id} value={u.id}>{u.full_name}</option>)}
              </NativeSelect>
            )}
          </div>
        </div>

        {q.error ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : (
          <>
            <DataTable
              columns={columns}
              rows={q.data?.rows}
              loading={q.isLoading}
              rowKey={(l) => l.id}
              onRowClick={(l) => navigate(`/loans/${l.id}`)}
              empty={<EmptyState icon={<ShieldAlert />} title="No overdue loans" description={bucket === 'all' ? 'Every active loan is current. 🎉' : 'No loans fall in this ageing bucket.'} />}
            />
            {rows.length > 0 && (
              <div className="mt-3 flex items-center justify-between rounded-lg bg-red-50/60 px-3 py-2 text-sm">
                <span className="text-muted-foreground">Overdue on this page</span>
                <span className="tabular font-semibold text-red-700">{peso(rows.reduce((a, l) => a + toNumber(l.overdue_amount), 0))}</span>
              </div>
            )}
            <Pagination page={page} pageSize={PAGE_SIZE} count={q.data?.count ?? 0} onPage={setPage} />
          </>
        )}
      </Panel>

      <RecordPaymentDialog open={!!collect} onOpenChange={(o) => !o && setCollect(null)} loanId={collect ?? undefined} />
      <AssignCollectorDialog loan={assign} onClose={() => setAssign(null)} />
    </div>
  )
}

function AssignCollectorDialog({ loan, onClose }: { loan: LoanView | null; onClose: () => void }) {
  const qc = useQueryClient()
  const staff = useQuery({ queryKey: ['staff'], queryFn: usersService.list, enabled: !!loan })
  const [value, setValue] = useState<string>('')
  const [busy, setBusy] = useState(false)
  const options = (staff.data ?? []).filter((s) => s.is_active && (s.role === 'collector' || s.role === 'loan_officer' || s.role === 'admin'))
  const current = loan?.collector_id ?? ''
  useEffect(() => {
    setValue(loan?.collector_id ?? '')
  }, [loan])

  const save = async () => {
    if (!loan) return
    setBusy(true)
    try {
      await loansService.assignStaff(loan.id, null, value || null)
      toast.success(value ? 'Collector assigned' : 'Collector removed')
      for (const key of ['loans', 'loan', 'installments', 'report']) void qc.invalidateQueries({ queryKey: [key] })
      onClose()
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={!!loan} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Assign collector</DialogTitle>
          <DialogDescription>
            {loan && <>Loan {loan.loan_number} · {loan.borrower_name} · <LoanStatusBadge status={loan.status} /></>}
          </DialogDescription>
        </DialogHeader>
        <Field label="Collector" hint="The collector will see this borrower and loan and can record payments. They are notified of the assignment.">
          {staff.isLoading ? <div className="h-10 animate-pulse rounded-md bg-muted" /> : (
            <NativeSelect value={value} onChange={(e) => setValue(e.target.value)}>
              <option value="">— Unassigned —</option>
              {options.map((s) => <option key={s.id} value={s.id}>{s.full_name} · {ROLE_LABEL[s.role ?? ''] ?? s.role}</option>)}
            </NativeSelect>
          )}
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button onClick={() => void save()} disabled={busy || value === current}>{busy && <Spinner />} Save</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
