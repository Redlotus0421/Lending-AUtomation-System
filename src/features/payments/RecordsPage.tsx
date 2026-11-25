// Payment Records (Collections): pick a borrower, see every scheduled installment of a loan next to
// what was actually paid and when, plus the loan's payment history. Read-only: every figure comes
// from the posted payments and the loan's schedule, so it always matches Payments & Collections.
import { useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, CalendarCheck2, ClipboardList, HandCoins, Search, UserRound, Wallet } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DataTable, EmptyState, ErrorState, Panel, PageHeader, Pill, SearchInput, Spinner, StatCard, useDebounced, LoanStatusBadge, type Column,
} from '@/components/common'
import { useAuth } from '@/features/auth/AuthProvider'
import { BorrowerAvatar } from '@/features/borrowers/shared'
import { ExportMenu } from '@/features/reports/shared'
import { recordsService, type RecordPayment } from '@/services/records'
import { exportCsv, exportPdfTable, printElement, type ExportColumn } from '@/lib/export'
import { FREQUENCY_LABEL, fmtDate, peso, phMobile, todayManila, toNumber } from '@/lib/format'
import { cn } from '@/lib/utils'
import type { Borrower, LoanView } from '@/types/db'
import { buildRecordRows, RECORD_STATUS, type RecordRow } from './records'

const EXPORT_COLUMNS: ExportColumn[] = [
  { key: 'no', label: '#', type: 'number' },
  { key: 'due_date', label: 'Due date', type: 'date' },
  { key: 'scheduled', label: 'Scheduled amount', type: 'money', total: true },
  { key: 'paid', label: 'Amount paid', type: 'money', total: true },
  { key: 'paid_date', label: 'Payment date', type: 'date' },
  { key: 'payments', label: 'Payment #' },
  { key: 'status', label: 'Status' },
  { key: 'remaining', label: 'Remaining', type: 'money', total: true },
]

const receiptNo = (p: RecordPayment) => {
  const r = Array.isArray(p.payment_receipts) ? p.payment_receipts[0] : p.payment_receipts
  return r?.receipt_number ?? null
}

export default function RecordsPage() {
  const [params, setParams] = useSearchParams()
  const borrowerId = params.get('borrower')
  const loanParam = params.get('loan')
  const pick = (b: Borrower) => setParams({ borrower: b.id })

  const borrowerQ = useQuery({ queryKey: ['borrower', borrowerId, 'records'], queryFn: () => recordsService.borrower(borrowerId!), enabled: !!borrowerId })
  const loansQ = useQuery({ queryKey: ['loans', 'records', borrowerId], queryFn: () => recordsService.loans(borrowerId!), enabled: !!borrowerId })
  const loans = loansQ.data ?? []
  // default: the borrower's active (or defaulted) loan, otherwise the most recent one
  const loan = loans.find((l) => l.id === loanParam) ?? loans.find((l) => l.status === 'active' || l.status === 'defaulted') ?? loans[0]

  return (
    <div className="space-y-5">
      <PageHeader title="Payment Records"
        description="A borrower's full repayment schedule next to what was actually paid. Figures come straight from posted payments — nothing is entered here." />

      {!borrowerId ? (
        <BorrowerPicker onPick={pick} />
      ) : borrowerQ.error ? (
        <ErrorState error={borrowerQ.error} onRetry={() => void borrowerQ.refetch()} />
      ) : (
        <>
          <Panel>
            <div className="flex flex-wrap items-center gap-3">
              {borrowerQ.data ? <BorrowerAvatar borrower={borrowerQ.data} size={44} /> : <span className="size-11 rounded-full bg-muted" />}
              <div className="min-w-0 flex-1">
                <p className="truncate font-semibold">{borrowerQ.data?.full_name ?? '…'}</p>
                <p className="truncate text-xs text-muted-foreground">
                  {borrowerQ.data ? `${borrowerQ.data.borrower_code} · ${phMobile(borrowerQ.data.mobile_number)}` : ''}
                </p>
              </div>
              <Button variant="outline" className="bg-white" asChild><Link to={`/borrowers/${borrowerId}`}><UserRound /> Profile</Link></Button>
              <Button variant="outline" className="bg-white" onClick={() => setParams({})}><Search /> Change borrower</Button>
            </div>
            {loans.length > 1 && (
              <div role="tablist" aria-label="Loans" className="mt-4 flex gap-2 overflow-x-auto pb-1">
                {loans.map((l) => (
                  <button key={l.id} type="button" role="tab" aria-selected={l.id === loan?.id}
                    onClick={() => setParams({ borrower: borrowerId, loan: l.id })}
                    className={cn('shrink-0 rounded-lg border-2 bg-white px-3 py-2 text-left text-sm transition',
                      l.id === loan?.id ? 'border-[#2563EB] bg-blue-50' : 'border-slate-200 hover:border-slate-300')}>
                    <span className="block font-semibold">{l.loan_number}</span>
                    <span className="block text-xs text-muted-foreground">{peso(l.principal)} · {l.status === 'completed' ? 'Completed' : l.status === 'defaulted' ? 'Defaulted' : 'Active'}</span>
                  </button>
                ))}
              </div>
            )}
          </Panel>

          {loansQ.isLoading ? (
            <div className="grid place-items-center py-16"><Spinner /></div>
          ) : loansQ.error ? (
            <ErrorState error={loansQ.error} onRetry={() => void loansQ.refetch()} />
          ) : !loan ? (
            <Panel><EmptyState icon={<HandCoins />} title="No released loans yet"
              description="Records appear once a loan for this borrower has been released." /></Panel>
          ) : (
            <LoanRecord loan={loan} borrowerName={borrowerQ.data?.full_name ?? loan.borrower_name} />
          )}
        </>
      )}
    </div>
  )
}

