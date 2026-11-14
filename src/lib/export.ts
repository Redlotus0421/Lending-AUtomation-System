// Reusable exporters for tables and reports: CSV, XLSX and PDF.
// PDFs use "PHP" instead of the peso sign because the built-in PDF fonts lack it.
import type { ReportColumn } from '@/types/db'

export type ExportColumn = ReportColumn
export type ExportRow = Record<string, unknown>
export type ExportTotals = Record<string, number | string | null | undefined>

function toNum(v: unknown): number {
  if (v === null || v === undefined || v === '') return 0
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : 0
}

function isBlank(v: unknown) {
  return v === null || v === undefined || v === ''
}

function slug(name: string) {
  return name.replace(/[^\w.-]+/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '') || 'export'
}

function stamp() {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}`
}

function ensureExt(filename: string, ext: string) {
  const base = slug(filename.replace(new RegExp(`\\.${ext}$`, 'i'), ''))
  return `${base}.${ext}`
}

function dateText(v: unknown, long = false): string {
  if (isBlank(v)) return ''
  const s = String(v)
  const isDateOnly = s.length <= 10
  const d = isDateOnly ? new Date(`${s.slice(0, 10)}T12:00:00Z`) : new Date(s)
  if (Number.isNaN(d.getTime())) return s
  if (!long) {
    if (isDateOnly) return s.slice(0, 10)
    return d.toLocaleString('en-CA', { timeZone: 'Asia/Manila', hour12: false }).replace(',', '')
  }
  return d.toLocaleDateString('en-US', { timeZone: isDateOnly ? 'UTC' : 'Asia/Manila', year: 'numeric', month: 'short', day: '2-digit' })
}

function titleText(v: unknown) {
  return String(v ?? '').replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase())
}

/** Human-readable value for PDFs / print. */
export function displayValue(col: ExportColumn, v: unknown): string {
  if (isBlank(v)) return col.type === 'money' ? 'PHP 0.00' : ''
  switch (col.type) {
    case 'money': {
      const n = toNum(v)
      return `${n < 0 ? '-' : ''}PHP ${Math.abs(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
    }
    case 'number':
      return toNum(v).toLocaleString('en-US', { maximumFractionDigits: 2 })
    case 'percent':
      return `${toNum(v).toLocaleString('en-US', { maximumFractionDigits: 2 })}%`
    case 'date':
      return dateText(v, true)
    case 'status':
      return titleText(v)
    default:
      return String(v)
  }
}

/** Machine-friendly value for CSV (numbers stay numeric, dates are ISO). */
function csvValue(col: ExportColumn, v: unknown): string {
  if (isBlank(v)) return ''
  switch (col.type) {
    case 'money':
      return toNum(v).toFixed(2)
    case 'number':
    case 'percent':
      return String(toNum(v))
    case 'date':
      return dateText(v)
    case 'status':
      return titleText(v)
    default:
      return String(v)
  }
}

function csvEscape(s: string) {
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

function download(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 2000)
}

/** Compute totals for columns flagged `total: true` when they were not supplied. */
export function computeTotals(columns: ExportColumn[], rows: ExportRow[]): ExportTotals {
  const out: ExportTotals = {}
  for (const c of columns) {
    if (c.total) out[c.key] = Math.round(rows.reduce((a, r) => a + toNum(r[c.key]), 0) * 100) / 100
  }
  return out
}

function hasTotals(columns: ExportColumn[]) {
  return columns.some((c) => c.total)
}

function totalsRow<T>(columns: ExportColumn[], totals: ExportTotals, map: (c: ExportColumn, v: unknown) => T, label: T, blank: T): T[] {
  return columns.map((c, i) => {
    if (c.total) return map(c, totals[c.key])
    return i === 0 ? label : blank
  })
}

