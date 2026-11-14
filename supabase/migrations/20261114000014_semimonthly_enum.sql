-- =============================================================================
-- LendFlow — Semi-monthly repayment (collection twice a month)
-- The enum value is added in its own migration: PostgreSQL cannot use a newly
-- added enum value inside the transaction that adds it. The engine changes
-- follow in 20261114000015_semimonthly_engine.sql.
-- =============================================================================
alter type public.repayment_frequency add value if not exists 'semimonthly' after 'weekly';
