-- =============================================================================
-- LendFlow — Platform (super) administration
--
-- Platform administrators operate the LendFlow service: they create lender
-- accounts (an organization + its owner login), suspend/reactivate them and help
-- owners regain access. They are NOT members of any organization, so RLS still
-- gives them no access to borrower, loan, payment or document records; the
-- console only sees per-account counts and totals through the functions below.
-- Platform admins are granted only by the service role (scripts/seed-super-admin.mjs).
-- =============================================================================

create table public.platform_admins (
  user_id uuid primary key references auth.users (id) on delete cascade,
  email text not null,
  created_at timestamptz not null default now()
);
alter table public.platform_admins enable row level security;
create policy platform_admins_self on public.platform_admins for select to authenticated
  using (user_id = auth.uid());
revoke insert, update, delete on public.platform_admins from anon, authenticated;

alter table public.organizations
  add column status text not null default 'active' check (status in ('active', 'suspended')),
  add column status_reason text,
  add column status_changed_at timestamptz,
  add column created_by uuid references auth.users (id) on delete set null;

-- Account status and provenance can only be changed by platform functions / service role,
-- never by an organization's own users through the API.
create or replace function public.lf_org_status_guard()
returns trigger language plpgsql as $$
begin
  if current_user in ('authenticated', 'anon') and (
       new.status is distinct from old.status or new.status_reason is distinct from old.status_reason
       or new.status_changed_at is distinct from old.status_changed_at or new.created_by is distinct from old.created_by) then
    raise exception 'Account status can only be changed by a platform administrator' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger lf_org_status_guard before update on public.organizations
  for each row execute function public.lf_org_status_guard();

-- ---------------------------------------------------------------------------
-- Suspended organizations lose all access: every RLS policy and RPC resolves the
-- caller's organization through these helpers.
-- ---------------------------------------------------------------------------
create or replace function public.current_org_id()
returns uuid language sql stable security definer set search_path = public as $$
  select up.organization_id from public.user_profiles up
  join public.organizations o on o.id = up.organization_id and o.status = 'active'
  where up.id = auth.uid() and up.is_active
$$;

create or replace function public.current_app_role()
returns public.app_role language sql stable security definer set search_path = public as $$
  select ur.role from public.user_roles ur
  join public.user_profiles up on up.id = ur.user_id and up.organization_id = ur.organization_id
  join public.organizations o on o.id = ur.organization_id and o.status = 'active'
  where ur.user_id = auth.uid() and up.is_active
  limit 1
$$;

create or replace function public.has_permission(p_permission text)
returns boolean language sql stable security definer set search_path = public as $$
  select coalesce(
    (select true from public.user_roles ur
       join public.user_profiles up on up.id = ur.user_id and up.organization_id = ur.organization_id
       join public.organizations o on o.id = ur.organization_id and o.status = 'active'
      where ur.user_id = auth.uid() and up.is_active and ur.role = 'owner'
      limit 1),
    (select true from public.user_roles ur
       join public.user_profiles up on up.id = ur.user_id and up.organization_id = ur.organization_id
       join public.organizations o on o.id = ur.organization_id and o.status = 'active'
       join public.role_permissions rp on rp.organization_id = ur.organization_id and rp.role = ur.role
      where ur.user_id = auth.uid() and up.is_active and rp.permission = p_permission
      limit 1),
    false)
$$;

create or replace function public.is_platform_admin()
returns boolean language sql stable security definer set search_path = public as $$
  select auth.uid() is not null and exists (select 1 from public.platform_admins where user_id = auth.uid())
$$;

create or replace function public.lf_require_platform_admin()
returns uuid language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_platform_admin() then
    raise exception 'Platform administrator access required' using errcode = '42501';
  end if;
  return auth.uid();
end $$;

-- my_access now also reports platform-admin status and a suspended membership
create or replace function public.my_access()
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'user_id', auth.uid(),
    'organization_id', public.current_org_id(),
    'role', public.current_app_role(),
    'full_name', (select full_name from public.user_profiles where id = auth.uid()),
    'is_platform_admin', public.is_platform_admin(),
    'suspended_organization', (
      select jsonb_build_object('name', o.name, 'reason', o.status_reason)
      from public.user_profiles up join public.organizations o on o.id = up.organization_id
      where up.id = auth.uid() and o.status = 'suspended'),
    'permissions', coalesce(
      case when public.current_app_role() = 'owner'
           then to_jsonb(public.lf_all_permissions())
           else (select jsonb_agg(rp.permission) from public.role_permissions rp
                 where rp.organization_id = public.current_org_id() and rp.role = public.current_app_role()) end,
      '[]'::jsonb))
$$;

