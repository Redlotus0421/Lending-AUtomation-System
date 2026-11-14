-- =============================================================================
-- LendFlow — Read models: views, dashboard and financial reports
-- Interest is reported three ways and never mixed:
--   expected  = contractual interest on the schedule (future + past)
--   accrued   = scheduled interest whose due date has passed (earned to date)
--   collected = interest actually received through posted payments
-- All views use security_invoker so Row Level Security of base tables applies.
-- =============================================================================

create or replace view public.v_installments with (security_invoker = true) as
select
  i.id, i.organization_id, i.loan_id, i.installment_no, i.due_date, i.opening_balance,
  i.principal_due, i.interest_due, i.charges_due, i.closing_balance,
  i.principal_paid, i.interest_paid, i.charges_paid, i.penalty_due, i.penalty_paid, i.penalty_waived,
  i.interest_waived, i.charges_waived, i.status, i.paid_at, i.last_payment_date,
  (i.principal_due + i.interest_due + i.charges_due) as scheduled_amount,
  (i.principal_paid + i.interest_paid + i.charges_paid + i.penalty_paid) as amount_paid,
  public.lf_inst_base_remaining(i) as base_remaining,
  (i.penalty_due - i.penalty_paid - i.penalty_waived) as penalty_outstanding,
  public.lf_inst_remaining(i) as amount_remaining,
  case
    when i.status in ('paid', 'waived', 'rescheduled') then i.status
    when i.due_date < public.lf_today(i.organization_id) then 'overdue'
    when i.due_date = public.lf_today(i.organization_id) then 'due_today'
    when i.status = 'partially_paid' then 'partially_paid'
    else 'upcoming'
  end as display_status,
  (i.status = 'partially_paid') as is_partial,
  case when i.status in ('pending', 'partially_paid') then greatest(public.lf_today(i.organization_id) - i.due_date, 0) else 0 end as days_overdue,
  l.loan_number, l.status as loan_status, l.borrower_id, l.collector_id, l.loan_officer_id, l.interest_method,
  b.full_name as borrower_name, b.borrower_code, b.mobile_number,
  cu.full_name as collector_name
from public.loan_installments i
join public.loans l on l.id = i.loan_id
join public.borrowers b on b.id = l.borrower_id
left join public.user_profiles cu on cu.id = l.collector_id;

create or replace view public.v_loans with (security_invoker = true) as
select
  l.*,
  b.full_name as borrower_name, b.borrower_code, b.mobile_number as borrower_mobile, b.profile_photo_path as borrower_photo_path,
  p.name as product_name,
  lo.full_name as loan_officer_name,
  co.full_name as collector_name,
  agg.next_due_date, agg.next_due_amount, agg.overdue_amount, agg.overdue_count, agg.oldest_overdue_date,
  case when agg.oldest_overdue_date is null then 0 else public.lf_today(l.organization_id) - agg.oldest_overdue_date end as days_overdue,
  agg.paid_installments, agg.total_installments,
  (select a.status from public.loan_agreements a where a.loan_id = l.id order by a.version desc limit 1) as agreement_status
from public.loans l
join public.borrowers b on b.id = l.borrower_id
left join public.loan_products p on p.id = l.product_id
left join public.user_profiles lo on lo.id = l.loan_officer_id
left join public.user_profiles co on co.id = l.collector_id
left join lateral (
  select
    min(i.due_date) filter (where i.status in ('pending', 'partially_paid') and i.due_date >= public.lf_today(l.organization_id)) as next_due_date,
    (select public.lf_inst_remaining(x) from public.loan_installments x
      where x.loan_id = l.id and x.status in ('pending', 'partially_paid') order by x.installment_no limit 1) as next_due_amount,
    coalesce(sum(public.lf_inst_remaining(i)) filter (where i.status in ('pending', 'partially_paid') and i.due_date < public.lf_today(l.organization_id)), 0) as overdue_amount,
    count(*) filter (where i.status in ('pending', 'partially_paid') and i.due_date < public.lf_today(l.organization_id)) as overdue_count,
    min(i.due_date) filter (where i.status in ('pending', 'partially_paid') and i.due_date < public.lf_today(l.organization_id)) as oldest_overdue_date,
    count(*) filter (where i.status = 'paid') as paid_installments,
    count(*) filter (where i.status <> 'rescheduled') as total_installments
  from public.loan_installments i where i.loan_id = l.id
) agg on true;

-- ---------------------------------------------------------------------------
-- Dashboard
-- ---------------------------------------------------------------------------
create or replace function public.dashboard_summary(p_from date default null, p_to date default null)
returns jsonb language plpgsql volatile security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('dashboard.view');
  v_today date := public.lf_today(v_org);
  v_from date := coalesce(p_from, (date_trunc('month', v_today) - interval '11 months')::date);
  v_to date := coalesce(p_to, v_today);
  v_scope boolean := public.has_permission('loans.view_all');
  v jsonb;
