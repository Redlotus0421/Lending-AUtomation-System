-- =============================================================================
-- LendFlow — Agreement templates, generation, signing sessions and signatures
--
-- PDF rendering/embedding happens in Edge Functions (server-side). The database
-- owns every state transition, in transactions, with row locks:
--   agreement_begin       (user)    freeze terms snapshot, reserve number
--   agreement_complete    (service) register the unsigned PDF + hash
--   signature_commit      (service) idempotent: one signature per role
--   agreement_set_pdf     (service) compare-and-set the latest embedded PDF;
--                                    finalizes when all signatures are embedded
-- Signed agreements are never overwritten: amendments create a new version.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Template versioning
-- ---------------------------------------------------------------------------
create or replace function public.save_agreement_template(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('templates.manage');
  v_id uuid := nullif(p->>'id', '')::uuid;
  v_version integer;
  v_version_id uuid;
begin
  if coalesce(trim(p->>'name'), '') = '' then raise exception 'Template name is required' using errcode = '22023'; end if;
  if coalesce(trim(p->>'title'), '') = '' then raise exception 'Agreement title is required' using errcode = '22023'; end if;
  if coalesce(trim(p->>'body'), '') = '' then raise exception 'Template body is required' using errcode = '22023'; end if;

  if v_id is null then
    insert into public.loan_agreement_templates (organization_id, name, description, is_active, created_by)
    values (v_org, trim(p->>'name'), nullif(p->>'description', ''), coalesce((p->>'is_active')::boolean, true), auth.uid())
    returning id into v_id;
  else
    update public.loan_agreement_templates
    set name = trim(p->>'name'), description = nullif(p->>'description', ''),
        is_active = coalesce((p->>'is_active')::boolean, is_active)
    where id = v_id and organization_id = v_org;
    if not found then raise exception 'Template not found'; end if;
  end if;

  select coalesce(max(version_no), 0) + 1 into v_version from public.loan_agreement_template_versions where template_id = v_id;
  insert into public.loan_agreement_template_versions (organization_id, template_id, version_no, title, body, settings, change_note, created_by)
  values (v_org, v_id, v_version, trim(p->>'title'), p->>'body', coalesce(p->'settings', '{}'::jsonb), nullif(p->>'change_note', ''), auth.uid())
  returning id into v_version_id;

  update public.loan_agreement_templates set current_version_id = v_version_id where id = v_id;
  if coalesce((p->>'is_default')::boolean, false) then
    perform public.set_default_agreement_template(v_id);
  end if;
  return jsonb_build_object('id', v_id, 'version_id', v_version_id, 'version_no', v_version);
end $$;

create or replace function public.set_default_agreement_template(p_template_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare v_org uuid := public.lf_require('templates.manage');
begin
  if not exists (select 1 from public.loan_agreement_templates where id = p_template_id and organization_id = v_org and is_active) then
    raise exception 'Template not found or inactive';
  end if;
  update public.loan_agreement_templates set is_default = false where organization_id = v_org and is_default and id <> p_template_id;
  update public.loan_agreement_templates set is_default = true where id = p_template_id;
end $$;

create or replace function public.duplicate_agreement_template(p_template_id uuid, p_name text default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('templates.manage');
  t public.loan_agreement_templates%rowtype;
  v public.loan_agreement_template_versions%rowtype;
begin
  select * into t from public.loan_agreement_templates where id = p_template_id and organization_id = v_org;
  if not found then raise exception 'Template not found'; end if;
  select * into v from public.loan_agreement_template_versions where id = t.current_version_id;
  return public.save_agreement_template(jsonb_build_object(
    'name', coalesce(nullif(trim(p_name), ''), t.name || ' (copy)'), 'description', t.description,
    'title', v.title, 'body', v.body, 'settings', v.settings, 'change_note', 'Duplicated from ' || t.name || ' v' || v.version_no));
end $$;

-- ---------------------------------------------------------------------------
-- Internal helpers
-- ---------------------------------------------------------------------------
create or replace function public.lf_sha256_hex(p text)
returns text language sql immutable as $$
  select encode(extensions.digest(convert_to(p, 'UTF8'), 'sha256'), 'hex')
$$;

create or replace function public.lf_random_token()
returns text language sql volatile as $$
  select translate(rtrim(encode(extensions.gen_random_bytes(32), 'base64'), '='), '+/', '-_')
$$;

create or replace function public.lf_agreement_event(p_agreement uuid, p_session uuid, p_type text, p_actor_type text,
  p_actor_user uuid, p_actor_name text, p_ip text, p_ua text, p_meta jsonb default '{}'::jsonb)
returns void language sql security definer set search_path = public as $$
  insert into public.agreement_audit_events (organization_id, agreement_id, session_id, event_type, actor_type,
                                             actor_user_id, actor_name, ip_address, user_agent, metadata)
  select a.organization_id, a.id, p_session, p_type, p_actor_type, p_actor_user, p_actor_name, p_ip, p_ua, coalesce(p_meta, '{}'::jsonb)
  from public.loan_agreements a where a.id = p_agreement
$$;

-- Everything the PDF renderer needs, as one JSON document
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
                    from public.agreement_signatures sg where sg.agreement_id = a.id), '[]'::jsonb))
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

