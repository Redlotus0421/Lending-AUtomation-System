// Hand-written row types for the LendFlow schema (supabase/migrations).
export type AppRole = 'owner' | 'admin' | 'loan_officer' | 'collector'
export type LoanStatus =
  | 'draft' | 'pending_review' | 'approved' | 'awaiting_borrower_signature' | 'awaiting_lender_signature'
  | 'fully_signed' | 'ready_for_release' | 'active' | 'rejected' | 'cancelled' | 'completed' | 'defaulted'
export type InterestMethod = 'flat' | 'diminishing'
export type RatePeriod = 'daily' | 'weekly' | 'monthly' | 'yearly'
export type Frequency = 'daily' | 'weekly' | 'monthly'
export type Money = number | string

export interface Organization {
  id: string
  name: string
  legal_name: string | null
  registration_no: string | null
  tin: string | null
  address: string | null
  city: string | null
  province: string | null
  zip_code: string | null
  contact_email: string | null
  contact_phone: string | null
  website: string | null
  logo_path: string | null
  currency: string
  timezone: string
  date_format: string
  created_at: string
}

export interface Access {
  user_id: string
  organization_id: string | null
  role: AppRole | null
  full_name: string | null
  permissions: string[]
  /** Platform (super) administrator of the LendFlow service */
  is_platform_admin?: boolean
  /** Set when the user's lender account has been suspended by a platform admin */
  suspended_organization?: { name: string; reason: string | null } | null
}

export interface UserProfile {
  id: string
  organization_id: string | null
  full_name: string
  email: string | null
  phone: string | null
  is_active: boolean
  last_seen_at: string | null
  created_at: string
}

export interface Borrower {
  id: string
  organization_id: string
  borrower_code: string
  first_name: string
  middle_name: string | null
  last_name: string
  suffix: string | null
  full_name: string
  date_of_birth: string | null
  gender: string | null
  civil_status: string | null
  mobile_number: string
  email: string | null
  address_line: string | null
  barangay: string | null
  city_municipality: string | null
  province: string | null
  region: string | null
  zip_code: string | null
  occupation: string | null
  employer_name: string | null
  employment_type: string | null
  monthly_income: Money | null
  profile_photo_path: string | null
  emergency_contact_name: string | null
  emergency_contact_relationship: string | null
  emergency_contact_number: string | null
  risk_rating: 'unrated' | 'low' | 'medium' | 'high'
  assessment_notes: string | null
  internal_notes: string | null
  status: 'active' | 'archived' | 'blacklisted'
  assigned_officer_id: string | null
  created_by: string | null
  archived_at: string | null
  created_at: string
  updated_at: string
}

export interface BorrowerIdentity {
  borrower_id: string
  id_type: string
  id_number: string
  id_expiry: string | null
  id_front_path: string | null
  id_back_path: string | null
  verified_at: string | null
}

export interface BorrowerDocument {
  id: string
  borrower_id: string
  document_type: string
  title: string | null
  file_path: string
  file_name: string | null
  mime_type: string | null
  file_size: number | null
  created_at: string
}

export interface Guarantor {
  id: string
  borrower_id: string
  full_name: string
  relationship: string | null
  mobile_number: string | null
  email: string | null
  address: string | null
  occupation: string | null
  monthly_income: Money | null
  id_type: string | null
  id_number: string | null
  notes: string | null
}

export interface LoanProduct {
  id: string
  name: string
  code: string
  description: string | null
  interest_method: InterestMethod
  interest_rate: Money
  rate_period: RatePeriod
  repayment_frequency: Frequency
  min_amount: Money | null
  max_amount: Money | null
  min_term: number | null
  max_term: number | null
  default_term: number | null
  processing_fee_type: 'none' | 'fixed' | 'percent'
  processing_fee_value: Money
  charges_treatment: 'added' | 'deducted'
  day_count: '30/360' | 'actual/365' | 'actual/actual'
  penalty_enabled: boolean
  penalty_type: string | null
  penalty_rate: Money
  penalty_grace_days: number
  agreement_template_id: string | null
  is_active: boolean
  created_at: string
}

