// UI formatting helpers (screen uses the ₱ sign; documents use "PHP").
export {
  amountInWords, dateTimeManila, durationLabel, longDate, METHOD_LABEL, PERIOD_LABEL, PERIOD_PLURAL, shortDate,
  SIGNER_LABEL, todayManila, toNumber, trimNumber,
} from '../../supabase/functions/_shared/format.ts'
import { toNumber } from '../../supabase/functions/_shared/format.ts'

export function peso(v: unknown, opts: { decimals?: number; compact?: boolean } = {}): string {
  const n = toNumber(v)
  if (opts.compact && Math.abs(n) >= 1000) {
    return '₱' + new Intl.NumberFormat('en-PH', { notation: 'compact', maximumFractionDigits: 1 }).format(n)
  }
  const d = opts.decimals ?? 2
  return (n < 0 ? '-₱' : '₱') + Math.abs(n).toLocaleString('en-PH', { minimumFractionDigits: d, maximumFractionDigits: d })
}

export function num(v: unknown, decimals = 0): string {
  return toNumber(v).toLocaleString('en-PH', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })
}

export function pct(v: unknown, decimals = 2): string {
  if (v === null || v === undefined || v === '') return '—'
  return `${toNumber(v).toLocaleString('en-PH', { maximumFractionDigits: decimals })}%`
}

/** Format a YYYY-MM-DD business date or an ISO timestamp for display. */
export function fmtDate(v: string | null | undefined, style: 'short' | 'long' | 'numeric' = 'short'): string {
  if (!v) return '—'
  const isDateOnly = v.length <= 10
  const d = isDateOnly ? new Date(`${v}T12:00:00Z`) : new Date(v)
  if (Number.isNaN(d.getTime())) return v
  const opts: Intl.DateTimeFormatOptions =
    style === 'long' ? { year: 'numeric', month: 'long', day: 'numeric' }
      : style === 'numeric' ? { year: 'numeric', month: '2-digit', day: '2-digit' }
        : { year: 'numeric', month: 'short', day: 'numeric' }
  return d.toLocaleDateString('en-PH', { ...opts, timeZone: isDateOnly ? 'UTC' : 'Asia/Manila' })
}

export function fmtDateTime(v: string | null | undefined): string {
  if (!v) return '—'
  return new Date(v).toLocaleString('en-PH', {
    timeZone: 'Asia/Manila', year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
  })
}

export function timeAgo(v: string | null | undefined): string {
  if (!v) return ''
  const s = Math.round((Date.now() - new Date(v).getTime()) / 1000)
  if (s < 60) return 'just now'
  if (s < 3600) return `${Math.floor(s / 60)}m ago`
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`
  if (s < 86400 * 7) return `${Math.floor(s / 86400)}d ago`
  return fmtDate(v)
}

/** +639171234567 -> 0917 123 4567 */
export function phMobile(v: string | null | undefined): string {
  if (!v) return '—'
  const m = v.match(/^\+63(9\d{2})(\d{3})(\d{4})$/)
  return m ? `0${m[1]} ${m[2]} ${m[3]}` : v
}

export function normalizePhMobile(v: string): string {
  const d = v.replace(/\D/g, '')
  if (/^09\d{9}$/.test(d)) return `+63${d.slice(1)}`
  if (/^639\d{9}$/.test(d)) return `+${d}`
  if (/^9\d{9}$/.test(d)) return `+63${d}`
  return v
}

export function initials(name: string | null | undefined): string {
  if (!name) return '?'
  const parts = name.trim().split(/\s+/)
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase()
}

export function titleCase(v: string | null | undefined): string {
  if (!v) return ''
  return v.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase())
}

/** Add days to a YYYY-MM-DD date string */
export function addDays(date: string, days: number): string {
  const d = new Date(`${date}T12:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

export function addMonths(date: string, months: number): string {
  const d = new Date(`${date}T12:00:00Z`)
  const day = d.getUTCDate()
  d.setUTCDate(1)
  d.setUTCMonth(d.getUTCMonth() + months)
  const last = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).getUTCDate()
  d.setUTCDate(Math.min(day, last))
  return d.toISOString().slice(0, 10)
}

export function startOfMonth(date: string): string {
  return `${date.slice(0, 7)}-01`
}
