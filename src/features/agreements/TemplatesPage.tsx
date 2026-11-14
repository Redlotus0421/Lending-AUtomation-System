import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Copy, LayoutTemplate, MoreHorizontal, Pencil, Plus, Power, Star } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { EmptyState, ErrorState, PageHeader, PageLoader, Pill } from '@/components/common'
import { templatesService } from '@/services/agreements'
import { errorMessage } from '@/lib/errors'
import { fmtDateTime } from '@/lib/format'

export default function TemplatesPage() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['templates'], queryFn: templatesService.list })
  const done = (msg: string) => { toast.success(msg); void qc.invalidateQueries({ queryKey: ['templates'] }) }
  const dup = useMutation({ mutationFn: (id: string) => templatesService.duplicate(id), onSuccess: (r) => { done('Template duplicated'); navigate(`/agreements/templates/${r.id}`) }, onError: (e) => toast.error(errorMessage(e)) })
  const def = useMutation({ mutationFn: (id: string) => templatesService.setDefault(id), onSuccess: () => done('Default template updated'), onError: (e) => toast.error(errorMessage(e)) })
  const act = useMutation({ mutationFn: ({ id, active }: { id: string; active: boolean }) => templatesService.setActive(id, active), onSuccess: () => done('Template updated'), onError: (e) => toast.error(errorMessage(e)) })

  if (q.isLoading) return <PageLoader />
  if (q.error) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />
  return (
    <div>
      <PageHeader title="Agreement templates" description="Configurable, versioned agreement templates with dynamic placeholders. Each generated agreement freezes the template version it used."
        actions={<Button onClick={() => navigate('/agreements/templates/new')}><Plus /> New template</Button>} />
      {!q.data?.length ? (
        <EmptyState icon={<LayoutTemplate />} title="No templates yet" action={<Button onClick={() => navigate('/agreements/templates/new')}><Plus /> Create template</Button>} />
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {q.data.map((t) => (
            <div key={t.id} className="flex flex-col rounded-xl border bg-white p-4 shadow-card">
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <p className="truncate font-semibold">{t.name}</p>
                  <p className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">{t.description ?? t.current?.title}</p>
                </div>
                <DropdownMenu>
                  <DropdownMenuTrigger asChild><Button variant="ghost" size="icon" className="size-8 shrink-0" aria-label="Template actions"><MoreHorizontal /></Button></DropdownMenuTrigger>
                  <DropdownMenuContent align="end">
                    <DropdownMenuItem onClick={() => navigate(`/agreements/templates/${t.id}`)}><Pencil /> Edit</DropdownMenuItem>
                    <DropdownMenuItem onClick={() => dup.mutate(t.id)}><Copy /> Duplicate</DropdownMenuItem>
                    {!t.is_default && t.is_active && <DropdownMenuItem onClick={() => def.mutate(t.id)}><Star /> Set as default</DropdownMenuItem>}
                    {!t.is_default && <DropdownMenuItem onClick={() => act.mutate({ id: t.id, active: !t.is_active })}><Power /> {t.is_active ? 'Deactivate' : 'Activate'}</DropdownMenuItem>}
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>
              <div className="mt-3 flex flex-wrap gap-1.5">
                {t.is_default && <Pill tone="blue">Default</Pill>}
                <Pill tone={t.is_active ? 'green' : 'slate'}>{t.is_active ? 'Active' : 'Inactive'}</Pill>
                <Pill tone="slate" dot={false}>v{t.current?.version_no ?? '—'}</Pill>
              </div>
              <div className="mt-auto flex items-center justify-between pt-4 text-xs text-muted-foreground">
                <span>Updated {fmtDateTime(t.current?.created_at ?? t.updated_at)}</span>
                <Button size="sm" variant="outline" onClick={() => navigate(`/agreements/templates/${t.id}`)}>Open</Button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
