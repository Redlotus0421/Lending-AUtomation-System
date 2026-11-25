package com.lendflow.app.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.lendflow.app.feature.admin.AccountDetailScreen
import com.lendflow.app.feature.admin.AccountTemplateScreen
import com.lendflow.app.feature.admin.AccountsScreen
import com.lendflow.app.feature.admin.AdminOverviewScreen
import com.lendflow.app.feature.agreements.AgreementsScreen
import com.lendflow.app.feature.agreements.TemplateEditorScreen
import com.lendflow.app.feature.agreements.TemplatesScreen
import com.lendflow.app.feature.audit.AuditLogsScreen
import com.lendflow.app.feature.auth.ProfileScreen
import com.lendflow.app.feature.borrowers.BorrowerFormScreen
import com.lendflow.app.feature.borrowers.BorrowerProfileScreen
import com.lendflow.app.feature.borrowers.BorrowersScreen
import com.lendflow.app.feature.dashboard.DashboardScreen
import com.lendflow.app.feature.loans.LoanApplicationsScreen
import com.lendflow.app.feature.loans.LoanDetailScreen
import com.lendflow.app.feature.loans.LoanWizardScreen
import com.lendflow.app.feature.loans.LoansScreen
import com.lendflow.app.feature.loans.NewLoanScreen
import com.lendflow.app.feature.payments.DuePaymentsScreen
import com.lendflow.app.feature.payments.OverdueLoansScreen
import com.lendflow.app.feature.payments.PaymentDetailScreen
import com.lendflow.app.feature.payments.PaymentsScreen
import com.lendflow.app.feature.payments.RecordsScreen
import com.lendflow.app.feature.products.ProductsScreen
import com.lendflow.app.feature.reports.ReportsScreen
import com.lendflow.app.feature.settings.SettingsScreen
import com.lendflow.app.feature.users.UsersScreen
import com.lendflow.app.ui.components.AccessRestricted

/** Lender workspace routes — same paths and permission guards as src/App.tsx. */
@Composable
fun WorkspaceRoute(path: String, auth: AuthState, router: Router) {
    val r = Route(path)
    @Composable
    fun guard(vararg perms: String, content: @Composable () -> Unit) {
        if (auth.canAny(*perms)) content() else AccessRestricted { router.root("/") }
    }
    r.match("/")?.let { return guard("dashboard.view") { DashboardScreen() } }
    r.match("/profile")?.let { return ProfileScreen() }

    r.match("/borrowers")?.let { return guard("borrowers.view") { BorrowersScreen() } }
    r.match("/borrowers/new")?.let { return guard("borrowers.create") { BorrowerFormScreen(null) } }
    r.match("/borrowers/:id/edit")?.let { return guard("borrowers.edit") { BorrowerFormScreen(it.getValue("id")) } }
    r.match("/borrowers/:id")?.let { return guard("borrowers.view") { BorrowerProfileScreen(it.getValue("id")) } }

    r.match("/applications")?.let { return guard("loans.view") { LoanApplicationsScreen(r.param("tab")) } }
    r.match("/loans")?.let { return guard("loans.view") { LoansScreen(r.param("status")) } }
    r.match("/loans/new")?.let { return guard("loans.create") { NewLoanScreen(type = r.param("type"), borrowerId = r.param("borrower"), nonce = r.param("n")) } }
    r.match("/loans/:id/edit")?.let { return guard("loans.create") { LoanWizardScreen(editId = it.getValue("id"), borrowerId = null) } }
    r.match("/loans/:id")?.let { return guard("loans.view") { LoanDetailScreen(it.getValue("id"), r.param("tab")) } }

    r.match("/agreements")?.let { return guard("agreements.view") { AgreementsScreen() } }
    r.match("/agreements/templates")?.let { return guard("templates.manage") { TemplatesScreen() } }
    r.match("/agreements/templates/:id")?.let { return guard("templates.manage") { TemplateEditorScreen(it.getValue("id")) } }

    r.match("/payments")?.let { return guard("payments.view") { PaymentsScreen() } }
    r.match("/payments/:id")?.let { return guard("payments.view") { PaymentDetailScreen(it.getValue("id")) } }
    r.match("/due")?.let { return guard("payments.view") { DuePaymentsScreen(r.param("tab")) } }
    r.match("/overdue")?.let { return guard("payments.view") { OverdueLoansScreen() } }
    r.match("/records")?.let { return guard("reports.view") { RecordsScreen(borrowerId = r.param("borrower"), loanId = r.param("loan")) } }

    r.match("/products")?.let { return guard("products.manage", "loans.create") { ProductsScreen() } }
    r.match("/reports")?.let { return guard("reports.view") { ReportsScreen() } }
    r.match("/users")?.let { return guard("users.manage") { UsersScreen() } }
    r.match("/settings")?.let { return guard("settings.manage", "templates.manage") { SettingsScreen() } }
    r.match("/audit")?.let { return guard("audit.view") { AuditLogsScreen() } }

    // unknown path (or an /admin link while in a workspace): back to the dashboard
    LaunchedEffect(path) { router.replace("/") }
}

/** Platform super-admin console routes (src/App.tsx, the /admin pages). */
@Composable
fun AdminRoute(path: String, router: Router) {
    val r = Route(path)
    r.match("/admin")?.let { return AdminOverviewScreen() }
    r.match("/admin/accounts")?.let { return AccountsScreen(openNew = r.param("new") == "1") }
    r.match("/admin/accounts/:id")?.let { return AccountDetailScreen(it.getValue("id")) }
    r.match("/admin/accounts/:id/templates/:templateId")?.let { return AccountTemplateScreen(it.getValue("id"), it.getValue("templateId")) }
    r.match("/admin/profile")?.let { return ProfileScreen() }
    LaunchedEffect(path) { router.replace("/admin") }
}
