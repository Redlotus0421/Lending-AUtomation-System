// Shared UI helpers for dashboard, reports and payments pages.
import { useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ChevronDown, Download, FileSpreadsheet, FileText, Printer, Sheet } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { LoanStatusBadge, NativeSelect, Spinner } from '@/components/common'
import { addDays, addMonths, fmtDate, num, peso, startOfMonth, todayManila } from '@/lib/format'
import { errorMessage } from '@/lib/errors'
import type { ReportColumn } from '@/types/db'
import { cn } from '@/lib/utils'

export type RangePreset = 'today' | 'this_week' | 'this_month' | 'last_month' | 'last_3' | 'last_6' | 'last_12' | 'ytd' | 'custom'

export const PRESET_LABEL: Record<RangePreset, string> = {
  today: 'Today',
  this_week: 'This week',
  this_month: 'This month',
  last_month: 'Last month',
  last_3: 'Last 3 months',
  last_6: 'Last 6 months',
  last_12: 'Last 12 months',
  ytd: 'Year to date',
  custom: 'Custom range',
}

export type DateRange = { preset: RangePreset; from: string; to: string }

export function presetRange(preset: RangePreset, today = todayManila()): { from: string; to: string } {
  switch (preset) {
    case 'today':
      return { from: today, to: today }
    case 'this_week': {
      const d = new Date(`${today}T12:00:00Z`)
      const dow = (d.getUTCDay() + 6) % 7 // Monday start
      return { from: addDays(today, -dow), to: today }
    }
    case 'this_month':
      return { from: startOfMonth(today), to: today }
    case 'last_month': {
      const first = addMonths(startOfMonth(today), -1)
      return { from: first, to: addDays(startOfMonth(today), -1) }
    }
    case 'last_3':
      return { from: addMonths(startOfMonth(today), -2), to: today }
    case 'last_6':
      return { from: addMonths(startOfMonth(today), -5), to: today }
    case 'last_12':
      return { from: addMonths(startOfMonth(today), -11), to: today }
    case 'ytd':
      return { from: `${today.slice(0, 4)}-01-01`, to: today }
    default:
      return { from: startOfMonth(today), to: today }
  }
}

export function makeRange(preset: RangePreset): DateRange {
  return { preset, ...presetRange(preset) }
}

export function DateRangeFilter({ value, onChange, presets, className }: {
  value: DateRange
  onChange: (r: DateRange) => void
  presets?: RangePreset[]
  className?: string
}) {
  const list = presets ?? ['this_month', 'last_month', 'last_3', 'last_6', 'last_12', 'ytd', 'custom']
  const today = todayManila()
  return (
    <div className={cn('flex flex-wrap items-center gap-2', className)}>
      <NativeSelect
        value={value.preset}
        onChange={(e) => {
          const p = e.target.value as RangePreset
          onChange(p === 'custom' ? { ...value, preset: p } : makeRange(p))
        }}
        className="h-10 w-auto min-w-40"
        aria-label="Date range"
      >
        {list.map((p) => <option key={p} value={p}>{PRESET_LABEL[p]}</option>)}
      </NativeSelect>
      {value.preset === 'custom' ? (
        <div className="flex items-center gap-1.5">
          <Input type="date" value={value.from} max={value.to || today} className="h-10 w-[9.5rem] bg-white"
            onChange={(e) => e.target.value && onChange({ ...value, from: e.target.value })} aria-label="From date" />
          <span className="text-xs text-muted-foreground">to</span>
          <Input type="date" value={value.to} min={value.from} max={today} className="h-10 w-[9.5rem] bg-white"
            onChange={(e) => e.target.value && onChange({ ...value, to: e.target.value })} aria-label="To date" />
        </div>
      ) : (
        <span className="text-xs text-muted-foreground">{fmtDate(value.from)} – {fmtDate(value.to)}</span>
      )}
    </div>
  )
}

