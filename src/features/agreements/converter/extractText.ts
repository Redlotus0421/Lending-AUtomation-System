// Read the text of an uploaded terms file in the browser (no upload, no internet service).
// PDF via pdf.js (bundled with the app), Word .docx via a small ZIP/XML reader, plain text as is.
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs' // legacy: Safari 18 lacks APIs the modern build needs
import workerSrc from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url'
import { docxToText } from '../../../../supabase/functions/_shared/docx'

pdfjs.GlobalWorkerOptions.workerSrc = workerSrc

export type ExtractedText = { text: string; kind: 'pdf' | 'docx' | 'text'; pages?: number }

export class UnsupportedFileError extends Error {}

export function fileKind(f: File): ExtractedText['kind'] | null {
  const ext = f.name.toLowerCase().split('.').pop() ?? ''
  if (f.type === 'application/pdf' || ext === 'pdf') return 'pdf'
  if (ext === 'docx' || f.type === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document') return 'docx'
  if (f.type.startsWith('text/') || ['txt', 'md', 'markdown'].includes(ext)) return 'text'
  return null
}

type Item = { str: string; x: number; y: number; w: number; h: number }

/** Lines of one PDF page, top to bottom, with blank lines at paragraph gaps and wide gaps kept as spacing. */
function pageLines(items: Item[]): string[] {
  const rows: { y: number; h: number; items: Item[] }[] = []
  for (const it of items.filter((i) => i.str.trim() !== '' || i.str === ' ').sort((a, b) => b.y - a.y || a.x - b.x)) {
    const row = rows.find((r) => Math.abs(r.y - it.y) <= Math.max(2, Math.min(r.h, it.h) * 0.45))
    if (row) row.items.push(it)
    else rows.push({ y: it.y, h: it.h || 10, items: [it] })
  }
  rows.sort((a, b) => b.y - a.y)
  const heights = rows.map((r) => r.h).sort((a, b) => a - b)
  const lineH = heights[Math.floor(heights.length / 2)] || 10
  const out: string[] = []
  let prevY: number | null = null
  for (const r of rows) {
    if (prevY !== null && prevY - r.y > lineH * 1.75) out.push('')
    prevY = r.y
    const its = r.items.sort((a, b) => a.x - b.x)
    let line = ''
    let end = -Infinity
    for (const it of its) {
      const gap = it.x - end
      if (line && gap > lineH * 2.2) line += '    '
      else if (line && gap > lineH * 0.15 && !line.endsWith(' ') && !it.str.startsWith(' ')) line += ' '
      line += it.str
      end = it.x + it.w
    }
    out.push(line.replace(/[ \t]+$/, ''))
  }
  return out
}

async function pdfText(bytes: ArrayBuffer): Promise<{ text: string; pages: number }> {
  const doc = await pdfjs.getDocument({ data: new Uint8Array(bytes) }).promise
  const pages: string[][] = []
  for (let p = 1; p <= doc.numPages; p++) {
    const page = await doc.getPage(p)
    const content = await page.getTextContent()
    const items: Item[] = []
    for (const raw of content.items) {
      if (!('str' in raw)) continue
      items.push({ str: raw.str, x: raw.transform[4], y: raw.transform[5], w: raw.width, h: Math.abs(raw.transform[3]) || raw.height })
    }
    pages.push(pageLines(items))
  }
  // drop running headers / footers that repeat on most pages
  if (pages.length >= 2) {
    const count = new Map<string, number>()
    for (const lines of pages) {
      const edge = [...lines.filter(Boolean).slice(0, 2), ...lines.filter(Boolean).slice(-2)]
      for (const l of new Set(edge.map((x) => x.trim().replace(/\d+/g, '#')))) count.set(l, (count.get(l) ?? 0) + 1)
    }
    const repeated = new Set([...count].filter(([, c]) => c >= Math.max(2, Math.ceil(pages.length * 0.6))).map(([l]) => l))
    for (let i = 0; i < pages.length; i++) {
      const lines = pages[i]
      const nonEmpty = lines.map((l, j) => ({ l, j })).filter((x) => x.l.trim())
      const edges = new Set([...nonEmpty.slice(0, 2), ...nonEmpty.slice(-2)].map((x) => x.j))
      pages[i] = lines.filter((l, j) => !(edges.has(j) && repeated.has(l.trim().replace(/\d+/g, '#'))))
    }
  }
  return { text: pages.map((l) => l.join('\n')).join('\n\n'), pages: pages.length }
}

export async function extractText(file: File): Promise<ExtractedText> {
  const kind = fileKind(file)
  if (!kind) throw new UnsupportedFileError('Use a PDF, Word (.docx) or text file.')
  const bytes = await file.arrayBuffer()
  if (kind === 'pdf') {
    const { text, pages } = await pdfText(bytes)
    if (text.replace(/\s/g, '').length < 80) {
      throw new UnsupportedFileError('This PDF has no readable text — it looks like a scanned image or photo. Use the original Word file or a PDF saved from Word.')
    }
    return { text, kind, pages }
  }
  if (kind === 'docx') return { text: await docxToText(new Uint8Array(bytes)), kind }
  return { text: new TextDecoder().decode(bytes), kind }
}