-- ---------------------------------------------------------------------------
-- Console read models (counts and totals only — no borrower or loan rows)
-- ---------------------------------------------------------------------------
create or replace function public.lf_platform_org_stats(p_org uuid)
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'users', (select count(*) from public.user_profiles where organization_id = p_org and is_active),
    'borrowers', (select count(*) from public.borrowers where organization_id = p_org),
    'loans', (select count(*) from public.loans where organization_id = p_org),
    'active_loans', (select count(*) from public.loans where organization_id = p_org and status = 'active'),
    'outstanding', (select coalesce(sum(total_outstanding), 0) from public.loans
                    where organization_id = p_org and status in ('active', 'defaulted')),
    'collected', (select coalesce(sum(amount), 0) from public.loan_payments where organization_id = p_org and status = 'posted'),
    'last_activity_at', (select max(last_sign_in_at) from auth.users u
                         join public.user_profiles up on up.id = u.id where up.organization_id = p_org))
$$;

create or replace function public.lf_platform_org_owner(p_org uuid)
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object('user_id', up.id, 'full_name', up.full_name, 'email', up.email, 'phone', up.phone)
  from public.user_roles ur join public.user_profiles up on up.id = ur.user_id and up.organization_id = ur.organization_id
  where ur.organization_id = p_org and ur.role = 'owner'
  order by up.is_active desc, ur.created_at
  limit 1
$$;

create or replace function public.platform_list_organizations()
returns jsonb language plpgsql stable security definer set search_path = public as $$
begin
  perform public.lf_require_platform_admin();
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', o.id, 'name', o.name, 'legal_name', o.legal_name, 'city', o.city, 'province', o.province,
      'contact_email', o.contact_email, 'contact_phone', o.contact_phone,
      'status', o.status, 'status_reason', o.status_reason, 'status_changed_at', o.status_changed_at,
      'created_at', o.created_at,
      'owner', public.lf_platform_org_owner(o.id),
      'stats', public.lf_platform_org_stats(o.id)) order by o.created_at desc)
    from public.organizations o), '[]'::jsonb);
end $$;

create or replace function public.platform_get_organization(p_org uuid)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare v jsonb;
begin
  perform public.lf_require_platform_admin();
  select to_jsonb(o) || jsonb_build_object(
      'owner', public.lf_platform_org_owner(o.id),
      'stats', public.lf_platform_org_stats(o.id),
      'members', coalesce((
        select jsonb_agg(jsonb_build_object(
          'id', up.id, 'full_name', up.full_name, 'email', up.email, 'phone', up.phone, 'is_active', up.is_active,
          'role', ur.role, 'created_at', up.created_at, 'last_sign_in_at', u.last_sign_in_at)
          order by (ur.role = 'owner') desc, up.full_name)
        from public.user_profiles up
        left join public.user_roles ur on ur.user_id = up.id and ur.organization_id = up.organization_id
        left join auth.users u on u.id = up.id
        where up.organization_id = o.id), '[]'::jsonb))
  into v
  from public.organizations o where o.id = p_org;
  if v is null then raise exception 'Account not found' using errcode = 'P0002'; end if;
  return v;
end $$;

create or replace function public.platform_overview()
returns jsonb language plpgsql stable security definer set search_path = public as $$
begin
  perform public.lf_require_platform_admin();
  return jsonb_build_object(
    'organizations', (select count(*) from public.organizations),
    'active_organizations', (select count(*) from public.organizations where status = 'active'),
    'suspended_organizations', (select count(*) from public.organizations where status = 'suspended'),
    'new_organizations_30d', (select count(*) from public.organizations where created_at > now() - interval '30 days'),
    'users', (select count(*) from public.user_profiles where organization_id is not null and is_active),
    'borrowers', (select count(*) from public.borrowers),
    'active_loans', (select count(*) from public.loans where status = 'active'),
    'outstanding', (select coalesce(sum(total_outstanding), 0) from public.loans where status in ('active', 'defaulted')),
    'collected_30d', (select coalesce(sum(amount), 0) from public.loan_payments
                      where status = 'posted' and payment_date > (now() at time zone 'Asia/Manila')::date - 30),
    'signups_by_month', coalesce((
      select jsonb_agg(jsonb_build_object('month', m, 'count', c) order by m)
      from (select to_char(date_trunc('month', created_at at time zone 'Asia/Manila'), 'YYYY-MM') m, count(*) c
            from public.organizations where created_at > now() - interval '12 months' group by 1) s), '[]'::jsonb));
end $$;

