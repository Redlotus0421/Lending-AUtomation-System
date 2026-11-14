import { useQuery } from '@tanstack/react-query'
import { cn } from '@/lib/utils'
import { initials } from '@/lib/format'
import { Pill } from '@/components/common'
import { borrowersService } from '@/services/borrowers'
import type { Borrower } from '@/types/db'

/** Signed URL for a private borrower file (refreshed before the link expires). */
export function useBorrowerFileUrl(path: string | null | undefined) {
  return useQuery({
    queryKey: ['borrower-file', path],
    queryFn: () => borrowersService.fileUrl(path!, 3600),
    enabled: !!path,
    staleTime: 50 * 60_000,
  }).data
}

export function BorrowerAvatar({ borrower, size = 40, className }: { borrower: Pick<Borrower, 'full_name' | 'profile_photo_path'>; size?: number; className?: string }) {
  const url = useBorrowerFileUrl(borrower.profile_photo_path)
  return url ? (
    <img src={url} alt={borrower.full_name} style={{ width: size, height: size }} className={cn('shrink-0 rounded-full object-cover ring-1 ring-black/5', className)} />
  ) : (
    <span style={{ width: size, height: size, fontSize: size * 0.36 }}
      className={cn('grid shrink-0 place-items-center rounded-full bg-[#142B4A]/10 font-semibold text-[#142B4A]', className)}>
      {initials(borrower.full_name)}
    </span>
  )
}

export const RISK: Record<string, { label: string; tone: 'slate' | 'green' | 'amber' | 'red' }> = {
  unrated: { label: 'Unrated', tone: 'slate' },
  low: { label: 'Low risk', tone: 'green' },
  medium: { label: 'Medium risk', tone: 'amber' },
  high: { label: 'High risk', tone: 'red' },
}

export function RiskBadge({ risk }: { risk: string }) {
  const r = RISK[risk] ?? RISK.unrated
  return <Pill tone={r.tone}>{r.label}</Pill>
}

export function BorrowerStatusBadge({ status }: { status: string }) {
  const tone = status === 'active' ? 'green' : status === 'blacklisted' ? 'red' : 'slate'
  return <Pill tone={tone}>{status === 'active' ? 'Active' : status === 'blacklisted' ? 'Blacklisted' : 'Archived'}</Pill>
}

export const OPEN_LOAN_STATUSES = ['active', 'defaulted', 'ready_for_release', 'fully_signed', 'awaiting_borrower_signature', 'awaiting_lender_signature', 'approved', 'pending_review', 'draft']
