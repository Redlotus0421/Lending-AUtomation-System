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
