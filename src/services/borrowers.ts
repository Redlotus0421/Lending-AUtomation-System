import { supabase } from '@/lib/supabase'
import type { Borrower, BorrowerDocument, BorrowerIdentity, Guarantor, LoanNote, LoanView, Payment } from '@/types/db'
import { must, rpc, safeFileName, signedUrl, uploadFile } from './api'

export type BorrowerAgreement = {
  id: string; loan_id: string; agreement_number: string; version: number; kind: string; status: string
  generated_at: string | null; fully_signed_at: string | null; unsigned_pdf_path: string | null; current_pdf_path: string | null
  signed_pdf_path: string | null; signed_pdf_hash: string | null; loans: { loan_number: string; borrower_id: string } | null
}

export type BorrowerInput = Partial<Omit<Borrower, 'id' | 'organization_id' | 'borrower_code' | 'full_name' | 'created_at' | 'updated_at'>>
export type BorrowerListItem = Borrower & {
  loans?: { id: string; status: string; total_outstanding: number | string }[]
}

export const borrowersService = {
  async list(f: { search?: string; status?: string; risk?: string; page?: number; pageSize?: number } = {}) {
    const pageSize = f.pageSize ?? 25
    let q = supabase
      .from('borrowers')
      .select('*, loans(id, status, total_outstanding)', { count: 'exact' })
      .order('created_at', { ascending: false })
    if (f.status && f.status !== 'all') q = q.eq('status', f.status)
    else if (!f.status) q = q.neq('status', 'archived')
    if (f.risk && f.risk !== 'all') q = q.eq('risk_rating', f.risk)
    if (f.search?.trim()) {
      const s = f.search.trim().replace(/[%,()]/g, ' ')
      const digits = s.replace(/\D/g, '')
      const parts = [`full_name.ilike.%${s}%`, `borrower_code.ilike.%${s}%`, `email.ilike.%${s}%`]
      if (digits.length >= 4) parts.push(`mobile_number.ilike.%${digits.replace(/^0/, '')}%`)
      q = q.or(parts.join(','))
    }
    const page = f.page ?? 0
    const res = await q.range(page * pageSize, page * pageSize + pageSize - 1)
    return { rows: must(res) as BorrowerListItem[], count: res.count ?? 0 }
  },

  async search(term: string, limit = 8): Promise<Borrower[]> {
    const s = term.trim().replace(/[%,()]/g, ' ')
    let q = supabase.from('borrowers').select('*').eq('status', 'active').order('full_name').limit(limit)
    if (s) {
      const digits = s.replace(/\D/g, '')
      const parts = [`full_name.ilike.%${s}%`, `borrower_code.ilike.%${s}%`]
      if (digits.length >= 4) parts.push(`mobile_number.ilike.%${digits.replace(/^0/, '')}%`)
      q = q.or(parts.join(','))
    }
    return must(await q) as Borrower[]
  },

  async get(id: string): Promise<Borrower> {
    return must(await supabase.from('borrowers').select('*').eq('id', id).single()) as Borrower
  },

  async identity(id: string): Promise<BorrowerIdentity | null> {
    const res = await supabase.from('borrower_identities').select('*').eq('borrower_id', id).maybeSingle()
    if (res.error) return null // no permission to sensitive data
    return res.data as BorrowerIdentity | null
  },

  async create(input: BorrowerInput, identity?: Partial<BorrowerIdentity> | null, guarantor?: Partial<Guarantor> | null): Promise<Borrower> {
    const borrower = must(await supabase.from('borrowers').insert(input).select().single()) as Borrower
    if (identity?.id_type && identity?.id_number) {
      must(await supabase.from('borrower_identities').insert({ borrower_id: borrower.id, ...identity }).select().single())
    }
    if (guarantor?.full_name) {
      must(await supabase.from('guarantors').insert({ borrower_id: borrower.id, ...guarantor }).select().single())
    }
    return borrower
  },

  async update(id: string, input: BorrowerInput): Promise<Borrower> {
    return must(await supabase.from('borrowers').update(input).eq('id', id).select().single()) as Borrower
  },

  async saveIdentity(borrowerId: string, identity: Partial<BorrowerIdentity>) {
    const existing = must(await supabase.from('borrower_identities').select('borrower_id').eq('borrower_id', borrowerId).maybeSingle())
    if (existing) {
      return must(await supabase.from('borrower_identities').update(identity).eq('borrower_id', borrowerId).select().single())
    }
    return must(await supabase.from('borrower_identities').insert({ borrower_id: borrowerId, ...identity }).select().single())
  },

  setStatus: (id: string, status: Borrower['status']) => borrowersService.update(id, { status }),

  checkDuplicates: (p: { first_name: string; last_name: string; date_of_birth?: string | null; mobile?: string; id_type?: string; id_number?: string; exclude?: string }) =>
    rpc<{ borrower_id: string; borrower_code: string; full_name: string; mobile_number: string; match_reason: string }[]>('check_borrower_duplicates', {
      p_first_name: p.first_name, p_last_name: p.last_name, p_date_of_birth: p.date_of_birth || null, p_mobile: p.mobile ?? '',
      p_id_type: p.id_type || null, p_id_number: p.id_number || null, p_exclude: p.exclude ?? null,
    }),

  // ----- files -----
  async uploadBorrowerFile(orgId: string, borrowerId: string, category: 'profile' | 'identity' | 'documents', file: File) {
    const path = `${orgId}/${borrowerId}/${category}/${safeFileName(file.name)}`
    await uploadFile('borrower-files', path, file)
    return path
  },
  fileUrl: (path: string, seconds = 600) => signedUrl('borrower-files', path, seconds),

  async documents(borrowerId: string): Promise<BorrowerDocument[]> {
    return must(await supabase.from('borrower_documents').select('*').eq('borrower_id', borrowerId).order('created_at', { ascending: false })) as BorrowerDocument[]
  },
  /** Sensitive documents (e.g. ATM cards) go to the 'identity' folder, readable only with borrowers.view_sensitive. */
  async addDocument(orgId: string, borrowerId: string, file: File, documentType: string, title?: string, category: 'documents' | 'identity' = 'documents') {
    const path = await borrowersService.uploadBorrowerFile(orgId, borrowerId, category, file)
    return must(await supabase.from('borrower_documents').insert({
      borrower_id: borrowerId, document_type: documentType, title: title || file.name, file_path: path,
      file_name: file.name, mime_type: file.type, file_size: file.size,
    }).select().single()) as BorrowerDocument
  },
  async deleteDocument(doc: BorrowerDocument) {
    must(await supabase.from('borrower_documents').delete().eq('id', doc.id))
    await supabase.storage.from('borrower-files').remove([doc.file_path])
  },

  // ----- guarantors -----
  async guarantors(borrowerId: string): Promise<Guarantor[]> {
    return must(await supabase.from('guarantors').select('*').eq('borrower_id', borrowerId).order('created_at')) as Guarantor[]
  },
  async saveGuarantor(borrowerId: string, g: Partial<Guarantor>) {
    if (g.id) return must(await supabase.from('guarantors').update(g).eq('id', g.id).select().single()) as Guarantor
    return must(await supabase.from('guarantors').insert({ ...g, borrower_id: borrowerId }).select().single()) as Guarantor
  },
  async deleteGuarantor(id: string) {
    must(await supabase.from('guarantors').delete().eq('id', id))
  },

  // ----- history -----
  async loans(borrowerId: string): Promise<LoanView[]> {
    return must(await supabase.from('v_loans').select('*').eq('borrower_id', borrowerId).order('created_at', { ascending: false })) as LoanView[]
  },
  async agreements(borrowerId: string): Promise<BorrowerAgreement[]> {
    return must(await supabase.from('loan_agreements')
      .select('id, loan_id, agreement_number, version, kind, status, generated_at, fully_signed_at, unsigned_pdf_path, current_pdf_path, signed_pdf_path, signed_pdf_hash, loans!inner(loan_number, borrower_id)')
      .eq('loans.borrower_id', borrowerId)
      .order('created_at', { ascending: false })) as unknown as BorrowerAgreement[]
  },
  async notes(borrowerId: string): Promise<LoanNote[]> {
    return must(await supabase.from('loan_notes').select('*, author:user_profiles(full_name)').eq('borrower_id', borrowerId)
      .order('created_at', { ascending: false }).limit(200)) as LoanNote[]
  },
  async payments(borrowerId: string): Promise<(Payment & { loans: { loan_number: string } | null })[]> {
    return must(await supabase.from('loan_payments').select('*, loans(loan_number)').eq('borrower_id', borrowerId)
      .order('payment_date', { ascending: false }).order('created_at', { ascending: false })) as (Payment & { loans: { loan_number: string } | null })[]
  },
}
