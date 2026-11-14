import { lazy } from 'react'
import { useQuery } from '@tanstack/react-query'
import { PageLoader } from '@/components/common'
import { useAuth } from '@/features/auth/AuthProvider'
import { orgService } from '@/services/org'

const LoanWizardPage = lazy(() => import('./LoanWizardPage'))
const SimplifiedLoanPage = lazy(() => import('./simplified/SimplifiedLoanPage'))

/**
 * /loans/new — the workflow is decided by the business's lending mode, which only the platform
 * administrator can change. Staff are never asked to choose between Complete and Simplified Lending.
 * The mode is re-read on entry so a change by the administrator applies without signing in again
 * (the database enforces the mode regardless).
 */
export default function NewLoanPage() {
  const { organization } = useAuth()
  const org = useQuery({ queryKey: ['organization', 'lending-mode'], queryFn: orgService.get, staleTime: 0 })
  if (org.isLoading) return <PageLoader />
  const mode = org.data?.lending_mode ?? organization?.lending_mode
  return mode === 'simplified' ? <SimplifiedLoanPage /> : <LoanWizardPage />
}