export interface Loan {
  id: string
  organization_id: string
  loan_number: string
  borrower_id: string
  product_id: string | null
  status: LoanStatus
  principal: Money
  interest_method: InterestMethod
  interest_rate: Money
  rate_period: RatePeriod
  day_count: string
  repayment_frequency: Frequency
  term_count: number
  release_date: string
  first_due_date: string
  maturity_date: string
  processing_fee_type: string
  processing_fee_value: Money
  other_charges: Money
  other_charges_description: string | null
  charges_treatment: 'added' | 'deducted'
  rounding: string
  penalty_enabled: boolean
  penalty_type: string | null
  penalty_rate: Money
  penalty_grace_days: number
  allocation_order: string[]
  installment_count: number
  periodic_rate: Money
  processing_fee: Money
  total_charges: Money
  financed_charges: Money
  net_proceeds: Money
  total_interest: Money
  total_repayment: Money
  installment_amount: Money
  effective_annual_rate: Money | null
  eir_monthly: Money | null
  calc_summary: LoanSummary
  outstanding_principal: Money
  outstanding_interest: Money
  outstanding_charges: Money
  outstanding_penalties: Money
  total_outstanding: Money
  principal_paid: Money
  interest_paid: Money
  charges_paid: Money
  penalties_paid: Money
  total_paid: Money
  penalties_assessed: Money
  penalties_waived: Money
  interest_waived: Money
  charges_waived: Money
  last_payment_date: string | null
  purpose: string | null
  notes: string | null
  agreement_template_id: string | null
  signing_order: 'borrower_first' | 'lender_first' | 'any'
  witness_count: number
  loan_officer_id: string | null
  collector_id: string | null
  created_by: string | null
  submitted_at: string | null
  approved_at: string | null
  approved_by: string | null
  approval_notes: string | null
  rejected_at: string | null
  rejection_reason: string | null
  cancelled_at: string | null
  cancellation_reason: string | null
  released_at: string | null
  actual_release_date: string | null
  release_method: string | null
  release_reference: string | null
  activated_at: string | null
  completed_at: string | null
  defaulted_at: string | null
  default_reason: string | null
  terms_snapshot_id: string | null
  created_at: string
  updated_at: string
}

/** Row of the v_loans view */
export interface LoanView extends Loan {
  borrower_name: string
  borrower_code: string
  borrower_mobile: string
  borrower_photo_path: string | null
  product_name: string | null
  loan_officer_name: string | null
  collector_name: string | null
  next_due_date: string | null
  next_due_amount: Money | null
  overdue_amount: Money
  overdue_count: number
  oldest_overdue_date: string | null
  days_overdue: number
  paid_installments: number
  total_installments: number
  agreement_status: string | null
}

export interface LoanSummary {
  principal: Money
  interest_method: InterestMethod
  interest_rate: Money
  rate_period: RatePeriod
  rate_basis: string
  day_count: string
  rounding: string
  repayment_frequency: Frequency
  term_count: number
  installment_count: number
  duration_label: string
  release_date: string
  first_due_date: string
  last_due_date: string
  annual_nominal_rate: Money
  periodic_rate: Money
  periodic_rate_pct: Money
  processing_fee_type: string
  processing_fee_value: Money
  processing_fee: Money
  other_charges: Money
  total_charges: Money
  charges_treatment: 'added' | 'deducted'
  financed_charges: Money
  deducted_charges: Money
  net_proceeds: Money
  total_interest: Money
  total_repayment: Money
  finance_charge: Money
  installment_amount: Money
  last_installment_amount: Money
  eir_periodic_pct: Money | null
  eir_monthly_pct: Money | null
  eir_annual_nominal_pct: Money | null
  effective_annual_rate_pct: Money | null
}

export interface ScheduleRow {
  installment_no: number
  due_date: string
  opening_balance: Money
  principal: Money
  interest: Money
  charges: Money
  total: Money
  closing_balance: Money
}

export interface LoanCalculation {
  summary: LoanSummary
  schedule: ScheduleRow[]
  disclosures: string[]
  warnings: string[]
  params?: Record<string, unknown>
}

export interface Installment {
  id: string
  organization_id: string
  loan_id: string
  installment_no: number
  due_date: string
  opening_balance: Money
  principal_due: Money
  interest_due: Money
  charges_due: Money
  closing_balance: Money
  principal_paid: Money
  interest_paid: Money
  charges_paid: Money
  penalty_due: Money
  penalty_paid: Money
  penalty_waived: Money
  interest_waived: Money
  charges_waived: Money
  status: 'pending' | 'partially_paid' | 'paid' | 'waived' | 'rescheduled'
  paid_at: string | null
  last_payment_date: string | null
}

