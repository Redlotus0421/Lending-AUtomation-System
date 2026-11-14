import type { LoanStatus } from '@/types/db'

export const PH_ID_TYPES = [
  'PhilSys National ID (PhilID / ePhilID)',
  'Philippine Passport',
  "Driver's License (LTO)",
  'UMID',
  'SSS ID',
  'GSIS eCard',
  'PRC ID',
  'Postal ID',
  "Voter's ID / Certification",
  'PhilHealth ID',
  'TIN ID',
  'Senior Citizen ID',
  'PWD ID',
  'OFW ID (iDOLE)',
  'Barangay ID / Clearance',
  'Company ID',
  'Other government-issued ID',
]

export const PH_REGIONS = [
  'NCR — National Capital Region',
  'CAR — Cordillera Administrative Region',
  'Region I — Ilocos Region',
  'Region II — Cagayan Valley',
  'Region III — Central Luzon',
  'Region IV-A — CALABARZON',
  'MIMAROPA Region',
  'Region V — Bicol Region',
  'Region VI — Western Visayas',
  'NIR — Negros Island Region',
  'Region VII — Central Visayas',
  'Region VIII — Eastern Visayas',
  'Region IX — Zamboanga Peninsula',
  'Region X — Northern Mindanao',
  'Region XI — Davao Region',
  'Region XII — SOCCSKSARGEN',
  'Region XIII — Caraga',
  'BARMM — Bangsamoro Autonomous Region',
]

export const EMPLOYMENT_TYPES = ['Employed (private)', 'Employed (government)', 'Self-employed / Business owner', 'OFW', 'Freelancer', 'Pensioner', 'Unemployed', 'Other']

export const RELATIONSHIPS = ['Spouse', 'Parent', 'Child', 'Sibling', 'Relative', 'Friend', 'Co-worker', 'Employer', 'Other']

export const DOCUMENT_TYPES = [
  { value: 'proof_of_income', label: 'Proof of income (payslip, ITR, COE)' },
  { value: 'proof_of_billing', label: 'Proof of billing / address' },
  { value: 'bank_statement', label: 'Bank statement' },
  { value: 'business_permit', label: 'Business permit / DTI' },
  { value: 'collateral', label: 'Collateral document' },
  { value: 'cedula', label: 'Community tax certificate (cedula)' },
  { value: 'atm_card', label: 'ATM card' },
  { value: 'government_id', label: 'Government ID' },
  { value: 'other', label: 'Other' },
]

export const PAYMENT_METHODS = [
  { value: 'cash', label: 'Cash' },
  { value: 'bank_transfer', label: 'Bank transfer' },
  { value: 'e_wallet', label: 'E-wallet (GCash, Maya…)' },
  { value: 'check', label: 'Check' },
  { value: 'other', label: 'Other' },
] as const

/** Payment methods that only the system posts (shown in history, never offered in forms) */
export const SYSTEM_PAYMENT_METHODS: Record<string, string> = { renewal: 'Renewal offset' }

export const LOAN_STATUS: Record<LoanStatus, { label: string; tone: Tone }> = {
  draft: { label: 'Draft', tone: 'slate' },
  pending_review: { label: 'Pending review', tone: 'amber' },
  approved: { label: 'Approved', tone: 'blue' },
  awaiting_borrower_signature: { label: 'Awaiting borrower signature', tone: 'violet' },
  awaiting_lender_signature: { label: 'Awaiting lender signature', tone: 'violet' },
  fully_signed: { label: 'Fully signed', tone: 'teal' },
  ready_for_release: { label: 'Ready for release', tone: 'teal' },
  active: { label: 'Active', tone: 'green' },
  rejected: { label: 'Rejected', tone: 'red' },
  cancelled: { label: 'Cancelled', tone: 'slate' },
  completed: { label: 'Completed', tone: 'navy' },
  defaulted: { label: 'Defaulted', tone: 'red' },
}

export type Tone = 'slate' | 'amber' | 'blue' | 'violet' | 'teal' | 'green' | 'red' | 'navy'

export const INSTALLMENT_STATUS: Record<string, { label: string; tone: Tone }> = {
  upcoming: { label: 'Upcoming', tone: 'slate' },
  pending: { label: 'Upcoming', tone: 'slate' },
  due_today: { label: 'Due today', tone: 'amber' },
  partially_paid: { label: 'Partially paid', tone: 'blue' },
  paid: { label: 'Paid', tone: 'green' },
  overdue: { label: 'Overdue', tone: 'red' },
  rescheduled: { label: 'Rescheduled', tone: 'violet' },
  waived: { label: 'Waived', tone: 'teal' },
}

