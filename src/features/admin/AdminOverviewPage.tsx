import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Ban, Building2, Contact, HandCoins, Plus, Users, Wallet } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { DataTable, EmptyState, ErrorState, PageHeader, Panel, StatCard, type Column } from '@/components/common'
import { useAuth } from '@/features/auth/AuthProvider'
import { platformService } from '@/services/platform'
import { fmtDate, num, peso } from '@/lib/format'
import type { PlatformOrg } from '@/types/db'
import { AccountStatusPill } from './dialogs'

export default function AdminOverviewPage() {
  const navigate = useNavigate()
  const { access } = useAuth()
  const overview = useQuery({ queryKey: ['platform', 'overview'], queryFn: platformService.overview })
  const orgs = useQuery({ queryKey: ['platform', 'organizations'], queryFn: platformService.listOrganizations })
  const o = overview.data
  const create = () => navigate('/admin/accounts?new=1')

  const columns: Column<PlatformOrg>[] = [
    { key: 'name', header: 'Business', mobile: 'title', cell: (r) => <span className="font-medium">{r.name}</span> },
    { key: 'owner', header: 'Owner', mobile: 'subtitle', cell: (r) => <span className="text-sm text-muted-foreground">{r.owner?.email ?? '—'}</span> },
    { key: 'status', header: 'Status', mobile: 'meta', cell: (r) => <AccountStatusPill status={r.status} /> },
    { key: 'created', header: 'Created', mobile: 'meta', cell: (r) => <span className="text-sm text-muted-foreground">{fmtDate(r.created_at)}</span> },
  ]

  return (
    <div className="space-y-5">
      <PageHeader title={`Welcome${access?.full_name ? `, ${access.full_name.split(' ')[0]}` : ''}`}
        description="Create and manage the lending businesses that use LendFlow."
        actions={<Button onClick={create}><Plus /> Create account</Button>} />

      {overview.error ? <ErrorState error={overview.error} onRetry={() => void overview.refetch()} /> : (
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-3 xl:grid-cols-6">
          <StatCard label="Lender accounts" value={o ? num(o.organizations) : '…'} hint={o ? `${num(o.new_organizations_30d)} new in 30 days` : undefined}
            icon={<Building2 />} tone="navy" onClick={() => navigate('/admin/accounts')} />
          <StatCard label="Suspended" value={o ? num(o.suspended_organizations) : '…'} icon={<Ban />} tone={o?.suspended_organizations ? 'red' : 'green'} />
          <StatCard label="Users" value={o ? num(o.users) : '…'} hint="Active logins in all accounts" icon={<Users />} tone="blue" />
          <StatCard label="Borrowers" value={o ? num(o.borrowers) : '…'} icon={<Contact />} tone="teal" />
          <StatCard label="Active loans" value={o ? num(o.active_loans) : '…'} hint={o ? `${peso(o.outstanding, { compact: true })} outstanding` : undefined} icon={<HandCoins />} tone="amber" />
          <StatCard label="Collected (30 days)" value={o ? peso(o.collected_30d, { compact: true }) : '…'} icon={<Wallet />} tone="green" />
        </div>
      )}

      <Panel title="Recent accounts" bodyClassName="p-3 sm:p-4"
        actions={<Button variant="ghost" size="sm" onClick={() => navigate('/admin/accounts')}>View all</Button>}>
        {orgs.error ? <ErrorState error={orgs.error} onRetry={() => void orgs.refetch()} /> : (
          <DataTable columns={columns} rows={orgs.data?.slice(0, 8)} loading={orgs.isLoading} rowKey={(r) => r.id}
            onRowClick={(r) => navigate(`/admin/accounts/${r.id}`)}
            empty={<EmptyState icon={<Building2 />} title="No lender accounts yet"
              description="Create an account for each lending business. You set the owner's email and a temporary password; they add their own staff."
              action={<Button onClick={create}><Plus /> Create the first account</Button>} />} />
        )}
      </Panel>

      <p className="text-xs text-muted-foreground">
        For privacy, the console shows only counts and totals per account. Borrower, loan and payment records stay visible only
        to each account's own staff.
      </p>
    </div>
  )
}
