-- =============================================================================
-- LendFlow — The co-maker signs the loan agreement
--
-- When the borrower has a co-maker (guarantor) on file, new agreements require the
-- co-maker's signature too (signer role 'co_maker'), next to the borrower's. Like the
-- borrower, the co-maker signs in person on a staff member's device (witnessed) or
-- through a secure signing link; their name always comes from the borrower record.
-- Businesses can turn this off with settings.agreement.require_co_maker_signature.
-- Agreements generated before this change keep their original signers.
-- =============================================================================

alter table public.agreement_signing_sessions drop constraint agreement_signing_sessions_signer_role_check;
alter table public.agreement_signing_sessions add constraint agreement_signing_sessions_signer_role_check
  check (signer_role in ('borrower', 'co_maker', 'lender', 'witness_1', 'witness_2'));

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
  -- the borrower's co-maker signs too (on by default; the business can turn it off in settings)
  if coalesce((v_settings #>> '{agreement,require_co_maker_signature}')::boolean, true)
     and exists (select 1 from public.guarantors g where g.borrower_id = l.borrower_id) then
    v_signers := array_append(v_signers, 'co_maker');
  end if;
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

create or replace function public.signing_session_create(p_agreement_id uuid, p_signer_role text, p_signer_name text default null,
  p_contact text default null, p_expiry_hours integer default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('agreements.generate');
  a public.loan_agreements%rowtype;
  b public.borrowers%rowtype;
  g public.guarantors%rowtype;
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
  elsif p_signer_role = 'co_maker' then
    -- the co-maker's name and number come from the borrower record, never from the client
    select * into g from public.guarantors where borrower_id = b.id order by created_at desc limit 1;
    if not found then raise exception 'This borrower has no co-maker on file' using errcode = '22023'; end if;
    v_method := 'none';
    v_name := g.full_name;
    p_contact := coalesce(nullif(p_contact, ''), g.mobile_number);
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
  v_method text := coalesce(nullif(p->>'verification_method', ''), 'none');
  v_witness uuid := nullif(p->>'witnessed_by', '')::uuid;
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
  if v_method not in ('none', 'in_person') then raise exception 'Invalid verification method' using errcode = '22023'; end if;
  if v_role in ('borrower', 'co_maker') and v_session is null and (v_method <> 'in_person' or v_witness is null) then
    raise exception 'A % signs through a secure link or in person in front of a staff member',
      case when v_role = 'co_maker' then 'co-maker' else 'borrower' end using errcode = '22023';
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
    -- an in-person borrower signature revokes any outstanding remote link for the same role
    update public.agreement_signing_sessions set status = 'revoked'
    where agreement_id = a.id and signer_role = v_role and status in ('pending', 'opened', 'verified');
    insert into public.agreement_signing_sessions (organization_id, agreement_id, signer_role, signer_name, channel,
      verification_method, status, expires_at, opened_at, verified_at, created_by)
    values (a.organization_id, a.id, v_role, p->>'signer_name', 'in_app', v_method, 'verified', now() + interval '1 hour', now(), now(),
            coalesce(nullif(p->>'signer_user_id', '')::uuid, v_witness))
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
    coalesce(nullif(p->>'signer_user_id', '')::uuid, v_witness), coalesce(nullif(p->>'signer_name', ''), s.signer_name), p->>'ip', p->>'user_agent',
    jsonb_build_object('consent_text', p->>'consent_text', 'in_person', v_method = 'in_person', 'witnessed_by', v_witness));
  perform public.lf_agreement_event(a.id, s.id, 'signed', case when v_session is null then 'user' else 'signer' end,
    coalesce(nullif(p->>'signer_user_id', '')::uuid, v_witness), coalesce(nullif(p->>'signer_name', ''), s.signer_name), p->>'ip', p->>'user_agent',
    jsonb_build_object('signer_role', v_role, 'signature_hash', p->>'image_hash', 'in_person', v_method = 'in_person', 'witnessed_by', v_witness));

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

revoke execute on function public.signature_commit(jsonb) from public, anon, authenticated;
grant execute on function public.signature_commit(jsonb) to service_role;