/** Row of the v_installments view */
export interface InstallmentView extends Installment {
  scheduled_amount: Money
  amount_paid: Money
  base_remaining: Money
  penalty_outstanding: Money
  amount_remaining: Money
  display_status: 'upcoming' | 'due_today' | 'partially_paid' | 'paid' | 'overdue' | 'rescheduled' | 'waived'
  is_partial: boolean
  days_overdue: number
  loan_number: string
  loan_status: LoanStatus
  borrower_id: string
  collector_id: string | null
  loan_officer_id: string | null
  interest_method: InterestMethod
  borrower_name: string
  borrower_code: string
  mobile_number: string
  collector_name: string | null
}

export interface Payment {
  id: string
  organization_id: string
  loan_id: string
  borrower_id: string
  payment_number: string
  amount: Money
  payment_date: string
  method: string
  method_detail: string | null
  reference_no: string | null
  proof_path: string | null
  notes: string | null
  payment_type: 'regular' | 'settlement'
  status: 'posted' | 'reversed'
  principal_applied: Money
  interest_applied: Money
  charges_applied: Money
  penalty_applied: Money
  interest_rebated: Money
  balance_after: Money | null
  received_by: string | null
  recorded_by: string | null
  reversed_at: string | null
  reversal_reason: string | null
  created_at: string
}

export interface PaymentResult {
  payment_id: string
  payment_number: string
  amount: Money
  status: string
  principal_applied: Money
  interest_applied: Money
  charges_applied: Money
  penalty_applied: Money
  interest_rebated: Money
  receipt_id: string
  receipt_number: string
  loan_status: LoanStatus
  total_outstanding: Money
  outstanding_principal: Money
  outstanding_interest: Money
  outstanding_charges: Money
  outstanding_penalties: Money
  duplicate: boolean
}

export interface LedgerEntry {
  id: string
  entry_no: number
  loan_id: string
  payment_id: string | null
  entry_type: string
  entry_date: string
  amount: Money
  cash_delta: Money
  principal_delta: Money
  interest_delta: Money
  charges_delta: Money
  penalty_delta: Money
  balance_principal: Money
  balance_interest: Money
  balance_charges: Money
  balance_penalty: Money
  balance_total: Money
  description: string | null
  created_at: string
}

export interface Receipt {
  id: string
  payment_id: string
  receipt_number: string
  issued_at: string
  data: Record<string, any>
  pdf_path: string | null
  is_void: boolean
}

export interface AgreementTemplate {
  id: string
  name: string
  description: string | null
  is_default: boolean
  is_active: boolean
  current_version_id: string | null
  created_at: string
  updated_at: string
}

export interface AgreementTemplateVersion {
  id: string
  template_id: string
  version_no: number
  title: string
  body: string
  settings: Record<string, any>
  change_note: string | null
  created_at: string
  created_by: string | null
}

export interface Agreement {
  id: string
  organization_id: string
  loan_id: string
  agreement_number: string
  version: number
  kind: 'original' | 'amendment'
  status: 'generating' | 'failed' | 'pending_signatures' | 'partially_signed' | 'fully_signed' | 'superseded' | 'voided'
  template_id: string | null
  template_version_id: string | null
  terms_snapshot_id: string
  required_signers: string[]
  signing_order: string
  signature_fields: Record<string, unknown>
  unsigned_pdf_path: string | null
  unsigned_pdf_hash: string | null
  current_pdf_path: string | null
  current_pdf_hash: string | null
  current_signature_count: number
  signed_pdf_path: string | null
  signed_pdf_hash: string | null
  page_count: number | null
  generated_by: string | null
  generated_at: string | null
  fully_signed_at: string | null
  voided_at: string | null
  void_reason: string | null
  error_message: string | null
  created_at: string
}

export interface SigningSession {
  id: string
  agreement_id: string
  signer_role: string
  signer_name: string
  signer_contact: string | null
  channel: 'link' | 'in_app'
  verification_method: string
  status: 'pending' | 'opened' | 'verified' | 'signed' | 'expired' | 'revoked'
  expires_at: string
  opened_at: string | null
  verified_at: string | null
  signed_at: string | null
  failed_attempts: number
  created_at: string
}

