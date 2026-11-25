import { useState, type ReactNode } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  BarChart3, Bell, CalendarClock, ClipboardList, FileSignature, FileText, HandCoins, LayoutDashboard, LogOut, Menu, Package,
  NotebookTabs, PlusCircle, ScrollText, Settings, ShieldAlert, ShieldCheck, UserCog, Users, Wallet,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { useAuth } from '@/features/auth/AuthProvider'
import { notificationsService } from '@/services/org'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { ROLE_LABEL } from '@/lib/constants'
import { initials, timeAgo } from '@/lib/format'
import { NewLoanChooserProvider, useNewLoan } from '@/features/loans/NewLoanChooser'

type NavItem = { to: string; label: string; icon: ReactNode; perm?: string | string[]; end?: boolean }

export const NAV: { section: string; items: NavItem[] }[] = [
  {
    section: 'Overview',
    items: [{ to: '/', label: 'Dashboard', icon: <LayoutDashboard />, perm: 'dashboard.view', end: true }],
  },
  {
    section: 'Lending',
    items: [
      { to: '/borrowers', label: 'Borrowers', icon: <Users />, perm: 'borrowers.view' },
      { to: '/applications', label: 'Loan Applications', icon: <ClipboardList />, perm: 'loans.view' },
      { to: '/loans', label: 'Active Loans', icon: <HandCoins />, perm: 'loans.view', end: false },
      { to: '/agreements', label: 'Loan Agreements', icon: <FileSignature />, perm: 'agreements.view', end: true },
      { to: '/agreements/templates', label: 'Terms Templates', icon: <FileText />, perm: 'templates.manage' },
    ],
  },
  {
    section: 'Collections',
    items: [
      { to: '/payments', label: 'Payments & Collections', icon: <Wallet />, perm: 'payments.view' },
      { to: '/due', label: 'Due Payments', icon: <CalendarClock />, perm: 'payments.view' },
      { to: '/overdue', label: 'Overdue Loans', icon: <ShieldAlert />, perm: 'payments.view' },
      // owners and admins (reports.view): a borrower's whole schedule and payment history
      { to: '/records', label: 'Records', icon: <NotebookTabs />, perm: 'reports.view' },
    ],
  },
  {
    section: 'Management',
    items: [
      { to: '/products', label: 'Loan Products', icon: <Package />, perm: ['products.manage', 'loans.create'] },
      { to: '/reports', label: 'Reports & Analytics', icon: <BarChart3 />, perm: 'reports.view' },
      { to: '/users', label: 'Users & Staff', icon: <UserCog />, perm: 'users.manage' },
      { to: '/settings', label: 'Settings', icon: <Settings />, perm: ['settings.manage', 'templates.manage'] },
      { to: '/audit', label: 'Audit Logs', icon: <ScrollText />, perm: 'audit.view' },
    ],
  },
]

function useNav() {
  const { canAny } = useAuth()
  return NAV.map((s) => ({
    ...s,
    items: s.items.filter((i) => !i.perm || canAny(...(Array.isArray(i.perm) ? i.perm : [i.perm]))),
  })).filter((s) => s.items.length)
}

function Brand({ compact }: { compact?: boolean }) {
  const { organization } = useAuth()
  return (
    <Link to="/" className="flex items-center gap-2.5">
      <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-white/10 ring-1 ring-white/15">
        <svg viewBox="0 0 32 32" className="size-6"><path d="M9 8v16h14" stroke="#14B8A6" strokeWidth="3.2" fill="none" strokeLinecap="round" strokeLinejoin="round" /><path d="M14 18l4-5 3 3" stroke="#fff" strokeWidth="2.4" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
      </span>
      {!compact && (
        <span className="min-w-0 leading-tight">
          <span className="block text-[15px] font-semibold tracking-tight text-white">LendFlow</span>
          <span className="block truncate text-[11px] text-sidebar-foreground/80">{organization?.name ?? 'Lending Management'}</span>
        </span>
      )}
    </Link>
  )
}

