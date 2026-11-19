// Server-side agreement PDF rendering and permanent signature embedding.
import { PDFDocument, rgb, StandardFonts } from 'npm:pdf-lib@1.17.1'
import {
  buildPlaceholderValues, disclosureRows, fillPlaceholders, orgAddress, parseTemplate, type RenderData, withAutomaticClauses,
} from '../template.ts'
import { dateTimeManila, longDate, money, plainAmount, shortDate, SIGNER_LABEL } from '../format.ts'
import { COLORS, DocWriter, embedImage } from './writer.ts'

export type SignatureField = { page: number; x: number; y: number; width: number; height: number; textY: number; label: string }

export const CONSENT_TEXT = (agreementNumber: string) =>
  `I have read and understood Loan Agreement ${agreementNumber}, including the Disclosure Statement and Repayment Schedule. ` +
  'I agree to sign this agreement electronically and acknowledge that my electronic signature has the same legal effect as my ' +
  'handwritten signature under the Electronic Commerce Act of 2000 (R.A. No. 8792).'

/** Render the unsigned agreement. Returns the PDF bytes and the signature field coordinates. */
export async function renderAgreementPdf(data: RenderData, logo?: Uint8Array | null) {
  const w = await DocWriter.create()
  const org = data.organization
  const lenderName = org.legal_name || org.name
  const values = buildPlaceholderValues(data)
  const { text, missing } = fillPlaceholders(withAutomaticClauses(data.template.body, data), values)
  const blocks = parseTemplate(text)
  const settings = data.template.settings ?? {}
  const scheduleSize = Number(settings.schedule_font_size ?? 7.5)
  const title = (data.agreement.kind === 'amendment' ? 'Amended ' : '') + (data.template.title || 'Loan Agreement')

  w.onNewPage = (doc, index) => {
    if (index === 0) return
    doc.text(lenderName, doc.margin.left, doc.size.height - 40, { font: doc.fonts.sans, size: 7.5, color: COLORS.muted })
    doc.textRight(`Agreement No. ${data.agreement.agreement_number}`, doc.size.width - doc.margin.right, doc.size.height - 40,
      doc.fonts.sans, 7.5, COLORS.muted)
    doc.page.drawLine({
      start: { x: doc.margin.left, y: doc.size.height - 46 }, end: { x: doc.size.width - doc.margin.right, y: doc.size.height - 46 },
      thickness: 0.5, color: COLORS.rule,
    })
  }
  w.newPage()

  // ---- letterhead ----
  w.y = w.size.height - 46
  if (logo && settings.show_logo !== false) {
    try {
      const img = await embedImage(w.pdf, logo)
      if (img) {
        const scale = Math.min(110 / img.width, 42 / img.height, 1)
        const iw = img.width * scale
        const ih = img.height * scale
        w.page.drawImage(img, { x: (w.size.width - iw) / 2, y: w.y - ih, width: iw, height: ih })
        w.y -= ih + 8
      }
    } catch (_) { /* ignore broken logos */ }
  }
  w.y -= 12
  w.textCentered(lenderName, w.y, w.fonts.serifBold, 13, COLORS.navy)
  const addr = orgAddress(org)
  const contact = [org.contact_phone, org.contact_email, org.website].filter(Boolean).join('  |  ')
  if (addr) { w.y -= 12; w.textCentered(addr, w.y, w.fonts.sans, 8, COLORS.muted) }
  if (contact) { w.y -= 10; w.textCentered(contact, w.y, w.fonts.sans, 8, COLORS.muted) }
  if (org.registration_no) { w.y -= 10; w.textCentered(`SEC Reg. / CA No. ${org.registration_no}`, w.y, w.fonts.sans, 7.5, COLORS.muted) }
  w.y -= 8
  w.page.drawLine({ start: { x: w.margin.left, y: w.y }, end: { x: w.size.width - w.margin.right, y: w.y }, thickness: 1.2, color: COLORS.navy })
  w.y -= 26
  w.textCentered(title.toUpperCase(), w.y, w.fonts.serifBold, 15, COLORS.text)
  w.y -= 14
  w.textCentered(
    `Agreement No. ${data.agreement.agreement_number}   ·   Loan Ref. ${data.loan.loan_number}   ·   Version ${data.agreement.version}`,
    w.y, w.fonts.sans, 8, COLORS.muted)
  w.y -= 18

  const fields: Record<string, SignatureField> = {}

  for (const b of blocks) {
    switch (b.type) {
      case 'heading': {
        const size = b.level === 1 ? 13 : b.level === 2 ? 11 : 10.5
        w.ensure(size * 3.2)
        w.space(b.level === 3 ? 4 : 8)
        w.paragraph(b.runs.map((r) => ({ ...r, bold: true })), { size, align: 'left', spaceAfter: 2 })
        break
      }
      case 'paragraph':
        w.paragraph(b.runs, { size: 10.5 })
        break
      case 'list':
        b.items.forEach((item, i) => {
          w.paragraph(item, { size: 10.5, indent: 20, firstLinePrefix: b.ordered ? `${i + 1}.` : '•', spaceAfter: 2 })
        })
        w.space(3)
        break
      case 'rule':
        w.rule({ gapBefore: 6, gapAfter: 10 })
        break
      case 'disclosure': {
        w.table({
          columns: [{ label: 'Item', width: 52 }, { label: 'Amount / Terms', width: 48 }],
          rows: disclosureRows(data).map(([k, v]) => [k, v]),
          size: 8.5,
          wrap: true,
        })
        break
      }
      case 'schedule': {
        let tp = 0, ti = 0, tc = 0, tt = 0
        const rows = data.schedule.map((r) => {
          tp += Number(r.principal); ti += Number(r.interest); tc += Number(r.charges); tt += Number(r.total)
          return [String(r.installment_no), shortDate(r.due_date), plainAmount(r.opening_balance), plainAmount(r.principal),
            plainAmount(r.interest), plainAmount(r.charges), plainAmount(r.total), plainAmount(r.closing_balance)]
        })
        w.table({
          columns: [
            { label: '#', width: 5, align: 'center' }, { label: 'Due date', width: 13 },
            { label: 'Opening balance', width: 14, align: 'right' }, { label: 'Principal', width: 13, align: 'right' },
            { label: 'Interest', width: 12, align: 'right' }, { label: 'Charges', width: 11, align: 'right' },
            { label: 'Total payment', width: 14, align: 'right' }, { label: 'Closing balance', width: 14, align: 'right' },
          ],
          rows,
          totals: ['', 'TOTAL', '', plainAmount(tp), plainAmount(ti), plainAmount(tc), plainAmount(tt), ''],
          size: scheduleSize,
          zebra: true,
        })
        w.paragraph([{ text: `Amounts in Philippine Pesos (PHP). Interest method: ${data.summary.interest_method === 'flat' ? 'flat' : 'diminishing balance'}.`, bold: false }],
          { size: 7.5, regular: w.fonts.sans, bold: w.fonts.sansBold, color: COLORS.muted, align: 'left' })
        break
      }
      case 'signatures': {
        const signers = data.agreement.required_signers
        const colW = (w.contentWidth - 30) / 2
        const blockH = 140
        w.space(10)
        for (let i = 0; i < signers.length; i += 2) {
          w.ensure(blockH + 10)
          const top = w.y
          for (let c = 0; c < 2 && i + c < signers.length; c++) {
            const role = signers[i + c]
            const x = w.margin.left + c * (colW + 30)
            const isLender = role === 'lender'
            const name = role === 'borrower' ? data.borrower.full_name : role === 'co_maker' ? (data.co_maker?.full_name || '') : isLender ? (data.officer?.full_name || '') : ''
            const label = role === 'borrower' ? 'BORROWER' : role === 'co_maker' ? 'CO-MAKER' : isLender ? 'LENDER' : 'WITNESS'
            w.text(label, x, top - 10, { font: w.fonts.sansBold, size: 8, color: COLORS.navy })
            if (isLender) w.text(`For and on behalf of ${lenderName}`, x, top - 20, { font: w.fonts.sans, size: 7, color: COLORS.muted })
            const sigBottom = top - 82
            fields[role] = { page: w.pages.length - 1, x, y: sigBottom + 2, width: colW, height: 54, textY: sigBottom - 32, label }
            w.page.drawLine({ start: { x, y: sigBottom }, end: { x: x + colW, y: sigBottom }, thickness: 0.8, color: COLORS.text })
            w.text(name || '______________________________', x, sigBottom - 11, { font: w.fonts.serifBold, size: 9.5 })
            w.text(role === 'borrower' ? 'Signature over printed name' : isLender ? 'Authorized representative' : 'Signature over printed name',
              x, sigBottom - 21, { font: w.fonts.sans, size: 7, color: COLORS.muted })
          }
          w.y = top - blockH
        }
        break
      }
    }
  }

  // ---- footers ----
  w.footer((page, i, total) => {
    const y = 34
    page.drawLine({ start: { x: w.margin.left, y: y + 12 }, end: { x: w.size.width - w.margin.right, y: y + 12 }, thickness: 0.4, color: COLORS.rule })
    w.text(`${data.agreement.agreement_number} · v${data.agreement.version} · Loan ${data.loan.loan_number}`, w.margin.left, y,
      { font: w.fonts.sans, size: 7, color: COLORS.muted, page })
    const label = `Page ${i + 1} of ${total}`
    w.textRight(label, w.size.width - w.margin.right, y, w.fonts.sans, 7, COLORS.muted, page)
    if (settings.footer_text) {
      const ft = String(settings.footer_text)
      const fw = w.width(ft, w.fonts.sans, 6.5)
      w.text(ft, (w.size.width - fw) / 2, y - 10, { font: w.fonts.sans, size: 6.5, color: COLORS.muted, page })
    }
  })

  w.pdf.setTitle(`${title} ${data.agreement.agreement_number}`)
  w.pdf.setSubject(`Loan ${data.loan.loan_number} — ${data.borrower.full_name}`)
  w.pdf.setAuthor(lenderName)
  w.pdf.setProducer('LendFlow')
  w.pdf.setCreator('LendFlow Lending Management System')
  w.pdf.setCreationDate(new Date(data.agreement.generated_at))
  w.pdf.setModificationDate(new Date(data.agreement.generated_at))

  const bytes = await w.save()
  return { bytes, fields, pageCount: w.pages.length, missing, renderedBody: text, placeholders: values }
}

