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
