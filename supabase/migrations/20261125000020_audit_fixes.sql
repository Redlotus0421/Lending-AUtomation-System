-- =============================================================================
-- LendFlow — fixes from the 2026-11-25 system audit
--  1. reverse_payment: refuse to reverse a payment whose installments were closed by a reschedule
--     (the money was stranded and the loan could never be paid off).
--  2. compute_loan_schedule: spreading interest / financed charges over many installments could
--     leave a negative last installment, which then failed at release.
--  3. reschedule_loan: semi-monthly loans keep their pay days.
--  4. is_demo can only be set by seed_demo_data (it bypassed the lending-mode lock).
-- =============================================================================

-- 1. ---------------------------------------------------------------------
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
  -- the installments this payment paid were closed by a reschedule: putting the money back on them
  -- would strand it (closed installments take no new payments) and the loan could never be closed
  if exists (select 1 from public.payment_allocations pa join public.loan_installments ins on ins.id = pa.installment_id
             where pa.payment_id = pay.id and not pa.is_reversal and ins.status = 'rescheduled') then
    raise exception 'This payment was made before the loan was rescheduled and can no longer be reversed. Record an adjustment instead.'
      using errcode = '22023';
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

-- 2. ---------------------------------------------------------------------
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
  v_days integer[];
  v_default_first date;
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
  -- semi-monthly pay days chosen by the lender (e.g. the 5th and 20th, matching the borrower's payday)
  if v_freq = 'semimonthly' and jsonb_typeof(p->'pay_days') = 'array' and jsonb_array_length(p->'pay_days') > 0 then
    begin
      select array_agg(x::integer order by x::integer) into v_days from jsonb_array_elements_text(p->'pay_days') x;
    exception when others then
      raise exception 'Pay days must be whole days of the month' using errcode = '22023';
    end;
    if array_length(v_days, 1) <> 2 or v_days[1] < 1 or v_days[2] > 31 or v_days[1] = v_days[2] then
      raise exception 'Choose two different pay days between the 1st and the 31st' using errcode = '22023';
    end if;
    if v_days[2] - v_days[1] < 7 or v_days[2] - v_days[1] > 23 then
      raise exception 'The two pay days must be at least 7 days apart (e.g. the 5th and 20th, or the 15th and 30th)' using errcode = '22023';
    end if;
  end if;
  v_default_first := case when v_days is not null then public.lf_next_payday(v_release + 10, v_days)
                          else public.lf_default_first_due(v_release, v_freq) end;
  if v_days is not null and v_first is not null and v_first <> public.lf_next_payday(v_first, v_days) then
    raise exception 'The first payment date must fall on one of the pay days (the % and %)',
      public.lf_ordinal(v_days[1]), public.lf_ordinal(v_days[2]) using errcode = '22023';
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
    v_first := coalesce(v_first, v_default_first);
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
    v_first := v_default_first;
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
    v_due := v_due || case when v_days is not null then public.lf_payday_due(v_first, v_days, k)
                           else public.lf_due_date(v_first, v_freq, k) end;
    if v_method = 'diminishing' and v_day_count <> '30/360' then
      v_rate_k := v_rate_k || (v_annual * public.lf_year_fraction(v_prev, v_due[k], v_day_count));
    else
      v_rate_k := v_rate_k || v_r;
    end if;
    v_prev := v_due[k];
  end loop;

  -- the last installment absorbs the rounding difference: never let n-1 rounded-up shares exceed the total
  v_chg_each := round(v_financed / v_n, 2);
  if v_chg_each * (v_n - 1) > v_financed then v_chg_each := trunc(v_financed / v_n, 2); end if;

  -- ---------- schedule ----------
  if v_method = 'flat' then
    -- Principal x monthly rate x months when the period is given in months (any frequency);
    -- otherwise Principal x periodic rate x installments (identical for monthly / semi-monthly)
    v_flat_interest := round(case when v_months is not null then v_principal * v_annual / 12 * v_months
                                  else v_principal * v_r * v_n end, 2);
    v_int_each := round(v_flat_interest / v_n, 2);
    if v_int_each * (v_n - 1) > v_flat_interest then v_int_each := trunc(v_flat_interest / v_n, 2); end if;
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

  if v_first > v_default_first + 31 then
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
      'pay_days', to_jsonb(v_days),
      'monthly_rate_pct', round(v_annual / 12 * 100, 6),
      'installment_count', v_n,
      'duration_label', case when v_months is not null
                          then v_months || ' month' || case when v_months <> 1 then 's' else '' end || ' ('
                               || v_n || ' ' || case v_freq when 'semimonthly' then 'semi-monthly' else v_freq end
                               || ' payment' || case when v_n <> 1 then 's' else '' end
                               || case when v_days is not null then ', every ' || public.lf_ordinal(v_days[1]) || ' and ' || public.lf_ordinal(v_days[2]) else '' end || ')'
                        when v_freq = 'semimonthly' and v_days is not null
                          then v_n || ' semi-monthly payment' || case when v_n <> 1 then 's' else '' end
                               || ' (every ' || public.lf_ordinal(v_days[1]) || ' and ' || public.lf_ordinal(v_days[2]) || ')'
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

-- 3. ---------------------------------------------------------------------
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
    -- semi-monthly: keep the borrower's pay days unless new ones are given
    'pay_days', case when coalesce(nullif(p->>'repayment_frequency', ''), l.repayment_frequency::text) = 'semimonthly' then
      case when jsonb_typeof(p->'pay_days') = 'array' and jsonb_array_length(p->'pay_days') > 0 then p->'pay_days'
           else to_jsonb(l.pay_days) end end,
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

-- ---------------------------------------------------------------------------
-- 4. Demo flag: only the sample-data loader may create demo loans
-- ---------------------------------------------------------------------------
create or replace function public.lf_loan_demo_guard()
returns trigger language plpgsql set search_path = public as $$
begin
  if new.is_demo and (tg_op = 'INSERT' or not old.is_demo)
     and coalesce(current_setting('lendflow.seeding_demo', true), '') <> 'on' then
    raise exception 'Demo loans can only be created by the sample-data loader' using errcode = '42501';
  end if;
  return new;
end $$;
revoke execute on function public.lf_loan_demo_guard() from public, anon, authenticated;

drop trigger if exists lf_loan_demo_guard on public.loans;
create trigger lf_loan_demo_guard before insert or update of is_demo on public.loans
  for each row execute function public.lf_loan_demo_guard();

-- seed_demo_data keeps its body; the public entry point now marks the transaction as a demo seed
alter function public.seed_demo_data() rename to lf_seed_demo_data_impl;
revoke execute on function public.lf_seed_demo_data_impl() from public, anon, authenticated;

create or replace function public.seed_demo_data()
returns jsonb language plpgsql security definer set search_path = public as $$
declare r jsonb;
begin
  perform set_config('lendflow.seeding_demo', 'on', true);
  r := public.lf_seed_demo_data_impl();
  perform set_config('lendflow.seeding_demo', '', true);
  return r;
end $$;
revoke execute on function public.seed_demo_data() from public, anon;
grant execute on function public.seed_demo_data() to authenticated;
