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
