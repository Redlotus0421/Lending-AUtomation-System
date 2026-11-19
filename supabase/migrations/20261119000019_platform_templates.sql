-- =============================================================================
-- LendFlow — Agreement / terms templates managed by the platform administrator
--
-- The platform super admin can list, create, edit (new version), activate and set
-- the default template of any lender account, the same way the lender's owner does
-- in Settings. Lenders keep full control of their own templates; the admin role now
-- manages templates too (owners already could).
-- =============================================================================

-- Shared save logic for a given organization (used by the lender and the platform admin)
create or replace function public.lf_save_agreement_template(p_org uuid, p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_id uuid := nullif(p->>'id', '')::uuid;
  v_version integer;
  v_version_id uuid;
  v_actor uuid := (select id from public.user_profiles where id = auth.uid());
begin
  if coalesce(trim(p->>'name'), '') = '' then raise exception 'Template name is required' using errcode = '22023'; end if;
  if coalesce(trim(p->>'title'), '') = '' then raise exception 'Agreement title is required' using errcode = '22023'; end if;
  if coalesce(trim(p->>'body'), '') = '' then raise exception 'Template body is required' using errcode = '22023'; end if;

  if v_id is null then
    insert into public.loan_agreement_templates (organization_id, name, description, is_active, created_by)
    values (p_org, trim(p->>'name'), nullif(p->>'description', ''), coalesce((p->>'is_active')::boolean, true), v_actor)
    returning id into v_id;
  else
    update public.loan_agreement_templates
    set name = trim(p->>'name'), description = nullif(p->>'description', ''),
        is_active = coalesce((p->>'is_active')::boolean, is_active)
    where id = v_id and organization_id = p_org;
    if not found then raise exception 'Template not found'; end if;
  end if;

  select coalesce(max(version_no), 0) + 1 into v_version from public.loan_agreement_template_versions where template_id = v_id;
  insert into public.loan_agreement_template_versions (organization_id, template_id, version_no, title, body, settings, change_note, created_by)
  values (p_org, v_id, v_version, trim(p->>'title'), p->>'body', coalesce(p->'settings', '{}'::jsonb), nullif(p->>'change_note', ''), v_actor)
  returning id into v_version_id;

  update public.loan_agreement_templates set current_version_id = v_version_id where id = v_id;
  if coalesce((p->>'is_default')::boolean, false) then
    perform public.lf_set_default_template(p_org, v_id);
  end if;
  return jsonb_build_object('id', v_id, 'version_id', v_version_id, 'version_no', v_version);
end $$;

create or replace function public.lf_set_default_template(p_org uuid, p_template_id uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from public.loan_agreement_templates where id = p_template_id and organization_id = p_org and is_active) then
    raise exception 'Template not found or inactive';
  end if;
  update public.loan_agreement_templates set is_default = false where organization_id = p_org and is_default and id <> p_template_id;
  update public.loan_agreement_templates set is_default = true where id = p_template_id;
end $$;

-- the lender's own functions now use the shared logic
create or replace function public.save_agreement_template(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
begin
  return public.lf_save_agreement_template(public.lf_require('templates.manage'), p);
end $$;

create or replace function public.set_default_agreement_template(p_template_id uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform public.lf_set_default_template(public.lf_require('templates.manage'), p_template_id);
end $$;

-- ---------------------------------------------------------------------------
-- Platform administrator
-- ---------------------------------------------------------------------------
create or replace function public.lf_platform_template_audit(p_org uuid, p_action text, p_template uuid, p_data jsonb)
returns void language sql security definer set search_path = public as $$
  insert into public.audit_logs (organization_id, actor_id, actor_name, action, table_name, record_id, new_data, context)
  values (p_org, auth.uid(), 'Platform administrator', p_action, 'loan_agreement_templates', p_template::text, p_data,
          jsonb_build_object('source', 'platform_admin'))
$$;

create or replace function public.platform_list_templates(p_org uuid)
returns jsonb language plpgsql stable security definer set search_path = public as $$
begin
  perform public.lf_require_platform_admin();
  if not exists (select 1 from public.organizations where id = p_org) then raise exception 'Account not found' using errcode = 'P0002'; end if;
  return coalesce((
    select jsonb_agg(to_jsonb(t) || jsonb_build_object('current', to_jsonb(v)) order by t.is_default desc, t.name)
    from public.loan_agreement_templates t
    left join public.loan_agreement_template_versions v on v.id = t.current_version_id
    where t.organization_id = p_org), '[]'::jsonb);
end $$;

create or replace function public.platform_get_template(p_org uuid, p_template_id uuid)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare t public.loan_agreement_templates%rowtype;
begin
  perform public.lf_require_platform_admin();
  select * into t from public.loan_agreement_templates where id = p_template_id and organization_id = p_org;
  if not found then raise exception 'Template not found' using errcode = 'P0002'; end if;
  return jsonb_build_object(
    'template', to_jsonb(t),
    'versions', coalesce((select jsonb_agg(to_jsonb(v) order by v.version_no desc)
                          from public.loan_agreement_template_versions v where v.template_id = t.id), '[]'::jsonb));
end $$;

create or replace function public.platform_save_template(p_org uuid, p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare r jsonb;
begin
  perform public.lf_require_platform_admin();
  if not exists (select 1 from public.organizations where id = p_org) then raise exception 'Account not found' using errcode = 'P0002'; end if;
  r := public.lf_save_agreement_template(p_org, p);
  perform public.lf_platform_template_audit(p_org, 'template_saved_by_platform', (r->>'id')::uuid,
    jsonb_build_object('name', p->>'name', 'version_no', r->'version_no', 'is_default', coalesce((p->>'is_default')::boolean, false)));
  return r;
end $$;

create or replace function public.platform_set_default_template(p_org uuid, p_template_id uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform public.lf_require_platform_admin();
  perform public.lf_set_default_template(p_org, p_template_id);
  perform public.lf_platform_template_audit(p_org, 'template_set_default_by_platform', p_template_id, '{}'::jsonb);
end $$;

create or replace function public.platform_set_template_active(p_org uuid, p_template_id uuid, p_active boolean)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform public.lf_require_platform_admin();
  if not p_active and exists (select 1 from public.loan_agreement_templates where id = p_template_id and organization_id = p_org and is_default) then
    raise exception 'The default template cannot be deactivated. Set another template as default first.' using errcode = '22023';
  end if;
  update public.loan_agreement_templates set is_active = p_active where id = p_template_id and organization_id = p_org;
  if not found then raise exception 'Template not found' using errcode = 'P0002'; end if;
  perform public.lf_platform_template_audit(p_org, case when p_active then 'template_activated_by_platform' else 'template_deactivated_by_platform' end,
    p_template_id, '{}'::jsonb);
end $$;

-- ---------------------------------------------------------------------------
-- Lenders: the admin role manages templates too (owners already do)
-- ---------------------------------------------------------------------------
create or replace function public.lf_default_role_permissions(p_role public.app_role)
returns text[] language sql immutable as $$
  select case p_role
    when 'owner' then public.lf_all_permissions()
    when 'admin' then array[
      'dashboard.view', 'borrowers.view', 'borrowers.view_all', 'borrowers.create', 'borrowers.edit', 'borrowers.archive',
      'borrowers.view_sensitive', 'loans.view', 'loans.view_all', 'loans.create', 'loans.approve', 'loans.release', 'loans.cancel',
      'loans.manage', 'agreements.view', 'agreements.generate', 'agreements.sign_lender', 'payments.view', 'payments.record',
      'payments.reverse', 'payments.adjust', 'collections.assign', 'reports.view', 'templates.manage']
    when 'loan_officer' then array[
      'dashboard.view', 'borrowers.view', 'borrowers.create', 'borrowers.edit', 'borrowers.view_sensitive',
      'loans.view', 'loans.create', 'agreements.view', 'agreements.generate', 'payments.view']
    when 'collector' then array[
      'dashboard.view', 'borrowers.view', 'loans.view', 'payments.view', 'payments.record']
  end
$$;

-- existing businesses: give their admin role template management as well
insert into public.role_permissions (organization_id, role, permission)
select o.id, 'admin', 'templates.manage' from public.organizations o
on conflict do nothing;

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
do $$
declare f text;
begin
  foreach f in array array[
    'public.lf_save_agreement_template(uuid, jsonb)',
    'public.lf_set_default_template(uuid, uuid)',
    'public.lf_platform_template_audit(uuid, text, uuid, jsonb)'
  ] loop
    execute format('revoke execute on function %s from public, anon, authenticated', f);
    execute format('grant execute on function %s to service_role', f);
  end loop;
end $$;
