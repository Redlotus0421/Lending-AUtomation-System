import { ArrowRight, CheckCircle2, Lock, RefreshCw, UserPlus } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { TransactionType } from '@/services/simplified'

const OPTIONS: { t: TransactionType; icon: React.ReactNode; title: string; desc: string; points: string[] }[] = [
  {
    t: 'new', icon: <UserPlus className="size-6" />, title: 'New loan', desc: 'First loan of a new borrower.',
    points: ['Register the borrower with photo, ID and ATM card', 'Add the co-maker', 'Enter amount and duration — the rate is automatic'],
  },
  {
    t: 'renewal', icon: <RefreshCw className="size-6" />, title: 'Reloan / Renew', desc: 'Another loan for a registered borrower.',
    points: ['Search the borrower — no re-registration', 'Outstanding balance is fetched automatically', 'Balance is deducted from the new loan'],
  },
]

/** The two Simplified Lending transactions, as large tappable cards (page step and the New loan popup). */
export function TransactionOptions({ onPick, compact, disabled }: {
  onPick: (t: TransactionType) => void
  /** Tighter layout for the popup: stacked cards, icon beside the text. */
  compact?: boolean
  /** Transactions the user may not start, with the reason shown on the card. */
  disabled?: Partial<Record<TransactionType, string>>
}) {
  return (
    <div className={cn('grid gap-4', compact ? 'gap-3' : 'md:grid-cols-2')}>
      {OPTIONS.map((o) => {
        const blocked = disabled?.[o.t]
        return (
          <button key={o.t} type="button" onClick={() => onPick(o.t)} disabled={!!blocked}
            className={cn(
              'group rounded-2xl border-2 border-slate-200 bg-white text-left transition hover:border-[#2563EB] focus-visible:border-[#2563EB] focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:border-slate-200',
              compact ? 'flex items-start gap-3 p-4' : 'p-5 shadow-card hover:shadow-lg',
            )}>
            <span className={cn('grid shrink-0 place-items-center rounded-xl bg-[#142B4A]/5 text-[#142B4A] group-enabled:group-hover:bg-[#2563EB] group-enabled:group-hover:text-white', compact ? 'size-11' : 'size-12')}>
              {o.icon}
            </span>
            <span className="min-w-0 flex-1">
              <span className={cn('block font-semibold', compact ? 'text-base' : 'mt-4 text-lg')}>{o.title}</span>
              <span className="block text-sm text-muted-foreground">{o.desc}</span>
              <ul className={cn(compact ? 'mt-2 space-y-1' : 'mt-3 space-y-1.5')}>
                {o.points.map((p) => (
                  <li key={p} className={cn('flex gap-2', compact ? 'text-xs text-slate-600' : 'text-sm')}>
                    <CheckCircle2 className={cn('mt-0.5 shrink-0 text-teal-600', compact ? 'size-3.5' : 'size-4')} />{p}
                  </li>
                ))}
              </ul>
              {blocked
                ? <span className="mt-2 flex items-start gap-1.5 text-xs text-amber-700"><Lock className="mt-0.5 size-3.5 shrink-0" />{blocked}</span>
                : !compact && <span className="mt-4 inline-flex items-center gap-1 text-sm font-semibold text-[#2563EB]">Start <ArrowRight className="size-4" /></span>}
            </span>
            {compact && !blocked && <ArrowRight className="mt-3 size-5 shrink-0 text-[#2563EB]" />}
          </button>
        )
      })}
    </div>
  )
}
