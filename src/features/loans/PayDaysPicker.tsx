// Semi-monthly pay days, e.g. the borrower is paid on the 5th and 20th. Built for staff who are not
// comfortable with forms: tap a common pair, or tap two days on a small calendar, then tap the
// first payment date. The server repeats the same date rules and computes the schedule.
import { useEffect, useState } from 'react'
import { CalendarDays, Check } from 'lucide-react'
import { cn } from '@/lib/utils'
import { todayManila } from '@/lib/format'

export type PayDays = [number, number]

const PRESETS: PayDays[] = [[15, 30], [5, 20], [10, 25]]
export const DEFAULT_PAY_DAYS: PayDays = [15, 30]

export const ordinal = (n: number) => {
  const t = n % 100
  return n + (t >= 11 && t <= 13 ? 'th' : n % 10 === 1 ? 'st' : n % 10 === 2 ? 'nd' : n % 10 === 3 ? 'rd' : 'th')
}
export const payDaysLabel = (d: PayDays) => `${ordinal(d[0])} & ${ordinal(d[1])}`

/** Problem with a pair of days, or null when the server will accept it (same rule as compute_loan_schedule). */
export function payDaysError(d: number[]): string | null {
  if (d.length !== 2) return 'Tap two days'
  const [a, b] = [...d].sort((x, y) => x - y)
  if (b - a < 7) return 'The two pay days must be at least 7 days apart'
  if (b - a > 23) return 'The two pay days must be at most 23 days apart'
  return null
}

const iso = (y: number, m: number, d: number) => `${y}-${String(m + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`
const lastDay = (y: number, m: number) => new Date(Date.UTC(y, m + 1, 0)).getUTCDate()

/** The next `count` pay dates, starting with the first one at least 10 days after `from` (YYYY-MM-DD). */
export function nextPayDates(days: PayDays, from = todayManila(), count = 4): string[] {
  const [y0, m0, d0] = from.split('-').map(Number)
  const min = new Date(Date.UTC(y0, m0 - 1, d0 + 10)).toISOString().slice(0, 10)
  const out: string[] = []
  for (let i = 0; out.length < count && i < 24; i++) {
    const y = y0 + Math.floor((m0 - 1 + i) / 12)
    const m = (m0 - 1 + i) % 12
    for (const d of days) {
      const date = iso(y, m, Math.min(d, lastDay(y, m)))
      if (date >= min && out.length < count && !out.includes(date)) out.push(date)
    }
  }
  return out
}

const fmtChip = (v: string) => new Date(`${v}T12:00:00Z`).toLocaleDateString('en-PH', { timeZone: 'UTC', weekday: 'short', month: 'short', day: 'numeric' })

