import type { ReactNode } from 'react'
import { ArrowDownRight } from 'lucide-react'
import { peso, toNumber } from '@/lib/format'
import { cn } from '@/lib/utils'
import type { Frequency, Money } from '@/types/db'

/** "Monthly payment", "Weekly payment", … */
export function paymentLabel(freq: Frequency | string | undefined) {
  return freq === 'weekly' ? 'Weekly payment' : freq === 'daily' ? 'Daily payment' : 'Monthly payment'
}

export function termUnit(freq: Frequency | string | undefined, n = 2) {
  const one = freq === 'weekly' ? 'week' : freq === 'daily' ? 'day' : 'month'
  return n === 1 ? one : `${one}s`
}

/** Line-by-line computation of the amount actually handed to the borrower. */
export function NetReleaseTable({ principal, deductedCharges, previousBalance, previousLabel, netRelease, className, footnote }: {
  principal: Money
  deductedCharges: Money
  previousBalance: Money
  previousLabel?: ReactNode
  netRelease: Money
  className?: string
  footnote?: ReactNode
}) {
  const rows: [ReactNode, Money, boolean?][] = [['New loan principal', principal]]
  if (toNumber(deductedCharges) > 0) rows.push(['Less: fees & charges deducted', deductedCharges, true])
  if (toNumber(previousBalance) > 0) rows.push([previousLabel ?? 'Less: existing outstanding balance', previousBalance, true])
  return (
    <div className={cn('overflow-hidden rounded-xl border', className)}>
      <table className="w-full text-sm">
        <tbody className="divide-y">
          {rows.map(([label, amount, minus], i) => (
            <tr key={i}>
              <td className="px-4 py-2.5 text-muted-foreground">
                <span className="inline-flex items-center gap-1.5">{minus && <ArrowDownRight className="size-3.5 text-red-500" />}{label}</span>
              </td>
              <td className={cn('tabular px-4 py-2.5 text-right font-medium', minus && 'text-red-600')}>{minus ? '− ' : ''}{peso(amount)}</td>
            </tr>
          ))}
          <tr className="bg-[#142B4A] text-white">
            <td className="px-4 py-3 font-semibold">Net amount to release</td>
            <td className="tabular px-4 py-3 text-right text-lg font-semibold">{peso(netRelease)}</td>
          </tr>
        </tbody>
      </table>
      {footnote && <p className="border-t bg-slate-50 px-4 py-2 text-xs text-muted-foreground">{footnote}</p>}
    </div>
  )
}
