import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ArrowLeft, Braces, Eye, History, Save, Sparkles, Star, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Switch } from '@/components/ui/switch'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { ErrorState, Field, PageHeader, PageLoader, Panel, Pill, Spinner } from '@/components/common'
import { useAuth } from '@/features/auth/AuthProvider'
import { templatesService } from '@/services/agreements'
import { errorMessage } from '@/lib/errors'
import { fmtDate, fmtDateTime, num } from '@/lib/format'
import type { AgreementTemplateVersion } from '@/types/db'
import { AiImportDialog, type AiDraft } from './AiImportDialog'
import {
  buildPlaceholderValues, disclosureRows, fillPlaceholders, parseTemplate, PLACEHOLDERS, sampleRenderData, type Run,
} from '../../../supabase/functions/_shared/template.ts'

const STARTER = `This LOAN AGREEMENT is made on **{{agreement_date}}** between **{{lender_name}}** ("LENDER") and **{{borrower_name}}** ("BORROWER"), residing at {{borrower_address}}.

## 1. The Loan

The LENDER lends the BORROWER **{{loan_amount_words}} ({{loan_amount}})** at **{{interest_rate}}** ({{interest_method}}), payable in {{installment_count}} {{payment_frequency}} installments of {{installment_amount}}.

## 2. Disclosure Statement

{{disclosure_table}}

## 3. Repayment Schedule

{{repayment_schedule}}

## 4. Late Payment

{{penalty_terms}}

{{signature_block}}`

export default function TemplateEditorPage() {
  const { id } = useParams()
  return <TemplateEditor key={id} id={id ?? 'new'} />
}

/**
 * Agreement template editor with live preview. Used as its own page and embedded in
 * Settings -> Terms template (`embedded`), where saving a new template calls `onSaved`.
 */