function BorrowerPicker({ onPick }: { onPick: (b: Borrower) => void }) {
  const [term, setTerm] = useState('')
  const debounced = useDebounced(term)
  const q = useQuery({ queryKey: ['borrowers', 'records-search', debounced], queryFn: () => recordsService.searchBorrowers(debounced, 12) })
  return (
    <Panel title="Select a borrower" description="Search by name, borrower code or mobile number.">
      <SearchInput value={term} onChange={setTerm} placeholder="e.g. Dela Cruz, BRW-000001, 0917…" className="max-w-xl" />
      <div className="mt-3">
        {q.isLoading ? <div className="grid place-items-center py-10"><Spinner /></div>
          : q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} />
          : !q.data?.length ? <EmptyState icon={<UserRound />} title="No borrowers found" />
          : (
            <ul className="divide-y rounded-lg border bg-white">
              {q.data.map((b) => (
                <li key={b.id}>
                  <button type="button" onClick={() => onPick(b)} className="flex w-full items-center gap-3 px-3 py-2.5 text-left hover:bg-muted/50">
                    <BorrowerAvatar borrower={b} size={36} />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium">{b.full_name}</span>
                      <span className="block truncate text-xs text-muted-foreground">{b.borrower_code} · {phMobile(b.mobile_number)}</span>
                    </span>
                    <ClipboardList className="size-4 shrink-0 text-muted-foreground" />
                  </button>
                </li>
              ))}
            </ul>
          )}
      </div>
    </Panel>
  )
}

