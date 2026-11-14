import { useMemo, useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { ScrollText } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import {
  DataTable, EmptyState, ErrorState, Field, NativeSelect, PageHeader, Pagination, Panel, Pill, SearchInput, useDebounced, type Column,
} from '@/components/common'
import { auditService } from '@/services/org'
import { fmtDateTime, titleCase } from '@/lib/format'
import type { AuditLog } from '@/types/db'

const PAGE_SIZE = 50

const TABLES: { value: string; label: string }[] = [
  { value: 'loans', label: 'Loans' },
  { value: 'loan_payments', label: 'Payments' },
  { value: 'payment_receipts', label: 'Receipts' },
  { value: 'loan_agreements', label: 'Agreements' },
  { value: 'agreement_signatures', label: 'Signatures' },
  { value: 'agreement_signing_sessions', label: 'Signing links' },
  { value: 'borrowers', label: 'Borrowers' },
  { value: 'borrower_identities', label: 'Borrower IDs' },
  { value: 'borrower_documents', label: 'Borrower documents' },
  { value: 'guarantors', label: 'Guarantors' },
  { value: 'loan_charges', label: 'Loan charges' },
  { value: 'collector_assignments', label: 'Collector assignments' },
  { value: 'loan_products', label: 'Loan products' },
  { value: 'loan_agreement_templates', label: 'Agreement templates' },
  { value: 'loan_agreement_template_versions', label: 'Template versions' },
  { value: 'system_settings', label: 'Settings' },
  { value: 'organizations', label: 'Organization' },
  { value: 'user_profiles', label: 'User profiles' },
  { value: 'user_roles', label: 'User roles' },
  { value: 'role_permissions', label: 'Role permissions' },
]
const tableLabel = (t: string) => TABLES.find((x) => x.value === t)?.label ?? titleCase(t)
const ACTION_TONE: Record<string, 'green' | 'blue' | 'red' | 'slate'> = { insert: 'green', update: 'blue', delete: 'red' }
const ACTION_LABEL: Record<string, string> = { insert: 'Created', update: 'Updated', delete: 'Deleted' }

function recordLabel(l: AuditLog) {
  const d = l.new_data ?? l.old_data ?? {}
  return d.loan_number || d.payment_number || d.receipt_number || d.agreement_number || d.borrower_code || d.full_name || d.name || d.code ||
    (d.signer_role ? `${titleCase(d.signer_role)} signature` : null) || (l.record_id ? `${l.record_id.slice(0, 8)}…` : '—')
}

function fmtValue(v: unknown): string {
  if (v === null || v === undefined) return '∅'
  if (typeof v === 'object') return JSON.stringify(v, null, 2)
  return String(v)
}

export default function AuditLogsPage() {
  const [table, setTable] = useState('')
  const [action, setAction] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<AuditLog | null>(null)
  const debounced = useDebounced(search)

  const q = useQuery({
    queryKey: ['audit', { table, action, from, to, search: debounced, page }],
    queryFn: () => auditService.list({ table, action, from, to, search: debounced, page, pageSize: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  })

  const reset = () => { setTable(''); setAction(''); setFrom(''); setTo(''); setSearch(''); setPage(0) }

  const columns: Column<AuditLog>[] = [
    { key: 'when', header: 'When', mobile: 'subtitle', cell: (l) => <span className="whitespace-nowrap text-sm">{fmtDateTime(l.created_at)}</span> },
    { key: 'actor', header: 'User', mobile: 'meta', cell: (l) => <span className="text-sm">{l.actor_name || (l.actor_id ? 'Staff' : 'System / signer')}</span> },
    { key: 'action', header: 'Action', mobile: 'meta', cell: (l) => <Pill tone={ACTION_TONE[l.action] ?? 'slate'}>{ACTION_LABEL[l.action] ?? titleCase(l.action)}</Pill> },
    { key: 'table', header: 'Record type', mobile: 'title', cell: (l) => <span className="font-medium">{tableLabel(l.table_name)}</span> },
    { key: 'record', header: 'Record', mobile: 'meta', cell: (l) => <span className="font-mono text-xs">{recordLabel(l)}</span> },
    {
      key: 'fields', header: 'Changed fields', mobile: false,
      cell: (l) => l.changed_fields?.length ? (
        <span className="line-clamp-2 max-w-xs text-xs text-muted-foreground">{l.changed_fields.slice(0, 6).join(', ')}{l.changed_fields.length > 6 ? ` +${l.changed_fields.length - 6}` : ''}</span>
      ) : <span className="text-xs text-muted-foreground">—</span>,
    },
  ]

  return (
    <div>
      <PageHeader title="Audit logs" description="Immutable record of changes to borrowers, loans, payments, documents and settings." />
      <Panel bodyClassName="p-3 sm:p-4">
        <div className="mb-4 grid gap-2 sm:grid-cols-2 lg:grid-cols-[1.4fr_1fr_1fr_150px_150px_auto]">
          <SearchInput value={search} onChange={(v) => { setSearch(v); setPage(0) }} placeholder="Search user or record ID" />
          <NativeSelect value={table} onChange={(e) => { setTable(e.target.value); setPage(0) }} aria-label="Record type">
            <option value="">All record types</option>
            {TABLES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
          </NativeSelect>
          <NativeSelect value={action} onChange={(e) => { setAction(e.target.value); setPage(0) }} aria-label="Action">
            <option value="">All actions</option>
            <option value="insert">Created</option>
            <option value="update">Updated</option>
            <option value="delete">Deleted</option>
          </NativeSelect>
          <Input type="date" className="h-10" value={from} onChange={(e) => { setFrom(e.target.value); setPage(0) }} aria-label="From date" />
          <Input type="date" className="h-10" value={to} onChange={(e) => { setTo(e.target.value); setPage(0) }} aria-label="To date" />
          <Button variant="ghost" onClick={reset} className="h-10">Reset</Button>
        </div>
        {q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : (
          <>
            <DataTable columns={columns} rows={q.data?.rows} loading={q.isLoading} rowKey={(l) => String(l.id)} onRowClick={setSelected} dense
              empty={<EmptyState icon={<ScrollText />} title="No audit entries" description="Try widening the date range or clearing filters." />} />
            <Pagination page={page} pageSize={PAGE_SIZE} count={q.data?.count ?? 0} onPage={setPage} />
          </>
        )}
      </Panel>
      <Sheet open={!!selected} onOpenChange={(o) => !o && setSelected(null)}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-xl">
          {selected && <AuditDetail log={selected} />}
        </SheetContent>
      </Sheet>
    </div>
  )
}

function AuditDetail({ log }: { log: AuditLog }) {
  const rows = useMemo(() => {
    const oldD = log.old_data ?? {}
    const newD = log.new_data ?? {}
    const keys = log.action === 'update'
      ? (log.changed_fields ?? [])
      : Object.keys(log.action === 'delete' ? oldD : newD).filter((k) => !['organization_id'].includes(k))
    return keys.map((k) => ({ key: k, old: oldD[k], next: newD[k] }))
  }, [log])
  return (
    <>
      <SheetHeader>
        <SheetTitle>{ACTION_LABEL[log.action] ?? titleCase(log.action)} · {tableLabel(log.table_name)}</SheetTitle>
        <SheetDescription>{fmtDateTime(log.created_at)} by {log.actor_name || (log.actor_id ? 'staff user' : 'system / external signer')}</SheetDescription>
      </SheetHeader>
      <div className="space-y-4 px-4 pb-8">
        <div className="grid grid-cols-2 gap-3 rounded-lg bg-slate-50 p-3 text-xs">
          <Field label="Record"><span className="font-mono">{recordLabel(log)}</span></Field>
          <Field label="Record ID"><span className="break-all font-mono">{log.record_id ?? '—'}</span></Field>
        </div>
        {rows.length === 0 ? <p className="text-sm text-muted-foreground">No field details recorded.</p> : (
          <div className="overflow-hidden rounded-lg border">
            <table className="w-full table-fixed text-xs">
              <thead className="bg-slate-50 text-left text-muted-foreground">
                <tr>
                  <th className="w-1/4 px-3 py-2 font-medium">Field</th>
                  {log.action !== 'insert' && <th className="px-3 py-2 font-medium">Before</th>}
                  {log.action !== 'delete' && <th className="px-3 py-2 font-medium">After</th>}
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.key} className="border-t align-top">
                    <td className="break-words px-3 py-2 font-mono font-medium">{r.key}</td>
                    {log.action !== 'insert' && <td className="px-3 py-2"><pre className="whitespace-pre-wrap break-words rounded bg-red-50 px-1.5 py-0.5 font-mono text-red-800">{fmtValue(r.old)}</pre></td>}
                    {log.action !== 'delete' && <td className="px-3 py-2"><pre className="whitespace-pre-wrap break-words rounded bg-green-50 px-1.5 py-0.5 font-mono text-green-800">{fmtValue(r.next)}</pre></td>}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  )
}
