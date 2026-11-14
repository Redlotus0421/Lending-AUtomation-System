import { degrees, rgb } from 'npm:pdf-lib@1.17.1'
import { dateTimeManila, longDate, money } from '../format.ts'
import { A5, COLORS, DocWriter, embedImage } from './writer.ts'

export async function renderReceiptPdf(r: Record<string, any>, opts: { isVoid: boolean; logo?: Uint8Array | null }) {
  const w = await DocWriter.create(A5, { top: 40, bottom: 40, left: 36, right: 36 })
  w.newPage()
  const c = r.company ?? {}
  const right = w.size.width - w.margin.right

  // header band
  w.page.drawRectangle({ x: 0, y: w.size.height - 8, width: w.size.width, height: 8, color: COLORS.navy })
  let y = w.size.height - 40
  if (opts.logo) {
    try {
      const img = await embedImage(w.pdf, opts.logo)
      if (img) {
        const s = Math.min(48 / img.width, 30 / img.height, 1)
        w.page.drawImage(img, { x: w.margin.left, y: y - img.height * s + 10, width: img.width * s, height: img.height * s })
      }
    } catch (_) { /* ignore */ }
  }
  w.textRight(c.legal_name || c.name || '', right, y, w.fonts.sansBold, 11, COLORS.navy)
  if (c.address) { y -= 11; w.textRight(c.address, right, y, w.fonts.sans, 7, COLORS.muted) }
  const contact = [c.phone, c.email].filter(Boolean).join('  |  ')
  if (contact) { y -= 9; w.textRight(contact, right, y, w.fonts.sans, 7, COLORS.muted) }
  if (c.tin) { y -= 9; w.textRight(`TIN ${c.tin}`, right, y, w.fonts.sans, 7, COLORS.muted) }

  y -= 26
  w.text('PAYMENT RECEIPT', w.margin.left, y, { font: w.fonts.sansBold, size: 15, color: COLORS.text })
  w.textRight(`No. ${r.receipt_number}`, right, y, w.fonts.sansBold, 10, COLORS.red)
  y -= 14
  w.text(`Issued ${dateTimeManila(r.issued_at)}`, w.margin.left, y, { font: w.fonts.sans, size: 7.5, color: COLORS.muted })
  y -= 10
  w.page.drawLine({ start: { x: w.margin.left, y }, end: { x: right, y }, thickness: 0.8, color: COLORS.rule })
  y -= 22

  const row = (label: string, value: string, bold = false) => {
    w.text(label, w.margin.left, y, { font: w.fonts.sans, size: 8.5, color: COLORS.muted })
    w.textRight(value, right, y, bold ? w.fonts.sansBold : w.fonts.sans, 9, COLORS.text)
    y -= 15
  }
  row('Received from', r.borrower_name, true)
  row('Borrower code', r.borrower_code ?? '')
  row('Loan reference', r.loan_number)
  row('Payment date', longDate(r.payment_date))
  row('Payment method', String(r.method ?? '').replace('_', ' ').replace(/\b\w/g, (m: string) => m.toUpperCase()))
  if (r.reference_no) row('Transaction reference', r.reference_no)
  const inst = (r.installments ?? []) as { installment_no: number }[]
  if (inst.length) row('Installment(s)', inst.map((i) => `#${i.installment_no}`).join(', '))
  if (r.payment_type === 'settlement') row('Payment type', 'Early full settlement', true)

  y -= 4
  w.page.drawRectangle({ x: w.margin.left, y: y - 26, width: right - w.margin.left, height: 34, color: COLORS.shade })
  w.text('AMOUNT RECEIVED', w.margin.left + 10, y - 13, { font: w.fonts.sansBold, size: 9, color: COLORS.navy })
  w.textRight(money(r.amount), right - 10, y - 14, w.fonts.sansBold, 15, COLORS.text)
  y -= 48

  w.text('ALLOCATION', w.margin.left, y, { font: w.fonts.sansBold, size: 8, color: COLORS.navy })
  y -= 14
  row('Principal', money(r.principal_paid))
  row('Interest', money(r.interest_paid))
  row('Charges', money(r.charges_paid))
  row('Penalties', money(r.penalty_paid))
  if (Number(r.interest_rebated) > 0) row('Unearned interest rebated', money(r.interest_rebated))
  w.page.drawLine({ start: { x: w.margin.left, y: y + 6 }, end: { x: right, y: y + 6 }, thickness: 0.5, color: COLORS.rule })
  y -= 4
  row('Remaining loan balance', money(r.remaining_balance), true)
  row('Outstanding principal', money(r.outstanding_principal))

  y -= 16
  w.page.drawLine({ start: { x: right - 150, y }, end: { x: right, y }, thickness: 0.6, color: COLORS.text })
  y -= 10
  w.textRight(r.collector_name || r.recorded_by || '', right, y, w.fonts.sansBold, 8.5)
  y -= 9
  w.textRight('Received by', right, y, w.fonts.sans, 7, COLORS.muted)

  if (r.footer_note) {
    const lines = w.wrapRuns([{ text: r.footer_note, bold: false }], w.contentWidth, 6.5, w.fonts.sans, w.fonts.sansBold).lines
    let fy = 30 + lines.length * 8
    for (const l of lines) {
      w.text(l.words.map((x, i) => (i > 0 && x.spaceBefore ? ' ' : '') + x.text).join(''), w.margin.left, fy,
        { font: w.fonts.sans, size: 6.5, color: COLORS.muted })
      fy -= 8
    }
  }
  w.text(`Payment ${r.payment_number}`, w.margin.left, 20, { font: w.fonts.sans, size: 6, color: COLORS.muted })

  if (opts.isVoid) {
    w.page.drawText('VOID', {
      x: 90, y: 200, size: 110, font: w.fonts.sansBold, color: rgb(0.86, 0.15, 0.15), opacity: 0.18, rotate: degrees(35),
    })
  }
  w.pdf.setTitle(`Receipt ${r.receipt_number}`)
  w.pdf.setProducer('LendFlow')
  return await w.save()
}