export const AGREEMENT_STATUS: Record<string, { label: string; tone: Tone }> = {
  generating: { label: 'Generating', tone: 'slate' },
  failed: { label: 'Failed', tone: 'red' },
  pending_signatures: { label: 'Awaiting signatures', tone: 'amber' },
  partially_signed: { label: 'Partially signed', tone: 'violet' },
  fully_signed: { label: 'Fully signed', tone: 'green' },
  superseded: { label: 'Superseded', tone: 'slate' },
  voided: { label: 'Voided', tone: 'red' },
}

export const ROLE_LABEL: Record<string, string> = {
  owner: 'Owner',
  admin: 'Administrator',
  loan_officer: 'Loan officer',
  collector: 'Collector',
}

export const PERMISSIONS: { key: string; label: string; group: string }[] = [
  { key: 'dashboard.view', label: 'View dashboard', group: 'General' },
  { key: 'reports.view', label: 'View financial reports', group: 'General' },
  { key: 'borrowers.view', label: 'View assigned borrowers', group: 'Borrowers' },
  { key: 'borrowers.view_all', label: 'View all borrowers', group: 'Borrowers' },
  { key: 'borrowers.create', label: 'Register borrowers', group: 'Borrowers' },
  { key: 'borrowers.edit', label: 'Edit borrowers', group: 'Borrowers' },
  { key: 'borrowers.archive', label: 'Archive / blacklist borrowers', group: 'Borrowers' },
  { key: 'borrowers.view_sensitive', label: 'View IDs and sensitive data', group: 'Borrowers' },
  { key: 'loans.view', label: 'View assigned loans', group: 'Loans' },
  { key: 'loans.view_all', label: 'View all loans', group: 'Loans' },
  { key: 'loans.create', label: 'Create loan applications', group: 'Loans' },
  { key: 'loans.approve', label: 'Approve / reject loans', group: 'Loans' },
  { key: 'loans.release', label: 'Release funds (activate)', group: 'Loans' },
  { key: 'loans.cancel', label: 'Cancel loans', group: 'Loans' },
  { key: 'loans.manage', label: 'Reschedule, default, close loans', group: 'Loans' },
  { key: 'agreements.view', label: 'View agreements', group: 'Agreements' },
  { key: 'agreements.generate', label: 'Generate agreements & signing links', group: 'Agreements' },
  { key: 'agreements.sign_lender', label: 'Sign as lender', group: 'Agreements' },
  { key: 'payments.view', label: 'View payments', group: 'Payments' },
  { key: 'payments.record', label: 'Record payments', group: 'Payments' },
  { key: 'payments.reverse', label: 'Reverse payments', group: 'Payments' },
  { key: 'payments.adjust', label: 'Waivers & adjustments', group: 'Payments' },
  { key: 'collections.assign', label: 'Assign collectors', group: 'Payments' },
  { key: 'products.manage', label: 'Manage loan products', group: 'Administration' },
  { key: 'templates.manage', label: 'Manage agreement templates', group: 'Administration' },
  { key: 'users.manage', label: 'Manage users & permissions', group: 'Administration' },
  { key: 'settings.manage', label: 'Manage system settings', group: 'Administration' },
  { key: 'audit.view', label: 'View audit logs', group: 'Administration' },
]

export const PENALTY_TYPES = [
  { value: 'percent_monthly', label: '% per month of unpaid amount (accrues daily)' },
  { value: 'percent_daily', label: '% per day of unpaid amount' },
  { value: 'percent_once', label: 'One-time % of unpaid installment' },
  { value: 'fixed', label: 'One-time fixed fee per late installment' },
]

export const FREQUENCIES = [
  { value: 'monthly', label: 'Monthly', unit: 'months' },
  { value: 'weekly', label: 'Weekly', unit: 'weeks' },
  { value: 'daily', label: 'Daily', unit: 'days' },
] as const

export const RATE_PERIODS = [
  { value: 'monthly', label: 'per month' },
  { value: 'yearly', label: 'per year' },
  { value: 'weekly', label: 'per week' },
  { value: 'daily', label: 'per day' },
] as const
