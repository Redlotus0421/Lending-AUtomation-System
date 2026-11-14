import { useEffect, useState, type ReactNode } from 'react'
import { AlertTriangle, ChevronLeft, ChevronRight, Inbox, Loader2, Search, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { AGREEMENT_STATUS, INSTALLMENT_STATUS, LOAN_STATUS, type Tone } from '@/lib/constants'

// ---------------------------------------------------------------------------
// Status badges
// ---------------------------------------------------------------------------
const TONES: Record<Tone, string> = {
  slate: 'bg-slate-100 text-slate-700 ring-slate-200',
  amber: 'bg-amber-50 text-amber-800 ring-amber-200',
  blue: 'bg-blue-50 text-blue-700 ring-blue-200',
  violet: 'bg-violet-50 text-violet-700 ring-violet-200',
  teal: 'bg-teal-50 text-teal-700 ring-teal-200',
  green: 'bg-green-50 text-green-700 ring-green-200',
  red: 'bg-red-50 text-red-700 ring-red-200',
  navy: 'bg-[#142B4A]/10 text-[#142B4A] ring-[#142B4A]/20',
}

export function Pill({ tone = 'slate', children, className, dot = true }: { tone?: Tone; children: ReactNode; className?: string; dot?: boolean }) {
  return (
    <span className={cn('inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset', TONES[tone], className)}>
      {dot && <span className="size-1.5 rounded-full bg-current opacity-70" />}
      {children}
    </span>
  )
}

export function LoanStatusBadge({ status, className }: { status: string; className?: string }) {
  const s = LOAN_STATUS[status as keyof typeof LOAN_STATUS] ?? { label: status, tone: 'slate' as Tone }
  return <Pill tone={s.tone} className={className}>{s.label}</Pill>
}

export function InstallmentStatusBadge({ status }: { status: string }) {
  const s = INSTALLMENT_STATUS[status] ?? { label: status, tone: 'slate' as Tone }
  return <Pill tone={s.tone}>{s.label}</Pill>
}

export function AgreementStatusBadge({ status }: { status: string }) {
  const s = AGREEMENT_STATUS[status] ?? { label: status, tone: 'slate' as Tone }
  return <Pill tone={s.tone}>{s.label}</Pill>
}

// ---------------------------------------------------------------------------
// Page scaffolding
// ---------------------------------------------------------------------------
export function PageHeader({ title, description, actions, back }: { title: ReactNode; description?: ReactNode; actions?: ReactNode; back?: ReactNode }) {
  return (
    <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div className="min-w-0">
        {back}
        <h1 className="truncate text-xl font-semibold tracking-tight text-[#0f1b2d] sm:text-2xl">{title}</h1>
        {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}

export function Panel({ title, description, actions, children, className, bodyClassName }: {
  title?: ReactNode; description?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; bodyClassName?: string
}) {
  return (
    <section className={cn('rounded-xl border bg-card shadow-card', className)}>
      {(title || actions) && (
        <div className="flex flex-wrap items-start justify-between gap-2 border-b px-4 py-3 sm:px-5">
          <div>
            {title && <h2 className="text-sm font-semibold text-foreground">{title}</h2>}
            {description && <p className="mt-0.5 text-xs text-muted-foreground">{description}</p>}
          </div>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </div>
      )}
      <div className={cn('p-4 sm:p-5', bodyClassName)}>{children}</div>
    </section>
  )
}

export function StatCard({ label, value, hint, icon, tone = 'blue', onClick, className }: {
  label: string; value: ReactNode; hint?: ReactNode; icon?: ReactNode; tone?: 'blue' | 'teal' | 'navy' | 'green' | 'amber' | 'red'; onClick?: () => void; className?: string
}) {
  const tones = {
    blue: 'bg-blue-50 text-blue-600', teal: 'bg-teal-50 text-teal-600', navy: 'bg-[#142B4A]/10 text-[#142B4A]',
    green: 'bg-green-50 text-green-600', amber: 'bg-amber-50 text-amber-600', red: 'bg-red-50 text-red-600',
  }
  return (
    <div
      onClick={onClick}
      className={cn('rounded-xl border bg-card p-4 shadow-card transition', onClick && 'cursor-pointer hover:border-blue-200 hover:shadow-md', className)}
    >
      <div className="flex items-start justify-between gap-3">
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</p>
        {icon && <span className={cn('grid size-8 shrink-0 place-items-center rounded-lg [&_svg]:size-4', tones[tone])}>{icon}</span>}
      </div>
      <p className="tabular mt-2 text-xl font-semibold tracking-tight text-[#0f1b2d] sm:text-2xl">{value}</p>
      {hint && <p className="mt-1 text-xs text-muted-foreground">{hint}</p>}
    </div>
  )
}

export function KeyValue({ items, columns = 2, className }: { items: { label: ReactNode; value: ReactNode; hidden?: boolean }[]; columns?: 1 | 2 | 3 | 4; className?: string }) {
  const cols = { 1: '', 2: 'sm:grid-cols-2', 3: 'sm:grid-cols-2 lg:grid-cols-3', 4: 'sm:grid-cols-2 lg:grid-cols-4' }[columns]
  return (
    <dl className={cn('grid grid-cols-1 gap-x-6 gap-y-3', cols, className)}>
      {items.filter((i) => !i.hidden).map((i, idx) => (
        <div key={idx} className="min-w-0">
          <dt className="text-xs text-muted-foreground">{i.label}</dt>
          <dd className="mt-0.5 break-words text-sm font-medium text-foreground">{i.value ?? '—'}</dd>
        </div>
      ))}
    </dl>
  )
}

export function EmptyState({ icon, title, description, action, className }: { icon?: ReactNode; title: string; description?: ReactNode; action?: ReactNode; className?: string }) {
  return (
    <div className={cn('flex flex-col items-center justify-center px-6 py-12 text-center', className)}>
      <div className="mb-3 grid size-12 place-items-center rounded-full bg-muted text-muted-foreground [&_svg]:size-5">{icon ?? <Inbox />}</div>
      <p className="text-sm font-semibold">{title}</p>
      {description && <p className="mt-1 max-w-sm text-sm text-muted-foreground">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const msg = (error as Error)?.message ?? String(error)
  return (
    <div className="flex flex-col items-center justify-center rounded-xl border border-red-200 bg-red-50/50 px-6 py-10 text-center">
      <AlertTriangle className="mb-2 size-6 text-red-500" />
      <p className="text-sm font-semibold text-red-800">Could not load data</p>
      <p className="mt-1 max-w-md text-sm text-red-700/80">{msg}</p>
      {onRetry && <Button variant="outline" size="sm" className="mt-4" onClick={onRetry}>Try again</Button>}
    </div>
  )
}

export function Spinner({ className }: { className?: string }) {
  return <Loader2 className={cn('size-4 animate-spin', className)} />
}

export function PageLoader({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="flex min-h-[40vh] items-center justify-center gap-2 text-sm text-muted-foreground">
      <Spinner /> {label}
    </div>
  )
}

export function TableSkeleton({ rows = 6 }: { rows?: number }) {
  return (
    <div className="space-y-2">
      {Array.from({ length: rows }).map((_, i) => <Skeleton key={i} className="h-10 w-full" />)}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Search / pagination
// ---------------------------------------------------------------------------
export function useDebounced<T>(value: T, delay = 300): T {
  const [v, setV] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setV(value), delay)
    return () => clearTimeout(t)
  }, [value, delay])
  return v
}

export function SearchInput({ value, onChange, placeholder = 'Search…', className }: { value: string; onChange: (v: string) => void; placeholder?: string; className?: string }) {
  return (
    <div className={cn('relative', className)}>
      <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
      <Input value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} className="h-10 bg-white pl-9 pr-8" />
      {value && (
        <button type="button" onClick={() => onChange('')} className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1 text-muted-foreground hover:bg-muted" aria-label="Clear search">
          <X className="size-3.5" />
        </button>
      )}
    </div>
  )
}

export function Pagination({ page, pageSize, count, onPage }: { page: number; pageSize: number; count: number; onPage: (p: number) => void }) {
  const pages = Math.max(1, Math.ceil(count / pageSize))
  if (count <= pageSize) return count ? <p className="px-1 pt-3 text-xs text-muted-foreground">{count} record{count === 1 ? '' : 's'}</p> : null
  return (
    <div className="flex items-center justify-between gap-2 px-1 pt-3 text-xs text-muted-foreground">
      <span>
        {page * pageSize + 1}–{Math.min(count, (page + 1) * pageSize)} of {count}
      </span>
      <div className="flex items-center gap-1">
        <Button variant="outline" size="icon" className="size-8" disabled={page === 0} onClick={() => onPage(page - 1)} aria-label="Previous page"><ChevronLeft /></Button>
        <span className="px-2">Page {page + 1} / {pages}</span>
        <Button variant="outline" size="icon" className="size-8" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)} aria-label="Next page"><ChevronRight /></Button>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Responsive data table: table on desktop, cards on mobile
// ---------------------------------------------------------------------------
export type Column<T> = {
  key: string
  header: ReactNode
  cell: (row: T) => ReactNode
  className?: string
  align?: 'left' | 'right' | 'center'
  /** hide on the mobile card */
  mobile?: 'title' | 'subtitle' | 'value' | 'meta' | false
}

export function DataTable<T>({ columns, rows, rowKey, onRowClick, empty, loading, dense, footer }: {
  columns: Column<T>[]
  rows: T[] | undefined
  rowKey: (row: T) => string
  onRowClick?: (row: T) => void
  empty?: ReactNode
  loading?: boolean
  dense?: boolean
  footer?: ReactNode
}) {
  if (loading) return <TableSkeleton />
  if (!rows?.length) return <>{empty ?? <EmptyState title="Nothing here yet" />}</>
  const title = columns.find((c) => c.mobile === 'title')
  const subtitle = columns.find((c) => c.mobile === 'subtitle')
  const value = columns.find((c) => c.mobile === 'value')
  const metas = columns.filter((c) => c.mobile === 'meta')
  return (
    <>
      <div className="hidden overflow-x-auto md:block">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b text-left text-xs font-medium uppercase tracking-wide text-muted-foreground">
              {columns.map((c) => (
                <th key={c.key} className={cn('whitespace-nowrap px-3 py-2.5 font-medium', c.align === 'right' && 'text-right', c.align === 'center' && 'text-center', c.className)}>{c.header}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={rowKey(r)} onClick={onRowClick ? () => onRowClick(r) : undefined}
                className={cn('border-b last:border-0', onRowClick && 'cursor-pointer hover:bg-muted/50')}>
                {columns.map((c) => (
                  <td key={c.key} className={cn('px-3', dense ? 'py-2' : 'py-3', c.align === 'right' && 'text-right tabular', c.align === 'center' && 'text-center', c.className)}>{c.cell(r)}</td>
                ))}
              </tr>
            ))}
          </tbody>
          {footer && <tfoot>{footer}</tfoot>}
        </table>
      </div>
      <div className="space-y-2 md:hidden">
        {rows.map((r) => (
          <div key={rowKey(r)} onClick={onRowClick ? () => onRowClick(r) : undefined}
            className={cn('rounded-lg border bg-white p-3', onRowClick && 'active:bg-muted/60')}>
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                {title && <div className="truncate text-sm font-semibold">{title.cell(r)}</div>}
                {subtitle && <div className="mt-0.5 truncate text-xs text-muted-foreground">{subtitle.cell(r)}</div>}
              </div>
              {value && <div className="tabular shrink-0 text-right text-sm font-semibold">{value.cell(r)}</div>}
            </div>
            {metas.length > 0 && (
              <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                {metas.map((m) => <span key={m.key} className="inline-flex items-center gap-1">{m.cell(r)}</span>)}
              </div>
            )}
          </div>
        ))}
      </div>
    </>
  )
}

export function Field({ label, error, hint, children, required, className }: { label: ReactNode; error?: string; hint?: ReactNode; children: ReactNode; required?: boolean; className?: string }) {
  return (
    <div className={cn('space-y-1.5', className)}>
      <label className="text-sm font-medium text-foreground">
        {label}
        {required && <span className="ml-0.5 text-red-500">*</span>}
      </label>
      {children}
      {error ? <p className="text-xs text-red-600">{error}</p> : hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
    </div>
  )
}

/** Native select styled like shadcn inputs (works great on mobile). */
export function NativeSelect({ className, children, ...props }: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      className={cn('h-10 w-full rounded-md border border-input bg-white px-3 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:opacity-50', className)}
      {...props}
    >
      {children}
    </select>
  )
}
