// Minimal document layout engine on top of pdf-lib: wrapping (with bold runs),
// justification, pagination, running headers, tables with repeated headers.
import { PDFDocument, PDFFont, PDFPage, rgb, StandardFonts, type RGB } from 'npm:pdf-lib@1.17.1'
import type { Run } from '../template.ts'

export const A4 = { width: 595.28, height: 841.89 }
export const A5 = { width: 419.53, height: 595.28 }

export const COLORS = {
  text: rgb(0.07, 0.09, 0.13),
  muted: rgb(0.38, 0.43, 0.5),
  rule: rgb(0.8, 0.84, 0.88),
  navy: rgb(0.078, 0.169, 0.29),
  teal: rgb(0.078, 0.722, 0.651),
  shade: rgb(0.945, 0.957, 0.973),
  white: rgb(1, 1, 1),
  red: rgb(0.86, 0.15, 0.15),
}

const REPLACEMENTS: Record<string, string> = {
  '₱': 'PHP ', ' ': ' ', ' ': ' ', ' ': ' ', '​': '', '≤': '<=', '≥': '>=', '→': '->', '✓': 'v',
}

export type Fonts = { serif: PDFFont; serifBold: PDFFont; sans: PDFFont; sansBold: PDFFont }

export class DocWriter {
  pdf!: PDFDocument
  fonts!: Fonts
  page!: PDFPage
  pages: PDFPage[] = []
  y = 0
  size = A4
  margin = { top: 64, bottom: 64, left: 62, right: 62 }
  onNewPage?: (w: DocWriter, pageIndex: number) => void
  private encodable = new Map<string, boolean>()

  static async create(size = A4, margin?: Partial<DocWriter['margin']>) {
    const w = new DocWriter()
    w.size = size
    if (margin) w.margin = { ...w.margin, ...margin }
    w.pdf = await PDFDocument.create()
    w.fonts = {
      serif: await w.pdf.embedFont(StandardFonts.TimesRoman),
      serifBold: await w.pdf.embedFont(StandardFonts.TimesRomanBold),
      sans: await w.pdf.embedFont(StandardFonts.Helvetica),
      sansBold: await w.pdf.embedFont(StandardFonts.HelveticaBold),
    }
    return w
  }

  get contentWidth() {
    return this.size.width - this.margin.left - this.margin.right
  }

  /** Make text safe for the WinAnsi-encoded standard fonts. */
  clean(text: string): string {
    let out = ''
    for (const ch of String(text ?? '')) {
      const r = REPLACEMENTS[ch]
      if (r !== undefined) {
        out += r
        continue
      }
      let ok = this.encodable.get(ch)
      if (ok === undefined) {
        try {
          this.fonts.sans.encodeText(ch)
          ok = true
        } catch {
          ok = false
        }
        this.encodable.set(ch, ok)
      }
      out += ok ? ch : '?'
    }
    return out
  }

  newPage() {
    this.page = this.pdf.addPage([this.size.width, this.size.height])
    this.pages.push(this.page)
    this.y = this.size.height - this.margin.top
    this.onNewPage?.(this, this.pages.length - 1)
    return this.page
  }

  ensure(height: number) {
    if (this.y - height < this.margin.bottom) this.newPage()
  }

  space(h: number) {
    this.y -= h
  }

  text(t: string, x: number, y: number, opts: { font?: PDFFont; size?: number; color?: RGB; page?: PDFPage } = {}) {
    const font = opts.font ?? this.fonts.serif
    ;(opts.page ?? this.page).drawText(this.clean(t), { x, y, size: opts.size ?? 10, font, color: opts.color ?? COLORS.text })
  }

  width(t: string, font: PDFFont, size: number) {
    return font.widthOfTextAtSize(this.clean(t), size)
  }

  textCentered(t: string, y: number, font: PDFFont, size: number, color: RGB = COLORS.text) {
    const w = this.width(t, font, size)
    this.text(t, (this.size.width - w) / 2, y, { font, size, color })
  }

  textRight(t: string, xRight: number, y: number, font: PDFFont, size: number, color: RGB = COLORS.text, page?: PDFPage) {
    const w = this.width(t, font, size)
    this.text(t, xRight - w, y, { font, size, color, page })
  }

