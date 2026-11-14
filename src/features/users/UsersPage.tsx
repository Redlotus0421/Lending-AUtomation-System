import { useEffect, useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { KeyRound, MoreHorizontal, Power, RotateCcw, ShieldCheck, UserCog, UserPlus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { PasswordInput } from '@/components/ui/password-input'
import { Checkbox } from '@/components/ui/checkbox'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { DataTable, EmptyState, ErrorState, Field, NativeSelect, PageHeader, Panel, Pill, Spinner, TableSkeleton, type Column } from '@/components/common'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { useAuth } from '@/features/auth/AuthProvider'
import { usersService, type StaffMember } from '@/services/org'
import { PERMISSIONS, ROLE_LABEL } from '@/lib/constants'
import { fmtDate, initials, normalizePhMobile, timeAgo } from '@/lib/format'
import { errorMessage } from '@/lib/errors'
import type { AppRole } from '@/types/db'

const ROLES: AppRole[] = ['owner', 'admin', 'loan_officer', 'collector']
const ROLE_TONE: Record<string, 'navy' | 'blue' | 'teal' | 'amber' | 'slate'> = { owner: 'navy', admin: 'blue', loan_officer: 'teal', collector: 'amber' }
const ROLE_DESC: Record<AppRole, string> = {
  owner: 'Full access, including users, settings, templates and audit logs.',
  admin: 'Manages borrowers, loans, payments and agreements; views reports.',
  loan_officer: 'Registers borrowers, prepares loan applications and agreements.',
  collector: 'Sees assigned borrowers and records payments in the field.',
}

function generatePassword() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%'
  const arr = new Uint32Array(14)
  crypto.getRandomValues(arr)
  return Array.from(arr, (n) => chars[n % chars.length]).join('')
}

export default function UsersPage() {
  const { access, settings } = useAuth()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['users'], queryFn: usersService.list })
  const [adding, setAdding] = useState(false)
  const [roleFor, setRoleFor] = useState<StaffMember | null>(null)
  const [activeFor, setActiveFor] = useState<StaffMember | null>(null)
  const [pwFor, setPwFor] = useState<StaffMember | null>(null)
  const isOwner = access?.role === 'owner'
  const minPw = settings?.security?.password_min_length ?? 10

  const columns: Column<StaffMember>[] = [
    {
      key: 'name', header: 'Name', mobile: 'title',
      cell: (u) => (
        <div className="flex items-center gap-3">
          <span className="hidden size-9 shrink-0 place-items-center rounded-full bg-[#142B4A]/10 text-xs font-semibold text-[#142B4A] md:grid">{initials(u.full_name)}</span>
          <div className="min-w-0">
            <p className="truncate font-medium">{u.full_name || '—'}{u.id === access?.user_id && <span className="ml-1.5 text-xs text-muted-foreground">(you)</span>}</p>
            <p className="truncate text-xs text-muted-foreground">{u.email}</p>
          </div>
        </div>
      ),
    },
    { key: 'role', header: 'Role', mobile: 'meta', cell: (u) => u.role ? <Pill tone={ROLE_TONE[u.role]} dot={false}>{ROLE_LABEL[u.role]}</Pill> : <Pill>No role</Pill> },
    { key: 'phone', header: 'Phone', mobile: 'subtitle', cell: (u) => <span className="text-sm text-muted-foreground">{u.phone || '—'}</span> },
    { key: 'status', header: 'Status', mobile: 'meta', cell: (u) => u.is_active ? <Pill tone="green">Active</Pill> : <Pill tone="slate">Deactivated</Pill> },
    { key: 'seen', header: 'Last active', mobile: false, cell: (u) => <span className="text-sm text-muted-foreground">{u.last_seen_at ? timeAgo(u.last_seen_at) : '—'}</span> },
    { key: 'joined', header: 'Added', mobile: false, cell: (u) => <span className="text-sm text-muted-foreground">{fmtDate(u.created_at)}</span> },
    {
      key: 'actions', header: '', align: 'right', mobile: 'value',
      cell: (u) => u.id === access?.user_id ? null : (
        <DropdownMenu>
          <DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label="User actions" onClick={(e) => e.stopPropagation()}><MoreHorizontal /></Button></DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuLabel className="text-xs">{u.full_name}</DropdownMenuLabel>
            <DropdownMenuItem disabled={u.role === 'owner' && !isOwner} onClick={() => setRoleFor(u)}><UserCog /> Change role</DropdownMenuItem>
            <DropdownMenuItem onClick={() => setPwFor(u)}><KeyRound /> Reset password</DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem variant={u.is_active ? 'destructive' : 'default'} disabled={u.role === 'owner' && !isOwner} onClick={() => setActiveFor(u)}>
              <Power /> {u.is_active ? 'Deactivate' : 'Reactivate'}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      ),
    },
  ]

  const counts = useMemo(() => {
    const c: Record<string, number> = {}
    for (const u of q.data ?? []) if (u.is_active && u.role) c[u.role] = (c[u.role] ?? 0) + 1
    return c
  }, [q.data])

  return (
    <div>
      <PageHeader title="Users & staff" description="Create staff accounts, assign roles and control what each role can do."
        actions={<Button onClick={() => setAdding(true)}><UserPlus /> Add staff</Button>} />
      <Tabs defaultValue="staff">
        <TabsList>
          <TabsTrigger value="staff">Staff</TabsTrigger>
          <TabsTrigger value="permissions">Roles & permissions</TabsTrigger>
        </TabsList>
        <TabsContent value="staff" className="mt-4 space-y-4">
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            {ROLES.map((r) => (
              <div key={r} className="rounded-xl border bg-card p-3 shadow-card">
                <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{ROLE_LABEL[r]}</p>
                <p className="tabular mt-1 text-xl font-semibold">{counts[r] ?? 0}</p>
                <p className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">{ROLE_DESC[r]}</p>
              </div>
            ))}
          </div>
          <Panel bodyClassName="p-3 sm:p-4">
            {q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : (
              <DataTable columns={columns} rows={q.data} loading={q.isLoading} rowKey={(u) => u.id}
                empty={<EmptyState icon={<UserCog />} title="No staff yet" description="Add administrators, loan officers and collectors." />} />
            )}
          </Panel>
        </TabsContent>
        <TabsContent value="permissions" className="mt-4">
          <PermissionsMatrix isOwner={isOwner} />
        </TabsContent>
      </Tabs>

      {adding && <AddStaffDialog onClose={() => setAdding(false)} isOwner={isOwner} minPw={minPw} />}
      {roleFor && <RoleDialog user={roleFor} isOwner={isOwner} onClose={() => setRoleFor(null)} />}
      {pwFor && <ResetPasswordDialog user={pwFor} minPw={minPw} onClose={() => setPwFor(null)} />}
      <ConfirmDialog
        open={!!activeFor}
        onOpenChange={(o) => !o && setActiveFor(null)}
        title={activeFor?.is_active ? `Deactivate ${activeFor?.full_name}?` : `Reactivate ${activeFor?.full_name}?`}
        description={activeFor?.is_active
          ? 'They will lose access immediately and their collector assignments will be released. Their history is preserved.'
          : 'They will regain access with their current role.'}
        destructive={activeFor?.is_active}
        confirmLabel={activeFor?.is_active ? 'Deactivate' : 'Reactivate'}
        onConfirm={async () => {
          await usersService.setActive(activeFor!.id, !activeFor!.is_active)
          toast.success(activeFor!.is_active ? 'User deactivated' : 'User reactivated')
          void qc.invalidateQueries({ queryKey: ['users'] })
        }}
      />
    </div>
  )
}