function SidebarNav({ onNavigate }: { onNavigate?: () => void }) {
  const nav = useNav()
  return (
    <nav className="space-y-5">
      {nav.map((s) => (
        <div key={s.section}>
          <p className="mb-1.5 px-3 text-[10px] font-semibold uppercase tracking-[0.12em] text-sidebar-foreground/50">{s.section}</p>
          <ul className="space-y-0.5">
            {s.items.map((i) => (
              <li key={i.to}>
                <NavLink
                  to={i.to}
                  end={i.end ?? false}
                  onClick={onNavigate}
                  className={({ isActive }) => cn(
                    'flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition [&_svg]:size-[18px]',
                    isActive ? 'bg-sidebar-accent text-white shadow-inner [&_svg]:text-teal' : 'text-sidebar-foreground hover:bg-white/5 hover:text-white',
                  )}
                >
                  {i.icon}
                  {i.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  )
}

function Notifications() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const { data } = useQuery({ queryKey: ['notifications'], queryFn: notificationsService.list, refetchInterval: 60_000 })
  const unread = data?.filter((n) => !n.read_at).length ?? 0
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="icon" className="relative size-10" aria-label="Notifications">
          <Bell className="size-5" />
          {unread > 0 && <span className="absolute right-1.5 top-1.5 grid min-w-4 place-items-center rounded-full bg-red-500 px-1 text-[10px] font-semibold text-white">{unread}</span>}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-80 p-0">
        <div className="flex items-center justify-between border-b px-3 py-2">
          <p className="text-sm font-semibold">Notifications</p>
          {unread > 0 && (
            <button className="text-xs text-blue-600 hover:underline" onClick={async () => {
              await notificationsService.markRead()
              void qc.invalidateQueries({ queryKey: ['notifications'] })
            }}>Mark all read</button>
          )}
        </div>
        <div className="max-h-96 overflow-auto">
          {!data?.length && <p className="px-3 py-8 text-center text-sm text-muted-foreground">You're all caught up.</p>}
          {data?.map((n) => (
            <button key={n.id} className={cn('block w-full border-b px-3 py-2.5 text-left last:border-0 hover:bg-muted/50', !n.read_at && 'bg-blue-50/50')}
              onClick={async () => {
                if (!n.read_at) {
                  await notificationsService.markRead([n.id])
                  void qc.invalidateQueries({ queryKey: ['notifications'] })
                }
                if (n.link) navigate(n.link)
              }}>
              <p className="text-sm font-medium">{n.title}</p>
              {n.body && <p className="text-xs text-muted-foreground">{n.body}</p>}
              <p className="mt-0.5 text-[11px] text-muted-foreground">{timeAgo(n.created_at)}</p>
            </button>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  )
}

function UserMenu() {
  const { access, signOut, session } = useAuth()
  const name = access?.full_name || session?.user.email || 'User'
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button className="flex items-center gap-2 rounded-full p-0.5 pr-2 hover:bg-muted" aria-label="Account menu">
          <span className="grid size-9 place-items-center rounded-full bg-[#142B4A] text-xs font-semibold text-white">{initials(name)}</span>
          <span className="hidden text-left leading-tight lg:block">
            <span className="block max-w-36 truncate text-sm font-medium">{name}</span>
            <span className="block text-[11px] text-muted-foreground">{ROLE_LABEL[access?.role ?? ''] ?? ''}</span>
          </span>
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        <DropdownMenuLabel>
          <p className="truncate text-sm">{name}</p>
          <p className="truncate text-xs font-normal text-muted-foreground">{session?.user.email}</p>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild><Link to="/profile"><ShieldCheck /> My profile</Link></DropdownMenuItem>
        <DropdownMenuItem onClick={() => void signOut()}><LogOut /> Sign out</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

export function AppShell() {
  return <NewLoanChooserProvider><ShellLayout /></NewLoanChooserProvider>
}

function ShellLayout() {
  const [menuOpen, setMenuOpen] = useState(false)
  const { can } = useAuth()
  const newLoan = useNewLoan()
  const location = useLocation()
  const bottom = [
    { to: '/', label: 'Home', icon: <LayoutDashboard />, show: can('dashboard.view'), end: true },
    { to: '/borrowers', label: 'Borrowers', icon: <Users />, show: can('borrowers.view') },
    { to: '/loans/new', label: 'New loan', icon: <PlusCircle />, show: can('loans.create'), primary: true },
    { to: '/due', label: 'Collect', icon: <Wallet />, show: can('payments.view') },
  ].filter((b) => b.show)

  return (
    <div className="min-h-dvh bg-background">
      {/* desktop sidebar */}
      <aside className="no-print fixed inset-y-0 left-0 z-30 hidden w-64 flex-col bg-sidebar lg:flex">
        <div className="flex h-16 items-center px-5"><Brand /></div>
        <div className="scrollbar-thin flex-1 overflow-y-auto px-3 pb-6 pt-2"><SidebarNav /></div>
        <div className="border-t border-sidebar-border px-5 py-3 text-[11px] text-sidebar-foreground/60">Asia/Manila · PHP</div>
      </aside>

      <div className="lg:pl-64 print:pl-0">
        <header className="no-print sticky top-0 z-20 flex h-16 items-center gap-2 border-b bg-white/90 px-3 backdrop-blur sm:px-6">
          <Button variant="ghost" size="icon" className="size-10 lg:hidden" onClick={() => setMenuOpen(true)} aria-label="Open menu"><Menu className="size-5" /></Button>
          <div className="lg:hidden"><span className="text-base font-semibold text-[#142B4A]">LendFlow</span></div>
          <div className="flex-1" />
          {can('loans.create') && (
            <Button className="hidden sm:inline-flex" onClick={newLoan.open}><PlusCircle /> New loan</Button>
          )}
          <Notifications />
          <UserMenu />
        </header>
        <main key={location.pathname.split('/')[1]} className="mx-auto w-full max-w-[1400px] px-3 pb-28 pt-5 sm:px-6 lg:pb-10">
          <Outlet />
        </main>
      </div>

      {/* mobile bottom navigation */}
      <nav className="no-print pb-safe fixed inset-x-0 bottom-0 z-30 border-t bg-white lg:hidden">
        <div className="grid" style={{ gridTemplateColumns: `repeat(${bottom.length + 1}, minmax(0, 1fr))` }}>
          {bottom.map((b) => b.primary ? (
            <button key={b.to} type="button" onClick={newLoan.open}
              className="flex flex-col items-center gap-0.5 py-2 text-[11px] font-medium text-slate-600 [&_svg]:size-5">
              <span className="-mt-5 grid size-12 place-items-center rounded-full bg-[#2563EB] text-white shadow-lg ring-4 ring-white">{b.icon}</span>
              <span>{b.label}</span>
            </button>
          ) : (
            <NavLink key={b.to} to={b.to} end={b.end}
              className={({ isActive }) => cn('flex flex-col items-center gap-0.5 py-2 text-[11px] font-medium [&_svg]:size-5',
                isActive ? 'text-blue-600' : 'text-slate-500')}>
              {b.icon}
              <span>{b.label}</span>
            </NavLink>
          ))}
          <button onClick={() => setMenuOpen(true)} className="flex flex-col items-center gap-0.5 py-2 text-[11px] font-medium text-slate-500 [&_svg]:size-5">
            <Menu /> Menu
          </button>
        </div>
      </nav>

      <Sheet open={menuOpen} onOpenChange={setMenuOpen}>
        <SheetContent side="left" className="w-72 border-0 bg-sidebar p-0 text-sidebar-foreground">
          <SheetHeader className="px-5 pt-5"><SheetTitle className="sr-only">Menu</SheetTitle><Brand /></SheetHeader>
          <div className="overflow-y-auto px-3 pb-8"><SidebarNav onNavigate={() => setMenuOpen(false)} /></div>
        </SheetContent>
      </Sheet>
    </div>
  )
}

export function RequirePermission({ perm, children }: { perm: string | string[]; children: ReactNode }) {
  const { canAny } = useAuth()
  const ok = canAny(...(Array.isArray(perm) ? perm : [perm]))
  if (!ok) {
    return (
      <div className="mx-auto mt-16 max-w-md rounded-xl border bg-white p-8 text-center shadow-card">
        <ShieldAlert className="mx-auto mb-3 size-8 text-amber-500" />
        <h1 className="text-lg font-semibold">Access restricted</h1>
        <p className="mt-1 text-sm text-muted-foreground">Your role does not have permission to open this page. Contact your administrator if you need access.</p>
        <Button asChild variant="outline" className="mt-5"><Link to="/">Back to dashboard</Link></Button>
      </div>
    )
  }
  return <>{children}</>
}