begin
  create temporary table if not exists _dash_loans (id uuid primary key) on commit drop;
  truncate _dash_loans;
  insert into _dash_loans select id from public.loans l
  where l.organization_id = v_org and (v_scope or public.can_access_loan(l.id));

  select jsonb_build_object(
    'range', jsonb_build_object('from', v_from, 'to', v_to, 'today', v_today),
    'financial', (
      select jsonb_build_object(
        'capital_released', coalesce(sum(l.principal) filter (where l.actual_release_date between v_from and v_to), 0),
        'capital_released_all_time', coalesce(sum(l.principal) filter (where l.actual_release_date is not null), 0),
        'outstanding_principal', coalesce(sum(l.outstanding_principal) filter (where l.status in ('active', 'defaulted')), 0),
        'remaining_receivables', coalesce(sum(l.total_outstanding) filter (where l.status in ('active', 'defaulted')), 0),
        'interest_expected_remaining', coalesce(sum(l.outstanding_interest) filter (where l.status in ('active', 'defaulted')), 0),
        'interest_expected_contract', coalesce(sum(l.total_interest) filter (where l.actual_release_date between v_from and v_to), 0)
      ) from public.loans l join _dash_loans d on d.id = l.id),
    'collections', (
      select jsonb_build_object(
        'total', coalesce(sum(p.amount), 0),
        'principal', coalesce(sum(p.principal_applied), 0),
        'interest', coalesce(sum(p.interest_applied), 0),
        'charges', coalesce(sum(p.charges_applied), 0),
        'penalties', coalesce(sum(p.penalty_applied), 0),
        'count', count(*)
      ) from public.loan_payments p join _dash_loans d on d.id = p.loan_id
      where p.status = 'posted' and p.payment_date between v_from and v_to),
    'interest_accrued', (
      select coalesce(sum(i.interest_due - i.interest_waived), 0)
      from public.loan_installments i join _dash_loans d on d.id = i.loan_id
      where i.due_date between v_from and least(v_to, v_today) and i.status <> 'rescheduled'),
    'overdue', (
      select jsonb_build_object(
        'amount', coalesce(sum(public.lf_inst_remaining(i)), 0),
        'loans', count(distinct i.loan_id),
        'installments', count(*))
      from public.loan_installments i join _dash_loans d on d.id = i.loan_id
      join public.loans l on l.id = i.loan_id and l.status in ('active', 'defaulted')
      where i.status in ('pending', 'partially_paid') and i.due_date < v_today),
    'operational', jsonb_build_object(
      'borrowers', (select count(*) from public.borrowers b where b.organization_id = v_org and b.status = 'active'
                    and (public.has_permission('borrowers.view_all') or public.can_access_borrower(b.id))),
      'active_loans', (select count(*) from public.loans l join _dash_loans d on d.id = l.id where l.status = 'active'),
      'pending_applications', (select count(*) from public.loans l join _dash_loans d on d.id = l.id
                               where l.status in ('draft', 'pending_review', 'approved')),
      'awaiting_signature', (select count(*) from public.loans l join _dash_loans d on d.id = l.id
                             where l.status in ('awaiting_borrower_signature', 'awaiting_lender_signature')),
      'ready_for_release', (select count(*) from public.loans l join _dash_loans d on d.id = l.id where l.status in ('ready_for_release', 'fully_signed')),
      'due_today', (select count(distinct i.loan_id) from public.loan_installments i join _dash_loans d on d.id = i.loan_id
                    where i.due_date = v_today and i.status in ('pending', 'partially_paid')),
      'due_today_amount', (select coalesce(sum(public.lf_inst_remaining(i)), 0) from public.loan_installments i join _dash_loans d on d.id = i.loan_id
                    where i.due_date = v_today and i.status in ('pending', 'partially_paid'))
    ),
    'monthly', (
      select coalesce(jsonb_agg(jsonb_build_object(
        'month', to_char(m, 'YYYY-MM'), 'label', to_char(m, 'Mon YY'),
        'released', coalesce((select sum(l.principal) from public.loans l join _dash_loans d on d.id = l.id
                              where date_trunc('month', l.actual_release_date) = m), 0),
        'loans_released', coalesce((select count(*) from public.loans l join _dash_loans d on d.id = l.id
                              where date_trunc('month', l.actual_release_date) = m), 0),
        'collected', coalesce((select sum(p.amount) from public.loan_payments p join _dash_loans d on d.id = p.loan_id
                               where p.status = 'posted' and date_trunc('month', p.payment_date) = m), 0),
        'principal_collected', coalesce((select sum(p.principal_applied) from public.loan_payments p join _dash_loans d on d.id = p.loan_id
                               where p.status = 'posted' and date_trunc('month', p.payment_date) = m), 0),
        'interest_collected', coalesce((select sum(p.interest_applied) from public.loan_payments p join _dash_loans d on d.id = p.loan_id
                               where p.status = 'posted' and date_trunc('month', p.payment_date) = m), 0),
        'other_collected', coalesce((select sum(p.charges_applied + p.penalty_applied) from public.loan_payments p join _dash_loans d on d.id = p.loan_id
                               where p.status = 'posted' and date_trunc('month', p.payment_date) = m), 0),
        'scheduled_due', coalesce((select sum(i.principal_due + i.interest_due + i.charges_due) from public.loan_installments i
                               join _dash_loans d on d.id = i.loan_id
                               where date_trunc('month', i.due_date) = m and i.status <> 'rescheduled'), 0),
        'paid_on_due', coalesce((select sum(i.principal_paid + i.interest_paid + i.charges_paid) from public.loan_installments i
                               join _dash_loans d on d.id = i.loan_id
                               where date_trunc('month', i.due_date) = m and i.status <> 'rescheduled'), 0)
      ) order by m), '[]'::jsonb)
      from generate_series(date_trunc('month', v_from), date_trunc('month', v_to), interval '1 month') m),
    'portfolio_aging', (
      select coalesce(jsonb_agg(jsonb_build_object('bucket', bucket, 'loans', cnt, 'outstanding_principal', amt) order by ord), '[]'::jsonb)
      from (
        select bucket, ord, count(*) as cnt, sum(outstanding_principal) as amt from (
          select l.outstanding_principal,
            case when x.oldest is null then 'Current'
                 when v_today - x.oldest <= 30 then '1–30 days'
                 when v_today - x.oldest <= 60 then '31–60 days'
                 when v_today - x.oldest <= 90 then '61–90 days'
                 else '90+ days' end as bucket,
            case when x.oldest is null then 0
                 when v_today - x.oldest <= 30 then 1
                 when v_today - x.oldest <= 60 then 2
                 when v_today - x.oldest <= 90 then 3
                 else 4 end as ord
          from public.loans l join _dash_loans d on d.id = l.id
          left join lateral (select min(i.due_date) as oldest from public.loan_installments i
                             where i.loan_id = l.id and i.status in ('pending', 'partially_paid') and i.due_date < v_today) x on true
          where l.status in ('active', 'defaulted')
        ) a group by bucket, ord
      ) g),
    'recent_applications', (
      select coalesce(jsonb_agg(r order by r->>'created_at' desc), '[]'::jsonb) from (
        select jsonb_build_object('id', l.id, 'loan_number', l.loan_number, 'borrower_name', b.full_name, 'principal', l.principal,
                                  'status', l.status, 'created_at', l.created_at) as r
        from public.loans l join _dash_loans d on d.id = l.id join public.borrowers b on b.id = l.borrower_id
        where l.status in ('draft', 'pending_review', 'approved', 'awaiting_borrower_signature', 'awaiting_lender_signature', 'fully_signed', 'ready_for_release')
        order by l.created_at desc limit 6) t),
    'upcoming_payments', (
      select coalesce(jsonb_agg(r order by (r->>'due_date'), (r->>'installment_no')::int), '[]'::jsonb) from (
        select jsonb_build_object('installment_id', i.id, 'loan_id', l.id, 'loan_number', l.loan_number, 'borrower_name', b.full_name,
                                  'installment_no', i.installment_no, 'due_date', i.due_date, 'amount', public.lf_inst_remaining(i)) as r
        from public.loan_installments i join _dash_loans d on d.id = i.loan_id
        join public.loans l on l.id = i.loan_id join public.borrowers b on b.id = l.borrower_id
        where i.status in ('pending', 'partially_paid') and i.due_date between v_today and v_today + 7 and l.status = 'active'
        order by i.due_date, i.installment_no limit 8) t),
    'recent_collections', (
      select coalesce(jsonb_agg(r order by r->>'created_at' desc), '[]'::jsonb) from (
        select jsonb_build_object('payment_id', p.id, 'payment_number', p.payment_number, 'loan_id', l.id, 'loan_number', l.loan_number,
                                  'borrower_name', b.full_name, 'amount', p.amount, 'payment_date', p.payment_date, 'method', p.method,
                                  'created_at', p.created_at) as r
        from public.loan_payments p join _dash_loans d on d.id = p.loan_id
        join public.loans l on l.id = p.loan_id join public.borrowers b on b.id = p.borrower_id
        where p.status = 'posted' order by p.created_at desc limit 6) t),
    'overdue_borrowers', (
      select coalesce(jsonb_agg(r order by (r->>'overdue_amount')::numeric desc), '[]'::jsonb) from (
        select jsonb_build_object('loan_id', l.id, 'loan_number', l.loan_number, 'borrower_id', b.id, 'borrower_name', b.full_name,
                                  'mobile_number', b.mobile_number, 'overdue_amount', sum(public.lf_inst_remaining(i)),
                                  'days_overdue', v_today - min(i.due_date)) as r
        from public.loan_installments i join _dash_loans d on d.id = i.loan_id
        join public.loans l on l.id = i.loan_id join public.borrowers b on b.id = l.borrower_id
        where i.status in ('pending', 'partially_paid') and i.due_date < v_today and l.status in ('active', 'defaulted')
        group by l.id, l.loan_number, b.id, b.full_name, b.mobile_number
        order by sum(public.lf_inst_remaining(i)) desc limit 6) t),
    'recently_signed', (
      select coalesce(jsonb_agg(r order by r->>'fully_signed_at' desc), '[]'::jsonb) from (
        select jsonb_build_object('agreement_id', a.id, 'agreement_number', a.agreement_number, 'loan_id', l.id,
                                  'loan_number', l.loan_number, 'borrower_name', b.full_name, 'fully_signed_at', a.fully_signed_at) as r
        from public.loan_agreements a join _dash_loans d on d.id = a.loan_id
        join public.loans l on l.id = a.loan_id join public.borrowers b on b.id = l.borrower_id
        where a.status = 'fully_signed' order by a.fully_signed_at desc limit 6) t)
  ) into v;
  return v;
