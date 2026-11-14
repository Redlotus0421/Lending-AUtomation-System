import { useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Building2, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { DataTable, EmptyState, ErrorState, NativeSelect, PageHeader, Panel, SearchInput, type Column } from '@/components/common'
import { platformService } from '@/services/platform'
import { fmtDate, num, peso, timeAgo } from '@/lib/format'
import type { PlatformOrg } from '@/types/db'
import { AccountStatusPill, CreateAccountDialog, LendingModePill } from './dialogs'

export default function AccountsPage() {
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState<'all' | 'active' | 'suspended'>('all')
  const [creating, setCreating] = useState(params.get('new') === '1')
  const q = useQuery({ queryKey: ['platform', 'organizations'], queryFn: platformService.listOrganizations })

  const rows = useMemo(() => {
    const term = search.trim().toLowerCase()
    return (q.data ?? []).filter((o) => (status === 'all' || o.status === status) && (!term || [
      o.name, o.legal_name, o.city, o.province, o.owner?.full_name, o.owner?.email, o.contact_phone,
    ].some((v) => v?.toLowerCase().includes(term))))
  }, [q.data, search, status])

  const columns: Column<PlatformOrg>[] = [
    {
      key: 'name', header: 'Business', mobile: 'title',
      cell: (o) => (
        <div className="min-w-0">
          <p className="truncate font-medium">{o.name}</p>
          <p className="truncate text-xs text-muted-foreground">{[o.city, o.province].filter(Boolean).join(', ') || o.legal_name || '—'}</p>
        </div>
      ),
    },
    {
      key: 'owner', header: 'Owner', mobile: 'subtitle',
      cell: (o) => o.owner ? (
        <div className="min-w-0">
          <p className="truncate text-sm">{o.owner.full_name}</p>
          <p className="truncate text-xs text-muted-foreground">{o.owner.email}</p>
        </div>
      ) : <span className="text-sm text-muted-foreground">No owner</span>,
    },
    { key: 'status', header: 'Status', mobile: 'meta', cell: (o) => <AccountStatusPill status={o.status} /> },
    { key: 'mode', header: 'Lending mode', mobile: 'meta', cell: (o) => <LendingModePill mode={o.lending_mode ?? 'complete'} /> },
    { key: 'users', header: 'Users', align: 'right', mobile: false, cell: (o) => num(o.stats.users) },
    { key: 'loans', header: 'Active loans', align: 'right', mobile: 'meta', cell: (o) => <span>{num(o.stats.active_loans)}<span className="md:hidden"> active loans</span></span> },
    { key: 'outstanding', header: 'Outstanding', align: 'right', mobile: 'value', cell: (o) => peso(o.stats.outstanding, { compact: true }) },
    { key: 'activity', header: 'Last sign-in', mobile: false, cell: (o) => <span className="text-sm text-muted-foreground">{o.stats.last_activity_at ? timeAgo(o.stats.last_activity_at) : 'Never'}</span> },
    { key: 'created', header: 'Created', mobile: false, cell: (o) => <span className="text-sm text-muted-foreground">{fmtDate(o.created_at)}</span> },
  ]

  const closeCreate = () => {
    setCreating(false)
    if (params.has('new')) setParams({}, { replace: true })
  }

  return (
    <div>
      <PageHeader title="Lender accounts" description="Each account is a separate lending business with its own private data, staff and settings."
        actions={<Button onClick={() => setCreating(true)}><Plus /> Create account</Button>} />
      <Panel bodyClassName="p-3 sm:p-4">
        <div className="mb-3 flex flex-col gap-2 sm:flex-row">
          <SearchInput value={search} onChange={setSearch} placeholder="Search business, owner, email, city…" className="sm:max-w-sm" />
          <NativeSelect value={status} onChange={(e) => setStatus(e.target.value as typeof status)} className="sm:w-44" aria-label="Filter by status">
            <option value="all">All statuses</option>
            <option value="active">Active</option>
            <option value="suspended">Suspended</option>
          </NativeSelect>
        </div>
        {q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : (
          <DataTable columns={columns} rows={rows} loading={q.isLoading} rowKey={(o) => o.id}
            onRowClick={(o) => navigate(`/admin/accounts/${o.id}`)}
            empty={q.data?.length ? <EmptyState title="No matching accounts" description="Try a different search or status." /> : (
              <EmptyState icon={<Building2 />} title="No lender accounts yet"
                description="Create the first account for a lending business. Its owner signs in with the email and temporary password you set."
                action={<Button onClick={() => setCreating(true)}><Plus /> Create account</Button>} />
            )} />
        )}
      </Panel>
      {creating && (
        <CreateAccountDialog onClose={closeCreate} onCreated={(id, c) => {
          closeCreate()
          navigate(`/admin/accounts/${id}`, { state: { creds: c } })
        }} />
      )}
    </div>
  )
}
