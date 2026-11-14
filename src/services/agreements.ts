import { supabase } from '@/lib/supabase'
import type {
  Agreement, AgreementEvent, AgreementSignature, AgreementTemplate, AgreementTemplateVersion, SigningSession,
} from '@/types/db'
import { invoke, must, rpc, signedUrl } from './api'

export type AgreementListItem = Agreement & {
  loans: { loan_number: string; borrower_id: string; status: string; borrowers: { full_name: string } | null } | null
}

export const templatesService = {
  async list(): Promise<(AgreementTemplate & { current: AgreementTemplateVersion | null })[]> {
    const rows = must(await supabase.from('loan_agreement_templates').select('*, current:loan_agreement_template_versions!loan_agreement_templates_current_version_fk(*)')
      .order('is_default', { ascending: false }).order('name')) as (AgreementTemplate & { current: AgreementTemplateVersion | null })[]
    return rows
  },
  async get(id: string) {
    const t = must(await supabase.from('loan_agreement_templates').select('*').eq('id', id).single()) as AgreementTemplate
    const versions = must(await supabase.from('loan_agreement_template_versions').select('*').eq('template_id', id)
      .order('version_no', { ascending: false })) as AgreementTemplateVersion[]
    return { template: t, versions }
  },
  save: (p: { id?: string; name: string; description?: string; title: string; body: string; settings?: Record<string, unknown>; change_note?: string; is_default?: boolean; is_active?: boolean }) =>
    rpc<{ id: string; version_id: string; version_no: number }>('save_agreement_template', { p }),
  duplicate: (id: string, name?: string) => rpc<{ id: string }>('duplicate_agreement_template', { p_template_id: id, p_name: name ?? null }),
  setDefault: (id: string) => rpc('set_default_agreement_template', { p_template_id: id }),
  async setActive(id: string, active: boolean) {
    must(await supabase.from('loan_agreement_templates').update({ is_active: active }).eq('id', id))
  },
}

export const agreementsService = {
  async list(f: { status?: string; search?: string; page?: number; pageSize?: number } = {}) {
    const pageSize = f.pageSize ?? 25
    let q = supabase.from('loan_agreements')
      .select('*, loans!inner(loan_number, borrower_id, status, borrowers(full_name))', { count: 'exact' })
      .order('created_at', { ascending: false })
    if (f.status && f.status !== 'all') q = q.eq('status', f.status)
    if (f.search?.trim()) {
      const s = f.search.trim().replace(/[%,()]/g, ' ')
      q = q.or(`agreement_number.ilike.%${s}%`)
    }
    const page = f.page ?? 0
    const res = await q.range(page * pageSize, page * pageSize + pageSize - 1)
    return { rows: must(res) as AgreementListItem[], count: res.count ?? 0 }
  },
  async forLoan(loanId: string): Promise<Agreement[]> {
    return must(await supabase.from('loan_agreements').select('*').eq('loan_id', loanId).order('version', { ascending: false })) as Agreement[]
  },
  async get(id: string): Promise<Agreement> {
    return must(await supabase.from('loan_agreements').select('*').eq('id', id).single()) as Agreement
  },
  async sessions(agreementId: string): Promise<SigningSession[]> {
    return must(await supabase.from('agreement_signing_sessions').select('*').eq('agreement_id', agreementId)
      .order('created_at', { ascending: false })) as SigningSession[]
  },
  async signatures(agreementId: string): Promise<AgreementSignature[]> {
    return must(await supabase.from('agreement_signatures').select('*').eq('agreement_id', agreementId).order('signed_at')) as AgreementSignature[]
  },
  async events(agreementId: string): Promise<AgreementEvent[]> {
    return must(await supabase.from('agreement_audit_events').select('*').eq('agreement_id', agreementId).order('created_at')) as AgreementEvent[]
  },
  generate: (loanId: string) => invoke<{ id: string; agreement_number: string; status: string; missing_placeholders: string[] }>('agreement-generate', { loan_id: loanId }),
  void: (id: string, reason: string) => rpc('agreement_void', { p_agreement_id: id, p_reason: reason }),
  createLink: (agreementId: string, role: string, signerName?: string, contact?: string, expiryHours?: number) =>
    rpc<{ session_id: string; token: string; expires_at: string; verification_method: string; signer_name: string }>('signing_session_create', {
      p_agreement_id: agreementId, p_signer_role: role, p_signer_name: signerName ?? null, p_contact: contact ?? null, p_expiry_hours: expiryHours ?? null,
    }),
  revokeLink: (sessionId: string) => rpc('signing_session_revoke', { p_session_id: sessionId }),
  signInApp: (p: { agreement_id: string; role: string; signature: string; signer_name?: string }) =>
    invoke<{ commit: Record<string, unknown>; result: Record<string, unknown> }>('agreement-sign', { action: 'sign', consent: true, ...p }),
  rebuild: (agreementId: string) => invoke('agreement-sign', { action: 'rebuild', agreement_id: agreementId }),
  pdfUrl: (path: string, download?: string) => signedUrl('agreements', path, 900, download),
  signatureUrl: (path: string) => signedUrl('agreements', path, 600),
  signingLink: (token: string) => `${window.location.origin}/sign/${token}`,
}

// ---------- public signing page (no login) ----------
export type PublicSigningContext = {
  session: { signer_role: string; signer_name: string; status: string; expires_at: string; verification_method: string; signed_at: string | null; locked_until: string | null }
  agreement: { agreement_number: string; status: string; version: number; required_signers: string[]; signing_order: string }
  lender: { name: string }
  consent_text?: string
  loan?: Record<string, any>
  signatures?: { signer_role: string; signer_name: string; signed_at: string }[]
  pdf_url?: string | null
}

export const publicSigning = {
  load: (token: string) => invoke<PublicSigningContext>('public-signing', { action: 'load', token }),
  verify: (token: string, answer: string) => invoke<{ ok: boolean; nonce?: string; error?: string; attempts_left?: number }>('public-signing', { action: 'verify', token, answer }),
  document: (token: string, nonce: string) => invoke<PublicSigningContext>('public-signing', { action: 'document', token, nonce }),
  sign: (token: string, nonce: string, signature: string, typedName: string) =>
    invoke<{ ok: boolean; duplicate: boolean }>('public-signing', { action: 'sign', token, nonce, signature, consent: true, typed_name: typedName }),
  download: (token: string, nonce: string) => invoke<{ url: string; status: string }>('public-signing', { action: 'download', token, nonce }),
}