end $$;

-- ---------------------------------------------------------------------------
-- Reports: get_report(type, from, to, filters) -> {columns, rows, totals}
-- ---------------------------------------------------------------------------
create or replace function public.get_report(p_report text, p_from date default null, p_to date default null, p_filters jsonb default '{}'::jsonb)
returns jsonb language plpgsql volatile security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('reports.view');
  v_today date := public.lf_today(v_org);
  v_from date := coalesce(p_from, date_trunc('month', v_today)::date);
  v_to date := coalesce(p_to, v_today);
  v_scope boolean := public.has_permission('loans.view_all');
  v_collector uuid := nullif(p_filters->>'collector_id', '')::uuid;
  v_cols jsonb;
  v_rows jsonb;
begin
  create temporary table if not exists _rep_loans (id uuid primary key) on commit drop;
  truncate _rep_loans;
  insert into _rep_loans select id from public.loans l
  where l.organization_id = v_org and (v_scope or public.can_access_loan(l.id))
    and (v_collector is null or l.collector_id = v_collector);

  if p_report = 'loans_released' then
    v_cols := '[{"key":"loan_number","label":"Loan #"},{"key":"borrower_name","label":"Borrower"},{"key":"product","label":"Product"},
                {"key":"release_date","label":"Released","type":"date"},{"key":"interest","label":"Rate"},
                {"key":"principal","label":"Principal","type":"money","total":true},{"key":"net_proceeds","label":"Net proceeds","type":"money","total":true},
                {"key":"total_interest","label":"Contract interest","type":"money","total":true},{"key":"total_repayment","label":"Total repayment","type":"money","total":true},
                {"key":"officer","label":"Loan officer"}]';
    select coalesce(jsonb_agg(jsonb_build_object('loan_id', l.id, 'loan_number', l.loan_number, 'borrower_name', b.full_name, 'product', p.name,
             'release_date', l.actual_release_date,
             'interest', trim_scale(l.interest_rate)::text || '% / ' || l.rate_period || ' (' || l.interest_method || ')',
             'principal', l.principal, 'net_proceeds', l.net_proceeds, 'total_interest', l.total_interest,
             'total_repayment', l.total_repayment, 'officer', u.full_name) order by l.actual_release_date, l.loan_number), '[]'::jsonb)
      into v_rows
    from public.loans l join _rep_loans r on r.id = l.id join public.borrowers b on b.id = l.borrower_id
    left join public.loan_products p on p.id = l.product_id left join public.user_profiles u on u.id = l.loan_officer_id
    where l.actual_release_date between v_from and v_to;

  elsif p_report in ('principal_outstanding', 'outstanding_receivables') then
    v_cols := case when p_report = 'principal_outstanding' then
      '[{"key":"loan_number","label":"Loan #"},{"key":"borrower_name","label":"Borrower"},{"key":"status","label":"Status","type":"status"},
        {"key":"maturity_date","label":"Maturity","type":"date"},{"key":"principal","label":"Principal","type":"money","total":true},
        {"key":"principal_paid","label":"Principal paid","type":"money","total":true},
        {"key":"outstanding_principal","label":"Outstanding principal","type":"money","total":true}]'::jsonb
      else
      '[{"key":"loan_number","label":"Loan #"},{"key":"borrower_name","label":"Borrower"},{"key":"status","label":"Status","type":"status"},
        {"key":"outstanding_principal","label":"Principal","type":"money","total":true},{"key":"outstanding_interest","label":"Interest","type":"money","total":true},
        {"key":"outstanding_charges","label":"Charges","type":"money","total":true},{"key":"outstanding_penalties","label":"Penalties","type":"money","total":true},
        {"key":"total_outstanding","label":"Total receivable","type":"money","total":true},{"key":"overdue_amount","label":"Of which overdue","type":"money","total":true}]'::jsonb end;
    select coalesce(jsonb_agg(jsonb_build_object('loan_id', l.id, 'loan_number', l.loan_number, 'borrower_name', b.full_name, 'status', l.status,
             'maturity_date', l.maturity_date, 'principal', l.principal, 'principal_paid', l.principal_paid,
             'outstanding_principal', l.outstanding_principal, 'outstanding_interest', l.outstanding_interest,
             'outstanding_charges', l.outstanding_charges, 'outstanding_penalties', l.outstanding_penalties,
             'total_outstanding', l.total_outstanding,
             'overdue_amount', coalesce((select sum(public.lf_inst_remaining(i)) from public.loan_installments i
                                         where i.loan_id = l.id and i.status in ('pending', 'partially_paid') and i.due_date < v_today), 0))
             order by l.total_outstanding desc), '[]'::jsonb)
      into v_rows
    from public.loans l join _rep_loans r on r.id = l.id join public.borrowers b on b.id = l.borrower_id
    where l.status in ('active', 'defaulted');

  elsif p_report = 'interest_earned' then
    v_cols := '[{"key":"loan_number","label":"Loan #"},{"key":"borrower_name","label":"Borrower"},{"key":"method","label":"Method"},
                {"key":"accrued","label":"Accrued (due in period)","type":"money","total":true},
                {"key":"collected","label":"Collected in period","type":"money","total":true},
                {"key":"waived","label":"Waived/rebated","type":"money","total":true},
                {"key":"expected_remaining","label":"Expected (future)","type":"money","total":true}]';
    select coalesce(jsonb_agg(x order by x->>'loan_number'), '[]'::jsonb) into v_rows from (
      select jsonb_build_object('loan_id', l.id, 'loan_number', l.loan_number, 'borrower_name', b.full_name, 'method', l.interest_method,
        'accrued', coalesce((select sum(i.interest_due) from public.loan_installments i where i.loan_id = l.id
                             and i.due_date between v_from and v_to and i.status <> 'rescheduled'), 0),
        'collected', coalesce((select sum(p.interest_applied) from public.loan_payments p where p.loan_id = l.id and p.status = 'posted'
                               and p.payment_date between v_from and v_to), 0),
        'waived', coalesce((select sum(i.interest_waived) from public.loan_installments i where i.loan_id = l.id
                            and i.due_date between v_from and v_to), 0),
        'expected_remaining', coalesce((select sum(i.interest_due - i.interest_paid - i.interest_waived) from public.loan_installments i
                                        where i.loan_id = l.id and i.due_date > v_to and i.status in ('pending', 'partially_paid')), 0)) as x
      from public.loans l join _rep_loans r on r.id = l.id join public.borrowers b on b.id = l.borrower_id
      where l.status in ('active', 'defaulted', 'completed')
    ) t
    where (x->>'accrued')::numeric <> 0 or (x->>'collected')::numeric <> 0 or (x->>'expected_remaining')::numeric <> 0;

  elsif p_report in ('interest_collected', 'total_collections') then
    v_cols := case when p_report = 'interest_collected' then
      '[{"key":"payment_date","label":"Date","type":"date"},{"key":"payment_number","label":"Payment #"},{"key":"receipt_number","label":"Receipt #"},
        {"key":"loan_number","label":"Loan #"},{"key":"borrower_name","label":"Borrower"},
        {"key":"interest","label":"Interest collected","type":"money","total":true}]'::jsonb
      else
      '[{"key":"payment_date","label":"Date","type":"date"},{"key":"payment_number","label":"Payment #"},{"key":"receipt_number","label":"Receipt #"},
        {"key":"loan_number","label":"Loan #"},{"key":"borrower_name","label":"Borrower"},{"key":"method","label":"Method"},
        {"key":"principal","label":"Principal","type":"money","total":true},{"key":"interest","label":"Interest","type":"money","total":true},
        {"key":"charges","label":"Charges","type":"money","total":true},{"key":"penalties","label":"Penalties","type":"money","total":true},
        {"key":"amount","label":"Total","type":"money","total":true},{"key":"collector","label":"Collector"}]'::jsonb end;
    select coalesce(jsonb_agg(jsonb_build_object('payment_id', p.id, 'loan_id', l.id, 'payment_date', p.payment_date,
             'payment_number', p.payment_number, 'receipt_number', rc.receipt_number, 'loan_number', l.loan_number,
             'borrower_name', b.full_name, 'method', replace(p.method, '_', ' '),
             'principal', p.principal_applied, 'interest', p.interest_applied, 'charges', p.charges_applied,
             'penalties', p.penalty_applied, 'amount', p.amount, 'collector', u.full_name)
             order by p.payment_date, p.payment_number), '[]'::jsonb)
      into v_rows
    from public.loan_payments p join _rep_loans r on r.id = p.loan_id join public.loans l on l.id = p.loan_id
    join public.borrowers b on b.id = p.borrower_id left join public.payment_receipts rc on rc.payment_id = p.id
    left join public.user_profiles u on u.id = p.received_by
    where p.status = 'posted' and p.payment_date between v_from and v_to
      and (p_report <> 'interest_collected' or p.interest_applied > 0);

  elsif p_report = 'overdue_accounts' then
    v_cols := '[{"key":"borrower_name","label":"Borrower"},{"key":"mobile_number","label":"Mobile"},{"key":"loan_number","label":"Loan #"},
                {"key":"oldest_due_date","label":"Oldest due","type":"date"},{"key":"days_overdue","label":"Days overdue","type":"number"},
                {"key":"installments","label":"Installments","type":"number"},
                {"key":"overdue_amount","label":"Overdue amount","type":"money","total":true},
                {"key":"penalties","label":"Penalties (to date)","type":"money","total":true},{"key":"collector","label":"Collector"}]';
    select coalesce(jsonb_agg(x order by (x->>'days_overdue')::int desc), '[]'::jsonb) into v_rows from (
      select jsonb_build_object('loan_id', l.id, 'borrower_name', b.full_name, 'mobile_number', b.mobile_number, 'loan_number', l.loan_number,
        'oldest_due_date', min(i.due_date), 'days_overdue', v_today - min(i.due_date), 'installments', count(*),
        'overdue_amount', sum(public.lf_inst_base_remaining(i)),
        'penalties', sum((i.penalty_due - i.penalty_paid - i.penalty_waived) + public.lf_projected_penalty(i.id, v_today)),
        'collector', u.full_name) as x
      from public.loan_installments i join _rep_loans r on r.id = i.loan_id
      join public.loans l on l.id = i.loan_id join public.borrowers b on b.id = l.borrower_id
      left join public.user_profiles u on u.id = l.collector_id
      where i.status in ('pending', 'partially_paid') and i.due_date < v_today and l.status in ('active', 'defaulted')
      group by l.id, b.full_name, b.mobile_number, l.loan_number, u.full_name
    ) t;

  elsif p_report in ('completed_loans', 'defaulted_loans') then
    v_cols := '[{"key":"loan_number","label":"Loan #"},{"key":"borrower_name","label":"Borrower"},{"key":"release_date","label":"Released","type":"date"},
                {"key":"closed_date","label":"Closed / defaulted","type":"date"},{"key":"principal","label":"Principal","type":"money","total":true},
                {"key":"total_paid","label":"Total paid","type":"money","total":true},{"key":"interest_paid","label":"Interest paid","type":"money","total":true},
                {"key":"total_outstanding","label":"Outstanding","type":"money","total":true}]';
    select coalesce(jsonb_agg(jsonb_build_object('loan_id', l.id, 'loan_number', l.loan_number, 'borrower_name', b.full_name,
             'release_date', l.actual_release_date,
             'closed_date', (case when p_report = 'completed_loans' then l.completed_at else l.defaulted_at end at time zone 'Asia/Manila')::date,
             'principal', l.principal, 'total_paid', l.total_paid, 'interest_paid', l.interest_paid, 'total_outstanding', l.total_outstanding)
             order by l.loan_number), '[]'::jsonb)
      into v_rows
    from public.loans l join _rep_loans r on r.id = l.id join public.borrowers b on b.id = l.borrower_id
    where (p_report = 'completed_loans' and l.status = 'completed'
           and (l.completed_at at time zone 'Asia/Manila')::date between v_from and v_to)
       or (p_report = 'defaulted_loans' and l.status = 'defaulted');

  elsif p_report = 'collector_performance' then
    v_cols := '[{"key":"collector","label":"Collector"},{"key":"loans","label":"Assigned loans","type":"number","total":true},
                {"key":"due","label":"Due in period","type":"money","total":true},{"key":"collected","label":"Collected","type":"money","total":true},
                {"key":"payments","label":"Payments","type":"number","total":true},{"key":"rate","label":"Collection rate","type":"percent"},
                {"key":"overdue","label":"Overdue now","type":"money","total":true}]';
    select coalesce(jsonb_agg(x order by x->>'collector'), '[]'::jsonb) into v_rows from (
      select jsonb_build_object('collector_id', u.id, 'collector', u.full_name,
        'loans', (select count(*) from public.loans l join _rep_loans r on r.id = l.id where l.collector_id = u.id and l.status in ('active', 'defaulted')),
        'due', coalesce((select sum(i.principal_due + i.interest_due + i.charges_due) from public.loan_installments i join public.loans l on l.id = i.loan_id
                         join _rep_loans r on r.id = l.id where l.collector_id = u.id and i.due_date between v_from and v_to and i.status <> 'rescheduled'), 0),
        'collected', coalesce((select sum(p.amount) from public.loan_payments p join _rep_loans r on r.id = p.loan_id
                               where p.received_by = u.id and p.status = 'posted' and p.payment_date between v_from and v_to), 0),
        'payments', (select count(*) from public.loan_payments p join _rep_loans r on r.id = p.loan_id
                     where p.received_by = u.id and p.status = 'posted' and p.payment_date between v_from and v_to),
        'overdue', coalesce((select sum(public.lf_inst_remaining(i)) from public.loan_installments i join public.loans l on l.id = i.loan_id
                             join _rep_loans r on r.id = l.id where l.collector_id = u.id and i.status in ('pending', 'partially_paid')
                             and i.due_date < v_today and l.status in ('active', 'defaulted')), 0)) as x
      from public.user_profiles u
      join public.user_roles ur on ur.user_id = u.id and ur.organization_id = v_org
      where u.organization_id = v_org and (ur.role = 'collector'
        or exists (select 1 from public.loan_payments p where p.received_by = u.id and p.payment_date between v_from and v_to)
        or exists (select 1 from public.loans l where l.collector_id = u.id))
        and (v_collector is null or u.id = v_collector)
    ) t;
    select coalesce(jsonb_agg(x || jsonb_build_object('rate', case when (x->>'due')::numeric > 0
                    then round((x->>'collected')::numeric / (x->>'due')::numeric * 100, 1) else null end)), '[]'::jsonb)
      into v_rows from jsonb_array_elements(v_rows) x;

  elsif p_report = 'borrower_performance' then
    v_cols := '[{"key":"borrower_name","label":"Borrower"},{"key":"borrower_code","label":"Code"},{"key":"loans","label":"Loans","type":"number"},
                {"key":"due_installments","label":"Installments due","type":"number","total":true},{"key":"on_time","label":"Paid on time","type":"number","total":true},
                {"key":"late","label":"Paid late","type":"number","total":true},{"key":"overdue","label":"Currently overdue","type":"number","total":true},
                {"key":"on_time_rate","label":"On-time rate","type":"percent"},{"key":"total_paid","label":"Total paid","type":"money","total":true}]';
    select coalesce(jsonb_agg(x order by x->>'borrower_name'), '[]'::jsonb) into v_rows from (
      select jsonb_build_object('borrower_id', b.id, 'borrower_name', b.full_name, 'borrower_code', b.borrower_code,
        'loans', count(distinct l.id),
        'due_installments', count(i.id) filter (where i.due_date <= v_today and i.status <> 'rescheduled'),
        'on_time', count(i.id) filter (where i.status = 'paid' and i.paid_at <= i.due_date),
        'late', count(i.id) filter (where i.status = 'paid' and i.paid_at > i.due_date),
        'overdue', count(i.id) filter (where i.status in ('pending', 'partially_paid') and i.due_date < v_today),
        'on_time_rate', case when count(i.id) filter (where i.due_date <= v_today and i.status <> 'rescheduled') > 0
                        then round(count(i.id) filter (where i.status = 'paid' and i.paid_at <= i.due_date)::numeric * 100
                                   / count(i.id) filter (where i.due_date <= v_today and i.status <> 'rescheduled'), 1) end,
        'total_paid', (select coalesce(sum(l2.total_paid), 0) from public.loans l2 join _rep_loans r2 on r2.id = l2.id where l2.borrower_id = b.id)) as x
      from public.borrowers b
      join public.loans l on l.borrower_id = b.id join _rep_loans r on r.id = l.id
      left join public.loan_installments i on i.loan_id = l.id
      where l.status in ('active', 'defaulted', 'completed')
      group by b.id, b.full_name, b.borrower_code
    ) t;

  elsif p_report in ('daily_cash', 'monthly_cash') then
    v_cols := '[{"key":"period","label":"Period"},{"key":"payments","label":"Payments","type":"number","total":true},
                {"key":"cash","label":"Cash","type":"money","total":true},{"key":"bank_transfer","label":"Bank transfer","type":"money","total":true},
                {"key":"e_wallet","label":"E-wallet","type":"money","total":true},{"key":"other","label":"Check / other","type":"money","total":true},
                {"key":"collected","label":"Total collected","type":"money","total":true},
                {"key":"released","label":"Loans released (net cash out)","type":"money","total":true},
                {"key":"net","label":"Net cash flow","type":"money","total":true}]';
    select coalesce(jsonb_agg(x order by x->>'sort'), '[]'::jsonb) into v_rows from (
      select jsonb_build_object(
        'sort', to_char(d, 'YYYY-MM-DD'),
        'period', case when p_report = 'daily_cash' then to_char(d, 'Mon DD, YYYY (Dy)') else to_char(d, 'FMMonth YYYY') end,
        'payments', count(p.id),
        'cash', coalesce(sum(p.amount) filter (where p.method = 'cash'), 0),
        'bank_transfer', coalesce(sum(p.amount) filter (where p.method = 'bank_transfer'), 0),
        'e_wallet', coalesce(sum(p.amount) filter (where p.method = 'e_wallet'), 0),
        'other', coalesce(sum(p.amount) filter (where p.method in ('check', 'other')), 0),
        'collected', coalesce(sum(p.amount), 0),
        'released', rel.amount,
        'net', coalesce(sum(p.amount), 0) - rel.amount) as x
      from generate_series(case when p_report = 'daily_cash' then v_from else date_trunc('month', v_from)::date end, v_to,
                           case when p_report = 'daily_cash' then interval '1 day' else interval '1 month' end) d
      left join public.loan_payments p on p.status = 'posted' and p.loan_id in (select id from _rep_loans)
        and p.payment_date >= d::date
        and p.payment_date < (d + case when p_report = 'daily_cash' then interval '1 day' else interval '1 month' end)::date
        and p.payment_date between v_from and v_to
      left join lateral (
        select coalesce(sum(-fl.cash_delta), 0) as amount from public.financial_ledger fl
        where fl.loan_id in (select id from _rep_loans) and fl.entry_type in ('disbursement', 'fee_deducted')
          and fl.entry_date >= d::date
          and fl.entry_date < (d + case when p_report = 'daily_cash' then interval '1 day' else interval '1 month' end)::date
          and fl.entry_date between v_from and v_to
      ) rel on true
      group by d, rel.amount
    ) t;
  else
    raise exception 'Unknown report "%"', p_report using errcode = '22023';
  end if;

  return jsonb_build_object(
    'report', p_report, 'from', v_from, 'to', v_to, 'generated_at', now(),
    'columns', v_cols, 'rows', coalesce(v_rows, '[]'::jsonb),
    'totals', (select coalesce(jsonb_object_agg(c->>'key', (select coalesce(sum((r->>(c->>'key'))::numeric), 0)
                                                             from jsonb_array_elements(coalesce(v_rows, '[]'::jsonb)) r)), '{}'::jsonb)
               from jsonb_array_elements(v_cols) c where (c->>'total')::boolean));