function LoanRecord({ loan, borrowerName }: { loan: LoanView; borrowerName: string }) {
  const navigate = useNavigate()
  const { organization } = useAuth()
  const printRef = useRef<HTMLDivElement>(null)
  const today = todayManila()
  // query keys sit under 'installments' / 'payments', which recording or reversing a payment refreshes
  const instQ = useQuery({ queryKey: ['installments', loan.id, 'records'], queryFn: () => recordsService.installments(loan.id) })
  const allocQ = useQuery({ queryKey: ['payments', 'records', 'allocations', loan.id], queryFn: () => recordsService.allocations(loan.id) })
  const payQ = useQuery({ queryKey: ['payments', 'records', 'list', loan.id], queryFn: () => recordsService.payments(loan.id) })

  const rows = useMemo(() => buildRecordRows(instQ.data ?? [], allocQ.data ?? [], today), [instQ.data, allocQ.data, today])
  const live = rows.filter((r) => r.status !== 'rescheduled')
  const counts = live.reduce((a, r) => { a[r.status] = (a[r.status] ?? 0) + 1; return a }, {} as Record<string, number>)
  const paidCount = (counts.paid_on_time ?? 0) + (counts.paid_late ?? 0) + (counts.waived ?? 0)
  const overdueAmount = live.filter((r) => r.status === 'overdue').reduce((a, r) => a + r.remaining, 0)
  const loading = instQ.isLoading || allocQ.isLoading
  const error = instQ.error ?? allocQ.error

  const exportRows = rows.map((r) => ({
    no: r.inst.installment_no, due_date: r.inst.due_date, scheduled: r.scheduled, paid: r.paid, paid_date: r.paidDate,
    payments: r.payments.map((p) => p.payment_number).join(', '),
    status: RECORD_STATUS[r.status].label + (r.days ? ` (${r.days} day${r.days === 1 ? '' : 's'})` : ''),
    remaining: r.remaining,
  }))
  const fileBase = `records-${loan.loan_number}`
  const subtitle = `${borrowerName} · ${loan.loan_number} · ${FREQUENCY_LABEL[loan.repayment_frequency] ?? loan.repayment_frequency} · as of ${fmtDate(today, 'long')}`

  const columns: Column<RecordRow>[] = [
    { key: 'no', header: '#', cell: (r) => <span className="font-medium">#{r.inst.installment_no}</span>, mobile: 'title' },
    { key: 'due', header: 'Due date', cell: (r) => fmtDate(r.inst.due_date), mobile: 'subtitle' },
    { key: 'scheduled', header: 'Scheduled', align: 'right', cell: (r) => peso(r.scheduled), mobile: 'meta' },
    {
      key: 'paid', header: 'Amount paid', align: 'right', mobile: 'value',
      cell: (r) => (
        <span className={cn(r.paid > 0 ? 'font-semibold' : 'text-muted-foreground')}>
          {peso(r.paid)}
          {r.rebate > 0 && <span className="block text-[11px] font-normal text-teal-700">+ {peso(r.rebate)} rebate</span>}
        </span>
      ),
    },
    {
      key: 'date', header: 'Payment date', mobile: 'meta',
      cell: (r) => r.payments.length ? (
        <span className="text-sm">
          {fmtDate(r.paidDate)}
          {r.payments.length > 1 && <span className="block text-[11px] text-muted-foreground">{r.payments.length} payments: {r.payments.map((p) => fmtDate(p.payment_date)).join(', ')}</span>}
        </span>
      ) : <span className="text-muted-foreground">—</span>,
    },
    {
      key: 'status', header: 'Status', mobile: 'meta',
      cell: (r) => (
        <span className="inline-flex flex-col items-start gap-0.5">
          <Pill tone={RECORD_STATUS[r.status].tone}>{RECORD_STATUS[r.status].label}</Pill>
          {r.status === 'paid_late' && <span className="text-[11px] text-muted-foreground">{r.days} day{r.days === 1 ? '' : 's'} late</span>}
          {r.status === 'overdue' && <span className="text-[11px] text-red-700">{r.days} day{r.days === 1 ? '' : 's'} past due{r.paid > 0 ? ' · partly paid' : ''}</span>}
          {r.status === 'upcoming' && r.inst.due_date === today && <span className="text-[11px] text-amber-700">Due today</span>}
        </span>
      ),
    },
    { key: 'remaining', header: 'Remaining', align: 'right', mobile: 'meta', cell: (r) => r.remaining > 0 ? peso(r.remaining) : <span className="text-muted-foreground">{peso(0)}</span> },
  ]

  const payColumns: Column<RecordPayment>[] = [
    { key: 'date', header: 'Date', cell: (p) => fmtDate(p.payment_date), mobile: 'subtitle' },
    {
      key: 'no', header: 'Payment / receipt', mobile: 'title',
      cell: (p) => <span><span className="font-medium">{p.payment_number}</span>{receiptNo(p) && <span className="text-muted-foreground"> · {receiptNo(p)}</span>}</span>,
    },
    {
      key: 'applied', header: 'Applied to', mobile: 'meta',
      cell: (p) => {
        const nos = [...new Set(p.payment_allocations.filter((a) => !a.is_reversal).map((a) => a.loan_installments?.installment_no).filter(Boolean))].sort((a, b) => a! - b!)
        return nos.length ? nos.map((n) => `#${n}`).join(', ') : '—'
      },
    },
    { key: 'type', header: 'Type', mobile: false, cell: (p) => <span className="capitalize text-muted-foreground">{p.payment_type}</span> },
    { key: 'method', header: 'Method', mobile: 'meta', cell: (p) => <span className="capitalize">{p.method.replace(/_/g, ' ')}</span> },
    { key: 'status', header: 'Status', mobile: 'meta', cell: (p) => p.status === 'reversed' ? <Pill tone="red">Reversed</Pill> : <Pill tone="green">Posted</Pill> },
    {
      key: 'amount', header: 'Amount', align: 'right', mobile: 'value',
      cell: (p) => <span className={cn('font-semibold', p.status === 'reversed' && 'text-muted-foreground line-through')}>{peso(p.amount)}</span>,
    },
  ]

  return (
    <div className="space-y-5">
      <section className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatCard label="Total paid" value={peso(loan.total_paid)} hint={`${paidCount} of ${live.length} installments paid`} icon={<Wallet />} tone="green" />
        <StatCard label="Remaining balance" value={peso(loan.total_outstanding)} hint={`Total payable ${peso(toNumber(loan.total_paid) + toNumber(loan.total_outstanding))}`} icon={<HandCoins />} tone="navy" />
        <StatCard label="Overdue" value={peso(overdueAmount)} hint={`${counts.overdue ?? 0} installment${counts.overdue === 1 ? '' : 's'}`} icon={<AlertTriangle />} tone="red" />
        <StatCard label="Paid on time" value={`${counts.paid_on_time ?? 0} / ${(counts.paid_on_time ?? 0) + (counts.paid_late ?? 0)}`}
          hint={`${counts.paid_late ?? 0} paid late · ${(counts.upcoming ?? 0) + (counts.partially_paid ?? 0)} upcoming`} icon={<CalendarCheck2 />} tone="teal" />
      </section>

      <Panel
        title={<span className="flex flex-wrap items-center gap-2">Repayment schedule <LoanStatusBadge status={loan.status} /></span>}
        description={`${loan.loan_number} · ${peso(loan.principal)} · ${FREQUENCY_LABEL[loan.repayment_frequency] ?? loan.repayment_frequency} · released ${fmtDate(loan.actual_release_date ?? loan.release_date)}`}
        actions={
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" className="bg-white" asChild><Link to={`/loans/${loan.id}`}>Open loan</Link></Button>
            <ExportMenu disabled={loading || !rows.length}
              onCsv={() => exportCsv(fileBase, EXPORT_COLUMNS, exportRows)}
              onPdf={() => exportPdfTable(fileBase, { title: 'Payment record', subtitle, organization: organization?.legal_name || organization?.name, columns: EXPORT_COLUMNS, rows: exportRows, orientation: 'landscape' })}
              onPrint={() => printElement(printRef.current, `Payment record ${loan.loan_number}`)} />
          </div>
        }>
        <div ref={printRef}>
          {error ? <ErrorState error={error} onRetry={() => { void instQ.refetch(); void allocQ.refetch() }} /> : (
            <DataTable columns={columns} rows={rows} rowKey={(r) => r.inst.id} loading={loading} dense
              empty={<EmptyState title="No schedule yet" />}
              footer={
                <tr className="border-t bg-muted/40 font-semibold">
                  <td className="px-3 py-2.5" colSpan={2}>Total</td>
                  <td className="tabular px-3 py-2.5 text-right">{peso(live.reduce((a, r) => a + r.scheduled, 0))}</td>
                  <td className="tabular px-3 py-2.5 text-right">{peso(rows.reduce((a, r) => a + r.paid, 0))}</td>
                  <td className="px-3 py-2.5" colSpan={2} />
                  <td className="tabular px-3 py-2.5 text-right">{peso(rows.reduce((a, r) => a + r.remaining, 0))}</td>
                </tr>
              } />
          )}
          {rows.some((r) => r.status === 'rescheduled') && (
            <p className="mt-3 text-xs text-muted-foreground">Rescheduled installments were replaced by the amended schedule; their payments stay on record.</p>
          )}
        </div>
      </Panel>

      <Panel title="Payment history" description="Every payment recorded for this loan, newest first. Tap a payment to see its receipt.">
        {payQ.error ? <ErrorState error={payQ.error} onRetry={() => void payQ.refetch()} /> : (
          <DataTable columns={payColumns} rows={payQ.data} rowKey={(p) => p.id} loading={payQ.isLoading} dense
            onRowClick={(p) => navigate(`/payments/${p.id}`)}
            empty={<EmptyState icon={<Wallet />} title="No payments recorded yet" />} />
        )}
      </Panel>
    </div>
  )
}
