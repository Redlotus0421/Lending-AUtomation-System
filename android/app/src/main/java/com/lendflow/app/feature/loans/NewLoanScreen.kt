package com.lendflow.app.feature.loans

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.lendflow.app.app.AppState
import com.lendflow.app.data.OrgService
import com.lendflow.app.data.strOrNull
import com.lendflow.app.feature.loans.simplified.SimplifiedLoanScreen
import com.lendflow.app.ui.components.PageLoader
import com.lendflow.app.ui.components.rememberLoad

/**
 * /loans/new — the workflow is decided by the business's lending mode, which only the platform
 * administrator can change. Staff are never asked to choose between Complete and Simplified Lending.
 * The mode is re-read on entry so a change by the administrator applies without signing in again
 * (the database enforces the mode regardless). [nonce] restarts the page when a new pick is made in
 * the New loan popup while it is already open.
 */
@Composable
fun NewLoanScreen(type: String?, borrowerId: String?, nonce: String?) {
    val auth by AppState.state.collectAsState()
    val org = rememberLoad("organization-lending-mode", live = false) { OrgService.get() }
    if (org.loading && org.data == null && org.error == null) return PageLoader()
    val mode = org.data?.strOrNull("lending_mode") ?: auth.organization.strOrNull("lending_mode")
    key(nonce) {
        if (mode == "simplified") SimplifiedLoanScreen(type = type, borrowerId = borrowerId)
        else LoanWizardScreen(editId = null, borrowerId = borrowerId)
    }
}
