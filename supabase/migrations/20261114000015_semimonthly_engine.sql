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
