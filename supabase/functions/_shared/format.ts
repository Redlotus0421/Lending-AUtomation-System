// Pure formatting helpers shared by Edge Functions (Deno) and the web app (Vite).
// No runtime-specific imports allowed in this file.

export const MANILA_TZ = 'Asia/Manila'

export function toNumber(v: unknown): number {
  if (v === null || v === undefined || v === '') return 0
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : 0
}

/** "PHP 50,000.00" — documents use the ISO code because PDF base fonts lack the peso sign. */
export function money(v: unknown, prefix = 'PHP '): string {
  const n = toNumber(v)
  const s = Math.abs(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return `${n < 0 ? '-' : ''}${prefix}${s}`
}

export function plainAmount(v: unknown): string {
  return toNumber(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function trimNumber(v: unknown, maxDecimals = 4): string {
  const n = toNumber(v)
  return n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: maxDecimals })
}

/** Parse a YYYY-MM-DD business date without timezone shifting. */
export function parseDateOnly(d: string): Date {
  const [y, m, day] = d.slice(0, 10).split('-').map(Number)
  return new Date(Date.UTC(y, m - 1, day, 12))
}

export function longDate(d: string | Date | null | undefined): string {
  if (!d) return ''
  const date = typeof d === 'string' ? (d.length <= 10 ? parseDateOnly(d) : new Date(d)) : d
  return date.toLocaleDateString('en-US', {
    timeZone: typeof d === 'string' && d.length <= 10 ? 'UTC' : MANILA_TZ,
    year: 'numeric', month: 'long', day: 'numeric',
  })
}

export function shortDate(d: string | Date | null | undefined): string {
  if (!d) return ''
  const date = typeof d === 'string' ? (d.length <= 10 ? parseDateOnly(d) : new Date(d)) : d
  return date.toLocaleDateString('en-US', {
    timeZone: typeof d === 'string' && d.length <= 10 ? 'UTC' : MANILA_TZ,
    year: 'numeric', month: 'short', day: '2-digit',
  })
}

export function dateTimeManila(d: string | Date | null | undefined): string {
  if (!d) return ''
  const date = typeof d === 'string' ? new Date(d) : d
  return (
    date.toLocaleString('en-US', {
      timeZone: MANILA_TZ, year: 'numeric', month: 'short', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true,
    }) + ' PHT'
  )
}

/** Today's date in Manila as YYYY-MM-DD */
export function todayManila(): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: MANILA_TZ, year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date())
  return parts
}

const ONES = ['', 'One', 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Eight', 'Nine', 'Ten', 'Eleven', 'Twelve',
  'Thirteen', 'Fourteen', 'Fifteen', 'Sixteen', 'Seventeen', 'Eighteen', 'Nineteen']
const TENS = ['', '', 'Twenty', 'Thirty', 'Forty', 'Fifty', 'Sixty', 'Seventy', 'Eighty', 'Ninety']
const SCALES = ['', 'Thousand', 'Million', 'Billion', 'Trillion']

function chunkToWords(n: number): string {
  const parts: string[] = []
  const h = Math.floor(n / 100)
  const r = n % 100
  if (h) parts.push(`${ONES[h]} Hundred`)
  if (r) {
    if (r < 20) parts.push(ONES[r])
    else parts.push(TENS[Math.floor(r / 10)] + (r % 10 ? `-${ONES[r % 10]}` : ''))
  }
  return parts.join(' ')
}

export function integerToWords(n: number): string {
  n = Math.floor(Math.abs(n))
  if (n === 0) return 'Zero'
  const words: string[] = []
  let scale = 0
  while (n > 0) {
    const chunk = n % 1000
    if (chunk) words.unshift(chunkToWords(chunk) + (SCALES[scale] ? ` ${SCALES[scale]}` : ''))
    n = Math.floor(n / 1000)
    scale++
  }
  return words.join(' ')
}

/** "Fifty Thousand Pesos" / "One Thousand Two Hundred Pesos and 50/100" */
export function amountInWords(v: unknown): string {
  const n = Math.round(toNumber(v) * 100) / 100
  const pesos = Math.floor(n)
  const centavos = Math.round((n - pesos) * 100)
  const w = `${integerToWords(pesos)} Peso${pesos === 1 ? '' : 's'}`
  return centavos ? `${w} and ${String(centavos).padStart(2, '0')}/100` : w
}

export const FREQUENCY_LABEL: Record<string, string> = { daily: 'daily', weekly: 'weekly', semimonthly: 'semi-monthly', monthly: 'monthly' }
export const PERIOD_LABEL: Record<string, string> = { daily: 'day', weekly: 'week', semimonthly: 'half-month', monthly: 'month', yearly: 'year' }
export const PERIOD_PLURAL: Record<string, string> = { daily: 'days', weekly: 'weeks', semimonthly: 'half-months', monthly: 'months', yearly: 'years' }
export const METHOD_LABEL: Record<string, string> = { flat: 'Flat interest', diminishing: 'Diminishing balance' }

export const SIGNER_LABEL: Record<string, string> = {
  borrower: 'Borrower', co_maker: 'Co-maker', lender: 'Lender', witness_1: 'Witness', witness_2: 'Witness',
}

export function durationLabel(count: number, frequency: string): string {
  if (frequency === 'semimonthly') {
    const months = count / 2
    return `${count} semi-monthly payment${count === 1 ? '' : 's'} (${trimNumber(months)} month${months === 1 ? '' : 's'})`
  }
  const unit = count === 1 ? PERIOD_LABEL[frequency] : PERIOD_PLURAL[frequency]
  return `${count} ${unit}`
}

export function penaltyTerms(loan: {
  penalty_enabled?: boolean | null; penalty_type?: string | null; penalty_rate?: unknown; penalty_grace_days?: number | null
}): string {
  if (!loan.penalty_enabled || !loan.penalty_type || toNumber(loan.penalty_rate) <= 0) {
    return 'No late payment penalty shall be charged under this Agreement. Unpaid amounts nevertheless remain due and demandable.'
  }
  const grace = loan.penalty_grace_days ?? 0
  const graceText = grace > 0 ? `${grace}-day grace period after the due date` : 'due date'
  const rate = trimNumber(loan.penalty_rate)
  switch (loan.penalty_type) {
    case 'fixed':
      return `A one-time late payment fee of ${money(loan.penalty_rate)} shall be charged for each installment that remains unpaid after the ${graceText}.`
    case 'percent_once':
      return `A one-time late payment charge of ${rate}% of the unpaid installment amount shall be charged for each installment that remains unpaid after the ${graceText}.`
    case 'percent_daily':
      return `A late payment penalty of ${rate}% per day of the unpaid installment amount shall accrue for each day of delay after the ${graceText}.`
    case 'percent_monthly':
      return `A late payment penalty of ${rate}% per month of the unpaid installment amount, computed daily on a 30-day month, shall accrue for each day of delay after the ${graceText}.`
    default:
      return 'Late payment penalties apply as stated in the Disclosure Statement.'
  }
}

export function allocationOrderText(order: string[] | null | undefined): string {
  const labels: Record<string, string> = { penalty: 'penalties', charges: 'charges', interest: 'interest', principal: 'principal' }
  const o = (order && order.length ? order : ['penalty', 'charges', 'interest', 'principal']).map((x) => labels[x] ?? x)
  return o.slice(0, -1).join(', ') + ', and finally ' + o[o.length - 1]
}