  rule(opts: { color?: RGB; thickness?: number; gapBefore?: number; gapAfter?: number } = {}) {
    this.y -= opts.gapBefore ?? 4
    this.page.drawLine({
      start: { x: this.margin.left, y: this.y },
      end: { x: this.size.width - this.margin.right, y: this.y },
      thickness: opts.thickness ?? 0.6,
      color: opts.color ?? COLORS.rule,
    })
    this.y -= opts.gapAfter ?? 8
  }

  /** Wrap mixed bold/regular runs into lines. */
  wrapRuns(runs: Run[], maxWidth: number, size: number, regular: PDFFont, bold: PDFFont) {
    type Word = { text: string; font: PDFFont; width: number }
    const words: (Word & { spaceBefore: boolean })[] = []
    // whitespace at the end of one run must separate it from the next run
    let pendingSpace = false
    for (const r of runs) {
      const font = r.bold ? bold : regular
      const t = this.clean(r.text)
      const tokens = t.split(/(\s+)/)
      for (const tok of tokens) {
        if (!tok) continue
        if (/^\s+$/.test(tok)) {
          pendingSpace = true
          continue
        }
        // break very long tokens
        let rest = tok
        while (font.widthOfTextAtSize(rest, size) > maxWidth) {
          let cut = rest.length - 1
          while (cut > 1 && font.widthOfTextAtSize(rest.slice(0, cut), size) > maxWidth) cut--
          words.push({ text: rest.slice(0, cut), font, width: font.widthOfTextAtSize(rest.slice(0, cut), size), spaceBefore: pendingSpace })
          pendingSpace = false
          rest = rest.slice(cut)
        }
        words.push({ text: rest, font, width: font.widthOfTextAtSize(rest, size), spaceBefore: pendingSpace || false })
        pendingSpace = false
      }
    }
    const spaceW = regular.widthOfTextAtSize(' ', size)
    // Words not preceded by whitespace (e.g. punctuation after a bold run) are glued
    // to the previous word so a line never starts with a stray quote or period.
    type W = Word & { spaceBefore: boolean }
    const units: { parts: W[]; width: number; spaceBefore: boolean }[] = []
    for (const w of words) {
      const last = units[units.length - 1]
      if (last && !w.spaceBefore) {
        last.parts.push(w)
        last.width += w.width
      } else {
        units.push({ parts: [w], width: w.width, spaceBefore: w.spaceBefore })
      }
    }
    const lines: { words: W[]; width: number }[] = []
    let cur: W[] = []
    let curW = 0
    for (const u of units) {
      const add = (cur.length && u.spaceBefore ? spaceW : 0) + u.width
      if (cur.length && curW + add > maxWidth) {
        lines.push({ words: cur, width: curW })
        cur = u.parts.map((p, i) => (i === 0 ? { ...p, spaceBefore: false } : p))
        curW = u.width
      } else {
        cur.push(...u.parts.map((p, i) => (i === 0 && !cur.length ? { ...p, spaceBefore: false } : p)))
        curW += add
      }
    }
    if (cur.length) lines.push({ words: cur, width: curW })
    return { lines, spaceW }
  }

  paragraph(runs: Run[], opts: {
    size?: number; lineHeight?: number; indent?: number; firstLinePrefix?: string; align?: 'left' | 'justify' | 'center'
    regular?: PDFFont; bold?: PDFFont; color?: RGB; spaceAfter?: number
  } = {}) {
    const size = opts.size ?? 10.5
    const lh = opts.lineHeight ?? size * 1.38
    const indent = opts.indent ?? 0
    const regular = opts.regular ?? this.fonts.serif
    const bold = opts.bold ?? this.fonts.serifBold
    const maxWidth = this.contentWidth - indent
    const { lines, spaceW } = this.wrapRuns(runs, maxWidth, size, regular, bold)
    lines.forEach((line, idx) => {
      this.ensure(lh)
      this.y -= lh
      const isLast = idx === lines.length - 1
      let x = this.margin.left + indent
      if (idx === 0 && opts.firstLinePrefix) {
        this.text(opts.firstLinePrefix, this.margin.left + indent - 16, this.y, { font: regular, size, color: opts.color })
      }
      let gap = spaceW
      const gaps = line.words.filter((w, i) => i > 0 && w.spaceBefore).length
      if (opts.align === 'center') x += (maxWidth - line.width) / 2
      else if ((opts.align ?? 'justify') === 'justify' && !isLast && gaps > 0) {
        const extra = (maxWidth - line.width) / gaps
        if (extra < spaceW * 3) gap = spaceW + extra
      }
      line.words.forEach((w, i) => {
        if (i > 0 && w.spaceBefore) x += gap
        this.page.drawText(w.text, { x, y: this.y, size, font: w.font, color: opts.color ?? COLORS.text })
        x += w.width
      })
    })
    this.y -= opts.spaceAfter ?? 5
  }

