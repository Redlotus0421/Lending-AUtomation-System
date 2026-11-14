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