export function PayDaysPicker({ value, onChange, firstDue, onFirstDueChange, releaseDate }: {
  value: PayDays
  /** loan release date (YYYY-MM-DD); today when omitted */
  releaseDate?: string
  onChange: (d: PayDays) => void
  /** chosen first payment date, or null for the earliest one */
  firstDue: string | null
  onFirstDueChange: (d: string | null) => void
}) {
  const isPreset = PRESETS.some((p) => p[0] === value[0] && p[1] === value[1])
  const [custom, setCustom] = useState(!isPreset)
  const [picking, setPicking] = useState<number[]>(isPreset ? [] : [...value])
  const pickErr = picking.length === 2 ? payDaysError(picking) : null
  const options = nextPayDates(value, releaseDate || undefined)
  const first = firstDue && options.includes(firstDue) ? firstDue : options[0]
  // a picked date that is no longer offered (other pay days or release date) goes back to the earliest
  const stale = !!firstDue && !options.includes(firstDue)
  useEffect(() => { if (stale) onFirstDueChange(null) }, [stale]) // eslint-disable-line react-hooks/exhaustive-deps

  const choose = (d: PayDays) => { onChange(d); onFirstDueChange(null) }
  const tapDay = (d: number) => {
    const next = picking.includes(d) ? picking.filter((x) => x !== d) : picking.length >= 2 ? [d] : [...picking, d]
    setPicking(next)
    if (next.length === 2 && !payDaysError(next)) choose([...next].sort((a, b) => a - b) as PayDays)
  }

  return (
    <div className="space-y-4 rounded-xl border bg-slate-50/60 p-3 sm:p-4">
      <div>
        <p className="flex items-center gap-1.5 text-sm font-medium"><CalendarDays className="size-4 text-[#2563EB]" /> Pay days — when does the borrower get paid?</p>
        <p className="text-xs text-muted-foreground">Payments fall due on these two days every month.</p>
      </div>

      <div role="radiogroup" aria-label="Pay days" className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {PRESETS.map((p) => {
          const on = !custom && p[0] === value[0] && p[1] === value[1]
          return (
            <button key={p.join('-')} type="button" role="radio" aria-checked={on}
              onClick={() => { setCustom(false); setPicking([]); choose(p) }}
              className={cn('h-12 rounded-lg border-2 bg-white px-3 text-sm font-semibold transition', on ? 'border-[#2563EB] bg-blue-50 text-[#142B4A]' : 'border-slate-200 hover:border-slate-300')}>
              {payDaysLabel(p)}
            </button>
          )
        })}
        <button type="button" role="radio" aria-checked={custom} onClick={() => { setCustom(true); setPicking(isPreset ? [] : [...value]) }}
          className={cn('h-12 rounded-lg border-2 bg-white px-3 text-sm font-semibold transition', custom ? 'border-[#2563EB] bg-blue-50 text-[#142B4A]' : 'border-slate-200 hover:border-slate-300')}>
          Other days
        </button>
      </div>

      {custom && (
        <div className="rounded-lg border bg-white p-3">
          <p className="mb-2 text-xs font-medium text-slate-600">
            {picking.length === 0 ? 'Tap the first pay day' : picking.length === 1 ? `Now tap the second pay day (first: ${ordinal(picking[0])})` : pickErr ?? `Every ${payDaysLabel([...picking].sort((a, b) => a - b) as PayDays)}`}
          </p>
          <div className="grid grid-cols-7 gap-1.5">
            {Array.from({ length: 31 }, (_, i) => i + 1).map((d) => {
              const on = picking.includes(d)
              return (
                <button key={d} type="button" onClick={() => tapDay(d)} aria-pressed={on} aria-label={`Day ${d}`}
                  className={cn('grid h-10 place-items-center rounded-lg text-sm font-medium transition', on ? 'bg-[#2563EB] text-white' : 'bg-slate-100 text-slate-700 hover:bg-slate-200')}>
                  {d}
                </button>
              )
            })}
          </div>
          {pickErr && <p className="mt-2 text-xs text-red-600">{pickErr}. Tap a day to start again.</p>}
          <p className="mt-2 text-[11px] text-muted-foreground">Days 29–31 fall on the last day of shorter months (e.g. February 28).</p>
        </div>
      )}

      <div>
        <p className="mb-2 text-sm font-medium">First payment</p>
        <div role="radiogroup" aria-label="First payment date" className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {options.map((d, i) => {
            const on = d === first
            return (
              <button key={d} type="button" role="radio" aria-checked={on} onClick={() => onFirstDueChange(i === 0 ? null : d)}
                className={cn('flex h-14 flex-col items-center justify-center rounded-lg border-2 bg-white text-sm transition', on ? 'border-[#2563EB] bg-blue-50 text-[#142B4A]' : 'border-slate-200 hover:border-slate-300')}>
                <span className="flex items-center gap-1 font-semibold">{on && <Check className="size-3.5" />}{fmtChip(d)}</span>
                {i === 0 && <span className="text-[10px] text-muted-foreground">Earliest</span>}
              </button>
            )
          })}
        </div>
      </div>
    </div>
  )
}
