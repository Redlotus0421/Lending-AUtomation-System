-- =============================================================================
-- LendFlow — Lender accounts are provisioned only by the platform administrator
--
-- Self-service onboarding is closed: a login that is not attached to an account
-- cannot create one. Accounts are created through the admin console
-- (platform-admin Edge Function -> lf_platform_create_organization).
-- The function is kept, with the same signature, so older clients get a clear error.
-- =============================================================================
create or replace function public.create_organization(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
begin
  raise exception 'Lender accounts are created by the LendFlow administrator. Please contact them for access.'
    using errcode = '42501';
end $$;

-- Attaching a login without a name falls back to its existing profile name or email
create or replace function public.lf_attach_user(p_user_id uuid, p_org uuid, p_role public.app_role, p_full_name text, p_phone text)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_email text := (select email from auth.users where id = p_user_id);
  v_name text := coalesce(nullif(trim(p_full_name), ''),
                          nullif((select full_name from public.user_profiles where id = p_user_id), ''),
                          split_part(v_email, '@', 1), '');
begin
  insert into public.user_profiles (id, organization_id, full_name, email, phone)
  values (p_user_id, p_org, v_name, v_email, p_phone)
  on conflict (id) do update set organization_id = p_org, full_name = v_name,
    phone = coalesce(p_phone, public.user_profiles.phone), is_active = true;
  insert into public.user_roles (user_id, organization_id, role) values (p_user_id, p_org, p_role)
  on conflict (user_id, organization_id) do update set role = excluded.role;
end $$;
