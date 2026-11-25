// Collection day for monthly, weekly and daily loans, so the schedule can follow the borrower's payday
// (semi-monthly loans use PayDaysPicker). Staff tap the day the borrower is paid, then the first
// collection date; every later collection repeats from that date on the server (lf_due_date).
import { useEffect, useState } from 'react'
import { CalendarDays, Check } from 'lucide-react'
import { cn } from '@/lib/utils'
import { addDays, addMonths, todayManila } from '@/lib/format'
import { ordinal } from './PayDaysPicker'

type Freq = 'monthly' | 'weekly' | 'daily'
const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const WEEKDAY_NAMES = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']

const dayOf = (d: string) => Number(d.slice(8, 10))
const weekdayOf = (d: string) => new Date(`${d}T12:00:00Z`).getUTCDay()
const lastDay = (y: number, m: number) => new Date(Date.UTC(y, m + 1, 0)).getUTCDate()
const fmtChip = (v: string) => new Date(`${v}T12:00:00Z`).toLocaleDateString('en-PH', { timeZone: 'UTC', weekday: 'short', month: 'short', day: 'numeric' })

/** The server's first due date when none is chosen (lf_default_first_due). */
export function defaultFirstDue(freq: string, release = todayManila()): string {
  if (freq === 'monthly') return addMonths(release, 1)
  if (freq === 'weekly') return addDays(release, 7)
  return addDays(release, 1)
}

/**
 * First collection dates to offer. Monthly: the next 3 months that have that day, starting at least a
 * week after release (a 31st is only offered in 31-day months; later months fall on the last day).
 * Weekly: the next 3 of that weekday. Daily: the next 4 days.
 */
export function collectionDates(freq: string, day: number, release = todayManila()): string[] {
  const out: string[] = []
  if (freq === 'monthly') {
    const min = addDays(release, 7)
    const [y0, m0] = release.split('-').map(Number)
    for (let i = 0; out.length < 3 && i < 24; i++) {
      const y = y0 + Math.floor((m0 - 1 + i) / 12)
      const m = (m0 - 1 + i) % 12
      if (day > lastDay(y, m)) continue
      const d = `${y}-${String(m + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`
      if (d >= min) out.push(d)
    }
  } else if (freq === 'weekly') {
    let d = addDays(release, 1)
    while (weekdayOf(d) !== day) d = addDays(d, 1)
    for (let i = 0; i < 3; i++) out.push(addDays(d, i * 7))
  } else {
    for (let i = 1; i <= 4; i++) out.push(addDays(release, i))
  }
  return out
}

/** A saved first collection date that can still be used (after today), else null for the server default. */
export function validFirstDue(firstDue: string | null, release = todayManila()): string | null {
  return firstDue && firstDue > release ? firstDue : null
}

export function CollectionDatePicker({ frequency, value, onChange, releaseDate }: {
  frequency: string
  /** chosen first collection date, or null for the server default */
  value: string | null
  onChange: (d: string | null) => void
  /** loan release date (YYYY-MM-DD); today when omitted */
  releaseDate?: string
}) {
  const freq = frequency as Freq
  const release = releaseDate || todayManila()
  const current = validFirstDue(value, release) ?? defaultFirstDue(freq, release)
  const [day, setDay] = useState(() => (freq === 'weekly' ? weekdayOf(current) : dayOf(current)))
  // switching between monthly and weekly changes what "day" means
  useEffect(() => { setDay(freq === 'weekly' ? weekdayOf(current) : dayOf(current)) }, [freq]) // eslint-disable-line react-hooks/exhaustive-deps

  const options = collectionDates(freq, day, release)
  const selected = options.includes(current) ? current : null
  // the chosen date is no longer offered (other day, or a draft resumed later): use the first option
  useEffect(() => {
    if (!selected && options.length && value !== options[0]) onChange(options[0])
  }, [selected, options.join()]) // eslint-disable-line react-hooks/exhaustive-deps

  const pickDay = (d: number) => {
    setDay(d)
    onChange(collectionDates(freq, d, release)[0] ?? null)
  }

  const title = freq === 'monthly' ? 'Collection day — which day of the month is the borrower paid?'
    : freq === 'weekly' ? 'Collection day — which day of the week is the borrower paid?'
    : 'First collection — when do daily collections start?'
  const summary = freq === 'monthly' ? `Collected every ${ordinal(day)} of the month`
    : freq === 'weekly' ? `Collected every ${WEEKDAY_NAMES[day]}`
    : 'Collected every day'

  return (
    <div className="space-y-4 rounded-xl border bg-slate-50/60 p-3 sm:p-4">
      <div>
        <p className="flex items-center gap-1.5 text-sm font-medium"><CalendarDays className="size-4 text-[#2563EB]" /> {title}</p>
        <p className="text-xs text-muted-foreground">{summary}. Match it to the borrower's payday.</p>
      </div>

      {freq === 'monthly' && (
        <div className="rounded-lg border bg-white p-3">
          <div role="radiogroup" aria-label="Day of the month" className="grid grid-cols-7 gap-1.5">
            {Array.from({ length: 31 }, (_, i) => i + 1).map((d) => (
              <button key={d} type="button" role="radio" aria-checked={d === day} aria-label={`Day ${d}`} onClick={() => pickDay(d)}
                className={cn('grid h-10 place-items-center rounded-lg text-sm font-medium transition', d === day ? 'bg-[#2563EB] text-white' : 'bg-slate-100 text-slate-700 hover:bg-slate-200')}>
                {d}
              </button>
            ))}
          </div>
          {day > 28 && <p className="mt-2 text-[11px] text-muted-foreground">In months without the {ordinal(day)}, the payment falls on the last day of the month.</p>}
        </div>
      )}

      {freq === 'weekly' && (
        <div role="radiogroup" aria-label="Day of the week" className="grid grid-cols-4 gap-2 sm:grid-cols-7">
          {WEEKDAYS.map((w, i) => (
            <button key={w} type="button" role="radio" aria-checked={i === day} onClick={() => pickDay(i)}
              className={cn('h-11 rounded-lg border-2 bg-white text-sm font-semibold transition', i === day ? 'border-[#2563EB] bg-blue-50 text-[#142B4A]' : 'border-slate-200 hover:border-slate-300')}>
              {w}
            </button>
          ))}
        </div>
      )}

      <div>
        <p className="mb-2 text-sm font-medium">First collection</p>
        <div role="radiogroup" aria-label="First collection date" className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {options.map((d) => {
            const on = d === selected
            return (
              <button key={d} type="button" role="radio" aria-checked={on} onClick={() => onChange(d)}
                className={cn('flex h-14 flex-col items-center justify-center rounded-lg border-2 bg-white text-sm transition', on ? 'border-[#2563EB] bg-blue-50 text-[#142B4A]' : 'border-slate-200 hover:border-slate-300')}>
                <span className="flex items-center gap-1 font-semibold">{on && <Check className="size-3.5" />}{fmtChip(d)}</span>
              </button>
            )
          })}
        </div>
      </div>
    </div>
  )
}
