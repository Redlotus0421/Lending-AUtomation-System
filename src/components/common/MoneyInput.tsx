import { useEffect, useLayoutEffect, useRef, useState, type ComponentProps } from 'react'
import { Input } from '@/components/ui/input'

/** Keep digits and one decimal point (max 2 decimals); commas, spaces and ₱ typed by the user are ignored. */
export function cleanMoney(text: string): string {
  let s = text.replace(/[^\d.]/g, '')
  const dot = s.indexOf('.')
  if (dot !== -1) s = s.slice(0, dot + 1) + s.slice(dot + 1).replace(/\./g, '').slice(0, 2)
  s = s.replace(/^0+(?=\d)/, '')
  return s.slice(0, 15)
}

/** 1234567.5 -> "1,234,567.5" (keeps a trailing "." while typing) */
export function groupMoney(raw: string): string {
  if (!raw) return ''
  const [int, dec] = raw.split('.')
  const grouped = (int || '0').replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return dec === undefined ? grouped : `${grouped}.${dec}`
}

const significant = (s: string) => s.replace(/[^\d.]/g, '').length

/**
 * Peso amount field for phones. A plain text field with a decimal keypad instead of
 * type="number": number fields reject a typed comma (e.g. "20,000") and then clear
 * themselves on many Android keyboards, which looks like the field is locked.
 * Shows thousands separators while typing and reports the plain number string.
 */
export function MoneyInput({ value, onValueChange, ...props }: Omit<ComponentProps<'input'>, 'value' | 'onChange' | 'type'> & {
  value: string | number | null | undefined
  /** Plain number string, e.g. "20000.5" ("" when empty) */
  onValueChange: (raw: string) => void
}) {
  const toRaw = (v: typeof value) => (v === null || v === undefined || (typeof v === 'number' && !Number.isFinite(v)) ? '' : cleanMoney(String(v)))
  const [raw, setRaw] = useState(() => toRaw(value))
  const ref = useRef<HTMLInputElement>(null)
  const caret = useRef<number | null>(null)

  // follow outside changes (draft restore, product defaults) without fighting the user's typing
  useEffect(() => {
    const next = toRaw(value)
    setRaw((cur) => (Number(cur || 'NaN') === Number(next || 'NaN') || (cur === '' && next === '') ? cur : next))
  }, [value]) // eslint-disable-line react-hooks/exhaustive-deps

  const display = groupMoney(raw)

  // put the cursor back after the same number of digits it was after before regrouping
  useLayoutEffect(() => {
    const el = ref.current
    if (!el || caret.current === null || document.activeElement !== el) return
    let seen = 0
    let pos = 0
    while (pos < display.length && seen < caret.current) {
      if (/[\d.]/.test(display[pos])) seen++
      pos++
    }
    el.setSelectionRange(pos, pos)
    caret.current = null
  }, [display])

  return (
    <Input
      ref={ref}
      type="text"
      inputMode="decimal"
      autoComplete="off"
      enterKeyHint="next"
      {...props}
      value={display}
      onChange={(e) => {
        const text = e.target.value
        caret.current = significant(text.slice(0, e.target.selectionStart ?? text.length))
        const next = cleanMoney(text)
        setRaw(next)
        onValueChange(next)
      }}
    />
  )
}
