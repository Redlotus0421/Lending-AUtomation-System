-- =============================================================================
-- LendFlow — Core schema
-- Organizations, users/roles/permissions, borrowers, products, loans,
-- installments, agreements, payments, ledger, receipts, audit.
-- All money columns use numeric(14,2); rates use numeric with high precision.
-- =============================================================================

create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------------------
-- Enumerations
-- ---------------------------------------------------------------------------
create type public.app_role as enum ('owner', 'admin', 'loan_officer', 'collector');

create type public.loan_status as enum (
  'draft', 'pending_review', 'approved',
  'awaiting_borrower_signature', 'awaiting_lender_signature',
  'fully_signed', 'ready_for_release', 'active',
  'rejected', 'cancelled', 'completed', 'defaulted'
);

create type public.interest_method as enum ('flat', 'diminishing');
create type public.rate_period as enum ('daily', 'weekly', 'monthly', 'yearly');
create type public.repayment_frequency as enum ('daily', 'weekly', 'monthly');

-- ---------------------------------------------------------------------------
-- Generic helpers
-- ---------------------------------------------------------------------------
create or replace function public.lf_touch_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end $$;

-- ---------------------------------------------------------------------------
-- Organizations & users
-- ---------------------------------------------------------------------------
create table public.organizations (
  id uuid primary key default gen_random_uuid(),
  name text not null check (length(trim(name)) between 2 and 200),
  legal_name text,
  registration_no text,
  tin text,
  address text,
  city text,
  province text,
  zip_code text,
  contact_email text,
  contact_phone text,
  website text,
  logo_path text,
  currency text not null default 'PHP',
  timezone text not null default 'Asia/Manila',
  date_format text not null default 'MMM d, yyyy',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.user_profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  organization_id uuid references public.organizations (id) on delete set null,
  full_name text not null default '',
  email text,
  phone text,
  avatar_path text,
  is_active boolean not null default true,
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index user_profiles_org_idx on public.user_profiles (organization_id);

create table public.user_roles (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  organization_id uuid not null references public.organizations (id) on delete cascade,
  role public.app_role not null,
  created_at timestamptz not null default now(),
  unique (user_id, organization_id)
);
create index user_roles_org_idx on public.user_roles (organization_id, role);

create table public.role_permissions (
  organization_id uuid not null references public.organizations (id) on delete cascade,
  role public.app_role not null,
  permission text not null,
  primary key (organization_id, role, permission)
);

create table public.system_settings (
  organization_id uuid primary key references public.organizations (id) on delete cascade,
  settings jsonb not null default '{}'::jsonb,
  updated_by uuid references auth.users (id),
  updated_at timestamptz not null default now()
);

-- Per-organization reference number counters (LN-2026-000001 etc.)
create table public.org_counters (
  organization_id uuid not null references public.organizations (id) on delete cascade,
  counter_key text not null,
  period text not null default '',
  value bigint not null default 0,
  primary key (organization_id, counter_key, period)
);

-- Simple fixed-window rate limiter (used by public signing endpoints)
create table public.rate_limits (
  key text not null,
  window_start timestamptz not null,
  hits integer not null default 0,
  primary key (key, window_start)
);

-- ---------------------------------------------------------------------------
-- Auth/permission helper functions (SECURITY DEFINER, stable)
-- ---------------------------------------------------------------------------
create or replace function public.current_org_id()
returns uuid language sql stable security definer set search_path = public as $$
  select organization_id from public.user_profiles
  where id = auth.uid() and is_active
$$;

create or replace function public.current_app_role()
returns public.app_role language sql stable security definer set search_path = public as $$
  select ur.role from public.user_roles ur
  join public.user_profiles up on up.id = ur.user_id and up.organization_id = ur.organization_id
  where ur.user_id = auth.uid() and up.is_active
  limit 1
$$;

create or replace function public.has_permission(p_permission text)
returns boolean language sql stable security definer set search_path = public as $$
  select coalesce(
    (select true from public.user_roles ur
       join public.user_profiles up on up.id = ur.user_id and up.organization_id = ur.organization_id
      where ur.user_id = auth.uid() and up.is_active and ur.role = 'owner'
      limit 1),
    (select true from public.user_roles ur
       join public.user_profiles up on up.id = ur.user_id and up.organization_id = ur.organization_id
       join public.role_permissions rp on rp.organization_id = ur.organization_id and rp.role = ur.role
      where ur.user_id = auth.uid() and up.is_active and rp.permission = p_permission
      limit 1),
    false)
$$;

create or replace function public.lf_require(p_permission text)
returns uuid language plpgsql stable security definer set search_path = public as $$
declare v_org uuid;
begin
  v_org := public.current_org_id();
  if auth.uid() is null or v_org is null then
    raise exception 'Not authenticated or not a member of an organization' using errcode = '42501';
  end if;
  if p_permission is not null and not public.has_permission(p_permission) then
    raise exception 'You do not have permission to perform this action (%).', p_permission using errcode = '42501';
  end if;
  return v_org;
end $$;

-- Next reference number, e.g. lf_next_ref(org, 'loan', 'LN') -> LN-2026-000001
create or replace function public.lf_next_ref(p_org uuid, p_key text, p_prefix text, p_yearly boolean default true)
returns text language plpgsql security definer set search_path = public as $$
declare
  v_period text := case when p_yearly then to_char(now() at time zone 'Asia/Manila', 'YYYY') else '' end;
  v_value bigint;
begin
  insert into public.org_counters (organization_id, counter_key, period, value)
  values (p_org, p_key, v_period, 1)
  on conflict (organization_id, counter_key, period)
  do update set value = public.org_counters.value + 1
  returning value into v_value;
  return p_prefix || '-' || case when p_yearly then v_period || '-' else '' end || lpad(v_value::text, 6, '0');
end $$;

-- Today's business date for an organization (Asia/Manila by default)
create or replace function public.lf_today(p_org uuid default null)
returns date language sql stable security definer set search_path = public as $$
  select (now() at time zone coalesce(
    (select timezone from public.organizations where id = coalesce(p_org, public.current_org_id())),
    'Asia/Manila'))::date
$$;

-- ---------------------------------------------------------------------------
-- Borrowers
-- ---------------------------------------------------------------------------
create table public.borrowers (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  borrower_code text not null,
  first_name text not null check (length(trim(first_name)) > 0),
  middle_name text,
  last_name text not null check (length(trim(last_name)) > 0),
  suffix text,
  full_name text generated always as (
    trim(first_name || coalesce(' ' || nullif(trim(middle_name), ''), '') || ' ' || last_name || coalesce(' ' || nullif(trim(suffix), ''), ''))
  ) stored,
  date_of_birth date check (date_of_birth is null or date_of_birth > date '1900-01-01'),
  gender text check (gender is null or gender in ('male', 'female', 'other', 'prefer_not_to_say')),
  civil_status text check (civil_status is null or civil_status in ('single', 'married', 'widowed', 'separated', 'annulled')),
  mobile_number text not null check (mobile_number ~ '^\+639[0-9]{9}$'),
  email text check (email is null or email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
  address_line text,
  barangay text,
  city_municipality text,
  province text,
  region text,
  zip_code text,
  occupation text,
  employer_name text,
  employment_type text,
  monthly_income numeric(14, 2) check (monthly_income is null or monthly_income >= 0),
  profile_photo_path text,
  emergency_contact_name text,
  emergency_contact_relationship text,
  emergency_contact_number text,
  risk_rating text not null default 'unrated' check (risk_rating in ('unrated', 'low', 'medium', 'high')),
  assessment_notes text,
  internal_notes text,
  status text not null default 'active' check (status in ('active', 'archived', 'blacklisted')),
  assigned_officer_id uuid references public.user_profiles (id) on delete set null,
  created_by uuid references public.user_profiles (id) on delete set null,
  archived_at timestamptz,
  is_demo boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, borrower_code)
);
create index borrowers_org_status_idx on public.borrowers (organization_id, status);
create index borrowers_org_name_idx on public.borrowers (organization_id, lower(last_name), lower(first_name));
create index borrowers_org_mobile_idx on public.borrowers (organization_id, mobile_number);
create unique index borrowers_dup_name_dob_uniq on public.borrowers
  (organization_id, lower(trim(first_name)), lower(trim(last_name)), date_of_birth)
  where date_of_birth is not null and status <> 'archived';

-- Identity documents are separated so they can be restricted to authorized staff
create table public.borrower_identities (
  borrower_id uuid primary key references public.borrowers (id) on delete cascade,
  organization_id uuid not null references public.organizations (id) on delete restrict,
  id_type text not null,
  id_number text not null,
  id_expiry date,
  id_front_path text,
  id_back_path text,
  verified_at timestamptz,
  verified_by uuid references public.user_profiles (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index borrower_identities_doc_uniq on public.borrower_identities
  (organization_id, lower(id_type), upper(regexp_replace(id_number, '[^A-Za-z0-9]', '', 'g')));

create table public.borrower_documents (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  borrower_id uuid not null references public.borrowers (id) on delete cascade,
  document_type text not null,
  title text,
  file_path text not null,
  file_name text,
  mime_type text,
  file_size bigint,
  uploaded_by uuid references public.user_profiles (id) on delete set null,
  created_at timestamptz not null default now()
);
create index borrower_documents_borrower_idx on public.borrower_documents (borrower_id);

create table public.guarantors (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  borrower_id uuid not null references public.borrowers (id) on delete cascade,
  full_name text not null,
  relationship text,
  mobile_number text,
  email text,
  address text,
  occupation text,
  monthly_income numeric(14, 2),
  id_type text,
  id_number text,
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index guarantors_borrower_idx on public.guarantors (borrower_id);

-- ---------------------------------------------------------------------------
-- Agreement templates (declared before products so products can reference them)
-- ---------------------------------------------------------------------------
create table public.loan_agreement_templates (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete cascade,
  name text not null,
  description text,
  is_default boolean not null default false,
  is_active boolean not null default true,
  current_version_id uuid,
  created_by uuid references public.user_profiles (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index loan_agreement_templates_default_uniq on public.loan_agreement_templates (organization_id) where is_default;

create table public.loan_agreement_template_versions (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete cascade,
  template_id uuid not null references public.loan_agreement_templates (id) on delete cascade,
  version_no integer not null,
  title text not null,
  body text not null,
  settings jsonb not null default '{}'::jsonb,
  change_note text,
  created_by uuid references public.user_profiles (id) on delete set null,
  created_at timestamptz not null default now(),
  unique (template_id, version_no)
);
alter table public.loan_agreement_templates
  add constraint loan_agreement_templates_current_version_fk
  foreign key (current_version_id) references public.loan_agreement_template_versions (id) on delete set null;

-- ---------------------------------------------------------------------------
-- Loan products
-- ---------------------------------------------------------------------------
create table public.loan_products (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  name text not null,
  code text not null,
  description text,
  interest_method public.interest_method not null default 'flat',
  interest_rate numeric(10, 6) not null default 0 check (interest_rate >= 0 and interest_rate <= 100),
  rate_period public.rate_period not null default 'monthly',
  repayment_frequency public.repayment_frequency not null default 'monthly',
  min_amount numeric(14, 2) check (min_amount is null or min_amount > 0),
  max_amount numeric(14, 2) check (max_amount is null or max_amount > 0),
  min_term integer check (min_term is null or min_term > 0),
  max_term integer check (max_term is null or max_term > 0),
  default_term integer check (default_term is null or default_term > 0),
  processing_fee_type text not null default 'none' check (processing_fee_type in ('none', 'fixed', 'percent')),
  processing_fee_value numeric(14, 4) not null default 0 check (processing_fee_value >= 0),
  charges_treatment text not null default 'deducted' check (charges_treatment in ('added', 'deducted')),
  day_count text not null default '30/360' check (day_count in ('30/360', 'actual/365', 'actual/actual')),
  penalty_enabled boolean not null default false,
  penalty_type text check (penalty_type is null or penalty_type in ('fixed', 'percent_once', 'percent_daily', 'percent_monthly')),
  penalty_rate numeric(10, 4) not null default 0 check (penalty_rate >= 0),
  penalty_grace_days integer not null default 0 check (penalty_grace_days >= 0),
  agreement_template_id uuid references public.loan_agreement_templates (id) on delete set null,
  is_active boolean not null default true,
  created_by uuid references public.user_profiles (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, code),
  check (max_amount is null or min_amount is null or max_amount >= min_amount),
  check (max_term is null or min_term is null or max_term >= min_term)
);

-- ---------------------------------------------------------------------------
-- Loans (one record across the entire lifecycle)
-- ---------------------------------------------------------------------------
create table public.loans (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  loan_number text not null,
  borrower_id uuid not null references public.borrowers (id) on delete restrict,
  product_id uuid references public.loan_products (id) on delete set null,
  status public.loan_status not null default 'draft',

  -- contractual terms (inputs)
  principal numeric(14, 2) not null check (principal > 0),
  interest_method public.interest_method not null,
  interest_rate numeric(10, 6) not null check (interest_rate >= 0),
  rate_period public.rate_period not null,
  rate_basis text not null default 'nominal' check (rate_basis in ('nominal')),
  day_count text not null default '30/360' check (day_count in ('30/360', 'actual/365', 'actual/actual')),
  repayment_frequency public.repayment_frequency not null,
  term_count integer not null check (term_count between 1 and 3660),
  release_date date not null,
  first_due_date date not null,
  maturity_date date not null,
  processing_fee_type text not null default 'none' check (processing_fee_type in ('none', 'fixed', 'percent')),
  processing_fee_value numeric(14, 4) not null default 0,
  other_charges numeric(14, 2) not null default 0 check (other_charges >= 0),
  other_charges_description text,
  charges_treatment text not null default 'deducted' check (charges_treatment in ('added', 'deducted')),
  rounding text not null default 'centavo' check (rounding in ('centavo', 'peso')),
  penalty_enabled boolean not null default false,
  penalty_type text check (penalty_type is null or penalty_type in ('fixed', 'percent_once', 'percent_daily', 'percent_monthly')),
  penalty_rate numeric(10, 4) not null default 0,
  penalty_grace_days integer not null default 0,
  allocation_order text[] not null default array['penalty', 'charges', 'interest', 'principal'],

  -- server-computed figures (never accepted from clients)
  installment_count integer not null,
  periodic_rate numeric(20, 12) not null,
  processing_fee numeric(14, 2) not null default 0,
  total_charges numeric(14, 2) not null default 0,
  financed_charges numeric(14, 2) not null default 0,
  net_proceeds numeric(14, 2) not null,
  total_interest numeric(14, 2) not null,
  total_repayment numeric(14, 2) not null,
  installment_amount numeric(14, 2) not null,
  effective_annual_rate numeric(12, 6),
  eir_monthly numeric(12, 6),
  calc_summary jsonb not null default '{}'::jsonb,

  -- running balances (maintained only by ledger-posting functions)
  outstanding_principal numeric(14, 2) not null default 0,
  outstanding_interest numeric(14, 2) not null default 0,
  outstanding_charges numeric(14, 2) not null default 0,
  outstanding_penalties numeric(14, 2) not null default 0,
  total_outstanding numeric(14, 2) generated always as
    (outstanding_principal + outstanding_interest + outstanding_charges + outstanding_penalties) stored,
  principal_paid numeric(14, 2) not null default 0,
  interest_paid numeric(14, 2) not null default 0,
  charges_paid numeric(14, 2) not null default 0,
  penalties_paid numeric(14, 2) not null default 0,
  total_paid numeric(14, 2) not null default 0,
  penalties_assessed numeric(14, 2) not null default 0,
  penalties_waived numeric(14, 2) not null default 0,
  interest_waived numeric(14, 2) not null default 0,
  charges_waived numeric(14, 2) not null default 0,
  last_payment_date date,

  -- workflow
  purpose text,
  notes text,
  agreement_template_id uuid references public.loan_agreement_templates (id) on delete set null,
  signing_order text not null default 'borrower_first' check (signing_order in ('borrower_first', 'lender_first', 'any')),
  witness_count integer not null default 0 check (witness_count between 0 and 2),
  loan_officer_id uuid references public.user_profiles (id) on delete set null,
  collector_id uuid references public.user_profiles (id) on delete set null,
  created_by uuid references public.user_profiles (id) on delete set null,
  submitted_at timestamptz,
  submitted_by uuid references public.user_profiles (id) on delete set null,
  approved_at timestamptz,
  approved_by uuid references public.user_profiles (id) on delete set null,
  approval_notes text,
  rejected_at timestamptz,
  rejected_by uuid references public.user_profiles (id) on delete set null,
  rejection_reason text,
  cancelled_at timestamptz,
  cancelled_by uuid references public.user_profiles (id) on delete set null,
  cancellation_reason text,
  released_at timestamptz,
  actual_release_date date,
  released_by uuid references public.user_profiles (id) on delete set null,
  release_method text,
  release_reference text,
  activated_at timestamptz,
  completed_at timestamptz,
  defaulted_at timestamptz,
  defaulted_by uuid references public.user_profiles (id) on delete set null,
  default_reason text,
  is_demo boolean not null default false,
  row_version integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, loan_number),
  check (first_due_date > release_date),
  check (array_length(allocation_order, 1) = 4)
);
create index loans_org_status_idx on public.loans (organization_id, status);
create index loans_borrower_idx on public.loans (borrower_id);
create index loans_officer_idx on public.loans (loan_officer_id);
create index loans_collector_idx on public.loans (collector_id);
create index loans_org_released_idx on public.loans (organization_id, actual_release_date);

create table public.loan_applications (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  loan_id uuid not null unique references public.loans (id) on delete restrict,
  application_number text not null,
  purpose text,
  source text not null default 'walk_in' check (source in ('walk_in', 'referral', 'online', 'renewal', 'field')),
  requested_amount numeric(14, 2) not null,
  requested_term integer not null,
  decision text not null default 'pending' check (decision in ('pending', 'approved', 'rejected', 'withdrawn')),
  decided_by uuid references public.user_profiles (id) on delete set null,
  decided_at timestamptz,
  decision_notes text,
  submitted_by uuid references public.user_profiles (id) on delete set null,
  submitted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, application_number)
);

-- Frozen contractual terms (original + amendments)
create table public.loan_terms_snapshots (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  loan_id uuid not null references public.loans (id) on delete restrict,
  version integer not null,
  kind text not null default 'original' check (kind in ('original', 'amendment')),
  terms jsonb not null,
  summary jsonb not null,
  schedule jsonb not null,
  terms_hash text not null,
  reason text,
  created_by uuid references public.user_profiles (id) on delete set null,
  created_at timestamptz not null default now(),
  unique (loan_id, version)
);

alter table public.loans
  add column terms_snapshot_id uuid references public.loan_terms_snapshots (id) on delete restrict;

create table public.loan_installments (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  loan_id uuid not null references public.loans (id) on delete restrict,
  installment_no integer not null,
  due_date date not null,
  opening_balance numeric(14, 2) not null,
  principal_due numeric(14, 2) not null default 0,
  interest_due numeric(14, 2) not null default 0,
  charges_due numeric(14, 2) not null default 0,
  closing_balance numeric(14, 2) not null,
  principal_paid numeric(14, 2) not null default 0,
  interest_paid numeric(14, 2) not null default 0,
  charges_paid numeric(14, 2) not null default 0,
  penalty_due numeric(14, 2) not null default 0,
  penalty_paid numeric(14, 2) not null default 0,
  penalty_waived numeric(14, 2) not null default 0,
  interest_waived numeric(14, 2) not null default 0,
  charges_waived numeric(14, 2) not null default 0,
  penalty_accrued_through date,
  status text not null default 'pending' check (status in ('pending', 'partially_paid', 'paid', 'waived', 'rescheduled')),
  paid_at date,
  last_payment_date date,
  snapshot_version integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (loan_id, installment_no),
  check (principal_paid >= 0 and interest_paid >= 0 and charges_paid >= 0 and penalty_paid >= 0),
  check (principal_paid <= principal_due),
  check (interest_paid + interest_waived <= interest_due),
  check (charges_paid + charges_waived <= charges_due),
  check (penalty_paid + penalty_waived <= penalty_due)
);
create index loan_installments_loan_idx on public.loan_installments (loan_id, installment_no);
create index loan_installments_org_due_idx on public.loan_installments (organization_id, due_date) where status in ('pending', 'partially_paid');

create table public.loan_charges (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  loan_id uuid not null references public.loans (id) on delete restrict,
  installment_id uuid references public.loan_installments (id) on delete restrict,
  charge_type text not null check (charge_type in ('processing_fee', 'other', 'adjustment')),
  description text,
  amount numeric(14, 2) not null check (amount >= 0),
  treatment text not null check (treatment in ('added', 'deducted')),
  created_by uuid references public.user_profiles (id) on delete set null,
  created_at timestamptz not null default now()
);
create index loan_charges_loan_idx on public.loan_charges (loan_id);

create table public.loan_penalties (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  loan_id uuid not null references public.loans (id) on delete restrict,
  installment_id uuid not null references public.loan_installments (id) on delete restrict,
  penalty_type text not null,
  accrued_from date not null,
  accrued_to date not null,
  days integer not null default 0,
  base_amount numeric(14, 2) not null,
  rate numeric(10, 4) not null,
  amount numeric(14, 2) not null check (amount >= 0),
  payment_id uuid,
  created_at timestamptz not null default now()
);
create index loan_penalties_loan_idx on public.loan_penalties (loan_id);

-- ---------------------------------------------------------------------------
-- Agreements & signing
-- ---------------------------------------------------------------------------
create table public.loan_agreements (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  loan_id uuid not null references public.loans (id) on delete restrict,
  agreement_number text not null,
  version integer not null,
  kind text not null default 'original' check (kind in ('original', 'amendment')),
  status text not null default 'generating' check (status in
    ('generating', 'failed', 'pending_signatures', 'partially_signed', 'fully_signed', 'superseded', 'voided')),
  template_id uuid references public.loan_agreement_templates (id) on delete set null,
  template_version_id uuid references public.loan_agreement_template_versions (id) on delete set null,
  terms_snapshot_id uuid not null references public.loan_terms_snapshots (id) on delete restrict,
  placeholders jsonb not null default '{}'::jsonb,
  rendered_body text,
  required_signers text[] not null default array['borrower', 'lender'],
  signing_order text not null default 'borrower_first',
  signature_fields jsonb not null default '{}'::jsonb,
  unsigned_pdf_path text,
  unsigned_pdf_hash text,
  current_pdf_path text,
  current_pdf_hash text,
  current_signature_count integer not null default 0,
  signed_pdf_path text,
  signed_pdf_hash text,
  page_count integer,
  generated_by uuid references public.user_profiles (id) on delete set null,
  generated_at timestamptz,
  fully_signed_at timestamptz,
  voided_at timestamptz,
  voided_by uuid references public.user_profiles (id) on delete set null,
  void_reason text,
  superseded_by uuid references public.loan_agreements (id) on delete set null,
  error_message text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (loan_id, version),
  unique (organization_id, agreement_number)
);
create index loan_agreements_org_status_idx on public.loan_agreements (organization_id, status);

create table public.agreement_signing_sessions (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  agreement_id uuid not null references public.loan_agreements (id) on delete restrict,
  signer_role text not null check (signer_role in ('borrower', 'lender', 'witness_1', 'witness_2')),
  signer_name text not null,
  signer_contact text,
  token_hash text unique,
  channel text not null default 'link' check (channel in ('link', 'in_app')),
  verification_method text not null default 'none' check (verification_method in ('none', 'dob', 'id_last4')),
  verify_nonce_hash text,
  status text not null default 'pending' check (status in ('pending', 'opened', 'verified', 'signed', 'expired', 'revoked')),
  expires_at timestamptz not null,
  opened_at timestamptz,
  verified_at timestamptz,
  signed_at timestamptz,
  failed_attempts integer not null default 0,
  locked_until timestamptz,
  created_by uuid references public.user_profiles (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index agreement_signing_sessions_agreement_idx on public.agreement_signing_sessions (agreement_id);

create table public.agreement_signatures (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  agreement_id uuid not null references public.loan_agreements (id) on delete restrict,
  session_id uuid references public.agreement_signing_sessions (id) on delete restrict,
  signer_role text not null,
  signer_name text not null,
  signer_user_id uuid references auth.users (id) on delete set null,
  signature_image_path text not null,
  signature_image_hash text not null,
  consent_text text not null,
  consent_given_at timestamptz not null,
  verification_method text,
  ip_address text,
  user_agent text,
  signed_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  unique (agreement_id, signer_role)
);

create table public.agreement_audit_events (
  id bigint generated always as identity primary key,
  organization_id uuid not null references public.organizations (id) on delete restrict,
  agreement_id uuid not null references public.loan_agreements (id) on delete restrict,
  session_id uuid references public.agreement_signing_sessions (id) on delete set null,
  event_type text not null,
  actor_type text not null default 'user' check (actor_type in ('user', 'signer', 'system')),
  actor_user_id uuid references auth.users (id) on delete set null,
  actor_name text,
  ip_address text,
  user_agent text,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index agreement_audit_events_agreement_idx on public.agreement_audit_events (agreement_id, created_at);

-- ---------------------------------------------------------------------------
-- Payments, allocations, ledger, receipts
-- ---------------------------------------------------------------------------
create table public.loan_payments (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  loan_id uuid not null references public.loans (id) on delete restrict,
  borrower_id uuid not null references public.borrowers (id) on delete restrict,
  payment_number text not null,
  amount numeric(14, 2) not null check (amount > 0),
  payment_date date not null,
  method text not null check (method in ('cash', 'bank_transfer', 'e_wallet', 'check', 'other')),
  method_detail text,
  reference_no text,
  proof_path text,
  notes text,
  payment_type text not null default 'regular' check (payment_type in ('regular', 'settlement')),
  target_installment_id uuid references public.loan_installments (id) on delete restrict,
  status text not null default 'posted' check (status in ('posted', 'reversed')),
  idempotency_key uuid not null,
  principal_applied numeric(14, 2) not null default 0,
  interest_applied numeric(14, 2) not null default 0,
  charges_applied numeric(14, 2) not null default 0,
  penalty_applied numeric(14, 2) not null default 0,
  interest_rebated numeric(14, 2) not null default 0,
  balance_after numeric(14, 2),
  received_by uuid references public.user_profiles (id) on delete set null,
  recorded_by uuid references public.user_profiles (id) on delete set null,
  reversed_at timestamptz,
  reversed_by uuid references public.user_profiles (id) on delete set null,
  reversal_reason text,
  created_at timestamptz not null default now(),
  unique (organization_id, idempotency_key),
  unique (organization_id, payment_number)
);
create index loan_payments_loan_idx on public.loan_payments (loan_id, payment_date);
create index loan_payments_org_date_idx on public.loan_payments (organization_id, payment_date) where status = 'posted';
create unique index loan_payments_reference_uniq on public.loan_payments (organization_id, method, lower(reference_no))
  where reference_no is not null and status = 'posted' and method <> 'cash';

create table public.payment_allocations (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  payment_id uuid not null references public.loan_payments (id) on delete restrict,
  loan_id uuid not null references public.loans (id) on delete restrict,
  installment_id uuid not null references public.loan_installments (id) on delete restrict,
  principal_amount numeric(14, 2) not null default 0,
  interest_amount numeric(14, 2) not null default 0,
  charges_amount numeric(14, 2) not null default 0,
  penalty_amount numeric(14, 2) not null default 0,
  interest_rebate numeric(14, 2) not null default 0,
  total_amount numeric(14, 2) generated always as (principal_amount + interest_amount + charges_amount + penalty_amount) stored,
  is_reversal boolean not null default false,
  created_at timestamptz not null default now()
);
create index payment_allocations_payment_idx on public.payment_allocations (payment_id);
create index payment_allocations_installment_idx on public.payment_allocations (installment_id);

create table public.financial_ledger (
  id uuid primary key default gen_random_uuid(),
  entry_no bigint generated always as identity,
  organization_id uuid not null references public.organizations (id) on delete restrict,
  loan_id uuid not null references public.loans (id) on delete restrict,
  payment_id uuid references public.loan_payments (id) on delete restrict,
  entry_type text not null check (entry_type in (
    'disbursement', 'loan_booked', 'fee_deducted', 'payment', 'payment_reversal',
    'penalty_assessed', 'penalty_waived', 'interest_waived', 'charges_waived',
    'charge_added', 'settlement_rebate', 'reschedule', 'write_off', 'adjustment')),
  entry_date date not null,
  amount numeric(14, 2) not null,
  cash_delta numeric(14, 2) not null default 0,
  principal_delta numeric(14, 2) not null default 0,
  interest_delta numeric(14, 2) not null default 0,
  charges_delta numeric(14, 2) not null default 0,
  penalty_delta numeric(14, 2) not null default 0,
  balance_principal numeric(14, 2) not null,
  balance_interest numeric(14, 2) not null,
  balance_charges numeric(14, 2) not null,
  balance_penalty numeric(14, 2) not null,
  balance_total numeric(14, 2) not null,
  description text,
  created_by uuid references auth.users (id) on delete set null,
  created_at timestamptz not null default now()
);
create index financial_ledger_loan_idx on public.financial_ledger (loan_id, entry_no);
create index financial_ledger_org_date_idx on public.financial_ledger (organization_id, entry_date, entry_type);

create table public.payment_receipts (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  payment_id uuid not null unique references public.loan_payments (id) on delete restrict,
  receipt_number text not null,
  issued_at timestamptz not null default now(),
  issued_by uuid references public.user_profiles (id) on delete set null,
  data jsonb not null,
  pdf_path text,
  is_void boolean not null default false,
  voided_at timestamptz,
  created_at timestamptz not null default now(),
  unique (organization_id, receipt_number)
);

-- ---------------------------------------------------------------------------
-- Collections, notes, notifications, audit
-- ---------------------------------------------------------------------------
create table public.collector_assignments (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  collector_id uuid not null references public.user_profiles (id) on delete cascade,
  borrower_id uuid not null references public.borrowers (id) on delete cascade,
  loan_id uuid references public.loans (id) on delete cascade,
  is_active boolean not null default true,
  notes text,
  assigned_by uuid references public.user_profiles (id) on delete set null,
  assigned_at timestamptz not null default now(),
  unassigned_at timestamptz
);
create index collector_assignments_collector_idx on public.collector_assignments (collector_id) where is_active;
create index collector_assignments_borrower_idx on public.collector_assignments (borrower_id) where is_active;

create table public.loan_notes (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  loan_id uuid references public.loans (id) on delete cascade,
  borrower_id uuid references public.borrowers (id) on delete cascade,
  note_type text not null default 'note' check (note_type in ('note', 'call', 'visit', 'promise_to_pay', 'system')),
  body text not null,
  promise_date date,
  promise_amount numeric(14, 2),
  created_by uuid references public.user_profiles (id) on delete set null,
  created_at timestamptz not null default now(),
  check (loan_id is not null or borrower_id is not null)
);
create index loan_notes_loan_idx on public.loan_notes (loan_id, created_at desc);
create index loan_notes_borrower_idx on public.loan_notes (borrower_id, created_at desc);

create table public.notifications (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  type text not null,
  title text not null,
  body text,
  link text,
  read_at timestamptz,
  created_at timestamptz not null default now()
);
create index notifications_user_idx on public.notifications (user_id, created_at desc);

create table public.audit_logs (
  id bigint generated always as identity primary key,
  organization_id uuid references public.organizations (id) on delete cascade,
  actor_id uuid,
  actor_name text,
  action text not null,
  table_name text not null,
  record_id text,
  old_data jsonb,
  new_data jsonb,
  changed_fields text[],
  context jsonb,
  created_at timestamptz not null default now()
);
create index audit_logs_org_created_idx on public.audit_logs (organization_id, created_at desc);
create index audit_logs_record_idx on public.audit_logs (table_name, record_id);

-- ---------------------------------------------------------------------------
-- updated_at triggers
-- ---------------------------------------------------------------------------
do $$
declare t text;
begin
  foreach t in array array[
    'organizations', 'user_profiles', 'borrowers', 'borrower_identities', 'guarantors',
    'loan_agreement_templates', 'loan_products', 'loans', 'loan_applications', 'loan_installments',
    'loan_agreements', 'agreement_signing_sessions'
  ] loop
    execute format('create trigger %I before update on public.%I for each row execute function public.lf_touch_updated_at()',
      t || '_touch_updated_at', t);
  end loop;
end $$;

-- ---------------------------------------------------------------------------
-- Access-scope helpers (depend on tables above)
-- ---------------------------------------------------------------------------
create or replace function public.can_access_borrower(p_borrower uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.borrowers b
    where b.id = p_borrower
      and b.organization_id = public.current_org_id()
      and (
        public.has_permission('borrowers.view_all')
        or b.assigned_officer_id = auth.uid()
        or b.created_by = auth.uid()
        or exists (select 1 from public.collector_assignments ca
                   where ca.borrower_id = b.id and ca.collector_id = auth.uid() and ca.is_active)
        or exists (select 1 from public.loans l
                   where l.borrower_id = b.id and (l.loan_officer_id = auth.uid() or l.collector_id = auth.uid()))
      )
  )
$$;

create or replace function public.can_access_loan(p_loan uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.loans l
    where l.id = p_loan
      and l.organization_id = public.current_org_id()
      and (
        public.has_permission('loans.view_all')
        or l.loan_officer_id = auth.uid()
        or l.collector_id = auth.uid()
        or l.created_by = auth.uid()
        or exists (select 1 from public.collector_assignments ca
                   where ca.borrower_id = l.borrower_id and ca.collector_id = auth.uid() and ca.is_active)
      )
  )
$$;

-- Notify all active members of an organization holding one of the given roles
create or replace function public.lf_notify_roles(p_org uuid, p_roles public.app_role[], p_type text, p_title text, p_body text, p_link text)
returns void language sql security definer set search_path = public as $$
  insert into public.notifications (organization_id, user_id, type, title, body, link)
  select p_org, ur.user_id, p_type, p_title, p_body, p_link
  from public.user_roles ur
  join public.user_profiles up on up.id = ur.user_id and up.is_active
  where ur.organization_id = p_org and ur.role = any (p_roles)
$$;

create or replace function public.lf_notify_user(p_org uuid, p_user uuid, p_type text, p_title text, p_body text, p_link text)
returns void language sql security definer set search_path = public as $$
  insert into public.notifications (organization_id, user_id, type, title, body, link)
  select p_org, p_user, p_type, p_title, p_body, p_link
  where p_user is not null
$$;
