-- =============================================================================
-- LendFlow — ATM withdrawals on payments, and richer receipt snapshots
--
-- Lenders often hold the borrower's ATM card: on pay day the collector withdraws the salary,
-- keeps the loan payment and hands the rest back. The payment can now record the amount withdrawn;
-- the cash returned is withdrawal - payment. Both are frozen into the receipt snapshot so a reprint
-- shows exactly what was issued. The amount posted to the loan is still only the payment.
-- =============================================================================

alter table public.loan_payments add column atm_withdrawal numeric(14, 2)
  constraint loan_payments_atm_withdrawal_check check (atm_withdrawal is null or atm_withdrawal >= amount);

comment on column public.loan_payments.atm_withdrawal is
  'Amount withdrawn from the borrower''s ATM for this payment (null when not an ATM collection); cash returned = atm_withdrawal - amount.';

-- record_payment hands the withdrawal to the insert through a transaction-local setting, so the
-- payment engine (lf_record_payment, shared with renewal settlements) is unchanged.
create or replace function public.lf_payment_atm_from_request()
returns trigger language plpgsql set search_path = public as $$
declare v text := nullif(current_setting('lendflow.atm_withdrawal', true), '');
begin
  if new.atm_withdrawal is null and v is not null then
    new.atm_withdrawal := v::numeric;
  end if;
  return new;
end $$;
revoke execute on function public.lf_payment_atm_from_request() from public, anon, authenticated;

create trigger loan_payments_atm_from_request before insert on public.loan_payments
  for each row execute function public.lf_payment_atm_from_request();

create or replace function public.record_payment(p jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('payments.record');
  v_atm numeric;
  v_amount numeric;
  r jsonb;
begin
  if p->>'method' = 'renewal' then
    raise exception 'Renewal settlements are posted automatically when the renewal loan is released' using errcode = '22023';
  end if;
  if nullif(trim(p->>'atm_withdrawal'), '') is not null then
    begin
      v_atm := round((p->>'atm_withdrawal')::numeric, 2);
      v_amount := round((p->>'amount')::numeric, 2);
    exception when others then
      raise exception 'Enter the ATM withdrawal as an amount in pesos' using errcode = '22023';
    end;
    if v_atm <= 0 then
      raise exception 'The ATM withdrawal must be greater than zero' using errcode = '22023';
    end if;
    if v_amount is not null and v_atm < v_amount then
      raise exception 'The ATM withdrawal (PHP %) cannot be less than the loan payment (PHP %)',
        to_char(v_atm, 'FM999,999,990.00'), to_char(v_amount, 'FM999,999,990.00') using errcode = '22023';
    end if;
    perform set_config('lendflow.atm_withdrawal', v_atm::text, true);
  end if;
  r := public.lf_record_payment(v_org, p, true);
  perform set_config('lendflow.atm_withdrawal', '', true);
  return r;
end $$;

-- the withdrawal is part of the posted payment: it can no more be edited than the amount
create or replace function public.lf_payment_guard()
returns trigger language plpgsql as $$
begin
  if new.amount <> old.amount or new.payment_date <> old.payment_date or new.loan_id <> old.loan_id
     or new.borrower_id <> old.borrower_id or new.idempotency_key <> old.idempotency_key
     or new.payment_number <> old.payment_number or new.method <> old.method
     or new.reference_no is distinct from old.reference_no or new.payment_type <> old.payment_type
     or new.atm_withdrawal is distinct from old.atm_withdrawal then
    raise exception 'Posted payments cannot be edited. Reverse the payment and record a new one.' using errcode = '42501';
  end if;
  if old.status = 'reversed' and (new.status <> 'reversed' or new.principal_applied <> old.principal_applied) then
    raise exception 'A reversed payment cannot be changed' using errcode = '42501';
  end if;
  return new;
end $$;

-- receipt snapshot: adds the ATM breakdown, the time the payment was recorded and each installment's status
create or replace function public.lf_receipt_data(p_payment_id uuid, p_receipt_number text)
returns jsonb language sql stable security definer set search_path = public as $$
  select jsonb_build_object(
    'receipt_number', p_receipt_number,
    'payment_number', pay.payment_number,
    'payment_date', pay.payment_date,
    'recorded_at', pay.created_at,
    'issued_at', now(),
    'payment_type', pay.payment_type,
    'amount', pay.amount,
    'atm_withdrawal', pay.atm_withdrawal,
    'cash_returned', case when pay.atm_withdrawal is not null then pay.atm_withdrawal - pay.amount end,
    'method', pay.method,
    'reference_no', pay.reference_no,
    'principal_paid', pay.principal_applied,
    'interest_paid', pay.interest_applied,
    'charges_paid', pay.charges_applied,
    'penalty_paid', pay.penalty_applied,
    'interest_rebated', pay.interest_rebated,
    'installments', coalesce((select jsonb_agg(jsonb_build_object('installment_no', li.installment_no, 'due_date', li.due_date,
                                       'amount', pa.total_amount, 'status', li.status) order by li.installment_no)
                              from public.payment_allocations pa join public.loan_installments li on li.id = pa.installment_id
                              where pa.payment_id = pay.id and not pa.is_reversal), '[]'::jsonb),
    'remaining_balance', l.total_outstanding,
    'outstanding_principal', l.outstanding_principal,
    'loan_number', l.loan_number,
    'loan_status', l.status,
    'borrower_name', b.full_name,
    'borrower_code', b.borrower_code,
    'collector_name', coalesce(rc.full_name, rb.full_name),
    'recorded_by', rb.full_name,
    'company', jsonb_build_object('name', o.name, 'legal_name', o.legal_name, 'address',
                 concat_ws(', ', nullif(o.address, ''), nullif(o.city, ''), nullif(o.province, ''), nullif(o.zip_code, '')),
                 'phone', o.contact_phone, 'email', o.contact_email, 'tin', o.tin, 'registration_no', o.registration_no),
    'footer_note', public.lf_settings(o.id) #>> '{receipt,footer_note}')
  from public.loan_payments pay
  join public.loans l on l.id = pay.loan_id
  join public.borrowers b on b.id = pay.borrower_id
  join public.organizations o on o.id = pay.organization_id
  left join public.user_profiles rc on rc.id = pay.received_by
  left join public.user_profiles rb on rb.id = pay.recorded_by
  where pay.id = p_payment_id
$$;