end $$;

-- Collections summary for the Payments & Collections page
create or replace function public.collections_summary()
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  v_org uuid := public.lf_require('payments.view');
  v_today date := public.lf_today(v_org);
  v_scope boolean := public.has_permission('loans.view_all');
begin
  return (
    with scoped as (select id from public.loans l where l.organization_id = v_org and (v_scope or public.can_access_loan(l.id))),
    pay as (select p.* from public.loan_payments p join scoped s on s.id = p.loan_id where p.status = 'posted')
    select jsonb_build_object(
      'today', v_today,
      'collected_today', coalesce((select sum(amount) from pay where payment_date = v_today), 0),
      'collected_week', coalesce((select sum(amount) from pay where payment_date >= date_trunc('week', v_today)::date), 0),
      'collected_month', coalesce((select sum(amount) from pay where payment_date >= date_trunc('month', v_today)::date), 0),
      'count_today', (select count(*) from pay where payment_date = v_today),
      'due_today', coalesce((select sum(public.lf_inst_remaining(i)) from public.loan_installments i join scoped s on s.id = i.loan_id
                             join public.loans l on l.id = i.loan_id and l.status in ('active', 'defaulted')
                             where i.status in ('pending', 'partially_paid') and i.due_date = v_today), 0),
      'overdue', coalesce((select sum(public.lf_inst_remaining(i)) from public.loan_installments i join scoped s on s.id = i.loan_id
                           join public.loans l on l.id = i.loan_id and l.status in ('active', 'defaulted')
                           where i.status in ('pending', 'partially_paid') and i.due_date < v_today), 0),
      'receivables', coalesce((select sum(l.total_outstanding) from public.loans l join scoped s on s.id = l.id
                               where l.status in ('active', 'defaulted')), 0),
      'upcoming_7d', coalesce((select sum(public.lf_inst_remaining(i)) from public.loan_installments i join scoped s on s.id = i.loan_id
                               join public.loans l on l.id = i.loan_id and l.status = 'active'
                               where i.status in ('pending', 'partially_paid') and i.due_date between v_today + 1 and v_today + 7), 0)
    ));
end $$;