export interface AgreementSignature {
  id: string
  agreement_id: string
  signer_role: string
  signer_name: string
  signer_user_id: string | null
  signature_image_path: string
  signature_image_hash: string
  consent_text: string
  verification_method: string | null
  ip_address: string | null
  user_agent: string | null
  signed_at: string
}

export interface AgreementEvent {
  id: number
  agreement_id: string
  session_id: string | null
  event_type: string
  actor_type: string
  actor_name: string | null
  ip_address: string | null
  user_agent: string | null
  metadata: Record<string, any>
  created_at: string
}

export interface LoanNote {
  id: string
  loan_id: string | null
  borrower_id: string | null
  note_type: 'note' | 'call' | 'visit' | 'promise_to_pay' | 'system'
  body: string
  promise_date: string | null
  promise_amount: Money | null
  created_by: string | null
  created_at: string
  author?: { full_name: string } | null
}

export interface AuditLog {
  id: number
  actor_id: string | null
  actor_name: string | null
  action: string
  table_name: string
  record_id: string | null
  old_data: Record<string, any> | null
  new_data: Record<string, any> | null
  changed_fields: string[] | null
  created_at: string
}

export interface Notification {
  id: string
  type: string
  title: string
  body: string | null
  link: string | null
  read_at: string | null
  created_at: string
}

export interface SettlementQuote {
  loan_id: string
  as_of: string
  rule: string
  principal: Money
  interest: Money
  charges: Money
  penalties: Money
  total: Money
  interest_rebate: Money
  contract_remaining: Money
  lines: { installment_id: string; installment_no: number; due_date: string; principal: Money; interest: Money; charges: Money; penalty: Money; rebate: Money }[]
}

export interface Settings {
  interest: {
    flat_enabled: boolean; diminishing_enabled: boolean; default_method: InterestMethod; default_rate: number
    default_rate_period: RatePeriod; rate_presets: number[]; rate_periods: RatePeriod[]; day_count: string; rounding: string
  }
  repayment: {
    frequencies: Frequency[]; default_frequency: Frequency; first_due_rule: string; grace_days: number
    early_settlement_rule: 'prorated' | 'full_period' | 'due_only'; allocation_order: string[]
    charges_treatment: 'added' | 'deducted'; require_separate_approver: boolean
  }
  penalty: { enabled: boolean; type: string; rate: number; grace_days: number }
  agreement: {
    default_template_id: string | null; signing_order: 'borrower_first' | 'lender_first' | 'any'
    require_lender_signature: boolean; witness_count: number; identity_verification: 'none' | 'dob' | 'id_last4'
    link_expiry_hours: number
  }
  security: { session_timeout_minutes: number; password_min_length: number; audit_logging: boolean }
  receipt: { footer_note: string }
}

export interface ReportColumn {
  key: string
  label: string
  type?: 'money' | 'date' | 'number' | 'percent' | 'status'
  total?: boolean
}
export interface ReportResult {
  report: string
  from: string
  to: string
  generated_at: string
  columns: ReportColumn[]
  rows: Record<string, any>[]
  totals: Record<string, number>
}

// ---------- platform (super admin) console ----------
export type AccountStatus = 'active' | 'suspended'
export interface PlatformOrgStats {
  users: number
  borrowers: number
  loans: number
  active_loans: number
  outstanding: Money
  collected: Money
  last_activity_at: string | null
}
export interface PlatformOwner { user_id: string; full_name: string; email: string | null; phone: string | null }
export interface PlatformOrg {
  id: string
  name: string
  legal_name: string | null
  city: string | null
  province: string | null
  contact_email: string | null
  contact_phone: string | null
  status: AccountStatus
  status_reason: string | null
  status_changed_at: string | null
  created_at: string
  owner: PlatformOwner | null
  stats: PlatformOrgStats
}
export interface PlatformMember {
  id: string
  full_name: string
  email: string | null
  phone: string | null
  is_active: boolean
  role: AppRole | null
  created_at: string
  last_sign_in_at: string | null
}
export interface PlatformOrgDetail extends PlatformOrg {
  registration_no: string | null
  tin: string | null
  address: string | null
  zip_code: string | null
  members: PlatformMember[]
}
export interface PlatformOverview {
  organizations: number
  active_organizations: number
  suspended_organizations: number
  new_organizations_30d: number
  users: number
  borrowers: number
  active_loans: number
  outstanding: Money
  collected_30d: Money
  signups_by_month: { month: string; count: number }[]
}