// ---------------------------------------------------------------------------
// CSV
// ---------------------------------------------------------------------------
export function exportCsv(filename: string, columns: ExportColumn[], rows: ExportRow[], totals?: ExportTotals) {
  const lines: string[] = [columns.map((c) => csvEscape(c.label)).join(',')]
  for (const r of rows) lines.push(columns.map((c) => csvEscape(csvValue(c, r[c.key]))).join(','))
  if (hasTotals(columns) && rows.length) {
    const t = totals ?? computeTotals(columns, rows)
    lines.push(totalsRow(columns, t, (c, v) => csvEscape(csvValue(c, v)), 'TOTAL', '').join(','))
  }
  // BOM so Excel opens UTF-8 (₱, ñ) correctly
  download(new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' }), ensureExt(`${filename}-${stamp()}`, 'csv'))
}

// ---------------------------------------------------------------------------
// XLSX
// ---------------------------------------------------------------------------
export type XlsxSheet = { name: string; columns: ExportColumn[]; rows: ExportRow[]; totals?: ExportTotals }

function xlsxCell(col: ExportColumn, v: unknown, bold = false) {
  const base = bold ? { fontWeight: 'bold' as const } : {}
  if (isBlank(v)) return bold ? { value: '', type: String, ...base } : null
  switch (col.type) {
    case 'money':
      return { value: toNum(v), type: Number, format: '#,##0.00', ...base }
    case 'number':
      return { value: toNum(v), type: Number, format: '#,##0.##', ...base }
    case 'percent':
      return { value: toNum(v) / 100, type: Number, format: '0.00%', ...base }
    case 'date': {
      const s = String(v)
      const d = s.length <= 10 ? new Date(`${s.slice(0, 10)}T00:00:00Z`) : new Date(s)
      if (Number.isNaN(d.getTime())) return { value: s, type: String, ...base }
      return { value: d, type: Date, format: s.length <= 10 ? 'yyyy-mm-dd' : 'yyyy-mm-dd hh:mm', ...base }
    }
    case 'status':
      return { value: titleText(v), type: String, ...base }
    default:
      return { value: String(v), type: String, ...base }
  }
}

function sheetData(s: XlsxSheet) {
  const header = s.columns.map((c) => ({ value: c.label, type: String, fontWeight: 'bold' as const, backgroundColor: '#E8EEF7' }))
  const body = s.rows.map((r) => s.columns.map((c) => xlsxCell(c, r[c.key])))
  const data: unknown[][] = [header, ...body]
  if (hasTotals(s.columns) && s.rows.length) {
    const t = s.totals ?? computeTotals(s.columns, s.rows)
    data.push(s.columns.map((c, i) => (c.total ? xlsxCell(c, t[c.key] ?? 0, true) : i === 0 ? { value: 'TOTAL', type: String, fontWeight: 'bold' as const } : null)))
  }
  return data
}

function colWidths(columns: ExportColumn[], rows: ExportRow[]) {
  return columns.map((c) => {
    const longest = rows.slice(0, 200).reduce((m, r) => Math.max(m, displayValue(c, r[c.key]).length), c.label.length)
    return { width: Math.min(Math.max(longest + 2, 10), 48) }
  })
}

export async function exportXlsxSheets(filename: string, sheets: XlsxSheet[]) {
  const { default: writeXlsxFile } = await import('write-excel-file/browser')
  const prepared = sheets.map((s) => ({
    data: sheetData(s) as never,
    sheet: s.name.replace(/[\\/?*[\]:]/g, ' ').slice(0, 31) || 'Sheet',
    columns: colWidths(s.columns, s.rows),
    stickyRowsCount: 1,
  }))
  await writeXlsxFile(prepared).toFile(ensureExt(`${filename}-${stamp()}`, 'xlsx'))
}

export async function exportXlsx(filename: string, sheetName: string, columns: ExportColumn[], rows: ExportRow[], totals?: ExportTotals) {
  await exportXlsxSheets(filename, [{ name: sheetName, columns, rows, totals }])
}

// ---------------------------------------------------------------------------
// PDF
// ---------------------------------------------------------------------------
export type PdfSection = { title?: string; columns: ExportColumn[]; rows: ExportRow[]; totals?: ExportTotals; note?: string }

export async function exportPdfReport(filename: string, opts: {
  title: string
  subtitle?: string
  organization?: string
  sections: PdfSection[]
  orientation?: 'portrait' | 'landscape'
  footerNote?: string
}) {
  const [{ jsPDF }, { autoTable }] = await Promise.all([import('jspdf'), import('jspdf-autotable')])
  const orientation = opts.orientation ?? (Math.max(...opts.sections.map((s) => s.columns.length)) > 6 ? 'landscape' : 'portrait')
  const doc = new jsPDF({ orientation, unit: 'pt', format: 'a4' })
  const pageW = doc.internal.pageSize.getWidth()
  const margin = 40
  const navy: [number, number, number] = [20, 43, 74]

  doc.setFillColor(...navy)
  doc.rect(0, 0, pageW, 6, 'F')
  let y = 36
  if (opts.organization) {
    doc.setFont('helvetica', 'normal')
    doc.setFontSize(9)
    doc.setTextColor(91, 107, 130)
    doc.text(clean(opts.organization), margin, y)
    y += 16
  }
  doc.setFont('helvetica', 'bold')
  doc.setFontSize(15)
  doc.setTextColor(15, 27, 45)
  doc.text(clean(opts.title), margin, y)
  y += 15
  doc.setFont('helvetica', 'normal')
  doc.setFontSize(8.5)
  doc.setTextColor(91, 107, 130)
  const generated = `Generated ${new Date().toLocaleString('en-US', { timeZone: 'Asia/Manila', dateStyle: 'medium', timeStyle: 'short' })} (PHT)`
  doc.text(clean(opts.subtitle ? `${opts.subtitle}  ·  ${generated}` : generated), margin, y)
  y += 14

  for (const s of opts.sections) {
    if (s.title) {
      if (y > doc.internal.pageSize.getHeight() - 90) {
        doc.addPage()
        y = 40
      }
      doc.setFont('helvetica', 'bold')
      doc.setFontSize(10.5)
      doc.setTextColor(...navy)
      doc.text(clean(s.title), margin, y + 10)
      y += 16
    }
    const t = hasTotals(s.columns) && s.rows.length ? (s.totals ?? computeTotals(s.columns, s.rows)) : null
    autoTable(doc, {
      startY: y,
      margin: { left: margin, right: margin, bottom: 40 },
      head: [s.columns.map((c) => clean(c.label))],
      body: s.rows.map((r) => s.columns.map((c) => clean(displayValue(c, r[c.key])))),
      foot: t ? [totalsRow(s.columns, t, (c, v) => clean(displayValue(c, v)), 'TOTAL', '')] : undefined,
      theme: 'striped',
      styles: { font: 'helvetica', fontSize: s.columns.length > 9 ? 6.8 : 7.8, cellPadding: 3.5, overflow: 'linebreak', textColor: [17, 24, 39] },
      headStyles: { fillColor: navy, textColor: [255, 255, 255], fontStyle: 'bold' },
      footStyles: { fillColor: [232, 238, 247], textColor: [15, 27, 45], fontStyle: 'bold' },
      alternateRowStyles: { fillColor: [247, 249, 252] },
      columnStyles: Object.fromEntries(
        s.columns.map((c, i) => [i, { halign: c.type === 'money' || c.type === 'number' || c.type === 'percent' ? 'right' : 'left' }]),
      ),
      showFoot: 'lastPage',
    })
    y = ((doc as unknown as { lastAutoTable?: { finalY: number } }).lastAutoTable?.finalY ?? y) + 14
    if (s.note) {
      doc.setFont('helvetica', 'italic')
      doc.setFontSize(7.5)
      doc.setTextColor(91, 107, 130)
      const lines = doc.splitTextToSize(clean(s.note), pageW - margin * 2)
      doc.text(lines, margin, y)
      y += lines.length * 10 + 8
    }
  }

  const pages = doc.getNumberOfPages()
  for (let i = 1; i <= pages; i++) {
    doc.setPage(i)
    const h = doc.internal.pageSize.getHeight()
    doc.setFont('helvetica', 'normal')
    doc.setFontSize(7)
    doc.setTextColor(140, 150, 165)
    doc.text(clean(opts.footerNote ?? 'LendFlow · Amounts in Philippine Pesos (PHP)'), margin, h - 20)
    const label = `Page ${i} of ${pages}`
    doc.text(label, pageW - margin - doc.getTextWidth(label), h - 20)
  }
  doc.save(ensureExt(`${filename}-${stamp()}`, 'pdf'))
}

export async function exportPdfTable(filename: string, opts: {
  title: string
  subtitle?: string
  organization?: string
  columns: ExportColumn[]
  rows: ExportRow[]
  totals?: ExportTotals
  orientation?: 'portrait' | 'landscape'
  note?: string
}) {
  await exportPdfReport(filename, {
    title: opts.title,
    subtitle: opts.subtitle,
    organization: opts.organization,
    orientation: opts.orientation,
    sections: [{ columns: opts.columns, rows: opts.rows, totals: opts.totals, note: opts.note }],
  })
}

/** Replace characters the standard PDF fonts cannot render. */
function clean(s: string) {
  return String(s ?? '')
    .replace(/₱\s?/g, 'PHP ')
    .replace(/[–—]/g, '-')
    .replace(/[“”]/g, '"')
    .replace(/[‘’]/g, "'")
    .replace(/…/g, '...')
    .replace(/[^\x20-\x7E -ÿ\n]/g, '')
}

/** Print only the given element (opens a clean print window with the page styles). */
export function printElement(el: HTMLElement | null, title = document.title) {
  if (!el) return window.print()
  const w = window.open('', '_blank', 'width=900,height=700')
  if (!w) return window.print()
  const styles = Array.from(document.querySelectorAll('style, link[rel="stylesheet"]')).map((n) => n.outerHTML).join('\n')
  w.document.write(`<!doctype html><html><head><base href="${window.location.origin}/"><title>${title.replace(/</g, '')}</title>${styles}
    <style>body{background:#fff;padding:24px} .no-print{display:none!important}</style></head><body>${el.outerHTML}</body></html>`)
  w.document.close()
  w.focus()
  setTimeout(() => {
    w.print()
    w.close()
  }, 400)
}