/** Dropdown with CSV / Excel / PDF / Print actions. Each handler may be async. */
export function ExportMenu({ onCsv, onXlsx, onPdf, onPrint, disabled, label = 'Export', size = 'default' }: {
  onCsv?: () => unknown
  onXlsx?: () => unknown
  onPdf?: () => unknown
  onPrint?: () => unknown
  disabled?: boolean
  label?: string
  size?: 'default' | 'sm'
}) {
  const [busy, setBusy] = useState(false)
  const run = async (fn?: () => unknown) => {
    if (!fn) return
    setBusy(true)
    try {
      await fn()
    } catch (e) {
      toast.error(errorMessage(e, 'Export failed'))
    } finally {
      setBusy(false)
    }
  }
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size={size} disabled={disabled || busy} className="bg-white">
          {busy ? <Spinner /> : <Download />} {label} <ChevronDown className="opacity-60" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-48">
        <DropdownMenuLabel className="text-xs text-muted-foreground">Download as</DropdownMenuLabel>
        {onPdf && <DropdownMenuItem onClick={() => void run(onPdf)}><FileText /> PDF</DropdownMenuItem>}
        {onXlsx && <DropdownMenuItem onClick={() => void run(onXlsx)}><FileSpreadsheet /> Excel (.xlsx)</DropdownMenuItem>}
        {onCsv && <DropdownMenuItem onClick={() => void run(onCsv)}><Sheet /> CSV</DropdownMenuItem>}
        {onPrint && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={() => void run(onPrint)}><Printer /> Print</DropdownMenuItem>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/** Screen formatting for a report cell. */
export function formatReportCell(col: ReportColumn, value: unknown, row?: Record<string, unknown>): ReactNode {
  if (value === null || value === undefined || value === '') {
    return col.type === 'money' ? <span className="text-muted-foreground">{peso(0)}</span> : <span className="text-muted-foreground">—</span>
  }
  switch (col.type) {
    case 'money':
      return peso(value)
    case 'number':
      return num(value, Number.isInteger(Number(value)) ? 0 : 2)
    case 'percent':
      return `${Number(value).toLocaleString('en-PH', { maximumFractionDigits: 1 })}%`
    case 'date':
      return fmtDate(String(value))
    case 'status':
      return <LoanStatusBadge status={String(value)} />
    default:
      if (col.key === 'loan_number' && row?.loan_id) {
        return <Link to={`/loans/${row.loan_id}`} className="font-medium text-blue-600 hover:underline" onClick={(e) => e.stopPropagation()}>{String(value)}</Link>
      }
      if (col.key === 'payment_number' && row?.payment_id) {
        return <Link to={`/payments/${row.payment_id}`} className="font-medium text-blue-600 hover:underline" onClick={(e) => e.stopPropagation()}>{String(value)}</Link>
      }
      return String(value)
  }
}

export function MoneyCell({ value, className }: { value: unknown; className?: string }) {
  return <span className={cn('tabular whitespace-nowrap', className)}>{peso(value)}</span>
}

export function telLink(mobile: string | null | undefined) {
  return mobile ? `tel:${mobile}` : undefined
}

/** SMS deep link with a bilingual (English/Filipino) payment reminder. */
export function smsReminderLink(p: { mobile: string | null | undefined; name: string; amount: unknown; dueDate: string; loanNumber: string; lender?: string; overdue?: boolean }) {
  if (!p.mobile) return undefined
  const first = p.name.split(' ')[0]
  const when = fmtDate(p.dueDate)
  const amt = peso(p.amount)
  const body = p.overdue
    ? `Hi ${first}, this is ${p.lender ?? 'your lender'}. Your payment of ${amt} for loan ${p.loanNumber} was due on ${when} and is now overdue. Paki-settle po sa lalong madaling panahon. Salamat po!`
    : `Hi ${first}, this is ${p.lender ?? 'your lender'}. Friendly reminder: your payment of ${amt} for loan ${p.loanNumber} is due on ${when}. Paalala po na bayaran sa takdang araw. Salamat po!`
  // `?&body=` works on both iOS and Android
  return `sms:${p.mobile}?&body=${encodeURIComponent(body)}`
}