create or replace function public.platform_set_organization_status(p_org uuid, p_status text, p_reason text default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v_admin uuid := public.lf_require_platform_admin();
begin
  if p_status not in ('active', 'suspended') then raise exception 'Invalid status' using errcode = '22023'; end if;
  if p_status = 'suspended' and coalesce(trim(p_reason), '') = '' then
    raise exception 'Give a reason for suspending this account' using errcode = '22023';
  end if;
  update public.organizations
  set status = p_status, status_reason = case when p_status = 'suspended' then trim(p_reason) end, status_changed_at = now()
  where id = p_org;
  if not found then raise exception 'Account not found' using errcode = 'P0002'; end if;
  insert into public.audit_logs (organization_id, actor_id, actor_name, action, table_name, record_id, new_data, context)
  values (p_org, v_admin, 'Platform administrator',
          case when p_status = 'suspended' then 'account_suspended' else 'account_reactivated' end,
          'organizations', p_org::text, jsonb_build_object('status', p_status, 'reason', p_reason),
          jsonb_build_object('source', 'platform_admin'));
  return public.platform_get_organization(p_org);
end $$;

create or replace function public.platform_update_organization(p_org uuid, p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
begin
  perform public.lf_require_platform_admin();
  if p ? 'name' and coalesce(trim(p->>'name'), '') = '' then raise exception 'Business name is required' using errcode = '22023'; end if;
  update public.organizations set
    name = coalesce(nullif(trim(p->>'name'), ''), name),
    legal_name = case when p ? 'legal_name' then nullif(trim(p->>'legal_name'), '') else legal_name end,
    registration_no = case when p ? 'registration_no' then nullif(trim(p->>'registration_no'), '') else registration_no end,
    tin = case when p ? 'tin' then nullif(trim(p->>'tin'), '') else tin end,
    address = case when p ? 'address' then nullif(trim(p->>'address'), '') else address end,
    city = case when p ? 'city' then nullif(trim(p->>'city'), '') else city end,
    province = case when p ? 'province' then nullif(trim(p->>'province'), '') else province end,
    contact_email = case when p ? 'contact_email' then nullif(trim(p->>'contact_email'), '') else contact_email end,
    contact_phone = case when p ? 'contact_phone' then nullif(trim(p->>'contact_phone'), '') else contact_phone end
  where id = p_org;
  if not found then raise exception 'Account not found' using errcode = 'P0002'; end if;
  return public.platform_get_organization(p_org);
end $$;

-- ---------------------------------------------------------------------------
-- Service-role only (called by the platform-admin Edge Function after it has
-- verified the caller is a platform admin and created the auth user).
-- ---------------------------------------------------------------------------
create or replace function public.lf_platform_create_organization(p jsonb, p_owner uuid, p_created_by uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v_org uuid;
begin
  if coalesce(trim(p->>'name'), '') = '' then raise exception 'Business name is required' using errcode = '22023'; end if;
  if not exists (select 1 from auth.users where id = p_owner) then raise exception 'Owner login not found'; end if;
  if exists (select 1 from public.user_profiles where id = p_owner and organization_id is not null) then
    raise exception 'This login already belongs to a lender account' using errcode = '22023';
  end if;
  if exists (select 1 from public.platform_admins where user_id = p_owner) then
    raise exception 'A platform administrator login cannot own a lender account' using errcode = '22023';
  end if;

  insert into public.organizations (name, legal_name, registration_no, tin, address, city, province, zip_code,
    contact_email, contact_phone, created_by)
  values (trim(p->>'name'), nullif(trim(p->>'legal_name'), ''), nullif(trim(p->>'registration_no'), ''),
          nullif(trim(p->>'tin'), ''), nullif(trim(p->>'address'), ''), nullif(trim(p->>'city'), ''),
          nullif(trim(p->>'province'), ''), nullif(trim(p->>'zip_code'), ''), nullif(trim(p->>'contact_email'), ''),
          nullif(trim(p->>'contact_phone'), ''), p_created_by)
  returning id into v_org;

  perform public.lf_seed_role_permissions(v_org);
  perform public.lf_seed_defaults(v_org);
  perform public.lf_attach_user(p_owner, v_org, 'owner', nullif(trim(p->>'owner_name'), ''), nullif(trim(p->>'owner_phone'), ''));

  insert into public.audit_logs (organization_id, actor_id, actor_name, action, table_name, record_id, new_data, context)
  values (v_org, p_created_by, 'Platform administrator', 'account_created', 'organizations', v_org::text,
          jsonb_build_object('name', trim(p->>'name'), 'owner_user_id', p_owner),
          jsonb_build_object('source', 'platform_admin'));
  return jsonb_build_object('organization_id', v_org);
end $$;

create or replace function public.lf_grant_platform_admin(p_user uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  if exists (select 1 from public.user_profiles where id = p_user and organization_id is not null) then
    raise exception 'This login belongs to a lender account; use a separate login for platform administration';
  end if;
  insert into public.platform_admins (user_id, email)
  select id, email from auth.users where id = p_user
  on conflict (user_id) do update set email = excluded.email;
  if not found then raise exception 'User not found'; end if;
end $$;

-- Users who can sign in to a suspended account must not be able to self-onboard into a new one
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
  if public.is_platform_admin() then
    raise exception 'Platform administrators create lender accounts from the admin console' using errcode = '22023';
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

revoke execute on function public.lf_platform_create_organization(jsonb, uuid, uuid) from public, anon, authenticated;
revoke execute on function public.lf_grant_platform_admin(uuid) from public, anon, authenticated;
revoke execute on function public.lf_platform_org_stats(uuid) from public, anon, authenticated;
revoke execute on function public.lf_platform_org_owner(uuid) from public, anon, authenticated;
revoke execute on function public.lf_org_status_guard() from public, anon, authenticated;
grant execute on function public.lf_platform_create_organization(jsonb, uuid, uuid) to service_role;
grant execute on function public.lf_grant_platform_admin(uuid) to service_role;