export type SignatureRecord = {
  id: string; signer_role: string; signer_name: string; signed_at: string; ip_address?: string | null
  user_agent?: string | null; verification_method?: string | null; signature_image_hash: string; consent_given_at?: string
}

/**
 * Permanently embed signature images (and signer name + timestamp) into the
 * unsigned PDF. When `final` is true, an electronic signature certificate page is appended.
 */
export async function embedSignatures(opts: {
  unsigned: Uint8Array
  data: RenderData & { agreement: RenderData['agreement'] & { signature_fields?: Record<string, SignatureField>; unsigned_pdf_hash?: string } }
  signatures: SignatureRecord[]
  images: Record<string, Uint8Array>
  final: boolean
}) {
  const pdf = await PDFDocument.load(opts.unsigned, { updateMetadata: false })
  const sans = await pdf.embedFont(StandardFonts.Helvetica)
  const sansBold = await pdf.embedFont(StandardFonts.HelveticaBold)
  const serifBold = await pdf.embedFont(StandardFonts.TimesRomanBold)
  const pages = pdf.getPages()
  const fields = (opts.data.agreement.signature_fields ?? {}) as Record<string, SignatureField>
  const clean = (t: string) => String(t ?? '')
    .replace(/[–—]/g, '-').replace(/[‘’]/g, "'").replace(/[“”]/g, '"').replace(/…/g, '...')
    .replace(/[^ -~ -ÿ]/g, '?')

  for (const s of opts.signatures) {
    const f = fields[s.signer_role]
    const imgBytes = opts.images[s.id]
    if (!f || !imgBytes) continue
    const page = pages[f.page]
    if (!page) continue
    const img = await pdf.embedPng(imgBytes)
    const maxW = f.width - 10
    const maxH = f.height - 4
    const scale = Math.min(maxW / img.width, maxH / img.height)
    const iw = img.width * scale
    const ih = img.height * scale
    page.drawImage(img, { x: f.x + (f.width - iw) / 2, y: f.y, width: iw, height: ih })
    // overwrite the printed-name placeholder line for witnesses with their real name
    if (s.signer_role.startsWith('witness')) {
      page.drawRectangle({ x: f.x, y: f.y - 2 - 14, width: f.width, height: 12, color: rgb(1, 1, 1) })
      page.drawText(clean(s.signer_name), { x: f.x, y: f.y - 2 - 11, size: 9.5, font: serifBold, color: COLORS.text })
    }
    page.drawText(clean(`Signed electronically by ${s.signer_name}`), { x: f.x, y: f.textY, size: 6.5, font: sansBold, color: COLORS.navy })
    page.drawText(clean(`${dateTimeManila(s.signed_at)}  ·  Signature ID ${s.id.slice(0, 8).toUpperCase()}`),
      { x: f.x, y: f.textY - 8.5, size: 6.5, font: sans, color: COLORS.muted })
  }

  if (opts.final) {
    const d = opts.data
    const [pw, ph] = [595.28, 841.89]
    const page = pdf.addPage([pw, ph])
    const left = 62
    let y = ph - 70
    const line = (label: string, value: string, size = 8.5) => {
      page.drawText(clean(label), { x: left, y, size, font: sansBold, color: COLORS.text })
      const words = clean(value).split(' ')
      let cur = ''
      let first = true
      const maxW = pw - left - 62 - 150
      for (const word of words) {
        const test = cur ? `${cur} ${word}` : word
        if (sans.widthOfTextAtSize(test, size) > maxW && cur) {
          page.drawText(cur, { x: left + 150, y, size, font: sans, color: COLORS.text })
          y -= size + 3
          cur = word
          first = false
        } else cur = test
      }
      page.drawText(cur, { x: left + 150, y, size, font: sans, color: COLORS.text })
      void first
      y -= size + 6
    }
    page.drawText('ELECTRONIC SIGNATURE CERTIFICATE', { x: left, y, size: 14, font: serifBold, color: COLORS.navy })
    y -= 12
    page.drawLine({ start: { x: left, y }, end: { x: pw - 62, y }, thickness: 1, color: COLORS.navy })
    y -= 20
    line('Agreement number', `${d.agreement.agreement_number} (version ${d.agreement.version})`)
    line('Loan reference', d.loan.loan_number)
    line('Lender', d.organization.legal_name || d.organization.name)
    line('Borrower', d.borrower.full_name)
    line('Principal / total payable', `${money(d.summary.principal)} / ${money(d.summary.total_repayment)}`)
    line('Document generated', `${longDate(d.agreement.generated_at)} — ${dateTimeManila(d.agreement.generated_at)}`)
    line('Original document SHA-256', d.agreement.unsigned_pdf_hash ?? '')
    line('Pages in signed document', `${pages.length} (plus this certificate)`)
    y -= 6
    page.drawText('SIGNING EVENTS', { x: left, y, size: 10, font: sansBold, color: COLORS.navy })
    y -= 16
    for (const s of opts.signatures) {
      page.drawRectangle({ x: left - 6, y: y - 92, width: pw - 124 + 12, height: 104, color: COLORS.shade })
      line('Signer', `${s.signer_name} — ${SIGNER_LABEL[s.signer_role] ?? s.signer_role}`)
      line('Signed at', dateTimeManila(s.signed_at))
      line('Identity check', s.verification_method === 'dob' ? 'Date of birth confirmed' : s.verification_method === 'id_last4' ? 'ID number confirmed' : s.verification_method === 'none' ? 'Authenticated staff session / none' : (s.verification_method ?? '—'))
      line('IP address', s.ip_address || '—')
      line('Device', (s.user_agent || '—').slice(0, 140))
      line('Signature image SHA-256', s.signature_image_hash)
      y -= 14
    }
    y -= 4
    const note = [
      'Each signer gave explicit consent to sign electronically: "' + CONSENT_TEXT(d.agreement.agreement_number) + '"',
      'The signature images above are permanently embedded in this PDF. A visual electronic signature is not a certificate-based digital signature; ' +
        'the SHA-256 fingerprint of this final document is recorded by the lender and can be used to verify that the document has not been altered.',
    ]
    for (const para of note) {
      const words = clean(para).split(' ')
      let cur = ''
      for (const word of words) {
        const test = cur ? `${cur} ${word}` : word
        if (sans.widthOfTextAtSize(test, 7.5) > pw - 124 && cur) {
          page.drawText(cur, { x: left, y, size: 7.5, font: sans, color: COLORS.muted })
          y -= 10
          cur = word
        } else cur = test
      }
      page.drawText(cur, { x: left, y, size: 7.5, font: sans, color: COLORS.muted })
      y -= 16
    }
    page.drawText(clean(`${d.agreement.agreement_number} · Signature Certificate`), { x: left, y: 34, size: 7, font: sans, color: COLORS.muted })
  }

  pdf.setModificationDate(new Date())
  return await pdf.save({ useObjectStreams: false })
}
