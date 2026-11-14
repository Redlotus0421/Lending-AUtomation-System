import { useState, type ReactNode } from 'react'
import { Link, Navigate, NavLink, Outlet, useLocation } from 'react-router-dom'
import { Building2, KeyRound, LayoutDashboard, LogOut, Menu, ShieldCheck } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useAuth } from '@/features/auth/AuthProvider'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { PageLoader } from '@/components/common'
import { initials } from '@/lib/format'

const NAV: { to: string; label: string; icon: ReactNode; end?: boolean }[] = [
  { to: '/admin', label: 'Overview', icon: <LayoutDashboard />, end: true },
  { to: '/admin/accounts', label: 'Lender accounts', icon: <Building2 /> },
  { to: '/admin/profile', label: 'My login', icon: <KeyRound /> },
]

/** Only platform administrators may open the console. */
export function RequirePlatformAdmin({ children }: { children: ReactNode }) {
  const { loading, session, access } = useAuth()
  if (loading) return <PageLoader label="Loading the admin console…" />
  if (!session) return <Navigate to="/login" replace />
  if (!access?.is_platform_admin) return <Navigate to="/" replace />
  return <>{children}</>
}

function Brand() {
  return (
    <Link to="/admin" className="flex items-center gap-2.5">
      <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-white/10 ring-1 ring-white/15">
        <svg viewBox="0 0 32 32" className="size-6"><path d="M9 8v16h14" stroke="#14B8A6" strokeWidth="3.2" fill="none" strokeLinecap="round" strokeLinejoin="round" /><path d="M14 18l4-5 3 3" stroke="#fff" strokeWidth="2.4" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
      </span>
      <span className="min-w-0 leading-tight">
        <span className="block text-[15px] font-semibold tracking-tight text-white">LendFlow</span>
        <span className="block truncate text-[11px] text-sidebar-foreground/80">Platform administration</span>
      </span>
    </Link>
  )
}

function Nav({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <nav>
      <p className="mb-1.5 px-3 text-[10px] font-semibold uppercase tracking-[0.12em] text-sidebar-foreground/50">Super admin</p>
      <ul className="space-y-0.5">
        {NAV.map((i) => (
          <li key={i.to}>
            <NavLink to={i.to} end={i.end ?? false} onClick={onNavigate}
              className={({ isActive }) => cn(
                'flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition [&_svg]:size-[18px]',
                isActive ? 'bg-sidebar-accent text-white shadow-inner [&_svg]:text-teal' : 'text-sidebar-foreground hover:bg-white/5 hover:text-white',
              )}>
              {i.icon}
              {i.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}

function UserMenu() {
  const { access, session, signOut } = useAuth()
  const name = access?.full_name || session?.user.email || 'Admin'
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button className="flex items-center gap-2 rounded-full p-0.5 pr-2 hover:bg-muted" aria-label="Account menu">
          <span className="grid size-9 place-items-center rounded-full bg-[#142B4A] text-xs font-semibold text-white">{initials(name)}</span>
          <span className="hidden text-left leading-tight lg:block">
            <span className="block max-w-40 truncate text-sm font-medium">{name}</span>
            <span className="block text-[11px] text-muted-foreground">Super admin</span>
          </span>
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        <DropdownMenuLabel>
          <p className="truncate text-sm">{name}</p>
          <p className="truncate text-xs font-normal text-muted-foreground">{session?.user.email}</p>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild><Link to="/admin/profile"><ShieldCheck /> My login</Link></DropdownMenuItem>
        <DropdownMenuItem onClick={() => void signOut()}><LogOut /> Sign out</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

export function AdminShell() {
  const [open, setOpen] = useState(false)
  const location = useLocation()
  return (
    <div className="min-h-dvh bg-background">
      <aside className="no-print fixed inset-y-0 left-0 z-30 hidden w-64 flex-col bg-sidebar lg:flex">
        <div className="flex h-16 items-center px-5"><Brand /></div>
        <div className="flex-1 overflow-y-auto px-3 pb-6 pt-2"><Nav /></div>
        <div className="border-t border-sidebar-border px-5 py-3 text-[11px] text-sidebar-foreground/60">
          You manage accounts, not their loan records.
        </div>
      </aside>
      <div className="lg:pl-64 print:pl-0">
        <header className="no-print sticky top-0 z-20 flex h-16 items-center gap-2 border-b bg-white/90 px-3 backdrop-blur sm:px-6">
          <Button variant="ghost" size="icon" className="size-10 lg:hidden" onClick={() => setOpen(true)} aria-label="Open menu"><Menu className="size-5" /></Button>
          <div className="lg:hidden"><span className="text-base font-semibold text-[#142B4A]">LendFlow Admin</span></div>
          <div className="flex-1" />
          <UserMenu />
        </header>
        <main key={location.pathname.split('/')[2] ?? ''} className="mx-auto w-full max-w-[1400px] px-3 pb-16 pt-5 sm:px-6">
          <Outlet />
        </main>
      </div>
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent side="left" className="w-72 border-0 bg-sidebar p-0 text-sidebar-foreground">
          <SheetHeader className="px-5 pt-5"><SheetTitle className="sr-only">Menu</SheetTitle><Brand /></SheetHeader>
          <div className="px-3 pb-8"><Nav onNavigate={() => setOpen(false)} /></div>
        </SheetContent>
      </Sheet>
    </div>
  )
}
