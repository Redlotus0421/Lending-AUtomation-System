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