export function TemplateEditor({ id, embedded = false, onSaved }: { id: string; embedded?: boolean; onSaved?: (id: string) => void }) {
  const isNew = id === 'new'
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { organization } = useAuth()
  const q = useQuery({ queryKey: ['template', id], queryFn: () => templatesService.get(id!), enabled: !isNew })
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [title, setTitle] = useState('Loan Agreement')
  const [body, setBody] = useState(STARTER)
  const [settings, setSettings] = useState<Record<string, any>>({ show_logo: true, footer_text: 'This document is electronically generated and signed.', schedule_font_size: 7.5 })
  const [note, setNote] = useState('')
  const [viewing, setViewing] = useState<AgreementTemplateVersion | null>(null)
  const textarea = useRef<HTMLTextAreaElement>(null)
  const [aiOpen, setAiOpen] = useState(false)
  const [aiDraft, setAiDraft] = useState<(AiDraft & { file: string }) | null>(null)
  const applyAi = (d: AiDraft, file: string) => {
    setTitle(d.title)
    setBody(d.body)
    if (isNew || !name.trim()) setName(d.suggested_name)
    setNote(`Imported from ${file} with the built-in assistant`)
    setAiDraft({ ...d, file })
    toast.success('Draft ready — check the preview, then save')
  }

  useEffect(() => {
    if (!q.data) return
    const cur = q.data.versions.find((v) => v.id === q.data!.template.current_version_id) ?? q.data.versions[0]
    setName(q.data.template.name)
    setDescription(q.data.template.description ?? '')
    if (cur) {
      setTitle(cur.title)
      setBody(cur.body)
      setSettings({ show_logo: true, schedule_font_size: 7.5, ...cur.settings })
    }
  }, [q.data])

  const save = useMutation({
    mutationFn: (asDefault: boolean) => templatesService.save({
      id: isNew ? undefined : id, name, description, title, body, settings, change_note: note || undefined, is_default: asDefault || undefined,
    }),
    onSuccess: (r) => {
      toast.success(`Saved as version ${r.version_no}`)
      setNote('')
      void qc.invalidateQueries({ queryKey: ['templates'] })
      void qc.invalidateQueries({ queryKey: ['template', r.id] })
      setAiDraft(null)
      if (isNew) {
        if (onSaved) onSaved(r.id)
        else navigate(`/agreements/templates/${r.id}`, { replace: true })
      }
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  const insert = (key: string) => {
    const el = textarea.current
    const token = ['repayment_schedule', 'disclosure_table', 'signature_block'].includes(key) ? `\n\n{{${key}}}\n\n` : `{{${key}}}`
    if (!el) return setBody((b) => b + token)
    const start = el.selectionStart
    const end = el.selectionEnd
    const next = body.slice(0, start) + token + body.slice(end)
    setBody(next)
    requestAnimationFrame(() => {
      el.focus()
      el.setSelectionRange(start + token.length, start + token.length)
    })
  }

  const dirty = q.data ? (() => {
    const cur = q.data.versions.find((v) => v.id === q.data!.template.current_version_id)
    return !cur || cur.body !== body || cur.title !== title || JSON.stringify(cur.settings) !== JSON.stringify(settings) || q.data.template.name !== name || (q.data.template.description ?? '') !== description
  })() : true

  if (!isNew && q.isLoading) return <PageLoader />
  if (!isNew && q.error) return <ErrorState error={q.error} />

  const groups = PLACEHOLDERS.reduce<Record<string, typeof PLACEHOLDERS>>((a, p) => ((a[p.group] ??= []).push(p), a), {})

  const actions = (
    <>
      {!isNew && q.data?.template.is_default && <Pill tone="blue">Default</Pill>}
      {!q.data?.template.is_default && <Button variant="outline" onClick={() => save.mutate(true)} disabled={save.isPending || !name.trim() || !title.trim() || !body.trim()}><Star /> Save & set default</Button>}
      <Button onClick={() => save.mutate(false)} disabled={save.isPending || !name.trim() || !title.trim() || !body.trim() || (!isNew && !dirty)}>
        {save.isPending ? <Spinner /> : <Save />} Save {isNew ? 'template' : 'new version'}
      </Button>
    </>
  )

  return (
    <div>
      {embedded ? (
        <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
          <p className="text-xs text-muted-foreground">Saving creates a new version. Agreements already generated keep the version they were created from.</p>
          <div className="flex flex-wrap items-center gap-2">{actions}</div>
        </div>
      ) : (
        <PageHeader
          back={<button onClick={() => navigate('/agreements/templates')} className="mb-2 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"><ArrowLeft className="size-3.5" /> Templates</button>}
          title={isNew ? 'New agreement template' : name || 'Template'}
          description="Saving creates a new immutable version. Agreements already generated keep the version they were created from."
          actions={actions}
        />
      )}
      <div className="mb-5 flex flex-col gap-3 rounded-xl border border-violet-200 bg-gradient-to-r from-violet-50 to-blue-50 p-4 sm:flex-row sm:items-center">
        <span className="grid size-10 shrink-0 place-items-center rounded-lg bg-white text-violet-600 shadow-sm"><Sparkles className="size-5" /></span>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold">Already have your own terms?</p>
          <p className="text-xs text-muted-foreground">Upload your loan agreement (PDF, Word or text). The built-in assistant turns it into a template with the right fields filled in for every loan — on this device, no internet AI.</p>
        </div>
        <Button variant="outline" className="h-11 shrink-0 bg-white" onClick={() => setAiOpen(true)}><Sparkles /> Upload terms</Button>
      </div>
      {aiDraft && (
        <div className="mb-5 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950">
          <div className="flex items-start justify-between gap-2">
            <p className="font-semibold">Draft from {aiDraft.file} — not saved yet</p>
            <button onClick={() => setAiDraft(null)} aria-label="Dismiss" className="rounded p-1 hover:bg-amber-100"><X className="size-4" /></button>
          </div>
          <p className="mt-1 text-xs">Read the preview carefully before saving. The assistant can make mistakes.</p>
          {aiDraft.notes.length > 0 && (
            <ul className="mt-2 list-disc space-y-1 pl-5 text-xs">{aiDraft.notes.map((n, i) => <li key={i}>{n}</li>)}</ul>
          )}
        </div>
      )}
      <AiImportDialog open={aiOpen} onOpenChange={setAiOpen} onDraft={applyAi} />
      <div className="grid gap-5 xl:grid-cols-2">
        <div className="space-y-5">
          <Panel title="Template details">
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Template name" required><Input value={name} onChange={(e) => setName(e.target.value)} className="h-10" placeholder="e.g. Standard Loan Agreement" /></Field>
              <Field label="Agreement title (printed)" required><Input value={title} onChange={(e) => setTitle(e.target.value)} className="h-10" /></Field>
              <Field label="Description" className="sm:col-span-2"><Input value={description} onChange={(e) => setDescription(e.target.value)} className="h-10" /></Field>
              <label className="flex items-center justify-between gap-3 rounded-lg border p-3 text-sm sm:col-span-1">
                <span>Show company logo</span>
                <Switch checked={settings.show_logo !== false} onCheckedChange={(c) => setSettings((s) => ({ ...s, show_logo: c }))} />
              </label>
              <Field label="Schedule table font size">
                <Input type="number" step="0.5" min={6} max={10} value={settings.schedule_font_size ?? 7.5} onChange={(e) => setSettings((s) => ({ ...s, schedule_font_size: Number(e.target.value) }))} className="h-10" />
              </Field>
              <Field label="Footer text" className="sm:col-span-2"><Input value={settings.footer_text ?? ''} onChange={(e) => setSettings((s) => ({ ...s, footer_text: e.target.value }))} className="h-10" /></Field>
            </div>
          </Panel>
          <Panel title="Agreement body" description="Use ## for section headings, **bold**, numbered lists (1.) and bullets (-). Click a placeholder to insert it at the cursor.">
            <div className="mb-3 space-y-2">
              {Object.entries(groups).map(([g, items]) => (
                <div key={g} className="flex flex-wrap items-center gap-1.5">
                  <span className="w-20 shrink-0 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{g}</span>
                  {items.map((p) => (
                    <button key={p.key} type="button" onClick={() => insert(p.key)} title={p.label}
                      className="inline-flex items-center gap-1 rounded-md border bg-slate-50 px-1.5 py-0.5 font-mono text-[11px] text-slate-700 hover:border-blue-300 hover:bg-blue-50">
                      <Braces className="size-3 text-blue-500" />{p.key}
                    </button>
                  ))}
                </div>
              ))}
            </div>
            <Textarea ref={textarea} value={body} onChange={(e) => setBody(e.target.value)} rows={28} className="font-mono text-[13px] leading-relaxed" spellCheck />
            {!isNew && <Field label="Change note for this version" className="mt-3"><Input value={note} onChange={(e) => setNote(e.target.value)} className="h-10" placeholder="What changed and why" /></Field>}
          </Panel>
        </div>

        <div className="space-y-5 xl:sticky xl:top-20 xl:self-start">
          <Tabs defaultValue="preview">
            <TabsList><TabsTrigger value="preview"><Eye /> Preview</TabsTrigger>{!isNew && <TabsTrigger value="versions"><History /> Versions ({q.data?.versions.length ?? 0})</TabsTrigger>}</TabsList>
            <TabsContent value="preview" className="mt-3">
              <TemplatePreview title={title} body={body} org={organization ?? undefined} />
            </TabsContent>
            {!isNew && (
              <TabsContent value="versions" className="mt-3">
                <Panel>
                  <ul className="space-y-2">
                    {q.data?.versions.map((v) => (
                      <li key={v.id} className="flex items-center justify-between gap-2 rounded-lg border p-3">
                        <div className="min-w-0">
                          <p className="text-sm font-medium">Version {v.version_no} {v.id === q.data?.template.current_version_id && <Pill tone="green" className="ml-1">Current</Pill>}</p>
                          <p className="truncate text-xs text-muted-foreground">{fmtDateTime(v.created_at)}{v.change_note ? ` · ${v.change_note}` : ''}</p>
                        </div>
                        <div className="flex shrink-0 gap-1">
                          <Button size="sm" variant="ghost" onClick={() => setViewing(v)}>View</Button>
                          {v.id !== q.data?.template.current_version_id && (
                            <Button size="sm" variant="outline" onClick={() => { setTitle(v.title); setBody(v.body); setSettings(v.settings); setNote(`Restored from version ${v.version_no}`); toast.info(`Loaded version ${v.version_no} into the editor — save to make it current`) }}>Restore</Button>
                          )}
                        </div>
                      </li>
                    ))}
                  </ul>
                </Panel>
                {viewing && (
                  <div className="mt-3">
                    <p className="mb-2 text-xs text-muted-foreground">Viewing version {viewing.version_no} (read-only)</p>
                    <TemplatePreview title={viewing.title} body={viewing.body} org={organization ?? undefined} />
                  </div>
                )}
              </TabsContent>
            )}
          </Tabs>
        </div>
      </div>
    </div>
  )
}

function RunsView({ runs }: { runs: Run[] }) {
  return <>{runs.map((r, i) => {
    const parts = r.text.split(/(\{\{[a-z0-9_]+\}\})/gi)
    const content: ReactNode[] = parts.map((p, j) => /^\{\{/.test(p) ? <span key={j} className="ph-missing">{p}</span> : p)
    return r.bold ? <strong key={i}>{content}</strong> : <span key={i}>{content}</span>
  })}</>
}

/** HTML preview of a template filled with sample data (the PDF uses the same parser on the server). */
export function TemplatePreview({ title, body, org }: { title: string; body: string; org?: Record<string, any> }) {
  const data = useMemo(() => sampleRenderData(org), [org])
  const { blocks, missing } = useMemo(() => {
    const values = buildPlaceholderValues(data)
    const filled = fillPlaceholders(body, values)
    return { blocks: parseTemplate(filled.text), missing: filled.missing }
  }, [body, data])
  return (
    <div className="space-y-2">
      {missing.length > 0 && <p className="rounded-lg bg-amber-50 p-2 text-xs text-amber-900">Unknown placeholders: {missing.map((m) => `{{${m}}}`).join(', ')}</p>}
      <div className="legal-doc max-h-[78vh] overflow-auto rounded-xl border bg-white p-6 shadow-card sm:p-10">
        <p className="text-center font-sans text-sm font-semibold text-[#142B4A]">{data.organization.legal_name || data.organization.name}</p>
        <p className="mb-2 text-center font-sans text-[11px] text-slate-500">{[data.organization.address, data.organization.city, data.organization.province].filter(Boolean).join(', ')}</p>
        <hr />
        <h1>{title}</h1>
        <p className="!text-center font-sans text-[11px] text-slate-500">Agreement No. {data.agreement.agreement_number} · Loan Ref. {data.loan.loan_number} · Version 1</p>
        {blocks.map((b, i) => {
          switch (b.type) {
            case 'heading': return b.level === 1 ? <h2 key={i}><RunsView runs={b.runs} /></h2> : b.level === 2 ? <h2 key={i}><RunsView runs={b.runs} /></h2> : <h3 key={i}><RunsView runs={b.runs} /></h3>
            case 'paragraph': return <p key={i}><RunsView runs={b.runs} /></p>
            case 'list': return b.ordered
              ? <ol key={i}>{b.items.map((it, j) => <li key={j}><RunsView runs={it} /></li>)}</ol>
              : <ul key={i}>{b.items.map((it, j) => <li key={j}><RunsView runs={it} /></li>)}</ul>
            case 'rule': return <hr key={i} />
            case 'disclosure': return (
              <table key={i}><tbody>{disclosureRows(data).map(([k, v]) => <tr key={k}><td style={{ textAlign: 'left' }}>{k}</td><td style={{ textAlign: 'left' }}>{v}</td></tr>)}</tbody></table>
            )
            case 'schedule': return (
              <table key={i}>
                <thead><tr><th>#</th><th style={{ textAlign: 'left' }}>Due date</th><th>Principal</th><th>Interest</th><th>Total</th><th>Balance</th></tr></thead>
                <tbody>{data.schedule.map((r) => (
                  <tr key={r.installment_no}><td style={{ textAlign: 'center' }}>{r.installment_no}</td><td style={{ textAlign: 'left' }}>{fmtDate(r.due_date)}</td>
                    <td>{num(r.principal, 2)}</td><td>{num(r.interest, 2)}</td><td>{num(r.total, 2)}</td><td>{num(r.closing_balance, 2)}</td></tr>
                ))}</tbody>
              </table>
            )
            case 'signatures': return (
              <div key={i} className="mt-8 grid grid-cols-2 gap-8 font-sans text-xs">
                {[
                  { label: 'BORROWER', name: data.borrower.full_name, caption: 'Signature over printed name' },
                  ...(data.co_maker ? [{ label: 'CO-MAKER', name: data.co_maker.full_name, caption: 'Signature over printed name' }] : []),
                  { label: 'LENDER', name: data.officer.full_name ?? '', caption: 'Authorized representative' },
                ].map((r) => (
                  <div key={r.label}>
                    <p className="font-semibold text-[#142B4A]">{r.label}</p>
                    <div className="mt-10 border-b border-slate-800" />
                    <p className="mt-1 font-serif text-sm font-semibold">{r.name}</p>
                    <p className="text-slate-500">{r.caption}</p>
                  </div>
                ))}
              </div>
            )
          }
        })}
      </div>
    </div>
  )
}
