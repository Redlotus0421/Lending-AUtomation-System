// Public, token-based signing endpoint for borrowers (no account needed).
// Every action is rate limited; documents are only revealed after identity verification.
//   { action: 'load', token }                      -> link status + minimal context
//   { action: 'verify', token, answer }            -> one-time nonce
//   { action: 'document', token, nonce }           -> loan summary + short-lived PDF URL
//   { action: 'sign', token, nonce, signature, consent: true, typed_name }
//   { action: 'download', token, nonce }           -> short-lived URL to the latest (signed) PDF
import { adminClient, clientIp, dbError, decodeDataUrlPng, HttpError, json, readJson, serve, sha256Hex } from '../_shared/http.ts'
import { CONSENT_TEXT } from '../_shared/pdf/agreement.ts'
import { commitSignature, loadRenderData, signedUrl } from '../_shared/signing.ts'

type Body = { action?: string; token?: string; nonce?: string; answer?: string; signature?: string; consent?: boolean; typed_name?: string }

serve(async (req) => {
  const body = await readJson<Body>(req)
  const admin = adminClient()
  const ip = clientIp(req)
  const ua = req.headers.get('user-agent') ?? ''

  const limited = async (key: string, limit: number, windowSeconds: number) => {
    const { data } = await admin.rpc('lf_rate_limit', { p_key: key, p_limit: limit, p_window_seconds: windowSeconds })
    if (data === false) throw new HttpError(429, 'Too many requests. Please wait a few minutes and try again.')
  }
  await limited(`sign-ip:${ip}`, 120, 600)

  const token = (body.token ?? '').trim()
  if (!token || token.length < 20 || token.length > 200) throw new HttpError(400, 'Invalid signing link')
  const tokenHash = await sha256Hex(token)
  await limited(`sign-token:${tokenHash.slice(0, 24)}`, 60, 600)

  const { data: found, error } = await admin.rpc('signing_lookup', { p_token_hash: tokenHash })
  if (error) dbError(error)
  if (!found) throw new HttpError(404, 'This signing link is invalid. Please ask your lender for a new link.')
  const session = found.session

  const requireNonce = async () => {
    if (!body.nonce || !session.verify_nonce_hash || (await sha256Hex(body.nonce)) !== session.verify_nonce_hash) {
      throw new HttpError(401, 'Please verify your identity again.', 'verify_required')
    }
    if (!['verified', 'signed'].includes(session.status)) throw new HttpError(410, `This signing link is ${session.status}.`)
    if (new Date(session.expires_at) < new Date()) throw new HttpError(410, 'This signing link has expired.')
  }

  const context = async (full: boolean) => {
    const d = await loadRenderData(admin, found.agreement_id)
    const s = d.summary
    const base = {
      session: {
        signer_role: session.signer_role,
        signer_name: full ? session.signer_name : session.signer_name.split(' ')[0],
        status: session.status,
        expires_at: session.expires_at,
        verification_method: session.verification_method,
        signed_at: session.signed_at,
        locked_until: session.locked_until,
      },
      agreement: {
        agreement_number: d.agreement.agreement_number,
        status: d.agreement.status,
        version: d.agreement.version,
        required_signers: d.agreement.required_signers,
        signing_order: d.agreement.signing_order,
      },
      lender: { name: d.organization.legal_name || d.organization.name, logo: null },
    }
    if (!full) return base
    return {
      ...base,
      consent_text: CONSENT_TEXT(d.agreement.agreement_number),
      loan: {
        loan_number: d.loan.loan_number,
        principal: s.principal,
        net_proceeds: s.net_proceeds,
        interest_method: s.interest_method,
        interest_rate: s.interest_rate,
        rate_period: s.rate_period,
        annual_nominal_rate: s.annual_nominal_rate,
        eir_monthly_pct: s.eir_monthly_pct,
        effective_annual_rate_pct: s.effective_annual_rate_pct,
        total_interest: s.total_interest,
        total_charges: s.total_charges,
        total_repayment: s.total_repayment,
        installment_amount: s.installment_amount,
        installment_count: s.installment_count,
        repayment_frequency: s.repayment_frequency,
        first_due_date: s.first_due_date,
        last_due_date: s.last_due_date,
      },
      signatures: (d.signatures ?? []).map((x: any) => ({ signer_role: x.signer_role, signer_name: x.signer_name, signed_at: x.signed_at })),
      pdf_url: await signedUrl(admin, 'agreements', (await currentPath(found.agreement_id)) ?? d.agreement.unsigned_pdf_path, 900),
    }
  }

  const currentPath = async (agreementId: string): Promise<string | null> => {
    const { data } = await admin.from('loan_agreements').select('current_pdf_path, signed_pdf_path').eq('id', agreementId).single()
    return data?.signed_pdf_path ?? data?.current_pdf_path ?? null
  }

  switch (body.action) {
    case 'load': {
      if (['pending', 'opened', 'verified'].includes(session.status)) {
        await admin.rpc('signing_mark_opened', { p_session_id: session.id, p_ip: ip, p_ua: ua })
      }
      return json(await context(false))
    }
    case 'verify': {
      await limited(`sign-verify:${session.id}`, 10, 900)
      const { data, error: e } = await admin.rpc('signing_verify', { p_session_id: session.id, p_answer: body.answer ?? '', p_ip: ip, p_ua: ua })
      if (e) dbError(e)
      if (!data.ok) return json({ ok: false, error: data.error, attempts_left: data.attempts_left, locked_until: data.locked_until }, 200)
      return json({ ok: true, nonce: data.nonce })
    }
    case 'document': {
      await requireNonce()
      return json(await context(true))
    }
    case 'download': {
      await requireNonce()
      const path = await currentPath(found.agreement_id)
      if (!path) throw new HttpError(404, 'Document not available')
      await admin.rpc('agreement_log_download', {
        p_agreement_id: found.agreement_id, p_session: session.id, p_actor_type: 'signer', p_user: null, p_ip: ip, p_ua: ua,
      })
      const { data: a } = await admin.from('loan_agreements').select('agreement_number, status').eq('id', found.agreement_id).single()
      const name = `${a?.agreement_number ?? 'agreement'}${a?.status === 'fully_signed' ? '-signed' : ''}.pdf`
      return json({ url: await signedUrl(admin, 'agreements', path, 600, name), status: a?.status })
    }
    case 'sign': {
      await requireNonce()
      if (session.status === 'signed') {
        // idempotent: the signature is already committed
        return json({ ok: true, duplicate: true, ...(await context(true)) })
      }
      if (body.consent !== true) throw new HttpError(400, 'Please confirm your consent to sign electronically.')
      const typed = (body.typed_name ?? '').trim().toLowerCase().replace(/\s+/g, ' ')
      const expected = String(session.signer_name).trim().toLowerCase().replace(/\s+/g, ' ')
      if (session.signer_role === 'borrower' && typed && typed !== expected) {
        throw new HttpError(400, 'The name you typed does not match the borrower name on the agreement.')
      }
      const png = decodeDataUrlPng(body.signature ?? '')
      const out = await commitSignature(admin, {
        agreementId: found.agreement_id,
        role: session.signer_role,
        sessionId: session.id,
        signerName: session.signer_name,
        png,
        ip,
        userAgent: ua,
      })
      return json({ ok: true, duplicate: !!(out.commit as any)?.duplicate, result: out.result })
    }
    default:
      throw new HttpError(400, 'Unknown action')
  }
})