-- ---------------------------------------------------------------------------
-- Generation
-- ---------------------------------------------------------------------------
create or replace function public.agreement_begin(p_loan_id uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('agreements.generate');
  l public.loans%rowtype;
  v_settings jsonb;
  v_params jsonb;
  v_calc jsonb;
  v_snap public.loan_terms_snapshots%rowtype;
  v_snap_id uuid;
  v_version integer;
  v_template public.loan_agreement_templates%rowtype;
  v_kind text := 'original';
  v_signers text[];
  v_id uuid;
  i integer;
begin
  select * into l from public.loans where id = p_loan_id and organization_id = v_org for update;
  if not found or not public.can_access_loan(l.id) then raise exception 'Loan not found'; end if;
  v_settings := public.lf_settings(v_org);

  if l.status in ('active', 'defaulted') then
    -- amendment agreement for a rescheduled loan
    select * into v_snap from public.loan_terms_snapshots where id = l.terms_snapshot_id;
    if v_snap.kind <> 'amendment' then
      raise exception 'This active loan has no pending amendment. Reschedule the loan first to generate an amendment agreement.'
        using errcode = '22023';
    end if;
    if exists (select 1 from public.loan_agreements where terms_snapshot_id = v_snap.id and status in ('pending_signatures', 'partially_signed', 'fully_signed')) then
      raise exception 'An agreement for this amendment already exists' using errcode = '22023';
    end if;
    v_kind := 'amendment';
    v_snap_id := v_snap.id;
  elsif l.status in ('draft', 'pending_review', 'approved', 'awaiting_borrower_signature', 'awaiting_lender_signature') then
    if exists (select 1 from public.agreement_signatures sg join public.loan_agreements a on a.id = sg.agreement_id
               where a.loan_id = l.id and a.status in ('pending_signatures', 'partially_signed')) then
      raise exception 'The current agreement already has signatures. Void it before generating a new version.' using errcode = '22023';
    end if;
    if l.status = 'draft' then
      perform public.submit_loan(l.id);
      select * into l from public.loans where id = l.id;
    end if;
    -- supersede any unsigned, still-open agreement
    update public.agreement_signing_sessions set status = 'revoked'
    where agreement_id in (select id from public.loan_agreements where loan_id = l.id and status in ('pending_signatures', 'generating', 'failed'))
      and status in ('pending', 'opened', 'verified');
    update public.loan_agreements set status = 'superseded'
    where loan_id = l.id and status in ('pending_signatures', 'generating', 'failed');

    -- freeze the contractual terms (recomputed server-side from the loan record)
    v_params := public.lf_loan_params(l);
    v_calc := public.compute_loan_schedule(v_params);
    if (v_calc #>> '{summary,total_repayment}')::numeric <> l.total_repayment
       or (v_calc #>> '{summary,total_interest}')::numeric <> l.total_interest then
      raise exception 'Loan figures changed since they were saved. Re-open the loan, review the preview and save it again.';
    end if;
    select coalesce(max(version), 0) + 1 into v_version from public.loan_terms_snapshots where loan_id = l.id;
    insert into public.loan_terms_snapshots (organization_id, loan_id, version, kind, terms, summary, schedule, terms_hash, created_by)
    values (v_org, l.id, v_version, 'original', v_params, v_calc->'summary', v_calc->'schedule',
            public.lf_sha256_hex((v_params || jsonb_build_object('schedule', v_calc->'schedule'))::text), auth.uid())
    returning id into v_snap_id;
  else
    raise exception 'Agreements cannot be generated for a loan in status "%"', l.status using errcode = '22023';
  end if;

  -- template resolution: loan -> settings default -> organization default -> any active
  select * into v_template from public.loan_agreement_templates t
  where t.organization_id = v_org and t.is_active and t.current_version_id is not null
  order by (t.id = l.agreement_template_id) desc,
           (t.id = nullif(v_settings #>> '{agreement,default_template_id}', '')::uuid) desc,
           t.is_default desc, t.created_at
  limit 1;
  if not found then raise exception 'No active agreement template is configured' using errcode = '22023'; end if;

  v_signers := array['borrower'];
  if coalesce((v_settings #>> '{agreement,require_lender_signature}')::boolean, true) then
    v_signers := array_append(v_signers, 'lender');
  end if;
  for i in 1..coalesce(l.witness_count, 0) loop
    v_signers := array_append(v_signers, 'witness_' || i);
  end loop;

  select coalesce(max(version), 0) + 1 into v_version from public.loan_agreements where loan_id = l.id;
  insert into public.loan_agreements (organization_id, loan_id, agreement_number, version, kind, status,
    template_id, template_version_id, terms_snapshot_id, required_signers, signing_order, generated_by, generated_at)
  values (v_org, l.id, public.lf_next_ref(v_org, 'agreement', 'LA'), v_version, v_kind, 'generating',
    v_template.id, v_template.current_version_id, v_snap_id, v_signers, l.signing_order, auth.uid(), now())
  returning id into v_id;

  return public.lf_agreement_render_data(v_id);
end $$;

create or replace function public.agreement_complete(p_agreement_id uuid, p_path text, p_hash text, p_fields jsonb,
  p_page_count integer, p_placeholders jsonb, p_rendered_body text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  a public.loan_agreements%rowtype;
  l public.loans%rowtype;
begin
  select * into a from public.loan_agreements where id = p_agreement_id for update;
  if not found then raise exception 'Agreement not found'; end if;
  if a.status <> 'generating' then raise exception 'Agreement is not being generated (status %)', a.status; end if;
  update public.loan_agreements set
    status = 'pending_signatures', unsigned_pdf_path = p_path, unsigned_pdf_hash = p_hash,
    current_pdf_path = p_path, current_pdf_hash = p_hash, current_signature_count = 0,
    signature_fields = coalesce(p_fields, '{}'::jsonb), page_count = p_page_count,
    placeholders = coalesce(p_placeholders, '{}'::jsonb), rendered_body = p_rendered_body
  where id = a.id;

  select * into l from public.loans where id = a.loan_id for update;
  if a.kind = 'original' then
    update public.loans set status = case when a.signing_order = 'lender_first' then 'awaiting_lender_signature'::public.loan_status
                                          else 'awaiting_borrower_signature'::public.loan_status end
    where id = l.id;
  end if;
  perform public.lf_agreement_event(a.id, null, 'generated', 'user', a.generated_by, null, null, null,
    jsonb_build_object('pdf_hash', p_hash, 'pages', p_page_count, 'version', a.version));
  perform public.lf_system_note(a.organization_id, l.id, l.borrower_id,
    format('Agreement %s (v%s) generated; awaiting signatures.', a.agreement_number, a.version));
  return jsonb_build_object('id', a.id, 'agreement_number', a.agreement_number, 'status', 'pending_signatures');
end $$;

create or replace function public.agreement_fail(p_agreement_id uuid, p_error text)
returns void language sql security definer set search_path = public as $$
  update public.loan_agreements set status = 'failed', error_message = left(p_error, 2000)
  where id = p_agreement_id and status = 'generating'
$$;

create or replace function public.agreement_void(p_agreement_id uuid, p_reason text)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('agreements.generate');
  a public.loan_agreements%rowtype;
  l public.loans%rowtype;
begin
  if coalesce(trim(p_reason), '') = '' then raise exception 'A reason is required to void an agreement' using errcode = '22023'; end if;
  select * into a from public.loan_agreements where id = p_agreement_id and organization_id = v_org for update;
  if not found or not public.can_access_loan(a.loan_id) then raise exception 'Agreement not found'; end if;
  if a.status = 'fully_signed' then
    raise exception 'A fully signed agreement cannot be voided. Cancel the loan or create an amendment instead.' using errcode = '22023';
  end if;
  if a.status not in ('pending_signatures', 'partially_signed', 'failed', 'generating') then
    raise exception 'Agreement is already %', a.status using errcode = '22023';
  end if;
  update public.agreement_signing_sessions set status = 'revoked' where agreement_id = a.id and status in ('pending', 'opened', 'verified');
  update public.loan_agreements set status = 'voided', voided_at = now(), voided_by = auth.uid(), void_reason = p_reason where id = a.id;
  perform public.lf_agreement_event(a.id, null, 'voided', 'user', auth.uid(), null, null, null, jsonb_build_object('reason', p_reason));
  select * into l from public.loans where id = a.loan_id for update;
  if a.kind = 'original' and l.status in ('awaiting_borrower_signature', 'awaiting_lender_signature') then
    update public.loans set status = case when approved_at is not null then 'approved'::public.loan_status else 'pending_review'::public.loan_status end
    where id = l.id;
  end if;
  perform public.lf_system_note(v_org, l.id, l.borrower_id, format('Agreement %s voided: %s', a.agreement_number, p_reason));
end $$;

-- ---------------------------------------------------------------------------
-- Signing sessions (secure, time-limited, single-use links)
-- ---------------------------------------------------------------------------
create or replace function public.signing_session_create(p_agreement_id uuid, p_signer_role text, p_signer_name text default null,
  p_contact text default null, p_expiry_hours integer default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('agreements.generate');
  a public.loan_agreements%rowtype;
  b public.borrowers%rowtype;
  v_settings jsonb;
  v_token text;
  v_hours integer;
  v_method text;
  v_name text;
  v_id uuid;
begin
  select * into a from public.loan_agreements where id = p_agreement_id and organization_id = v_org for update;
  if not found or not public.can_access_loan(a.loan_id) then raise exception 'Agreement not found'; end if;
  if a.status not in ('pending_signatures', 'partially_signed') then
    raise exception 'Agreement is not open for signing (status %)', a.status using errcode = '22023';
  end if;
  if not (p_signer_role = any (a.required_signers)) then
    raise exception 'Role "%" is not a required signer of this agreement', p_signer_role using errcode = '22023';
  end if;
  if exists (select 1 from public.agreement_signatures where agreement_id = a.id and signer_role = p_signer_role) then
    raise exception 'This party has already signed' using errcode = '22023';
  end if;

  v_settings := public.lf_settings(v_org);
  v_hours := least(greatest(coalesce(p_expiry_hours, (v_settings #>> '{agreement,link_expiry_hours}')::integer, 72), 1), 720);
  select b2.* into b from public.borrowers b2 join public.loans l on l.borrower_id = b2.id where l.id = a.loan_id;
  if p_signer_role = 'borrower' then
    v_method := coalesce(v_settings #>> '{agreement,identity_verification}', 'dob');
    if v_method = 'dob' and b.date_of_birth is null then v_method := 'none'; end if;
    if v_method = 'id_last4' and not exists (select 1 from public.borrower_identities where borrower_id = b.id) then v_method := 'none'; end if;
    v_name := b.full_name;
  else
    v_method := 'none';
    v_name := coalesce(nullif(trim(p_signer_name), ''), initcap(replace(p_signer_role, '_', ' ')));
  end if;

  update public.agreement_signing_sessions set status = 'revoked'
  where agreement_id = a.id and signer_role = p_signer_role and status in ('pending', 'opened', 'verified');

  v_token := public.lf_random_token();
  insert into public.agreement_signing_sessions (organization_id, agreement_id, signer_role, signer_name, signer_contact,
    token_hash, channel, verification_method, expires_at, created_by)
  values (v_org, a.id, p_signer_role, v_name, coalesce(nullif(p_contact, ''), case when p_signer_role = 'borrower' then b.mobile_number end),
    public.lf_sha256_hex(v_token), 'link', v_method, now() + make_interval(hours => v_hours), auth.uid())
  returning id into v_id;

  perform public.lf_agreement_event(a.id, v_id, 'link_created', 'user', auth.uid(), null, null, null,
    jsonb_build_object('signer_role', p_signer_role, 'expires_in_hours', v_hours, 'verification', v_method));
  return jsonb_build_object('session_id', v_id, 'token', v_token, 'expires_at', now() + make_interval(hours => v_hours),
                            'verification_method', v_method, 'signer_name', v_name);
end $$;

create or replace function public.signing_session_revoke(p_session_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('agreements.generate');
  s public.agreement_signing_sessions%rowtype;
begin
  select * into s from public.agreement_signing_sessions where id = p_session_id and organization_id = v_org for update;
  if not found then raise exception 'Signing link not found'; end if;
  if s.status not in ('pending', 'opened', 'verified') then raise exception 'Signing link is already %', s.status using errcode = '22023'; end if;
  update public.agreement_signing_sessions set status = 'revoked' where id = s.id;
  perform public.lf_agreement_event(s.agreement_id, s.id, 'link_revoked', 'user', auth.uid(), null, null, null, '{}'::jsonb);
end $$;

-- Public signing: look up a session by token hash (service role only)
create or replace function public.signing_lookup(p_token_hash text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  s public.agreement_signing_sessions%rowtype;
  a public.loan_agreements%rowtype;
begin
  select * into s from public.agreement_signing_sessions where token_hash = p_token_hash;
  if not found then return null; end if;
  if s.status in ('pending', 'opened', 'verified') and s.expires_at < now() then
    update public.agreement_signing_sessions set status = 'expired' where id = s.id;
    s.status := 'expired';
  end if;
  select * into a from public.loan_agreements where id = s.agreement_id;
  return jsonb_build_object(
    'session', jsonb_build_object('id', s.id, 'signer_role', s.signer_role, 'signer_name', s.signer_name, 'status', s.status,
                                  'expires_at', s.expires_at, 'verification_method', s.verification_method,
                                  'verified', s.verified_at is not null, 'signed_at', s.signed_at,
                                  'locked_until', s.locked_until, 'failed_attempts', s.failed_attempts,
                                  'verify_nonce_hash', s.verify_nonce_hash, 'organization_id', s.organization_id),
    'agreement_id', a.id, 'agreement_status', a.status);
end $$;

create or replace function public.signing_mark_opened(p_session_id uuid, p_ip text, p_ua text)
returns void language plpgsql security definer set search_path = public as $$
declare s public.agreement_signing_sessions%rowtype;
begin
  select * into s from public.agreement_signing_sessions where id = p_session_id for update;
  if s.status = 'pending' then
    update public.agreement_signing_sessions set status = 'opened', opened_at = now() where id = s.id;
  end if;
  perform public.lf_agreement_event(s.agreement_id, s.id, 'viewed', 'signer', null, s.signer_name, p_ip, p_ua, '{}'::jsonb);
end $$;

-- Identity check before the document is revealed. Returns a one-time nonce on success.
create or replace function public.signing_verify(p_session_id uuid, p_answer text, p_ip text, p_ua text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  s public.agreement_signing_sessions%rowtype;
  v_ok boolean := false;
  v_dob date;
  v_id text;
  v_nonce text;
  v_answer text := trim(coalesce(p_answer, ''));
begin
  select * into s from public.agreement_signing_sessions where id = p_session_id for update;
  if not found then raise exception 'Signing link not found'; end if;
  if s.status not in ('pending', 'opened', 'verified', 'signed') then
    return jsonb_build_object('ok', false, 'error', 'This signing link is ' || s.status || '.');
  end if;
  if s.expires_at < now() then
    if s.status <> 'signed' then
      update public.agreement_signing_sessions set status = 'expired' where id = s.id;
    end if;
    return jsonb_build_object('ok', false, 'error', 'This signing link has expired.');
  end if;
  if s.locked_until is not null and s.locked_until > now() then
    return jsonb_build_object('ok', false, 'error', 'Too many failed attempts. Try again after ' ||
      to_char(s.locked_until at time zone 'Asia/Manila', 'HH12:MI AM') || '.', 'locked_until', s.locked_until);
  end if;

  if s.verification_method = 'none' then
    v_ok := true;
  elsif s.verification_method = 'dob' then
    select b.date_of_birth into v_dob from public.loan_agreements a join public.loans l on l.id = a.loan_id
    join public.borrowers b on b.id = l.borrower_id where a.id = s.agreement_id;
    begin
      v_ok := v_dob is not null and v_answer::date = v_dob;
    exception when others then v_ok := false;
    end;
  elsif s.verification_method = 'id_last4' then
    select upper(regexp_replace(bi.id_number, '[^A-Za-z0-9]', '', 'g')) into v_id
    from public.loan_agreements a join public.loans l on l.id = a.loan_id
    join public.borrower_identities bi on bi.borrower_id = l.borrower_id where a.id = s.agreement_id;
    v_ok := v_id is not null and length(v_answer) >= 4 and right(v_id, 4) = upper(right(regexp_replace(v_answer, '[^A-Za-z0-9]', '', 'g'), 4));
  end if;

  if not v_ok then
    update public.agreement_signing_sessions set
      failed_attempts = failed_attempts + 1,
      locked_until = case when failed_attempts + 1 >= 5 then now() + interval '15 minutes' else locked_until end
    where id = s.id;
    perform public.lf_agreement_event(s.agreement_id, s.id, 'verification_failed', 'signer', null, s.signer_name, p_ip, p_ua,
      jsonb_build_object('attempt', s.failed_attempts + 1));
    return jsonb_build_object('ok', false, 'error', 'The details you entered do not match our records.',
                              'attempts_left', greatest(0, 4 - s.failed_attempts));
  end if;

  v_nonce := public.lf_random_token();
  update public.agreement_signing_sessions set
    status = case when status = 'signed' then 'signed' else 'verified' end,
    verified_at = coalesce(verified_at, now()), failed_attempts = 0, locked_until = null,
    opened_at = coalesce(opened_at, now()), verify_nonce_hash = public.lf_sha256_hex(v_nonce)
  where id = s.id;
  perform public.lf_agreement_event(s.agreement_id, s.id, 'verified', 'signer', null, s.signer_name, p_ip, p_ua,
    jsonb_build_object('method', s.verification_method));
  return jsonb_build_object('ok', true, 'nonce', v_nonce);
end $$;

-- ---------------------------------------------------------------------------
-- Signature commit (idempotent per agreement + role)
-- ---------------------------------------------------------------------------
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
    insert into public.agreement_signing_sessions (organization_id, agreement_id, signer_role, signer_name, channel,
      verification_method, status, expires_at, opened_at, verified_at, created_by)
    values (a.organization_id, a.id, v_role, p->>'signer_name', 'in_app', 'none', 'verified', now() + interval '1 hour', now(), now(),
            nullif(p->>'signer_user_id', '')::uuid)
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
    nullif(p->>'signer_user_id', '')::uuid, coalesce(nullif(p->>'signer_name', ''), s.signer_name), p->>'ip', p->>'user_agent',
    jsonb_build_object('consent_text', p->>'consent_text'));
  perform public.lf_agreement_event(a.id, s.id, 'signed', case when v_session is null then 'user' else 'signer' end,
    nullif(p->>'signer_user_id', '')::uuid, coalesce(nullif(p->>'signer_name', ''), s.signer_name), p->>'ip', p->>'user_agent',
    jsonb_build_object('signer_role', v_role, 'signature_hash', p->>'image_hash'));

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

create or replace function public.lf_agreement_signing_state(p_agreement_id uuid)
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'agreement_status', a.status,
    'required_signers', to_jsonb(a.required_signers),
    'signature_count', (select count(*) from public.agreement_signatures where agreement_id = a.id),
    'all_signed', not exists (select 1 from unnest(a.required_signers) r
                              where not exists (select 1 from public.agreement_signatures sg where sg.agreement_id = a.id and sg.signer_role = r)),
    'current_pdf_path', a.current_pdf_path, 'current_signature_count', a.current_signature_count,
    'signed_pdf_path', a.signed_pdf_path, 'signed_pdf_hash', a.signed_pdf_hash)
  from public.loan_agreements a where a.id = p_agreement_id
$$;

-- Compare-and-set the latest PDF; finalize when every required signature is embedded.
create or replace function public.agreement_set_pdf(p_agreement_id uuid, p_path text, p_hash text, p_signature_count integer)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  a public.loan_agreements%rowtype;
  l public.loans%rowtype;
  v_total integer;
  v_all boolean;
begin
  select * into a from public.loan_agreements where id = p_agreement_id for update;
  if not found then raise exception 'Agreement not found'; end if;
  if a.status = 'fully_signed' then
    return jsonb_build_object('applied', false, 'reason', 'already_final') || public.lf_agreement_signing_state(a.id);
  end if;
  if a.status not in ('partially_signed', 'pending_signatures') then
    return jsonb_build_object('applied', false, 'reason', 'status_' || a.status) || public.lf_agreement_signing_state(a.id);
  end if;
  select count(*) into v_total from public.agreement_signatures where agreement_id = a.id;
  if p_signature_count < a.current_signature_count or p_signature_count > v_total then
    return jsonb_build_object('applied', false, 'reason', 'stale') || public.lf_agreement_signing_state(a.id);
  end if;
  v_all := p_signature_count = v_total and not exists (
    select 1 from unnest(a.required_signers) r
    where not exists (select 1 from public.agreement_signatures sg where sg.agreement_id = a.id and sg.signer_role = r));

  update public.loan_agreements set current_pdf_path = p_path, current_pdf_hash = p_hash, current_signature_count = p_signature_count
  where id = a.id;

  if v_all then
    update public.loan_agreements set status = 'fully_signed', signed_pdf_path = p_path, signed_pdf_hash = p_hash, fully_signed_at = now()
    where id = a.id;
    perform public.lf_agreement_event(a.id, null, 'pdf_finalized', 'system', null, null, null, null,
      jsonb_build_object('signed_pdf_hash', p_hash, 'signatures', p_signature_count));
    select * into l from public.loans where id = a.loan_id for update;
    if a.kind = 'original' and l.status in ('awaiting_borrower_signature', 'awaiting_lender_signature', 'pending_review', 'approved') then
      update public.loans set status = case when approved_at is not null then 'ready_for_release'::public.loan_status
                                            else 'fully_signed'::public.loan_status end
      where id = l.id;
    end if;
    perform public.lf_system_note(a.organization_id, a.loan_id, l.borrower_id,
      format('Agreement %s fully signed. SHA-256: %s', a.agreement_number, p_hash));
    perform public.lf_notify_roles(a.organization_id, array['owner', 'admin']::public.app_role[], 'agreement_signed',
      'Agreement fully signed', a.agreement_number || ' has all required signatures.', '/loans/' || a.loan_id);
  end if;
  return jsonb_build_object('applied', true, 'finalized', v_all) || public.lf_agreement_signing_state(a.id);
end $$;

create or replace function public.agreement_log_download(p_agreement_id uuid, p_session uuid, p_actor_type text, p_user uuid, p_ip text, p_ua text)
returns void language sql security definer set search_path = public as $$
  select public.lf_agreement_event(p_agreement_id, p_session, 'downloaded', p_actor_type, p_user, null, p_ip, p_ua, '{}'::jsonb)
$$;

-- Simple fixed-window rate limiter for public endpoints (service role only)
create or replace function public.lf_rate_limit(p_key text, p_limit integer, p_window_seconds integer)
returns boolean language plpgsql security definer set search_path = public as $$
declare
  v_window timestamptz := to_timestamp(floor(extract(epoch from now()) / p_window_seconds) * p_window_seconds);
  v_hits integer;
begin
  insert into public.rate_limits (key, window_start, hits) values (p_key, v_window, 1)
  on conflict (key, window_start) do update set hits = public.rate_limits.hits + 1
  returning hits into v_hits;
  if random() < 0.01 then
    delete from public.rate_limits where window_start < now() - interval '1 day';
  end if;
  return v_hits <= p_limit;
end $$;

-- Canonical permission catalogue (owners implicitly hold every permission)
create or replace function public.lf_all_permissions()
returns text[] language sql immutable as $$
  select array[
    'dashboard.view',
    'borrowers.view', 'borrowers.view_all', 'borrowers.create', 'borrowers.edit', 'borrowers.archive', 'borrowers.view_sensitive',
    'loans.view', 'loans.view_all', 'loans.create', 'loans.approve', 'loans.release', 'loans.cancel', 'loans.manage',
    'agreements.view', 'agreements.generate', 'agreements.sign_lender',
    'payments.view', 'payments.record', 'payments.reverse', 'payments.adjust',
    'collections.assign', 'reports.view',
    'products.manage', 'templates.manage', 'users.manage', 'settings.manage', 'audit.view'
  ]
$$;

-- In-app permission probe used by the frontend and by Edge Functions acting with the caller's JWT
create or replace function public.my_access()
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'user_id', auth.uid(),
    'organization_id', public.current_org_id(),
    'role', public.current_app_role(),
    'full_name', (select full_name from public.user_profiles where id = auth.uid()),
    'permissions', coalesce(
      case when public.current_app_role() = 'owner'
           then to_jsonb(public.lf_all_permissions())
           else (select jsonb_agg(rp.permission) from public.role_permissions rp
                 where rp.organization_id = public.current_org_id() and rp.role = public.current_app_role()) end,
      '[]'::jsonb))
$$;
