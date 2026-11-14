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