  /** Table with repeated header rows on page breaks. */
  table(opts: {
    columns: { label: string; width: number; align?: 'left' | 'right' | 'center' }[]
    rows: string[][]
    totals?: string[]
    size?: number
    rowHeight?: number
    headerFill?: RGB
    zebra?: boolean
    wrap?: boolean
  }) {
    const size = opts.size ?? 8
    const pad = 3.5
    const totalW = opts.columns.reduce((a, c) => a + c.width, 0)
    const scale = this.contentWidth / totalW
    const cols = opts.columns.map((c) => ({ ...c, width: c.width * scale }))
    const baseH = opts.rowHeight ?? size + 6
    const font = this.fonts.sans
    const bold = this.fonts.sansBold

    const cellLines = (text: string, w: number, f: PDFFont) => {
      if (!opts.wrap) return [this.clean(text)]
      return this.wrapRuns([{ text, bold: f === bold }], w - pad * 2, size, font, bold).lines.map((l) =>
        l.words.map((x, i) => (i > 0 && x.spaceBefore ? ' ' : '') + x.text).join(''))
    }

    let drawingHeader = false
    const drawRow = (cells: string[], f: PDFFont, fill?: RGB) => {
      const linesPer = cells.map((c, i) => cellLines(c ?? '', cols[i].width, f))
      const h = Math.max(baseH, Math.max(...linesPer.map((l) => l.length)) * (size + 2.5) + 4)
      if (this.y - h < this.margin.bottom) {
        this.newPage()
        if (!drawingHeader) drawHeader()
      }
      const top = this.y
      if (fill) {
        this.page.drawRectangle({ x: this.margin.left, y: top - h, width: this.contentWidth, height: h, color: fill })
      }
      let x = this.margin.left
      cells.forEach((_, i) => {
        const c = cols[i]
        linesPer[i].forEach((ln, li) => {
          const ty = top - pad - size - li * (size + 2.5) + 1
          const tw = f.widthOfTextAtSize(ln, size)
          const tx = c.align === 'right' ? x + c.width - pad - tw : c.align === 'center' ? x + (c.width - tw) / 2 : x + pad
          this.page.drawText(ln, { x: tx, y: ty, size, font: f, color: COLORS.text })
        })
        x += c.width
      })
      this.page.drawLine({ start: { x: this.margin.left, y: top - h }, end: { x: this.margin.left + this.contentWidth, y: top - h }, thickness: 0.4, color: COLORS.rule })
      this.y -= h
      return h
    }

    function drawHeader(this: void) {
      drawingHeader = true
      self.ensure(baseH * 2.2)
      self.page.drawLine({ start: { x: self.margin.left, y: self.y }, end: { x: self.margin.left + self.contentWidth, y: self.y }, thickness: 0.6, color: COLORS.rule })
      drawRow(cols.map((c) => c.label), bold, opts.headerFill ?? COLORS.shade)
      drawingHeader = false
    }
    // eslint-disable-next-line @typescript-eslint/no-this-alias
    const self = this

    drawHeader()
    opts.rows.forEach((r, idx) => {
      drawRow(r, font, opts.zebra && idx % 2 === 1 ? rgb(0.98, 0.985, 0.99) : undefined)
    })
    if (opts.totals) drawRow(opts.totals, bold, COLORS.shade)
    this.y -= 6
  }

  /** Draw footers on all pages once the page count is known. */
  footer(draw: (page: PDFPage, index: number, total: number) => void) {
    const total = this.pages.length
    this.pages.forEach((p, i) => draw(p, i, total))
  }

  async save() {
    return await this.pdf.save({ useObjectStreams: false })
  }
}

export async function embedImage(pdf: PDFDocument, bytes: Uint8Array) {
  const isPng = bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47
  const isJpg = bytes[0] === 0xff && bytes[1] === 0xd8
  if (isPng) return await pdf.embedPng(bytes)
  if (isJpg) return await pdf.embedJpg(bytes)
  return null
}
