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