const addSchema = z.object({
  full_name: z.string().trim().min(2, 'Enter the full name'),
  email: z.string().trim().email('Enter a valid email'),
  phone: z.string().trim().optional().refine((v) => !v || /^\+639\d{9}$/.test(normalizePhMobile(v)), 'Enter a valid PH mobile number'),
  role: z.enum(['owner', 'admin', 'loan_officer', 'collector']),
  password: z.string(),
})

function AddStaffDialog({ onClose, isOwner, minPw }: { onClose: () => void; isOwner: boolean; minPw: number }) {
  const qc = useQueryClient()
  const form = useForm<z.infer<typeof addSchema>>({
    resolver: zodResolver(addSchema.refine((v) => v.password.length >= minPw, { path: ['password'], message: `At least ${minPw} characters` })),
    defaultValues: { full_name: '', email: '', phone: '', role: 'loan_officer', password: generatePassword() },
  })
  const { register, handleSubmit, setValue, watch, formState: { errors, isSubmitting } } = form
  const [created, setCreated] = useState<{ email: string; password: string } | null>(null)
  const role = watch('role')
  const onSubmit = handleSubmit(async (v) => {
    try {
      await usersService.create({ email: v.email, password: v.password, full_name: v.full_name, role: v.role, phone: v.phone ? normalizePhMobile(v.phone) : undefined })
      void qc.invalidateQueries({ queryKey: ['users'] })
      setCreated({ email: v.email, password: v.password })
    } catch (e) {
      toast.error(errorMessage(e))
    }
  })
  return (
    <Dialog open onOpenChange={(o) => !o && !isSubmitting && onClose()}>
      <DialogContent className="sm:max-w-md">
        {created ? (
          <>
            <DialogHeader>
              <DialogTitle>Staff account created</DialogTitle>
              <DialogDescription>Share these sign-in details securely. Ask them to change the password after signing in (My profile → Change password).</DialogDescription>
            </DialogHeader>
            <div className="space-y-2 rounded-lg bg-slate-50 p-3 font-mono text-sm">
              <p>Email: {created.email}</p>
              <p>Temporary password: {created.password}</p>
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => { void navigator.clipboard?.writeText(`Email: ${created.email}\nTemporary password: ${created.password}\nSign in: ${window.location.origin}/login`); toast.success('Copied') }}>Copy</Button>
              <Button onClick={onClose}>Done</Button>
            </DialogFooter>
          </>
        ) : (
          <form onSubmit={onSubmit} noValidate>
            <DialogHeader>
              <DialogTitle>Add staff member</DialogTitle>
              <DialogDescription>The account is active immediately — no email confirmation needed.</DialogDescription>
            </DialogHeader>
            <div className="my-4 space-y-3">
              <Field label="Full name" required error={errors.full_name?.message}><Input className="h-10" {...register('full_name')} /></Field>
              <Field label="Email" required error={errors.email?.message}><Input className="h-10" type="email" autoComplete="off" {...register('email')} /></Field>
              <Field label="Mobile number" error={errors.phone?.message}><Input className="h-10" inputMode="tel" {...register('phone')} /></Field>
              <Field label="Role" hint={ROLE_DESC[role]}>
                <NativeSelect {...register('role')}>
                  {ROLES.filter((r) => r !== 'owner' || isOwner).map((r) => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
                </NativeSelect>
              </Field>
              <Field label="Temporary password" error={errors.password?.message}>
                <div className="flex gap-2">
                  <PasswordInput defaultVisible className="h-10 font-mono" autoComplete="new-password" {...register('password')} />
                  <Button type="button" variant="outline" size="icon" className="size-10 shrink-0" aria-label="Generate password" onClick={() => setValue('password', generatePassword(), { shouldValidate: true })}><RotateCcw /></Button>
                </div>
              </Field>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={onClose} disabled={isSubmitting}>Cancel</Button>
              <Button type="submit" disabled={isSubmitting}>{isSubmitting && <Spinner />} Create account</Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}

function RoleDialog({ user, isOwner, onClose }: { user: StaffMember; isOwner: boolean; onClose: () => void }) {
  const qc = useQueryClient()
  const [role, setRole] = useState<AppRole>(user.role ?? 'collector')
  const [busy, setBusy] = useState(false)
  return (
    <Dialog open onOpenChange={(o) => !o && !busy && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Change role — {user.full_name}</DialogTitle>
          <DialogDescription>Permissions change immediately on their next action.</DialogDescription>
        </DialogHeader>
        <div className="space-y-2">
          {ROLES.filter((r) => r !== 'owner' || isOwner).map((r) => (
            <label key={r} className={`flex cursor-pointer items-start gap-3 rounded-lg border p-3 ${role === r ? 'border-blue-500 bg-blue-50/50' : 'hover:bg-muted/40'}`}>
              <input type="radio" name="role" className="mt-1" checked={role === r} onChange={() => setRole(r)} />
              <span>
                <span className="block text-sm font-medium">{ROLE_LABEL[r]}</span>
                <span className="block text-xs text-muted-foreground">{ROLE_DESC[r]}</span>
              </span>
            </label>
          ))}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button disabled={busy || role === user.role} onClick={async () => {
            setBusy(true)
            try {
              await usersService.setRole(user.id, role)
              toast.success('Role updated')
              void qc.invalidateQueries({ queryKey: ['users'] })
              onClose()
            } catch (e) {
              toast.error(errorMessage(e))
            } finally {
              setBusy(false)
            }
          }}>{busy && <Spinner />} Save role</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ResetPasswordDialog({ user, minPw, onClose }: { user: StaffMember; minPw: number; onClose: () => void }) {
  const [pw, setPw] = useState(generatePassword())
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  return (
    <Dialog open onOpenChange={(o) => !o && !busy && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Reset password — {user.full_name}</DialogTitle>
          <DialogDescription>{done ? 'Password changed. Share the new password securely.' : 'Set a new temporary password for this user.'}</DialogDescription>
        </DialogHeader>
        <Field label="New password" error={pw.length < minPw ? `At least ${minPw} characters` : undefined}>
          <div className="flex gap-2">
            <PasswordInput defaultVisible className="h-10 font-mono" autoComplete="new-password" value={pw} onChange={(e) => setPw(e.target.value)} readOnly={done} />
            {!done && <Button type="button" variant="outline" size="icon" className="size-10 shrink-0" onClick={() => setPw(generatePassword())} aria-label="Generate"><RotateCcw /></Button>}
          </div>
        </Field>
        <DialogFooter>
          {done ? (
            <>
              <Button variant="outline" onClick={() => { void navigator.clipboard?.writeText(pw); toast.success('Copied') }}>Copy</Button>
              <Button onClick={onClose}>Done</Button>
            </>
          ) : (
            <>
              <Button variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
              <Button disabled={busy || pw.length < minPw} onClick={async () => {
                setBusy(true)
                try {
                  await usersService.resetPassword(user.id, pw)
                  setDone(true)
                  toast.success('Password reset')
                } catch (e) {
                  toast.error(errorMessage(e))
                } finally {
                  setBusy(false)
                }
              }}>{busy && <Spinner />} Reset password</Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function PermissionsMatrix({ isOwner }: { isOwner: boolean }) {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['users', 'permissions'], queryFn: usersService.rolePermissions })
  const [draft, setDraft] = useState<Record<string, Set<string>>>({})
  const [saving, setSaving] = useState<string | null>(null)
  const [resetRole, setResetRole] = useState<AppRole | null>(null)
  const editable: AppRole[] = ['admin', 'loan_officer', 'collector']

  useEffect(() => {
    if (!q.data) return
    setDraft(Object.fromEntries(editable.map((r) => [r, new Set(q.data[r] ?? [])])))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q.data])

  const dirty = (r: AppRole) => {
    const orig = new Set(q.data?.[r] ?? [])
    const d = draft[r]
    if (!d) return false
    return d.size !== orig.size || [...d].some((p) => !orig.has(p))
  }
  const toggle = (r: AppRole, p: string) => setDraft((prev) => {
    const next = new Set(prev[r])
    if (next.has(p)) next.delete(p)
    else next.add(p)
    return { ...prev, [r]: next }
  })
  const save = async (r: AppRole) => {
    setSaving(r)
    try {
      await usersService.setRolePermissions(r, [...(draft[r] ?? [])])
      toast.success(`${ROLE_LABEL[r]} permissions saved`)
      void qc.invalidateQueries({ queryKey: ['users', 'permissions'] })
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setSaving(null)
    }
  }

  if (q.error) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />
  if (q.isLoading || !Object.keys(draft).length) return <TableSkeleton />
  const groups = [...new Set(PERMISSIONS.map((p) => p.group))]

  return (
    <Panel title="Role permissions" description="Owners always have every permission. Changes take effect immediately for all users with that role.">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[640px] text-sm">
          <thead>
            <tr className="border-b text-left text-xs uppercase tracking-wide text-muted-foreground">
              <th className="py-2 pr-3 font-medium">Permission</th>
              <th className="w-24 px-2 py-2 text-center font-medium">Owner</th>
              {editable.map((r) => <th key={r} className="w-28 px-2 py-2 text-center font-medium">{ROLE_LABEL[r]}</th>)}
            </tr>
          </thead>
          <tbody>
            {groups.map((g) => (
              <PermissionGroup key={g} group={g} editable={editable} draft={draft} toggle={toggle} disabled={!!saving} isOwner={isOwner} />
            ))}
          </tbody>
          <tfoot>
            <tr>
              <td className="py-3 pr-3 text-xs text-muted-foreground"><ShieldCheck className="mr-1 inline size-3.5" />Only owners can grant user management.</td>
              <td />
              {editable.map((r) => (
                <td key={r} className="px-2 py-3 text-center align-top">
                  <div className="flex flex-col items-center gap-1">
                    <Button size="sm" disabled={!dirty(r) || !!saving} onClick={() => void save(r)}>{saving === r && <Spinner />} Save</Button>
                    <button className="text-[11px] text-blue-600 hover:underline" onClick={() => setResetRole(r)}>Reset defaults</button>
                  </div>
                </td>
              ))}
            </tr>
          </tfoot>
        </table>
      </div>
      <ConfirmDialog open={!!resetRole} onOpenChange={(o) => !o && setResetRole(null)}
        title={`Reset ${resetRole ? ROLE_LABEL[resetRole] : ''} permissions?`} description="The role will get LendFlow's recommended default permissions."
        confirmLabel="Reset" onConfirm={async () => {
          await usersService.resetRolePermissions(resetRole!)
          toast.success('Permissions reset to defaults')
          void qc.invalidateQueries({ queryKey: ['users', 'permissions'] })
        }} />
    </Panel>
  )
}

function PermissionGroup({ group, editable, draft, toggle, disabled, isOwner }: {
  group: string; editable: AppRole[]; draft: Record<string, Set<string>>; toggle: (r: AppRole, p: string) => void; disabled: boolean; isOwner: boolean
}) {
  return (
    <>
      <tr><td colSpan={2 + editable.length} className="pb-1 pt-4 text-xs font-semibold uppercase tracking-wide text-[#142B4A]">{group}</td></tr>
      {PERMISSIONS.filter((p) => p.group === group).map((p) => (
        <tr key={p.key} className="border-b last:border-0">
          <td className="py-2 pr-3">
            <p className="font-medium">{p.label}</p>
            <p className="font-mono text-[11px] text-muted-foreground">{p.key}</p>
          </td>
          <td className="px-2 py-2 text-center"><Checkbox checked disabled aria-label="Owner" /></td>
          {editable.map((r) => (
            <td key={r} className="px-2 py-2 text-center">
              <Checkbox
                checked={draft[r]?.has(p.key) ?? false}
                disabled={disabled || (p.key === 'users.manage' && !isOwner)}
                onCheckedChange={() => toggle(r, p.key)}
                aria-label={`${ROLE_LABEL[r]}: ${p.label}`}
              />
            </td>
          ))}
        </tr>
      ))}
    </>
  )
}
