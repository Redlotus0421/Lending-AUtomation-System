// POST { loan_id } — freeze terms, render the unsigned agreement PDF server-side,
// store it privately with its SHA-256 hash, and open the agreement for signing.
import {
  adminClient, dbError, downloadBytes, HttpError, json, readJson, requirePermission, requireUser, serve, sha256Hex, uploadOnce,
} from '../_shared/http.ts'
import { renderAgreementPdf } from '../_shared/pdf/agreement.ts'

serve(async (req) => {
  const { client, access } = await requireUser(req)
  requirePermission(access, 'agreements.generate')
  const { loan_id } = await readJson<{ loan_id?: string }>(req)
  if (!loan_id) throw new HttpError(400, 'loan_id is required')

  // agreement_begin runs as the caller: permission, status and access checks happen in the database
  const { data, error } = await client.rpc('agreement_begin', { p_loan_id: loan_id })
  if (error) dbError(error)
  const admin = adminClient()
  const agreementId = data.agreement.id as string

  try {
    let logo: Uint8Array | null = null
    if (data.organization.logo_path) {
      try {
        logo = await downloadBytes(admin, 'branding', data.organization.logo_path)
      } catch (_) {
        logo = null
      }
    }
    const pdf = await renderAgreementPdf(data, logo)
    const hash = await sha256Hex(pdf.bytes)
    const path = `${data.organization.id}/${agreementId}/unsigned-v${data.agreement.version}-${hash.slice(0, 16)}.pdf`
    await uploadOnce(admin, 'agreements', path, pdf.bytes, 'application/pdf')
    const { data: done, error: e2 } = await admin.rpc('agreement_complete', {
      p_agreement_id: agreementId,
      p_path: path,
      p_hash: hash,
      p_fields: pdf.fields,
      p_page_count: pdf.pageCount,
      p_placeholders: pdf.placeholders,
      p_rendered_body: pdf.renderedBody,
    })
    if (e2) dbError(e2)
    return json({ ...done, unsigned_pdf_path: path, unsigned_pdf_hash: hash, pages: pdf.pageCount, missing_placeholders: pdf.missing })
  } catch (e) {
    await admin.rpc('agreement_fail', { p_agreement_id: agreementId, p_error: (e as Error).message ?? String(e) })
    throw e
  }
})
