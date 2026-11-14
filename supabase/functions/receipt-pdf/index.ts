// POST { payment_id, download? } -> signed URL to the receipt PDF, rendered once from the frozen receipt snapshot.
import {
  adminClient, downloadBytes, HttpError, json, readJson, requirePermission, requireUser, serve, uploadOnce,
} from '../_shared/http.ts'
import { renderReceiptPdf } from '../_shared/pdf/receipt.ts'
import { signedUrl } from '../_shared/signing.ts'

serve(async (req) => {
  const { client, access } = await requireUser(req)
  requirePermission(access, 'payments.view')
  const { payment_id, download } = await readJson<{ payment_id?: string; download?: boolean }>(req)
  if (!payment_id) throw new HttpError(400, 'payment_id is required')

  const { data: receipt, error } = await client.from('payment_receipts')
    .select('id, organization_id, receipt_number, data, pdf_path, is_void').eq('payment_id', payment_id).maybeSingle()
  if (error) throw new HttpError(400, error.message)
  if (!receipt) throw new HttpError(404, 'Receipt not found')
  const admin = adminClient()
  const fileName = `${receipt.receipt_number}${receipt.is_void ? '-VOID' : ''}.pdf`
  const path = `${receipt.organization_id}/${fileName}`

  if (receipt.pdf_path !== path) {
    const { data: org } = await admin.from('organizations').select('logo_path').eq('id', receipt.organization_id).single()
    let logo: Uint8Array | null = null
    if (org?.logo_path) {
      try {
        logo = await downloadBytes(admin, 'branding', org.logo_path)
      } catch (_) {
        logo = null
      }
    }
    const bytes = await renderReceiptPdf(receipt.data, { isVoid: receipt.is_void, logo })
    await uploadOnce(admin, 'receipts', path, bytes, 'application/pdf')
    await admin.from('payment_receipts').update({ pdf_path: path }).eq('id', receipt.id)
  }
  return json({
    url: await signedUrl(admin, 'receipts', path, 600, download ? fileName : undefined),
    receipt_number: receipt.receipt_number,
  })
})
