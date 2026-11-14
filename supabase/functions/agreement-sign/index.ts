// In-app signing for staff (lender representative, in-person witnesses), borrowers signing in person
// on a staff member's device (Simplified Lending), and PDF recovery.
// POST { action: 'sign', agreement_id, role: 'lender'|'witness_1'|'witness_2', signature: dataURL, consent: true, signer_name? }
// POST { action: 'sign', agreement_id, role: 'borrower', in_person: true, signature: dataURL, consent: true }
// POST { action: 'rebuild', agreement_id }   — idempotent re-embed of all committed signatures
import {
  adminClient, clientIp, decodeDataUrlPng, HttpError, json, readJson, requirePermission, requireUser, serve,
} from '../_shared/http.ts'
import { commitSignature, rebuildSignedPdf } from '../_shared/signing.ts'

serve(async (req) => {
  const { client, access, userId } = await requireUser(req)
  const body = await readJson<{
    action?: string; agreement_id?: string; role?: string; signature?: string; consent?: boolean; signer_name?: string
    in_person?: boolean
  }>(req)
  if (!body.agreement_id) throw new HttpError(400, 'agreement_id is required')

  // RLS check: the caller must be able to see this agreement
  const { data: agreement, error } = await client.from('loan_agreements')
    .select('id, status, organization_id, required_signers').eq('id', body.agreement_id).maybeSingle()
  if (error) throw new HttpError(400, error.message)
  if (!agreement || agreement.organization_id !== access.organization_id) throw new HttpError(404, 'Agreement not found')
  const admin = adminClient()

  if (body.action === 'rebuild') {
    requirePermission(access, 'agreements.generate')
    return json(await rebuildSignedPdf(admin, agreement.id))
  }

  if (body.action !== 'sign') throw new HttpError(400, 'Unknown action')
  const role = body.role ?? 'lender'
  const inPersonBorrower = role === 'borrower' && body.in_person === true
  if (!['lender', 'witness_1', 'witness_2'].includes(role) && !inPersonBorrower) {
    throw new HttpError(400, 'Borrowers sign through their secure signing link or in person')
  }
  if (role === 'lender') requirePermission(access, 'agreements.sign_lender')
  else requirePermission(access, 'agreements.generate')
  if (body.consent !== true) throw new HttpError(400, 'Consent to sign electronically is required')
  if (!(agreement.required_signers as string[]).includes(role)) {
    throw new HttpError(400, `This agreement does not require a ${role.replace('_', ' ')} signature`)
  }

  const png = decodeDataUrlPng(body.signature ?? '')
  let signerName = role === 'lender' ? access.full_name : (body.signer_name ?? '').trim()
  if (inPersonBorrower) {
    // the borrower's name always comes from the loan record, never from the client
    const { data: loan, error: e2 } = await client.from('loan_agreements')
      .select('loans!inner(borrowers!inner(full_name))').eq('id', agreement.id).single()
    if (e2) throw new HttpError(400, e2.message)
    signerName = (loan as any)?.loans?.borrowers?.full_name ?? ''
  }
  if (!signerName) throw new HttpError(400, 'Signer name is required')

  const out = await commitSignature(admin, {
    agreementId: agreement.id,
    role,
    signerName,
    signerUserId: role === 'lender' ? userId : null,
    verificationMethod: inPersonBorrower ? 'in_person' : 'none',
    witnessedBy: inPersonBorrower ? userId : null,
    png,
    ip: clientIp(req),
    userAgent: req.headers.get('user-agent') ?? '',
  })
  return json(out)
})
