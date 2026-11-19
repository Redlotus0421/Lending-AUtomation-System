// Platform admin: agreement / terms templates of one lender account. The list sits on the
// account page; editing opens the same template editor lenders use, saving into that account.
import { useMemo } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ArrowLeft, FilePlus2, Pencil, Power, Star } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EmptyState, ErrorState, PageHeader, PageLoader, Panel, Pill, Spinner } from '@/components/common'
import { platformService } from '@/services/platform'
import { errorMessage } from '@/lib/errors'
import { fmtDate } from '@/lib/format'
import { TemplateEditor, type TemplateSource } from '@/features/agreements/TemplateEditorPage'
import type { PlatformOrgDetail } from '@/types/db'

const listKey = (orgId: string) => ['platform', 'templates', orgId] as const

/** Templates panel on the account page. */
export function AccountTemplatesPanel({ org }: { org: PlatformOrgDetail }) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const q = useQuery({ queryKey: listKey(org.id), queryFn: () => platformService.listTemplates(org.id) })
  const refresh = () => void qc.invalidateQueries({ queryKey: listKey(org.id) })
  const setDefault = useMutation({
    mutationFn: (id: string) => platformService.setDefaultTemplate(org.id, id),
    onSuccess: () => { toast.success('Default template changed'); refresh() },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const setActive = useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) => platformService.setTemplateActive(org.id, id, active),
    onSuccess: (_, v) => { toast.success(v.active ? 'Template activated' : 'Template deactivated'); refresh() },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const busy = setDefault.isPending || setActive.isPending

  return (
    <Panel title="Agreement / terms templates"
      description={`The terms printed on ${org.name}'s loan agreements. You and the lender can both create and edit them; the default is used for new agreements.`}
      actions={<Button size="sm" onClick={() => navigate(`/admin/accounts/${org.id}/templates/new`)}><FilePlus2 /> New template</Button>}>
      {q.isLoading ? <div className="py-6 text-center"><Spinner /></div>
        : q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        : !q.data?.length ? <EmptyState title="No templates yet" description="Create the first terms template for this lender." />
        : (
          <ul className="divide-y rounded-lg border">
            {q.data.map((t) => (
              <li key={t.id} className="flex flex-col gap-2 p-3 sm:flex-row sm:items-center">
                <div className="min-w-0 flex-1">
                  <p className="flex flex-wrap items-center gap-2 text-sm font-medium">
                    {t.name}
                    {t.is_default && <Pill tone="blue">Default</Pill>}
                    {!t.is_active && <Pill tone="slate">Inactive</Pill>}
                  </p>
                  <p className="truncate text-xs text-muted-foreground">
                    {t.current ? `${t.current.title} · version ${t.current.version_no} · updated ${fmtDate(t.current.created_at)}` : 'No saved version'}
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  <Button size="sm" variant="outline" onClick={() => navigate(`/admin/accounts/${org.id}/templates/${t.id}`)}><Pencil /> Edit</Button>
                  {!t.is_default && t.is_active && (
                    <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDefault.mutate(t.id)}><Star /> Make default</Button>
                  )}
                  {!t.is_default && (
                    <Button size="sm" variant="ghost" disabled={busy} onClick={() => setActive.mutate({ id: t.id, active: !t.is_active })}>
                      <Power /> {t.is_active ? 'Deactivate' : 'Activate'}
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
    </Panel>
  )
}

/** /admin/accounts/:id/templates/:templateId — edit or create a template for that lender. */
export default function AccountTemplatePage() {
  const { id: orgId = '', templateId = 'new' } = useParams()
  const navigate = useNavigate()
  const org = useQuery({ queryKey: ['platform', 'org', orgId], queryFn: () => platformService.getOrganization(orgId) })
  const source: TemplateSource = useMemo(() => ({
    key: listKey(orgId),
    get: (id) => platformService.getTemplate(orgId, id),
    save: (p) => platformService.saveTemplate(orgId, p as Record<string, unknown>),
    organization: org.data ?? null,
  }), [orgId, org.data])

  if (org.isLoading) return <PageLoader />
  if (org.error || !org.data) return <ErrorState error={org.error ?? 'Account not found'} />
  return (
    <div>
      <PageHeader
        back={<Link to={`/admin/accounts/${orgId}`} className="mb-2 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"><ArrowLeft className="size-4" /> {org.data.name}</Link>}
        title={templateId === 'new' ? 'New terms template' : 'Edit terms template'}
        description={`For ${org.data.name}. Saved templates are available to the lender right away.`}
      />
      <TemplateEditor key={templateId} id={templateId} embedded source={source}
        onSaved={(id) => navigate(`/admin/accounts/${orgId}/templates/${id}`, { replace: true })} />
    </div>
  )
}
