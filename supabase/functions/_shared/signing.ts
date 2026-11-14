// Shared signing pipeline: store signature -> commit (DB transaction) -> rebuild
// the signed PDF from the frozen unsigned PDF + every committed signature ->
// hash -> store -> compare-and-set. Safe to retry at any point.
import type { SupabaseClient } from 'npm:@supabase/supabase-js@2'
import { CONSENT_TEXT, embedSignatures, type SignatureRecord } from './pdf/agreement.ts'
import { dbError, downloadBytes, HttpError, sha256Hex, uploadOnce } from './http.ts'

export async function loadRenderData(admin: SupabaseClient, agreementId: string) {
  const { data, error } = await admin.rpc('lf_agreement_render_data', { p_agreement_id: agreementId })
  if (error) dbError(error)
  if (!data) throw new HttpError(404, 'Agreement not found')
  return data as any
}

export async function commitSignature(admin: SupabaseClient, p: {
  agreementId: string
  role: string
  sessionId?: string | null
  signerName: string
  signerUserId?: string | null
  /** 'in_person' when a staff member witnesses the borrower signing on their device */
  verificationMethod?: 'none' | 'in_person'
  witnessedBy?: string | null
  png: Uint8Array
  ip: string
  userAgent: string
}) {
  const data = await loadRenderData(admin, p.agreementId)
  const org = data.organization.id as string
  const imageHash = await sha256Hex(p.png)
  const imagePath = `${org}/${p.agreementId}/signatures/${p.role}-${imageHash.slice(0, 16)}.png`
  await uploadOnce(admin, 'agreements', imagePath, p.png, 'image/png')

  const { data: commit, error } = await admin.rpc('signature_commit', {
    p: {
      agreement_id: p.agreementId,
      signer_role: p.role,
      session_id: p.sessionId ?? null,
      signer_name: p.signerName,
      signer_user_id: p.signerUserId ?? null,
      verification_method: p.verificationMethod ?? null,
      witnessed_by: p.witnessedBy ?? null,
      image_path: imagePath,
      image_hash: imageHash,
      consent_text: CONSENT_TEXT(data.agreement.agreement_number),
      consent_given_at: new Date().toISOString(),
      ip: p.ip,
      user_agent: p.userAgent.slice(0, 400),
    },
  })
  if (error) dbError(error)
  const result = await rebuildSignedPdf(admin, p.agreementId)
  return { commit, result }
}

/** Deterministically rebuild the signed PDF; finalizes when every required signature is present. */
export async function rebuildSignedPdf(admin: SupabaseClient, agreementId: string) {
  const data = await loadRenderData(admin, agreementId)
  const a = data.agreement
  const signatures = (data.signatures ?? []) as (SignatureRecord & { signature_image_path: string })[]
  if (!a.unsigned_pdf_path) throw new HttpError(409, 'Agreement PDF has not been generated')
  if (a.status === 'fully_signed') return { finalized: true, already: true }
  if (!signatures.length) return { finalized: false, signatures: 0 }

  const unsigned = await downloadBytes(admin, 'agreements', a.unsigned_pdf_path)
  const images: Record<string, Uint8Array> = {}
  for (const s of signatures) images[s.id] = await downloadBytes(admin, 'agreements', s.signature_image_path)

  const required: string[] = a.required_signers ?? []
  const allSigned = required.every((r) => signatures.some((s) => s.signer_role === r))
  const bytes = await embedSignatures({ unsigned, data, signatures, images, final: allSigned })
  const hash = await sha256Hex(bytes)
  const path = `${data.organization.id}/${agreementId}/${allSigned ? 'signed-final' : 'signed'}-${signatures.length}-${hash.slice(0, 16)}.pdf`
  await uploadOnce(admin, 'agreements', path, bytes, 'application/pdf')
  const { data: cas, error } = await admin.rpc('agreement_set_pdf', {
    p_agreement_id: agreementId, p_path: path, p_hash: hash, p_signature_count: signatures.length,
  })
  if (error) dbError(error)
  return { ...(cas as Record<string, unknown>), path, hash }
}

export async function signedUrl(admin: SupabaseClient, bucket: string, path: string, seconds = 600, download?: string) {
  const { data, error } = await admin.storage.from(bucket).createSignedUrl(path, seconds, download ? { download } : undefined)
  if (error || !data) throw new HttpError(500, `Could not create a download link: ${error?.message}`)
  return data.signedUrl
}
