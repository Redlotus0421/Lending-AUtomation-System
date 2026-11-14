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
