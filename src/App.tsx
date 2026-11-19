import { lazy, Suspense, type ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell, RequirePermission } from '@/components/layout/AppShell'
import { PageLoader } from '@/components/common'
import { useAuth } from '@/features/auth/AuthProvider'
import {
  AccountSuspendedPage, ForcePasswordChangePage, LoginPage, NoAccountPage, ProfilePage, ResetPasswordPage,
} from '@/features/auth/AuthPages'
import { AdminShell, RequirePlatformAdmin } from '@/features/admin/AdminShell'

const DashboardPage = lazy(() => import('@/features/dashboard/DashboardPage'))
const BorrowersPage = lazy(() => import('@/features/borrowers/BorrowersPage'))
const BorrowerFormPage = lazy(() => import('@/features/borrowers/BorrowerFormPage'))
const BorrowerProfilePage = lazy(() => import('@/features/borrowers/BorrowerProfilePage'))
const LoanApplicationsPage = lazy(() => import('@/features/loans/LoanApplicationsPage'))
const LoansPage = lazy(() => import('@/features/loans/LoansPage'))
const LoanWizardPage = lazy(() => import('@/features/loans/LoanWizardPage'))
const NewLoanPage = lazy(() => import('@/features/loans/NewLoanPage'))
const LoanDetailPage = lazy(() => import('@/features/loans/LoanDetailPage'))
const AgreementsPage = lazy(() => import('@/features/agreements/AgreementsPage'))
const TemplatesPage = lazy(() => import('@/features/agreements/TemplatesPage'))
const TemplateEditorPage = lazy(() => import('@/features/agreements/TemplateEditorPage'))
const PublicSigningPage = lazy(() => import('@/features/signing/PublicSigningPage'))
const PaymentsPage = lazy(() => import('@/features/payments/PaymentsPage'))
const PaymentDetailPage = lazy(() => import('@/features/payments/PaymentDetailPage'))
const DuePaymentsPage = lazy(() => import('@/features/payments/DuePaymentsPage'))
const OverdueLoansPage = lazy(() => import('@/features/payments/OverdueLoansPage'))
const ProductsPage = lazy(() => import('@/features/products/ProductsPage'))
const ReportsPage = lazy(() => import('@/features/reports/ReportsPage'))
const UsersPage = lazy(() => import('@/features/users/UsersPage'))
const SettingsPage = lazy(() => import('@/features/settings/SettingsPage'))
const AuditLogsPage = lazy(() => import('@/features/audit/AuditLogsPage'))
const AdminOverviewPage = lazy(() => import('@/features/admin/AdminOverviewPage'))
const AccountsPage = lazy(() => import('@/features/admin/AccountsPage'))
const AccountDetailPage = lazy(() => import('@/features/admin/AccountDetailPage'))
const AccountTemplatePage = lazy(() => import('@/features/admin/AccountTemplates'))

function Protected({ children }: { children: ReactNode }) {
  const { loading, session, access } = useAuth()
  if (loading) return <PageLoader label="Loading your workspace…" />
  if (!session) return <Navigate to="/login" replace />
  if (session.user.user_metadata?.must_change_password) return <ForcePasswordChangePage />
  if (access?.suspended_organization) return <AccountSuspendedPage />
  if (!access?.organization_id) return <Navigate to={access?.is_platform_admin ? '/admin' : '/no-account'} replace />
  return <>{children}</>
}

function AdminGate({ children }: { children: ReactNode }) {
  const { session } = useAuth()
  return (
    <RequirePlatformAdmin>
      {session?.user.user_metadata?.must_change_password ? <ForcePasswordChangePage /> : children}
    </RequirePlatformAdmin>
  )
}

const guard = (perm: string | string[], el: ReactNode) => <RequirePermission perm={perm}>{el}</RequirePermission>

export default function App() {
  return (
    <Suspense fallback={<PageLoader />}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<Navigate to="/login" replace />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route path="/no-account" element={<NoAccountPage />} />
        <Route path="/onboarding" element={<Navigate to="/no-account" replace />} />
        <Route path="/sign/:token" element={<PublicSigningPage />} />

        <Route path="/admin" element={<AdminGate><AdminShell /></AdminGate>}>
          <Route index element={<AdminOverviewPage />} />
          <Route path="accounts" element={<AccountsPage />} />
          <Route path="accounts/:id" element={<AccountDetailPage />} />
          <Route path="accounts/:id/templates/:templateId" element={<AccountTemplatePage />} />
          <Route path="profile" element={<ProfilePage />} />
          <Route path="*" element={<Navigate to="/admin" replace />} />
        </Route>

        <Route element={<Protected><AppShell /></Protected>}>
          <Route index element={guard('dashboard.view', <DashboardPage />)} />
          <Route path="profile" element={<ProfilePage />} />

          <Route path="borrowers" element={guard('borrowers.view', <BorrowersPage />)} />
          <Route path="borrowers/new" element={guard('borrowers.create', <BorrowerFormPage />)} />
          <Route path="borrowers/:id" element={guard('borrowers.view', <BorrowerProfilePage />)} />
          <Route path="borrowers/:id/edit" element={guard('borrowers.edit', <BorrowerFormPage />)} />

          <Route path="applications" element={guard('loans.view', <LoanApplicationsPage />)} />
          <Route path="loans" element={guard('loans.view', <LoansPage />)} />
          <Route path="loans/new" element={guard('loans.create', <NewLoanPage />)} />
          <Route path="loans/:id" element={guard('loans.view', <LoanDetailPage />)} />
          <Route path="loans/:id/edit" element={guard('loans.create', <LoanWizardPage />)} />

          <Route path="agreements" element={guard('agreements.view', <AgreementsPage />)} />
          <Route path="agreements/templates" element={guard('templates.manage', <TemplatesPage />)} />
          <Route path="agreements/templates/:id" element={guard('templates.manage', <TemplateEditorPage />)} />

          <Route path="payments" element={guard('payments.view', <PaymentsPage />)} />
          <Route path="payments/:id" element={guard('payments.view', <PaymentDetailPage />)} />
          <Route path="due" element={guard('payments.view', <DuePaymentsPage />)} />
          <Route path="overdue" element={guard('payments.view', <OverdueLoansPage />)} />

          <Route path="products" element={guard(['products.manage', 'loans.create'], <ProductsPage />)} />
          <Route path="reports" element={guard('reports.view', <ReportsPage />)} />
          <Route path="users" element={guard('users.manage', <UsersPage />)} />
          <Route path="settings" element={guard(['settings.manage', 'templates.manage'], <SettingsPage />)} />
          <Route path="audit" element={guard('audit.view', <AuditLogsPage />)} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </Suspense>
  )
}
