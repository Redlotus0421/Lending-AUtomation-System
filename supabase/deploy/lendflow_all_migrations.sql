-- LendFlow: all migrations bundled for the Supabase SQL Editor (generated; do not edit)

-- ==== 20261114000001_core_schema.sql ====
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


-- ==== 20261114000002_loan_engine.sql ====
-- =============================================================================
-- LendFlow — Loan calculation engine (authoritative, server-side, decimal-safe)
--
-- All arithmetic uses PostgreSQL numeric (arbitrary precision decimal).
-- Rate conventions:
--   * The entered rate is a NOMINAL rate for the selected rate period
--     (e.g. "2% per month, nominal").
--   * Periods per year: monthly 12, weekly 52, daily 360 (30/360 basis) or 365.
--   * Periodic rate per installment = rate × ppy(rate_period) / ppy(frequency).
--     No silent mixing: the conversion is returned in the disclosures.
-- Methods:
--   * flat        Total interest = P × periodic rate × number of installments.
--   * diminishing Level payment A = P·r / (1 − (1 + r)^−n) (30/360 basis), or
--                 a solved level payment when interest accrues on actual days
--                 (actual/365, actual/actual — leap years handled).
--   The final installment always absorbs rounding differences.
-- =============================================================================

create or replace function public.lf_ppy(p_period text, p_day_count text default '30/360')
returns numeric language sql immutable as $$
  select case p_period
    when 'daily' then case when p_day_count = '30/360' then 360 else 365 end
    when 'weekly' then 52
    when 'monthly' then 12
    when 'yearly' then 1
  end::numeric
$$;

create or replace function public.lf_period_label(p_period text, p_plural boolean default false)
returns text language sql immutable as $$
  select case p_period
    when 'daily' then case when p_plural then 'days' else 'day' end
    when 'weekly' then case when p_plural then 'weeks' else 'week' end
    when 'monthly' then case when p_plural then 'months' else 'month' end
    when 'yearly' then case when p_plural then 'years' else 'year' end
  end
$$;

-- Due date of installment i (1-based), anchored on the first due date to avoid drift.
-- Monthly: Jan 31 -> Feb 28/29 -> Mar 31 (end-of-month clamping, leap-year aware)
create or replace function public.lf_due_date(p_first date, p_freq text, p_i integer)
returns date language sql immutable as $$
  select case p_freq
    when 'monthly' then (p_first + make_interval(months => p_i - 1))::date
    when 'weekly' then p_first + (p_i - 1) * 7
    when 'daily' then p_first + (p_i - 1)
  end
$$;

-- Default first due date: one repayment period after release
create or replace function public.lf_default_first_due(p_release date, p_freq text)
returns date language sql immutable as $$
  select case p_freq
    when 'monthly' then (p_release + interval '1 month')::date
    when 'weekly' then p_release + 7
    when 'daily' then p_release + 1
  end
$$;

-- Year fraction between two dates for actual-day accrual (d1 exclusive, d2 inclusive)
create or replace function public.lf_year_fraction(p_d1 date, p_d2 date, p_day_count text)
returns numeric language plpgsql immutable as $$
declare
  v_total numeric := 0;
  v_cursor date := p_d1;
  v_year integer;
  v_seg_end date;
  v_days_in_year integer;
begin
  if p_d2 <= p_d1 then return 0; end if;
  if p_day_count = 'actual/365' then
    return (p_d2 - p_d1)::numeric / 365;
  end if;
  -- actual/actual: split the period at year boundaries; days counted are (cursor, seg_end]
  while v_cursor < p_d2 loop
    v_year := extract(year from v_cursor + 1)::int;
    v_seg_end := least(make_date(v_year, 12, 31), p_d2);
    v_days_in_year := (make_date(v_year, 12, 31) - make_date(v_year, 1, 1) + 1);
    v_total := v_total + (v_seg_end - v_cursor)::numeric / v_days_in_year;
    v_cursor := v_seg_end;
  end loop;
  return v_total;
end $$;

-- Format a numeric without trailing zeros (2.500000 -> 2.5)
create or replace function public.lf_fmt_num(v numeric)
returns text language sql immutable as $$
  select trim_scale(v)::text
$$;

-- Periodic internal rate of return by bisection (double precision is fine for a disclosure figure)
create or replace function public.lf_irr(p_net numeric, p_flows numeric[])
returns numeric language plpgsql immutable as $$
declare
  lo double precision := 0;
  hi double precision := 1;
  mid double precision;
  npv double precision;
  i integer;
  k integer;
  n integer := coalesce(array_length(p_flows, 1), 0);
  total double precision := 0;
begin
  if n = 0 or p_net <= 0 then return null; end if;
  for k in 1..n loop total := total + p_flows[k]; end loop;
  if total <= p_net then return 0; end if;
  -- expand upper bound if needed
  loop
    npv := -p_net;
    for k in 1..n loop npv := npv + p_flows[k] / power(1 + hi, k); end loop;
    exit when npv < 0 or hi > 1000;
    hi := hi * 2;
  end loop;
  for i in 1..200 loop
    mid := (lo + hi) / 2;
    npv := -p_net;
    for k in 1..n loop npv := npv + p_flows[k] / power(1 + mid, k); end loop;
    if npv > 0 then lo := mid; else hi := mid; end if;
    exit when hi - lo < 1e-12;
  end loop;
  return ((lo + hi) / 2)::numeric;
end $$;

-- -----------------------------------------------------------------------------
-- compute_loan_schedule(params jsonb) -> jsonb
-- Pure function: validates inputs, returns summary + full amortization schedule.
-- -----------------------------------------------------------------------------
create or replace function public.compute_loan_schedule(p jsonb)
returns jsonb language plpgsql stable as $$
declare
  v_principal numeric;
  v_method text := coalesce(nullif(p->>'interest_method', ''), 'flat');
  v_rate numeric := coalesce(nullif(p->>'interest_rate', '')::numeric, 0);
  v_rate_period text := coalesce(nullif(p->>'rate_period', ''), 'monthly');
  v_freq text := coalesce(nullif(p->>'repayment_frequency', ''), 'monthly');
  v_n integer := nullif(p->>'term_count', '')::integer;
  v_release date := coalesce(nullif(p->>'release_date', '')::date, current_date);
  v_first date := nullif(p->>'first_due_date', '')::date;
  v_day_count text := coalesce(nullif(p->>'day_count', ''), '30/360');
  v_rounding text := coalesce(nullif(p->>'rounding', ''), 'centavo');
  v_fee_type text := coalesce(nullif(p->>'processing_fee_type', ''), 'none');
  v_fee_value numeric := coalesce(nullif(p->>'processing_fee_value', '')::numeric, 0);
  v_other numeric := round(coalesce(nullif(p->>'other_charges', '')::numeric, 0), 2);
  v_treatment text := coalesce(nullif(p->>'charges_treatment', ''), 'deducted');

  v_scale integer;
  v_annual numeric;
  v_r numeric;
  v_fee numeric := 0;
  v_charges numeric;
  v_financed numeric;
  v_deducted numeric;
  v_net numeric;

  v_due date[] := '{}';
  v_rate_k numeric[] := '{}';
  v_pmt numeric;
  v_bal numeric;
  v_int numeric;
  v_prin numeric;
  v_chg numeric;
  v_chg_each numeric;
  v_int_each numeric;
  v_prin_each numeric;
  v_total_interest numeric := 0;
  v_flat_interest numeric;
  v_sum_int numeric := 0;
  v_sum_chg numeric := 0;
  v_sum_prin numeric := 0;
  v_rows jsonb := '[]'::jsonb;
  v_flows numeric[] := '{}';
  v_prev date;
  v_lo numeric; v_hi numeric; v_mid numeric; v_res numeric;
  v_iter integer;
  k integer;
  v_irr numeric;
  v_ppy_freq numeric;
  v_last_total numeric;
  v_first_total numeric;
  v_warnings jsonb := '[]'::jsonb;
  v_total_repayment numeric;
begin
  -- ---------- validation ----------
  begin
    v_principal := (p->>'principal')::numeric;
  exception when others then
    raise exception 'Loan amount is not a valid number' using errcode = '22023';
  end;
  if v_principal is null or v_principal <= 0 then
    raise exception 'Loan amount must be greater than zero' using errcode = '22023';
  end if;
  if v_principal > 999999999999 then
    raise exception 'Loan amount is too large' using errcode = '22023';
  end if;
  if v_method not in ('flat', 'diminishing') then
    raise exception 'Unknown interest method "%"', v_method using errcode = '22023';
  end if;
  if v_rate_period not in ('daily', 'weekly', 'monthly', 'yearly') then
    raise exception 'Unknown interest rate period "%"', v_rate_period using errcode = '22023';
  end if;
  if v_freq not in ('daily', 'weekly', 'monthly') then
    raise exception 'Unknown repayment frequency "%"', v_freq using errcode = '22023';
  end if;
  if v_day_count not in ('30/360', 'actual/365', 'actual/actual') then
    raise exception 'Unknown day-count basis "%"', v_day_count using errcode = '22023';
  end if;
  if v_rounding not in ('centavo', 'peso') then
    raise exception 'Unknown rounding rule "%"', v_rounding using errcode = '22023';
  end if;
  if v_rate < 0 then
    raise exception 'Interest rate cannot be negative' using errcode = '22023';
  end if;
  if v_rate > 100 then
    raise exception 'Interest rate above 100%% per period is not allowed' using errcode = '22023';
  end if;
  if v_n is null or v_n < 1 then
    raise exception 'Repayment duration must be at least 1 installment' using errcode = '22023';
  end if;
  if v_n > 3660 then
    raise exception 'Repayment duration is too long (maximum 3,660 installments)' using errcode = '22023';
  end if;
  if v_fee_type not in ('none', 'fixed', 'percent') then
    raise exception 'Unknown processing fee type "%"', v_fee_type using errcode = '22023';
  end if;
  if v_fee_value < 0 or v_other < 0 then
    raise exception 'Fees and charges cannot be negative' using errcode = '22023';
  end if;
  if v_treatment not in ('added', 'deducted') then
    raise exception 'Unknown charges treatment "%"', v_treatment using errcode = '22023';
  end if;
  if v_first is null then
    v_first := public.lf_default_first_due(v_release, v_freq);
  end if;
  if v_first <= v_release then
    raise exception 'First repayment date must be after the loan release date' using errcode = '22023';
  end if;

  v_principal := round(v_principal, 2);
  v_scale := case when v_rounding = 'peso' then 0 else 2 end;

  -- ---------- rates ----------
  v_ppy_freq := public.lf_ppy(v_freq, v_day_count);
  v_annual := v_rate / 100 * public.lf_ppy(v_rate_period, v_day_count);
  v_r := v_annual / v_ppy_freq;

  -- ---------- charges ----------
  v_fee := case v_fee_type
    when 'fixed' then round(v_fee_value, 2)
    when 'percent' then round(v_principal * v_fee_value / 100, 2)
    else 0 end;
  v_charges := v_fee + v_other;
  v_financed := case when v_treatment = 'added' then v_charges else 0 end;
  v_deducted := case when v_treatment = 'deducted' then v_charges else 0 end;
  v_net := v_principal - v_deducted;
  if v_net <= 0 then
    raise exception 'Deducted fees and charges cannot equal or exceed the loan amount' using errcode = '22023';
  end if;

  -- ---------- due dates & per-period rates ----------
  v_prev := v_release;
  for k in 1..v_n loop
    v_due := v_due || public.lf_due_date(v_first, v_freq, k);
    if v_method = 'diminishing' and v_day_count <> '30/360' then
      v_rate_k := v_rate_k || (v_annual * public.lf_year_fraction(v_prev, v_due[k], v_day_count));
    else
      v_rate_k := v_rate_k || v_r;
    end if;
    v_prev := v_due[k];
  end loop;

  v_chg_each := round(v_financed / v_n, 2);

  -- ---------- schedule ----------
  if v_method = 'flat' then
    v_flat_interest := round(v_principal * v_r * v_n, 2);
    v_int_each := round(v_flat_interest / v_n, 2);
    v_pmt := round((v_principal + v_flat_interest + v_financed) / v_n, v_scale);
    v_bal := v_principal;
    for k in 1..v_n loop
      if k < v_n then
        v_int := v_int_each;
        v_chg := v_chg_each;
        v_prin := v_pmt - v_int - v_chg;
        if v_prin < 0 then
          raise exception 'Installment rounding produced a negative principal portion; use centavo rounding' using errcode = '22023';
        end if;
        if v_prin > v_bal then v_prin := v_bal; end if;
      else
        v_int := v_flat_interest - v_sum_int;
        v_chg := v_financed - v_sum_chg;
        v_prin := v_bal;
      end if;
      v_rows := v_rows || jsonb_build_object(
        'installment_no', k, 'due_date', v_due[k],
        'opening_balance', v_bal, 'principal', v_prin, 'interest', v_int, 'charges', v_chg,
        'total', v_prin + v_int + v_chg, 'closing_balance', v_bal - v_prin);
      v_flows := v_flows || (v_prin + v_int + v_chg);
      v_sum_int := v_sum_int + v_int;
      v_sum_chg := v_sum_chg + v_chg;
      v_sum_prin := v_sum_prin + v_prin;
      v_bal := v_bal - v_prin;
    end loop;
    v_total_interest := v_sum_int;
  else
    -- diminishing balance: find level payment
    if v_r = 0 then
      v_pmt := v_principal / v_n;
    elsif v_day_count = '30/360' then
      v_pmt := v_principal * v_r / (1 - power(1 + v_r, -v_n));
    else
      -- solve for the level payment that amortizes to zero under actual-day accrual
      v_lo := v_principal / v_n;
      v_hi := v_principal * (1 + v_annual * public.lf_year_fraction(v_release, v_due[v_n], v_day_count)) + 1;
      for v_iter in 1..120 loop
        v_mid := (v_lo + v_hi) / 2;
        v_res := v_principal;
        for k in 1..v_n loop
          v_res := v_res - (v_mid - round(v_res * v_rate_k[k], 2));
        end loop;
        if v_res > 0 then v_lo := v_mid; else v_hi := v_mid; end if;
        exit when v_hi - v_lo < 0.000001;
      end loop;
      v_pmt := (v_lo + v_hi) / 2;
    end if;
    v_pmt := round(v_pmt, v_scale);
    if v_pmt <= 0 then
      raise exception 'Computed installment is zero; check the loan amount and duration' using errcode = '22023';
    end if;

    v_bal := v_principal;
    for k in 1..v_n loop
      v_int := round(v_bal * v_rate_k[k], 2);
      if k < v_n then
        v_prin := v_pmt - v_int;
        v_chg := v_chg_each;
        if v_prin < 0 then
          raise exception 'Installment does not cover interest (negative amortization); adjust the terms' using errcode = '22023';
        end if;
        if v_prin >= v_bal then
          raise exception 'Rounding to whole pesos pays the loan off early; use centavo rounding for these terms' using errcode = '22023';
        end if;
      else
        v_prin := v_bal;
        v_chg := v_financed - v_sum_chg;
      end if;
      v_rows := v_rows || jsonb_build_object(
        'installment_no', k, 'due_date', v_due[k],
        'opening_balance', v_bal, 'principal', v_prin, 'interest', v_int, 'charges', v_chg,
        'total', v_prin + v_int + v_chg, 'closing_balance', v_bal - v_prin);
      v_flows := v_flows || (v_prin + v_int + v_chg);
      v_sum_int := v_sum_int + v_int;
      v_sum_chg := v_sum_chg + v_chg;
      v_sum_prin := v_sum_prin + v_prin;
      v_bal := v_bal - v_prin;
    end loop;
    v_total_interest := v_sum_int;
  end if;

  -- integrity checks
  if v_sum_prin <> v_principal then
    raise exception 'Internal calculation error: principal mismatch (% vs %)', v_sum_prin, v_principal;
  end if;
  if v_sum_chg <> v_financed then
    raise exception 'Internal calculation error: charges mismatch';
  end if;

  v_first_total := (v_rows->0->>'total')::numeric;
  v_last_total := (v_rows->(v_n - 1)->>'total')::numeric;
  v_total_repayment := v_principal + v_total_interest + v_financed;
  v_irr := public.lf_irr(v_net, v_flows);

  if v_first > public.lf_default_first_due(v_release, v_freq) + 31 then
    v_warnings := v_warnings || to_jsonb('The first repayment date is more than one period after release; the first period is longer than the others.'::text);
  end if;
  if v_method = 'flat' and v_n > 1 then
    v_warnings := v_warnings || to_jsonb('Flat interest is computed on the original principal for the full term; the effective interest rate is higher than the nominal rate.'::text);
  end if;

  return jsonb_build_object(
    'summary', jsonb_build_object(
      'principal', v_principal,
      'interest_method', v_method,
      'interest_rate', v_rate,
      'rate_period', v_rate_period,
      'rate_basis', 'nominal',
      'day_count', v_day_count,
      'rounding', v_rounding,
      'repayment_frequency', v_freq,
      'term_count', v_n,
      'installment_count', v_n,
      'duration_label', v_n || ' ' || public.lf_period_label(v_freq, v_n <> 1),
      'release_date', v_release,
      'first_due_date', v_first,
      'last_due_date', v_due[v_n],
      'annual_nominal_rate', round(v_annual * 100, 6),
      'periodic_rate', v_r,
      'periodic_rate_pct', round(v_r * 100, 6),
      'processing_fee_type', v_fee_type,
      'processing_fee_value', v_fee_value,
      'processing_fee', v_fee,
      'other_charges', v_other,
      'total_charges', v_charges,
      'charges_treatment', v_treatment,
      'financed_charges', v_financed,
      'deducted_charges', v_deducted,
      'net_proceeds', v_net,
      'total_interest', v_total_interest,
      'total_repayment', v_total_repayment,
      'finance_charge', v_total_interest + v_charges,
      'installment_amount', v_first_total,
      'last_installment_amount', v_last_total,
      'eir_periodic_pct', case when v_irr is null then null else round(v_irr * 100, 6) end,
      'eir_monthly_pct', case when v_irr is null then null else round(v_irr * v_ppy_freq / 12 * 100, 4) end,
      'eir_annual_nominal_pct', case when v_irr is null then null else round(v_irr * v_ppy_freq * 100, 4) end,
      'effective_annual_rate_pct', case when v_irr is null then null
                                        else round(((power(1 + v_irr::double precision, v_ppy_freq::double precision) - 1) * 100)::numeric, 4) end
    ),
    'schedule', v_rows,
    'disclosures', jsonb_build_array(
      format('Interest rate entered: %s%% per %s (nominal).', public.lf_fmt_num(v_rate), public.lf_period_label(v_rate_period)),
      format('Equivalent nominal annual rate: %s%%; rate applied per %s installment period: %s%%.',
             public.lf_fmt_num(round(v_annual * 100, 4)), public.lf_period_label(v_freq),
             public.lf_fmt_num(round(v_r * 100, 6))),
      case when v_method = 'flat'
        then 'Flat method: interest is computed on the original principal for the whole term (Principal × periodic rate × number of installments).'
        else 'Diminishing balance: interest each period is computed on the outstanding principal; payments are level and the final installment absorbs rounding.' end,
      format('Day-count basis: %s. Periods per year — monthly 12, weekly 52, daily %s.', v_day_count,
             case when v_day_count = '30/360' then 360 else 365 end),
      format('Fees and charges of PHP %s are %s.', to_char(v_charges, 'FM999,999,999,990.00'),
             case when v_treatment = 'added' then 'added to the installments' else 'deducted from the loan proceeds' end)
    ),
    'warnings', v_warnings
  );
end $$;


-- ==== 20261114000003_rls_policies.sql ====
-- =============================================================================
-- LendFlow — Row Level Security
-- Every table is organization-scoped. Financial tables are read-only for API
-- clients: all writes go through SECURITY DEFINER functions that enforce
-- permissions, validation and transactional integrity.
-- =============================================================================

do $$
declare t text;
begin
  foreach t in array array[
    'organizations', 'user_profiles', 'user_roles', 'role_permissions', 'system_settings', 'org_counters', 'rate_limits',
    'borrowers', 'borrower_identities', 'borrower_documents', 'guarantors',
    'loan_agreement_templates', 'loan_agreement_template_versions', 'loan_products',
    'loans', 'loan_applications', 'loan_terms_snapshots', 'loan_installments', 'loan_charges', 'loan_penalties',
    'loan_agreements', 'agreement_signing_sessions', 'agreement_signatures', 'agreement_audit_events',
    'loan_payments', 'payment_allocations', 'financial_ledger', 'payment_receipts',
    'collector_assignments', 'loan_notes', 'notifications', 'audit_logs'
  ] loop
    execute format('alter table public.%I enable row level security', t);
  end loop;
end $$;

-- organizations ---------------------------------------------------------------
create policy organizations_select on public.organizations for select to authenticated
  using (id = public.current_org_id());
create policy organizations_update on public.organizations for update to authenticated
  using (id = public.current_org_id() and public.has_permission('settings.manage'))
  with check (id = public.current_org_id());

-- user profiles / roles / permissions ----------------------------------------------
create policy user_profiles_select on public.user_profiles for select to authenticated
  using (id = auth.uid() or (organization_id is not null and organization_id = public.current_org_id()));

create policy user_roles_select on public.user_roles for select to authenticated
  using (user_id = auth.uid() or organization_id = public.current_org_id());

create policy role_permissions_select on public.role_permissions for select to authenticated
  using (organization_id = public.current_org_id());

create policy system_settings_select on public.system_settings for select to authenticated
  using (organization_id = public.current_org_id());

-- borrowers ---------------------------------------------------------------------
-- Row-based access check (does not re-read the row, so INSERT ... RETURNING works)
create or replace function public.lf_borrower_row_access(p_id uuid, p_officer uuid, p_created_by uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select public.has_permission('borrowers.view_all')
    or p_officer = auth.uid()
    or p_created_by = auth.uid()
    or exists (select 1 from public.collector_assignments ca
               where ca.borrower_id = p_id and ca.collector_id = auth.uid() and ca.is_active)
    or exists (select 1 from public.loans l
               where l.borrower_id = p_id and (l.loan_officer_id = auth.uid() or l.collector_id = auth.uid()))
$$;

create or replace function public.lf_loan_row_access(p_borrower uuid, p_officer uuid, p_collector uuid, p_created_by uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select public.has_permission('loans.view_all')
    or p_officer = auth.uid()
    or p_collector = auth.uid()
    or p_created_by = auth.uid()
    or exists (select 1 from public.collector_assignments ca
               where ca.borrower_id = p_borrower and ca.collector_id = auth.uid() and ca.is_active)
$$;

create policy borrowers_select on public.borrowers for select to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('borrowers.view')
         and public.lf_borrower_row_access(id, assigned_officer_id, created_by));
create policy borrowers_insert on public.borrowers for insert to authenticated
  with check (organization_id = public.current_org_id() and public.has_permission('borrowers.create'));
create policy borrowers_update on public.borrowers for update to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('borrowers.edit')
         and public.lf_borrower_row_access(id, assigned_officer_id, created_by))
  with check (organization_id = public.current_org_id());

create policy borrower_identities_select on public.borrower_identities for select to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('borrowers.view_sensitive')
         and public.can_access_borrower(borrower_id));
create policy borrower_identities_insert on public.borrower_identities for insert to authenticated
  with check (organization_id = public.current_org_id() and public.has_permission('borrowers.view_sensitive')
              and (public.has_permission('borrowers.create') or public.has_permission('borrowers.edit'))
              and public.can_access_borrower(borrower_id));
create policy borrower_identities_update on public.borrower_identities for update to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('borrowers.view_sensitive')
         and public.has_permission('borrowers.edit') and public.can_access_borrower(borrower_id))
  with check (organization_id = public.current_org_id());

create policy borrower_documents_select on public.borrower_documents for select to authenticated
  using (organization_id = public.current_org_id() and public.can_access_borrower(borrower_id));
create policy borrower_documents_insert on public.borrower_documents for insert to authenticated
  with check (organization_id = public.current_org_id()
              and (public.has_permission('borrowers.create') or public.has_permission('borrowers.edit'))
              and public.can_access_borrower(borrower_id));
create policy borrower_documents_delete on public.borrower_documents for delete to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('borrowers.edit') and public.can_access_borrower(borrower_id));

create policy guarantors_select on public.guarantors for select to authenticated
  using (organization_id = public.current_org_id() and public.can_access_borrower(borrower_id));
create policy guarantors_insert on public.guarantors for insert to authenticated
  with check (organization_id = public.current_org_id()
              and (public.has_permission('borrowers.create') or public.has_permission('borrowers.edit'))
              and public.can_access_borrower(borrower_id));
create policy guarantors_update on public.guarantors for update to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('borrowers.edit') and public.can_access_borrower(borrower_id))
  with check (organization_id = public.current_org_id());
create policy guarantors_delete on public.guarantors for delete to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('borrowers.edit') and public.can_access_borrower(borrower_id));

-- templates & products -------------------------------------------------------------
create policy templates_select on public.loan_agreement_templates for select to authenticated
  using (organization_id = public.current_org_id()
         and (public.has_permission('templates.manage') or public.has_permission('agreements.view') or public.has_permission('loans.create')));
create policy templates_insert on public.loan_agreement_templates for insert to authenticated
  with check (organization_id = public.current_org_id() and public.has_permission('templates.manage'));
create policy templates_update on public.loan_agreement_templates for update to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('templates.manage'))
  with check (organization_id = public.current_org_id());

create policy template_versions_select on public.loan_agreement_template_versions for select to authenticated
  using (organization_id = public.current_org_id()
         and (public.has_permission('templates.manage') or public.has_permission('agreements.view') or public.has_permission('loans.create')));

create policy products_select on public.loan_products for select to authenticated
  using (organization_id = public.current_org_id());
create policy products_insert on public.loan_products for insert to authenticated
  with check (organization_id = public.current_org_id() and public.has_permission('products.manage'));
create policy products_update on public.loan_products for update to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('products.manage'))
  with check (organization_id = public.current_org_id());

-- loans and loan children (read-only for clients) -----------------------------------------
create policy loans_select on public.loans for select to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('loans.view')
         and public.lf_loan_row_access(borrower_id, loan_officer_id, collector_id, created_by));
create policy loan_applications_select on public.loan_applications for select to authenticated
  using (organization_id = public.current_org_id() and public.can_access_loan(loan_id));
create policy loan_terms_snapshots_select on public.loan_terms_snapshots for select to authenticated
  using (organization_id = public.current_org_id() and public.can_access_loan(loan_id));
create policy loan_installments_select on public.loan_installments for select to authenticated
  using (organization_id = public.current_org_id() and public.can_access_loan(loan_id));
create policy loan_charges_select on public.loan_charges for select to authenticated
  using (organization_id = public.current_org_id() and public.can_access_loan(loan_id));
create policy loan_penalties_select on public.loan_penalties for select to authenticated
  using (organization_id = public.current_org_id() and public.can_access_loan(loan_id));

-- agreements -------------------------------------------------------------------------------
create policy loan_agreements_select on public.loan_agreements for select to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('agreements.view') and public.can_access_loan(loan_id));
create policy signing_sessions_select on public.agreement_signing_sessions for select to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('agreements.view')
         and exists (select 1 from public.loan_agreements a where a.id = agreement_id and public.can_access_loan(a.loan_id)));
create policy agreement_signatures_select on public.agreement_signatures for select to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('agreements.view')
         and exists (select 1 from public.loan_agreements a where a.id = agreement_id and public.can_access_loan(a.loan_id)));
create policy agreement_audit_events_select on public.agreement_audit_events for select to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('agreements.view')
         and exists (select 1 from public.loan_agreements a where a.id = agreement_id and public.can_access_loan(a.loan_id)));

-- payments ----------------------------------------------------------------------------------
create policy loan_payments_select on public.loan_payments for select to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('payments.view') and public.can_access_loan(loan_id));
create policy payment_allocations_select on public.payment_allocations for select to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('payments.view') and public.can_access_loan(loan_id));
create policy financial_ledger_select on public.financial_ledger for select to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('payments.view') and public.can_access_loan(loan_id));
create policy payment_receipts_select on public.payment_receipts for select to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('payments.view')
         and exists (select 1 from public.loan_payments lp where lp.id = payment_id and public.can_access_loan(lp.loan_id)));

-- collections / notes / notifications / audit ---------------------------------------------------
create policy collector_assignments_select on public.collector_assignments for select to authenticated
  using (organization_id = public.current_org_id()
         and (public.has_permission('collections.assign') or public.has_permission('borrowers.view_all') or collector_id = auth.uid()));

create policy loan_notes_select on public.loan_notes for select to authenticated
  using (organization_id = public.current_org_id()
         and (loan_id is null or public.can_access_loan(loan_id))
         and (borrower_id is null or public.can_access_borrower(borrower_id)));
create policy loan_notes_insert on public.loan_notes for insert to authenticated
  with check (organization_id = public.current_org_id() and created_by = auth.uid() and note_type <> 'system'
              and (loan_id is null or public.can_access_loan(loan_id))
              and (borrower_id is null or public.can_access_borrower(borrower_id)));

create policy notifications_select on public.notifications for select to authenticated
  using (user_id = auth.uid());
create policy notifications_update on public.notifications for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy audit_logs_select on public.audit_logs for select to authenticated
  using (organization_id = public.current_org_id() and public.has_permission('audit.view'));

-- ---------------------------------------------------------------------------
-- Borrower write guards (defaults, normalization, immutable columns)
-- ---------------------------------------------------------------------------
create or replace function public.lf_normalize_ph_mobile(p text)
returns text language plpgsql immutable as $$
declare d text;
begin
  if p is null then return null; end if;
  d := regexp_replace(p, '[^0-9]', '', 'g');
  if d ~ '^09[0-9]{9}$' then return '+63' || substr(d, 2); end if;
  if d ~ '^639[0-9]{9}$' then return '+' || d; end if;
  if d ~ '^9[0-9]{9}$' then return '+63' || d; end if;
  return p;
end $$;

create or replace function public.lf_borrowers_guard()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    new.organization_id := coalesce(new.organization_id, public.current_org_id());
    new.borrower_code := public.lf_next_ref(new.organization_id, 'borrower', 'BRW', false);
    new.created_by := coalesce(auth.uid(), new.created_by);
    new.mobile_number := public.lf_normalize_ph_mobile(new.mobile_number);
    new.emergency_contact_number := public.lf_normalize_ph_mobile(new.emergency_contact_number);
    if new.status <> 'active' and auth.uid() is not null then
      new.status := 'active';
    end if;
  else
    if new.organization_id <> old.organization_id or new.borrower_code <> old.borrower_code
       or new.created_by is distinct from old.created_by then
      raise exception 'Borrower organization, code and creator cannot be changed';
    end if;
    new.mobile_number := public.lf_normalize_ph_mobile(new.mobile_number);
    new.emergency_contact_number := public.lf_normalize_ph_mobile(new.emergency_contact_number);
    if new.status is distinct from old.status and auth.uid() is not null then
      if not public.has_permission('borrowers.archive') then
        raise exception 'You do not have permission to change borrower status' using errcode = '42501';
      end if;
      if new.status = 'archived' then
        if exists (select 1 from public.loans l where l.borrower_id = new.id
                   and l.status in ('active', 'ready_for_release', 'fully_signed', 'awaiting_borrower_signature', 'awaiting_lender_signature', 'approved', 'pending_review')) then
          raise exception 'Borrower has open loans and cannot be archived';
        end if;
        new.archived_at := now();
      else
        new.archived_at := null;
      end if;
    end if;
    if (new.risk_rating is distinct from old.risk_rating or new.assigned_officer_id is distinct from old.assigned_officer_id)
       and auth.uid() is not null and not public.has_permission('borrowers.view_all') then
      raise exception 'Only supervisors can change risk ratings or officer assignments' using errcode = '42501';
    end if;
  end if;
  return new;
end $$;

create trigger borrowers_guard before insert or update on public.borrowers
  for each row execute function public.lf_borrowers_guard();

create or replace function public.lf_org_child_guard()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    new.organization_id := coalesce(new.organization_id, public.current_org_id());
  elsif new.organization_id <> old.organization_id then
    raise exception 'organization_id cannot be changed';
  end if;
  return new;
end $$;

do $$
declare t text;
begin
  foreach t in array array['borrower_identities', 'borrower_documents', 'guarantors', 'loan_products',
                           'loan_agreement_templates', 'loan_notes'] loop
    execute format('create trigger %I before insert or update on public.%I for each row execute function public.lf_org_child_guard()',
      t || '_org_guard', t);
  end loop;
end $$;

create or replace function public.lf_borrower_doc_defaults()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  new.uploaded_by := coalesce(auth.uid(), new.uploaded_by);
  return new;
end $$;
create trigger borrower_documents_defaults before insert on public.borrower_documents
  for each row execute function public.lf_borrower_doc_defaults();

create or replace function public.lf_product_defaults()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    new.created_by := coalesce(auth.uid(), new.created_by);
  end if;
  new.code := upper(regexp_replace(trim(new.code), '\s+', '-', 'g'));
  return new;
end $$;
create trigger loan_products_defaults before insert or update on public.loan_products
  for each row execute function public.lf_product_defaults();

-- Duplicate detection helper used by the registration form (warns before insert)
create or replace function public.check_borrower_duplicates(
  p_first_name text, p_last_name text, p_date_of_birth date, p_mobile text,
  p_id_type text default null, p_id_number text default null, p_exclude uuid default null)
returns table (borrower_id uuid, borrower_code text, full_name text, mobile_number text, match_reason text)
language sql stable security definer set search_path = public as $$
  with me as (select public.current_org_id() as org, public.lf_normalize_ph_mobile(p_mobile) as mobile)
  select b.id, b.borrower_code, b.full_name, b.mobile_number,
    case
      when exists (select 1 from public.borrower_identities bi where bi.borrower_id = b.id
                   and p_id_number is not null and lower(bi.id_type) = lower(p_id_type)
                   and upper(regexp_replace(bi.id_number, '[^A-Za-z0-9]', '', 'g')) = upper(regexp_replace(p_id_number, '[^A-Za-z0-9]', '', 'g')))
        then 'Same ID document'
      when lower(trim(b.first_name)) = lower(trim(p_first_name)) and lower(trim(b.last_name)) = lower(trim(p_last_name))
           and b.date_of_birth = p_date_of_birth then 'Same name and date of birth'
      when b.mobile_number = (select mobile from me) then 'Same mobile number'
      else 'Similar name'
    end
  from public.borrowers b, me
  where b.organization_id = me.org
    and public.has_permission('borrowers.create')
    and (p_exclude is null or b.id <> p_exclude)
    and b.status <> 'archived'
    and (
      (lower(trim(b.first_name)) = lower(trim(p_first_name)) and lower(trim(b.last_name)) = lower(trim(p_last_name)))
      or b.mobile_number = me.mobile
      or exists (select 1 from public.borrower_identities bi where bi.borrower_id = b.id
                 and p_id_number is not null and lower(bi.id_type) = lower(p_id_type)
                 and upper(regexp_replace(bi.id_number, '[^A-Za-z0-9]', '', 'g')) = upper(regexp_replace(p_id_number, '[^A-Za-z0-9]', '', 'g')))
    )
  limit 10
$$;


-- ==== 20261114000004_loan_workflow.sql ====
-- =============================================================================
-- LendFlow — Settings helpers, ledger posting and loan lifecycle workflow
-- Status flow:
--   draft -> pending_review -> approved ---------------------\
--                 \-> (agreement generated) awaiting_*_signature -> fully_signed
--   fully_signed + approved -> ready_for_release -> (release) active -> completed
--   any pre-release state -> rejected / cancelled;  active -> defaulted
-- Approval, signing and disbursement are separate steps.
-- =============================================================================

create or replace function public.lf_default_settings()
returns jsonb language sql immutable as $$
  select '{
    "interest": {
      "flat_enabled": true, "diminishing_enabled": true, "default_method": "flat",
      "default_rate": 2, "default_rate_period": "monthly",
      "rate_presets": [1, 1.5, 2, 2.5, 3, 3.5, 4, 5],
      "rate_periods": ["monthly", "yearly"],
      "day_count": "30/360", "rounding": "centavo"
    },
    "repayment": {
      "frequencies": ["monthly", "weekly", "daily"], "default_frequency": "monthly",
      "first_due_rule": "one_period", "grace_days": 0,
      "early_settlement_rule": "prorated",
      "allocation_order": ["penalty", "charges", "interest", "principal"],
      "charges_treatment": "deducted",
      "require_separate_approver": false
    },
    "penalty": { "enabled": true, "type": "percent_monthly", "rate": 3, "grace_days": 3 },
    "agreement": {
      "default_template_id": null, "signing_order": "borrower_first",
      "require_lender_signature": true, "witness_count": 0,
      "identity_verification": "dob", "link_expiry_hours": 72
    },
    "security": { "session_timeout_minutes": 60, "password_min_length": 10, "audit_logging": true },
    "receipt": { "footer_note": "This is a system-generated acknowledgement receipt. Please keep it for your records." }
  }'::jsonb
$$;

create or replace function public.lf_merge_settings(p_default jsonb, p_stored jsonb)
returns jsonb language sql immutable as $$
  select coalesce(jsonb_object_agg(k,
           case when jsonb_typeof(p_default -> k) = 'object' and jsonb_typeof(p_stored -> k) = 'object'
                then (p_default -> k) || (p_stored -> k)
                else coalesce(p_stored -> k, p_default -> k) end), '{}'::jsonb)
  from (select jsonb_object_keys(p_default) as k
        union select jsonb_object_keys(coalesce(p_stored, '{}'::jsonb))) keys
$$;

create or replace function public.lf_settings(p_org uuid)
returns jsonb language sql stable security definer set search_path = public as $$
  select public.lf_merge_settings(public.lf_default_settings(),
    coalesce((select settings from public.system_settings where organization_id = p_org), '{}'::jsonb))
$$;

create or replace function public.get_settings()
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare v_org uuid := public.lf_require(null);
begin
  return public.lf_settings(v_org);
end $$;

-- ---------------------------------------------------------------------------
-- Ledger posting: the ONLY place outstanding balances change.
-- ---------------------------------------------------------------------------
create or replace function public.lf_post_ledger(
  p_loan uuid, p_type text, p_date date, p_amount numeric, p_cash numeric,
  p_dp numeric, p_di numeric, p_dc numeric, p_dpen numeric, p_desc text, p_payment uuid default null)
returns uuid language plpgsql security definer set search_path = public as $$
declare
  v public.loans%rowtype;
  v_id uuid;
begin
  update public.loans set
    outstanding_principal = outstanding_principal + p_dp,
    outstanding_interest = outstanding_interest + p_di,
    outstanding_charges = outstanding_charges + p_dc,
    outstanding_penalties = outstanding_penalties + p_dpen,
    row_version = row_version + 1
  where id = p_loan
  returning * into v;
  if not found then raise exception 'Loan % not found', p_loan; end if;
  if v.outstanding_principal < 0 or v.outstanding_interest < 0 or v.outstanding_charges < 0 or v.outstanding_penalties < 0 then
    raise exception 'Posting would produce a negative balance on loan %', v.loan_number;
  end if;
  insert into public.financial_ledger (
    organization_id, loan_id, payment_id, entry_type, entry_date, amount, cash_delta,
    principal_delta, interest_delta, charges_delta, penalty_delta,
    balance_principal, balance_interest, balance_charges, balance_penalty, balance_total,
    description, created_by)
  values (
    v.organization_id, p_loan, p_payment, p_type, p_date, p_amount, p_cash,
    p_dp, p_di, p_dc, p_dpen,
    v.outstanding_principal, v.outstanding_interest, v.outstanding_charges, v.outstanding_penalties,
    v.outstanding_principal + v.outstanding_interest + v.outstanding_charges + v.outstanding_penalties,
    p_desc, auth.uid())
  returning id into v_id;
  return v_id;
end $$;

create or replace function public.lf_system_note(p_org uuid, p_loan uuid, p_borrower uuid, p_body text)
returns void language sql security definer set search_path = public as $$
  insert into public.loan_notes (organization_id, loan_id, borrower_id, note_type, body, created_by)
  values (p_org, p_loan, p_borrower, 'system', p_body, auth.uid())
$$;

-- ---------------------------------------------------------------------------
-- Build normalized calculation parameters from request + product + settings
-- ---------------------------------------------------------------------------
create or replace function public.lf_build_loan_params(p_org uuid, p jsonb)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  s jsonb := public.lf_settings(p_org);
  pr public.loan_products%rowtype;
  v_has_product boolean := false;
  v_method text;
  v_principal numeric;
  v_term integer;
  v jsonb;
begin
  if nullif(p->>'product_id', '') is not null then
    select * into pr from public.loan_products
    where id = (p->>'product_id')::uuid and organization_id = p_org;
    if not found then raise exception 'Loan product not found' using errcode = '22023'; end if;
    if not pr.is_active then raise exception 'Loan product "%" is inactive', pr.name using errcode = '22023'; end if;
    v_has_product := true;
  end if;

  v_method := coalesce(nullif(p->>'interest_method', ''),
                       case when v_has_product then pr.interest_method::text end,
                       s #>> '{interest,default_method}');
  if v_method = 'flat' and not coalesce((s #>> '{interest,flat_enabled}')::boolean, true) then
    raise exception 'Flat interest is disabled in settings' using errcode = '22023';
  end if;
  if v_method = 'diminishing' and not coalesce((s #>> '{interest,diminishing_enabled}')::boolean, true) then
    raise exception 'Diminishing balance interest is disabled in settings' using errcode = '22023';
  end if;

  v := jsonb_build_object(
    'product_id', case when v_has_product then pr.id end,
    'principal', p->'principal',
    'interest_method', v_method,
    'interest_rate', coalesce(nullif(p->>'interest_rate', '')::numeric,
                              case when v_has_product then pr.interest_rate end,
                              (s #>> '{interest,default_rate}')::numeric),
    'rate_period', coalesce(nullif(p->>'rate_period', ''), case when v_has_product then pr.rate_period::text end,
                            s #>> '{interest,default_rate_period}'),
    'repayment_frequency', coalesce(nullif(p->>'repayment_frequency', ''), case when v_has_product then pr.repayment_frequency::text end,
                                    s #>> '{repayment,default_frequency}'),
    'term_count', coalesce(nullif(p->>'term_count', '')::integer, case when v_has_product then pr.default_term end),
    'release_date', coalesce(nullif(p->>'release_date', '')::date, public.lf_today(p_org)),
    'first_due_date', nullif(p->>'first_due_date', ''),
    'day_count', coalesce(nullif(p->>'day_count', ''), case when v_has_product then pr.day_count end, s #>> '{interest,day_count}'),
    'rounding', coalesce(nullif(p->>'rounding', ''), s #>> '{interest,rounding}'),
    'processing_fee_type', coalesce(nullif(p->>'processing_fee_type', ''), case when v_has_product then pr.processing_fee_type end, 'none'),
    'processing_fee_value', coalesce(nullif(p->>'processing_fee_value', '')::numeric, case when v_has_product then pr.processing_fee_value end, 0),
    'other_charges', coalesce(nullif(p->>'other_charges', '')::numeric, 0),
    'other_charges_description', nullif(p->>'other_charges_description', ''),
    'charges_treatment', coalesce(nullif(p->>'charges_treatment', ''), case when v_has_product then pr.charges_treatment end,
                                  s #>> '{repayment,charges_treatment}'),
    'penalty_enabled', coalesce((p->>'penalty_enabled')::boolean,
                                case when v_has_product then pr.penalty_enabled end,
                                (s #>> '{penalty,enabled}')::boolean, false),
    'penalty_type', coalesce(nullif(p->>'penalty_type', ''), case when v_has_product and pr.penalty_enabled then pr.penalty_type end,
                             s #>> '{penalty,type}'),
    'penalty_rate', coalesce(nullif(p->>'penalty_rate', '')::numeric, case when v_has_product and pr.penalty_enabled then pr.penalty_rate end,
                             (s #>> '{penalty,rate}')::numeric, 0),
    'penalty_grace_days', coalesce(nullif(p->>'penalty_grace_days', '')::integer, case when v_has_product and pr.penalty_enabled then pr.penalty_grace_days end,
                                   (s #>> '{penalty,grace_days}')::integer, 0),
    'allocation_order', coalesce(p->'allocation_order', s #> '{repayment,allocation_order}'),
    'signing_order', coalesce(nullif(p->>'signing_order', ''), s #>> '{agreement,signing_order}'),
    'witness_count', coalesce(nullif(p->>'witness_count', '')::integer, (s #>> '{agreement,witness_count}')::integer, 0),
    'agreement_template_id', coalesce(nullif(p->>'agreement_template_id', '')::uuid,
                                      case when v_has_product then pr.agreement_template_id end,
                                      nullif(s #>> '{agreement,default_template_id}', '')::uuid)
  );

  v_principal := nullif(v->>'principal', '')::numeric;
  v_term := nullif(v->>'term_count', '')::integer;
  if v_has_product then
    if pr.min_amount is not null and v_principal < pr.min_amount then
      raise exception 'Loan amount is below the product minimum of PHP %', to_char(pr.min_amount, 'FM999,999,999,990.00') using errcode = '22023';
    end if;
    if pr.max_amount is not null and v_principal > pr.max_amount then
      raise exception 'Loan amount exceeds the product maximum of PHP %', to_char(pr.max_amount, 'FM999,999,999,990.00') using errcode = '22023';
    end if;
    if pr.min_term is not null and v_term < pr.min_term then
      raise exception 'Duration is below the product minimum of % installments', pr.min_term using errcode = '22023';
    end if;
    if pr.max_term is not null and v_term > pr.max_term then
      raise exception 'Duration exceeds the product maximum of % installments', pr.max_term using errcode = '22023';
    end if;
  end if;
  if (v->>'penalty_enabled')::boolean and coalesce(v->>'penalty_type', '') not in ('fixed', 'percent_once', 'percent_daily', 'percent_monthly') then
    raise exception 'A valid penalty type is required when penalties are enabled' using errcode = '22023';
  end if;
  if jsonb_typeof(v->'allocation_order') <> 'array' or jsonb_array_length(v->'allocation_order') <> 4
     or not (v->'allocation_order' ?& array['penalty', 'charges', 'interest', 'principal']) then
    raise exception 'Payment allocation order must list penalty, charges, interest and principal exactly once' using errcode = '22023';
  end if;
  if v->>'signing_order' not in ('borrower_first', 'lender_first', 'any') then
    raise exception 'Invalid signing order' using errcode = '22023';
  end if;
  return v;
end $$;

-- Calculation parameters of an existing loan (for snapshots / recalculation)
create or replace function public.lf_loan_params(l public.loans)
returns jsonb language sql stable as $$
  select jsonb_build_object(
    'product_id', l.product_id, 'principal', l.principal, 'interest_method', l.interest_method,
    'interest_rate', l.interest_rate, 'rate_period', l.rate_period, 'repayment_frequency', l.repayment_frequency,
    'term_count', l.term_count, 'release_date', l.release_date, 'first_due_date', l.first_due_date,
    'day_count', l.day_count, 'rounding', l.rounding,
    'processing_fee_type', l.processing_fee_type, 'processing_fee_value', l.processing_fee_value,
    'other_charges', l.other_charges, 'other_charges_description', l.other_charges_description,
    'charges_treatment', l.charges_treatment,
    'penalty_enabled', l.penalty_enabled, 'penalty_type', l.penalty_type, 'penalty_rate', l.penalty_rate,
    'penalty_grace_days', l.penalty_grace_days, 'allocation_order', to_jsonb(l.allocation_order),
    'signing_order', l.signing_order, 'witness_count', l.witness_count,
    'agreement_template_id', l.agreement_template_id)
$$;

-- ---------------------------------------------------------------------------
-- Preview (no records are created)
-- ---------------------------------------------------------------------------
create or replace function public.preview_loan(p jsonb)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require(null);
  v_params jsonb;
begin
  if not (public.has_permission('loans.create') or public.has_permission('loans.view')) then
    raise exception 'You do not have permission to preview loans' using errcode = '42501';
  end if;
  v_params := public.lf_build_loan_params(v_org, p);
  return public.compute_loan_schedule(v_params) || jsonb_build_object('params', v_params);
end $$;

-- ---------------------------------------------------------------------------
-- Create / update / submit
-- ---------------------------------------------------------------------------
create or replace function public.create_loan(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('loans.create');
  v_uid uuid := auth.uid();
  b public.borrowers%rowtype;
  v_params jsonb;
  v_calc jsonb;
  s jsonb;
  v_id uuid;
  v_number text;
  v_status public.loan_status;
begin
  select * into b from public.borrowers where id = nullif(p->>'borrower_id', '')::uuid and organization_id = v_org;
  if not found or not public.can_access_borrower(b.id) then
    raise exception 'Borrower not found' using errcode = '22023';
  end if;
  if b.status <> 'active' then
    raise exception 'Borrower is %; loans can only be created for active borrowers', b.status using errcode = '22023';
  end if;

  v_params := public.lf_build_loan_params(v_org, p);
  v_calc := public.compute_loan_schedule(v_params);
  s := v_calc->'summary';
  v_number := public.lf_next_ref(v_org, 'loan', 'LN');

  insert into public.loans (
    organization_id, loan_number, borrower_id, product_id, status,
    principal, interest_method, interest_rate, rate_period, day_count, repayment_frequency, term_count,
    release_date, first_due_date, maturity_date,
    processing_fee_type, processing_fee_value, other_charges, other_charges_description, charges_treatment, rounding,
    penalty_enabled, penalty_type, penalty_rate, penalty_grace_days, allocation_order,
    installment_count, periodic_rate, processing_fee, total_charges, financed_charges, net_proceeds,
    total_interest, total_repayment, installment_amount, effective_annual_rate, eir_monthly, calc_summary,
    purpose, notes, agreement_template_id, signing_order, witness_count,
    loan_officer_id, collector_id, created_by, is_demo)
  values (
    v_org, v_number, b.id, nullif(v_params->>'product_id', '')::uuid, 'draft',
    (s->>'principal')::numeric, (s->>'interest_method')::public.interest_method, (s->>'interest_rate')::numeric,
    (s->>'rate_period')::public.rate_period, s->>'day_count', (s->>'repayment_frequency')::public.repayment_frequency,
    (s->>'term_count')::integer, (s->>'release_date')::date, (s->>'first_due_date')::date, (s->>'last_due_date')::date,
    s->>'processing_fee_type', (s->>'processing_fee_value')::numeric, (s->>'other_charges')::numeric,
    v_params->>'other_charges_description', s->>'charges_treatment', s->>'rounding',
    (v_params->>'penalty_enabled')::boolean, v_params->>'penalty_type', (v_params->>'penalty_rate')::numeric,
    (v_params->>'penalty_grace_days')::integer, array(select jsonb_array_elements_text(v_params->'allocation_order')),
    (s->>'installment_count')::integer, (s->>'periodic_rate')::numeric, (s->>'processing_fee')::numeric,
    (s->>'total_charges')::numeric, (s->>'financed_charges')::numeric, (s->>'net_proceeds')::numeric,
    (s->>'total_interest')::numeric, (s->>'total_repayment')::numeric, (s->>'installment_amount')::numeric,
    nullif(s->>'effective_annual_rate_pct', '')::numeric, nullif(s->>'eir_monthly_pct', '')::numeric, s,
    nullif(p->>'purpose', ''), nullif(p->>'notes', ''), nullif(v_params->>'agreement_template_id', '')::uuid,
    v_params->>'signing_order', (v_params->>'witness_count')::integer,
    coalesce(nullif(p->>'loan_officer_id', '')::uuid, v_uid), nullif(p->>'collector_id', '')::uuid, v_uid,
    coalesce((p->>'is_demo')::boolean, false))
  returning id into v_id;

  insert into public.loan_applications (organization_id, loan_id, application_number, purpose, source,
                                        requested_amount, requested_term)
  values (v_org, v_id, public.lf_next_ref(v_org, 'application', 'APP'), nullif(p->>'purpose', ''),
          coalesce(nullif(p->>'source', ''), 'walk_in'), (s->>'principal')::numeric, (s->>'term_count')::integer);

  perform public.lf_system_note(v_org, v_id, b.id, format('Loan %s created (draft) for PHP %s.', v_number,
    to_char((s->>'principal')::numeric, 'FM999,999,999,990.00')));

  if coalesce((p->>'submit')::boolean, false) then
    perform public.submit_loan(v_id);
  end if;

  select status into v_status from public.loans where id = v_id;
  return jsonb_build_object('id', v_id, 'loan_number', v_number, 'status', v_status);
end $$;

create or replace function public.lf_void_open_agreements(p_loan uuid, p_reason text)
returns integer language plpgsql security definer set search_path = public as $$
declare v_count integer;
begin
  update public.agreement_signing_sessions set status = 'revoked'
  where agreement_id in (select id from public.loan_agreements where loan_id = p_loan
                         and status in ('generating', 'pending_signatures', 'partially_signed'))
    and status in ('pending', 'opened', 'verified');
  with v as (
    update public.loan_agreements
    set status = 'voided', voided_at = now(), voided_by = auth.uid(), void_reason = p_reason
    where loan_id = p_loan and status in ('generating', 'failed', 'pending_signatures', 'partially_signed')
    returning id, organization_id
  ), ev as (
    insert into public.agreement_audit_events (organization_id, agreement_id, event_type, actor_type, actor_user_id, metadata)
    select organization_id, id, 'voided', 'user', auth.uid(), jsonb_build_object('reason', p_reason) from v
    returning 1
  )
  select count(*) into v_count from ev;
  return v_count;
end $$;

create or replace function public.update_loan(p_loan_id uuid, p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('loans.create');
  l public.loans%rowtype;
  v_params jsonb;
  v_calc jsonb;
  s jsonb;
begin
  select * into l from public.loans where id = p_loan_id and organization_id = v_org for update;
  if not found or not public.can_access_loan(l.id) then raise exception 'Loan not found'; end if;
  if l.status not in ('draft', 'pending_review', 'approved', 'awaiting_borrower_signature', 'awaiting_lender_signature') then
    raise exception 'Loan terms cannot be edited in status "%"', l.status using errcode = '22023';
  end if;
  if exists (select 1 from public.agreement_signatures sg join public.loan_agreements a on a.id = sg.agreement_id
             where a.loan_id = l.id and a.status in ('pending_signatures', 'partially_signed')) then
    raise exception 'The current agreement already has a signature. Void the agreement first; signed documents are never overwritten.'
      using errcode = '22023';
  end if;

  perform public.lf_void_open_agreements(l.id, 'Loan terms were edited before signing');

  v_params := public.lf_build_loan_params(v_org, public.lf_loan_params(l) || (p - 'borrower_id'));
  v_calc := public.compute_loan_schedule(v_params);
  s := v_calc->'summary';

  update public.loans set
    product_id = nullif(v_params->>'product_id', '')::uuid,
    status = 'draft',
    principal = (s->>'principal')::numeric,
    interest_method = (s->>'interest_method')::public.interest_method,
    interest_rate = (s->>'interest_rate')::numeric,
    rate_period = (s->>'rate_period')::public.rate_period,
    day_count = s->>'day_count',
    repayment_frequency = (s->>'repayment_frequency')::public.repayment_frequency,
    term_count = (s->>'term_count')::integer,
    release_date = (s->>'release_date')::date,
    first_due_date = (s->>'first_due_date')::date,
    maturity_date = (s->>'last_due_date')::date,
    processing_fee_type = s->>'processing_fee_type',
    processing_fee_value = (s->>'processing_fee_value')::numeric,
    other_charges = (s->>'other_charges')::numeric,
    other_charges_description = v_params->>'other_charges_description',
    charges_treatment = s->>'charges_treatment',
    rounding = s->>'rounding',
    penalty_enabled = (v_params->>'penalty_enabled')::boolean,
    penalty_type = v_params->>'penalty_type',
    penalty_rate = (v_params->>'penalty_rate')::numeric,
    penalty_grace_days = (v_params->>'penalty_grace_days')::integer,
    allocation_order = array(select jsonb_array_elements_text(v_params->'allocation_order')),
    installment_count = (s->>'installment_count')::integer,
    periodic_rate = (s->>'periodic_rate')::numeric,
    processing_fee = (s->>'processing_fee')::numeric,
    total_charges = (s->>'total_charges')::numeric,
    financed_charges = (s->>'financed_charges')::numeric,
    net_proceeds = (s->>'net_proceeds')::numeric,
    total_interest = (s->>'total_interest')::numeric,
    total_repayment = (s->>'total_repayment')::numeric,
    installment_amount = (s->>'installment_amount')::numeric,
    effective_annual_rate = nullif(s->>'effective_annual_rate_pct', '')::numeric,
    eir_monthly = nullif(s->>'eir_monthly_pct', '')::numeric,
    calc_summary = s,
    purpose = coalesce(nullif(p->>'purpose', ''), purpose),
    notes = case when p ? 'notes' then nullif(p->>'notes', '') else notes end,
    agreement_template_id = nullif(v_params->>'agreement_template_id', '')::uuid,
    signing_order = v_params->>'signing_order',
    witness_count = (v_params->>'witness_count')::integer,
    collector_id = case when p ? 'collector_id' then nullif(p->>'collector_id', '')::uuid else collector_id end,
    submitted_at = null, submitted_by = null, approved_at = null, approved_by = null, approval_notes = null,
    row_version = row_version + 1
  where id = l.id;

  update public.loan_applications set
    requested_amount = (s->>'principal')::numeric, requested_term = (s->>'term_count')::integer,
    purpose = coalesce(nullif(p->>'purpose', ''), purpose), decision = 'pending', decided_at = null, decided_by = null
  where loan_id = l.id;

  perform public.lf_system_note(v_org, l.id, l.borrower_id, 'Loan terms edited; loan returned to draft.');
  if coalesce((p->>'submit')::boolean, false) then
    perform public.submit_loan(l.id);
  end if;
  return jsonb_build_object('id', l.id, 'loan_number', l.loan_number,
                            'status', (select status from public.loans where id = l.id));
end $$;

create or replace function public.submit_loan(p_loan_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('loans.create');
  l public.loans%rowtype;
begin
  select * into l from public.loans where id = p_loan_id and organization_id = v_org for update;
  if not found or not public.can_access_loan(l.id) then raise exception 'Loan not found'; end if;
  if l.status <> 'draft' then
    raise exception 'Only draft loans can be submitted for review (current status: %)', l.status using errcode = '22023';
  end if;
  update public.loans set status = 'pending_review', submitted_at = now(), submitted_by = auth.uid() where id = l.id;
  update public.loan_applications set submitted_at = now(), submitted_by = auth.uid(), decision = 'pending' where loan_id = l.id;
  perform public.lf_system_note(v_org, l.id, l.borrower_id, 'Submitted for review.');
  perform public.lf_notify_roles(v_org, array['owner', 'admin']::public.app_role[], 'loan_submitted',
    'Loan application awaiting review', format('%s — PHP %s', l.loan_number, to_char(l.principal, 'FM999,999,999,990.00')),
    '/loans/' || l.id);
end $$;

create or replace function public.approve_loan(p_loan_id uuid, p_notes text default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('loans.approve');
  l public.loans%rowtype;
  v_new public.loan_status;
begin
  select * into l from public.loans where id = p_loan_id and organization_id = v_org for update;
  if not found or not public.can_access_loan(l.id) then raise exception 'Loan not found'; end if;
  if l.approved_at is not null then raise exception 'Loan is already approved' using errcode = '22023'; end if;
  if l.status not in ('pending_review', 'awaiting_borrower_signature', 'awaiting_lender_signature', 'fully_signed') then
    raise exception 'Loan cannot be approved in status "%"', l.status using errcode = '22023';
  end if;
  if coalesce((public.lf_settings(v_org) #>> '{repayment,require_separate_approver}')::boolean, false)
     and l.created_by = auth.uid() and public.current_app_role() <> 'owner' then
    raise exception 'Segregation of duties: the loan creator cannot approve their own loan' using errcode = '42501';
  end if;
  v_new := case l.status when 'pending_review' then 'approved'::public.loan_status
                         when 'fully_signed' then 'ready_for_release'::public.loan_status
                         else l.status end;
  update public.loans set approved_at = now(), approved_by = auth.uid(), approval_notes = p_notes, status = v_new
  where id = l.id;
  update public.loan_applications set decision = 'approved', decided_at = now(), decided_by = auth.uid(), decision_notes = p_notes
  where loan_id = l.id;
  perform public.lf_system_note(v_org, l.id, l.borrower_id, 'Loan approved.' || coalesce(' Notes: ' || p_notes, ''));
  perform public.lf_notify_user(v_org, coalesce(l.loan_officer_id, l.created_by), 'loan_approved', 'Loan approved',
    l.loan_number || ' was approved.', '/loans/' || l.id);
  return jsonb_build_object('status', v_new);
end $$;

create or replace function public.reject_loan(p_loan_id uuid, p_reason text)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('loans.approve');
  l public.loans%rowtype;
begin
  if coalesce(trim(p_reason), '') = '' then raise exception 'A rejection reason is required' using errcode = '22023'; end if;
  select * into l from public.loans where id = p_loan_id and organization_id = v_org for update;
  if not found or not public.can_access_loan(l.id) then raise exception 'Loan not found'; end if;
  if l.status not in ('pending_review', 'approved', 'awaiting_borrower_signature', 'awaiting_lender_signature', 'fully_signed', 'ready_for_release') then
    raise exception 'Loan cannot be rejected in status "%"', l.status using errcode = '22023';
  end if;
  perform public.lf_void_open_agreements(l.id, 'Loan rejected: ' || p_reason);
  update public.loans set status = 'rejected', rejected_at = now(), rejected_by = auth.uid(), rejection_reason = p_reason where id = l.id;
  update public.loan_applications set decision = 'rejected', decided_at = now(), decided_by = auth.uid(), decision_notes = p_reason
  where loan_id = l.id;
  perform public.lf_system_note(v_org, l.id, l.borrower_id, 'Loan rejected: ' || p_reason);
  perform public.lf_notify_user(v_org, coalesce(l.loan_officer_id, l.created_by), 'loan_rejected', 'Loan rejected',
    l.loan_number || ': ' || p_reason, '/loans/' || l.id);
end $$;

create or replace function public.cancel_loan(p_loan_id uuid, p_reason text)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('loans.cancel');
  l public.loans%rowtype;
begin
  if coalesce(trim(p_reason), '') = '' then raise exception 'A cancellation reason is required' using errcode = '22023'; end if;
  select * into l from public.loans where id = p_loan_id and organization_id = v_org for update;
  if not found or not public.can_access_loan(l.id) then raise exception 'Loan not found'; end if;
  if l.status not in ('draft', 'pending_review', 'approved', 'awaiting_borrower_signature', 'awaiting_lender_signature', 'fully_signed', 'ready_for_release') then
    raise exception 'Loan cannot be cancelled in status "%"', l.status using errcode = '22023';
  end if;
  perform public.lf_void_open_agreements(l.id, 'Loan cancelled: ' || p_reason);
  update public.loans set status = 'cancelled', cancelled_at = now(), cancelled_by = auth.uid(), cancellation_reason = p_reason where id = l.id;
  update public.loan_applications set decision = 'withdrawn', decided_at = now(), decided_by = auth.uid(), decision_notes = p_reason
  where loan_id = l.id and decision = 'pending';
  perform public.lf_system_note(v_org, l.id, l.borrower_id, 'Loan cancelled: ' || p_reason);
end $$;

-- ---------------------------------------------------------------------------
-- Release (disbursement) and activation
-- ---------------------------------------------------------------------------
create or replace function public.release_loan(p_loan_id uuid, p jsonb default '{}'::jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('loans.release');
  l public.loans%rowtype;
  a public.loan_agreements%rowtype;
  snap public.loan_terms_snapshots%rowtype;
  v_date date := coalesce(nullif(p->>'release_date', '')::date, public.lf_today(v_org));
  r jsonb;
  v_count integer := 0;
begin
  select * into l from public.loans where id = p_loan_id and organization_id = v_org for update;
  if not found or not public.can_access_loan(l.id) then raise exception 'Loan not found'; end if;
  if l.status <> 'ready_for_release' then
    raise exception 'Loan must be approved and fully signed before release (current status: %)', l.status using errcode = '22023';
  end if;
  if l.approved_at is null then raise exception 'Loan has not been approved' using errcode = '22023'; end if;
  select * into a from public.loan_agreements where loan_id = l.id and status = 'fully_signed'
  order by version desc limit 1;
  if not found then raise exception 'No fully signed agreement found for this loan' using errcode = '22023'; end if;
  if a.signed_pdf_path is null or a.signed_pdf_hash is null then
    raise exception 'The signed agreement PDF has not been finalized yet' using errcode = '22023';
  end if;
  select * into snap from public.loan_terms_snapshots where id = a.terms_snapshot_id;
  if v_date > public.lf_today(v_org) then raise exception 'Release date cannot be in the future' using errcode = '22023'; end if;
  if exists (select 1 from public.loan_installments where loan_id = l.id) then
    raise exception 'Installments already exist for this loan';
  end if;

  for r in select * from jsonb_array_elements(snap.schedule) loop
    insert into public.loan_installments (organization_id, loan_id, installment_no, due_date, opening_balance,
      principal_due, interest_due, charges_due, closing_balance, snapshot_version)
    values (v_org, l.id, (r->>'installment_no')::integer, (r->>'due_date')::date, (r->>'opening_balance')::numeric,
      (r->>'principal')::numeric, (r->>'interest')::numeric, (r->>'charges')::numeric, (r->>'closing_balance')::numeric,
      snap.version);
    v_count := v_count + 1;
  end loop;

  if l.processing_fee > 0 then
    insert into public.loan_charges (organization_id, loan_id, charge_type, description, amount, treatment, created_by)
    values (v_org, l.id, 'processing_fee', 'Processing fee', l.processing_fee, l.charges_treatment, auth.uid());
  end if;
  if l.other_charges > 0 then
    insert into public.loan_charges (organization_id, loan_id, charge_type, description, amount, treatment, created_by)
    values (v_org, l.id, 'other', coalesce(l.other_charges_description, 'Other charges'), l.other_charges, l.charges_treatment, auth.uid());
  end if;

  perform public.lf_post_ledger(l.id, 'disbursement', v_date, l.principal, -l.principal, l.principal, 0, 0, 0,
    'Loan proceeds released' || coalesce(' via ' || nullif(p->>'method', ''), ''));
  if l.total_charges - l.financed_charges > 0 then
    perform public.lf_post_ledger(l.id, 'fee_deducted', v_date, l.total_charges - l.financed_charges,
      l.total_charges - l.financed_charges, 0, 0, 0, 0, 'Fees and charges deducted from proceeds');
  end if;
  perform public.lf_post_ledger(l.id, 'loan_booked', v_date, l.total_interest + l.financed_charges, 0,
    0, l.total_interest, l.financed_charges, 0, 'Contractual interest and financed charges booked');

  update public.loans set
    status = 'active', released_at = now(), actual_release_date = v_date, released_by = auth.uid(),
    release_method = nullif(p->>'method', ''), release_reference = nullif(p->>'reference', ''),
    activated_at = now(), terms_snapshot_id = snap.id,
    loan_officer_id = coalesce(loan_officer_id, created_by)
  where id = l.id;

  if l.collector_id is not null then
    insert into public.collector_assignments (organization_id, collector_id, borrower_id, loan_id, assigned_by)
    select v_org, l.collector_id, l.borrower_id, l.id, auth.uid()
    where not exists (select 1 from public.collector_assignments where loan_id = l.id and collector_id = l.collector_id and is_active);
  end if;

  perform public.lf_system_note(v_org, l.id, l.borrower_id,
    format('Loan released on %s and activated with %s installments.%s', v_date, v_count,
      case when v_date <> l.release_date then format(' (Planned release date was %s; schedule follows the signed agreement.)', l.release_date) else '' end));
  perform public.lf_notify_roles(v_org, array['owner', 'admin']::public.app_role[], 'loan_released', 'Loan released',
    format('%s — PHP %s released', l.loan_number, to_char(l.net_proceeds, 'FM999,999,999,990.00')), '/loans/' || l.id);
  return jsonb_build_object('status', 'active', 'installments', v_count);
end $$;

create or replace function public.mark_loan_default(p_loan_id uuid, p_reason text)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('loans.manage');
  l public.loans%rowtype;
begin
  if coalesce(trim(p_reason), '') = '' then raise exception 'A reason is required' using errcode = '22023'; end if;
  select * into l from public.loans where id = p_loan_id and organization_id = v_org for update;
  if not found then raise exception 'Loan not found'; end if;
  if l.status <> 'active' then raise exception 'Only active loans can be marked as defaulted' using errcode = '22023'; end if;
  update public.loans set status = 'defaulted', defaulted_at = now(), defaulted_by = auth.uid(), default_reason = p_reason where id = l.id;
  perform public.lf_system_note(v_org, l.id, l.borrower_id, 'Loan marked as defaulted: ' || p_reason);
end $$;

create or replace function public.reinstate_loan(p_loan_id uuid, p_reason text)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('loans.manage');
  l public.loans%rowtype;
begin
  select * into l from public.loans where id = p_loan_id and organization_id = v_org for update;
  if not found then raise exception 'Loan not found'; end if;
  if l.status <> 'defaulted' then raise exception 'Only defaulted loans can be reinstated' using errcode = '22023'; end if;
  update public.loans set status = 'active', defaulted_at = null, default_reason = null where id = l.id;
  perform public.lf_system_note(v_org, l.id, l.borrower_id, 'Loan reinstated to active: ' || coalesce(p_reason, ''));
end $$;

create or replace function public.close_loan(p_loan_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('loans.manage');
  l public.loans%rowtype;
begin
  select * into l from public.loans where id = p_loan_id and organization_id = v_org for update;
  if not found then raise exception 'Loan not found'; end if;
  if l.status not in ('active', 'defaulted') then raise exception 'Loan is not open' using errcode = '22023'; end if;
  if l.total_outstanding > 0 then
    raise exception 'Loan still has an outstanding balance of PHP %; record the final payment or an early settlement first',
      to_char(l.total_outstanding, 'FM999,999,999,990.00') using errcode = '22023';
  end if;
  update public.loans set status = 'completed', completed_at = now() where id = l.id;
  perform public.lf_system_note(v_org, l.id, l.borrower_id, 'Loan closed.');
end $$;

create or replace function public.assign_loan_staff(p_loan_id uuid, p_loan_officer uuid, p_collector uuid)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('collections.assign');
  l public.loans%rowtype;
begin
  select * into l from public.loans where id = p_loan_id and organization_id = v_org for update;
  if not found then raise exception 'Loan not found'; end if;
  if p_loan_officer is not null and not exists (select 1 from public.user_profiles where id = p_loan_officer and organization_id = v_org and is_active) then
    raise exception 'Loan officer not found in this organization';
  end if;
  if p_collector is not null and not exists (select 1 from public.user_profiles where id = p_collector and organization_id = v_org and is_active) then
    raise exception 'Collector not found in this organization';
  end if;
  update public.loans set loan_officer_id = coalesce(p_loan_officer, loan_officer_id), collector_id = p_collector where id = l.id;
  if p_collector is distinct from l.collector_id then
    update public.collector_assignments set is_active = false, unassigned_at = now()
    where loan_id = l.id and is_active;
    if p_collector is not null then
      insert into public.collector_assignments (organization_id, collector_id, borrower_id, loan_id, assigned_by)
      values (v_org, p_collector, l.borrower_id, l.id, auth.uid());
      perform public.lf_notify_user(v_org, p_collector, 'collector_assigned', 'New collection assignment',
        l.loan_number || ' was assigned to you.', '/loans/' || l.id);
    end if;
  end if;
  perform public.lf_system_note(v_org, l.id, l.borrower_id, 'Staff assignment updated.');
end $$;


-- ==== 20261114000005_payments.sql ====
-- =============================================================================
-- LendFlow — Penalties, payment allocation, settlement, reversals, adjustments
--
-- Accounting rules
--  * Separate balances: principal, interest, charges, penalties (+ total).
--  * Payments are allocated installment by installment (oldest first, or the
--    selected installment first) using the loan's configured allocation order.
--  * Early settlement charges interest earned to date only; unearned future
--    interest is rebated (never charged automatically).
--  * Confirmed transactions are never deleted; corrections are reversals or
--    adjustments, each with its own ledger entry.
--  * Idempotency keys + loan row locks prevent duplicate postings.
-- =============================================================================

create or replace function public.lf_inst_remaining(i public.loan_installments)
returns numeric language sql immutable as $$
  select (i.principal_due - i.principal_paid)
       + (i.interest_due - i.interest_paid - i.interest_waived)
       + (i.charges_due - i.charges_paid - i.charges_waived)
       + (i.penalty_due - i.penalty_paid - i.penalty_waived)
$$;

create or replace function public.lf_inst_base_remaining(i public.loan_installments)
returns numeric language sql immutable as $$
  select (i.principal_due - i.principal_paid)
       + (i.interest_due - i.interest_paid - i.interest_waived)
       + (i.charges_due - i.charges_paid - i.charges_waived)
$$;

create or replace function public.lf_refresh_installment(p_id uuid, p_date date default null)
returns void language plpgsql security definer set search_path = public as $$
declare i public.loan_installments%rowtype;
begin
  select * into i from public.loan_installments where id = p_id;
  if i.status in ('rescheduled') then return; end if;
  update public.loan_installments set
    status = case
      when public.lf_inst_remaining(i) <= 0 then 'paid'
      when i.principal_paid + i.interest_paid + i.charges_paid + i.penalty_paid + i.interest_waived + i.charges_waived + i.penalty_waived > 0 then 'partially_paid'
      else 'pending' end,
    paid_at = case when public.lf_inst_remaining(i) <= 0 then coalesce(i.paid_at, p_date, public.lf_today(i.organization_id)) else null end
  where id = p_id;
end $$;

-- ---------------------------------------------------------------------------
-- Penalties
-- ---------------------------------------------------------------------------
-- Incremental penalty for one installment from its last accrual date to p_as_of.
-- Penalties start after due date + grace days, on the unpaid installment amount.
create or replace function public.lf_penalty_increment(i public.loan_installments, l public.loans, p_as_of date)
returns table (amount numeric, accrued_from date, accrued_to date, days integer, base numeric)
language plpgsql stable as $$
declare
  v_base numeric := public.lf_inst_base_remaining(i);
  v_start date := i.due_date + coalesce(l.penalty_grace_days, 0);
  v_from date;
  v_days integer;
begin
  if not l.penalty_enabled or l.penalty_type is null or coalesce(l.penalty_rate, 0) <= 0 then return; end if;
  if i.status not in ('pending', 'partially_paid') or v_base <= 0 or p_as_of <= v_start then return; end if;
  if l.penalty_type in ('fixed', 'percent_once') then
    if i.penalty_accrued_through is not null then return; end if;
    amount := case when l.penalty_type = 'fixed' then round(l.penalty_rate, 2) else round(v_base * l.penalty_rate / 100, 2) end;
    accrued_from := v_start + 1; accrued_to := p_as_of; days := p_as_of - v_start; base := v_base;
    return next;
  else
    v_from := greatest(v_start, coalesce(i.penalty_accrued_through, v_start));
    v_days := p_as_of - v_from;
    if v_days <= 0 then return; end if;
    amount := round(v_base * l.penalty_rate / 100 * v_days / case when l.penalty_type = 'percent_daily' then 1 else 30 end, 2);
    accrued_from := v_from + 1; accrued_to := p_as_of; days := v_days; base := v_base;
    return next;
  end if;
end $$;

-- Projected (not yet posted) penalty for an installment as of a date — read only
create or replace function public.lf_projected_penalty(p_installment uuid, p_as_of date)
returns numeric language sql stable security definer set search_path = public as $$
  select coalesce((select sum(x.amount) from public.loan_installments i
                   join public.loans l on l.id = i.loan_id,
                   lateral public.lf_penalty_increment(i, l, p_as_of) x
                   where i.id = p_installment), 0)
$$;

-- Client-facing wrapper: same figure, but scoped to the caller's organization and loan access
create or replace function public.get_projected_penalty(p_installment uuid, p_as_of date)
returns numeric language plpgsql stable security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('payments.view');
  v_loan uuid;
begin
  select loan_id into v_loan from public.loan_installments where id = p_installment and organization_id = v_org;
  if v_loan is null or not public.can_access_loan(v_loan) then
    raise exception 'Installment not found' using errcode = '22023';
  end if;
  return public.lf_projected_penalty(p_installment, p_as_of);
end $$;

-- Persist penalties for a loan through p_as_of (called when payments are posted)
create or replace function public.lf_assess_penalties(p_loan_id uuid, p_as_of date)
returns numeric language plpgsql security definer set search_path = public as $$
declare
  l public.loans%rowtype;
  i public.loan_installments%rowtype;
  x record;
  v_total numeric := 0;
begin
  select * into l from public.loans where id = p_loan_id;
  if not l.penalty_enabled then return 0; end if;
  for i in select * from public.loan_installments
           where loan_id = p_loan_id and status in ('pending', 'partially_paid')
             and due_date + l.penalty_grace_days < p_as_of
           order by installment_no for update loop
    for x in select * from public.lf_penalty_increment(i, l, p_as_of) loop
      if x.amount > 0 then
        insert into public.loan_penalties (organization_id, loan_id, installment_id, penalty_type, accrued_from, accrued_to,
                                           days, base_amount, rate, amount)
        values (l.organization_id, l.id, i.id, l.penalty_type, x.accrued_from, x.accrued_to, x.days, x.base, l.penalty_rate, x.amount);
        v_total := v_total + x.amount;
      end if;
      update public.loan_installments
      set penalty_due = penalty_due + x.amount, penalty_accrued_through = p_as_of
      where id = i.id;
    end loop;
  end loop;
  if v_total > 0 then
    perform public.lf_post_ledger(l.id, 'penalty_assessed', p_as_of, v_total, 0, 0, 0, 0, v_total,
      'Late payment penalties assessed through ' || to_char(p_as_of, 'Mon DD, YYYY'));
    update public.loans set penalties_assessed = penalties_assessed + v_total where id = l.id;
  end if;
  return v_total;
end $$;

-- ---------------------------------------------------------------------------
-- Early settlement quote (read only)
-- ---------------------------------------------------------------------------
create or replace function public.lf_settlement_quote(p_loan_id uuid, p_as_of date)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  l public.loans%rowtype;
  i public.loan_installments%rowtype;
  v_rule text;
  v_prev date;
  v_current_found boolean := false;
  v_earned numeric;
  v_int_rem numeric;
  v_int numeric;
  v_rebate numeric;
  v_pen numeric;
  v_lines jsonb := '[]'::jsonb;
  t_p numeric := 0; t_i numeric := 0; t_c numeric := 0; t_pen numeric := 0; t_reb numeric := 0;
begin
  select * into l from public.loans where id = p_loan_id;
  if not found then raise exception 'Loan not found'; end if;
  v_rule := coalesce(public.lf_settings(l.organization_id) #>> '{repayment,early_settlement_rule}', 'prorated');
  v_prev := coalesce(l.actual_release_date, l.release_date);

  for i in select * from public.loan_installments where loan_id = l.id order by installment_no loop
    if i.status in ('pending', 'partially_paid') then
      v_int_rem := i.interest_due - i.interest_paid - i.interest_waived;
      if i.due_date <= p_as_of then
        v_int := v_int_rem;
      elsif not v_current_found then
        v_current_found := true;
        v_earned := case v_rule
          when 'full_period' then i.interest_due
          when 'due_only' then 0
          else round(i.interest_due * greatest(least(p_as_of - v_prev, i.due_date - v_prev), 0)::numeric
                     / greatest(i.due_date - v_prev, 1), 2)
        end;
        v_int := greatest(least(v_earned - i.interest_paid - i.interest_waived, v_int_rem), 0);
      else
        v_int := 0;
      end if;
      v_rebate := v_int_rem - v_int;
      v_pen := (i.penalty_due - i.penalty_paid - i.penalty_waived) + public.lf_projected_penalty(i.id, p_as_of);
      v_lines := v_lines || jsonb_build_object(
        'installment_id', i.id, 'installment_no', i.installment_no, 'due_date', i.due_date,
        'principal', i.principal_due - i.principal_paid,
        'interest', v_int,
        'charges', i.charges_due - i.charges_paid - i.charges_waived,
        'penalty', v_pen,
        'rebate', v_rebate);
      t_p := t_p + (i.principal_due - i.principal_paid);
      t_i := t_i + v_int;
      t_c := t_c + (i.charges_due - i.charges_paid - i.charges_waived);
      t_pen := t_pen + v_pen;
      t_reb := t_reb + v_rebate;
    elsif i.due_date > p_as_of and not v_current_found and i.status = 'paid' then
      null; -- prepaid future installment; nothing due
    end if;
    v_prev := i.due_date;
  end loop;

  return jsonb_build_object(
    'loan_id', l.id, 'as_of', p_as_of, 'rule', v_rule,
    'principal', t_p, 'interest', t_i, 'charges', t_c, 'penalties', t_pen,
    'total', t_p + t_i + t_c + t_pen, 'interest_rebate', t_reb,
    'contract_remaining', l.total_outstanding,
    'lines', v_lines);
end $$;

create or replace function public.get_settlement_quote(p_loan_id uuid, p_as_of date default null)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('payments.view');
  l public.loans%rowtype;
begin
  select * into l from public.loans where id = p_loan_id and organization_id = v_org;
  if not found or not public.can_access_loan(l.id) then raise exception 'Loan not found'; end if;
  if l.status not in ('active', 'defaulted') then raise exception 'Loan is not active' using errcode = '22023'; end if;
  return public.lf_settlement_quote(l.id, coalesce(p_as_of, public.lf_today(v_org)));
end $$;

-- ---------------------------------------------------------------------------
-- Receipt snapshot
-- ---------------------------------------------------------------------------
create or replace function public.lf_receipt_data(p_payment_id uuid, p_receipt_number text)
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'receipt_number', p_receipt_number,
    'payment_number', pay.payment_number,
    'payment_date', pay.payment_date,
    'issued_at', now(),
    'payment_type', pay.payment_type,
    'amount', pay.amount,
    'method', pay.method,
    'reference_no', pay.reference_no,
    'principal_paid', pay.principal_applied,
    'interest_paid', pay.interest_applied,
    'charges_paid', pay.charges_applied,
    'penalty_paid', pay.penalty_applied,
    'interest_rebated', pay.interest_rebated,
    'installments', coalesce((select jsonb_agg(jsonb_build_object('installment_no', li.installment_no, 'due_date', li.due_date,
                                       'amount', pa.total_amount) order by li.installment_no)
                              from public.payment_allocations pa join public.loan_installments li on li.id = pa.installment_id
                              where pa.payment_id = pay.id and not pa.is_reversal), '[]'::jsonb),
    'remaining_balance', l.total_outstanding,
    'outstanding_principal', l.outstanding_principal,
    'loan_number', l.loan_number,
    'loan_status', l.status,
    'borrower_name', b.full_name,
    'borrower_code', b.borrower_code,
    'collector_name', coalesce(rc.full_name, rb.full_name),
    'recorded_by', rb.full_name,
    'company', jsonb_build_object('name', o.name, 'legal_name', o.legal_name, 'address',
                 concat_ws(', ', nullif(o.address, ''), nullif(o.city, ''), nullif(o.province, ''), nullif(o.zip_code, '')),
                 'phone', o.contact_phone, 'email', o.contact_email, 'tin', o.tin, 'registration_no', o.registration_no),
    'footer_note', public.lf_settings(o.id) #>> '{receipt,footer_note}')
  from public.loan_payments pay
  join public.loans l on l.id = pay.loan_id
  join public.borrowers b on b.id = pay.borrower_id
  join public.organizations o on o.id = pay.organization_id
  left join public.user_profiles rc on rc.id = pay.received_by
  left join public.user_profiles rb on rb.id = pay.recorded_by
  where pay.id = p_payment_id
$$;

create or replace function public.lf_payment_result(p_payment_id uuid)
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'payment_id', pay.id, 'payment_number', pay.payment_number, 'amount', pay.amount, 'status', pay.status,
    'principal_applied', pay.principal_applied, 'interest_applied', pay.interest_applied,
    'charges_applied', pay.charges_applied, 'penalty_applied', pay.penalty_applied,
    'interest_rebated', pay.interest_rebated,
    'receipt_id', r.id, 'receipt_number', r.receipt_number,
    'loan_status', l.status, 'total_outstanding', l.total_outstanding,
    'outstanding_principal', l.outstanding_principal, 'outstanding_interest', l.outstanding_interest,
    'outstanding_charges', l.outstanding_charges, 'outstanding_penalties', l.outstanding_penalties,
    'duplicate', false)
  from public.loan_payments pay
  join public.loans l on l.id = pay.loan_id
  left join public.payment_receipts r on r.payment_id = pay.id
  where pay.id = p_payment_id
$$;

-- ---------------------------------------------------------------------------
-- Record payment
-- ---------------------------------------------------------------------------
create or replace function public.record_payment(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('payments.record');
  v_key uuid;
  v_existing uuid;
  l public.loans%rowtype;
  i public.loan_installments%rowtype;
  v_amount numeric;
  v_date date;
  v_type text := coalesce(nullif(p->>'payment_type', ''), 'regular');
  v_method text := coalesce(nullif(p->>'method', ''), 'cash');
  v_target uuid := nullif(p->>'installment_id', '')::uuid;
  v_ref text := nullif(trim(p->>'reference_no'), '');
  v_received_by uuid := coalesce(nullif(p->>'received_by', '')::uuid, auth.uid());
  v_left numeric;
  v_pay_id uuid;
  v_number text;
  v_comp text;
  v_take numeric;
  a_p numeric; a_i numeric; a_c numeric; a_pen numeric; a_reb numeric;
  t_p numeric := 0; t_i numeric := 0; t_c numeric := 0; t_pen numeric := 0; t_reb numeric := 0;
  v_quote jsonb;
  v_line jsonb;
  v_receipt_no text;
begin
  begin
    v_key := (p->>'idempotency_key')::uuid;
    v_amount := round((p->>'amount')::numeric, 2);
    v_date := coalesce(nullif(p->>'payment_date', '')::date, public.lf_today(v_org));
  exception when others then
    raise exception 'Invalid payment input' using errcode = '22023';
  end;
  if v_key is null then raise exception 'An idempotency key is required' using errcode = '22023'; end if;

  select id into v_existing from public.loan_payments where organization_id = v_org and idempotency_key = v_key;
  if found then return public.lf_payment_result(v_existing) || jsonb_build_object('duplicate', true); end if;

  select * into l from public.loans where id = nullif(p->>'loan_id', '')::uuid and organization_id = v_org for update;
  if not found or not public.can_access_loan(l.id) then raise exception 'Loan not found' using errcode = '22023'; end if;

  -- re-check after the lock: a concurrent duplicate submission may have just committed
  select id into v_existing from public.loan_payments where organization_id = v_org and idempotency_key = v_key;
  if found then return public.lf_payment_result(v_existing) || jsonb_build_object('duplicate', true); end if;

  if l.status not in ('active', 'defaulted') then
    raise exception 'Payments can only be recorded on active loans (status: %)', l.status using errcode = '22023';
  end if;
  if v_amount is null or v_amount <= 0 then raise exception 'Payment amount must be greater than zero' using errcode = '22023'; end if;
  if v_type not in ('regular', 'settlement') then raise exception 'Invalid payment type' using errcode = '22023'; end if;
  if v_method not in ('cash', 'bank_transfer', 'e_wallet', 'check', 'other') then raise exception 'Invalid payment method' using errcode = '22023'; end if;
  if v_date > public.lf_today(v_org) then raise exception 'Payment date cannot be in the future' using errcode = '22023'; end if;
  if v_date < coalesce(l.actual_release_date, l.release_date) then
    raise exception 'Payment date cannot be before the loan release date' using errcode = '22023';
  end if;
  if l.last_payment_date is not null and v_date < l.last_payment_date then
    raise exception 'Payments must be recorded in date order; the latest payment on this loan is dated %',
      to_char(l.last_payment_date, 'Mon DD, YYYY') using errcode = '22023';
  end if;
  if v_ref is not null and v_method <> 'cash' and exists (
      select 1 from public.loan_payments where organization_id = v_org and method = v_method
      and lower(reference_no) = lower(v_ref) and status = 'posted') then
    raise exception 'A posted payment with reference "%" already exists. This looks like a duplicate submission.', v_ref
      using errcode = '23505';
  end if;
  if v_target is not null and not exists (select 1 from public.loan_installments where id = v_target and loan_id = l.id
                                          and status in ('pending', 'partially_paid')) then
    raise exception 'Selected installment is not open' using errcode = '22023';
  end if;

  -- bring penalties up to the payment date
  perform public.lf_assess_penalties(l.id, v_date);
  select * into l from public.loans where id = l.id;

  if v_type = 'settlement' then
    v_quote := public.lf_settlement_quote(l.id, v_date);
    if v_amount <> (v_quote->>'total')::numeric then
      raise exception 'Early settlement amount must be exactly PHP % as of %',
        to_char((v_quote->>'total')::numeric, 'FM999,999,999,990.00'), to_char(v_date, 'Mon DD, YYYY') using errcode = '22023';
    end if;
  elsif v_amount > l.total_outstanding then
    raise exception 'Amount exceeds the total outstanding balance of PHP %',
      to_char(l.total_outstanding, 'FM999,999,999,990.00') using errcode = '22023';
  end if;

  v_number := public.lf_next_ref(v_org, 'payment', 'PAY');
  insert into public.loan_payments (organization_id, loan_id, borrower_id, payment_number, amount, payment_date,
    method, method_detail, reference_no, proof_path, notes, payment_type, target_installment_id,
    idempotency_key, received_by, recorded_by)
  values (v_org, l.id, l.borrower_id, v_number, v_amount, v_date,
    v_method, nullif(p->>'method_detail', ''), v_ref, nullif(p->>'proof_path', ''), nullif(p->>'notes', ''), v_type, v_target,
    v_key, v_received_by, auth.uid())
  returning id into v_pay_id;

  v_left := v_amount;
  for i in select * from public.loan_installments
           where loan_id = l.id and status in ('pending', 'partially_paid')
           order by (id = v_target) desc nulls last, installment_no
           for update loop
    a_p := 0; a_i := 0; a_c := 0; a_pen := 0; a_reb := 0;
    if v_type = 'settlement' then
      select x into v_line from jsonb_array_elements(v_quote->'lines') x where x->>'installment_id' = i.id::text;
      if v_line is null then continue; end if;
      a_p := (v_line->>'principal')::numeric;
      a_i := (v_line->>'interest')::numeric;
      a_c := (v_line->>'charges')::numeric;
      a_pen := (v_line->>'penalty')::numeric;
      a_reb := (v_line->>'rebate')::numeric;
      v_left := v_left - (a_p + a_i + a_c + a_pen);
    else
      exit when v_left <= 0;
      foreach v_comp in array l.allocation_order loop
        exit when v_left <= 0;
        if v_comp = 'penalty' then
          v_take := least(v_left, i.penalty_due - i.penalty_paid - i.penalty_waived); a_pen := v_take;
        elsif v_comp = 'charges' then
          v_take := least(v_left, i.charges_due - i.charges_paid - i.charges_waived); a_c := v_take;
        elsif v_comp = 'interest' then
          v_take := least(v_left, i.interest_due - i.interest_paid - i.interest_waived); a_i := v_take;
        else
          v_take := least(v_left, i.principal_due - i.principal_paid); a_p := v_take;
        end if;
        v_left := v_left - v_take;
      end loop;
    end if;

    if a_p + a_i + a_c + a_pen + a_reb > 0 then
      update public.loan_installments set
        principal_paid = principal_paid + a_p, interest_paid = interest_paid + a_i,
        charges_paid = charges_paid + a_c, penalty_paid = penalty_paid + a_pen,
        interest_waived = interest_waived + a_reb, last_payment_date = v_date
      where id = i.id;
      perform public.lf_refresh_installment(i.id, v_date);
      insert into public.payment_allocations (organization_id, payment_id, loan_id, installment_id,
        principal_amount, interest_amount, charges_amount, penalty_amount, interest_rebate)
      values (v_org, v_pay_id, l.id, i.id, a_p, a_i, a_c, a_pen, a_reb);
      t_p := t_p + a_p; t_i := t_i + a_i; t_c := t_c + a_c; t_pen := t_pen + a_pen; t_reb := t_reb + a_reb;
    end if;
  end loop;

  if v_left <> 0 then
    raise exception 'Payment could not be fully allocated (remaining PHP %)', v_left;
  end if;

  update public.loans set
    principal_paid = principal_paid + t_p, interest_paid = interest_paid + t_i,
    charges_paid = charges_paid + t_c, penalties_paid = penalties_paid + t_pen,
    total_paid = total_paid + v_amount, interest_waived = interest_waived + t_reb,
    last_payment_date = v_date
  where id = l.id;

  perform public.lf_post_ledger(l.id, 'payment', v_date, v_amount, v_amount, -t_p, -t_i, -t_c, -t_pen,
    'Payment ' || v_number || case when v_type = 'settlement' then ' (early settlement)' else '' end, v_pay_id);
  if t_reb > 0 then
    perform public.lf_post_ledger(l.id, 'settlement_rebate', v_date, t_reb, 0, 0, -t_reb, 0, 0,
      'Unearned interest rebated on early settlement', v_pay_id);
  end if;

  select * into l from public.loans where id = l.id;
  if l.total_outstanding = 0 then
    update public.loans set status = 'completed', completed_at = now() where id = l.id;
    perform public.lf_system_note(v_org, l.id, l.borrower_id,
      case when v_type = 'settlement' then 'Loan fully paid by early settlement.' else 'Loan fully paid.' end);
  end if;

  update public.loan_payments set
    principal_applied = t_p, interest_applied = t_i, charges_applied = t_c, penalty_applied = t_pen,
    interest_rebated = t_reb, balance_after = l.total_outstanding
  where id = v_pay_id;

  v_receipt_no := public.lf_next_ref(v_org, 'receipt', 'OR');
  insert into public.payment_receipts (organization_id, payment_id, receipt_number, issued_by, data)
  values (v_org, v_pay_id, v_receipt_no, auth.uid(), public.lf_receipt_data(v_pay_id, v_receipt_no));

  return public.lf_payment_result(v_pay_id);
end $$;

-- ---------------------------------------------------------------------------
-- Reverse payment (LIFO per loan to keep allocations consistent)
-- ---------------------------------------------------------------------------
create or replace function public.reverse_payment(p_payment_id uuid, p_reason text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('payments.reverse');
  pay public.loan_payments%rowtype;
  l public.loans%rowtype;
  a public.payment_allocations%rowtype;
  v_today date;
begin
  if coalesce(trim(p_reason), '') = '' then raise exception 'A reversal reason is required' using errcode = '22023'; end if;
  select * into pay from public.loan_payments where id = p_payment_id and organization_id = v_org;
  if not found then raise exception 'Payment not found'; end if;
  select * into l from public.loans where id = pay.loan_id for update;
  select * into pay from public.loan_payments where id = p_payment_id for update;
  if pay.status <> 'posted' then raise exception 'Payment is already reversed' using errcode = '22023'; end if;
  if exists (select 1 from public.loan_payments x where x.loan_id = pay.loan_id and x.status = 'posted' and x.id <> pay.id
             and (x.payment_date > pay.payment_date or (x.payment_date = pay.payment_date and x.created_at > pay.created_at))) then
    raise exception 'Only the most recent payment on a loan can be reversed. Reverse later payments first.' using errcode = '22023';
  end if;
  v_today := public.lf_today(v_org);

  for a in select * from public.payment_allocations where payment_id = pay.id and not is_reversal loop
    update public.loan_installments set
      principal_paid = principal_paid - a.principal_amount, interest_paid = interest_paid - a.interest_amount,
      charges_paid = charges_paid - a.charges_amount, penalty_paid = penalty_paid - a.penalty_amount,
      interest_waived = interest_waived - a.interest_rebate, paid_at = null
    where id = a.installment_id;
    perform public.lf_refresh_installment(a.installment_id, null);
    insert into public.payment_allocations (organization_id, payment_id, loan_id, installment_id,
      principal_amount, interest_amount, charges_amount, penalty_amount, interest_rebate, is_reversal)
    values (v_org, pay.id, pay.loan_id, a.installment_id,
      -a.principal_amount, -a.interest_amount, -a.charges_amount, -a.penalty_amount, -a.interest_rebate, true);
  end loop;

  update public.loans set
    principal_paid = principal_paid - pay.principal_applied, interest_paid = interest_paid - pay.interest_applied,
    charges_paid = charges_paid - pay.charges_applied, penalties_paid = penalties_paid - pay.penalty_applied,
    total_paid = total_paid - pay.amount, interest_waived = interest_waived - pay.interest_rebated,
    last_payment_date = (select max(payment_date) from public.loan_payments where loan_id = pay.loan_id and status = 'posted' and id <> pay.id),
    status = case when status = 'completed' then 'active'::public.loan_status else status end,
    completed_at = case when status = 'completed' then null else completed_at end
  where id = pay.loan_id;

  perform public.lf_post_ledger(pay.loan_id, 'payment_reversal', v_today, pay.amount, -pay.amount,
    pay.principal_applied, pay.interest_applied, pay.charges_applied, pay.penalty_applied,
    'Reversal of ' || pay.payment_number || ': ' || p_reason, pay.id);
  if pay.interest_rebated > 0 then
    perform public.lf_post_ledger(pay.loan_id, 'adjustment', v_today, pay.interest_rebated, 0, 0, pay.interest_rebated, 0, 0,
      'Settlement interest rebate reinstated (reversal of ' || pay.payment_number || ')', pay.id);
  end if;

  update public.loan_payments set status = 'reversed', reversed_at = now(), reversed_by = auth.uid(), reversal_reason = p_reason
  where id = pay.id;
  update public.payment_receipts set is_void = true, voided_at = now() where payment_id = pay.id;
  perform public.lf_system_note(v_org, pay.loan_id, pay.borrower_id,
    format('Payment %s (PHP %s) reversed: %s', pay.payment_number, to_char(pay.amount, 'FM999,999,999,990.00'), p_reason));
  return public.lf_payment_result(pay.id);
end $$;

-- ---------------------------------------------------------------------------
-- Adjustments: waivers and added charges (audited, ledgered)
-- ---------------------------------------------------------------------------
create or replace function public.apply_loan_adjustment(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('payments.adjust');
  l public.loans%rowtype;
  i public.loan_installments%rowtype;
  v_type text := p->>'type';
  v_amount numeric := round(nullif(p->>'amount', '')::numeric, 2);
  v_reason text := nullif(trim(p->>'reason'), '');
  v_today date;
  v_max numeric;
begin
  if v_reason is null then raise exception 'A reason is required for adjustments' using errcode = '22023'; end if;
  if v_amount is null or v_amount <= 0 then raise exception 'Adjustment amount must be greater than zero' using errcode = '22023'; end if;
  select * into l from public.loans where id = nullif(p->>'loan_id', '')::uuid and organization_id = v_org for update;
  if not found or not public.can_access_loan(l.id) then raise exception 'Loan not found'; end if;
  if l.status not in ('active', 'defaulted') then raise exception 'Adjustments apply to active loans only' using errcode = '22023'; end if;
  select * into i from public.loan_installments where id = nullif(p->>'installment_id', '')::uuid and loan_id = l.id for update;
  if not found then raise exception 'Installment not found' using errcode = '22023'; end if;
  if i.status not in ('pending', 'partially_paid') then raise exception 'Installment is not open' using errcode = '22023'; end if;
  v_today := public.lf_today(v_org);

  if v_type = 'waive_penalty' then
    perform public.lf_assess_penalties(l.id, v_today);
    select * into i from public.loan_installments where id = i.id;
    v_max := i.penalty_due - i.penalty_paid - i.penalty_waived;
    if v_amount > v_max then raise exception 'Only PHP % of penalties is outstanding on this installment', to_char(v_max, 'FM999,999,990.00') using errcode = '22023'; end if;
    update public.loan_installments set penalty_waived = penalty_waived + v_amount where id = i.id;
    update public.loans set penalties_waived = penalties_waived + v_amount where id = l.id;
    perform public.lf_post_ledger(l.id, 'penalty_waived', v_today, v_amount, 0, 0, 0, 0, -v_amount,
      format('Penalty waived on installment #%s: %s', i.installment_no, v_reason));
  elsif v_type = 'waive_interest' then
    v_max := i.interest_due - i.interest_paid - i.interest_waived;
    if v_amount > v_max then raise exception 'Only PHP % of interest is outstanding on this installment', to_char(v_max, 'FM999,999,990.00') using errcode = '22023'; end if;
    update public.loan_installments set interest_waived = interest_waived + v_amount where id = i.id;
    update public.loans set interest_waived = interest_waived + v_amount where id = l.id;
    perform public.lf_post_ledger(l.id, 'interest_waived', v_today, v_amount, 0, 0, -v_amount, 0, 0,
      format('Interest waived on installment #%s: %s', i.installment_no, v_reason));
  elsif v_type = 'waive_charges' then
    v_max := i.charges_due - i.charges_paid - i.charges_waived;
    if v_amount > v_max then raise exception 'Only PHP % of charges is outstanding on this installment', to_char(v_max, 'FM999,999,990.00') using errcode = '22023'; end if;
    update public.loan_installments set charges_waived = charges_waived + v_amount where id = i.id;
    update public.loans set charges_waived = charges_waived + v_amount where id = l.id;
    perform public.lf_post_ledger(l.id, 'charges_waived', v_today, v_amount, 0, 0, 0, -v_amount, 0,
      format('Charges waived on installment #%s: %s', i.installment_no, v_reason));
  elsif v_type = 'add_charge' then
    update public.loan_installments set charges_due = charges_due + v_amount where id = i.id;
    insert into public.loan_charges (organization_id, loan_id, installment_id, charge_type, description, amount, treatment, created_by)
    values (v_org, l.id, i.id, 'adjustment', v_reason, v_amount, 'added', auth.uid());
    perform public.lf_post_ledger(l.id, 'charge_added', v_today, v_amount, 0, 0, 0, v_amount, 0,
      format('Charge added to installment #%s: %s', i.installment_no, v_reason));
  else
    raise exception 'Unknown adjustment type "%"', v_type using errcode = '22023';
  end if;

  perform public.lf_refresh_installment(i.id, v_today);
  select * into l from public.loans where id = l.id;
  if l.total_outstanding = 0 and l.status = 'active' then
    update public.loans set status = 'completed', completed_at = now() where id = l.id;
  end if;
  perform public.lf_system_note(v_org, l.id, l.borrower_id,
    format('Adjustment (%s) of PHP %s on installment #%s: %s', replace(v_type, '_', ' '),
           to_char(v_amount, 'FM999,999,990.00'), i.installment_no, v_reason));
  return jsonb_build_object('ok', true, 'total_outstanding', l.total_outstanding);
end $$;

-- ---------------------------------------------------------------------------
-- Reschedule (documented amendment): re-amortize the outstanding principal
-- ---------------------------------------------------------------------------
create or replace function public.reschedule_loan(p_loan_id uuid, p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('loans.manage');
  l public.loans%rowtype;
  v_today date;
  v_reason text := nullif(trim(p->>'reason'), '');
  v_open_interest numeric;
  v_due_interest numeric;
  v_open_charges numeric;
  v_due_charges numeric;
  v_new_principal numeric;
  v_params jsonb;
  v_calc jsonb;
  v_next integer;
  v_version integer;
  r jsonb;
  v_snap uuid;
begin
  if v_reason is null then raise exception 'A reason for rescheduling is required' using errcode = '22023'; end if;
  select * into l from public.loans where id = p_loan_id and organization_id = v_org for update;
  if not found or not public.can_access_loan(l.id) then raise exception 'Loan not found'; end if;
  if l.status not in ('active', 'defaulted') then raise exception 'Only active loans can be rescheduled' using errcode = '22023'; end if;
  v_today := public.lf_today(v_org);
  perform public.lf_assess_penalties(l.id, v_today);
  select * into l from public.loans where id = l.id;
  if l.outstanding_penalties > 0 then
    raise exception 'Settle or waive outstanding penalties (PHP %) before rescheduling', to_char(l.outstanding_penalties, 'FM999,999,990.00')
      using errcode = '22023';
  end if;
  if l.outstanding_principal <= 0 then raise exception 'No principal remains to reschedule' using errcode = '22023'; end if;

  select coalesce(sum(interest_due - interest_paid - interest_waived), 0),
         coalesce(sum(interest_due - interest_paid - interest_waived) filter (where due_date <= v_today), 0),
         coalesce(sum(charges_due - charges_paid - charges_waived), 0),
         coalesce(sum(charges_due - charges_paid - charges_waived) filter (where due_date <= v_today), 0)
    into v_open_interest, v_due_interest, v_open_charges, v_due_charges
  from public.loan_installments where loan_id = l.id and status in ('pending', 'partially_paid');

  -- interest already due and all open charges (earned fees) are capitalized; unearned future interest is dropped
  v_new_principal := l.outstanding_principal + v_due_interest + v_open_charges;

  v_params := public.lf_build_loan_params(v_org, jsonb_build_object(
    'principal', v_new_principal,
    'interest_method', coalesce(nullif(p->>'interest_method', ''), l.interest_method::text),
    'interest_rate', coalesce(nullif(p->>'interest_rate', '')::numeric, l.interest_rate),
    'rate_period', coalesce(nullif(p->>'rate_period', ''), l.rate_period::text),
    'repayment_frequency', coalesce(nullif(p->>'repayment_frequency', ''), l.repayment_frequency::text),
    'term_count', nullif(p->>'term_count', '')::integer,
    'release_date', v_today,
    'first_due_date', nullif(p->>'first_due_date', ''),
    'day_count', l.day_count, 'rounding', l.rounding,
    'processing_fee_type', 'none', 'processing_fee_value', 0, 'other_charges', 0,
    'charges_treatment', 'added',
    'penalty_enabled', l.penalty_enabled, 'penalty_type', l.penalty_type, 'penalty_rate', l.penalty_rate,
    'penalty_grace_days', l.penalty_grace_days, 'allocation_order', to_jsonb(l.allocation_order),
    'signing_order', l.signing_order, 'witness_count', l.witness_count));
  v_calc := public.compute_loan_schedule(v_params);

  -- close out the open installments
  update public.loan_installments set status = 'rescheduled'
  where loan_id = l.id and status in ('pending', 'partially_paid');

  select coalesce(max(installment_no), 0) + 1 into v_next from public.loan_installments where loan_id = l.id;
  select coalesce(max(version), 0) + 1 into v_version from public.loan_terms_snapshots where loan_id = l.id;

  insert into public.loan_terms_snapshots (organization_id, loan_id, version, kind, terms, summary, schedule, terms_hash, reason, created_by)
  values (v_org, l.id, v_version, 'amendment', v_params, v_calc->'summary', v_calc->'schedule',
          encode(extensions.digest(convert_to((v_params || jsonb_build_object('schedule', v_calc->'schedule'))::text, 'UTF8'), 'sha256'), 'hex'),
          v_reason, auth.uid())
  returning id into v_snap;

  for r in select * from jsonb_array_elements(v_calc->'schedule') loop
    insert into public.loan_installments (organization_id, loan_id, installment_no, due_date, opening_balance,
      principal_due, interest_due, charges_due, closing_balance, snapshot_version)
    values (v_org, l.id, v_next + (r->>'installment_no')::integer - 1, (r->>'due_date')::date, (r->>'opening_balance')::numeric,
      (r->>'principal')::numeric, (r->>'interest')::numeric, (r->>'charges')::numeric, (r->>'closing_balance')::numeric, v_version);
  end loop;

  -- balances: capitalize due interest + open charges into principal, replace the old open interest with the new schedule's
  perform public.lf_post_ledger(l.id, 'reschedule', v_today,
    (v_calc #>> '{summary,total_interest}')::numeric,
    0,
    v_new_principal - l.outstanding_principal,
    (v_calc #>> '{summary,total_interest}')::numeric - v_open_interest,
    -v_open_charges,
    0,
    format('Loan rescheduled (amendment v%s): %s', v_version, v_reason));

  update public.loans set
    maturity_date = (v_calc #>> '{summary,last_due_date}')::date,
    installment_amount = (v_calc #>> '{summary,installment_amount}')::numeric,
    terms_snapshot_id = v_snap,
    status = 'active'
  where id = l.id;

  perform public.lf_system_note(v_org, l.id, l.borrower_id,
    format('Loan rescheduled: PHP %s re-amortized over %s installments from %s. Reason: %s. A new agreement version should be generated and signed.',
      to_char(v_new_principal, 'FM999,999,999,990.00'), v_calc #>> '{summary,installment_count}',
      v_calc #>> '{summary,first_due_date}', v_reason));
  return jsonb_build_object('snapshot_id', v_snap, 'version', v_version, 'summary', v_calc->'summary');
end $$;


-- ==== 20261114000006_agreements.sql ====
-- =============================================================================
-- LendFlow — Agreement templates, generation, signing sessions and signatures
--
-- PDF rendering/embedding happens in Edge Functions (server-side). The database
-- owns every state transition, in transactions, with row locks:
--   agreement_begin       (user)    freeze terms snapshot, reserve number
--   agreement_complete    (service) register the unsigned PDF + hash
--   signature_commit      (service) idempotent: one signature per role
--   agreement_set_pdf     (service) compare-and-set the latest embedded PDF;
--                                    finalizes when all signatures are embedded
-- Signed agreements are never overwritten: amendments create a new version.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Template versioning
-- ---------------------------------------------------------------------------
create or replace function public.save_agreement_template(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('templates.manage');
  v_id uuid := nullif(p->>'id', '')::uuid;
  v_version integer;
  v_version_id uuid;
begin
  if coalesce(trim(p->>'name'), '') = '' then raise exception 'Template name is required' using errcode = '22023'; end if;
  if coalesce(trim(p->>'title'), '') = '' then raise exception 'Agreement title is required' using errcode = '22023'; end if;
  if coalesce(trim(p->>'body'), '') = '' then raise exception 'Template body is required' using errcode = '22023'; end if;

  if v_id is null then
    insert into public.loan_agreement_templates (organization_id, name, description, is_active, created_by)
    values (v_org, trim(p->>'name'), nullif(p->>'description', ''), coalesce((p->>'is_active')::boolean, true), auth.uid())
    returning id into v_id;
  else
    update public.loan_agreement_templates
    set name = trim(p->>'name'), description = nullif(p->>'description', ''),
        is_active = coalesce((p->>'is_active')::boolean, is_active)
    where id = v_id and organization_id = v_org;
    if not found then raise exception 'Template not found'; end if;
  end if;

  select coalesce(max(version_no), 0) + 1 into v_version from public.loan_agreement_template_versions where template_id = v_id;
  insert into public.loan_agreement_template_versions (organization_id, template_id, version_no, title, body, settings, change_note, created_by)
  values (v_org, v_id, v_version, trim(p->>'title'), p->>'body', coalesce(p->'settings', '{}'::jsonb), nullif(p->>'change_note', ''), auth.uid())
  returning id into v_version_id;

  update public.loan_agreement_templates set current_version_id = v_version_id where id = v_id;
  if coalesce((p->>'is_default')::boolean, false) then
    perform public.set_default_agreement_template(v_id);
  end if;
  return jsonb_build_object('id', v_id, 'version_id', v_version_id, 'version_no', v_version);
end $$;

create or replace function public.set_default_agreement_template(p_template_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare v_org uuid := public.lf_require('templates.manage');
begin
  if not exists (select 1 from public.loan_agreement_templates where id = p_template_id and organization_id = v_org and is_active) then
    raise exception 'Template not found or inactive';
  end if;
  update public.loan_agreement_templates set is_default = false where organization_id = v_org and is_default and id <> p_template_id;
  update public.loan_agreement_templates set is_default = true where id = p_template_id;
end $$;

create or replace function public.duplicate_agreement_template(p_template_id uuid, p_name text default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('templates.manage');
  t public.loan_agreement_templates%rowtype;
  v public.loan_agreement_template_versions%rowtype;
begin
  select * into t from public.loan_agreement_templates where id = p_template_id and organization_id = v_org;
  if not found then raise exception 'Template not found'; end if;
  select * into v from public.loan_agreement_template_versions where id = t.current_version_id;
  return public.save_agreement_template(jsonb_build_object(
    'name', coalesce(nullif(trim(p_name), ''), t.name || ' (copy)'), 'description', t.description,
    'title', v.title, 'body', v.body, 'settings', v.settings, 'change_note', 'Duplicated from ' || t.name || ' v' || v.version_no));
end $$;

-- ---------------------------------------------------------------------------
-- Internal helpers
-- ---------------------------------------------------------------------------
create or replace function public.lf_sha256_hex(p text)
returns text language sql immutable as $$
  select encode(extensions.digest(convert_to(p, 'UTF8'), 'sha256'), 'hex')
$$;

create or replace function public.lf_random_token()
returns text language sql volatile as $$
  select translate(rtrim(encode(extensions.gen_random_bytes(32), 'base64'), '='), '+/', '-_')
$$;

create or replace function public.lf_agreement_event(p_agreement uuid, p_session uuid, p_type text, p_actor_type text,
  p_actor_user uuid, p_actor_name text, p_ip text, p_ua text, p_meta jsonb default '{}'::jsonb)
returns void language sql security definer set search_path = public as $$
  insert into public.agreement_audit_events (organization_id, agreement_id, session_id, event_type, actor_type,
                                             actor_user_id, actor_name, ip_address, user_agent, metadata)
  select a.organization_id, a.id, p_session, p_type, p_actor_type, p_actor_user, p_actor_name, p_ip, p_ua, coalesce(p_meta, '{}'::jsonb)
  from public.loan_agreements a where a.id = p_agreement
$$;

-- Everything the PDF renderer needs, as one JSON document
create or replace function public.lf_agreement_render_data(p_agreement_id uuid)
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'agreement', jsonb_build_object('id', a.id, 'agreement_number', a.agreement_number, 'version', a.version, 'kind', a.kind,
                                    'status', a.status, 'generated_at', coalesce(a.generated_at, a.created_at),
                                    'required_signers', to_jsonb(a.required_signers), 'signing_order', a.signing_order,
                                    'unsigned_pdf_path', a.unsigned_pdf_path, 'unsigned_pdf_hash', a.unsigned_pdf_hash,
                                    'signature_fields', a.signature_fields, 'page_count', a.page_count),
    'organization', to_jsonb(o) - 'created_at' - 'updated_at',
    'borrower', (to_jsonb(b) - 'internal_notes' - 'assessment_notes') || jsonb_build_object(
                  'id_type', bi.id_type, 'id_number', bi.id_number,
                  'address_full', concat_ws(', ', nullif(b.address_line, ''), nullif(b.barangay, ''), nullif(b.city_municipality, ''),
                                            nullif(b.province, ''), nullif(b.zip_code, ''))),
    'loan', to_jsonb(l) - 'calc_summary',
    'snapshot', jsonb_build_object('id', s.id, 'version', s.version, 'kind', s.kind, 'terms_hash', s.terms_hash, 'reason', s.reason),
    'summary', s.summary,
    'schedule', s.schedule,
    'template', jsonb_build_object('id', tv.template_id, 'version_id', tv.id, 'version_no', tv.version_no,
                                   'title', tv.title, 'body', tv.body, 'settings', tv.settings),
    'officer', jsonb_build_object('full_name', coalesce(off.full_name, cr.full_name)),
    'settings', public.lf_settings(o.id) -> 'agreement',
    'signatures', coalesce((select jsonb_agg(jsonb_build_object(
                      'id', sg.id, 'signer_role', sg.signer_role, 'signer_name', sg.signer_name,
                      'signature_image_path', sg.signature_image_path, 'signature_image_hash', sg.signature_image_hash,
                      'signed_at', sg.signed_at, 'ip_address', sg.ip_address, 'user_agent', sg.user_agent,
                      'verification_method', sg.verification_method, 'consent_given_at', sg.consent_given_at)
                      order by sg.signed_at, sg.signer_role)
                    from public.agreement_signatures sg where sg.agreement_id = a.id), '[]'::jsonb))
  from public.loan_agreements a
  join public.loans l on l.id = a.loan_id
  join public.organizations o on o.id = a.organization_id
  join public.borrowers b on b.id = l.borrower_id
  left join public.borrower_identities bi on bi.borrower_id = b.id
  join public.loan_terms_snapshots s on s.id = a.terms_snapshot_id
  left join public.loan_agreement_template_versions tv on tv.id = a.template_version_id
  left join public.user_profiles off on off.id = l.loan_officer_id
  left join public.user_profiles cr on cr.id = l.created_by
  where a.id = p_agreement_id
$$;

-- ---------------------------------------------------------------------------
-- Generation
-- ---------------------------------------------------------------------------
create or replace function public.agreement_begin(p_loan_id uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('agreements.generate');
  l public.loans%rowtype;
  v_settings jsonb;
  v_params jsonb;
  v_calc jsonb;
  v_snap public.loan_terms_snapshots%rowtype;
  v_snap_id uuid;
  v_version integer;
  v_template public.loan_agreement_templates%rowtype;
  v_kind text := 'original';
  v_signers text[];
  v_id uuid;
  i integer;
begin
  select * into l from public.loans where id = p_loan_id and organization_id = v_org for update;
  if not found or not public.can_access_loan(l.id) then raise exception 'Loan not found'; end if;
  v_settings := public.lf_settings(v_org);

  if l.status in ('active', 'defaulted') then
    -- amendment agreement for a rescheduled loan
    select * into v_snap from public.loan_terms_snapshots where id = l.terms_snapshot_id;
    if v_snap.kind <> 'amendment' then
      raise exception 'This active loan has no pending amendment. Reschedule the loan first to generate an amendment agreement.'
        using errcode = '22023';
    end if;
    if exists (select 1 from public.loan_agreements where terms_snapshot_id = v_snap.id and status in ('pending_signatures', 'partially_signed', 'fully_signed')) then
      raise exception 'An agreement for this amendment already exists' using errcode = '22023';
    end if;
    v_kind := 'amendment';
    v_snap_id := v_snap.id;
  elsif l.status in ('draft', 'pending_review', 'approved', 'awaiting_borrower_signature', 'awaiting_lender_signature') then
    if exists (select 1 from public.agreement_signatures sg join public.loan_agreements a on a.id = sg.agreement_id
               where a.loan_id = l.id and a.status in ('pending_signatures', 'partially_signed')) then
      raise exception 'The current agreement already has signatures. Void it before generating a new version.' using errcode = '22023';
    end if;
    if l.status = 'draft' then
      perform public.submit_loan(l.id);
      select * into l from public.loans where id = l.id;
    end if;
    -- supersede any unsigned, still-open agreement
    update public.agreement_signing_sessions set status = 'revoked'
    where agreement_id in (select id from public.loan_agreements where loan_id = l.id and status in ('pending_signatures', 'generating', 'failed'))
      and status in ('pending', 'opened', 'verified');
    update public.loan_agreements set status = 'superseded'
    where loan_id = l.id and status in ('pending_signatures', 'generating', 'failed');

    -- freeze the contractual terms (recomputed server-side from the loan record)
    v_params := public.lf_loan_params(l);
    v_calc := public.compute_loan_schedule(v_params);
    if (v_calc #>> '{summary,total_repayment}')::numeric <> l.total_repayment
       or (v_calc #>> '{summary,total_interest}')::numeric <> l.total_interest then
      raise exception 'Loan figures changed since they were saved. Re-open the loan, review the preview and save it again.';
    end if;
    select coalesce(max(version), 0) + 1 into v_version from public.loan_terms_snapshots where loan_id = l.id;
    insert into public.loan_terms_snapshots (organization_id, loan_id, version, kind, terms, summary, schedule, terms_hash, created_by)
    values (v_org, l.id, v_version, 'original', v_params, v_calc->'summary', v_calc->'schedule',
            public.lf_sha256_hex((v_params || jsonb_build_object('schedule', v_calc->'schedule'))::text), auth.uid())
    returning id into v_snap_id;
  else
    raise exception 'Agreements cannot be generated for a loan in status "%"', l.status using errcode = '22023';
  end if;

  -- template resolution: loan -> settings default -> organization default -> any active
  select * into v_template from public.loan_agreement_templates t
  where t.organization_id = v_org and t.is_active and t.current_version_id is not null
  order by (t.id = l.agreement_template_id) desc,
           (t.id = nullif(v_settings #>> '{agreement,default_template_id}', '')::uuid) desc,
           t.is_default desc, t.created_at
  limit 1;
  if not found then raise exception 'No active agreement template is configured' using errcode = '22023'; end if;

  v_signers := array['borrower'];
  if coalesce((v_settings #>> '{agreement,require_lender_signature}')::boolean, true) then
    v_signers := array_append(v_signers, 'lender');
  end if;
  for i in 1..coalesce(l.witness_count, 0) loop
    v_signers := array_append(v_signers, 'witness_' || i);
  end loop;

  select coalesce(max(version), 0) + 1 into v_version from public.loan_agreements where loan_id = l.id;
  insert into public.loan_agreements (organization_id, loan_id, agreement_number, version, kind, status,
    template_id, template_version_id, terms_snapshot_id, required_signers, signing_order, generated_by, generated_at)
  values (v_org, l.id, public.lf_next_ref(v_org, 'agreement', 'LA'), v_version, v_kind, 'generating',
    v_template.id, v_template.current_version_id, v_snap_id, v_signers, l.signing_order, auth.uid(), now())
  returning id into v_id;

  return public.lf_agreement_render_data(v_id);
end $$;

create or replace function public.agreement_complete(p_agreement_id uuid, p_path text, p_hash text, p_fields jsonb,
  p_page_count integer, p_placeholders jsonb, p_rendered_body text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  a public.loan_agreements%rowtype;
  l public.loans%rowtype;
begin
  select * into a from public.loan_agreements where id = p_agreement_id for update;
  if not found then raise exception 'Agreement not found'; end if;
  if a.status <> 'generating' then raise exception 'Agreement is not being generated (status %)', a.status; end if;
  update public.loan_agreements set
    status = 'pending_signatures', unsigned_pdf_path = p_path, unsigned_pdf_hash = p_hash,
    current_pdf_path = p_path, current_pdf_hash = p_hash, current_signature_count = 0,
    signature_fields = coalesce(p_fields, '{}'::jsonb), page_count = p_page_count,
    placeholders = coalesce(p_placeholders, '{}'::jsonb), rendered_body = p_rendered_body
  where id = a.id;

  select * into l from public.loans where id = a.loan_id for update;
  if a.kind = 'original' then
    update public.loans set status = case when a.signing_order = 'lender_first' then 'awaiting_lender_signature'::public.loan_status
                                          else 'awaiting_borrower_signature'::public.loan_status end
    where id = l.id;
  end if;
  perform public.lf_agreement_event(a.id, null, 'generated', 'user', a.generated_by, null, null, null,
    jsonb_build_object('pdf_hash', p_hash, 'pages', p_page_count, 'version', a.version));
  perform public.lf_system_note(a.organization_id, l.id, l.borrower_id,
    format('Agreement %s (v%s) generated; awaiting signatures.', a.agreement_number, a.version));
  return jsonb_build_object('id', a.id, 'agreement_number', a.agreement_number, 'status', 'pending_signatures');
end $$;

create or replace function public.agreement_fail(p_agreement_id uuid, p_error text)
returns void language sql security definer set search_path = public as $$
  update public.loan_agreements set status = 'failed', error_message = left(p_error, 2000)
  where id = p_agreement_id and status = 'generating'
$$;

create or replace function public.agreement_void(p_agreement_id uuid, p_reason text)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('agreements.generate');
  a public.loan_agreements%rowtype;
  l public.loans%rowtype;
begin
  if coalesce(trim(p_reason), '') = '' then raise exception 'A reason is required to void an agreement' using errcode = '22023'; end if;
  select * into a from public.loan_agreements where id = p_agreement_id and organization_id = v_org for update;
  if not found or not public.can_access_loan(a.loan_id) then raise exception 'Agreement not found'; end if;
  if a.status = 'fully_signed' then
    raise exception 'A fully signed agreement cannot be voided. Cancel the loan or create an amendment instead.' using errcode = '22023';
  end if;
  if a.status not in ('pending_signatures', 'partially_signed', 'failed', 'generating') then
    raise exception 'Agreement is already %', a.status using errcode = '22023';
  end if;
  update public.agreement_signing_sessions set status = 'revoked' where agreement_id = a.id and status in ('pending', 'opened', 'verified');
  update public.loan_agreements set status = 'voided', voided_at = now(), voided_by = auth.uid(), void_reason = p_reason where id = a.id;
  perform public.lf_agreement_event(a.id, null, 'voided', 'user', auth.uid(), null, null, null, jsonb_build_object('reason', p_reason));
  select * into l from public.loans where id = a.loan_id for update;
  if a.kind = 'original' and l.status in ('awaiting_borrower_signature', 'awaiting_lender_signature') then
    update public.loans set status = case when approved_at is not null then 'approved'::public.loan_status else 'pending_review'::public.loan_status end
    where id = l.id;
  end if;
  perform public.lf_system_note(v_org, l.id, l.borrower_id, format('Agreement %s voided: %s', a.agreement_number, p_reason));
end $$;

-- ---------------------------------------------------------------------------
-- Signing sessions (secure, time-limited, single-use links)
-- ---------------------------------------------------------------------------
create or replace function public.signing_session_create(p_agreement_id uuid, p_signer_role text, p_signer_name text default null,
  p_contact text default null, p_expiry_hours integer default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('agreements.generate');
  a public.loan_agreements%rowtype;
  b public.borrowers%rowtype;
  v_settings jsonb;
  v_token text;
  v_hours integer;
  v_method text;
  v_name text;
  v_id uuid;
begin
  select * into a from public.loan_agreements where id = p_agreement_id and organization_id = v_org for update;
  if not found or not public.can_access_loan(a.loan_id) then raise exception 'Agreement not found'; end if;
  if a.status not in ('pending_signatures', 'partially_signed') then
    raise exception 'Agreement is not open for signing (status %)', a.status using errcode = '22023';
  end if;
  if not (p_signer_role = any (a.required_signers)) then
    raise exception 'Role "%" is not a required signer of this agreement', p_signer_role using errcode = '22023';
  end if;
  if exists (select 1 from public.agreement_signatures where agreement_id = a.id and signer_role = p_signer_role) then
    raise exception 'This party has already signed' using errcode = '22023';
  end if;

  v_settings := public.lf_settings(v_org);
  v_hours := least(greatest(coalesce(p_expiry_hours, (v_settings #>> '{agreement,link_expiry_hours}')::integer, 72), 1), 720);
  select b2.* into b from public.borrowers b2 join public.loans l on l.borrower_id = b2.id where l.id = a.loan_id;
  if p_signer_role = 'borrower' then
    v_method := coalesce(v_settings #>> '{agreement,identity_verification}', 'dob');
    if v_method = 'dob' and b.date_of_birth is null then v_method := 'none'; end if;
    if v_method = 'id_last4' and not exists (select 1 from public.borrower_identities where borrower_id = b.id) then v_method := 'none'; end if;
    v_name := b.full_name;
  else
    v_method := 'none';
    v_name := coalesce(nullif(trim(p_signer_name), ''), initcap(replace(p_signer_role, '_', ' ')));
  end if;

  update public.agreement_signing_sessions set status = 'revoked'
  where agreement_id = a.id and signer_role = p_signer_role and status in ('pending', 'opened', 'verified');

  v_token := public.lf_random_token();
  insert into public.agreement_signing_sessions (organization_id, agreement_id, signer_role, signer_name, signer_contact,
    token_hash, channel, verification_method, expires_at, created_by)
  values (v_org, a.id, p_signer_role, v_name, coalesce(nullif(p_contact, ''), case when p_signer_role = 'borrower' then b.mobile_number end),
    public.lf_sha256_hex(v_token), 'link', v_method, now() + make_interval(hours => v_hours), auth.uid())
  returning id into v_id;

  perform public.lf_agreement_event(a.id, v_id, 'link_created', 'user', auth.uid(), null, null, null,
    jsonb_build_object('signer_role', p_signer_role, 'expires_in_hours', v_hours, 'verification', v_method));
  return jsonb_build_object('session_id', v_id, 'token', v_token, 'expires_at', now() + make_interval(hours => v_hours),
                            'verification_method', v_method, 'signer_name', v_name);
end $$;

create or replace function public.signing_session_revoke(p_session_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('agreements.generate');
  s public.agreement_signing_sessions%rowtype;
begin
  select * into s from public.agreement_signing_sessions where id = p_session_id and organization_id = v_org for update;
  if not found then raise exception 'Signing link not found'; end if;
  if s.status not in ('pending', 'opened', 'verified') then raise exception 'Signing link is already %', s.status using errcode = '22023'; end if;
  update public.agreement_signing_sessions set status = 'revoked' where id = s.id;
  perform public.lf_agreement_event(s.agreement_id, s.id, 'link_revoked', 'user', auth.uid(), null, null, null, '{}'::jsonb);
end $$;

-- Public signing: look up a session by token hash (service role only)
create or replace function public.signing_lookup(p_token_hash text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  s public.agreement_signing_sessions%rowtype;
  a public.loan_agreements%rowtype;
begin
  select * into s from public.agreement_signing_sessions where token_hash = p_token_hash;
  if not found then return null; end if;
  if s.status in ('pending', 'opened', 'verified') and s.expires_at < now() then
    update public.agreement_signing_sessions set status = 'expired' where id = s.id;
    s.status := 'expired';
  end if;
  select * into a from public.loan_agreements where id = s.agreement_id;
  return jsonb_build_object(
    'session', jsonb_build_object('id', s.id, 'signer_role', s.signer_role, 'signer_name', s.signer_name, 'status', s.status,
                                  'expires_at', s.expires_at, 'verification_method', s.verification_method,
                                  'verified', s.verified_at is not null, 'signed_at', s.signed_at,
                                  'locked_until', s.locked_until, 'failed_attempts', s.failed_attempts,
                                  'verify_nonce_hash', s.verify_nonce_hash, 'organization_id', s.organization_id),
    'agreement_id', a.id, 'agreement_status', a.status);
end $$;

create or replace function public.signing_mark_opened(p_session_id uuid, p_ip text, p_ua text)
returns void language plpgsql security definer set search_path = public as $$
declare s public.agreement_signing_sessions%rowtype;
begin
  select * into s from public.agreement_signing_sessions where id = p_session_id for update;
  if s.status = 'pending' then
    update public.agreement_signing_sessions set status = 'opened', opened_at = now() where id = s.id;
  end if;
  perform public.lf_agreement_event(s.agreement_id, s.id, 'viewed', 'signer', null, s.signer_name, p_ip, p_ua, '{}'::jsonb);
end $$;

-- Identity check before the document is revealed. Returns a one-time nonce on success.
create or replace function public.signing_verify(p_session_id uuid, p_answer text, p_ip text, p_ua text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  s public.agreement_signing_sessions%rowtype;
  v_ok boolean := false;
  v_dob date;
  v_id text;
  v_nonce text;
  v_answer text := trim(coalesce(p_answer, ''));
begin
  select * into s from public.agreement_signing_sessions where id = p_session_id for update;
  if not found then raise exception 'Signing link not found'; end if;
  if s.status not in ('pending', 'opened', 'verified', 'signed') then
    return jsonb_build_object('ok', false, 'error', 'This signing link is ' || s.status || '.');
  end if;
  if s.expires_at < now() then
    if s.status <> 'signed' then
      update public.agreement_signing_sessions set status = 'expired' where id = s.id;
    end if;
    return jsonb_build_object('ok', false, 'error', 'This signing link has expired.');
  end if;
  if s.locked_until is not null and s.locked_until > now() then
    return jsonb_build_object('ok', false, 'error', 'Too many failed attempts. Try again after ' ||
      to_char(s.locked_until at time zone 'Asia/Manila', 'HH12:MI AM') || '.', 'locked_until', s.locked_until);
  end if;

  if s.verification_method = 'none' then
    v_ok := true;
  elsif s.verification_method = 'dob' then
    select b.date_of_birth into v_dob from public.loan_agreements a join public.loans l on l.id = a.loan_id
    join public.borrowers b on b.id = l.borrower_id where a.id = s.agreement_id;
    begin
      v_ok := v_dob is not null and v_answer::date = v_dob;
    exception when others then v_ok := false;
    end;
  elsif s.verification_method = 'id_last4' then
    select upper(regexp_replace(bi.id_number, '[^A-Za-z0-9]', '', 'g')) into v_id
    from public.loan_agreements a join public.loans l on l.id = a.loan_id
    join public.borrower_identities bi on bi.borrower_id = l.borrower_id where a.id = s.agreement_id;
    v_ok := v_id is not null and length(v_answer) >= 4 and right(v_id, 4) = upper(right(regexp_replace(v_answer, '[^A-Za-z0-9]', '', 'g'), 4));
  end if;

  if not v_ok then
    update public.agreement_signing_sessions set
      failed_attempts = failed_attempts + 1,
      locked_until = case when failed_attempts + 1 >= 5 then now() + interval '15 minutes' else locked_until end
    where id = s.id;
    perform public.lf_agreement_event(s.agreement_id, s.id, 'verification_failed', 'signer', null, s.signer_name, p_ip, p_ua,
      jsonb_build_object('attempt', s.failed_attempts + 1));
    return jsonb_build_object('ok', false, 'error', 'The details you entered do not match our records.',
                              'attempts_left', greatest(0, 4 - s.failed_attempts));
  end if;

  v_nonce := public.lf_random_token();
  update public.agreement_signing_sessions set
    status = case when status = 'signed' then 'signed' else 'verified' end,
    verified_at = coalesce(verified_at, now()), failed_attempts = 0, locked_until = null,
    opened_at = coalesce(opened_at, now()), verify_nonce_hash = public.lf_sha256_hex(v_nonce)
  where id = s.id;
  perform public.lf_agreement_event(s.agreement_id, s.id, 'verified', 'signer', null, s.signer_name, p_ip, p_ua,
    jsonb_build_object('method', s.verification_method));
  return jsonb_build_object('ok', true, 'nonce', v_nonce);
end $$;

-- ---------------------------------------------------------------------------
-- Signature commit (idempotent per agreement + role)
-- ---------------------------------------------------------------------------
create or replace function public.signature_commit(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  a public.loan_agreements%rowtype;
  s public.agreement_signing_sessions%rowtype;
  v_role text := p->>'signer_role';
  v_existing uuid;
  v_sig uuid;
  v_signed text[];
  v_missing text[];
  v_session uuid := nullif(p->>'session_id', '')::uuid;
begin
  select * into a from public.loan_agreements where id = (p->>'agreement_id')::uuid for update;
  if not found then raise exception 'Agreement not found'; end if;

  select id into v_existing from public.agreement_signatures where agreement_id = a.id and signer_role = v_role;
  if found then
    return jsonb_build_object('duplicate', true, 'signature_id', v_existing) || public.lf_agreement_signing_state(a.id);
  end if;

  if a.status not in ('pending_signatures', 'partially_signed') then
    raise exception 'Agreement is not open for signing (status %)', a.status using errcode = '22023';
  end if;
  if not (v_role = any (a.required_signers)) then
    raise exception 'Role "%" is not a required signer', v_role using errcode = '22023';
  end if;

  select array_agg(signer_role) into v_signed from public.agreement_signatures where agreement_id = a.id;
  v_signed := coalesce(v_signed, '{}');
  if a.signing_order = 'borrower_first' and v_role = 'lender' and not ('borrower' = any (v_signed)) then
    raise exception 'The borrower must sign before the lender (signing order: borrower first)' using errcode = '22023';
  end if;
  if a.signing_order = 'lender_first' and v_role = 'borrower' and 'lender' = any (a.required_signers) and not ('lender' = any (v_signed)) then
    raise exception 'The lender must sign before the borrower (signing order: lender first)' using errcode = '22023';
  end if;

  if v_session is not null then
    select * into s from public.agreement_signing_sessions where id = v_session for update;
    if not found or s.agreement_id <> a.id or s.signer_role <> v_role then raise exception 'Invalid signing session'; end if;
    if s.status <> 'verified' then raise exception 'Signing session is not verified (status %)', s.status using errcode = '22023'; end if;
    if s.expires_at < now() then raise exception 'Signing link has expired' using errcode = '22023'; end if;
  else
    insert into public.agreement_signing_sessions (organization_id, agreement_id, signer_role, signer_name, channel,
      verification_method, status, expires_at, opened_at, verified_at, created_by)
    values (a.organization_id, a.id, v_role, p->>'signer_name', 'in_app', 'none', 'verified', now() + interval '1 hour', now(), now(),
            nullif(p->>'signer_user_id', '')::uuid)
    returning * into s;
  end if;

  insert into public.agreement_signatures (organization_id, agreement_id, session_id, signer_role, signer_name, signer_user_id,
    signature_image_path, signature_image_hash, consent_text, consent_given_at, verification_method, ip_address, user_agent)
  values (a.organization_id, a.id, s.id, v_role, coalesce(nullif(p->>'signer_name', ''), s.signer_name),
    nullif(p->>'signer_user_id', '')::uuid, p->>'image_path', p->>'image_hash', p->>'consent_text',
    coalesce(nullif(p->>'consent_given_at', '')::timestamptz, now()), s.verification_method, p->>'ip', p->>'user_agent')
  returning id into v_sig;

  update public.agreement_signing_sessions set status = 'signed', signed_at = now() where id = s.id;
  update public.loan_agreements set status = 'partially_signed' where id = a.id;

  perform public.lf_agreement_event(a.id, s.id, 'consent_given', case when v_session is null then 'user' else 'signer' end,
    nullif(p->>'signer_user_id', '')::uuid, coalesce(nullif(p->>'signer_name', ''), s.signer_name), p->>'ip', p->>'user_agent',
    jsonb_build_object('consent_text', p->>'consent_text'));
  perform public.lf_agreement_event(a.id, s.id, 'signed', case when v_session is null then 'user' else 'signer' end,
    nullif(p->>'signer_user_id', '')::uuid, coalesce(nullif(p->>'signer_name', ''), s.signer_name), p->>'ip', p->>'user_agent',
    jsonb_build_object('signer_role', v_role, 'signature_hash', p->>'image_hash'));

  -- move the loan to the next awaiting state
  v_signed := array_append(v_signed, v_role);
  select array_agg(r) into v_missing from unnest(a.required_signers) r where not (r = any (v_signed));
  if a.kind = 'original' and v_missing is not null then
    update public.loans set status = case
        when 'borrower' = any (v_missing) and (a.signing_order <> 'lender_first' or not ('lender' = any (v_missing)))
          then 'awaiting_borrower_signature'::public.loan_status
        when 'lender' = any (v_missing) then 'awaiting_lender_signature'::public.loan_status
        else status end
    where id = a.loan_id and status in ('awaiting_borrower_signature', 'awaiting_lender_signature');
  end if;

  return jsonb_build_object('duplicate', false, 'signature_id', v_sig) || public.lf_agreement_signing_state(a.id);
end $$;

create or replace function public.lf_agreement_signing_state(p_agreement_id uuid)
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'agreement_status', a.status,
    'required_signers', to_jsonb(a.required_signers),
    'signature_count', (select count(*) from public.agreement_signatures where agreement_id = a.id),
    'all_signed', not exists (select 1 from unnest(a.required_signers) r
                              where not exists (select 1 from public.agreement_signatures sg where sg.agreement_id = a.id and sg.signer_role = r)),
    'current_pdf_path', a.current_pdf_path, 'current_signature_count', a.current_signature_count,
    'signed_pdf_path', a.signed_pdf_path, 'signed_pdf_hash', a.signed_pdf_hash)
  from public.loan_agreements a where a.id = p_agreement_id
$$;

-- Compare-and-set the latest PDF; finalize when every required signature is embedded.
create or replace function public.agreement_set_pdf(p_agreement_id uuid, p_path text, p_hash text, p_signature_count integer)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  a public.loan_agreements%rowtype;
  l public.loans%rowtype;
  v_total integer;
  v_all boolean;
begin
  select * into a from public.loan_agreements where id = p_agreement_id for update;
  if not found then raise exception 'Agreement not found'; end if;
  if a.status = 'fully_signed' then
    return jsonb_build_object('applied', false, 'reason', 'already_final') || public.lf_agreement_signing_state(a.id);
  end if;
  if a.status not in ('partially_signed', 'pending_signatures') then
    return jsonb_build_object('applied', false, 'reason', 'status_' || a.status) || public.lf_agreement_signing_state(a.id);
  end if;
  select count(*) into v_total from public.agreement_signatures where agreement_id = a.id;
  if p_signature_count < a.current_signature_count or p_signature_count > v_total then
    return jsonb_build_object('applied', false, 'reason', 'stale') || public.lf_agreement_signing_state(a.id);
  end if;
  v_all := p_signature_count = v_total and not exists (
    select 1 from unnest(a.required_signers) r
    where not exists (select 1 from public.agreement_signatures sg where sg.agreement_id = a.id and sg.signer_role = r));

  update public.loan_agreements set current_pdf_path = p_path, current_pdf_hash = p_hash, current_signature_count = p_signature_count
  where id = a.id;

  if v_all then
    update public.loan_agreements set status = 'fully_signed', signed_pdf_path = p_path, signed_pdf_hash = p_hash, fully_signed_at = now()
    where id = a.id;
    perform public.lf_agreement_event(a.id, null, 'pdf_finalized', 'system', null, null, null, null,
      jsonb_build_object('signed_pdf_hash', p_hash, 'signatures', p_signature_count));
    select * into l from public.loans where id = a.loan_id for update;
    if a.kind = 'original' and l.status in ('awaiting_borrower_signature', 'awaiting_lender_signature', 'pending_review', 'approved') then
      update public.loans set status = case when approved_at is not null then 'ready_for_release'::public.loan_status
                                            else 'fully_signed'::public.loan_status end
      where id = l.id;
    end if;
    perform public.lf_system_note(a.organization_id, a.loan_id, l.borrower_id,
      format('Agreement %s fully signed. SHA-256: %s', a.agreement_number, p_hash));
    perform public.lf_notify_roles(a.organization_id, array['owner', 'admin']::public.app_role[], 'agreement_signed',
      'Agreement fully signed', a.agreement_number || ' has all required signatures.', '/loans/' || a.loan_id);
  end if;
  return jsonb_build_object('applied', true, 'finalized', v_all) || public.lf_agreement_signing_state(a.id);
end $$;

create or replace function public.agreement_log_download(p_agreement_id uuid, p_session uuid, p_actor_type text, p_user uuid, p_ip text, p_ua text)
returns void language sql security definer set search_path = public as $$
  select public.lf_agreement_event(p_agreement_id, p_session, 'downloaded', p_actor_type, p_user, null, p_ip, p_ua, '{}'::jsonb)
$$;

-- Simple fixed-window rate limiter for public endpoints (service role only)
create or replace function public.lf_rate_limit(p_key text, p_limit integer, p_window_seconds integer)
returns boolean language plpgsql security definer set search_path = public as $$
declare
  v_window timestamptz := to_timestamp(floor(extract(epoch from now()) / p_window_seconds) * p_window_seconds);
  v_hits integer;
begin
  insert into public.rate_limits (key, window_start, hits) values (p_key, v_window, 1)
  on conflict (key, window_start) do update set hits = public.rate_limits.hits + 1
  returning hits into v_hits;
  if random() < 0.01 then
    delete from public.rate_limits where window_start < now() - interval '1 day';
  end if;
  return v_hits <= p_limit;
end $$;

-- Canonical permission catalogue (owners implicitly hold every permission)
create or replace function public.lf_all_permissions()
returns text[] language sql immutable as $$
  select array[
    'dashboard.view',
    'borrowers.view', 'borrowers.view_all', 'borrowers.create', 'borrowers.edit', 'borrowers.archive', 'borrowers.view_sensitive',
    'loans.view', 'loans.view_all', 'loans.create', 'loans.approve', 'loans.release', 'loans.cancel', 'loans.manage',
    'agreements.view', 'agreements.generate', 'agreements.sign_lender',
    'payments.view', 'payments.record', 'payments.reverse', 'payments.adjust',
    'collections.assign', 'reports.view',
    'products.manage', 'templates.manage', 'users.manage', 'settings.manage', 'audit.view'
  ]
$$;

-- In-app permission probe used by the frontend and by Edge Functions acting with the caller's JWT
create or replace function public.my_access()
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'user_id', auth.uid(),
    'organization_id', public.current_org_id(),
    'role', public.current_app_role(),
    'full_name', (select full_name from public.user_profiles where id = auth.uid()),
    'permissions', coalesce(
      case when public.current_app_role() = 'owner'
           then to_jsonb(public.lf_all_permissions())
           else (select jsonb_agg(rp.permission) from public.role_permissions rp
                 where rp.organization_id = public.current_org_id() and rp.role = public.current_app_role()) end,
      '[]'::jsonb))
$$;


-- ==== 20261114000007_reports.sql ====
-- =============================================================================
-- LendFlow — Read models: views, dashboard and financial reports
-- Interest is reported three ways and never mixed:
--   expected  = contractual interest on the schedule (future + past)
--   accrued   = scheduled interest whose due date has passed (earned to date)
--   collected = interest actually received through posted payments
-- All views use security_invoker so Row Level Security of base tables applies.
-- =============================================================================

create or replace view public.v_installments with (security_invoker = true) as
select
  i.id, i.organization_id, i.loan_id, i.installment_no, i.due_date, i.opening_balance,
  i.principal_due, i.interest_due, i.charges_due, i.closing_balance,
  i.principal_paid, i.interest_paid, i.charges_paid, i.penalty_due, i.penalty_paid, i.penalty_waived,
  i.interest_waived, i.charges_waived, i.status, i.paid_at, i.last_payment_date,
  (i.principal_due + i.interest_due + i.charges_due) as scheduled_amount,
  (i.principal_paid + i.interest_paid + i.charges_paid + i.penalty_paid) as amount_paid,
  public.lf_inst_base_remaining(i) as base_remaining,
  (i.penalty_due - i.penalty_paid - i.penalty_waived) as penalty_outstanding,
  public.lf_inst_remaining(i) as amount_remaining,
  case
    when i.status in ('paid', 'waived', 'rescheduled') then i.status
    when i.due_date < public.lf_today(i.organization_id) then 'overdue'
    when i.due_date = public.lf_today(i.organization_id) then 'due_today'
    when i.status = 'partially_paid' then 'partially_paid'
    else 'upcoming'
  end as display_status,
  (i.status = 'partially_paid') as is_partial,
  case when i.status in ('pending', 'partially_paid') then greatest(public.lf_today(i.organization_id) - i.due_date, 0) else 0 end as days_overdue,
  l.loan_number, l.status as loan_status, l.borrower_id, l.collector_id, l.loan_officer_id, l.interest_method,
  b.full_name as borrower_name, b.borrower_code, b.mobile_number,
  cu.full_name as collector_name
from public.loan_installments i
join public.loans l on l.id = i.loan_id
join public.borrowers b on b.id = l.borrower_id
left join public.user_profiles cu on cu.id = l.collector_id;

create or replace view public.v_loans with (security_invoker = true) as
select
  l.*,
  b.full_name as borrower_name, b.borrower_code, b.mobile_number as borrower_mobile, b.profile_photo_path as borrower_photo_path,
  p.name as product_name,
  lo.full_name as loan_officer_name,
  co.full_name as collector_name,
  agg.next_due_date, agg.next_due_amount, agg.overdue_amount, agg.overdue_count, agg.oldest_overdue_date,
  case when agg.oldest_overdue_date is null then 0 else public.lf_today(l.organization_id) - agg.oldest_overdue_date end as days_overdue,
  agg.paid_installments, agg.total_installments,
  (select a.status from public.loan_agreements a where a.loan_id = l.id order by a.version desc limit 1) as agreement_status
from public.loans l
join public.borrowers b on b.id = l.borrower_id
left join public.loan_products p on p.id = l.product_id
left join public.user_profiles lo on lo.id = l.loan_officer_id
left join public.user_profiles co on co.id = l.collector_id
left join lateral (
  select
    min(i.due_date) filter (where i.status in ('pending', 'partially_paid') and i.due_date >= public.lf_today(l.organization_id)) as next_due_date,
    (select public.lf_inst_remaining(x) from public.loan_installments x
      where x.loan_id = l.id and x.status in ('pending', 'partially_paid') order by x.installment_no limit 1) as next_due_amount,
    coalesce(sum(public.lf_inst_remaining(i)) filter (where i.status in ('pending', 'partially_paid') and i.due_date < public.lf_today(l.organization_id)), 0) as overdue_amount,
    count(*) filter (where i.status in ('pending', 'partially_paid') and i.due_date < public.lf_today(l.organization_id)) as overdue_count,
    min(i.due_date) filter (where i.status in ('pending', 'partially_paid') and i.due_date < public.lf_today(l.organization_id)) as oldest_overdue_date,
    count(*) filter (where i.status = 'paid') as paid_installments,
    count(*) filter (where i.status <> 'rescheduled') as total_installments
  from public.loan_installments i where i.loan_id = l.id
) agg on true;

-- ---------------------------------------------------------------------------
-- Dashboard
-- ---------------------------------------------------------------------------
create or replace function public.dashboard_summary(p_from date default null, p_to date default null)
returns jsonb language plpgsql volatile security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('dashboard.view');
  v_today date := public.lf_today(v_org);
  v_from date := coalesce(p_from, (date_trunc('month', v_today) - interval '11 months')::date);
  v_to date := coalesce(p_to, v_today);
  v_scope boolean := public.has_permission('loans.view_all');
  v jsonb;
begin
  create temporary table if not exists _dash_loans (id uuid primary key) on commit drop;
  truncate _dash_loans;
  insert into _dash_loans select id from public.loans l
  where l.organization_id = v_org and (v_scope or public.can_access_loan(l.id));

  select jsonb_build_object(
    'range', jsonb_build_object('from', v_from, 'to', v_to, 'today', v_today),
    'financial', (
      select jsonb_build_object(
        'capital_released', coalesce(sum(l.principal) filter (where l.actual_release_date between v_from and v_to), 0),
        'capital_released_all_time', coalesce(sum(l.principal) filter (where l.actual_release_date is not null), 0),
        'outstanding_principal', coalesce(sum(l.outstanding_principal) filter (where l.status in ('active', 'defaulted')), 0),
        'remaining_receivables', coalesce(sum(l.total_outstanding) filter (where l.status in ('active', 'defaulted')), 0),
        'interest_expected_remaining', coalesce(sum(l.outstanding_interest) filter (where l.status in ('active', 'defaulted')), 0),
        'interest_expected_contract', coalesce(sum(l.total_interest) filter (where l.actual_release_date between v_from and v_to), 0)
      ) from public.loans l join _dash_loans d on d.id = l.id),
    'collections', (
      select jsonb_build_object(
        'total', coalesce(sum(p.amount), 0),
        'principal', coalesce(sum(p.principal_applied), 0),
        'interest', coalesce(sum(p.interest_applied), 0),
        'charges', coalesce(sum(p.charges_applied), 0),
        'penalties', coalesce(sum(p.penalty_applied), 0),
        'count', count(*)
      ) from public.loan_payments p join _dash_loans d on d.id = p.loan_id
      where p.status = 'posted' and p.payment_date between v_from and v_to),
    'interest_accrued', (
      select coalesce(sum(i.interest_due - i.interest_waived), 0)
      from public.loan_installments i join _dash_loans d on d.id = i.loan_id
      where i.due_date between v_from and least(v_to, v_today) and i.status <> 'rescheduled'),
    'overdue', (
      select jsonb_build_object(
        'amount', coalesce(sum(public.lf_inst_remaining(i)), 0),
        'loans', count(distinct i.loan_id),
        'installments', count(*))
      from public.loan_installments i join _dash_loans d on d.id = i.loan_id
      join public.loans l on l.id = i.loan_id and l.status in ('active', 'defaulted')
      where i.status in ('pending', 'partially_paid') and i.due_date < v_today),
    'operational', jsonb_build_object(
      'borrowers', (select count(*) from public.borrowers b where b.organization_id = v_org and b.status = 'active'
                    and (public.has_permission('borrowers.view_all') or public.can_access_borrower(b.id))),
      'active_loans', (select count(*) from public.loans l join _dash_loans d on d.id = l.id where l.status = 'active'),
      'pending_applications', (select count(*) from public.loans l join _dash_loans d on d.id = l.id
                               where l.status in ('draft', 'pending_review', 'approved')),
      'awaiting_signature', (select count(*) from public.loans l join _dash_loans d on d.id = l.id
                             where l.status in ('awaiting_borrower_signature', 'awaiting_lender_signature')),
      'ready_for_release', (select count(*) from public.loans l join _dash_loans d on d.id = l.id where l.status in ('ready_for_release', 'fully_signed')),
      'due_today', (select count(distinct i.loan_id) from public.loan_installments i join _dash_loans d on d.id = i.loan_id
                    where i.due_date = v_today and i.status in ('pending', 'partially_paid')),
      'due_today_amount', (select coalesce(sum(public.lf_inst_remaining(i)), 0) from public.loan_installments i join _dash_loans d on d.id = i.loan_id
                    where i.due_date = v_today and i.status in ('pending', 'partially_paid'))
    ),
    'monthly', (
      select coalesce(jsonb_agg(jsonb_build_object(
        'month', to_char(m, 'YYYY-MM'), 'label', to_char(m, 'Mon YY'),
        'released', coalesce((select sum(l.principal) from public.loans l join _dash_loans d on d.id = l.id
                              where date_trunc('month', l.actual_release_date) = m), 0),
        'loans_released', coalesce((select count(*) from public.loans l join _dash_loans d on d.id = l.id
                              where date_trunc('month', l.actual_release_date) = m), 0),
        'collected', coalesce((select sum(p.amount) from public.loan_payments p join _dash_loans d on d.id = p.loan_id
                               where p.status = 'posted' and date_trunc('month', p.payment_date) = m), 0),
        'principal_collected', coalesce((select sum(p.principal_applied) from public.loan_payments p join _dash_loans d on d.id = p.loan_id
                               where p.status = 'posted' and date_trunc('month', p.payment_date) = m), 0),
        'interest_collected', coalesce((select sum(p.interest_applied) from public.loan_payments p join _dash_loans d on d.id = p.loan_id
                               where p.status = 'posted' and date_trunc('month', p.payment_date) = m), 0),
        'other_collected', coalesce((select sum(p.charges_applied + p.penalty_applied) from public.loan_payments p join _dash_loans d on d.id = p.loan_id
                               where p.status = 'posted' and date_trunc('month', p.payment_date) = m), 0),
        'scheduled_due', coalesce((select sum(i.principal_due + i.interest_due + i.charges_due) from public.loan_installments i
                               join _dash_loans d on d.id = i.loan_id
                               where date_trunc('month', i.due_date) = m and i.status <> 'rescheduled'), 0),
        'paid_on_due', coalesce((select sum(i.principal_paid + i.interest_paid + i.charges_paid) from public.loan_installments i
                               join _dash_loans d on d.id = i.loan_id
                               where date_trunc('month', i.due_date) = m and i.status <> 'rescheduled'), 0)
      ) order by m), '[]'::jsonb)
      from generate_series(date_trunc('month', v_from), date_trunc('month', v_to), interval '1 month') m),
    'portfolio_aging', (
      select coalesce(jsonb_agg(jsonb_build_object('bucket', bucket, 'loans', cnt, 'outstanding_principal', amt) order by ord), '[]'::jsonb)
      from (
        select bucket, ord, count(*) as cnt, sum(outstanding_principal) as amt from (
          select l.outstanding_principal,
            case when x.oldest is null then 'Current'
                 when v_today - x.oldest <= 30 then '1–30 days'
                 when v_today - x.oldest <= 60 then '31–60 days'
                 when v_today - x.oldest <= 90 then '61–90 days'
                 else '90+ days' end as bucket,
            case when x.oldest is null then 0
                 when v_today - x.oldest <= 30 then 1
                 when v_today - x.oldest <= 60 then 2
                 when v_today - x.oldest <= 90 then 3
                 else 4 end as ord
          from public.loans l join _dash_loans d on d.id = l.id
          left join lateral (select min(i.due_date) as oldest from public.loan_installments i
                             where i.loan_id = l.id and i.status in ('pending', 'partially_paid') and i.due_date < v_today) x on true
          where l.status in ('active', 'defaulted')
        ) a group by bucket, ord
      ) g),
    'recent_applications', (
      select coalesce(jsonb_agg(r order by r->>'created_at' desc), '[]'::jsonb) from (
        select jsonb_build_object('id', l.id, 'loan_number', l.loan_number, 'borrower_name', b.full_name, 'principal', l.principal,
                                  'status', l.status, 'created_at', l.created_at) as r
        from public.loans l join _dash_loans d on d.id = l.id join public.borrowers b on b.id = l.borrower_id
        where l.status in ('draft', 'pending_review', 'approved', 'awaiting_borrower_signature', 'awaiting_lender_signature', 'fully_signed', 'ready_for_release')
        order by l.created_at desc limit 6) t),
    'upcoming_payments', (
      select coalesce(jsonb_agg(r order by (r->>'due_date'), (r->>'installment_no')::int), '[]'::jsonb) from (
        select jsonb_build_object('installment_id', i.id, 'loan_id', l.id, 'loan_number', l.loan_number, 'borrower_name', b.full_name,
                                  'installment_no', i.installment_no, 'due_date', i.due_date, 'amount', public.lf_inst_remaining(i)) as r
        from public.loan_installments i join _dash_loans d on d.id = i.loan_id
        join public.loans l on l.id = i.loan_id join public.borrowers b on b.id = l.borrower_id
        where i.status in ('pending', 'partially_paid') and i.due_date between v_today and v_today + 7 and l.status = 'active'
        order by i.due_date, i.installment_no limit 8) t),
    'recent_collections', (
      select coalesce(jsonb_agg(r order by r->>'created_at' desc), '[]'::jsonb) from (
        select jsonb_build_object('payment_id', p.id, 'payment_number', p.payment_number, 'loan_id', l.id, 'loan_number', l.loan_number,
                                  'borrower_name', b.full_name, 'amount', p.amount, 'payment_date', p.payment_date, 'method', p.method,
                                  'created_at', p.created_at) as r
        from public.loan_payments p join _dash_loans d on d.id = p.loan_id
        join public.loans l on l.id = p.loan_id join public.borrowers b on b.id = p.borrower_id
        where p.status = 'posted' order by p.created_at desc limit 6) t),
    'overdue_borrowers', (
      select coalesce(jsonb_agg(r order by (r->>'overdue_amount')::numeric desc), '[]'::jsonb) from (
        select jsonb_build_object('loan_id', l.id, 'loan_number', l.loan_number, 'borrower_id', b.id, 'borrower_name', b.full_name,
                                  'mobile_number', b.mobile_number, 'overdue_amount', sum(public.lf_inst_remaining(i)),
                                  'days_overdue', v_today - min(i.due_date)) as r
        from public.loan_installments i join _dash_loans d on d.id = i.loan_id
        join public.loans l on l.id = i.loan_id join public.borrowers b on b.id = l.borrower_id
        where i.status in ('pending', 'partially_paid') and i.due_date < v_today and l.status in ('active', 'defaulted')
        group by l.id, l.loan_number, b.id, b.full_name, b.mobile_number
        order by sum(public.lf_inst_remaining(i)) desc limit 6) t),
    'recently_signed', (
      select coalesce(jsonb_agg(r order by r->>'fully_signed_at' desc), '[]'::jsonb) from (
        select jsonb_build_object('agreement_id', a.id, 'agreement_number', a.agreement_number, 'loan_id', l.id,
                                  'loan_number', l.loan_number, 'borrower_name', b.full_name, 'fully_signed_at', a.fully_signed_at) as r
        from public.loan_agreements a join _dash_loans d on d.id = a.loan_id
        join public.loans l on l.id = a.loan_id join public.borrowers b on b.id = l.borrower_id
        where a.status = 'fully_signed' order by a.fully_signed_at desc limit 6) t)
  ) into v;
  return v;
end $$;

-- ---------------------------------------------------------------------------
-- Reports: get_report(type, from, to, filters) -> {columns, rows, totals}
-- ---------------------------------------------------------------------------
create or replace function public.get_report(p_report text, p_from date default null, p_to date default null, p_filters jsonb default '{}'::jsonb)
returns jsonb language plpgsql volatile security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('reports.view');
  v_today date := public.lf_today(v_org);
  v_from date := coalesce(p_from, date_trunc('month', v_today)::date);
  v_to date := coalesce(p_to, v_today);
  v_scope boolean := public.has_permission('loans.view_all');
  v_collector uuid := nullif(p_filters->>'collector_id', '')::uuid;
  v_cols jsonb;
  v_rows jsonb;
begin
  create temporary table if not exists _rep_loans (id uuid primary key) on commit drop;
  truncate _rep_loans;
  insert into _rep_loans select id from public.loans l
  where l.organization_id = v_org and (v_scope or public.can_access_loan(l.id))
    and (v_collector is null or l.collector_id = v_collector);

  if p_report = 'loans_released' then
    v_cols := '[{"key":"loan_number","label":"Loan #"},{"key":"borrower_name","label":"Borrower"},{"key":"product","label":"Product"},
                {"key":"release_date","label":"Released","type":"date"},{"key":"interest","label":"Rate"},
                {"key":"principal","label":"Principal","type":"money","total":true},{"key":"net_proceeds","label":"Net proceeds","type":"money","total":true},
                {"key":"total_interest","label":"Contract interest","type":"money","total":true},{"key":"total_repayment","label":"Total repayment","type":"money","total":true},
                {"key":"officer","label":"Loan officer"}]';
    select coalesce(jsonb_agg(jsonb_build_object('loan_id', l.id, 'loan_number', l.loan_number, 'borrower_name', b.full_name, 'product', p.name,
             'release_date', l.actual_release_date,
             'interest', trim_scale(l.interest_rate)::text || '% / ' || l.rate_period || ' (' || l.interest_method || ')',
             'principal', l.principal, 'net_proceeds', l.net_proceeds, 'total_interest', l.total_interest,
             'total_repayment', l.total_repayment, 'officer', u.full_name) order by l.actual_release_date, l.loan_number), '[]'::jsonb)
      into v_rows
    from public.loans l join _rep_loans r on r.id = l.id join public.borrowers b on b.id = l.borrower_id
    left join public.loan_products p on p.id = l.product_id left join public.user_profiles u on u.id = l.loan_officer_id
    where l.actual_release_date between v_from and v_to;

  elsif p_report in ('principal_outstanding', 'outstanding_receivables') then
    v_cols := case when p_report = 'principal_outstanding' then
      '[{"key":"loan_number","label":"Loan #"},{"key":"borrower_name","label":"Borrower"},{"key":"status","label":"Status","type":"status"},
        {"key":"maturity_date","label":"Maturity","type":"date"},{"key":"principal","label":"Principal","type":"money","total":true},
        {"key":"principal_paid","label":"Principal paid","type":"money","total":true},
        {"key":"outstanding_principal","label":"Outstanding principal","type":"money","total":true}]'::jsonb
      else
      '[{"key":"loan_number","label":"Loan #"},{"key":"borrower_name","label":"Borrower"},{"key":"status","label":"Status","type":"status"},
        {"key":"outstanding_principal","label":"Principal","type":"money","total":true},{"key":"outstanding_interest","label":"Interest","type":"money","total":true},
        {"key":"outstanding_charges","label":"Charges","type":"money","total":true},{"key":"outstanding_penalties","label":"Penalties","type":"money","total":true},
        {"key":"total_outstanding","label":"Total receivable","type":"money","total":true},{"key":"overdue_amount","label":"Of which overdue","type":"money","total":true}]'::jsonb end;
    select coalesce(jsonb_agg(jsonb_build_object('loan_id', l.id, 'loan_number', l.loan_number, 'borrower_name', b.full_name, 'status', l.status,
             'maturity_date', l.maturity_date, 'principal', l.principal, 'principal_paid', l.principal_paid,
             'outstanding_principal', l.outstanding_principal, 'outstanding_interest', l.outstanding_interest,
             'outstanding_charges', l.outstanding_charges, 'outstanding_penalties', l.outstanding_penalties,
             'total_outstanding', l.total_outstanding,
             'overdue_amount', coalesce((select sum(public.lf_inst_remaining(i)) from public.loan_installments i
                                         where i.loan_id = l.id and i.status in ('pending', 'partially_paid') and i.due_date < v_today), 0))
             order by l.total_outstanding desc), '[]'::jsonb)
      into v_rows
    from public.loans l join _rep_loans r on r.id = l.id join public.borrowers b on b.id = l.borrower_id
    where l.status in ('active', 'defaulted');

  elsif p_report = 'interest_earned' then
    v_cols := '[{"key":"loan_number","label":"Loan #"},{"key":"borrower_name","label":"Borrower"},{"key":"method","label":"Method"},
                {"key":"accrued","label":"Accrued (due in period)","type":"money","total":true},
                {"key":"collected","label":"Collected in period","type":"money","total":true},
                {"key":"waived","label":"Waived/rebated","type":"money","total":true},
                {"key":"expected_remaining","label":"Expected (future)","type":"money","total":true}]';
    select coalesce(jsonb_agg(x order by x->>'loan_number'), '[]'::jsonb) into v_rows from (
      select jsonb_build_object('loan_id', l.id, 'loan_number', l.loan_number, 'borrower_name', b.full_name, 'method', l.interest_method,
        'accrued', coalesce((select sum(i.interest_due) from public.loan_installments i where i.loan_id = l.id
                             and i.due_date between v_from and v_to and i.status <> 'rescheduled'), 0),
        'collected', coalesce((select sum(p.interest_applied) from public.loan_payments p where p.loan_id = l.id and p.status = 'posted'
                               and p.payment_date between v_from and v_to), 0),
        'waived', coalesce((select sum(i.interest_waived) from public.loan_installments i where i.loan_id = l.id
                            and i.due_date between v_from and v_to), 0),
        'expected_remaining', coalesce((select sum(i.interest_due - i.interest_paid - i.interest_waived) from public.loan_installments i
                                        where i.loan_id = l.id and i.due_date > v_to and i.status in ('pending', 'partially_paid')), 0)) as x
      from public.loans l join _rep_loans r on r.id = l.id join public.borrowers b on b.id = l.borrower_id
      where l.status in ('active', 'defaulted', 'completed')
    ) t
    where (x->>'accrued')::numeric <> 0 or (x->>'collected')::numeric <> 0 or (x->>'expected_remaining')::numeric <> 0;

  elsif p_report in ('interest_collected', 'total_collections') then
    v_cols := case when p_report = 'interest_collected' then
      '[{"key":"payment_date","label":"Date","type":"date"},{"key":"payment_number","label":"Payment #"},{"key":"receipt_number","label":"Receipt #"},
        {"key":"loan_number","label":"Loan #"},{"key":"borrower_name","label":"Borrower"},
        {"key":"interest","label":"Interest collected","type":"money","total":true}]'::jsonb
      else
      '[{"key":"payment_date","label":"Date","type":"date"},{"key":"payment_number","label":"Payment #"},{"key":"receipt_number","label":"Receipt #"},
        {"key":"loan_number","label":"Loan #"},{"key":"borrower_name","label":"Borrower"},{"key":"method","label":"Method"},
        {"key":"principal","label":"Principal","type":"money","total":true},{"key":"interest","label":"Interest","type":"money","total":true},
        {"key":"charges","label":"Charges","type":"money","total":true},{"key":"penalties","label":"Penalties","type":"money","total":true},
        {"key":"amount","label":"Total","type":"money","total":true},{"key":"collector","label":"Collector"}]'::jsonb end;
    select coalesce(jsonb_agg(jsonb_build_object('payment_id', p.id, 'loan_id', l.id, 'payment_date', p.payment_date,
             'payment_number', p.payment_number, 'receipt_number', rc.receipt_number, 'loan_number', l.loan_number,
             'borrower_name', b.full_name, 'method', replace(p.method, '_', ' '),
             'principal', p.principal_applied, 'interest', p.interest_applied, 'charges', p.charges_applied,
             'penalties', p.penalty_applied, 'amount', p.amount, 'collector', u.full_name)
             order by p.payment_date, p.payment_number), '[]'::jsonb)
      into v_rows
    from public.loan_payments p join _rep_loans r on r.id = p.loan_id join public.loans l on l.id = p.loan_id
    join public.borrowers b on b.id = p.borrower_id left join public.payment_receipts rc on rc.payment_id = p.id
    left join public.user_profiles u on u.id = p.received_by
    where p.status = 'posted' and p.payment_date between v_from and v_to
      and (p_report <> 'interest_collected' or p.interest_applied > 0);

  elsif p_report = 'overdue_accounts' then
    v_cols := '[{"key":"borrower_name","label":"Borrower"},{"key":"mobile_number","label":"Mobile"},{"key":"loan_number","label":"Loan #"},
                {"key":"oldest_due_date","label":"Oldest due","type":"date"},{"key":"days_overdue","label":"Days overdue","type":"number"},
                {"key":"installments","label":"Installments","type":"number"},
                {"key":"overdue_amount","label":"Overdue amount","type":"money","total":true},
                {"key":"penalties","label":"Penalties (to date)","type":"money","total":true},{"key":"collector","label":"Collector"}]';
    select coalesce(jsonb_agg(x order by (x->>'days_overdue')::int desc), '[]'::jsonb) into v_rows from (
      select jsonb_build_object('loan_id', l.id, 'borrower_name', b.full_name, 'mobile_number', b.mobile_number, 'loan_number', l.loan_number,
        'oldest_due_date', min(i.due_date), 'days_overdue', v_today - min(i.due_date), 'installments', count(*),
        'overdue_amount', sum(public.lf_inst_base_remaining(i)),
        'penalties', sum((i.penalty_due - i.penalty_paid - i.penalty_waived) + public.lf_projected_penalty(i.id, v_today)),
        'collector', u.full_name) as x
      from public.loan_installments i join _rep_loans r on r.id = i.loan_id
      join public.loans l on l.id = i.loan_id join public.borrowers b on b.id = l.borrower_id
      left join public.user_profiles u on u.id = l.collector_id
      where i.status in ('pending', 'partially_paid') and i.due_date < v_today and l.status in ('active', 'defaulted')
      group by l.id, b.full_name, b.mobile_number, l.loan_number, u.full_name
    ) t;

  elsif p_report in ('completed_loans', 'defaulted_loans') then
    v_cols := '[{"key":"loan_number","label":"Loan #"},{"key":"borrower_name","label":"Borrower"},{"key":"release_date","label":"Released","type":"date"},
                {"key":"closed_date","label":"Closed / defaulted","type":"date"},{"key":"principal","label":"Principal","type":"money","total":true},
                {"key":"total_paid","label":"Total paid","type":"money","total":true},{"key":"interest_paid","label":"Interest paid","type":"money","total":true},
                {"key":"total_outstanding","label":"Outstanding","type":"money","total":true}]';
    select coalesce(jsonb_agg(jsonb_build_object('loan_id', l.id, 'loan_number', l.loan_number, 'borrower_name', b.full_name,
             'release_date', l.actual_release_date,
             'closed_date', (case when p_report = 'completed_loans' then l.completed_at else l.defaulted_at end at time zone 'Asia/Manila')::date,
             'principal', l.principal, 'total_paid', l.total_paid, 'interest_paid', l.interest_paid, 'total_outstanding', l.total_outstanding)
             order by l.loan_number), '[]'::jsonb)
      into v_rows
    from public.loans l join _rep_loans r on r.id = l.id join public.borrowers b on b.id = l.borrower_id
    where (p_report = 'completed_loans' and l.status = 'completed'
           and (l.completed_at at time zone 'Asia/Manila')::date between v_from and v_to)
       or (p_report = 'defaulted_loans' and l.status = 'defaulted');

  elsif p_report = 'collector_performance' then
    v_cols := '[{"key":"collector","label":"Collector"},{"key":"loans","label":"Assigned loans","type":"number","total":true},
                {"key":"due","label":"Due in period","type":"money","total":true},{"key":"collected","label":"Collected","type":"money","total":true},
                {"key":"payments","label":"Payments","type":"number","total":true},{"key":"rate","label":"Collection rate","type":"percent"},
                {"key":"overdue","label":"Overdue now","type":"money","total":true}]';
    select coalesce(jsonb_agg(x order by x->>'collector'), '[]'::jsonb) into v_rows from (
      select jsonb_build_object('collector_id', u.id, 'collector', u.full_name,
        'loans', (select count(*) from public.loans l join _rep_loans r on r.id = l.id where l.collector_id = u.id and l.status in ('active', 'defaulted')),
        'due', coalesce((select sum(i.principal_due + i.interest_due + i.charges_due) from public.loan_installments i join public.loans l on l.id = i.loan_id
                         join _rep_loans r on r.id = l.id where l.collector_id = u.id and i.due_date between v_from and v_to and i.status <> 'rescheduled'), 0),
        'collected', coalesce((select sum(p.amount) from public.loan_payments p join _rep_loans r on r.id = p.loan_id
                               where p.received_by = u.id and p.status = 'posted' and p.payment_date between v_from and v_to), 0),
        'payments', (select count(*) from public.loan_payments p join _rep_loans r on r.id = p.loan_id
                     where p.received_by = u.id and p.status = 'posted' and p.payment_date between v_from and v_to),
        'overdue', coalesce((select sum(public.lf_inst_remaining(i)) from public.loan_installments i join public.loans l on l.id = i.loan_id
                             join _rep_loans r on r.id = l.id where l.collector_id = u.id and i.status in ('pending', 'partially_paid')
                             and i.due_date < v_today and l.status in ('active', 'defaulted')), 0)) as x
      from public.user_profiles u
      join public.user_roles ur on ur.user_id = u.id and ur.organization_id = v_org
      where u.organization_id = v_org and (ur.role = 'collector'
        or exists (select 1 from public.loan_payments p where p.received_by = u.id and p.payment_date between v_from and v_to)
        or exists (select 1 from public.loans l where l.collector_id = u.id))
        and (v_collector is null or u.id = v_collector)
    ) t;
    select coalesce(jsonb_agg(x || jsonb_build_object('rate', case when (x->>'due')::numeric > 0
                    then round((x->>'collected')::numeric / (x->>'due')::numeric * 100, 1) else null end)), '[]'::jsonb)
      into v_rows from jsonb_array_elements(v_rows) x;

  elsif p_report = 'borrower_performance' then
    v_cols := '[{"key":"borrower_name","label":"Borrower"},{"key":"borrower_code","label":"Code"},{"key":"loans","label":"Loans","type":"number"},
                {"key":"due_installments","label":"Installments due","type":"number","total":true},{"key":"on_time","label":"Paid on time","type":"number","total":true},
                {"key":"late","label":"Paid late","type":"number","total":true},{"key":"overdue","label":"Currently overdue","type":"number","total":true},
                {"key":"on_time_rate","label":"On-time rate","type":"percent"},{"key":"total_paid","label":"Total paid","type":"money","total":true}]';
    select coalesce(jsonb_agg(x order by x->>'borrower_name'), '[]'::jsonb) into v_rows from (
      select jsonb_build_object('borrower_id', b.id, 'borrower_name', b.full_name, 'borrower_code', b.borrower_code,
        'loans', count(distinct l.id),
        'due_installments', count(i.id) filter (where i.due_date <= v_today and i.status <> 'rescheduled'),
        'on_time', count(i.id) filter (where i.status = 'paid' and i.paid_at <= i.due_date),
        'late', count(i.id) filter (where i.status = 'paid' and i.paid_at > i.due_date),
        'overdue', count(i.id) filter (where i.status in ('pending', 'partially_paid') and i.due_date < v_today),
        'on_time_rate', case when count(i.id) filter (where i.due_date <= v_today and i.status <> 'rescheduled') > 0
                        then round(count(i.id) filter (where i.status = 'paid' and i.paid_at <= i.due_date)::numeric * 100
                                   / count(i.id) filter (where i.due_date <= v_today and i.status <> 'rescheduled'), 1) end,
        'total_paid', (select coalesce(sum(l2.total_paid), 0) from public.loans l2 join _rep_loans r2 on r2.id = l2.id where l2.borrower_id = b.id)) as x
      from public.borrowers b
      join public.loans l on l.borrower_id = b.id join _rep_loans r on r.id = l.id
      left join public.loan_installments i on i.loan_id = l.id
      where l.status in ('active', 'defaulted', 'completed')
      group by b.id, b.full_name, b.borrower_code
    ) t;

  elsif p_report in ('daily_cash', 'monthly_cash') then
    v_cols := '[{"key":"period","label":"Period"},{"key":"payments","label":"Payments","type":"number","total":true},
                {"key":"cash","label":"Cash","type":"money","total":true},{"key":"bank_transfer","label":"Bank transfer","type":"money","total":true},
                {"key":"e_wallet","label":"E-wallet","type":"money","total":true},{"key":"other","label":"Check / other","type":"money","total":true},
                {"key":"collected","label":"Total collected","type":"money","total":true},
                {"key":"released","label":"Loans released (net cash out)","type":"money","total":true},
                {"key":"net","label":"Net cash flow","type":"money","total":true}]';
    select coalesce(jsonb_agg(x order by x->>'sort'), '[]'::jsonb) into v_rows from (
      select jsonb_build_object(
        'sort', to_char(d, 'YYYY-MM-DD'),
        'period', case when p_report = 'daily_cash' then to_char(d, 'Mon DD, YYYY (Dy)') else to_char(d, 'FMMonth YYYY') end,
        'payments', count(p.id),
        'cash', coalesce(sum(p.amount) filter (where p.method = 'cash'), 0),
        'bank_transfer', coalesce(sum(p.amount) filter (where p.method = 'bank_transfer'), 0),
        'e_wallet', coalesce(sum(p.amount) filter (where p.method = 'e_wallet'), 0),
        'other', coalesce(sum(p.amount) filter (where p.method in ('check', 'other')), 0),
        'collected', coalesce(sum(p.amount), 0),
        'released', rel.amount,
        'net', coalesce(sum(p.amount), 0) - rel.amount) as x
      from generate_series(case when p_report = 'daily_cash' then v_from else date_trunc('month', v_from)::date end, v_to,
                           case when p_report = 'daily_cash' then interval '1 day' else interval '1 month' end) d
      left join public.loan_payments p on p.status = 'posted' and p.loan_id in (select id from _rep_loans)
        and p.payment_date >= d::date
        and p.payment_date < (d + case when p_report = 'daily_cash' then interval '1 day' else interval '1 month' end)::date
        and p.payment_date between v_from and v_to
      left join lateral (
        select coalesce(sum(-fl.cash_delta), 0) as amount from public.financial_ledger fl
        where fl.loan_id in (select id from _rep_loans) and fl.entry_type in ('disbursement', 'fee_deducted')
          and fl.entry_date >= d::date
          and fl.entry_date < (d + case when p_report = 'daily_cash' then interval '1 day' else interval '1 month' end)::date
          and fl.entry_date between v_from and v_to
      ) rel on true
      group by d, rel.amount
    ) t;
  else
    raise exception 'Unknown report "%"', p_report using errcode = '22023';
  end if;

  return jsonb_build_object(
    'report', p_report, 'from', v_from, 'to', v_to, 'generated_at', now(),
    'columns', v_cols, 'rows', coalesce(v_rows, '[]'::jsonb),
    'totals', (select coalesce(jsonb_object_agg(c->>'key', (select coalesce(sum((r->>(c->>'key'))::numeric), 0)
                                                             from jsonb_array_elements(coalesce(v_rows, '[]'::jsonb)) r)), '{}'::jsonb)
               from jsonb_array_elements(v_cols) c where (c->>'total')::boolean));
end $$;

-- Collections summary for the Payments & Collections page
create or replace function public.collections_summary()
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('payments.view');
  v_today date := public.lf_today(v_org);
  v_scope boolean := public.has_permission('loans.view_all');
begin
  return (
    with scoped as (select id from public.loans l where l.organization_id = v_org and (v_scope or public.can_access_loan(l.id))),
    pay as (select p.* from public.loan_payments p join scoped s on s.id = p.loan_id where p.status = 'posted')
    select jsonb_build_object(
      'today', v_today,
      'collected_today', coalesce((select sum(amount) from pay where payment_date = v_today), 0),
      'collected_week', coalesce((select sum(amount) from pay where payment_date >= date_trunc('week', v_today)::date), 0),
      'collected_month', coalesce((select sum(amount) from pay where payment_date >= date_trunc('month', v_today)::date), 0),
      'count_today', (select count(*) from pay where payment_date = v_today),
      'due_today', coalesce((select sum(public.lf_inst_remaining(i)) from public.loan_installments i join scoped s on s.id = i.loan_id
                             join public.loans l on l.id = i.loan_id and l.status in ('active', 'defaulted')
                             where i.status in ('pending', 'partially_paid') and i.due_date = v_today), 0),
      'overdue', coalesce((select sum(public.lf_inst_remaining(i)) from public.loan_installments i join scoped s on s.id = i.loan_id
                           join public.loans l on l.id = i.loan_id and l.status in ('active', 'defaulted')
                           where i.status in ('pending', 'partially_paid') and i.due_date < v_today), 0),
      'receivables', coalesce((select sum(l.total_outstanding) from public.loans l join scoped s on s.id = l.id
                               where l.status in ('active', 'defaulted')), 0),
      'upcoming_7d', coalesce((select sum(public.lf_inst_remaining(i)) from public.loan_installments i join scoped s on s.id = i.loan_id
                               join public.loans l on l.id = i.loan_id and l.status = 'active'
                               where i.status in ('pending', 'partially_paid') and i.due_date between v_today + 1 and v_today + 7), 0)
    ));
end $$;


-- ==== 20261114000008_storage_audit_security.sql ====
-- =============================================================================
-- LendFlow — Storage buckets, audit trail, immutability and function privileges
-- =============================================================================

create or replace function public.lf_try_uuid(p text)
returns uuid language plpgsql immutable as $$
begin
  return p::uuid;
exception when others then
  return null;
end $$;

-- ---------------------------------------------------------------------------
-- Private storage buckets
--   borrower-files  {org}/{borrower_id}/{profile|identity|documents}/{file}
--   agreements      {org}/{agreement_id}/{file}        (written by Edge Functions only)
--   payment-proofs  {org}/{loan_id}/{file}
--   receipts        {org}/{payment_id}.pdf             (written by Edge Functions only)
--   branding        {org}/{file}
-- ---------------------------------------------------------------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types) values
  ('borrower-files', 'borrower-files', false, 10485760, array['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'application/pdf']),
  ('agreements', 'agreements', false, 20971520, array['application/pdf', 'image/png']),
  ('payment-proofs', 'payment-proofs', false, 10485760, array['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'application/pdf']),
  ('receipts', 'receipts', false, 5242880, array['application/pdf']),
  ('branding', 'branding', false, 2097152, array['image/jpeg', 'image/png', 'image/webp', 'image/svg+xml'])
on conflict (id) do nothing;

create policy "lf borrower files read" on storage.objects for select to authenticated
  using (bucket_id = 'borrower-files'
         and (storage.foldername(name))[1] = public.current_org_id()::text
         and public.can_access_borrower(public.lf_try_uuid((storage.foldername(name))[2]))
         and ((storage.foldername(name))[3] <> 'identity' or public.has_permission('borrowers.view_sensitive')));
create policy "lf borrower files insert" on storage.objects for insert to authenticated
  with check (bucket_id = 'borrower-files'
              and (storage.foldername(name))[1] = public.current_org_id()::text
              and (public.has_permission('borrowers.create') or public.has_permission('borrowers.edit'))
              and public.can_access_borrower(public.lf_try_uuid((storage.foldername(name))[2]))
              and ((storage.foldername(name))[3] <> 'identity' or public.has_permission('borrowers.view_sensitive')));
create policy "lf borrower files update" on storage.objects for update to authenticated
  using (bucket_id = 'borrower-files'
         and (storage.foldername(name))[1] = public.current_org_id()::text
         and public.has_permission('borrowers.edit')
         and public.can_access_borrower(public.lf_try_uuid((storage.foldername(name))[2])));
create policy "lf borrower files delete" on storage.objects for delete to authenticated
  using (bucket_id = 'borrower-files'
         and (storage.foldername(name))[1] = public.current_org_id()::text
         and public.has_permission('borrowers.edit')
         and public.can_access_borrower(public.lf_try_uuid((storage.foldername(name))[2])));

create policy "lf agreements read" on storage.objects for select to authenticated
  using (bucket_id = 'agreements'
         and (storage.foldername(name))[1] = public.current_org_id()::text
         and public.has_permission('agreements.view')
         and exists (select 1 from public.loan_agreements a
                     where a.id = public.lf_try_uuid((storage.foldername(name))[2]) and public.can_access_loan(a.loan_id)));

create policy "lf payment proofs read" on storage.objects for select to authenticated
  using (bucket_id = 'payment-proofs'
         and (storage.foldername(name))[1] = public.current_org_id()::text
         and public.has_permission('payments.view')
         and public.can_access_loan(public.lf_try_uuid((storage.foldername(name))[2])));
create policy "lf payment proofs insert" on storage.objects for insert to authenticated
  with check (bucket_id = 'payment-proofs'
              and (storage.foldername(name))[1] = public.current_org_id()::text
              and public.has_permission('payments.record')
              and public.can_access_loan(public.lf_try_uuid((storage.foldername(name))[2])));

create policy "lf receipts read" on storage.objects for select to authenticated
  using (bucket_id = 'receipts'
         and (storage.foldername(name))[1] = public.current_org_id()::text
         and public.has_permission('payments.view'));

create policy "lf branding read" on storage.objects for select to authenticated
  using (bucket_id = 'branding' and (storage.foldername(name))[1] = public.current_org_id()::text);
create policy "lf branding insert" on storage.objects for insert to authenticated
  with check (bucket_id = 'branding' and (storage.foldername(name))[1] = public.current_org_id()::text
              and public.has_permission('settings.manage'));
create policy "lf branding update" on storage.objects for update to authenticated
  using (bucket_id = 'branding' and (storage.foldername(name))[1] = public.current_org_id()::text
         and public.has_permission('settings.manage'));
create policy "lf branding delete" on storage.objects for delete to authenticated
  using (bucket_id = 'branding' and (storage.foldername(name))[1] = public.current_org_id()::text
         and public.has_permission('settings.manage'));

-- ---------------------------------------------------------------------------
-- Generic audit trail
-- ---------------------------------------------------------------------------
create or replace function public.lf_audit()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_old jsonb;
  v_new jsonb;
  v_changed text[];
  v_org uuid;
  v_id text;
  v_name text;
  v_ignore text[] := array['updated_at', 'row_version', 'last_seen_at'];
begin
  if tg_op in ('UPDATE', 'DELETE') then v_old := to_jsonb(old); end if;
  if tg_op in ('UPDATE', 'INSERT') then v_new := to_jsonb(new); end if;

  if tg_op = 'UPDATE' then
    select array_agg(k) into v_changed
    from jsonb_object_keys(v_new) k
    where (v_new -> k) is distinct from (v_old -> k) and not (k = any (v_ignore));
    if v_changed is null then return new; end if;
  end if;

  -- mask sensitive identity numbers in the audit trail
  if tg_table_name = 'borrower_identities' then
    v_old := case when v_old is null then null else v_old || jsonb_build_object('id_number', '•••' || right(v_old->>'id_number', 4)) end;
    v_new := case when v_new is null then null else v_new || jsonb_build_object('id_number', '•••' || right(v_new->>'id_number', 4)) end;
  end if;

  v_org := coalesce((coalesce(v_new, v_old) ->> 'organization_id')::uuid,
                    case when tg_table_name = 'organizations' then (coalesce(v_new, v_old) ->> 'id')::uuid end);
  v_id := coalesce(coalesce(v_new, v_old) ->> 'id', coalesce(v_new, v_old) ->> 'borrower_id', coalesce(v_new, v_old) ->> 'organization_id');
  select full_name into v_name from public.user_profiles where id = auth.uid();

  insert into public.audit_logs (organization_id, actor_id, actor_name, action, table_name, record_id, old_data, new_data, changed_fields)
  values (v_org, auth.uid(), v_name, lower(tg_op), tg_table_name, v_id,
          case when tg_op = 'INSERT' then null else v_old end,
          case when tg_op = 'DELETE' then null else v_new end,
          v_changed);
  return coalesce(new, old);
end $$;

do $$
declare t text;
begin
  foreach t in array array[
    'organizations', 'user_profiles', 'user_roles', 'role_permissions', 'system_settings',
    'borrowers', 'borrower_identities', 'borrower_documents', 'guarantors',
    'loan_products', 'loan_agreement_templates', 'loan_agreement_template_versions',
    'loans', 'loan_agreements', 'agreement_signatures', 'agreement_signing_sessions',
    'loan_payments', 'payment_receipts', 'loan_charges', 'collector_assignments'
  ] loop
    execute format('create trigger %I after insert or update or delete on public.%I for each row execute function public.lf_audit()',
      t || '_audit', t);
  end loop;
end $$;

-- ---------------------------------------------------------------------------
-- Immutability guards for financial and document records
-- ---------------------------------------------------------------------------
create or replace function public.lf_block_mutation()
returns trigger language plpgsql as $$
begin
  raise exception '% records are immutable and cannot be %d. Use a reversal or adjustment instead.',
    tg_table_name, lower(tg_op) using errcode = '42501';
end $$;

do $$
declare t text;
begin
  foreach t in array array[
    'financial_ledger', 'payment_allocations', 'agreement_audit_events', 'agreement_signatures',
    'loan_terms_snapshots', 'loan_agreement_template_versions', 'audit_logs', 'loan_penalties', 'loan_charges'
  ] loop
    execute format('create trigger %I before update or delete on public.%I for each row execute function public.lf_block_mutation()',
      t || '_immutable', t);
  end loop;
  foreach t in array array['loans', 'loan_payments', 'payment_receipts', 'loan_agreements', 'loan_installments', 'borrowers', 'loan_applications'] loop
    execute format('create trigger %I before delete on public.%I for each row execute function public.lf_block_mutation()',
      t || '_no_delete', t);
  end loop;
end $$;

create or replace function public.lf_payment_guard()
returns trigger language plpgsql as $$
begin
  if new.amount <> old.amount or new.payment_date <> old.payment_date or new.loan_id <> old.loan_id
     or new.borrower_id <> old.borrower_id or new.idempotency_key <> old.idempotency_key
     or new.payment_number <> old.payment_number or new.method <> old.method
     or new.reference_no is distinct from old.reference_no or new.payment_type <> old.payment_type then
    raise exception 'Posted payments cannot be edited. Reverse the payment and record a new one.' using errcode = '42501';
  end if;
  if old.status = 'reversed' and (new.status <> 'reversed' or new.principal_applied <> old.principal_applied) then
    raise exception 'A reversed payment cannot be changed' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger loan_payments_guard before update on public.loan_payments
  for each row execute function public.lf_payment_guard();

create or replace function public.lf_receipt_guard()
returns trigger language plpgsql as $$
begin
  if new.receipt_number <> old.receipt_number or new.data <> old.data or new.payment_id <> old.payment_id then
    raise exception 'Receipts are immutable' using errcode = '42501';
  end if;
  if old.is_void and not new.is_void then
    raise exception 'A voided receipt cannot be reinstated' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger payment_receipts_guard before update on public.payment_receipts
  for each row execute function public.lf_receipt_guard();

create or replace function public.lf_agreement_guard()
returns trigger language plpgsql as $$
begin
  if old.status = 'fully_signed' and (
       new.status <> old.status or new.signed_pdf_path is distinct from old.signed_pdf_path
       or new.signed_pdf_hash is distinct from old.signed_pdf_hash
       or new.current_pdf_path is distinct from old.current_pdf_path
       or new.unsigned_pdf_path is distinct from old.unsigned_pdf_path
       or new.terms_snapshot_id <> old.terms_snapshot_id
       or new.rendered_body is distinct from old.rendered_body
       or new.placeholders <> old.placeholders) then
    raise exception 'A fully signed agreement cannot be modified. Create an amendment (new agreement version) instead.'
      using errcode = '42501';
  end if;
  if old.unsigned_pdf_path is not null and (new.unsigned_pdf_path is distinct from old.unsigned_pdf_path
                                            or new.unsigned_pdf_hash is distinct from old.unsigned_pdf_hash) then
    raise exception 'The original unsigned agreement cannot be replaced' using errcode = '42501';
  end if;
  if new.terms_snapshot_id <> old.terms_snapshot_id or new.agreement_number <> old.agreement_number or new.version <> old.version then
    raise exception 'Agreement identity and terms snapshot are immutable' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger loan_agreements_guard before update on public.loan_agreements
  for each row execute function public.lf_agreement_guard();

-- Loans: contractual terms are frozen once the loan is active
create or replace function public.lf_loan_terms_guard()
returns trigger language plpgsql as $$
begin
  if old.status in ('active', 'completed', 'defaulted') and (
       new.principal <> old.principal or new.interest_rate <> old.interest_rate or new.interest_method <> old.interest_method
       or new.term_count <> old.term_count or new.total_interest <> old.total_interest or new.total_repayment <> old.total_repayment
       or new.borrower_id <> old.borrower_id or new.release_date <> old.release_date) then
    raise exception 'Contractual terms of an active loan cannot be changed; use a documented reschedule (amendment)'
      using errcode = '42501';
  end if;
  if new.organization_id <> old.organization_id or new.loan_number <> old.loan_number then
    raise exception 'Loan identity is immutable' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger loans_terms_guard before update on public.loans
  for each row execute function public.lf_loan_terms_guard();

-- ---------------------------------------------------------------------------
-- Function privileges: internal/service-only functions are not callable by API roles
-- ---------------------------------------------------------------------------
do $$
declare f text;
begin
  foreach f in array array[
    'public.lf_next_ref(uuid, text, text, boolean)',
    'public.lf_post_ledger(uuid, text, date, numeric, numeric, numeric, numeric, numeric, numeric, text, uuid)',
    'public.lf_system_note(uuid, uuid, uuid, text)',
    'public.lf_notify_roles(uuid, public.app_role[], text, text, text, text)',
    'public.lf_notify_user(uuid, uuid, text, text, text, text)',
    'public.lf_assess_penalties(uuid, date)',
    'public.lf_projected_penalty(uuid, date)',
    'public.lf_penalty_increment(public.loan_installments, public.loans, date)',
    'public.lf_refresh_installment(uuid, date)',
    'public.lf_void_open_agreements(uuid, text)',
    'public.lf_settlement_quote(uuid, date)',
    'public.lf_receipt_data(uuid, text)',
    'public.lf_agreement_render_data(uuid)',
    'public.lf_agreement_event(uuid, uuid, text, text, uuid, text, text, text, jsonb)',
    'public.agreement_complete(uuid, text, text, jsonb, integer, jsonb, text)',
    'public.agreement_fail(uuid, text)',
    'public.signing_lookup(text)',
    'public.signing_mark_opened(uuid, text, text)',
    'public.signing_verify(uuid, text, text, text)',
    'public.signature_commit(jsonb)',
    'public.agreement_set_pdf(uuid, text, text, integer)',
    'public.agreement_log_download(uuid, uuid, text, uuid, text, text)',
    'public.lf_rate_limit(text, integer, integer)',
    'public.lf_audit()', 'public.lf_borrowers_guard()', 'public.lf_org_child_guard()'
  ] loop
    execute format('revoke execute on function %s from public, anon, authenticated', f);
    execute format('grant execute on function %s to service_role', f);
  end loop;
end $$;


-- ==== 20261114000009_onboarding_admin.sql ====
-- =============================================================================
-- LendFlow — Onboarding, staff administration, settings and default content
-- =============================================================================

-- New auth user -> profile. Organization/role come ONLY from app_metadata,
-- which only the service role (admin API) can set.
create or replace function public.lf_handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_try_uuid(new.raw_app_meta_data ->> 'organization_id');
  v_role text := new.raw_app_meta_data ->> 'role';
begin
  if v_org is not null and not exists (select 1 from public.organizations where id = v_org) then
    v_org := null;
  end if;
  insert into public.user_profiles (id, organization_id, full_name, email, phone)
  values (new.id, v_org, coalesce(nullif(new.raw_user_meta_data ->> 'full_name', ''), split_part(new.email, '@', 1)),
          new.email, nullif(new.raw_user_meta_data ->> 'phone', ''))
  on conflict (id) do nothing;
  if v_org is not null and v_role in ('admin', 'loan_officer', 'collector', 'owner') then
    insert into public.user_roles (user_id, organization_id, role) values (new.id, v_org, v_role::public.app_role)
    on conflict (user_id, organization_id) do update set role = excluded.role;
  end if;
  return new;
end $$;

create trigger lf_on_auth_user_created after insert on auth.users
  for each row execute function public.lf_handle_new_user();

-- ---------------------------------------------------------------------------
-- Default permissions per role
-- ---------------------------------------------------------------------------
create or replace function public.lf_default_role_permissions(p_role public.app_role)
returns text[] language sql immutable as $$
  select case p_role
    when 'owner' then public.lf_all_permissions()
    when 'admin' then array[
      'dashboard.view', 'borrowers.view', 'borrowers.view_all', 'borrowers.create', 'borrowers.edit', 'borrowers.archive',
      'borrowers.view_sensitive', 'loans.view', 'loans.view_all', 'loans.create', 'loans.approve', 'loans.release', 'loans.cancel',
      'loans.manage', 'agreements.view', 'agreements.generate', 'agreements.sign_lender', 'payments.view', 'payments.record',
      'payments.reverse', 'payments.adjust', 'collections.assign', 'reports.view']
    when 'loan_officer' then array[
      'dashboard.view', 'borrowers.view', 'borrowers.create', 'borrowers.edit', 'borrowers.view_sensitive',
      'loans.view', 'loans.create', 'agreements.view', 'agreements.generate', 'payments.view']
    when 'collector' then array[
      'dashboard.view', 'borrowers.view', 'loans.view', 'payments.view', 'payments.record']
  end
$$;

create or replace function public.lf_seed_role_permissions(p_org uuid)
returns void language sql security definer set search_path = public as $$
  insert into public.role_permissions (organization_id, role, permission)
  select p_org, r.role, unnest(public.lf_default_role_permissions(r.role))
  from (select unnest(enum_range(null::public.app_role)) as role) r
  on conflict do nothing
$$;

-- ---------------------------------------------------------------------------
-- Default agreement template (Philippine consumer loan agreement)
-- ---------------------------------------------------------------------------
create or replace function public.lf_default_template_body()
returns text language sql immutable as $body$
  select $tpl$This LOAN AGREEMENT (the "**Agreement**") is made and entered into on **{{agreement_date}}** by and between:

**{{lender_name}}**, with business address at {{lender_address}}, hereinafter referred to as the "**LENDER**";

– and –

**{{borrower_name}}**, of legal age, residing at {{borrower_address}}, presenting {{borrower_id_type}} No. {{borrower_id_number}}, hereinafter referred to as the "**BORROWER**".

The LENDER and the BORROWER are each referred to as a "Party" and together as the "Parties".

## 1. The Loan

Subject to the terms of this Agreement, the LENDER agrees to lend and the BORROWER agrees to borrow the principal amount of **{{loan_amount_words}} ({{loan_amount}})** (the "**Loan**"), under Loan Reference No. {{loan_number}}. The net proceeds of the Loan released to the BORROWER amount to **{{net_proceeds}}**, after deduction of any fees and charges stated in the Disclosure Statement below.

## 2. Interest

The Loan shall bear interest at the nominal rate of **{{interest_rate}}**, computed using the **{{interest_method}}** method, for a term of **{{loan_duration}}**. Total interest over the term is **{{total_interest}}**. The effective interest rate, which reflects the true cost of the Loan, is disclosed below.

## 3. Disclosure Statement (Truth in Lending Act, R.A. No. 3765)

{{disclosure_table}}

## 4. Repayment

The BORROWER shall pay the total amount of **{{total_repayment}}** in **{{installment_count}} {{payment_frequency}} installments** of **{{installment_amount}}** each (the final installment being adjusted for rounding), beginning on **{{first_payment_date}}** and ending on **{{last_payment_date}}**, in accordance with the following Repayment Schedule, which forms an integral part of this Agreement:

{{repayment_schedule}}

## 5. Manner of Payment

Payments shall be made on or before each due date in cash at the LENDER's office, by bank transfer, or through e-wallet channels designated by the LENDER. Each payment shall be acknowledged with an official receipt. Payments shall be applied in the following order: {{allocation_order}}.

## 6. Late Payment

{{penalty_terms}}

## 7. Early Repayment

The BORROWER may prepay the Loan in full or in part at any time without prepayment penalty. Upon full early settlement, the BORROWER shall pay the outstanding principal, interest earned up to the date of settlement, unpaid charges and penalties, if any. Unearned interest for periods after the settlement date shall not be collected.

## 8. Events of Default

The BORROWER shall be in default upon any of the following: (a) failure to pay any installment within thirty (30) days from its due date; (b) any representation made by the BORROWER being found false or misleading in any material respect; (c) the BORROWER becoming insolvent or unable to pay debts as they fall due; or (d) violation of any other obligation under this Agreement.

## 9. Consequences of Default

Upon default, the LENDER may, after written notice to the BORROWER, declare the entire outstanding balance immediately due and demandable. Collection shall be conducted in a fair and lawful manner, without the use of threats, harassment, or disclosure of the BORROWER's debt to unauthorized third parties, in accordance with applicable regulations on fair debt collection practices.

## 10. Representations of the Borrower

The BORROWER represents that all information and documents provided to the LENDER are true, correct and complete; that the BORROWER has read and understood this Agreement, including the Disclosure Statement and Repayment Schedule; and that the BORROWER enters into this Agreement voluntarily.

## 11. Data Privacy

The BORROWER consents to the collection, use, processing and storage of personal information by the LENDER for purposes of evaluating, administering and collecting the Loan, in accordance with the Data Privacy Act of 2012 (R.A. No. 10173). Personal data shall be protected by reasonable organizational, physical and technical security measures and shall not be shared with third parties except as required by law or as necessary for the purposes stated herein. The BORROWER may exercise the rights of a data subject by contacting the LENDER.

## 12. Electronic Signatures

The Parties agree that this Agreement may be signed electronically and that electronic signatures shall have the same legal effect as handwritten signatures, pursuant to the Electronic Commerce Act of 2000 (R.A. No. 8792) and its implementing rules. A record of each signing event, including the date, time and document fingerprint (SHA-256), is kept by the LENDER.

## 13. Dispute Resolution

The Parties shall first endeavor to settle any dispute arising from this Agreement amicably. Failing amicable settlement, any action shall be filed exclusively in the proper courts of {{lender_city}}, to the exclusion of all other venues.

## 14. Miscellaneous

This Agreement constitutes the entire agreement between the Parties regarding the Loan. Any amendment shall be made in writing through a new version of this Agreement signed by both Parties. If any provision is held invalid, the remaining provisions shall remain in full force and effect.

IN WITNESS WHEREOF, the Parties have signed this Agreement on the date first written above.

{{signature_block}}$tpl$
$body$;

create or replace function public.lf_seed_defaults(p_org uuid)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_tpl uuid;
  v_ver uuid;
begin
  insert into public.system_settings (organization_id, settings) values (p_org, '{}'::jsonb) on conflict do nothing;

  insert into public.loan_agreement_templates (organization_id, name, description, is_default, is_active, created_by)
  values (p_org, 'Standard Loan Agreement', 'Philippine consumer loan agreement with Truth in Lending disclosure', true, true, auth.uid())
  returning id into v_tpl;
  insert into public.loan_agreement_template_versions (organization_id, template_id, version_no, title, body, settings, change_note, created_by)
  values (p_org, v_tpl, 1, 'Loan Agreement', public.lf_default_template_body(),
          '{"show_logo": true, "show_header": true, "footer_text": "This document is electronically generated and signed.", "schedule_font_size": 8}'::jsonb,
          'Initial version', auth.uid())
  returning id into v_ver;
  update public.loan_agreement_templates set current_version_id = v_ver where id = v_tpl;

  insert into public.loan_products (organization_id, name, code, description, interest_method, interest_rate, rate_period,
    repayment_frequency, min_amount, max_amount, min_term, max_term, default_term, processing_fee_type, processing_fee_value,
    charges_treatment, penalty_enabled, penalty_type, penalty_rate, penalty_grace_days, agreement_template_id, created_by)
  values
    (p_org, 'Personal Loan', 'PERSONAL', 'Flat-rate personal loan payable monthly', 'flat', 2, 'monthly', 'monthly',
     5000, 200000, 3, 24, 12, 'percent', 2, 'deducted', true, 'percent_monthly', 3, 3, v_tpl, auth.uid()),
    (p_org, 'Business Loan', 'BUSINESS', 'Diminishing-balance working capital loan', 'diminishing', 24, 'yearly', 'monthly',
     20000, 1000000, 6, 36, 12, 'percent', 1.5, 'deducted', true, 'percent_monthly', 2, 5, v_tpl, auth.uid()),
    (p_org, 'Salary Loan', 'SALARY', 'Short-term loan for employed borrowers', 'flat', 1.5, 'monthly', 'monthly',
     3000, 100000, 1, 12, 6, 'fixed', 300, 'added', true, 'fixed', 200, 3, v_tpl, auth.uid()),
    (p_org, 'Micro Daily Loan', 'MICRO-DAILY', 'Daily-collection microloan for small vendors', 'flat', 2, 'monthly', 'daily',
     1000, 30000, 30, 120, 60, 'none', 0, 'deducted', true, 'percent_daily', 0.1, 1, v_tpl, auth.uid());
end $$;

-- ---------------------------------------------------------------------------
-- Create organization (first-run onboarding)
-- ---------------------------------------------------------------------------
create or replace function public.create_organization(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_org uuid;
begin
  if v_uid is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  if exists (select 1 from public.user_profiles where id = v_uid and organization_id is not null) then
    raise exception 'You already belong to an organization' using errcode = '22023';
  end if;
  if coalesce(trim(p->>'name'), '') = '' then raise exception 'Organization name is required' using errcode = '22023'; end if;

  insert into public.organizations (name, legal_name, address, city, province, zip_code, contact_email, contact_phone, registration_no, tin)
  values (trim(p->>'name'), nullif(p->>'legal_name', ''), nullif(p->>'address', ''), nullif(p->>'city', ''), nullif(p->>'province', ''),
          nullif(p->>'zip_code', ''), nullif(p->>'contact_email', ''), nullif(p->>'contact_phone', ''),
          nullif(p->>'registration_no', ''), nullif(p->>'tin', ''))
  returning id into v_org;

  insert into public.user_profiles (id, organization_id, full_name, email)
  values (v_uid, v_org, coalesce(nullif(p->>'owner_name', ''), ''), (select email from auth.users where id = v_uid))
  on conflict (id) do update set organization_id = v_org,
    full_name = coalesce(nullif(p->>'owner_name', ''), public.user_profiles.full_name);
  insert into public.user_roles (user_id, organization_id, role) values (v_uid, v_org, 'owner');

  perform public.lf_seed_role_permissions(v_org);
  perform public.lf_seed_defaults(v_org);
  return jsonb_build_object('organization_id', v_org);
end $$;

-- ---------------------------------------------------------------------------
-- Settings & permissions administration
-- ---------------------------------------------------------------------------
create or replace function public.update_settings(p_section text, p_values jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('settings.manage');
  v_allowed text[] := array['interest', 'repayment', 'penalty', 'agreement', 'security', 'receipt'];
  v_current jsonb;
begin
  if not (p_section = any (v_allowed)) then raise exception 'Unknown settings section "%"', p_section using errcode = '22023'; end if;
  if jsonb_typeof(p_values) <> 'object' then raise exception 'Settings must be an object' using errcode = '22023'; end if;
  if p_section = 'interest' and coalesce((p_values->>'flat_enabled')::boolean, true) = false
     and coalesce((p_values->>'diminishing_enabled')::boolean, true) = false then
    raise exception 'At least one interest method must remain enabled' using errcode = '22023';
  end if;
  if p_section = 'repayment' and p_values ? 'allocation_order' and not (
       jsonb_typeof(p_values->'allocation_order') = 'array' and jsonb_array_length(p_values->'allocation_order') = 4
       and p_values->'allocation_order' ?& array['penalty', 'charges', 'interest', 'principal']) then
    raise exception 'Allocation order must list penalty, charges, interest and principal exactly once' using errcode = '22023';
  end if;
  if p_section = 'agreement' and p_values ? 'signing_order' and p_values->>'signing_order' not in ('borrower_first', 'lender_first', 'any') then
    raise exception 'Invalid signing order' using errcode = '22023';
  end if;
  insert into public.system_settings (organization_id, settings) values (v_org, '{}'::jsonb) on conflict do nothing;
  select settings into v_current from public.system_settings where organization_id = v_org for update;
  update public.system_settings
  set settings = jsonb_set(v_current, array[p_section], coalesce(v_current -> p_section, '{}'::jsonb) || p_values, true),
      updated_by = auth.uid(), updated_at = now()
  where organization_id = v_org;
  return public.lf_settings(v_org);
end $$;

create or replace function public.set_role_permissions(p_role public.app_role, p_permissions text[])
returns void language plpgsql security definer set search_path = public as $$
declare v_org uuid := public.lf_require('users.manage');
begin
  if p_role = 'owner' then raise exception 'Owner permissions cannot be changed' using errcode = '22023'; end if;
  if exists (select 1 from unnest(p_permissions) x where not (x = any (public.lf_all_permissions()))) then
    raise exception 'Unknown permission in list' using errcode = '22023';
  end if;
  if 'users.manage' = any (p_permissions) and public.current_app_role() <> 'owner' then
    raise exception 'Only the owner can grant user management' using errcode = '42501';
  end if;
  delete from public.role_permissions where organization_id = v_org and role = p_role;
  insert into public.role_permissions (organization_id, role, permission)
  select v_org, p_role, x from unnest(p_permissions) x on conflict do nothing;
end $$;

create or replace function public.reset_role_permissions(p_role public.app_role)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform public.set_role_permissions(p_role, public.lf_default_role_permissions(p_role));
end $$;

create or replace function public.update_my_profile(p_full_name text, p_phone text default null)
returns void language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  if coalesce(trim(p_full_name), '') = '' then raise exception 'Name is required' using errcode = '22023'; end if;
  update public.user_profiles set full_name = trim(p_full_name), phone = nullif(trim(p_phone), ''), last_seen_at = now()
  where id = auth.uid();
end $$;

create or replace function public.set_user_role(p_user_id uuid, p_role public.app_role)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('users.manage');
  v_old public.app_role;
begin
  select role into v_old from public.user_roles where user_id = p_user_id and organization_id = v_org;
  if not found then raise exception 'User not found in this organization'; end if;
  if p_user_id = auth.uid() then raise exception 'You cannot change your own role' using errcode = '22023'; end if;
  if (p_role = 'owner' or v_old = 'owner') and public.current_app_role() <> 'owner' then
    raise exception 'Only an owner can assign or remove the owner role' using errcode = '42501';
  end if;
  if v_old = 'owner' and p_role <> 'owner' and (select count(*) from public.user_roles where organization_id = v_org and role = 'owner') <= 1 then
    raise exception 'The organization must keep at least one owner' using errcode = '22023';
  end if;
  update public.user_roles set role = p_role where user_id = p_user_id and organization_id = v_org;
end $$;

create or replace function public.set_user_active(p_user_id uuid, p_active boolean)
returns void language plpgsql security definer set search_path = public as $$
declare v_org uuid := public.lf_require('users.manage');
begin
  if p_user_id = auth.uid() then raise exception 'You cannot deactivate yourself' using errcode = '22023'; end if;
  if not exists (select 1 from public.user_profiles where id = p_user_id and organization_id = v_org) then
    raise exception 'User not found in this organization';
  end if;
  if not p_active and exists (select 1 from public.user_roles where user_id = p_user_id and organization_id = v_org and role = 'owner')
     and public.current_app_role() <> 'owner' then
    raise exception 'Only an owner can deactivate another owner' using errcode = '42501';
  end if;
  update public.user_profiles set is_active = p_active where id = p_user_id;
  if not p_active then
    update public.collector_assignments set is_active = false, unassigned_at = now()
    where collector_id = p_user_id and is_active;
  end if;
end $$;

-- Used by the admin-users Edge Function (service role) to attach an existing auth user
create or replace function public.lf_attach_user(p_user_id uuid, p_org uuid, p_role public.app_role, p_full_name text, p_phone text)
returns void language plpgsql security definer set search_path = public as $$
begin
  insert into public.user_profiles (id, organization_id, full_name, email, phone)
  values (p_user_id, p_org, p_full_name, (select email from auth.users where id = p_user_id), p_phone)
  on conflict (id) do update set organization_id = p_org, full_name = coalesce(nullif(p_full_name, ''), public.user_profiles.full_name),
    phone = coalesce(p_phone, public.user_profiles.phone), is_active = true;
  insert into public.user_roles (user_id, organization_id, role) values (p_user_id, p_org, p_role)
  on conflict (user_id, organization_id) do update set role = excluded.role;
end $$;
revoke execute on function public.lf_attach_user(uuid, uuid, public.app_role, text, text) from public, anon, authenticated;
grant execute on function public.lf_attach_user(uuid, uuid, public.app_role, text, text) to service_role;

create or replace function public.mark_notifications_read(p_ids uuid[] default null)
returns void language sql security definer set search_path = public as $$
  update public.notifications set read_at = now()
  where user_id = auth.uid() and read_at is null and (p_ids is null or id = any (p_ids))
$$;

revoke execute on function public.lf_seed_defaults(uuid) from public, anon, authenticated;
revoke execute on function public.lf_seed_role_permissions(uuid) from public, anon, authenticated;
revoke execute on function public.lf_handle_new_user() from public, anon, authenticated;


-- ==== 20261114000010_demo_data.sql ====
-- =============================================================================
-- LendFlow — Realistic sample data for development / demos (owner only)
-- Creates Filipino borrowers, loans in every pipeline state and payment histories
-- posted through the real record_payment engine (penalties, partials, settlement).
-- Demo loans are activated WITHOUT an agreement (clearly noted) — never use for real data.
-- =============================================================================

-- Mirrors release_loan() but skips the signed-agreement requirement (demo only).
create or replace function public.lf_demo_activate(p_loan_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare
  l public.loans%rowtype;
  v_params jsonb;
  v_calc jsonb;
  v_snap uuid;
  r jsonb;
begin
  select * into l from public.loans where id = p_loan_id for update;
  if not found then raise exception 'Loan not found'; end if;
  if not l.is_demo then raise exception 'Only demo loans can be activated without an agreement'; end if;
  if l.status not in ('draft', 'pending_review', 'approved') then raise exception 'Demo loan is already %', l.status; end if;

  v_params := public.lf_loan_params(l);
  v_calc := public.compute_loan_schedule(v_params);
  insert into public.loan_terms_snapshots (organization_id, loan_id, version, kind, terms, summary, schedule, terms_hash, reason, created_by)
  values (l.organization_id, l.id, coalesce((select max(version) from public.loan_terms_snapshots where loan_id = l.id), 0) + 1,
          'original', v_params, v_calc->'summary', v_calc->'schedule',
          public.lf_sha256_hex((v_params || jsonb_build_object('schedule', v_calc->'schedule'))::text),
          'Demo data — activated without a signed agreement', auth.uid())
  returning id into v_snap;

  for r in select * from jsonb_array_elements(v_calc->'schedule') loop
    insert into public.loan_installments (organization_id, loan_id, installment_no, due_date, opening_balance,
      principal_due, interest_due, charges_due, closing_balance, snapshot_version)
    values (l.organization_id, l.id, (r->>'installment_no')::integer, (r->>'due_date')::date, (r->>'opening_balance')::numeric,
      (r->>'principal')::numeric, (r->>'interest')::numeric, (r->>'charges')::numeric, (r->>'closing_balance')::numeric, 1);
  end loop;

  if l.processing_fee > 0 then
    insert into public.loan_charges (organization_id, loan_id, charge_type, description, amount, treatment, created_by)
    values (l.organization_id, l.id, 'processing_fee', 'Processing fee', l.processing_fee, l.charges_treatment, auth.uid());
  end if;

  perform public.lf_post_ledger(l.id, 'disbursement', l.release_date, l.principal, -l.principal, l.principal, 0, 0, 0,
    'Loan proceeds released (demo data)');
  if l.total_charges - l.financed_charges > 0 then
    perform public.lf_post_ledger(l.id, 'fee_deducted', l.release_date, l.total_charges - l.financed_charges,
      l.total_charges - l.financed_charges, 0, 0, 0, 0, 'Fees and charges deducted from proceeds');
  end if;
  perform public.lf_post_ledger(l.id, 'loan_booked', l.release_date, l.total_interest + l.financed_charges, 0,
    0, l.total_interest, l.financed_charges, 0, 'Contractual interest and financed charges booked');

  update public.loans set
    status = 'active',
    submitted_at = coalesce(submitted_at, l.release_date::timestamp at time zone 'Asia/Manila'),
    submitted_by = coalesce(submitted_by, auth.uid()),
    approved_at = coalesce(approved_at, l.release_date::timestamp at time zone 'Asia/Manila'),
    approved_by = coalesce(approved_by, auth.uid()),
    released_at = l.release_date::timestamp at time zone 'Asia/Manila' + interval '10 hours',
    actual_release_date = l.release_date,
    released_by = auth.uid(),
    release_method = 'cash',
    activated_at = l.release_date::timestamp at time zone 'Asia/Manila' + interval '10 hours',
    terms_snapshot_id = v_snap,
    loan_officer_id = coalesce(loan_officer_id, created_by)
  where id = l.id;
  update public.loan_applications set decision = 'approved', decided_at = now(), decided_by = auth.uid(),
    decision_notes = 'Demo data', submitted_at = coalesce(submitted_at, now()), submitted_by = coalesce(submitted_by, auth.uid())
  where loan_id = l.id;
  perform public.lf_system_note(l.organization_id, l.id, l.borrower_id,
    'Demo loan activated without a signed agreement (sample data only).');
end $$;

-- Pay (a fraction of) one installment, including penalties accrued up to the payment date.
create or replace function public.lf_demo_pay(p_loan_id uuid, p_installment integer, p_days_late integer,
  p_fraction numeric default 1, p_method text default 'cash')
returns void language plpgsql security definer set search_path = public as $$
declare
  l public.loans%rowtype;
  i public.loan_installments%rowtype;
  v_date date;
  v_amount numeric;
begin
  select * into l from public.loans where id = p_loan_id;
  select * into i from public.loan_installments where loan_id = p_loan_id and installment_no = p_installment;
  if not found or i.status not in ('pending', 'partially_paid') then return; end if;
  v_date := least(i.due_date + p_days_late, public.lf_today(l.organization_id));
  if l.last_payment_date is not null and v_date < l.last_payment_date then v_date := l.last_payment_date; end if;
  if v_date < l.release_date then v_date := l.release_date; end if;
  v_amount := round((public.lf_inst_remaining(i) + public.lf_projected_penalty(i.id, v_date)) * p_fraction, 2);
  if v_amount <= 0 then return; end if;
  if v_amount > l.total_outstanding + public.lf_projected_penalty(i.id, v_date) then
    v_amount := l.total_outstanding + public.lf_projected_penalty(i.id, v_date);
  end if;
  perform public.record_payment(jsonb_build_object(
    'loan_id', p_loan_id, 'amount', v_amount, 'payment_date', v_date, 'method', p_method,
    'reference_no', case p_method when 'e_wallet' then 'GC-' || upper(substr(md5(random()::text), 1, 10))
                                  when 'bank_transfer' then 'BDO-' || upper(substr(md5(random()::text), 1, 10)) end,
    'notes', 'Demo payment', 'idempotency_key', gen_random_uuid()));
end $$;

create or replace function public.seed_demo_data()
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('settings.manage');
  v_today date;
  b uuid[] := '{}';
  v_id uuid;
  p_personal uuid; p_business uuid; p_salary uuid; p_micro uuid;
  l1 uuid; l2 uuid; l3 uuid; l4 uuid; l5 uuid; l6 uuid; l7 uuid; l8 uuid; l9 uuid; l10 uuid;
  v_loans integer := 0;
  v_quote jsonb;
  v_settle date;
  k integer;
  rec record;
begin
  if public.current_app_role() <> 'owner' then
    raise exception 'Only the owner can load sample data' using errcode = '42501';
  end if;
  if exists (select 1 from public.borrowers where organization_id = v_org and is_demo) then
    raise exception 'Sample data has already been loaded for this organization' using errcode = '22023';
  end if;
  v_today := public.lf_today(v_org);

  select id into p_personal from public.loan_products where organization_id = v_org and code = 'PERSONAL' and is_active;
  select id into p_business from public.loan_products where organization_id = v_org and code = 'BUSINESS' and is_active;
  select id into p_salary from public.loan_products where organization_id = v_org and code = 'SALARY' and is_active;
  select id into p_micro from public.loan_products where organization_id = v_org and code = 'MICRO-DAILY' and is_active;

  -- ---------------- borrowers ----------------
  for rec in select * from (values
    ('Juan', 'Santos', 'Dela Cruz', '1988-04-12'::date, 'male', 'married', '+639171234567', 'juan.delacruz@example.ph', '45 Mabini St.', 'San Roque', 'Marikina City', 'Metro Manila', 'NCR — National Capital Region', '1801', 'Jeepney operator', 'Self-employed', 'Self-employed / Business owner', 38000, 'low', 'PhilSys National ID (PhilID / ePhilID)', '1234-5678-9012-3456'),
    ('Maria Clara', 'Reyes', 'Santos', '1979-11-03'::date, 'female', 'married', '+639181112233', 'mcsantos@example.ph', '12 Colon St.', 'Santo Niño', 'Cebu City', 'Cebu', 'Region VII — Central Visayas', '6000', 'Sari-sari store owner', 'Santos General Merchandise', 'Self-employed / Business owner', 65000, 'medium', 'Philippine Passport', 'P1234567A'),
    ('Ramon', 'Garcia', 'Bautista', '1985-02-21'::date, 'male', 'married', '+639209876543', null, 'Blk 5 Lot 12 Camella Homes', 'Talomo', 'Davao City', 'Davao del Sur', 'Region XI — Davao Region', '8000', 'Accounting staff', 'Mindanao Agri Corp.', 'Employed (private)', 32000, 'low', 'UMID', '0111-2233445-6'),
    ('Liza', 'Marquez', 'Mendoza', '1992-07-09'::date, 'female', 'single', '+639278881234', 'liza.mendoza@example.ph', '88 Shaw Blvd.', 'Kapitolyo', 'Pasig City', 'Metro Manila', 'NCR — National Capital Region', '1603', 'Call center agent', 'Concentrix', 'Employed (private)', 28000, 'medium', null, null),
    ('Antonio', 'Cruz', 'Villanueva', '1975-12-30'::date, 'male', 'married', '+639356667788', null, 'Stall 14, La Paz Public Market', 'La Paz', 'Iloilo City', 'Iloilo', 'Region VI — Western Visayas', '5000', 'Market vendor (vegetables)', 'Self-employed', 'Self-employed / Business owner', 22000, 'medium', 'Driver''s License (LTO)', 'G01-23-456789'),
    ('Grace', 'Ocampo', 'Fernandez', '1983-05-17'::date, 'female', 'married', '+639454443322', 'grace.fernandez@example.ph', '3F Unit 302 Ayala Business Park', 'Cebu Business Park', 'Cebu City', 'Cebu', 'Region VII — Central Visayas', '6000', 'Bakery owner', 'Grace Bakeshop', 'Self-employed / Business owner', 90000, 'low', 'PhilSys National ID (PhilID / ePhilID)', '9876-5432-1098-7654'),
    ('Carlo', 'Domingo', 'Garcia', '1995-09-25'::date, 'male', 'single', '+639567778899', null, '21 Kamias Rd.', 'Pinyahan', 'Quezon City', 'Metro Manila', 'NCR — National Capital Region', '1100', 'Delivery rider', 'Lalamove (partner)', 'Freelancer', 25000, 'unrated', null, null),
    ('Rosario', 'Lopez', 'Aquino', '1969-03-08'::date, 'female', 'widowed', '+639171119900', null, '7 Rizal Ave.', 'Poblacion', 'Tarlac City', 'Tarlac', 'Region III — Central Luzon', '2300', 'Public school teacher', 'DepEd Tarlac', 'Employed (government)', 31000, 'low', 'GSIS eCard', 'CRN-0021-4455-7788'),
    ('Michael', 'Sy', 'Tan', '1981-08-14'::date, 'male', 'married', '+639985554433', 'michael.tan@example.ph', '150 Ongpin St.', 'Binondo', 'Manila', 'Metro Manila', 'NCR — National Capital Region', '1006', 'Hardware store owner', 'Tan Hardware Trading', 'Self-employed / Business owner', 150000, 'low', 'TIN ID', '123-456-789-000'),
    ('Andrea', 'Ramos', 'Castillo', '1990-01-27'::date, 'female', 'separated', '+639661237788', null, '9 Magsaysay Ave.', 'Naga Centro', 'Naga City', 'Camarines Sur', 'Region V — Bicol Region', '4400', 'Online seller', 'Self-employed', 'Self-employed / Business owner', 20000, 'high', null, null),
    ('Paolo', 'Cruz', 'Navarro', '1997-06-02'::date, 'male', 'single', '+639173334455', 'paolo.navarro@example.ph', '33 Session Rd.', 'Session Road Area', 'Baguio City', 'Benguet', 'CAR — Cordillera Administrative Region', '2600', 'Barista', 'Cafe by the Ruins', 'Employed (private)', 18000, 'unrated', null, null),
    ('Kristine', 'Go', 'Lim', '1987-10-19'::date, 'female', 'married', '+639188887766', 'kristine.lim@example.ph', '27 Lacson St.', 'Mandalagan', 'Bacolod City', 'Negros Occidental', 'NIR — Negros Island Region', '6100', 'Catering business owner', 'Lim Catering Services', 'Self-employed / Business owner', 120000, 'low', 'PRC ID', '0456789'),
    ('Joy', 'Pascual', 'Ramos', '1993-04-04'::date, 'female', 'married', '+639279990011', null, '14 J.P. Laurel Ave.', 'Bajada', 'Davao City', 'Davao del Sur', 'Region XI — Davao Region', '8000', 'Nurse', 'Davao Doctors Hospital', 'Employed (private)', 35000, 'low', 'PhilHealth ID', '01-234567890-1')
  ) as t(first_name, middle_name, last_name, dob, gender, civil, mobile, email, addr, brgy, city, province, region, zip,
         occupation, employer, emp_type, income, risk, id_type, id_number)
  loop
    insert into public.borrowers (first_name, middle_name, last_name, date_of_birth, gender, civil_status, mobile_number, email,
      address_line, barangay, city_municipality, province, region, zip_code, occupation, employer_name, employment_type,
      monthly_income, risk_rating, emergency_contact_name, emergency_contact_relationship, emergency_contact_number, is_demo)
    values (rec.first_name, rec.middle_name, rec.last_name, rec.dob, rec.gender, rec.civil, rec.mobile, rec.email,
      rec.addr, rec.brgy, rec.city, rec.province, rec.region, rec.zip, rec.occupation, rec.employer, rec.emp_type,
      rec.income, rec.risk, 'Contact of ' || rec.first_name, 'Sibling', '+6391700' || lpad((floor(random() * 99999))::int::text, 5, '0'), true)
    returning id into v_id;
    if rec.id_type is not null then
      insert into public.borrower_identities (borrower_id, organization_id, id_type, id_number, verified_at, verified_by)
      values (v_id, v_org, rec.id_type, rec.id_number, now(), auth.uid());
    end if;
    b := b || v_id;
  end loop;

  -- ---------------- active loans with histories ----------------
  -- 1. Juan — personal, 7 months in, always on time (installment 7 due today)
  l1 := (public.create_loan(jsonb_build_object('borrower_id', b[1], 'product_id', p_personal, 'principal', 50000,
    'interest_method', 'flat', 'interest_rate', 2, 'rate_period', 'monthly', 'repayment_frequency', 'monthly', 'term_count', 10,
    'release_date', (v_today - interval '7 months')::date, 'purpose', 'Jeepney engine overhaul', 'is_demo', true))->>'id')::uuid;
  perform public.lf_demo_activate(l1);
  for k in 1..6 loop perform public.lf_demo_pay(l1, k, 0, 1, case when k % 2 = 0 then 'e_wallet' else 'cash' end); end loop;

  -- 2. Maria Clara — business (diminishing), late payment, partial, now overdue
  l2 := (public.create_loan(jsonb_build_object('borrower_id', b[2], 'product_id', p_business, 'principal', 150000,
    'interest_method', 'diminishing', 'interest_rate', 24, 'rate_period', 'yearly', 'repayment_frequency', 'monthly', 'term_count', 12,
    'release_date', (v_today - interval '9 months')::date, 'purpose', 'Store inventory expansion', 'is_demo', true))->>'id')::uuid;
  perform public.lf_demo_activate(l2);
  for k in 1..5 loop perform public.lf_demo_pay(l2, k, 0, 1, 'bank_transfer'); end loop;
  perform public.lf_demo_pay(l2, 6, 12, 1, 'bank_transfer');
  perform public.lf_demo_pay(l2, 7, 3, 0.5, 'cash');

  -- 3. Ramon — salary loan fully paid (completed)
  l3 := (public.create_loan(jsonb_build_object('borrower_id', b[3], 'product_id', p_salary, 'principal', 20000,
    'interest_method', 'flat', 'interest_rate', 1.5, 'rate_period', 'monthly', 'repayment_frequency', 'monthly', 'term_count', 6,
    'release_date', (v_today - interval '6 months' - interval '10 days')::date, 'purpose', 'Tuition fees', 'is_demo', true))->>'id')::uuid;
  perform public.lf_demo_activate(l3);
  for k in 1..6 loop perform public.lf_demo_pay(l3, k, 0, 1, 'e_wallet'); end loop;

  -- 4. Liza — personal, one late payment, one overdue installment
  l4 := (public.create_loan(jsonb_build_object('borrower_id', b[4], 'product_id', p_personal, 'principal', 30000,
    'interest_method', 'flat', 'interest_rate', 2, 'rate_period', 'monthly', 'repayment_frequency', 'monthly', 'term_count', 12,
    'release_date', (v_today - interval '4 months')::date, 'purpose', 'Medical expenses', 'is_demo', true))->>'id')::uuid;
  perform public.lf_demo_activate(l4);
  perform public.lf_demo_pay(l4, 1, 0, 1, 'e_wallet');
  perform public.lf_demo_pay(l4, 2, 20, 1, 'e_wallet');

  -- 5. Antonio — daily microloan, collected daily then missed recent days
  l5 := (public.create_loan(jsonb_build_object('borrower_id', b[5], 'product_id', p_micro, 'principal', 10000,
    'interest_method', 'flat', 'interest_rate', 2, 'rate_period', 'monthly', 'repayment_frequency', 'daily', 'term_count', 60,
    'release_date', v_today - 45, 'purpose', 'Daily market capital', 'is_demo', true))->>'id')::uuid;
  perform public.lf_demo_activate(l5);
  for k in 1..38 loop perform public.lf_demo_pay(l5, k, 0, 1, 'cash'); end loop;

  -- 6. Grace — business loan settled early (unearned interest rebated)
  l6 := (public.create_loan(jsonb_build_object('borrower_id', b[6], 'product_id', p_business, 'principal', 80000,
    'interest_method', 'diminishing', 'interest_rate', 24, 'rate_period', 'yearly', 'repayment_frequency', 'monthly', 'term_count', 6,
    'release_date', (v_today - interval '5 months')::date, 'purpose', 'New oven and mixer', 'is_demo', true))->>'id')::uuid;
  perform public.lf_demo_activate(l6);
  for k in 1..4 loop perform public.lf_demo_pay(l6, k, 0, 1, 'bank_transfer'); end loop;
  v_settle := greatest(v_today - 5, (select last_payment_date from public.loans where id = l6));
  v_quote := public.lf_settlement_quote(l6, v_settle);
  perform public.record_payment(jsonb_build_object('loan_id', l6, 'amount', (v_quote->>'total')::numeric, 'payment_date', v_settle,
    'method', 'bank_transfer', 'reference_no', 'BDO-' || upper(substr(md5(random()::text), 1, 10)), 'payment_type', 'settlement',
    'notes', 'Early settlement (demo)', 'idempotency_key', gen_random_uuid()));

  -- 7. Carlo — personal, first installment paid, second due today
  l7 := (public.create_loan(jsonb_build_object('borrower_id', b[7], 'product_id', p_personal, 'principal', 25000,
    'interest_method', 'flat', 'interest_rate', 2, 'rate_period', 'monthly', 'repayment_frequency', 'monthly', 'term_count', 6,
    'release_date', (v_today - interval '2 months')::date, 'purpose', 'Motorcycle repair', 'is_demo', true))->>'id')::uuid;
  perform public.lf_demo_activate(l7);
  perform public.lf_demo_pay(l7, 1, 0, 1, 'e_wallet');

  -- 8. Rosario — salary loan just released, first payment upcoming
  l8 := (public.create_loan(jsonb_build_object('borrower_id', b[8], 'product_id', p_salary, 'principal', 15000,
    'interest_method', 'flat', 'interest_rate', 1.5, 'rate_period', 'monthly', 'repayment_frequency', 'monthly', 'term_count', 3,
    'release_date', v_today - 20, 'purpose', 'Home repairs', 'is_demo', true))->>'id')::uuid;
  perform public.lf_demo_activate(l8);

  -- 9. Michael — business, partial late payment
  l9 := (public.create_loan(jsonb_build_object('borrower_id', b[9], 'product_id', p_business, 'principal', 200000,
    'interest_method', 'diminishing', 'interest_rate', 24, 'rate_period', 'yearly', 'repayment_frequency', 'monthly', 'term_count', 12,
    'release_date', (v_today - interval '3 months')::date, 'purpose', 'Bulk cement purchase', 'is_demo', true))->>'id')::uuid;
  perform public.lf_demo_activate(l9);
  perform public.lf_demo_pay(l9, 1, 0, 1, 'bank_transfer');
  perform public.lf_demo_pay(l9, 2, 5, 0.6, 'bank_transfer');

  -- 10. Andrea — stopped paying 4 months ago, marked as defaulted
  l10 := (public.create_loan(jsonb_build_object('borrower_id', b[10], 'product_id', p_personal, 'principal', 40000,
    'interest_method', 'flat', 'interest_rate', 2, 'rate_period', 'monthly', 'repayment_frequency', 'monthly', 'term_count', 8,
    'release_date', (v_today - interval '8 months')::date, 'purpose', 'Online store stock', 'is_demo', true))->>'id')::uuid;
  perform public.lf_demo_activate(l10);
  for k in 1..3 loop perform public.lf_demo_pay(l10, k, 2, 1, 'e_wallet'); end loop;
  perform public.mark_loan_default(l10, 'No payments for over 120 days despite repeated collection visits (demo)');

  -- ---------------- pipeline ----------------
  perform public.create_loan(jsonb_build_object('borrower_id', b[11], 'product_id', p_personal, 'principal', 35000,
    'interest_method', 'flat', 'interest_rate', 2, 'rate_period', 'monthly', 'repayment_frequency', 'monthly', 'term_count', 12,
    'purpose', 'Laptop for freelance work', 'is_demo', true));
  perform public.create_loan(jsonb_build_object('borrower_id', b[12], 'product_id', p_business, 'principal', 120000,
    'interest_method', 'diminishing', 'interest_rate', 24, 'rate_period', 'yearly', 'repayment_frequency', 'monthly', 'term_count', 12,
    'purpose', 'Catering equipment', 'source', 'referral', 'submit', true, 'is_demo', true));
  perform public.create_loan(jsonb_build_object('borrower_id', b[3], 'product_id', p_salary, 'principal', 18000,
    'interest_method', 'flat', 'interest_rate', 1.5, 'rate_period', 'monthly', 'repayment_frequency', 'monthly', 'term_count', 6,
    'purpose', 'Renewal — school supplies', 'source', 'renewal', 'submit', true, 'is_demo', true));
  v_id := (public.create_loan(jsonb_build_object('borrower_id', b[13], 'product_id', p_personal, 'principal', 45000,
    'interest_method', 'flat', 'interest_rate', 2, 'rate_period', 'monthly', 'repayment_frequency', 'monthly', 'term_count', 10,
    'purpose', 'Family emergency fund', 'submit', true, 'is_demo', true))->>'id')::uuid;
  perform public.approve_loan(v_id, 'Stable employment; good references (demo)');

  select count(*) into v_loans from public.loans where organization_id = v_org and is_demo;
  return jsonb_build_object('borrowers', array_length(b, 1), 'loans', v_loans);
end $$;

revoke execute on function public.lf_demo_activate(uuid) from public, anon, authenticated;
revoke execute on function public.lf_demo_pay(uuid, integer, integer, numeric, text) from public, anon, authenticated;
grant execute on function public.lf_demo_activate(uuid) to service_role;
grant execute on function public.lf_demo_pay(uuid, integer, integer, numeric, text) to service_role;


-- ==== 20261114000011_platform_admin.sql ====
-- =============================================================================
-- LendFlow — Platform (super) administration
--
-- Platform administrators operate the LendFlow service: they create lender
-- accounts (an organization + its owner login), suspend/reactivate them and help
-- owners regain access. They are NOT members of any organization, so RLS still
-- gives them no access to borrower, loan, payment or document records; the
-- console only sees per-account counts and totals through the functions below.
-- Platform admins are granted only by the service role (scripts/seed-super-admin.mjs).
-- =============================================================================

create table public.platform_admins (
  user_id uuid primary key references auth.users (id) on delete cascade,
  email text not null,
  created_at timestamptz not null default now()
);
alter table public.platform_admins enable row level security;
create policy platform_admins_self on public.platform_admins for select to authenticated
  using (user_id = auth.uid());
revoke insert, update, delete on public.platform_admins from anon, authenticated;

alter table public.organizations
  add column status text not null default 'active' check (status in ('active', 'suspended')),
  add column status_reason text,
  add column status_changed_at timestamptz,
  add column created_by uuid references auth.users (id) on delete set null;

-- Account status and provenance can only be changed by platform functions / service role,
-- never by an organization's own users through the API.
create or replace function public.lf_org_status_guard()
returns trigger language plpgsql as $$
begin
  if current_user in ('authenticated', 'anon') and (
       new.status is distinct from old.status or new.status_reason is distinct from old.status_reason
       or new.status_changed_at is distinct from old.status_changed_at or new.created_by is distinct from old.created_by) then
    raise exception 'Account status can only be changed by a platform administrator' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger lf_org_status_guard before update on public.organizations
  for each row execute function public.lf_org_status_guard();

-- ---------------------------------------------------------------------------
-- Suspended organizations lose all access: every RLS policy and RPC resolves the
-- caller's organization through these helpers.
-- ---------------------------------------------------------------------------
create or replace function public.current_org_id()
returns uuid language sql stable security definer set search_path = public as $$
  select up.organization_id from public.user_profiles up
  join public.organizations o on o.id = up.organization_id and o.status = 'active'
  where up.id = auth.uid() and up.is_active
$$;

create or replace function public.current_app_role()
returns public.app_role language sql stable security definer set search_path = public as $$
  select ur.role from public.user_roles ur
  join public.user_profiles up on up.id = ur.user_id and up.organization_id = ur.organization_id
  join public.organizations o on o.id = ur.organization_id and o.status = 'active'
  where ur.user_id = auth.uid() and up.is_active
  limit 1
$$;

create or replace function public.has_permission(p_permission text)
returns boolean language sql stable security definer set search_path = public as $$
  select coalesce(
    (select true from public.user_roles ur
       join public.user_profiles up on up.id = ur.user_id and up.organization_id = ur.organization_id
       join public.organizations o on o.id = ur.organization_id and o.status = 'active'
      where ur.user_id = auth.uid() and up.is_active and ur.role = 'owner'
      limit 1),
    (select true from public.user_roles ur
       join public.user_profiles up on up.id = ur.user_id and up.organization_id = ur.organization_id
       join public.organizations o on o.id = ur.organization_id and o.status = 'active'
       join public.role_permissions rp on rp.organization_id = ur.organization_id and rp.role = ur.role
      where ur.user_id = auth.uid() and up.is_active and rp.permission = p_permission
      limit 1),
    false)
$$;

create or replace function public.is_platform_admin()
returns boolean language sql stable security definer set search_path = public as $$
  select auth.uid() is not null and exists (select 1 from public.platform_admins where user_id = auth.uid())
$$;

create or replace function public.lf_require_platform_admin()
returns uuid language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_platform_admin() then
    raise exception 'Platform administrator access required' using errcode = '42501';
  end if;
  return auth.uid();
end $$;

-- my_access now also reports platform-admin status and a suspended membership
create or replace function public.my_access()
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'user_id', auth.uid(),
    'organization_id', public.current_org_id(),
    'role', public.current_app_role(),
    'full_name', (select full_name from public.user_profiles where id = auth.uid()),
    'is_platform_admin', public.is_platform_admin(),
    'suspended_organization', (
      select jsonb_build_object('name', o.name, 'reason', o.status_reason)
      from public.user_profiles up join public.organizations o on o.id = up.organization_id
      where up.id = auth.uid() and o.status = 'suspended'),
    'permissions', coalesce(
      case when public.current_app_role() = 'owner'
           then to_jsonb(public.lf_all_permissions())
           else (select jsonb_agg(rp.permission) from public.role_permissions rp
                 where rp.organization_id = public.current_org_id() and rp.role = public.current_app_role()) end,
      '[]'::jsonb))
$$;

-- ---------------------------------------------------------------------------
-- Console read models (counts and totals only — no borrower or loan rows)
-- ---------------------------------------------------------------------------
create or replace function public.lf_platform_org_stats(p_org uuid)
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'users', (select count(*) from public.user_profiles where organization_id = p_org and is_active),
    'borrowers', (select count(*) from public.borrowers where organization_id = p_org),
    'loans', (select count(*) from public.loans where organization_id = p_org),
    'active_loans', (select count(*) from public.loans where organization_id = p_org and status = 'active'),
    'outstanding', (select coalesce(sum(total_outstanding), 0) from public.loans
                    where organization_id = p_org and status in ('active', 'defaulted')),
    'collected', (select coalesce(sum(amount), 0) from public.loan_payments where organization_id = p_org and status = 'posted'),
    'last_activity_at', (select max(last_sign_in_at) from auth.users u
                         join public.user_profiles up on up.id = u.id where up.organization_id = p_org))
$$;

create or replace function public.lf_platform_org_owner(p_org uuid)
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object('user_id', up.id, 'full_name', up.full_name, 'email', up.email, 'phone', up.phone)
  from public.user_roles ur join public.user_profiles up on up.id = ur.user_id and up.organization_id = ur.organization_id
  where ur.organization_id = p_org and ur.role = 'owner'
  order by up.is_active desc, ur.created_at
  limit 1
$$;

create or replace function public.platform_list_organizations()
returns jsonb language plpgsql stable security definer set search_path = public as $$
begin
  perform public.lf_require_platform_admin();
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', o.id, 'name', o.name, 'legal_name', o.legal_name, 'city', o.city, 'province', o.province,
      'contact_email', o.contact_email, 'contact_phone', o.contact_phone,
      'status', o.status, 'status_reason', o.status_reason, 'status_changed_at', o.status_changed_at,
      'created_at', o.created_at,
      'owner', public.lf_platform_org_owner(o.id),
      'stats', public.lf_platform_org_stats(o.id)) order by o.created_at desc)
    from public.organizations o), '[]'::jsonb);
end $$;

create or replace function public.platform_get_organization(p_org uuid)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare v jsonb;
begin
  perform public.lf_require_platform_admin();
  select to_jsonb(o) || jsonb_build_object(
      'owner', public.lf_platform_org_owner(o.id),
      'stats', public.lf_platform_org_stats(o.id),
      'members', coalesce((
        select jsonb_agg(jsonb_build_object(
          'id', up.id, 'full_name', up.full_name, 'email', up.email, 'phone', up.phone, 'is_active', up.is_active,
          'role', ur.role, 'created_at', up.created_at, 'last_sign_in_at', u.last_sign_in_at)
          order by (ur.role = 'owner') desc, up.full_name)
        from public.user_profiles up
        left join public.user_roles ur on ur.user_id = up.id and ur.organization_id = up.organization_id
        left join auth.users u on u.id = up.id
        where up.organization_id = o.id), '[]'::jsonb))
  into v
  from public.organizations o where o.id = p_org;
  if v is null then raise exception 'Account not found' using errcode = 'P0002'; end if;
  return v;
end $$;

create or replace function public.platform_overview()
returns jsonb language plpgsql stable security definer set search_path = public as $$
begin
  perform public.lf_require_platform_admin();
  return jsonb_build_object(
    'organizations', (select count(*) from public.organizations),
    'active_organizations', (select count(*) from public.organizations where status = 'active'),
    'suspended_organizations', (select count(*) from public.organizations where status = 'suspended'),
    'new_organizations_30d', (select count(*) from public.organizations where created_at > now() - interval '30 days'),
    'users', (select count(*) from public.user_profiles where organization_id is not null and is_active),
    'borrowers', (select count(*) from public.borrowers),
    'active_loans', (select count(*) from public.loans where status = 'active'),
    'outstanding', (select coalesce(sum(total_outstanding), 0) from public.loans where status in ('active', 'defaulted')),
    'collected_30d', (select coalesce(sum(amount), 0) from public.loan_payments
                      where status = 'posted' and payment_date > (now() at time zone 'Asia/Manila')::date - 30),
    'signups_by_month', coalesce((
      select jsonb_agg(jsonb_build_object('month', m, 'count', c) order by m)
      from (select to_char(date_trunc('month', created_at at time zone 'Asia/Manila'), 'YYYY-MM') m, count(*) c
            from public.organizations where created_at > now() - interval '12 months' group by 1) s), '[]'::jsonb));
end $$;

create or replace function public.platform_set_organization_status(p_org uuid, p_status text, p_reason text default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v_admin uuid := public.lf_require_platform_admin();
begin
  if p_status not in ('active', 'suspended') then raise exception 'Invalid status' using errcode = '22023'; end if;
  if p_status = 'suspended' and coalesce(trim(p_reason), '') = '' then
    raise exception 'Give a reason for suspending this account' using errcode = '22023';
  end if;
  update public.organizations
  set status = p_status, status_reason = case when p_status = 'suspended' then trim(p_reason) end, status_changed_at = now()
  where id = p_org;
  if not found then raise exception 'Account not found' using errcode = 'P0002'; end if;
  insert into public.audit_logs (organization_id, actor_id, actor_name, action, table_name, record_id, new_data, context)
  values (p_org, v_admin, 'Platform administrator',
          case when p_status = 'suspended' then 'account_suspended' else 'account_reactivated' end,
          'organizations', p_org::text, jsonb_build_object('status', p_status, 'reason', p_reason),
          jsonb_build_object('source', 'platform_admin'));
  return public.platform_get_organization(p_org);
end $$;

create or replace function public.platform_update_organization(p_org uuid, p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
begin
  perform public.lf_require_platform_admin();
  if p ? 'name' and coalesce(trim(p->>'name'), '') = '' then raise exception 'Business name is required' using errcode = '22023'; end if;
  update public.organizations set
    name = coalesce(nullif(trim(p->>'name'), ''), name),
    legal_name = case when p ? 'legal_name' then nullif(trim(p->>'legal_name'), '') else legal_name end,
    registration_no = case when p ? 'registration_no' then nullif(trim(p->>'registration_no'), '') else registration_no end,
    tin = case when p ? 'tin' then nullif(trim(p->>'tin'), '') else tin end,
    address = case when p ? 'address' then nullif(trim(p->>'address'), '') else address end,
    city = case when p ? 'city' then nullif(trim(p->>'city'), '') else city end,
    province = case when p ? 'province' then nullif(trim(p->>'province'), '') else province end,
    contact_email = case when p ? 'contact_email' then nullif(trim(p->>'contact_email'), '') else contact_email end,
    contact_phone = case when p ? 'contact_phone' then nullif(trim(p->>'contact_phone'), '') else contact_phone end
  where id = p_org;
  if not found then raise exception 'Account not found' using errcode = 'P0002'; end if;
  return public.platform_get_organization(p_org);
end $$;

-- ---------------------------------------------------------------------------
-- Service-role only (called by the platform-admin Edge Function after it has
-- verified the caller is a platform admin and created the auth user).
-- ---------------------------------------------------------------------------
create or replace function public.lf_platform_create_organization(p jsonb, p_owner uuid, p_created_by uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v_org uuid;
begin
  if coalesce(trim(p->>'name'), '') = '' then raise exception 'Business name is required' using errcode = '22023'; end if;
  if not exists (select 1 from auth.users where id = p_owner) then raise exception 'Owner login not found'; end if;
  if exists (select 1 from public.user_profiles where id = p_owner and organization_id is not null) then
    raise exception 'This login already belongs to a lender account' using errcode = '22023';
  end if;
  if exists (select 1 from public.platform_admins where user_id = p_owner) then
    raise exception 'A platform administrator login cannot own a lender account' using errcode = '22023';
  end if;

  insert into public.organizations (name, legal_name, registration_no, tin, address, city, province, zip_code,
    contact_email, contact_phone, created_by)
  values (trim(p->>'name'), nullif(trim(p->>'legal_name'), ''), nullif(trim(p->>'registration_no'), ''),
          nullif(trim(p->>'tin'), ''), nullif(trim(p->>'address'), ''), nullif(trim(p->>'city'), ''),
          nullif(trim(p->>'province'), ''), nullif(trim(p->>'zip_code'), ''), nullif(trim(p->>'contact_email'), ''),
          nullif(trim(p->>'contact_phone'), ''), p_created_by)
  returning id into v_org;

  perform public.lf_seed_role_permissions(v_org);
  perform public.lf_seed_defaults(v_org);
  perform public.lf_attach_user(p_owner, v_org, 'owner', nullif(trim(p->>'owner_name'), ''), nullif(trim(p->>'owner_phone'), ''));

  insert into public.audit_logs (organization_id, actor_id, actor_name, action, table_name, record_id, new_data, context)
  values (v_org, p_created_by, 'Platform administrator', 'account_created', 'organizations', v_org::text,
          jsonb_build_object('name', trim(p->>'name'), 'owner_user_id', p_owner),
          jsonb_build_object('source', 'platform_admin'));
  return jsonb_build_object('organization_id', v_org);
end $$;

create or replace function public.lf_grant_platform_admin(p_user uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  if exists (select 1 from public.user_profiles where id = p_user and organization_id is not null) then
    raise exception 'This login belongs to a lender account; use a separate login for platform administration';
  end if;
  insert into public.platform_admins (user_id, email)
  select id, email from auth.users where id = p_user
  on conflict (user_id) do update set email = excluded.email;
  if not found then raise exception 'User not found'; end if;
end $$;

-- Users who can sign in to a suspended account must not be able to self-onboard into a new one
create or replace function public.create_organization(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_org uuid;
begin
  if v_uid is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  if exists (select 1 from public.user_profiles where id = v_uid and organization_id is not null) then
    raise exception 'You already belong to an organization' using errcode = '22023';
  end if;
  if public.is_platform_admin() then
    raise exception 'Platform administrators create lender accounts from the admin console' using errcode = '22023';
  end if;
  if coalesce(trim(p->>'name'), '') = '' then raise exception 'Organization name is required' using errcode = '22023'; end if;

  insert into public.organizations (name, legal_name, address, city, province, zip_code, contact_email, contact_phone, registration_no, tin)
  values (trim(p->>'name'), nullif(p->>'legal_name', ''), nullif(p->>'address', ''), nullif(p->>'city', ''), nullif(p->>'province', ''),
          nullif(p->>'zip_code', ''), nullif(p->>'contact_email', ''), nullif(p->>'contact_phone', ''),
          nullif(p->>'registration_no', ''), nullif(p->>'tin', ''))
  returning id into v_org;

  insert into public.user_profiles (id, organization_id, full_name, email)
  values (v_uid, v_org, coalesce(nullif(p->>'owner_name', ''), ''), (select email from auth.users where id = v_uid))
  on conflict (id) do update set organization_id = v_org,
    full_name = coalesce(nullif(p->>'owner_name', ''), public.user_profiles.full_name);
  insert into public.user_roles (user_id, organization_id, role) values (v_uid, v_org, 'owner');

  perform public.lf_seed_role_permissions(v_org);
  perform public.lf_seed_defaults(v_org);
  return jsonb_build_object('organization_id', v_org);
end $$;

revoke execute on function public.lf_platform_create_organization(jsonb, uuid, uuid) from public, anon, authenticated;
revoke execute on function public.lf_grant_platform_admin(uuid) from public, anon, authenticated;
revoke execute on function public.lf_platform_org_stats(uuid) from public, anon, authenticated;
revoke execute on function public.lf_platform_org_owner(uuid) from public, anon, authenticated;
revoke execute on function public.lf_org_status_guard() from public, anon, authenticated;
grant execute on function public.lf_platform_create_organization(jsonb, uuid, uuid) to service_role;
grant execute on function public.lf_grant_platform_admin(uuid) to service_role;


-- ==== 20261114000012_disable_self_signup.sql ====
-- =============================================================================
-- LendFlow — Lender accounts are provisioned only by the platform administrator
--
-- Self-service onboarding is closed: a login that is not attached to an account
-- cannot create one. Accounts are created through the admin console
-- (platform-admin Edge Function -> lf_platform_create_organization).
-- The function is kept, with the same signature, so older clients get a clear error.
-- =============================================================================
create or replace function public.create_organization(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
begin
  raise exception 'Lender accounts are created by the LendFlow administrator. Please contact them for access.'
    using errcode = '42501';
end $$;

-- Attaching a login without a name falls back to its existing profile name or email
create or replace function public.lf_attach_user(p_user_id uuid, p_org uuid, p_role public.app_role, p_full_name text, p_phone text)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_email text := (select email from auth.users where id = p_user_id);
  v_name text := coalesce(nullif(trim(p_full_name), ''),
                          nullif((select full_name from public.user_profiles where id = p_user_id), ''),
                          split_part(v_email, '@', 1), '');
begin
  insert into public.user_profiles (id, organization_id, full_name, email, phone)
  values (p_user_id, p_org, v_name, v_email, p_phone)
  on conflict (id) do update set organization_id = p_org, full_name = v_name,
    phone = coalesce(p_phone, public.user_profiles.phone), is_active = true;
  insert into public.user_roles (user_id, organization_id, role) values (p_user_id, p_org, p_role)
  on conflict (user_id, organization_id) do update set role = excluded.role;
end $$;


-- ==== 20261114000013_lending_modes.sql ====
-- =============================================================================
-- LendFlow — Lending modes: Complete Lending and Simplified Lending
--
-- * organizations.lending_mode is chosen by the platform (super) administrator
--   and decides which loan-creation workflow every user of the business gets.
--   Organization users can never change it themselves.
-- * Complete Lending is the existing workflow (create_loan / update_loan, unchanged).
-- * Simplified Lending creates loans through create_simplified_loan: the interest
--   rate, method, frequency, fees and penalties come from the business settings;
--   staff only enter the amount and the duration (and the rate, if the business
--   explicitly allows an override).
-- * Reloan / Renew: the borrower's open loans are quoted for early settlement from
--   the database (never typed in by staff). The quote is recorded on the new loan
--   (loan_renewals) and, when the new loan is released, each previous loan is
--   settled in full by a "renewal" settlement payment as of the release date. The
--   amount released to the borrower is the new loan's net proceeds less those
--   settlements. Everything is ledgered and audited; previous loans keep their history.
-- Both modes share the same borrowers, ledger, agreements, approval and release steps.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Columns
-- ---------------------------------------------------------------------------
alter table public.organizations
  add column lending_mode text not null default 'complete' check (lending_mode in ('complete', 'simplified')),
  add column lending_mode_changed_at timestamptz;

alter table public.loans
  add column lending_mode text not null default 'complete' check (lending_mode in ('complete', 'simplified')),
  add column renewal_deduction numeric(14, 2) not null default 0 check (renewal_deduction >= 0);

-- Renewal settlements are an internal, non-cash payment method
alter table public.loan_payments drop constraint loan_payments_method_check;
alter table public.loan_payments add constraint loan_payments_method_check
  check (method in ('cash', 'bank_transfer', 'e_wallet', 'check', 'other', 'renewal'));

-- Borrowers may sign in person on the lender's device (Simplified Lending)
alter table public.agreement_signing_sessions drop constraint agreement_signing_sessions_verification_method_check;
alter table public.agreement_signing_sessions add constraint agreement_signing_sessions_verification_method_check
  check (verification_method in ('none', 'dob', 'id_last4', 'in_person'));

-- ---------------------------------------------------------------------------
-- Renewal settlements: how each previous loan was (or will be) settled by a new loan
-- ---------------------------------------------------------------------------
create table public.loan_renewals (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete restrict,
  new_loan_id uuid not null references public.loans (id) on delete restrict,
  old_loan_id uuid not null references public.loans (id) on delete restrict,
  status text not null default 'pending' check (status in ('pending', 'settled', 'cancelled', 'closed_before_release')),
  -- quote taken from the database when the renewal loan was created (shown in its agreement)
  quoted_amount numeric(14, 2) not null check (quoted_amount >= 0),
  quoted_contract_balance numeric(14, 2) not null default 0,
  quoted_interest_rebate numeric(14, 2) not null default 0,
  quoted_as_of date not null,
  -- actual settlement posted when the renewal loan was released
  settled_amount numeric(14, 2),
  settled_contract_balance numeric(14, 2),
  settled_interest_rebate numeric(14, 2),
  settled_as_of date,
  settlement_payment_id uuid references public.loan_payments (id) on delete restrict,
  settled_at timestamptz,
  created_by uuid references public.user_profiles (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (new_loan_id, old_loan_id),
  check (new_loan_id <> old_loan_id)
);
create index loan_renewals_old_idx on public.loan_renewals (old_loan_id);
-- a previous loan can be offset by only one unreleased renewal at a time
create unique index loan_renewals_one_pending on public.loan_renewals (old_loan_id) where status = 'pending';

alter table public.loan_renewals enable row level security;
create policy loan_renewals_select on public.loan_renewals for select to authenticated
  using (organization_id = public.current_org_id()
         and (public.can_access_loan(new_loan_id) or public.can_access_loan(old_loan_id)));
revoke insert, update, delete on public.loan_renewals from anon, authenticated;

create trigger loan_renewals_touch_updated_at before update on public.loan_renewals
  for each row execute function public.lf_touch_updated_at();
create trigger loan_renewals_audit after insert or update or delete on public.loan_renewals
  for each row execute function public.lf_audit();
create trigger loan_renewals_no_delete before delete on public.loan_renewals
  for each row execute function public.lf_block_mutation();

-- v_loans selects l.*; recreate it so it exposes the new loan columns
drop view public.v_loans;
create view public.v_loans with (security_invoker = true) as
select
  l.*,
  b.full_name as borrower_name, b.borrower_code, b.mobile_number as borrower_mobile, b.profile_photo_path as borrower_photo_path,
  p.name as product_name,
  lo.full_name as loan_officer_name,
  co.full_name as collector_name,
  agg.next_due_date, agg.next_due_amount, agg.overdue_amount, agg.overdue_count, agg.oldest_overdue_date,
  case when agg.oldest_overdue_date is null then 0 else public.lf_today(l.organization_id) - agg.oldest_overdue_date end as days_overdue,
  agg.paid_installments, agg.total_installments,
  (select a.status from public.loan_agreements a where a.loan_id = l.id order by a.version desc limit 1) as agreement_status
from public.loans l
join public.borrowers b on b.id = l.borrower_id
left join public.loan_products p on p.id = l.product_id
left join public.user_profiles lo on lo.id = l.loan_officer_id
left join public.user_profiles co on co.id = l.collector_id
left join lateral (
  select
    min(i.due_date) filter (where i.status in ('pending', 'partially_paid') and i.due_date >= public.lf_today(l.organization_id)) as next_due_date,
    (select public.lf_inst_remaining(x) from public.loan_installments x
      where x.loan_id = l.id and x.status in ('pending', 'partially_paid') order by x.installment_no limit 1) as next_due_amount,
    coalesce(sum(public.lf_inst_remaining(i)) filter (where i.status in ('pending', 'partially_paid') and i.due_date < public.lf_today(l.organization_id)), 0) as overdue_amount,
    count(*) filter (where i.status in ('pending', 'partially_paid') and i.due_date < public.lf_today(l.organization_id)) as overdue_count,
    min(i.due_date) filter (where i.status in ('pending', 'partially_paid') and i.due_date < public.lf_today(l.organization_id)) as oldest_overdue_date,
    count(*) filter (where i.status = 'paid') as paid_installments,
    count(*) filter (where i.status <> 'rescheduled') as total_installments
  from public.loan_installments i where i.loan_id = l.id
) agg on true;
grant select on public.v_loans to authenticated, service_role;

-- ---------------------------------------------------------------------------
-- Guards
-- ---------------------------------------------------------------------------
-- Account status, provenance and lending mode are platform-controlled
create or replace function public.lf_org_status_guard()
returns trigger language plpgsql as $$
begin
  if current_user in ('authenticated', 'anon') and (
       new.status is distinct from old.status or new.status_reason is distinct from old.status_reason
       or new.status_changed_at is distinct from old.status_changed_at or new.created_by is distinct from old.created_by) then
    raise exception 'Account status can only be changed by a platform administrator' using errcode = '42501';
  end if;
  if current_user in ('authenticated', 'anon') and (
       new.lending_mode is distinct from old.lending_mode or new.lending_mode_changed_at is distinct from old.lending_mode_changed_at) then
    raise exception 'The lending mode can only be changed by the platform administrator' using errcode = '42501';
  end if;
  return new;
end $$;

-- A loan is always created through the workflow of the business's lending mode, and
-- the contractual terms of a Simplified loan are never edited after creation.
create or replace function public.lf_loan_mode_guard()
returns trigger language plpgsql security definer set search_path = public as $$
declare v_mode text;
begin
  if tg_op = 'INSERT' then
    select lending_mode into v_mode from public.organizations where id = new.organization_id;
    if not new.is_demo and new.lending_mode is distinct from v_mode then
      raise exception '%', case when v_mode = 'simplified'
        then 'This business uses Simplified Lending. Create loans with the simplified New Loan / Reloan workflow.'
        else 'This business uses Complete Lending. Simplified loans are not enabled for this account.' end
        using errcode = '42501';
    end if;
    return new;
  end if;
  if new.lending_mode is distinct from old.lending_mode then
    raise exception 'The lending mode of a loan cannot be changed' using errcode = '42501';
  end if;
  if old.lending_mode = 'simplified' and (
       new.principal is distinct from old.principal or new.interest_rate is distinct from old.interest_rate
       or new.interest_method is distinct from old.interest_method or new.rate_period is distinct from old.rate_period
       or new.repayment_frequency is distinct from old.repayment_frequency or new.term_count is distinct from old.term_count
       or new.processing_fee_type is distinct from old.processing_fee_type or new.processing_fee_value is distinct from old.processing_fee_value
       or new.other_charges is distinct from old.other_charges or new.charges_treatment is distinct from old.charges_treatment
       or new.product_id is distinct from old.product_id or new.borrower_id is distinct from old.borrower_id) then
    raise exception 'Simplified loans cannot be edited after creation. Cancel this loan and create a new one with the correct amount or duration.'
      using errcode = '22023';
  end if;
  return new;
end $$;
create trigger lf_loan_mode_guard before insert or update on public.loans
  for each row execute function public.lf_loan_mode_guard();

-- A renewal that is cancelled or rejected before release releases its hold on the previous loans
create or replace function public.lf_renewal_status_sync()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if new.status in ('cancelled', 'rejected') and old.status is distinct from new.status then
    update public.loan_renewals set status = 'cancelled' where new_loan_id = new.id and status = 'pending';
  end if;
  return new;
end $$;
create trigger lf_renewal_status_sync after update of status on public.loans
  for each row execute function public.lf_renewal_status_sync();

-- A renewal settlement is part of a disbursed loan; reversing it alone would leave the
-- previous loan open while its balance was already paid out of the new loan.
create or replace function public.lf_renewal_payment_guard()
returns trigger language plpgsql as $$
begin
  if old.method = 'renewal' and new.status = 'reversed' and old.status = 'posted' then
    raise exception 'A renewal settlement cannot be reversed on its own; it was paid from the proceeds of the renewal loan (%).',
      coalesce(old.reference_no, 'see payment notes') using errcode = '22023';
  end if;
  return new;
end $$;
create trigger lf_renewal_payment_guard before update of status on public.loan_payments
  for each row execute function public.lf_renewal_payment_guard();

-- ---------------------------------------------------------------------------
-- Settings: a "simplified" section owned by the business
-- ---------------------------------------------------------------------------
create or replace function public.lf_default_settings()
returns jsonb language sql immutable as $$
  select '{
    "interest": {
      "flat_enabled": true, "diminishing_enabled": true, "default_method": "flat",
      "default_rate": 2, "default_rate_period": "monthly",
      "rate_presets": [1, 1.5, 2, 2.5, 3, 3.5, 4, 5],
      "rate_periods": ["monthly", "yearly"],
      "day_count": "30/360", "rounding": "centavo"
    },
    "repayment": {
      "frequencies": ["monthly", "weekly", "daily"], "default_frequency": "monthly",
      "first_due_rule": "one_period", "grace_days": 0,
      "early_settlement_rule": "prorated",
      "allocation_order": ["penalty", "charges", "interest", "principal"],
      "charges_treatment": "deducted",
      "require_separate_approver": false
    },
    "penalty": { "enabled": true, "type": "percent_monthly", "rate": 3, "grace_days": 3 },
    "agreement": {
      "default_template_id": null, "signing_order": "borrower_first",
      "require_lender_signature": true, "witness_count": 0,
      "identity_verification": "dob", "link_expiry_hours": 72
    },
    "security": { "session_timeout_minutes": 60, "password_min_length": 10, "audit_logging": true },
    "receipt": { "footer_note": "This is a system-generated acknowledgement receipt. Please keep it for your records." },
    "simplified": {
      "allow_rate_override": false, "default_term": 6,
      "processing_fee_type": "none", "processing_fee_value": 0,
      "approve_on_create": true
    }
  }'::jsonb
$$;

create or replace function public.update_settings(p_section text, p_values jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('settings.manage');
  v_allowed text[] := array['interest', 'repayment', 'penalty', 'agreement', 'security', 'receipt', 'simplified'];
  v_current jsonb;
begin
  if not (p_section = any (v_allowed)) then raise exception 'Unknown settings section "%"', p_section using errcode = '22023'; end if;
  if jsonb_typeof(p_values) <> 'object' then raise exception 'Settings must be an object' using errcode = '22023'; end if;
  if p_section = 'interest' and coalesce((p_values->>'flat_enabled')::boolean, true) = false
     and coalesce((p_values->>'diminishing_enabled')::boolean, true) = false then
    raise exception 'At least one interest method must remain enabled' using errcode = '22023';
  end if;
  if p_section = 'repayment' and p_values ? 'allocation_order' and not (
       jsonb_typeof(p_values->'allocation_order') = 'array' and jsonb_array_length(p_values->'allocation_order') = 4
       and p_values->'allocation_order' ?& array['penalty', 'charges', 'interest', 'principal']) then
    raise exception 'Allocation order must list penalty, charges, interest and principal exactly once' using errcode = '22023';
  end if;
  if p_section = 'agreement' and p_values ? 'signing_order' and p_values->>'signing_order' not in ('borrower_first', 'lender_first', 'any') then
    raise exception 'Invalid signing order' using errcode = '22023';
  end if;
  if p_section = 'simplified' then
    if p_values ? 'processing_fee_type' and p_values->>'processing_fee_type' not in ('none', 'fixed', 'percent') then
      raise exception 'Invalid processing fee type' using errcode = '22023';
    end if;
    if p_values ? 'processing_fee_value' and (jsonb_typeof(p_values->'processing_fee_value') <> 'number'
       or (p_values->>'processing_fee_value')::numeric < 0
       or (p_values->>'processing_fee_type' = 'percent' and (p_values->>'processing_fee_value')::numeric > 50)) then
      raise exception 'Processing fee must be a non-negative number (at most 50%% when it is a percentage)' using errcode = '22023';
    end if;
    if p_values ? 'default_term' and (jsonb_typeof(p_values->'default_term') <> 'number'
       or (p_values->>'default_term')::numeric <> trunc((p_values->>'default_term')::numeric)
       or (p_values->>'default_term')::integer not between 1 and 3660) then
      raise exception 'Default duration must be a whole number of installments from 1 to 3,660' using errcode = '22023';
    end if;
  end if;
  insert into public.system_settings (organization_id, settings) values (v_org, '{}'::jsonb) on conflict do nothing;
  select settings into v_current from public.system_settings where organization_id = v_org for update;
  update public.system_settings
  set settings = jsonb_set(v_current, array[p_section], coalesce(v_current -> p_section, '{}'::jsonb) || p_values, true),
      updated_by = auth.uid(), updated_at = now()
  where organization_id = v_org;
  return public.lf_settings(v_org);
end $$;

-- ---------------------------------------------------------------------------
-- Payments: internal posting core shared by record_payment and renewal settlement
-- ---------------------------------------------------------------------------
create or replace function public.lf_record_payment(p_org uuid, p jsonb, p_check_access boolean default true)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := p_org;
  v_key uuid;
  v_existing uuid;
  l public.loans%rowtype;
  i public.loan_installments%rowtype;
  v_amount numeric;
  v_date date;
  v_type text := coalesce(nullif(p->>'payment_type', ''), 'regular');
  v_method text := coalesce(nullif(p->>'method', ''), 'cash');
  v_target uuid := nullif(p->>'installment_id', '')::uuid;
  v_ref text := nullif(trim(p->>'reference_no'), '');
  v_received_by uuid := coalesce(nullif(p->>'received_by', '')::uuid, auth.uid());
  v_left numeric;
  v_pay_id uuid;
  v_number text;
  v_comp text;
  v_take numeric;
  a_p numeric; a_i numeric; a_c numeric; a_pen numeric; a_reb numeric;
  t_p numeric := 0; t_i numeric := 0; t_c numeric := 0; t_pen numeric := 0; t_reb numeric := 0;
  v_quote jsonb;
  v_line jsonb;
  v_receipt_no text;
begin
  begin
    v_key := (p->>'idempotency_key')::uuid;
    v_amount := round((p->>'amount')::numeric, 2);
    v_date := coalesce(nullif(p->>'payment_date', '')::date, public.lf_today(v_org));
  exception when others then
    raise exception 'Invalid payment input' using errcode = '22023';
  end;
  if v_key is null then raise exception 'An idempotency key is required' using errcode = '22023'; end if;

  select id into v_existing from public.loan_payments where organization_id = v_org and idempotency_key = v_key;
  if found then return public.lf_payment_result(v_existing) || jsonb_build_object('duplicate', true); end if;

  select * into l from public.loans where id = nullif(p->>'loan_id', '')::uuid and organization_id = v_org for update;
  if not found or (p_check_access and not public.can_access_loan(l.id)) then raise exception 'Loan not found' using errcode = '22023'; end if;

  -- re-check after the lock: a concurrent duplicate submission may have just committed
  select id into v_existing from public.loan_payments where organization_id = v_org and idempotency_key = v_key;
  if found then return public.lf_payment_result(v_existing) || jsonb_build_object('duplicate', true); end if;

  if l.status not in ('active', 'defaulted') then
    raise exception 'Payments can only be recorded on active loans (status: %)', l.status using errcode = '22023';
  end if;
  if v_amount is null or v_amount <= 0 then raise exception 'Payment amount must be greater than zero' using errcode = '22023'; end if;
  if v_type not in ('regular', 'settlement') then raise exception 'Invalid payment type' using errcode = '22023'; end if;
  if v_method not in ('cash', 'bank_transfer', 'e_wallet', 'check', 'other', 'renewal') then raise exception 'Invalid payment method' using errcode = '22023'; end if;
  if v_date > public.lf_today(v_org) then raise exception 'Payment date cannot be in the future' using errcode = '22023'; end if;
  if v_date < coalesce(l.actual_release_date, l.release_date) then
    raise exception 'Payment date cannot be before the loan release date' using errcode = '22023';
  end if;
  if l.last_payment_date is not null and v_date < l.last_payment_date then
    raise exception 'Payments must be recorded in date order; the latest payment on loan % is dated %',
      l.loan_number, to_char(l.last_payment_date, 'Mon DD, YYYY') using errcode = '22023';
  end if;
  if v_ref is not null and v_method <> 'cash' and exists (
      select 1 from public.loan_payments where organization_id = v_org and method = v_method
      and lower(reference_no) = lower(v_ref) and status = 'posted') then
    raise exception 'A posted payment with reference "%" already exists. This looks like a duplicate submission.', v_ref
      using errcode = '23505';
  end if;
  if v_target is not null and not exists (select 1 from public.loan_installments where id = v_target and loan_id = l.id
                                          and status in ('pending', 'partially_paid')) then
    raise exception 'Selected installment is not open' using errcode = '22023';
  end if;

  -- bring penalties up to the payment date
  perform public.lf_assess_penalties(l.id, v_date);
  select * into l from public.loans where id = l.id;

  if v_type = 'settlement' then
    v_quote := public.lf_settlement_quote(l.id, v_date);
    if v_amount <> (v_quote->>'total')::numeric then
      raise exception 'Early settlement amount must be exactly PHP % as of %',
        to_char((v_quote->>'total')::numeric, 'FM999,999,999,990.00'), to_char(v_date, 'Mon DD, YYYY') using errcode = '22023';
    end if;
  elsif v_amount > l.total_outstanding then
    raise exception 'Amount exceeds the total outstanding balance of PHP %',
      to_char(l.total_outstanding, 'FM999,999,999,990.00') using errcode = '22023';
  end if;

  v_number := public.lf_next_ref(v_org, 'payment', 'PAY');
  insert into public.loan_payments (organization_id, loan_id, borrower_id, payment_number, amount, payment_date,
    method, method_detail, reference_no, proof_path, notes, payment_type, target_installment_id,
    idempotency_key, received_by, recorded_by)
  values (v_org, l.id, l.borrower_id, v_number, v_amount, v_date,
    v_method, nullif(p->>'method_detail', ''), v_ref, nullif(p->>'proof_path', ''), nullif(p->>'notes', ''), v_type, v_target,
    v_key, v_received_by, auth.uid())
  returning id into v_pay_id;

  v_left := v_amount;
  for i in select * from public.loan_installments
           where loan_id = l.id and status in ('pending', 'partially_paid')
           order by (id = v_target) desc nulls last, installment_no
           for update loop
    a_p := 0; a_i := 0; a_c := 0; a_pen := 0; a_reb := 0;
    if v_type = 'settlement' then
      select x into v_line from jsonb_array_elements(v_quote->'lines') x where x->>'installment_id' = i.id::text;
      if v_line is null then continue; end if;
      a_p := (v_line->>'principal')::numeric;
      a_i := (v_line->>'interest')::numeric;
      a_c := (v_line->>'charges')::numeric;
      a_pen := (v_line->>'penalty')::numeric;
      a_reb := (v_line->>'rebate')::numeric;
      v_left := v_left - (a_p + a_i + a_c + a_pen);
    else
      exit when v_left <= 0;
      foreach v_comp in array l.allocation_order loop
        exit when v_left <= 0;
        if v_comp = 'penalty' then
          v_take := least(v_left, i.penalty_due - i.penalty_paid - i.penalty_waived); a_pen := v_take;
        elsif v_comp = 'charges' then
          v_take := least(v_left, i.charges_due - i.charges_paid - i.charges_waived); a_c := v_take;
        elsif v_comp = 'interest' then
          v_take := least(v_left, i.interest_due - i.interest_paid - i.interest_waived); a_i := v_take;
        else
          v_take := least(v_left, i.principal_due - i.principal_paid); a_p := v_take;
        end if;
        v_left := v_left - v_take;
      end loop;
    end if;

    if a_p + a_i + a_c + a_pen + a_reb > 0 then
      update public.loan_installments set
        principal_paid = principal_paid + a_p, interest_paid = interest_paid + a_i,
        charges_paid = charges_paid + a_c, penalty_paid = penalty_paid + a_pen,
        interest_waived = interest_waived + a_reb, last_payment_date = v_date
      where id = i.id;
      perform public.lf_refresh_installment(i.id, v_date);
      insert into public.payment_allocations (organization_id, payment_id, loan_id, installment_id,
        principal_amount, interest_amount, charges_amount, penalty_amount, interest_rebate)
      values (v_org, v_pay_id, l.id, i.id, a_p, a_i, a_c, a_pen, a_reb);
      t_p := t_p + a_p; t_i := t_i + a_i; t_c := t_c + a_c; t_pen := t_pen + a_pen; t_reb := t_reb + a_reb;
    end if;
  end loop;

  if v_left <> 0 then
    raise exception 'Payment could not be fully allocated (remaining PHP %)', v_left;
  end if;

  update public.loans set
    principal_paid = principal_paid + t_p, interest_paid = interest_paid + t_i,
    charges_paid = charges_paid + t_c, penalties_paid = penalties_paid + t_pen,
    total_paid = total_paid + v_amount, interest_waived = interest_waived + t_reb,
    last_payment_date = v_date
  where id = l.id;

  perform public.lf_post_ledger(l.id, 'payment', v_date, v_amount, v_amount, -t_p, -t_i, -t_c, -t_pen,
    'Payment ' || v_number || case when v_method = 'renewal' then ' (settled by renewal loan ' || coalesce(v_ref, '') || ')'
                                   when v_type = 'settlement' then ' (early settlement)' else '' end, v_pay_id);
  if t_reb > 0 then
    perform public.lf_post_ledger(l.id, 'settlement_rebate', v_date, t_reb, 0, 0, -t_reb, 0, 0,
      'Unearned interest rebated on early settlement', v_pay_id);
  end if;

  select * into l from public.loans where id = l.id;
  if l.total_outstanding = 0 then
    update public.loans set status = 'completed', completed_at = now() where id = l.id;
    perform public.lf_system_note(v_org, l.id, l.borrower_id,
      case when v_method = 'renewal' then 'Loan fully settled by renewal.'
           when v_type = 'settlement' then 'Loan fully paid by early settlement.' else 'Loan fully paid.' end);
  end if;

  update public.loan_payments set
    principal_applied = t_p, interest_applied = t_i, charges_applied = t_c, penalty_applied = t_pen,
    interest_rebated = t_reb, balance_after = l.total_outstanding
  where id = v_pay_id;

  v_receipt_no := public.lf_next_ref(v_org, 'receipt', 'OR');
  insert into public.payment_receipts (organization_id, payment_id, receipt_number, issued_by, data)
  values (v_org, v_pay_id, v_receipt_no, auth.uid(), public.lf_receipt_data(v_pay_id, v_receipt_no));

  return public.lf_payment_result(v_pay_id);
end $$;

create or replace function public.record_payment(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v_org uuid := public.lf_require('payments.record');
begin
  if p->>'method' = 'renewal' then
    raise exception 'Renewal settlements are posted automatically when the renewal loan is released' using errcode = '22023';
  end if;
  return public.lf_record_payment(v_org, p, true);
end $$;

-- ---------------------------------------------------------------------------
-- Simplified Lending: parameters, quotes, preview and creation
-- ---------------------------------------------------------------------------
create or replace function public.lf_require_mode(p_org uuid, p_mode text)
returns void language plpgsql stable security definer set search_path = public as $$
begin
  if (select lending_mode from public.organizations where id = p_org) is distinct from p_mode then
    raise exception '%', case when p_mode = 'simplified'
      then 'Simplified Lending is not enabled for this business. Ask your LendFlow administrator to switch the lending mode.'
      else 'This business uses Simplified Lending.' end using errcode = '42501';
  end if;
end $$;

-- Calculation parameters for a simplified loan: everything except the amount and the
-- duration comes from the business settings.
create or replace function public.lf_simplified_params(p_org uuid, p jsonb)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  s jsonb := public.lf_settings(p_org);
  ss jsonb := coalesce(s -> 'simplified', '{}'::jsonb);
  v_rate numeric;
begin
  if nullif(p->>'interest_rate', '') is not null
     and (p->>'interest_rate')::numeric is distinct from (s #>> '{interest,default_rate}')::numeric then
    if not coalesce((ss->>'allow_rate_override')::boolean, false) then
      raise exception 'The interest rate is fixed by your business settings (%). Changing it per loan is not allowed.',
        public.lf_fmt_num((s #>> '{interest,default_rate}')::numeric) || '%' using errcode = '42501';
    end if;
    v_rate := (p->>'interest_rate')::numeric;
  else
    v_rate := (s #>> '{interest,default_rate}')::numeric;
  end if;
  if nullif(p->>'term_count', '') is null then
    raise exception 'Enter the repayment duration' using errcode = '22023';
  end if;
  return public.lf_build_loan_params(p_org, jsonb_build_object(
    'principal', p->'principal',
    'term_count', p->'term_count',
    'interest_rate', v_rate,
    'interest_method', s #>> '{interest,default_method}',
    'rate_period', s #>> '{interest,default_rate_period}',
    'repayment_frequency', s #>> '{repayment,default_frequency}',
    'release_date', public.lf_today(p_org),
    'processing_fee_type', coalesce(ss->>'processing_fee_type', 'none'),
    'processing_fee_value', coalesce((ss->>'processing_fee_value')::numeric, 0),
    'other_charges', 0,
    'charges_treatment', s #>> '{repayment,charges_treatment}'));
end $$;

-- Early-settlement quote of every open loan of a borrower (read only)
create or replace function public.lf_renewal_quote(p_org uuid, p_borrower uuid, p_as_of date)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  l public.loans%rowtype;
  q jsonb;
  v_loans jsonb := '[]'::jsonb;
  v_total numeric := 0;
begin
  for l in select * from public.loans where organization_id = p_org and borrower_id = p_borrower
           and status in ('active', 'defaulted') order by created_at loop
    q := public.lf_settlement_quote(l.id, p_as_of);
    v_loans := v_loans || jsonb_build_object(
      'loan_id', l.id, 'loan_number', l.loan_number, 'status', l.status,
      'principal', l.principal, 'release_date', coalesce(l.actual_release_date, l.release_date),
      'maturity_date', l.maturity_date, 'installment_amount', l.installment_amount,
      'contract_balance', l.total_outstanding,
      'outstanding_principal', l.outstanding_principal,
      'payoff', (q->>'total')::numeric,
      'payoff_principal', (q->>'principal')::numeric, 'payoff_interest', (q->>'interest')::numeric,
      'payoff_charges', (q->>'charges')::numeric, 'payoff_penalties', (q->>'penalties')::numeric,
      'interest_rebate', (q->>'interest_rebate')::numeric,
      'pending_renewal_loan', (select nl.loan_number from public.loan_renewals r join public.loans nl on nl.id = r.new_loan_id
                               where r.old_loan_id = l.id and r.status = 'pending' limit 1));
    v_total := v_total + (q->>'total')::numeric;
  end loop;
  return jsonb_build_object('as_of', p_as_of, 'loans', v_loans, 'total', v_total,
                            'rule', coalesce(public.lf_settings(p_org) #>> '{repayment,early_settlement_rule}', 'prorated'));
end $$;

-- Borrower profile, outstanding balance and loan history for the Reloan / Renew step
create or replace function public.simplified_borrower_status(p_borrower_id uuid)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('loans.create');
  b public.borrowers%rowtype;
  v_quote jsonb;
begin
  select * into b from public.borrowers where id = p_borrower_id and organization_id = v_org;
  if not found or not public.can_access_borrower(b.id) then raise exception 'Borrower not found' using errcode = '22023'; end if;
  v_quote := public.lf_renewal_quote(v_org, b.id, public.lf_today(v_org));
  return jsonb_build_object(
    'borrower', jsonb_build_object('id', b.id, 'borrower_code', b.borrower_code, 'full_name', b.full_name,
      'mobile_number', b.mobile_number, 'email', b.email, 'status', b.status,
      'address', concat_ws(', ', nullif(b.address_line, ''), nullif(b.barangay, ''), nullif(b.city_municipality, ''),
                           nullif(b.province, ''), nullif(b.zip_code, '')),
      'employer_name', b.employer_name, 'profile_photo_path', b.profile_photo_path),
    'co_maker', (select jsonb_build_object('full_name', g.full_name, 'relationship', g.relationship, 'mobile_number', g.mobile_number)
                 from public.guarantors g where g.borrower_id = b.id order by g.created_at desc limit 1),
    'open_loans', v_quote->'loans',
    'total_contract_balance', coalesce((select sum((x->>'contract_balance')::numeric) from jsonb_array_elements(v_quote->'loans') x), 0),
    'total_payoff', (v_quote->>'total')::numeric,
    'as_of', v_quote->'as_of',
    'settlement_rule', v_quote->'rule',
    'has_outstanding', (v_quote->>'total')::numeric > 0,
    'pipeline_loans', coalesce((select jsonb_agg(jsonb_build_object('id', l.id, 'loan_number', l.loan_number, 'status', l.status,
                                  'principal', l.principal, 'created_at', l.created_at) order by l.created_at desc)
                                from public.loans l where l.borrower_id = b.id
                                  and l.status in ('draft', 'pending_review', 'approved', 'awaiting_borrower_signature',
                                                   'awaiting_lender_signature', 'fully_signed', 'ready_for_release')), '[]'::jsonb),
    'history', coalesce((select jsonb_agg(jsonb_build_object('id', l.id, 'loan_number', l.loan_number, 'status', l.status,
                           'principal', l.principal, 'total_repayment', l.total_repayment, 'total_paid', l.total_paid,
                           'release_date', coalesce(l.actual_release_date, l.release_date), 'completed_at', l.completed_at,
                           'lending_mode', l.lending_mode, 'renewal_deduction', l.renewal_deduction) order by l.created_at desc)
                         from public.loans l where l.borrower_id = b.id), '[]'::jsonb));
end $$;

create or replace function public.preview_simplified_loan(p jsonb)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('loans.create');
  v_type text := coalesce(nullif(p->>'transaction_type', ''), 'new');
  v_params jsonb;
  v_calc jsonb;
  v_quote jsonb := null;
  v_deduction numeric := 0;
  v_net numeric;
begin
  perform public.lf_require_mode(v_org, 'simplified');
  if v_type not in ('new', 'renewal') then raise exception 'Unknown transaction type' using errcode = '22023'; end if;
  v_params := public.lf_simplified_params(v_org, p);
  v_calc := public.compute_loan_schedule(v_params);
  if v_type = 'renewal' then
    if nullif(p->>'borrower_id', '') is null or not public.can_access_borrower((p->>'borrower_id')::uuid) then
      raise exception 'Select the borrower to renew' using errcode = '22023';
    end if;
    v_quote := public.lf_renewal_quote(v_org, (p->>'borrower_id')::uuid, public.lf_today(v_org));
    v_deduction := (v_quote->>'total')::numeric;
  end if;
  v_net := (v_calc #>> '{summary,net_proceeds}')::numeric - v_deduction;
  return v_calc || jsonb_build_object(
    'params', v_params,
    'transaction_type', v_type,
    'renewal', v_quote,
    'renewal_deduction', v_deduction,
    'net_release', v_net,
    'rate_overridden', (v_params->>'interest_rate')::numeric is distinct from (public.lf_settings(v_org) #>> '{interest,default_rate}')::numeric);
end $$;

create or replace function public.create_simplified_loan(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('loans.create');
  v_uid uuid := auth.uid();
  v_settings jsonb;
  v_type text := coalesce(nullif(p->>'transaction_type', ''), 'new');
  b public.borrowers%rowtype;
  v_params jsonb;
  v_calc jsonb;
  s jsonb;
  v_quote jsonb;
  v_deduction numeric := 0;
  v_net numeric;
  v_line jsonb;
  v_id uuid;
  v_number text;
  v_status public.loan_status;
  v_approved boolean := false;
  v_today date;
begin
  perform public.lf_require_mode(v_org, 'simplified');
  if v_type not in ('new', 'renewal') then raise exception 'Unknown transaction type' using errcode = '22023'; end if;
  v_settings := public.lf_settings(v_org);
  v_today := public.lf_today(v_org);

  select * into b from public.borrowers where id = nullif(p->>'borrower_id', '')::uuid and organization_id = v_org for update;
  if not found or not public.can_access_borrower(b.id) then raise exception 'Borrower not found' using errcode = '22023'; end if;
  if b.status <> 'active' then
    raise exception 'Borrower is %; loans can only be created for active borrowers', b.status using errcode = '22023';
  end if;

  v_params := public.lf_simplified_params(v_org, p);
  v_calc := public.compute_loan_schedule(v_params);
  s := v_calc->'summary';

  -- outstanding balances always come from the database
  v_quote := public.lf_renewal_quote(v_org, b.id, v_today);
  if v_type = 'new' and jsonb_array_length(v_quote->'loans') > 0 then
    raise exception 'This borrower already has an open loan. Use Reloan / Renew so the outstanding balance is settled from the new loan.'
      using errcode = '22023';
  end if;
  if v_type = 'renewal' then
    for v_line in select * from jsonb_array_elements(v_quote->'loans') loop
      if v_line->>'pending_renewal_loan' is not null then
        raise exception 'Loan % is already being renewed by loan %. Cancel that renewal first.',
          v_line->>'loan_number', v_line->>'pending_renewal_loan' using errcode = '22023';
      end if;
    end loop;
    v_deduction := (v_quote->>'total')::numeric;
  end if;
  v_net := (s->>'net_proceeds')::numeric - v_deduction;
  if v_net <= 0 then
    raise exception 'The new loan amount must be more than the outstanding balance of PHP % (plus any deducted fees). Net amount to release would be PHP %.',
      to_char(v_deduction, 'FM999,999,999,990.00'), to_char(v_net, 'FM999,999,999,990.00') using errcode = '22023';
  end if;

  v_number := public.lf_next_ref(v_org, 'loan', 'LN');
  insert into public.loans (
    organization_id, loan_number, borrower_id, product_id, status, lending_mode,
    principal, interest_method, interest_rate, rate_period, day_count, repayment_frequency, term_count,
    release_date, first_due_date, maturity_date,
    processing_fee_type, processing_fee_value, other_charges, other_charges_description, charges_treatment, rounding,
    penalty_enabled, penalty_type, penalty_rate, penalty_grace_days, allocation_order,
    installment_count, periodic_rate, processing_fee, total_charges, financed_charges, net_proceeds,
    total_interest, total_repayment, installment_amount, effective_annual_rate, eir_monthly, calc_summary,
    purpose, notes, agreement_template_id, signing_order, witness_count,
    loan_officer_id, collector_id, created_by)
  values (
    v_org, v_number, b.id, null, 'draft', 'simplified',
    (s->>'principal')::numeric, (s->>'interest_method')::public.interest_method, (s->>'interest_rate')::numeric,
    (s->>'rate_period')::public.rate_period, s->>'day_count', (s->>'repayment_frequency')::public.repayment_frequency,
    (s->>'term_count')::integer, (s->>'release_date')::date, (s->>'first_due_date')::date, (s->>'last_due_date')::date,
    s->>'processing_fee_type', (s->>'processing_fee_value')::numeric, (s->>'other_charges')::numeric,
    null, s->>'charges_treatment', s->>'rounding',
    (v_params->>'penalty_enabled')::boolean, v_params->>'penalty_type', (v_params->>'penalty_rate')::numeric,
    (v_params->>'penalty_grace_days')::integer, array(select jsonb_array_elements_text(v_params->'allocation_order')),
    (s->>'installment_count')::integer, (s->>'periodic_rate')::numeric, (s->>'processing_fee')::numeric,
    (s->>'total_charges')::numeric, (s->>'financed_charges')::numeric, (s->>'net_proceeds')::numeric,
    (s->>'total_interest')::numeric, (s->>'total_repayment')::numeric, (s->>'installment_amount')::numeric,
    nullif(s->>'effective_annual_rate_pct', '')::numeric, nullif(s->>'eir_monthly_pct', '')::numeric, s,
    coalesce(nullif(trim(p->>'purpose'), ''), case when v_type = 'renewal' then 'Loan renewal' end), nullif(trim(p->>'notes'), ''),
    nullif(v_params->>'agreement_template_id', '')::uuid, v_params->>'signing_order', (v_params->>'witness_count')::integer,
    v_uid, null, v_uid)
  returning id into v_id;

  insert into public.loan_applications (organization_id, loan_id, application_number, purpose, source, requested_amount, requested_term)
  values (v_org, v_id, public.lf_next_ref(v_org, 'application', 'APP'),
          coalesce(nullif(trim(p->>'purpose'), ''), case when v_type = 'renewal' then 'Loan renewal' end),
          case when v_type = 'renewal' then 'renewal' else 'walk_in' end,
          (s->>'principal')::numeric, (s->>'term_count')::integer);

  if v_type = 'renewal' then
    insert into public.loan_renewals (organization_id, new_loan_id, old_loan_id, quoted_amount, quoted_contract_balance,
                                      quoted_interest_rebate, quoted_as_of, created_by)
    select v_org, v_id, (x->>'loan_id')::uuid, (x->>'payoff')::numeric, (x->>'contract_balance')::numeric,
           (x->>'interest_rebate')::numeric, v_today, v_uid
    from jsonb_array_elements(v_quote->'loans') x;

    for v_line in select * from jsonb_array_elements(v_quote->'loans') loop
      perform public.lf_system_note(v_org, (v_line->>'loan_id')::uuid, b.id,
        format('Renewal loan %s created. The payoff balance of PHP %s (as of %s) will be settled from its proceeds when it is released.',
               v_number, to_char((v_line->>'payoff')::numeric, 'FM999,999,999,990.00'), v_today));
    end loop;
  end if;

  perform public.lf_system_note(v_org, v_id, b.id, format('%s %s created (simplified lending) for PHP %s over %s.%s',
    case when v_type = 'renewal' then 'Renewal loan' else 'Loan' end, v_number,
    to_char((s->>'principal')::numeric, 'FM999,999,999,990.00'), s->>'duration_label',
    case when v_deduction > 0 then format(' Previous balance to offset: PHP %s; estimated net release PHP %s.',
      to_char(v_deduction, 'FM999,999,999,990.00'), to_char(v_net, 'FM999,999,999,990.00')) else '' end));

  insert into public.audit_logs (organization_id, actor_id, actor_name, action, table_name, record_id, new_data, context)
  values (v_org, v_uid, (select full_name from public.user_profiles where id = v_uid),
          case when v_type = 'renewal' then 'loan_renewal_created' else 'simplified_loan_created' end,
          'loans', v_id::text,
          jsonb_build_object('loan_number', v_number, 'borrower_id', b.id, 'principal', s->'principal', 'term_count', s->'term_count',
                             'interest_rate', s->'interest_rate', 'net_proceeds', s->'net_proceeds',
                             'renewal_deduction', v_deduction, 'net_release', v_net),
          jsonb_build_object('lending_mode', 'simplified', 'transaction_type', v_type, 'renewal_quote', v_quote));

  perform public.submit_loan(v_id);

  -- optional approval at creation (business setting; respects segregation of duties)
  if coalesce((v_settings #>> '{simplified,approve_on_create}')::boolean, true)
     and public.has_permission('loans.approve')
     and not (coalesce((v_settings #>> '{repayment,require_separate_approver}')::boolean, false)
              and public.current_app_role() <> 'owner') then
    perform public.approve_loan(v_id, 'Approved at loan creation (simplified lending)');
    v_approved := true;
  end if;

  select status into v_status from public.loans where id = v_id;
  return jsonb_build_object('id', v_id, 'loan_number', v_number, 'status', v_status, 'approved', v_approved,
                            'transaction_type', v_type, 'renewal_deduction', v_deduction, 'net_release', v_net,
                            'net_proceeds', s->'net_proceeds');
end $$;

-- ---------------------------------------------------------------------------
-- Release: settle renewed loans first, then disburse the net amount
-- ---------------------------------------------------------------------------
create or replace function public.release_loan(p_loan_id uuid, p jsonb default '{}'::jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('loans.release');
  l public.loans%rowtype;
  ol public.loans%rowtype;
  a public.loan_agreements%rowtype;
  snap public.loan_terms_snapshots%rowtype;
  rn public.loan_renewals%rowtype;
  v_date date := coalesce(nullif(p->>'release_date', '')::date, public.lf_today(v_org));
  r jsonb;
  v_count integer := 0;
  v_quote jsonb;
  v_pay jsonb;
  v_deduction numeric := 0;
  v_settled integer := 0;
begin
  select * into l from public.loans where id = p_loan_id and organization_id = v_org for update;
  if not found or not public.can_access_loan(l.id) then raise exception 'Loan not found'; end if;
  if l.status <> 'ready_for_release' then
    raise exception 'Loan must be approved and fully signed before release (current status: %)', l.status using errcode = '22023';
  end if;
  if l.approved_at is null then raise exception 'Loan has not been approved' using errcode = '22023'; end if;
  select * into a from public.loan_agreements where loan_id = l.id and status = 'fully_signed'
  order by version desc limit 1;
  if not found then raise exception 'No fully signed agreement found for this loan' using errcode = '22023'; end if;
  if a.signed_pdf_path is null or a.signed_pdf_hash is null then
    raise exception 'The signed agreement PDF has not been finalized yet' using errcode = '22023';
  end if;
  select * into snap from public.loan_terms_snapshots where id = a.terms_snapshot_id;
  if v_date > public.lf_today(v_org) then raise exception 'Release date cannot be in the future' using errcode = '22023'; end if;
  if exists (select 1 from public.loan_installments where loan_id = l.id) then
    raise exception 'Installments already exist for this loan';
  end if;

  -- renewal: settle each previous loan in full as of the release date, from the new loan's proceeds
  for rn in select * from public.loan_renewals where new_loan_id = l.id and status = 'pending' order by created_at for update loop
    select * into ol from public.loans where id = rn.old_loan_id for update;
    if ol.status not in ('active', 'defaulted') or ol.total_outstanding <= 0 then
      update public.loan_renewals set status = 'closed_before_release', settled_amount = 0, settled_as_of = v_date, settled_at = now()
      where id = rn.id;
      continue;
    end if;
    perform public.lf_assess_penalties(ol.id, v_date);
    select * into ol from public.loans where id = ol.id;
    v_quote := public.lf_settlement_quote(ol.id, v_date);
    if (v_quote->>'total')::numeric <= 0 then
      raise exception 'Loan % has no payable balance to settle as of %. Close it before releasing this renewal.', ol.loan_number, v_date
        using errcode = '22023';
    end if;
    v_pay := public.lf_record_payment(v_org, jsonb_build_object(
      'loan_id', ol.id, 'amount', (v_quote->>'total')::numeric, 'payment_date', v_date,
      'payment_type', 'settlement', 'method', 'renewal',
      'reference_no', l.loan_number || '/' || ol.loan_number,
      'method_detail', 'Offset against proceeds of renewal loan ' || l.loan_number,
      'notes', format('Settled in full by renewal loan %s released on %s.', l.loan_number, v_date),
      'idempotency_key', gen_random_uuid()), false);
    update public.loan_renewals set
      status = 'settled', settled_amount = (v_quote->>'total')::numeric, settled_contract_balance = ol.total_outstanding,
      settled_interest_rebate = (v_quote->>'interest_rebate')::numeric, settled_as_of = v_date,
      settlement_payment_id = (v_pay->>'payment_id')::uuid, settled_at = now()
    where id = rn.id;
    perform public.lf_system_note(v_org, ol.id, ol.borrower_id,
      format('Settled in full by renewal loan %s: PHP %s offset against its proceeds on %s (payment %s).',
             l.loan_number, to_char((v_quote->>'total')::numeric, 'FM999,999,999,990.00'), v_date, v_pay->>'payment_number'));
    insert into public.audit_logs (organization_id, actor_id, actor_name, action, table_name, record_id, new_data, context)
    values (v_org, auth.uid(), (select full_name from public.user_profiles where id = auth.uid()), 'renewal_balance_settled',
            'loans', ol.id::text,
            jsonb_build_object('loan_number', ol.loan_number, 'settled_amount', v_quote->'total', 'payment_id', v_pay->'payment_id',
                               'payment_number', v_pay->'payment_number', 'interest_rebate', v_quote->'interest_rebate'),
            jsonb_build_object('renewal_loan_id', l.id, 'renewal_loan_number', l.loan_number, 'as_of', v_date));
    v_deduction := v_deduction + (v_quote->>'total')::numeric;
    v_settled := v_settled + 1;
  end loop;
  if v_deduction > 0 and l.net_proceeds - v_deduction <= 0 then
    raise exception 'The previous loan balance of PHP % as of % now equals or exceeds the net proceeds of PHP %. Cancel this renewal and create a new one.',
      to_char(v_deduction, 'FM999,999,999,990.00'), v_date, to_char(l.net_proceeds, 'FM999,999,999,990.00') using errcode = '22023';
  end if;

  for r in select * from jsonb_array_elements(snap.schedule) loop
    insert into public.loan_installments (organization_id, loan_id, installment_no, due_date, opening_balance,
      principal_due, interest_due, charges_due, closing_balance, snapshot_version)
    values (v_org, l.id, (r->>'installment_no')::integer, (r->>'due_date')::date, (r->>'opening_balance')::numeric,
      (r->>'principal')::numeric, (r->>'interest')::numeric, (r->>'charges')::numeric, (r->>'closing_balance')::numeric,
      snap.version);
    v_count := v_count + 1;
  end loop;

  if l.processing_fee > 0 then
    insert into public.loan_charges (organization_id, loan_id, charge_type, description, amount, treatment, created_by)
    values (v_org, l.id, 'processing_fee', 'Processing fee', l.processing_fee, l.charges_treatment, auth.uid());
  end if;
  if l.other_charges > 0 then
    insert into public.loan_charges (organization_id, loan_id, charge_type, description, amount, treatment, created_by)
    values (v_org, l.id, 'other', coalesce(l.other_charges_description, 'Other charges'), l.other_charges, l.charges_treatment, auth.uid());
  end if;

  perform public.lf_post_ledger(l.id, 'disbursement', v_date, l.principal, -l.principal, l.principal, 0, 0, 0,
    'Loan proceeds released' || coalesce(' via ' || nullif(p->>'method', ''), '')
    || case when v_deduction > 0 then format(' (PHP %s offset against previous loan balance; PHP %s paid out)',
         to_char(v_deduction, 'FM999,999,999,990.00'), to_char(l.net_proceeds - v_deduction, 'FM999,999,999,990.00')) else '' end);
  if l.total_charges - l.financed_charges > 0 then
    perform public.lf_post_ledger(l.id, 'fee_deducted', v_date, l.total_charges - l.financed_charges,
      l.total_charges - l.financed_charges, 0, 0, 0, 0, 'Fees and charges deducted from proceeds');
  end if;
  perform public.lf_post_ledger(l.id, 'loan_booked', v_date, l.total_interest + l.financed_charges, 0,
    0, l.total_interest, l.financed_charges, 0, 'Contractual interest and financed charges booked');

  update public.loans set
    status = 'active', released_at = now(), actual_release_date = v_date, released_by = auth.uid(),
    release_method = nullif(p->>'method', ''), release_reference = nullif(p->>'reference', ''),
    activated_at = now(), terms_snapshot_id = snap.id,
    loan_officer_id = coalesce(loan_officer_id, created_by),
    renewal_deduction = v_deduction
  where id = l.id;

  if l.collector_id is not null then
    insert into public.collector_assignments (organization_id, collector_id, borrower_id, loan_id, assigned_by)
    select v_org, l.collector_id, l.borrower_id, l.id, auth.uid()
    where not exists (select 1 from public.collector_assignments where loan_id = l.id and collector_id = l.collector_id and is_active);
  end if;

  perform public.lf_system_note(v_org, l.id, l.borrower_id,
    format('Loan released on %s and activated with %s installments.%s%s', v_date, v_count,
      case when v_date <> l.release_date then format(' (Planned release date was %s; schedule follows the signed agreement.)', l.release_date) else '' end,
      case when v_deduction > 0 then format(' %s previous loan(s) settled for PHP %s; net amount released PHP %s.', v_settled,
        to_char(v_deduction, 'FM999,999,999,990.00'), to_char(l.net_proceeds - v_deduction, 'FM999,999,999,990.00')) else '' end));
  insert into public.audit_logs (organization_id, actor_id, actor_name, action, table_name, record_id, new_data, context)
  values (v_org, auth.uid(), (select full_name from public.user_profiles where id = auth.uid()), 'loan_disbursed', 'loans', l.id::text,
          jsonb_build_object('loan_number', l.loan_number, 'principal', l.principal, 'net_proceeds', l.net_proceeds,
                             'renewal_deduction', v_deduction, 'net_release', l.net_proceeds - v_deduction, 'release_date', v_date,
                             'method', nullif(p->>'method', ''), 'reference', nullif(p->>'reference', '')),
          jsonb_build_object('lending_mode', l.lending_mode));
  perform public.lf_notify_roles(v_org, array['owner', 'admin']::public.app_role[], 'loan_released', 'Loan released',
    format('%s — PHP %s released', l.loan_number, to_char(l.net_proceeds - v_deduction, 'FM999,999,999,990.00')), '/loans/' || l.id);
  return jsonb_build_object('status', 'active', 'installments', v_count, 'renewal_deduction', v_deduction,
                            'net_release', l.net_proceeds - v_deduction, 'settled_loans', v_settled);
end $$;

-- ---------------------------------------------------------------------------
-- Agreements: renewal offset + co-maker in the render data; in-person borrower signing
-- ---------------------------------------------------------------------------
create or replace function public.lf_agreement_render_data(p_agreement_id uuid)
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'agreement', jsonb_build_object('id', a.id, 'agreement_number', a.agreement_number, 'version', a.version, 'kind', a.kind,
                                    'status', a.status, 'generated_at', coalesce(a.generated_at, a.created_at),
                                    'required_signers', to_jsonb(a.required_signers), 'signing_order', a.signing_order,
                                    'unsigned_pdf_path', a.unsigned_pdf_path, 'unsigned_pdf_hash', a.unsigned_pdf_hash,
                                    'signature_fields', a.signature_fields, 'page_count', a.page_count),
    'organization', to_jsonb(o) - 'created_at' - 'updated_at',
    'borrower', (to_jsonb(b) - 'internal_notes' - 'assessment_notes') || jsonb_build_object(
                  'id_type', bi.id_type, 'id_number', bi.id_number,
                  'address_full', concat_ws(', ', nullif(b.address_line, ''), nullif(b.barangay, ''), nullif(b.city_municipality, ''),
                                            nullif(b.province, ''), nullif(b.zip_code, ''))),
    'loan', to_jsonb(l) - 'calc_summary',
    'snapshot', jsonb_build_object('id', s.id, 'version', s.version, 'kind', s.kind, 'terms_hash', s.terms_hash, 'reason', s.reason),
    'summary', s.summary,
    'schedule', s.schedule,
    'template', jsonb_build_object('id', tv.template_id, 'version_id', tv.id, 'version_no', tv.version_no,
                                   'title', tv.title, 'body', tv.body, 'settings', tv.settings),
    'officer', jsonb_build_object('full_name', coalesce(off.full_name, cr.full_name)),
    'settings', public.lf_settings(o.id) -> 'agreement',
    'signatures', coalesce((select jsonb_agg(jsonb_build_object(
                      'id', sg.id, 'signer_role', sg.signer_role, 'signer_name', sg.signer_name,
                      'signature_image_path', sg.signature_image_path, 'signature_image_hash', sg.signature_image_hash,
                      'signed_at', sg.signed_at, 'ip_address', sg.ip_address, 'user_agent', sg.user_agent,
                      'verification_method', sg.verification_method, 'consent_given_at', sg.consent_given_at)
                      order by sg.signed_at, sg.signer_role)
                    from public.agreement_signatures sg where sg.agreement_id = a.id), '[]'::jsonb),
    'renewal', (select jsonb_build_object(
                  'loans', jsonb_agg(jsonb_build_object('loan_number', ol.loan_number, 'amount', coalesce(r.settled_amount, r.quoted_amount),
                                                        'as_of', coalesce(r.settled_as_of, r.quoted_as_of), 'status', r.status)
                                     order by ol.loan_number),
                  'total', sum(coalesce(r.settled_amount, r.quoted_amount)),
                  'as_of', max(coalesce(r.settled_as_of, r.quoted_as_of)),
                  'net_release', l.net_proceeds - sum(coalesce(r.settled_amount, r.quoted_amount)))
                from public.loan_renewals r join public.loans ol on ol.id = r.old_loan_id
                where r.new_loan_id = l.id and r.status in ('pending', 'settled')
                having count(*) > 0),
    'co_maker', (select jsonb_build_object('full_name', g.full_name, 'relationship', g.relationship, 'mobile_number', g.mobile_number,
                                           'address', g.address, 'occupation', g.occupation, 'id_type', g.id_type)
                 from public.guarantors g where g.borrower_id = b.id order by g.created_at desc limit 1))
  from public.loan_agreements a
  join public.loans l on l.id = a.loan_id
  join public.organizations o on o.id = a.organization_id
  join public.borrowers b on b.id = l.borrower_id
  left join public.borrower_identities bi on bi.borrower_id = b.id
  join public.loan_terms_snapshots s on s.id = a.terms_snapshot_id
  left join public.loan_agreement_template_versions tv on tv.id = a.template_version_id
  left join public.user_profiles off on off.id = l.loan_officer_id
  left join public.user_profiles cr on cr.id = l.created_by
  where a.id = p_agreement_id
$$;

create or replace function public.signature_commit(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  a public.loan_agreements%rowtype;
  s public.agreement_signing_sessions%rowtype;
  v_role text := p->>'signer_role';
  v_existing uuid;
  v_sig uuid;
  v_signed text[];
  v_missing text[];
  v_session uuid := nullif(p->>'session_id', '')::uuid;
  v_method text := coalesce(nullif(p->>'verification_method', ''), 'none');
  v_witness uuid := nullif(p->>'witnessed_by', '')::uuid;
begin
  select * into a from public.loan_agreements where id = (p->>'agreement_id')::uuid for update;
  if not found then raise exception 'Agreement not found'; end if;

  select id into v_existing from public.agreement_signatures where agreement_id = a.id and signer_role = v_role;
  if found then
    return jsonb_build_object('duplicate', true, 'signature_id', v_existing) || public.lf_agreement_signing_state(a.id);
  end if;

  if a.status not in ('pending_signatures', 'partially_signed') then
    raise exception 'Agreement is not open for signing (status %)', a.status using errcode = '22023';
  end if;
  if not (v_role = any (a.required_signers)) then
    raise exception 'Role "%" is not a required signer', v_role using errcode = '22023';
  end if;
  if v_method not in ('none', 'in_person') then raise exception 'Invalid verification method' using errcode = '22023'; end if;
  if v_role = 'borrower' and v_session is null and (v_method <> 'in_person' or v_witness is null) then
    raise exception 'A borrower signs through a secure link or in person in front of a staff member' using errcode = '22023';
  end if;

  select array_agg(signer_role) into v_signed from public.agreement_signatures where agreement_id = a.id;
  v_signed := coalesce(v_signed, '{}');
  if a.signing_order = 'borrower_first' and v_role = 'lender' and not ('borrower' = any (v_signed)) then
    raise exception 'The borrower must sign before the lender (signing order: borrower first)' using errcode = '22023';
  end if;
  if a.signing_order = 'lender_first' and v_role = 'borrower' and 'lender' = any (a.required_signers) and not ('lender' = any (v_signed)) then
    raise exception 'The lender must sign before the borrower (signing order: lender first)' using errcode = '22023';
  end if;

  if v_session is not null then
    select * into s from public.agreement_signing_sessions where id = v_session for update;
    if not found or s.agreement_id <> a.id or s.signer_role <> v_role then raise exception 'Invalid signing session'; end if;
    if s.status <> 'verified' then raise exception 'Signing session is not verified (status %)', s.status using errcode = '22023'; end if;
    if s.expires_at < now() then raise exception 'Signing link has expired' using errcode = '22023'; end if;
  else
    -- an in-person borrower signature revokes any outstanding remote link for the same role
    update public.agreement_signing_sessions set status = 'revoked'
    where agreement_id = a.id and signer_role = v_role and status in ('pending', 'opened', 'verified');
    insert into public.agreement_signing_sessions (organization_id, agreement_id, signer_role, signer_name, channel,
      verification_method, status, expires_at, opened_at, verified_at, created_by)
    values (a.organization_id, a.id, v_role, p->>'signer_name', 'in_app', v_method, 'verified', now() + interval '1 hour', now(), now(),
            coalesce(nullif(p->>'signer_user_id', '')::uuid, v_witness))
    returning * into s;
  end if;

  insert into public.agreement_signatures (organization_id, agreement_id, session_id, signer_role, signer_name, signer_user_id,
    signature_image_path, signature_image_hash, consent_text, consent_given_at, verification_method, ip_address, user_agent)
  values (a.organization_id, a.id, s.id, v_role, coalesce(nullif(p->>'signer_name', ''), s.signer_name),
    nullif(p->>'signer_user_id', '')::uuid, p->>'image_path', p->>'image_hash', p->>'consent_text',
    coalesce(nullif(p->>'consent_given_at', '')::timestamptz, now()), s.verification_method, p->>'ip', p->>'user_agent')
  returning id into v_sig;

  update public.agreement_signing_sessions set status = 'signed', signed_at = now() where id = s.id;
  update public.loan_agreements set status = 'partially_signed' where id = a.id;

  perform public.lf_agreement_event(a.id, s.id, 'consent_given', case when v_session is null then 'user' else 'signer' end,
    coalesce(nullif(p->>'signer_user_id', '')::uuid, v_witness), coalesce(nullif(p->>'signer_name', ''), s.signer_name), p->>'ip', p->>'user_agent',
    jsonb_build_object('consent_text', p->>'consent_text', 'in_person', v_method = 'in_person', 'witnessed_by', v_witness));
  perform public.lf_agreement_event(a.id, s.id, 'signed', case when v_session is null then 'user' else 'signer' end,
    coalesce(nullif(p->>'signer_user_id', '')::uuid, v_witness), coalesce(nullif(p->>'signer_name', ''), s.signer_name), p->>'ip', p->>'user_agent',
    jsonb_build_object('signer_role', v_role, 'signature_hash', p->>'image_hash', 'in_person', v_method = 'in_person', 'witnessed_by', v_witness));

  -- move the loan to the next awaiting state
  v_signed := array_append(v_signed, v_role);
  select array_agg(r) into v_missing from unnest(a.required_signers) r where not (r = any (v_signed));
  if a.kind = 'original' and v_missing is not null then
    update public.loans set status = case
        when 'borrower' = any (v_missing) and (a.signing_order <> 'lender_first' or not ('lender' = any (v_missing)))
          then 'awaiting_borrower_signature'::public.loan_status
        when 'lender' = any (v_missing) then 'awaiting_lender_signature'::public.loan_status
        else status end
    where id = a.loan_id and status in ('awaiting_borrower_signature', 'awaiting_lender_signature');
  end if;

  return jsonb_build_object('duplicate', false, 'signature_id', v_sig) || public.lf_agreement_signing_state(a.id);
end $$;

-- ---------------------------------------------------------------------------
-- Platform administration of the lending mode
-- ---------------------------------------------------------------------------
create or replace function public.platform_set_lending_mode(p_org uuid, p_mode text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_admin uuid := public.lf_require_platform_admin();
  v_old text;
begin
  if p_mode not in ('complete', 'simplified') then raise exception 'Invalid lending mode' using errcode = '22023'; end if;
  select lending_mode into v_old from public.organizations where id = p_org for update;
  if not found then raise exception 'Account not found' using errcode = 'P0002'; end if;
  if v_old is distinct from p_mode then
    update public.organizations set lending_mode = p_mode, lending_mode_changed_at = now() where id = p_org;
    insert into public.audit_logs (organization_id, actor_id, actor_name, action, table_name, record_id, old_data, new_data, context)
    values (p_org, v_admin, 'Platform administrator', 'lending_mode_changed', 'organizations', p_org::text,
            jsonb_build_object('lending_mode', v_old), jsonb_build_object('lending_mode', p_mode),
            jsonb_build_object('source', 'platform_admin'));
  end if;
  return public.platform_get_organization(p_org);
end $$;

create or replace function public.platform_list_organizations()
returns jsonb language plpgsql stable security definer set search_path = public as $$
begin
  perform public.lf_require_platform_admin();
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', o.id, 'name', o.name, 'legal_name', o.legal_name, 'city', o.city, 'province', o.province,
      'contact_email', o.contact_email, 'contact_phone', o.contact_phone,
      'status', o.status, 'status_reason', o.status_reason, 'status_changed_at', o.status_changed_at,
      'lending_mode', o.lending_mode,
      'created_at', o.created_at,
      'owner', public.lf_platform_org_owner(o.id),
      'stats', public.lf_platform_org_stats(o.id)) order by o.created_at desc)
    from public.organizations o), '[]'::jsonb);
end $$;

create or replace function public.lf_platform_create_organization(p jsonb, p_owner uuid, p_created_by uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid;
  v_mode text := coalesce(nullif(p->>'lending_mode', ''), 'complete');
begin
  if coalesce(trim(p->>'name'), '') = '' then raise exception 'Business name is required' using errcode = '22023'; end if;
  if v_mode not in ('complete', 'simplified') then raise exception 'Invalid lending mode' using errcode = '22023'; end if;
  if not exists (select 1 from auth.users where id = p_owner) then raise exception 'Owner login not found'; end if;
  if exists (select 1 from public.user_profiles where id = p_owner and organization_id is not null) then
    raise exception 'This login already belongs to a lender account' using errcode = '22023';
  end if;
  if exists (select 1 from public.platform_admins where user_id = p_owner) then
    raise exception 'A platform administrator login cannot own a lender account' using errcode = '22023';
  end if;

  insert into public.organizations (name, legal_name, registration_no, tin, address, city, province, zip_code,
    contact_email, contact_phone, created_by, lending_mode, lending_mode_changed_at)
  values (trim(p->>'name'), nullif(trim(p->>'legal_name'), ''), nullif(trim(p->>'registration_no'), ''),
          nullif(trim(p->>'tin'), ''), nullif(trim(p->>'address'), ''), nullif(trim(p->>'city'), ''),
          nullif(trim(p->>'province'), ''), nullif(trim(p->>'zip_code'), ''), nullif(trim(p->>'contact_email'), ''),
          nullif(trim(p->>'contact_phone'), ''), p_created_by, v_mode, now())
  returning id into v_org;

  perform public.lf_seed_role_permissions(v_org);
  perform public.lf_seed_defaults(v_org);
  perform public.lf_attach_user(p_owner, v_org, 'owner', nullif(trim(p->>'owner_name'), ''), nullif(trim(p->>'owner_phone'), ''));

  insert into public.audit_logs (organization_id, actor_id, actor_name, action, table_name, record_id, new_data, context)
  values (v_org, p_created_by, 'Platform administrator', 'account_created', 'organizations', v_org::text,
          jsonb_build_object('name', trim(p->>'name'), 'owner_user_id', p_owner, 'lending_mode', v_mode),
          jsonb_build_object('source', 'platform_admin'));
  return jsonb_build_object('organization_id', v_org);
end $$;

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
do $$
declare f text;
begin
  foreach f in array array[
    'public.lf_record_payment(uuid, jsonb, boolean)',
    'public.lf_require_mode(uuid, text)',
    'public.lf_simplified_params(uuid, jsonb)',
    'public.lf_renewal_quote(uuid, uuid, date)',
    'public.lf_loan_mode_guard()',
    'public.lf_renewal_status_sync()',
    'public.lf_renewal_payment_guard()',
    'public.lf_agreement_render_data(uuid)',
    'public.signature_commit(jsonb)',
    'public.lf_platform_create_organization(jsonb, uuid, uuid)'
  ] loop
    execute format('revoke execute on function %s from public, anon, authenticated', f);
    execute format('grant execute on function %s to service_role', f);
  end loop;
end $$;
revoke execute on function public.lf_org_status_guard() from public, anon, authenticated;


-- ==== 20261114000014_semimonthly_enum.sql ====
-- =============================================================================
-- LendFlow — Semi-monthly repayment (collection twice a month)
-- The enum value is added in its own migration: PostgreSQL cannot use a newly
-- added enum value inside the transaction that adds it. The engine changes
-- follow in 20261114000015_semimonthly_engine.sql.
-- =============================================================================
alter type public.repayment_frequency add value if not exists 'semimonthly' after 'weekly';


-- ==== 20261114000015_semimonthly_engine.sql ====
-- =============================================================================
-- LendFlow — Semi-monthly repayment engine
--
-- Two installments per month (24 periods per year). The entered rate stays a
-- nominal rate for its own period, so 2% per month is applied as 1% per
-- semi-monthly installment, and a term of N installments covers N/2 months.
--
-- Due dates follow the usual Philippine "kinsenas" pattern, anchored on the
-- first due date to avoid drift: the day of the first due date and the day 15
-- days away from it, every month, clamped to the end of the month.
--   first due 15th  -> 15th, 30th (Feb 28/29), 15th, 30th, ...
--   first due 31st  -> 31st (month end), 16th, month end, 16th, ...
--   first due 29th  -> 29th, 14th, 29th (Feb 28/29), 14th, ...
-- The default first due date is 15 days after release.
-- =============================================================================

create or replace function public.lf_ppy(p_period text, p_day_count text default '30/360')
returns numeric language sql immutable as $$
  select case p_period
    when 'daily' then case when p_day_count = '30/360' then 360 else 365 end
    when 'weekly' then 52
    when 'semimonthly' then 24
    when 'monthly' then 12
    when 'yearly' then 1
  end::numeric
$$;

create or replace function public.lf_period_label(p_period text, p_plural boolean default false)
returns text language sql immutable as $$
  select case p_period
    when 'daily' then case when p_plural then 'days' else 'day' end
    when 'weekly' then case when p_plural then 'weeks' else 'week' end
    when 'semimonthly' then case when p_plural then 'half-months' else 'half-month' end
    when 'monthly' then case when p_plural then 'months' else 'month' end
    when 'yearly' then case when p_plural then 'years' else 'year' end
  end
$$;

-- Due date of semi-monthly installment i (1-based)
create or replace function public.lf_semimonthly_due(p_first date, p_i integer)
returns date language sql immutable as $$
  with a as (
    select extract(day from p_first)::int as d, date_trunc('month', p_first)::date as m0
  ), s as (
    select m0,
           case when d <= 15 then d else d - 15 end as lo,
           case when d <= 15 then d + 15 else d end as hi,
           case when d <= 15 then 0 else 1 end as off
    from a
  ), k as (
    select m0, lo, hi, (p_i - 1) + off as j from s
  )
  select (m0 + make_interval(months => j / 2))::date
         + (least(case when j % 2 = 0 then lo else hi end,
                  extract(day from ((m0 + make_interval(months => j / 2 + 1))::date - 1))::int) - 1)
  from k
$$;

create or replace function public.lf_due_date(p_first date, p_freq text, p_i integer)
returns date language sql immutable as $$
  select case p_freq
    when 'monthly' then (p_first + make_interval(months => p_i - 1))::date
    when 'semimonthly' then public.lf_semimonthly_due(p_first, p_i)
    when 'weekly' then p_first + (p_i - 1) * 7
    when 'daily' then p_first + (p_i - 1)
  end
$$;

create or replace function public.lf_default_first_due(p_release date, p_freq text)
returns date language sql immutable as $$
  select case p_freq
    when 'monthly' then (p_release + interval '1 month')::date
    when 'semimonthly' then p_release + 15
    when 'weekly' then p_release + 7
    when 'daily' then p_release + 1
  end
$$;

create or replace function public.compute_loan_schedule(p jsonb)
returns jsonb language plpgsql stable as $$
declare
  v_principal numeric;
  v_method text := coalesce(nullif(p->>'interest_method', ''), 'flat');
  v_rate numeric := coalesce(nullif(p->>'interest_rate', '')::numeric, 0);
  v_rate_period text := coalesce(nullif(p->>'rate_period', ''), 'monthly');
  v_freq text := coalesce(nullif(p->>'repayment_frequency', ''), 'monthly');
  v_n integer := nullif(p->>'term_count', '')::integer;
  v_release date := coalesce(nullif(p->>'release_date', '')::date, current_date);
  v_first date := nullif(p->>'first_due_date', '')::date;
  v_day_count text := coalesce(nullif(p->>'day_count', ''), '30/360');
  v_rounding text := coalesce(nullif(p->>'rounding', ''), 'centavo');
  v_fee_type text := coalesce(nullif(p->>'processing_fee_type', ''), 'none');
  v_fee_value numeric := coalesce(nullif(p->>'processing_fee_value', '')::numeric, 0);
  v_other numeric := round(coalesce(nullif(p->>'other_charges', '')::numeric, 0), 2);
  v_treatment text := coalesce(nullif(p->>'charges_treatment', ''), 'deducted');

  v_scale integer;
  v_annual numeric;
  v_r numeric;
  v_fee numeric := 0;
  v_charges numeric;
  v_financed numeric;
  v_deducted numeric;
  v_net numeric;

  v_due date[] := '{}';
  v_rate_k numeric[] := '{}';
  v_pmt numeric;
  v_bal numeric;
  v_int numeric;
  v_prin numeric;
  v_chg numeric;
  v_chg_each numeric;
  v_int_each numeric;
  v_prin_each numeric;
  v_total_interest numeric := 0;
  v_flat_interest numeric;
  v_sum_int numeric := 0;
  v_sum_chg numeric := 0;
  v_sum_prin numeric := 0;
  v_rows jsonb := '[]'::jsonb;
  v_flows numeric[] := '{}';
  v_prev date;
  v_lo numeric; v_hi numeric; v_mid numeric; v_res numeric;
  v_iter integer;
  k integer;
  v_irr numeric;
  v_ppy_freq numeric;
  v_last_total numeric;
  v_first_total numeric;
  v_warnings jsonb := '[]'::jsonb;
  v_total_repayment numeric;
begin
  -- ---------- validation ----------
  begin
    v_principal := (p->>'principal')::numeric;
  exception when others then
    raise exception 'Loan amount is not a valid number' using errcode = '22023';
  end;
  if v_principal is null or v_principal <= 0 then
    raise exception 'Loan amount must be greater than zero' using errcode = '22023';
  end if;
  if v_principal > 999999999999 then
    raise exception 'Loan amount is too large' using errcode = '22023';
  end if;
  if v_method not in ('flat', 'diminishing') then
    raise exception 'Unknown interest method "%"', v_method using errcode = '22023';
  end if;
  if v_rate_period not in ('daily', 'weekly', 'monthly', 'yearly') then
    raise exception 'Unknown interest rate period "%"', v_rate_period using errcode = '22023';
  end if;
  if v_freq not in ('daily', 'weekly', 'semimonthly', 'monthly') then
    raise exception 'Unknown repayment frequency "%"', v_freq using errcode = '22023';
  end if;
  if v_day_count not in ('30/360', 'actual/365', 'actual/actual') then
    raise exception 'Unknown day-count basis "%"', v_day_count using errcode = '22023';
  end if;
  if v_rounding not in ('centavo', 'peso') then
    raise exception 'Unknown rounding rule "%"', v_rounding using errcode = '22023';
  end if;
  if v_rate < 0 then
    raise exception 'Interest rate cannot be negative' using errcode = '22023';
  end if;
  if v_rate > 100 then
    raise exception 'Interest rate above 100%% per period is not allowed' using errcode = '22023';
  end if;
  if v_n is null or v_n < 1 then
    raise exception 'Repayment duration must be at least 1 installment' using errcode = '22023';
  end if;
  if v_n > 3660 then
    raise exception 'Repayment duration is too long (maximum 3,660 installments)' using errcode = '22023';
  end if;
  if v_fee_type not in ('none', 'fixed', 'percent') then
    raise exception 'Unknown processing fee type "%"', v_fee_type using errcode = '22023';
  end if;
  if v_fee_value < 0 or v_other < 0 then
    raise exception 'Fees and charges cannot be negative' using errcode = '22023';
  end if;
  if v_treatment not in ('added', 'deducted') then
    raise exception 'Unknown charges treatment "%"', v_treatment using errcode = '22023';
  end if;
  if v_first is null then
    v_first := public.lf_default_first_due(v_release, v_freq);
  end if;
  if v_first <= v_release then
    raise exception 'First repayment date must be after the loan release date' using errcode = '22023';
  end if;

  v_principal := round(v_principal, 2);
  v_scale := case when v_rounding = 'peso' then 0 else 2 end;

  -- ---------- rates ----------
  v_ppy_freq := public.lf_ppy(v_freq, v_day_count);
  v_annual := v_rate / 100 * public.lf_ppy(v_rate_period, v_day_count);
  v_r := v_annual / v_ppy_freq;

  -- ---------- charges ----------
  v_fee := case v_fee_type
    when 'fixed' then round(v_fee_value, 2)
    when 'percent' then round(v_principal * v_fee_value / 100, 2)
    else 0 end;
  v_charges := v_fee + v_other;
  v_financed := case when v_treatment = 'added' then v_charges else 0 end;
  v_deducted := case when v_treatment = 'deducted' then v_charges else 0 end;
  v_net := v_principal - v_deducted;
  if v_net <= 0 then
    raise exception 'Deducted fees and charges cannot equal or exceed the loan amount' using errcode = '22023';
  end if;

  -- ---------- due dates & per-period rates ----------
  v_prev := v_release;
  for k in 1..v_n loop
    v_due := v_due || public.lf_due_date(v_first, v_freq, k);
    if v_method = 'diminishing' and v_day_count <> '30/360' then
      v_rate_k := v_rate_k || (v_annual * public.lf_year_fraction(v_prev, v_due[k], v_day_count));
    else
      v_rate_k := v_rate_k || v_r;
    end if;
    v_prev := v_due[k];
  end loop;

  v_chg_each := round(v_financed / v_n, 2);

  -- ---------- schedule ----------
  if v_method = 'flat' then
    v_flat_interest := round(v_principal * v_r * v_n, 2);
    v_int_each := round(v_flat_interest / v_n, 2);
    v_pmt := round((v_principal + v_flat_interest + v_financed) / v_n, v_scale);
    v_bal := v_principal;
    for k in 1..v_n loop
      if k < v_n then
        v_int := v_int_each;
        v_chg := v_chg_each;
        v_prin := v_pmt - v_int - v_chg;
        if v_prin < 0 then
          raise exception 'Installment rounding produced a negative principal portion; use centavo rounding' using errcode = '22023';
        end if;
        if v_prin > v_bal then v_prin := v_bal; end if;
      else
        v_int := v_flat_interest - v_sum_int;
        v_chg := v_financed - v_sum_chg;
        v_prin := v_bal;
      end if;
      v_rows := v_rows || jsonb_build_object(
        'installment_no', k, 'due_date', v_due[k],
        'opening_balance', v_bal, 'principal', v_prin, 'interest', v_int, 'charges', v_chg,
        'total', v_prin + v_int + v_chg, 'closing_balance', v_bal - v_prin);
      v_flows := v_flows || (v_prin + v_int + v_chg);
      v_sum_int := v_sum_int + v_int;
      v_sum_chg := v_sum_chg + v_chg;
      v_sum_prin := v_sum_prin + v_prin;
      v_bal := v_bal - v_prin;
    end loop;
    v_total_interest := v_sum_int;
  else
    -- diminishing balance: find level payment
    if v_r = 0 then
      v_pmt := v_principal / v_n;
    elsif v_day_count = '30/360' then
      v_pmt := v_principal * v_r / (1 - power(1 + v_r, -v_n));
    else
      -- solve for the level payment that amortizes to zero under actual-day accrual
      v_lo := v_principal / v_n;
      v_hi := v_principal * (1 + v_annual * public.lf_year_fraction(v_release, v_due[v_n], v_day_count)) + 1;
      for v_iter in 1..120 loop
        v_mid := (v_lo + v_hi) / 2;
        v_res := v_principal;
        for k in 1..v_n loop
          v_res := v_res - (v_mid - round(v_res * v_rate_k[k], 2));
        end loop;
        if v_res > 0 then v_lo := v_mid; else v_hi := v_mid; end if;
        exit when v_hi - v_lo < 0.000001;
      end loop;
      v_pmt := (v_lo + v_hi) / 2;
    end if;
    v_pmt := round(v_pmt, v_scale);
    if v_pmt <= 0 then
      raise exception 'Computed installment is zero; check the loan amount and duration' using errcode = '22023';
    end if;

    v_bal := v_principal;
    for k in 1..v_n loop
      v_int := round(v_bal * v_rate_k[k], 2);
      if k < v_n then
        v_prin := v_pmt - v_int;
        v_chg := v_chg_each;
        if v_prin < 0 then
          raise exception 'Installment does not cover interest (negative amortization); adjust the terms' using errcode = '22023';
        end if;
        if v_prin >= v_bal then
          raise exception 'Rounding to whole pesos pays the loan off early; use centavo rounding for these terms' using errcode = '22023';
        end if;
      else
        v_prin := v_bal;
        v_chg := v_financed - v_sum_chg;
      end if;
      v_rows := v_rows || jsonb_build_object(
        'installment_no', k, 'due_date', v_due[k],
        'opening_balance', v_bal, 'principal', v_prin, 'interest', v_int, 'charges', v_chg,
        'total', v_prin + v_int + v_chg, 'closing_balance', v_bal - v_prin);
      v_flows := v_flows || (v_prin + v_int + v_chg);
      v_sum_int := v_sum_int + v_int;
      v_sum_chg := v_sum_chg + v_chg;
      v_sum_prin := v_sum_prin + v_prin;
      v_bal := v_bal - v_prin;
    end loop;
    v_total_interest := v_sum_int;
  end if;

  -- integrity checks
  if v_sum_prin <> v_principal then
    raise exception 'Internal calculation error: principal mismatch (% vs %)', v_sum_prin, v_principal;
  end if;
  if v_sum_chg <> v_financed then
    raise exception 'Internal calculation error: charges mismatch';
  end if;

  v_first_total := (v_rows->0->>'total')::numeric;
  v_last_total := (v_rows->(v_n - 1)->>'total')::numeric;
  v_total_repayment := v_principal + v_total_interest + v_financed;
  v_irr := public.lf_irr(v_net, v_flows);

  if v_first > public.lf_default_first_due(v_release, v_freq) + 31 then
    v_warnings := v_warnings || to_jsonb('The first repayment date is more than one period after release; the first period is longer than the others.'::text);
  end if;
  if v_method = 'flat' and v_n > 1 then
    v_warnings := v_warnings || to_jsonb('Flat interest is computed on the original principal for the full term; the effective interest rate is higher than the nominal rate.'::text);
  end if;

  return jsonb_build_object(
    'summary', jsonb_build_object(
      'principal', v_principal,
      'interest_method', v_method,
      'interest_rate', v_rate,
      'rate_period', v_rate_period,
      'rate_basis', 'nominal',
      'day_count', v_day_count,
      'rounding', v_rounding,
      'repayment_frequency', v_freq,
      'term_count', v_n,
      'installment_count', v_n,
      'duration_label', case when v_freq = 'semimonthly'
                          then v_n || ' semi-monthly payment' || case when v_n <> 1 then 's' else '' end
                               || ' (' || public.lf_fmt_num(v_n / 2.0) || ' month' || case when v_n <> 2 then 's' else '' end || ')'
                          else v_n || ' ' || public.lf_period_label(v_freq, v_n <> 1) end,
      'release_date', v_release,
      'first_due_date', v_first,
      'last_due_date', v_due[v_n],
      'annual_nominal_rate', round(v_annual * 100, 6),
      'periodic_rate', v_r,
      'periodic_rate_pct', round(v_r * 100, 6),
      'processing_fee_type', v_fee_type,
      'processing_fee_value', v_fee_value,
      'processing_fee', v_fee,
      'other_charges', v_other,
      'total_charges', v_charges,
      'charges_treatment', v_treatment,
      'financed_charges', v_financed,
      'deducted_charges', v_deducted,
      'net_proceeds', v_net,
      'total_interest', v_total_interest,
      'total_repayment', v_total_repayment,
      'finance_charge', v_total_interest + v_charges,
      'installment_amount', v_first_total,
      'last_installment_amount', v_last_total,
      'eir_periodic_pct', case when v_irr is null then null else round(v_irr * 100, 6) end,
      'eir_monthly_pct', case when v_irr is null then null else round(v_irr * v_ppy_freq / 12 * 100, 4) end,
      'eir_annual_nominal_pct', case when v_irr is null then null else round(v_irr * v_ppy_freq * 100, 4) end,
      'effective_annual_rate_pct', case when v_irr is null then null
                                        else round(((power(1 + v_irr::double precision, v_ppy_freq::double precision) - 1) * 100)::numeric, 4) end
    ),
    'schedule', v_rows,
    'disclosures', jsonb_build_array(
      format('Interest rate entered: %s%% per %s (nominal).', public.lf_fmt_num(v_rate), public.lf_period_label(v_rate_period)),
      format('Equivalent nominal annual rate: %s%%; rate applied per %s installment period: %s%%.',
             public.lf_fmt_num(round(v_annual * 100, 4)), public.lf_period_label(v_freq),
             public.lf_fmt_num(round(v_r * 100, 6))),
      case when v_method = 'flat'
        then 'Flat method: interest is computed on the original principal for the whole term (Principal × periodic rate × number of installments).'
        else 'Diminishing balance: interest each period is computed on the outstanding principal; payments are level and the final installment absorbs rounding.' end,
      format('Day-count basis: %s. Periods per year — monthly 12, semi-monthly 24, weekly 52, daily %s.', v_day_count,
             case when v_day_count = '30/360' then 360 else 365 end),
      format('Fees and charges of PHP %s are %s.', to_char(v_charges, 'FM999,999,999,990.00'),
             case when v_treatment = 'added' then 'added to the installments' else 'deducted from the loan proceeds' end)
    ),
    'warnings', v_warnings
  );
end $$;


create or replace function public.lf_default_settings()
returns jsonb language sql immutable as $$
  select '{
    "interest": {
      "flat_enabled": true, "diminishing_enabled": true, "default_method": "flat",
      "default_rate": 2, "default_rate_period": "monthly",
      "rate_presets": [1, 1.5, 2, 2.5, 3, 3.5, 4, 5],
      "rate_periods": ["monthly", "yearly"],
      "day_count": "30/360", "rounding": "centavo"
    },
    "repayment": {
      "frequencies": ["monthly", "semimonthly", "weekly", "daily"], "default_frequency": "monthly",
      "first_due_rule": "one_period", "grace_days": 0,
      "early_settlement_rule": "prorated",
      "allocation_order": ["penalty", "charges", "interest", "principal"],
      "charges_treatment": "deducted",
      "require_separate_approver": false
    },
    "penalty": { "enabled": true, "type": "percent_monthly", "rate": 3, "grace_days": 3 },
    "agreement": {
      "default_template_id": null, "signing_order": "borrower_first",
      "require_lender_signature": true, "witness_count": 0,
      "identity_verification": "dob", "link_expiry_hours": 72
    },
    "security": { "session_timeout_minutes": 60, "password_min_length": 10, "audit_logging": true },
    "receipt": { "footer_note": "This is a system-generated acknowledgement receipt. Please keep it for your records." },
    "simplified": {
      "allow_rate_override": false, "default_term": 6,
      "processing_fee_type": "none", "processing_fee_value": 0,
      "approve_on_create": true
    }
  }'::jsonb
$$;


create or replace function public.lf_simplified_params(p_org uuid, p jsonb)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  s jsonb := public.lf_settings(p_org);
  ss jsonb := coalesce(s -> 'simplified', '{}'::jsonb);
  v_rate numeric;
  v_freq text := coalesce(nullif(p->>'repayment_frequency', ''), s #>> '{repayment,default_frequency}');
begin
  -- staff may pick any repayment frequency the business has enabled (e.g. monthly or semi-monthly)
  if not coalesce((s #> '{repayment,frequencies}') ? v_freq, false) and v_freq is distinct from s #>> '{repayment,default_frequency}' then
    raise exception 'Repayment frequency "%" is not enabled in your business settings', v_freq using errcode = '22023';
  end if;
  if nullif(p->>'interest_rate', '') is not null
     and (p->>'interest_rate')::numeric is distinct from (s #>> '{interest,default_rate}')::numeric then
    if not coalesce((ss->>'allow_rate_override')::boolean, false) then
      raise exception 'The interest rate is fixed by your business settings (%). Changing it per loan is not allowed.',
        public.lf_fmt_num((s #>> '{interest,default_rate}')::numeric) || '%' using errcode = '42501';
    end if;
    v_rate := (p->>'interest_rate')::numeric;
  else
    v_rate := (s #>> '{interest,default_rate}')::numeric;
  end if;
  if nullif(p->>'term_count', '') is null then
    raise exception 'Enter the repayment duration' using errcode = '22023';
  end if;
  return public.lf_build_loan_params(p_org, jsonb_build_object(
    'principal', p->'principal',
    'term_count', p->'term_count',
    'interest_rate', v_rate,
    'interest_method', s #>> '{interest,default_method}',
    'rate_period', s #>> '{interest,default_rate_period}',
    'repayment_frequency', v_freq,
    'release_date', public.lf_today(p_org),
    'processing_fee_type', coalesce(ss->>'processing_fee_type', 'none'),
    'processing_fee_value', coalesce((ss->>'processing_fee_value')::numeric, 0),
    'other_charges', 0,
    'charges_treatment', s #>> '{repayment,charges_treatment}'));
end $$;


-- Offer semi-monthly to every business that already saved its own list of frequencies
update public.system_settings
set settings = jsonb_set(settings, '{repayment,frequencies}', (settings #> '{repayment,frequencies}') || '["semimonthly"]'::jsonb)
where jsonb_typeof(settings #> '{repayment,frequencies}') = 'array'
  and not (settings #> '{repayment,frequencies}') ? 'semimonthly';

revoke execute on function public.lf_simplified_params(uuid, jsonb) from public, anon, authenticated;
grant execute on function public.lf_simplified_params(uuid, jsonb) to service_role;


-- ==== 20261114000016_term_months.sql ====
-- =============================================================================
-- LendFlow — Repayment period in months
--
-- Loans can be entered with a repayment period in months (term_months) instead of
-- a number of installments. The engine derives the installments from the
-- collection frequency:
--   monthly      1 per month           (12 months -> 12 installments)
--   semimonthly  2 per month           (12 months -> 24 installments)
--   weekly/daily every actual due date from the first due date up to release + N months
-- Flat interest is unchanged in principle: Principal x monthly rate x months.
-- Total repayment = principal + interest + financed fees; the installment is
-- total / installments and the final installment absorbs rounding.
--
-- loans.term_count keeps meaning "number of installments" (existing records,
-- schedules and products are untouched); loans.term_months records the period
-- when it was entered in months and is part of the frozen terms, so agreements
-- recompute exactly the figures shown on the review screen.
-- =============================================================================

alter table public.loans add column term_months integer check (term_months is null or term_months between 1 and 360);

-- term_months is whatever the server calculation used (never accepted from clients directly)
create or replace function public.lf_loan_term_months()
returns trigger language plpgsql as $$
begin
  new.term_months := nullif(new.calc_summary->>'term_months', '')::integer;
  return new;
end $$;
create trigger lf_loan_term_months before insert or update of calc_summary on public.loans
  for each row execute function public.lf_loan_term_months();

create or replace function public.compute_loan_schedule(p jsonb)
returns jsonb language plpgsql stable as $$
declare
  v_principal numeric;
  v_method text := coalesce(nullif(p->>'interest_method', ''), 'flat');
  v_rate numeric := coalesce(nullif(p->>'interest_rate', '')::numeric, 0);
  v_rate_period text := coalesce(nullif(p->>'rate_period', ''), 'monthly');
  v_freq text := coalesce(nullif(p->>'repayment_frequency', ''), 'monthly');
  v_n integer := nullif(p->>'term_count', '')::integer;
  v_release date := coalesce(nullif(p->>'release_date', '')::date, current_date);
  v_first date := nullif(p->>'first_due_date', '')::date;
  v_months_txt text := nullif(trim(p->>'term_months'), '');
  v_months integer;
  v_target date;
  v_day_count text := coalesce(nullif(p->>'day_count', ''), '30/360');
  v_rounding text := coalesce(nullif(p->>'rounding', ''), 'centavo');
  v_fee_type text := coalesce(nullif(p->>'processing_fee_type', ''), 'none');
  v_fee_value numeric := coalesce(nullif(p->>'processing_fee_value', '')::numeric, 0);
  v_other numeric := round(coalesce(nullif(p->>'other_charges', '')::numeric, 0), 2);
  v_treatment text := coalesce(nullif(p->>'charges_treatment', ''), 'deducted');

  v_scale integer;
  v_annual numeric;
  v_r numeric;
  v_fee numeric := 0;
  v_charges numeric;
  v_financed numeric;
  v_deducted numeric;
  v_net numeric;

  v_due date[] := '{}';
  v_rate_k numeric[] := '{}';
  v_pmt numeric;
  v_bal numeric;
  v_int numeric;
  v_prin numeric;
  v_chg numeric;
  v_chg_each numeric;
  v_int_each numeric;
  v_prin_each numeric;
  v_total_interest numeric := 0;
  v_flat_interest numeric;
  v_sum_int numeric := 0;
  v_sum_chg numeric := 0;
  v_sum_prin numeric := 0;
  v_rows jsonb := '[]'::jsonb;
  v_flows numeric[] := '{}';
  v_prev date;
  v_lo numeric; v_hi numeric; v_mid numeric; v_res numeric;
  v_iter integer;
  k integer;
  v_irr numeric;
  v_ppy_freq numeric;
  v_last_total numeric;
  v_first_total numeric;
  v_warnings jsonb := '[]'::jsonb;
  v_total_repayment numeric;
begin
  -- ---------- validation ----------
  begin
    v_principal := (p->>'principal')::numeric;
  exception when others then
    raise exception 'Loan amount is not a valid number' using errcode = '22023';
  end;
  if v_principal is null or v_principal <= 0 then
    raise exception 'Loan amount must be greater than zero' using errcode = '22023';
  end if;
  if v_principal > 999999999999 then
    raise exception 'Loan amount is too large' using errcode = '22023';
  end if;
  if v_method not in ('flat', 'diminishing') then
    raise exception 'Unknown interest method "%"', v_method using errcode = '22023';
  end if;
  if v_rate_period not in ('daily', 'weekly', 'monthly', 'yearly') then
    raise exception 'Unknown interest rate period "%"', v_rate_period using errcode = '22023';
  end if;
  if v_freq not in ('daily', 'weekly', 'semimonthly', 'monthly') then
    raise exception 'Unknown repayment frequency "%"', v_freq using errcode = '22023';
  end if;
  if v_day_count not in ('30/360', 'actual/365', 'actual/actual') then
    raise exception 'Unknown day-count basis "%"', v_day_count using errcode = '22023';
  end if;
  if v_rounding not in ('centavo', 'peso') then
    raise exception 'Unknown rounding rule "%"', v_rounding using errcode = '22023';
  end if;
  if v_rate < 0 then
    raise exception 'Interest rate cannot be negative' using errcode = '22023';
  end if;
  if v_rate > 100 then
    raise exception 'Interest rate above 100%% per period is not allowed' using errcode = '22023';
  end if;
  -- repayment period in months: the number of installments follows from the frequency
  if v_months_txt is not null then
    if v_months_txt !~ '^[0-9]+$' then
      raise exception 'Repayment period must be a whole number of months' using errcode = '22023';
    end if;
    v_months := v_months_txt::integer;
    if v_months < 1 then
      raise exception 'Repayment period must be at least 1 month' using errcode = '22023';
    end if;
    if v_months > 360 then
      raise exception 'Repayment period is too long (maximum 360 months)' using errcode = '22023';
    end if;
    v_first := coalesce(v_first, public.lf_default_first_due(v_release, v_freq));
    v_target := (v_release + make_interval(months => v_months))::date;
    v_n := case v_freq
      when 'monthly' then v_months
      when 'semimonthly' then v_months * 2
      -- weekly / daily: every actual due date that falls within the repayment period
      when 'weekly' then case when v_first <= v_target then (v_target - v_first) / 7 + 1 else 0 end
      when 'daily' then case when v_first <= v_target then (v_target - v_first) + 1 else 0 end
    end;
    if v_n < 1 then
      raise exception 'The first repayment date falls after the end of the repayment period' using errcode = '22023';
    end if;
  end if;
  if v_n is null or v_n < 1 then
    raise exception 'Repayment duration must be at least 1 installment' using errcode = '22023';
  end if;
  if v_n > 3660 then
    raise exception 'Repayment duration is too long (maximum 3,660 installments)' using errcode = '22023';
  end if;
  if v_fee_type not in ('none', 'fixed', 'percent') then
    raise exception 'Unknown processing fee type "%"', v_fee_type using errcode = '22023';
  end if;
  if v_fee_value < 0 or v_other < 0 then
    raise exception 'Fees and charges cannot be negative' using errcode = '22023';
  end if;
  if v_treatment not in ('added', 'deducted') then
    raise exception 'Unknown charges treatment "%"', v_treatment using errcode = '22023';
  end if;
  if v_first is null then
    v_first := public.lf_default_first_due(v_release, v_freq);
  end if;
  if v_first <= v_release then
    raise exception 'First repayment date must be after the loan release date' using errcode = '22023';
  end if;

  v_principal := round(v_principal, 2);
  v_scale := case when v_rounding = 'peso' then 0 else 2 end;

  -- ---------- rates ----------
  v_ppy_freq := public.lf_ppy(v_freq, v_day_count);
  v_annual := v_rate / 100 * public.lf_ppy(v_rate_period, v_day_count);
  v_r := v_annual / v_ppy_freq;

  -- ---------- charges ----------
  v_fee := case v_fee_type
    when 'fixed' then round(v_fee_value, 2)
    when 'percent' then round(v_principal * v_fee_value / 100, 2)
    else 0 end;
  v_charges := v_fee + v_other;
  v_financed := case when v_treatment = 'added' then v_charges else 0 end;
  v_deducted := case when v_treatment = 'deducted' then v_charges else 0 end;
  v_net := v_principal - v_deducted;
  if v_net <= 0 then
    raise exception 'Deducted fees and charges cannot equal or exceed the loan amount' using errcode = '22023';
  end if;

  -- ---------- due dates & per-period rates ----------
  v_prev := v_release;
  for k in 1..v_n loop
    v_due := v_due || public.lf_due_date(v_first, v_freq, k);
    if v_method = 'diminishing' and v_day_count <> '30/360' then
      v_rate_k := v_rate_k || (v_annual * public.lf_year_fraction(v_prev, v_due[k], v_day_count));
    else
      v_rate_k := v_rate_k || v_r;
    end if;
    v_prev := v_due[k];
  end loop;

  v_chg_each := round(v_financed / v_n, 2);

  -- ---------- schedule ----------
  if v_method = 'flat' then
    -- Principal x monthly rate x months when the period is given in months (any frequency);
    -- otherwise Principal x periodic rate x installments (identical for monthly / semi-monthly)
    v_flat_interest := round(case when v_months is not null then v_principal * v_annual / 12 * v_months
                                  else v_principal * v_r * v_n end, 2);
    v_int_each := round(v_flat_interest / v_n, 2);
    v_pmt := round((v_principal + v_flat_interest + v_financed) / v_n, v_scale);
    v_bal := v_principal;
    for k in 1..v_n loop
      if k < v_n then
        v_int := v_int_each;
        v_chg := v_chg_each;
        v_prin := v_pmt - v_int - v_chg;
        if v_prin < 0 then
          raise exception 'Installment rounding produced a negative principal portion; use centavo rounding' using errcode = '22023';
        end if;
        if v_prin > v_bal then v_prin := v_bal; end if;
      else
        v_int := v_flat_interest - v_sum_int;
        v_chg := v_financed - v_sum_chg;
        v_prin := v_bal;
      end if;
      v_rows := v_rows || jsonb_build_object(
        'installment_no', k, 'due_date', v_due[k],
        'opening_balance', v_bal, 'principal', v_prin, 'interest', v_int, 'charges', v_chg,
        'total', v_prin + v_int + v_chg, 'closing_balance', v_bal - v_prin);
      v_flows := v_flows || (v_prin + v_int + v_chg);
      v_sum_int := v_sum_int + v_int;
      v_sum_chg := v_sum_chg + v_chg;
      v_sum_prin := v_sum_prin + v_prin;
      v_bal := v_bal - v_prin;
    end loop;
    v_total_interest := v_sum_int;
  else
    -- diminishing balance: find level payment
    if v_r = 0 then
      v_pmt := v_principal / v_n;
    elsif v_day_count = '30/360' then
      v_pmt := v_principal * v_r / (1 - power(1 + v_r, -v_n));
    else
      -- solve for the level payment that amortizes to zero under actual-day accrual
      v_lo := v_principal / v_n;
      v_hi := v_principal * (1 + v_annual * public.lf_year_fraction(v_release, v_due[v_n], v_day_count)) + 1;
      for v_iter in 1..120 loop
        v_mid := (v_lo + v_hi) / 2;
        v_res := v_principal;
        for k in 1..v_n loop
          v_res := v_res - (v_mid - round(v_res * v_rate_k[k], 2));
        end loop;
        if v_res > 0 then v_lo := v_mid; else v_hi := v_mid; end if;
        exit when v_hi - v_lo < 0.000001;
      end loop;
      v_pmt := (v_lo + v_hi) / 2;
    end if;
    v_pmt := round(v_pmt, v_scale);
    if v_pmt <= 0 then
      raise exception 'Computed installment is zero; check the loan amount and duration' using errcode = '22023';
    end if;

    v_bal := v_principal;
    for k in 1..v_n loop
      v_int := round(v_bal * v_rate_k[k], 2);
      if k < v_n then
        v_prin := v_pmt - v_int;
        v_chg := v_chg_each;
        if v_prin < 0 then
          raise exception 'Installment does not cover interest (negative amortization); adjust the terms' using errcode = '22023';
        end if;
        if v_prin >= v_bal then
          raise exception 'Rounding to whole pesos pays the loan off early; use centavo rounding for these terms' using errcode = '22023';
        end if;
      else
        v_prin := v_bal;
        v_chg := v_financed - v_sum_chg;
      end if;
      v_rows := v_rows || jsonb_build_object(
        'installment_no', k, 'due_date', v_due[k],
        'opening_balance', v_bal, 'principal', v_prin, 'interest', v_int, 'charges', v_chg,
        'total', v_prin + v_int + v_chg, 'closing_balance', v_bal - v_prin);
      v_flows := v_flows || (v_prin + v_int + v_chg);
      v_sum_int := v_sum_int + v_int;
      v_sum_chg := v_sum_chg + v_chg;
      v_sum_prin := v_sum_prin + v_prin;
      v_bal := v_bal - v_prin;
    end loop;
    v_total_interest := v_sum_int;
  end if;

  -- integrity checks
  if v_sum_prin <> v_principal then
    raise exception 'Internal calculation error: principal mismatch (% vs %)', v_sum_prin, v_principal;
  end if;
  if v_sum_chg <> v_financed then
    raise exception 'Internal calculation error: charges mismatch';
  end if;

  v_first_total := (v_rows->0->>'total')::numeric;
  v_last_total := (v_rows->(v_n - 1)->>'total')::numeric;
  v_total_repayment := v_principal + v_total_interest + v_financed;
  v_irr := public.lf_irr(v_net, v_flows);

  if v_first > public.lf_default_first_due(v_release, v_freq) + 31 then
    v_warnings := v_warnings || to_jsonb('The first repayment date is more than one period after release; the first period is longer than the others.'::text);
  end if;
  if v_method = 'flat' and v_n > 1 then
    v_warnings := v_warnings || to_jsonb('Flat interest is computed on the original principal for the full term; the effective interest rate is higher than the nominal rate.'::text);
  end if;

  return jsonb_build_object(
    'summary', jsonb_build_object(
      'principal', v_principal,
      'interest_method', v_method,
      'interest_rate', v_rate,
      'rate_period', v_rate_period,
      'rate_basis', 'nominal',
      'day_count', v_day_count,
      'rounding', v_rounding,
      'repayment_frequency', v_freq,
      'term_count', v_n,
      'term_months', v_months,
      'monthly_rate_pct', round(v_annual / 12 * 100, 6),
      'installment_count', v_n,
      'duration_label', case when v_months is not null
                          then v_months || ' month' || case when v_months <> 1 then 's' else '' end || ' ('
                               || v_n || ' ' || case v_freq when 'semimonthly' then 'semi-monthly' else v_freq end
                               || ' payment' || case when v_n <> 1 then 's' else '' end || ')'
                        when v_freq = 'semimonthly'
                          then v_n || ' semi-monthly payment' || case when v_n <> 1 then 's' else '' end
                               || ' (' || public.lf_fmt_num(v_n / 2.0) || ' month' || case when v_n <> 2 then 's' else '' end || ')'
                          else v_n || ' ' || public.lf_period_label(v_freq, v_n <> 1) end,
      'release_date', v_release,
      'first_due_date', v_first,
      'last_due_date', v_due[v_n],
      'annual_nominal_rate', round(v_annual * 100, 6),
      'periodic_rate', v_r,
      'periodic_rate_pct', round(v_r * 100, 6),
      'processing_fee_type', v_fee_type,
      'processing_fee_value', v_fee_value,
      'processing_fee', v_fee,
      'other_charges', v_other,
      'total_charges', v_charges,
      'charges_treatment', v_treatment,
      'financed_charges', v_financed,
      'deducted_charges', v_deducted,
      'net_proceeds', v_net,
      'total_interest', v_total_interest,
      'total_repayment', v_total_repayment,
      'finance_charge', v_total_interest + v_charges,
      'installment_amount', v_first_total,
      'last_installment_amount', v_last_total,
      'eir_periodic_pct', case when v_irr is null then null else round(v_irr * 100, 6) end,
      'eir_monthly_pct', case when v_irr is null then null else round(v_irr * v_ppy_freq / 12 * 100, 4) end,
      'eir_annual_nominal_pct', case when v_irr is null then null else round(v_irr * v_ppy_freq * 100, 4) end,
      'effective_annual_rate_pct', case when v_irr is null then null
                                        else round(((power(1 + v_irr::double precision, v_ppy_freq::double precision) - 1) * 100)::numeric, 4) end
    ),
    'schedule', v_rows,
    'disclosures', jsonb_build_array(
      format('Interest rate entered: %s%% per %s (nominal).', public.lf_fmt_num(v_rate), public.lf_period_label(v_rate_period)),
      format('Equivalent nominal annual rate: %s%%; rate applied per %s installment period: %s%%.',
             public.lf_fmt_num(round(v_annual * 100, 4)), public.lf_period_label(v_freq),
             public.lf_fmt_num(round(v_r * 100, 6))),
      case when v_method = 'flat'
        then case when v_months is not null
          then format('Flat method: interest is computed on the original principal for the whole term (Principal × monthly rate × %s months), spread over %s installments.', v_months, v_n)
          else 'Flat method: interest is computed on the original principal for the whole term (Principal × periodic rate × number of installments).' end
        else 'Diminishing balance: interest each period is computed on the outstanding principal; payments are level and the final installment absorbs rounding.' end,
      format('Day-count basis: %s. Periods per year — monthly 12, semi-monthly 24, weekly 52, daily %s.', v_day_count,
             case when v_day_count = '30/360' then 360 else 365 end),
      format('Fees and charges of PHP %s are %s.', to_char(v_charges, 'FM999,999,999,990.00'),
             case when v_treatment = 'added' then 'added to the installments' else 'deducted from the loan proceeds' end)
    ),
    'warnings', v_warnings
  );
end $$;



create or replace function public.lf_build_loan_params(p_org uuid, p jsonb)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  s jsonb := public.lf_settings(p_org);
  pr public.loan_products%rowtype;
  v_has_product boolean := false;
  v_method text;
  v_principal numeric;
  v_term integer;
  v jsonb;
begin
  if nullif(p->>'product_id', '') is not null then
    select * into pr from public.loan_products
    where id = (p->>'product_id')::uuid and organization_id = p_org;
    if not found then raise exception 'Loan product not found' using errcode = '22023'; end if;
    if not pr.is_active then raise exception 'Loan product "%" is inactive', pr.name using errcode = '22023'; end if;
    v_has_product := true;
  end if;

  v_method := coalesce(nullif(p->>'interest_method', ''),
                       case when v_has_product then pr.interest_method::text end,
                       s #>> '{interest,default_method}');
  if v_method = 'flat' and not coalesce((s #>> '{interest,flat_enabled}')::boolean, true) then
    raise exception 'Flat interest is disabled in settings' using errcode = '22023';
  end if;
  if v_method = 'diminishing' and not coalesce((s #>> '{interest,diminishing_enabled}')::boolean, true) then
    raise exception 'Diminishing balance interest is disabled in settings' using errcode = '22023';
  end if;

  v := jsonb_build_object(
    'product_id', case when v_has_product then pr.id end,
    'principal', p->'principal',
    'interest_method', v_method,
    'interest_rate', coalesce(nullif(p->>'interest_rate', '')::numeric,
                              case when v_has_product then pr.interest_rate end,
                              (s #>> '{interest,default_rate}')::numeric),
    'rate_period', coalesce(nullif(p->>'rate_period', ''), case when v_has_product then pr.rate_period::text end,
                            s #>> '{interest,default_rate_period}'),
    'repayment_frequency', coalesce(nullif(p->>'repayment_frequency', ''), case when v_has_product then pr.repayment_frequency::text end,
                                    s #>> '{repayment,default_frequency}'),
    'term_count', case when nullif(p->>'term_months', '') is null
                       then coalesce(nullif(p->>'term_count', '')::integer, case when v_has_product then pr.default_term end) end,
    'term_months', nullif(p->>'term_months', ''),
    'release_date', coalesce(nullif(p->>'release_date', '')::date, public.lf_today(p_org)),
    'first_due_date', nullif(p->>'first_due_date', ''),
    'day_count', coalesce(nullif(p->>'day_count', ''), case when v_has_product then pr.day_count end, s #>> '{interest,day_count}'),
    'rounding', coalesce(nullif(p->>'rounding', ''), s #>> '{interest,rounding}'),
    'processing_fee_type', coalesce(nullif(p->>'processing_fee_type', ''), case when v_has_product then pr.processing_fee_type end, 'none'),
    'processing_fee_value', coalesce(nullif(p->>'processing_fee_value', '')::numeric, case when v_has_product then pr.processing_fee_value end, 0),
    'other_charges', coalesce(nullif(p->>'other_charges', '')::numeric, 0),
    'other_charges_description', nullif(p->>'other_charges_description', ''),
    'charges_treatment', coalesce(nullif(p->>'charges_treatment', ''), case when v_has_product then pr.charges_treatment end,
                                  s #>> '{repayment,charges_treatment}'),
    'penalty_enabled', coalesce((p->>'penalty_enabled')::boolean,
                                case when v_has_product then pr.penalty_enabled end,
                                (s #>> '{penalty,enabled}')::boolean, false),
    'penalty_type', coalesce(nullif(p->>'penalty_type', ''), case when v_has_product and pr.penalty_enabled then pr.penalty_type end,
                             s #>> '{penalty,type}'),
    'penalty_rate', coalesce(nullif(p->>'penalty_rate', '')::numeric, case when v_has_product and pr.penalty_enabled then pr.penalty_rate end,
                             (s #>> '{penalty,rate}')::numeric, 0),
    'penalty_grace_days', coalesce(nullif(p->>'penalty_grace_days', '')::integer, case when v_has_product and pr.penalty_enabled then pr.penalty_grace_days end,
                                   (s #>> '{penalty,grace_days}')::integer, 0),
    'allocation_order', coalesce(p->'allocation_order', s #> '{repayment,allocation_order}'),
    'signing_order', coalesce(nullif(p->>'signing_order', ''), s #>> '{agreement,signing_order}'),
    'witness_count', coalesce(nullif(p->>'witness_count', '')::integer, (s #>> '{agreement,witness_count}')::integer, 0),
    'agreement_template_id', coalesce(nullif(p->>'agreement_template_id', '')::uuid,
                                      case when v_has_product then pr.agreement_template_id end,
                                      nullif(s #>> '{agreement,default_template_id}', '')::uuid)
  );

  v_principal := nullif(v->>'principal', '')::numeric;
  v_term := nullif(v->>'term_count', '')::integer;
  if v_has_product then
    if pr.min_amount is not null and v_principal < pr.min_amount then
      raise exception 'Loan amount is below the product minimum of PHP %', to_char(pr.min_amount, 'FM999,999,999,990.00') using errcode = '22023';
    end if;
    if pr.max_amount is not null and v_principal > pr.max_amount then
      raise exception 'Loan amount exceeds the product maximum of PHP %', to_char(pr.max_amount, 'FM999,999,999,990.00') using errcode = '22023';
    end if;
    if pr.min_term is not null and v_term < pr.min_term then
      raise exception 'Duration is below the product minimum of % installments', pr.min_term using errcode = '22023';
    end if;
    if pr.max_term is not null and v_term > pr.max_term then
      raise exception 'Duration exceeds the product maximum of % installments', pr.max_term using errcode = '22023';
    end if;
  end if;
  if (v->>'penalty_enabled')::boolean and coalesce(v->>'penalty_type', '') not in ('fixed', 'percent_once', 'percent_daily', 'percent_monthly') then
    raise exception 'A valid penalty type is required when penalties are enabled' using errcode = '22023';
  end if;
  if jsonb_typeof(v->'allocation_order') <> 'array' or jsonb_array_length(v->'allocation_order') <> 4
     or not (v->'allocation_order' ?& array['penalty', 'charges', 'interest', 'principal']) then
    raise exception 'Payment allocation order must list penalty, charges, interest and principal exactly once' using errcode = '22023';
  end if;
  if v->>'signing_order' not in ('borrower_first', 'lender_first', 'any') then
    raise exception 'Invalid signing order' using errcode = '22023';
  end if;
  return v;
end $$;


create or replace function public.lf_loan_params(l public.loans)
returns jsonb language sql stable as $$
  select jsonb_build_object(
    'product_id', l.product_id, 'principal', l.principal, 'interest_method', l.interest_method,
    'interest_rate', l.interest_rate, 'rate_period', l.rate_period, 'repayment_frequency', l.repayment_frequency,
    'term_count', l.term_count, 'term_months', l.term_months, 'release_date', l.release_date, 'first_due_date', l.first_due_date,
    'day_count', l.day_count, 'rounding', l.rounding,
    'processing_fee_type', l.processing_fee_type, 'processing_fee_value', l.processing_fee_value,
    'other_charges', l.other_charges, 'other_charges_description', l.other_charges_description,
    'charges_treatment', l.charges_treatment,
    'penalty_enabled', l.penalty_enabled, 'penalty_type', l.penalty_type, 'penalty_rate', l.penalty_rate,
    'penalty_grace_days', l.penalty_grace_days, 'allocation_order', to_jsonb(l.allocation_order),
    'signing_order', l.signing_order, 'witness_count', l.witness_count,
    'agreement_template_id', l.agreement_template_id)
$$;


create or replace function public.lf_simplified_params(p_org uuid, p jsonb)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  s jsonb := public.lf_settings(p_org);
  ss jsonb := coalesce(s -> 'simplified', '{}'::jsonb);
  v_rate numeric;
  v_freq text := coalesce(nullif(p->>'repayment_frequency', ''), s #>> '{repayment,default_frequency}');
begin
  -- staff may pick any repayment frequency the business has enabled (e.g. monthly or semi-monthly)
  if not coalesce((s #> '{repayment,frequencies}') ? v_freq, false) and v_freq is distinct from s #>> '{repayment,default_frequency}' then
    raise exception 'Repayment frequency "%" is not enabled in your business settings', v_freq using errcode = '22023';
  end if;
  if nullif(p->>'interest_rate', '') is not null
     and (p->>'interest_rate')::numeric is distinct from (s #>> '{interest,default_rate}')::numeric then
    if not coalesce((ss->>'allow_rate_override')::boolean, false) then
      raise exception 'The interest rate is fixed by your business settings (%). Changing it per loan is not allowed.',
        public.lf_fmt_num((s #>> '{interest,default_rate}')::numeric) || '%' using errcode = '42501';
    end if;
    v_rate := (p->>'interest_rate')::numeric;
  else
    v_rate := (s #>> '{interest,default_rate}')::numeric;
  end if;
  if nullif(p->>'term_count', '') is null and nullif(p->>'term_months', '') is null then
    raise exception 'Enter the repayment period in months' using errcode = '22023';
  end if;
  return public.lf_build_loan_params(p_org, jsonb_build_object(
    'principal', p->'principal',
    'term_count', p->'term_count',
    'term_months', p->'term_months',
    'interest_rate', v_rate,
    'interest_method', s #>> '{interest,default_method}',
    'rate_period', s #>> '{interest,default_rate_period}',
    'repayment_frequency', v_freq,
    'release_date', public.lf_today(p_org),
    'processing_fee_type', coalesce(ss->>'processing_fee_type', 'none'),
    'processing_fee_value', coalesce((ss->>'processing_fee_value')::numeric, 0),
    'other_charges', 0,
    'charges_treatment', s #>> '{repayment,charges_treatment}'));
end $$;



-- v_loans selects l.*; recreate it so it exposes term_months
drop view public.v_loans;
create view public.v_loans with (security_invoker = true) as
select
  l.*,
  b.full_name as borrower_name, b.borrower_code, b.mobile_number as borrower_mobile, b.profile_photo_path as borrower_photo_path,
  p.name as product_name,
  lo.full_name as loan_officer_name,
  co.full_name as collector_name,
  agg.next_due_date, agg.next_due_amount, agg.overdue_amount, agg.overdue_count, agg.oldest_overdue_date,
  case when agg.oldest_overdue_date is null then 0 else public.lf_today(l.organization_id) - agg.oldest_overdue_date end as days_overdue,
  agg.paid_installments, agg.total_installments,
  (select a.status from public.loan_agreements a where a.loan_id = l.id order by a.version desc limit 1) as agreement_status
from public.loans l
join public.borrowers b on b.id = l.borrower_id
left join public.loan_products p on p.id = l.product_id
left join public.user_profiles lo on lo.id = l.loan_officer_id
left join public.user_profiles co on co.id = l.collector_id
left join lateral (
  select
    min(i.due_date) filter (where i.status in ('pending', 'partially_paid') and i.due_date >= public.lf_today(l.organization_id)) as next_due_date,
    (select public.lf_inst_remaining(x) from public.loan_installments x
      where x.loan_id = l.id and x.status in ('pending', 'partially_paid') order by x.installment_no limit 1) as next_due_amount,
    coalesce(sum(public.lf_inst_remaining(i)) filter (where i.status in ('pending', 'partially_paid') and i.due_date < public.lf_today(l.organization_id)), 0) as overdue_amount,
    count(*) filter (where i.status in ('pending', 'partially_paid') and i.due_date < public.lf_today(l.organization_id)) as overdue_count,
    min(i.due_date) filter (where i.status in ('pending', 'partially_paid') and i.due_date < public.lf_today(l.organization_id)) as oldest_overdue_date,
    count(*) filter (where i.status = 'paid') as paid_installments,
    count(*) filter (where i.status <> 'rescheduled') as total_installments
  from public.loan_installments i where i.loan_id = l.id
) agg on true;
grant select on public.v_loans to authenticated, service_role;

revoke execute on function public.lf_loan_term_months() from public, anon, authenticated;
revoke execute on function public.lf_simplified_params(uuid, jsonb) from public, anon, authenticated;
grant execute on function public.lf_simplified_params(uuid, jsonb) to service_role;

