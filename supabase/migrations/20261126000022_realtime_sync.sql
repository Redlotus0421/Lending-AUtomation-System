-- =============================================================================
-- LendFlow — real-time sync between the web app and the Android app
--
-- Adds the tables both clients display to the `supabase_realtime` publication, so a change made on
-- one device (a payment, a new borrower, a signature) is pushed to every other signed-in device,
-- which then re-reads the affected screens. Realtime evaluates row-level security for every
-- subscriber, so a user is only notified about rows their RLS policies already let them read;
-- no data is exposed that the REST API would not return.
-- Idempotent: tables already in the publication are skipped; a missing publication is created.
-- =============================================================================

do $$
declare
  t text;
  tables text[] := array[
    'borrowers', 'borrower_documents', 'loans', 'loan_installments', 'loan_payments', 'payment_receipts',
    'loan_agreements', 'agreement_signatures', 'agreement_signing_sessions', 'loan_notes', 'notifications',
    'loan_products', 'loan_agreement_templates', 'user_profiles', 'user_roles', 'organizations', 'loan_renewals'
  ];
begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    begin
      create publication supabase_realtime;
    exception when others then
      raise notice 'realtime publication not available here (%), skipping', sqlerrm;
      return;
    end;
  end if;

  foreach t in array tables loop
    if exists (select 1 from pg_class c join pg_namespace n on n.oid = c.relnamespace
               where n.nspname = 'public' and c.relname = t and c.relkind = 'r')
       and not exists (select 1 from pg_publication_tables
                       where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = t) then
      begin
        execute format('alter publication supabase_realtime add table public.%I', t);
      exception when others then
        raise notice 'could not add % to supabase_realtime: %', t, sqlerrm;
      end;
    end if;
  end loop;
end $$;
