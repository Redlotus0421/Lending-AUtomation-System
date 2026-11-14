import { AlertTriangle, Info } from 'lucide-react'
import type { LoanCalculation, ScheduleRow } from '@/types/db'
import { fmtDate, METHOD_LABEL, num, PERIOD_LABEL, peso, pct, toNumber, trimNumber } from '@/lib/format'
import { cn } from '@/lib/utils'

export function rateLabel(rate: unknown, period: string) {
  return `${trimNumber(rate, 4)}% per ${PERIOD_LABEL[period] ?? period}`
}

export function SummaryGrid({ calc, compact }: { calc: LoanCalculation; compact?: boolean }) {
  const s = calc.summary
  const items: [string, string, string?][] = [
    ['Principal', peso(s.principal)],
    ['Interest method', METHOD_LABEL[s.interest_method]],
    ['Interest rate', rateLabel(s.interest_rate, s.rate_period), `${trimNumber(s.annual_nominal_rate, 2)}% p.a. nominal`],
    ['Duration', s.duration_label, `${s.repayment_frequency} payments`],
    ['Installments', num(s.installment_count)],
    ['Installment amount', peso(s.installment_amount), toNumber(s.last_installment_amount) !== toNumber(s.installment_amount) ? `Final: ${peso(s.last_installment_amount)}` : undefined],
    ['Total interest', peso(s.total_interest)],
    ['Total charges', peso(s.total_charges), s.charges_treatment === 'added' ? 'Added to installments' : 'Deducted from proceeds'],
    ['Total repayment', peso(s.total_repayment)],
    ['Net proceeds', peso(s.net_proceeds)],
    ['First due date', fmtDate(s.first_due_date)],
    ['Last due date', fmtDate(s.last_due_date)],
    ['Effective rate (EIR)', s.eir_monthly_pct != null ? `${pct(s.eir_monthly_pct)} / month` : '—', s.effective_annual_rate_pct != null ? `${pct(s.effective_annual_rate_pct)} effective p.a.` : undefined],
    ['Rate per period', `${trimNumber(s.periodic_rate_pct, 6)}%`, `per ${PERIOD_LABEL[s.repayment_frequency]} (nominal)`],
  ]
  return (
    <dl className={cn('grid grid-cols-2 gap-x-4 gap-y-3', compact ? 'sm:grid-cols-2' : 'sm:grid-cols-3 lg:grid-cols-4')}>
      {items.map(([label, value, hint]) => (
        <div key={label} className="min-w-0">
          <dt className="text-xs text-muted-foreground">{label}</dt>
          <dd className="tabular mt-0.5 truncate text-sm font-semibold">{value}</dd>
          {hint && <dd className="truncate text-[11px] text-muted-foreground">{hint}</dd>}
        </div>
      ))}
    </dl>
  )
}

export function HeadlineFigures({ calc }: { calc: LoanCalculation }) {
  const s = calc.summary
  return (
    <div className="grid grid-cols-3 gap-2 rounded-xl bg-[#142B4A] p-4 text-white">
      <div>
        <p className="text-[11px] uppercase tracking-wide text-white/60">Installment</p>
        <p className="tabular text-lg font-semibold sm:text-xl">{peso(s.installment_amount)}</p>
        <p className="text-[11px] text-white/60">× {s.installment_count} {s.repayment_frequency}</p>
      </div>
      <div>
        <p className="text-[11px] uppercase tracking-wide text-white/60">Total interest</p>
        <p className="tabular text-lg font-semibold sm:text-xl">{peso(s.total_interest)}</p>
        <p className="text-[11px] text-white/60">{METHOD_LABEL[s.interest_method]}</p>
      </div>
      <div>
        <p className="text-[11px] uppercase tracking-wide text-white/60">Total repayment</p>
        <p className="tabular text-lg font-semibold text-teal sm:text-xl">{peso(s.total_repayment)}</p>
        <p className="text-[11px] text-white/60">Net proceeds {peso(s.net_proceeds, { compact: true })}</p>
      </div>
    </div>
  )
}

export function ScheduleTable({ rows, maxHeight }: { rows: ScheduleRow[]; maxHeight?: number }) {
  const totals = rows.reduce(
    (a, r) => ({ p: a.p + toNumber(r.principal), i: a.i + toNumber(r.interest), c: a.c + toNumber(r.charges), t: a.t + toNumber(r.total) }),
    { p: 0, i: 0, c: 0, t: 0 },
  )
  const showCharges = totals.c > 0
  return (
    <div className="overflow-auto rounded-lg border" style={{ maxHeight }}>
      <table className="w-full min-w-[640px] text-sm">
        <thead className="sticky top-0 bg-slate-50 text-xs uppercase tracking-wide text-muted-foreground">
          <tr>
            <th className="px-3 py-2 text-left font-medium">#</th>
            <th className="px-3 py-2 text-left font-medium">Due date</th>
            <th className="px-3 py-2 text-right font-medium">Opening balance</th>
            <th className="px-3 py-2 text-right font-medium">Principal</th>
            <th className="px-3 py-2 text-right font-medium">Interest</th>
            {showCharges && <th className="px-3 py-2 text-right font-medium">Charges</th>}
            <th className="px-3 py-2 text-right font-medium">Total payment</th>
            <th className="px-3 py-2 text-right font-medium">Closing balance</th>
          </tr>
        </thead>
        <tbody className="tabular">
          {rows.map((r) => (
            <tr key={r.installment_no} className="border-t">
              <td className="px-3 py-1.5 text-muted-foreground">{r.installment_no}</td>
              <td className="px-3 py-1.5">{fmtDate(r.due_date)}</td>
              <td className="px-3 py-1.5 text-right">{num(r.opening_balance, 2)}</td>
              <td className="px-3 py-1.5 text-right">{num(r.principal, 2)}</td>
              <td className="px-3 py-1.5 text-right">{num(r.interest, 2)}</td>
              {showCharges && <td className="px-3 py-1.5 text-right">{num(r.charges, 2)}</td>}
              <td className="px-3 py-1.5 text-right font-semibold">{num(r.total, 2)}</td>
              <td className="px-3 py-1.5 text-right">{num(r.closing_balance, 2)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot className="tabular sticky bottom-0 bg-slate-50 font-semibold">
          <tr className="border-t">
            <td className="px-3 py-2" colSpan={3}>Total</td>
            <td className="px-3 py-2 text-right">{num(totals.p, 2)}</td>
            <td className="px-3 py-2 text-right">{num(totals.i, 2)}</td>
            {showCharges && <td className="px-3 py-2 text-right">{num(totals.c, 2)}</td>}
            <td className="px-3 py-2 text-right">{num(totals.t, 2)}</td>
            <td />
          </tr>
        </tfoot>
      </table>
    </div>
  )
}

export function Disclosures({ calc }: { calc: LoanCalculation }) {
  return (
    <div className="space-y-2">
      {calc.warnings?.map((w) => (
        <div key={w} className="flex gap-2 rounded-lg bg-amber-50 p-3 text-xs text-amber-900">
          <AlertTriangle className="mt-0.5 size-3.5 shrink-0" /> {w}
        </div>
      ))}
      <div className="rounded-lg bg-slate-50 p-3 text-xs text-slate-700">
        <p className="mb-1 flex items-center gap-1.5 font-semibold text-slate-800"><Info className="size-3.5" /> Rate disclosure</p>
        <ul className="list-disc space-y-0.5 pl-5">
          {calc.disclosures?.map((d) => <li key={d}>{d}</li>)}
        </ul>
      </div>
    </div>
  )
}
