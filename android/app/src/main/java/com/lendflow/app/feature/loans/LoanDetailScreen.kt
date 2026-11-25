package com.lendflow.app.feature.loans

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.AuthState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.BorrowersService
import com.lendflow.app.data.DataBus
import com.lendflow.app.data.LoansService
import com.lendflow.app.data.PaymentsService
import com.lendflow.app.data.Row
import com.lendflow.app.data.bool
import com.lendflow.app.data.has
import com.lendflow.app.data.int
import com.lendflow.app.data.num
import com.lendflow.app.data.obj
import com.lendflow.app.data.objOrFirst
import com.lendflow.app.data.objs
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.strings
import com.lendflow.app.feature.agreements.AgreementPanel
import com.lendflow.app.feature.payments.RecordPaymentDialog
import com.lendflow.app.ui.components.Align
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CellRole
import com.lendflow.app.ui.components.CellText
import com.lendflow.app.ui.components.Col
import com.lendflow.app.ui.components.ConfirmDialog
import com.lendflow.app.ui.components.DataTable
import com.lendflow.app.ui.components.DateField
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.InstallmentStatusBadge
import com.lendflow.app.ui.components.KV
import com.lendflow.app.ui.components.KeyValue
import com.lendflow.app.ui.components.LoanStatusBadge
import com.lendflow.app.ui.components.MoneyInput
import com.lendflow.app.ui.components.PageLoader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.components.StatCard
import com.lendflow.app.ui.components.StatTone
import com.lendflow.app.ui.components.Tabs
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.callNumber
import com.lendflow.app.ui.components.card
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberPdfViewer
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.METHOD_LABEL
import com.lendflow.app.util.Opt
import com.lendflow.app.util.Tone
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.fmtDateTime
import com.lendflow.app.util.peso
import com.lendflow.app.util.phMobile
import com.lendflow.app.util.timeAgo
import com.lendflow.app.util.titleCase

private val PRE_ACTIVE = listOf("draft", "pending_review", "approved", "awaiting_borrower_signature", "awaiting_lender_signature", "fully_signed", "ready_for_release")
private val EDITABLE = listOf("draft", "pending_review", "approved", "awaiting_borrower_signature", "awaiting_lender_signature")
private val APPROVABLE = listOf("pending_review", "awaiting_borrower_signature", "awaiting_lender_signature", "fully_signed")
private val SIGNING = listOf("awaiting_borrower_signature", "awaiting_lender_signature")

private val DETAIL_TABS = listOf(
    Opt("overview", "Overview"),
    Opt("schedule", "Repayment schedule"),
    Opt("payments", "Payment history"),
    Opt("agreement", "Agreement documents"),
    Opt("borrower", "Borrower"),
    Opt("notes", "Notes & activities"),
    Opt("audit", "Audit history"),
)

/** tab: e.g. "agreement" to open the agreement tab */
@Composable
fun LoanDetailScreen(id: String, tab: String?) {
    val auth by AppState.state.collectAsState()
    val router = LocalRouter.current
    var currentTab by remember(id, tab) { mutableStateOf(tab ?: "overview") }
    var dlg by remember(id) { mutableStateOf<String?>(null) }
    var payInstallment by remember(id) { mutableStateOf<String?>(null) }
    val loanQ = rememberLoad(id) { LoansService.get(id) }
    val submitRunner = rememberRunner()
    val loan = loanQ.data
    if (loan == null) {
        if (loanQ.error != null) ErrorState(loanQ.error, loanQ.reload) else PageLoader()
        return
    }
    val refresh: () -> Unit = { DataBus.bump(0) }

    val s = loan.str("status")
    val isOpen = s == "active" || s == "defaulted"
    val preActive = s in PRE_ACTIVE
    // simplified loans are never edited after creation (enforced in the database too)
    val editable = loan.str("lending_mode") != "simplified" && s in EDITABLE
    val canApprove = auth.can("loans.approve") && !loan.has("approved_at") && s in APPROVABLE
    val submit = { submitRunner.run(success = "Submitted for review") { LoansService.submit(id) } }
    val openPay = { payInstallment = null; dlg = "pay" }

    Column {
        LoanHeader(loan, onBack = { if (!router.back()) router.go("/loans") }, onBorrower = { router.go("/borrowers/${loan.str("borrower_id")}") }) {
            // primary action
            when {
                s == "draft" && auth.can("loans.create") ->
                    Btn("Submit for review", { submit() }, icon = Icons.AutoMirrored.Filled.Send, loading = submitRunner.busy)
                canApprove -> Btn("Approve", { dlg = "approve" }, icon = Icons.Default.Verified)
                s == "ready_for_release" && auth.can("loans.release") -> Btn("Release funds", { dlg = "release" }, icon = Icons.Default.Payments)
                s in SIGNING -> Btn("Signing", { currentTab = "agreement" }, icon = Icons.Default.Draw)
                isOpen && auth.can("payments.record") -> Btn("Record payment", { openPay() }, icon = Icons.Default.AccountBalanceWallet)
            }
            ActionsMenu(
                buildList {
                    if (isOpen && auth.can("payments.record")) add(MenuAction("Record payment", Icons.Default.AccountBalanceWallet) { openPay() })
                    if (isOpen && auth.can("payments.record")) add(MenuAction("Early settlement", Icons.Default.VolunteerActivism) { dlg = "settle" })
                    add(MenuAction("View agreement", Icons.Default.Draw) { currentTab = "agreement" })
                    if (editable && auth.can("loans.create")) add(MenuAction("Edit loan terms", Icons.Default.Edit) { router.go("/loans/$id/edit") })
                    if (s == "draft" && auth.can("loans.create")) add(MenuAction("Submit for review", Icons.AutoMirrored.Filled.Send) { submit() })
                    if (canApprove) add(MenuAction("Approve", Icons.Default.Verified) { dlg = "approve" })
                    if (s == "ready_for_release" && auth.can("loans.release")) add(MenuAction("Release funds", Icons.Default.Payments) { dlg = "release" })
                    if (isOpen && auth.can("loans.manage")) add(MenuAction("Reschedule (amendment)", Icons.Default.EditCalendar) { dlg = "reschedule" })
                    if (isOpen && auth.can("payments.adjust")) add(MenuAction("Waiver / adjustment", Icons.Default.Checklist) { dlg = "adjust" })
                    if (auth.can("collections.assign")) add(MenuAction("Assign staff", Icons.Default.ManageAccounts) { dlg = "assign" })
                    add(MenuAction.Separator)
                    if (s == "active" && auth.can("loans.manage")) add(MenuAction("Mark as defaulted", Icons.Default.GppMaybe, danger = true) { dlg = "default" })
                    if (s == "defaulted" && auth.can("loans.manage")) add(MenuAction("Reinstate", Icons.Default.Restore) { dlg = "reinstate" })
                    if (isOpen && auth.can("loans.manage")) add(MenuAction("Close loan", Icons.Default.CheckCircle, enabled = loan.num("total_outstanding") <= 0) { dlg = "close" })
                    if (preActive && auth.can("loans.approve") && s != "draft") add(MenuAction("Reject", Icons.Default.HighlightOff, danger = true) { dlg = "reject" })
                    if (preActive && auth.can("loans.cancel")) add(MenuAction("Cancel loan", Icons.Default.Block, danger = true) { dlg = "cancel" })
                },
            )
        }

        WorkflowStrip(loan)

        Spacer(Modifier.height(20.dp))
        Tabs(DETAIL_TABS, currentTab, { currentTab = it })
        Spacer(Modifier.height(16.dp))
        when (currentTab) {
            "overview" -> OverviewTab(loan, onSettle = { dlg = "settle" })
            "schedule" -> ScheduleTab(loan, auth, onCollect = { iid -> payInstallment = iid; dlg = "pay" })
            "payments" -> PaymentsTab(loan, auth)
            "agreement" -> AgreementPanel(loan)
            "borrower" -> BorrowerTab(loan)
            "notes" -> NotesTab(loan)
            "audit" -> AuditTab(loan, auth)
        }
    }

    val close = { dlg = null }
    val num = loan.str("loan_number")
    when (dlg) {
        "approve" -> ApproveDialog(loan, close, refresh)
        "release" -> ReleaseDialog(loan, close, refresh)
        "assign" -> AssignStaffDialog(loan, close, refresh)
        "adjust" -> AdjustmentDialog(loan, close, refresh)
        "reschedule" -> RescheduleDialog(loan, close, refresh)
        "pay", "settle" -> RecordPaymentDialog(
            onDismiss = close, loanId = id, installmentId = payInstallment,
            defaultType = if (dlg == "settle") "settlement" else "regular", onRecorded = { refresh() },
        )
        "reject" -> ConfirmDialog(
            "Reject $num?", close, { r -> LoansService.reject(id, r) },
            message = "Any open agreement is voided and preserved in the audit trail.", confirmText = "Reject loan", destructive = true,
            reasonLabel = "Reason", reasonPlaceholder = "This will be recorded in the audit trail", successMessage = "Loan rejected",
        )
        "cancel" -> ConfirmDialog(
            "Cancel $num?", close, { r -> LoansService.cancel(id, r) },
            confirmText = "Cancel loan", destructive = true,
            reasonLabel = "Reason", reasonPlaceholder = "This will be recorded in the audit trail", successMessage = "Loan cancelled",
        )
        "default" -> ConfirmDialog(
            "Mark loan as defaulted?", close, { r -> LoansService.markDefault(id, r) },
            message = "Payments can still be recorded on defaulted loans.", confirmText = "Mark defaulted", destructive = true,
            reasonLabel = "Reason", reasonPlaceholder = "This will be recorded in the audit trail", successMessage = "Loan marked as defaulted",
        )
        "reinstate" -> ConfirmDialog(
            "Reinstate loan to active?", close, { r -> LoansService.reinstate(id, r) },
            confirmText = "Reinstate",
            reasonLabel = "Reason", reasonPlaceholder = "This will be recorded in the audit trail", successMessage = "Loan reinstated",
        )
        "close" -> ConfirmDialog(
            "Close this loan?", close, { LoansService.close(id) },
            message = "Only loans with a zero balance can be closed.", confirmText = "Close loan", successMessage = "Loan closed",
        )
    }
}

// ---------------------------------------------------------------------------
// Header + actions menu
// ---------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoanHeader(loan: Row, onBack: () -> Unit, onBorrower: () -> Unit, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onBack).padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(14.dp), tint = LF.MutedForeground)
            Spacer(Modifier.width(4.dp))
            Text("Back", color = LF.MutedForeground, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(loan.str("loan_number"), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.headlineSmall, color = LF.Foreground)
            LoanStatusBadge(loan.strOrNull("status"), Modifier.align(Alignment.CenterVertically))
            if (loan.str("lending_mode") == "simplified") Pill("Simplified", Tone.teal, dot = false, modifier = Modifier.align(Alignment.CenterVertically))
        }
        Spacer(Modifier.height(4.dp))
        val desc = buildAnnotatedString {
            withLink(LinkAnnotation.Clickable("borrower", TextLinkStyles(SpanStyle(fontWeight = FontWeight.Medium, color = LF.Foreground))) { onBorrower() }) {
                append(loan.str("borrower_name"))
            }
            append(" · ${loan.str("borrower_code")} · ${peso(loan.num("principal"))} · ${METHOD_LABEL[loan.str("interest_method")] ?: loan.str("interest_method")} ${rateLabel(loan.num("interest_rate"), loan.str("rate_period"))}")
        }
        Text(desc, fontSize = 14.sp, color = LF.MutedForeground)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

private class MenuAction(
    val label: String,
    val icon: ImageVector?,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val separator: Boolean = false,
    val onClick: () -> Unit,
) {
    companion object { val Separator = MenuAction("", null, separator = true) {} }
}

@Composable
private fun ActionsMenu(items: List<MenuAction>) {
    var open by remember { mutableStateOf(false) }
    // no leading / trailing / doubled separators
    val visible = items.filterIndexed { i, it -> !it.separator || (i > 0 && i < items.lastIndex && !items[i - 1].separator) }
        .let { l -> if (l.lastOrNull()?.separator == true) l.dropLast(1) else l }
    Box {
        Btn("Actions", { open = true }, variant = BtnVariant.Outline, icon = Icons.Default.MoreHoriz)
        DropdownMenu(open, { open = false }, Modifier.background(Color.White).width(240.dp)) {
            visible.forEach { a ->
                if (a.separator) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = LF.Border)
                else DropdownMenuItem(
                    text = { Text(a.label, fontSize = 14.sp, color = if (!a.enabled) LF.Slate400 else if (a.danger) LF.Red600 else LF.Foreground) },
                    leadingIcon = a.icon?.let { ic -> { Icon(ic, null, Modifier.size(18.dp), tint = if (!a.enabled) LF.Slate400 else if (a.danger) LF.Red600 else LF.MutedForeground) } },
                    enabled = a.enabled,
                    onClick = { open = false; a.onClick() },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Workflow strip
// ---------------------------------------------------------------------------
@Composable
private fun WorkflowStrip(loan: Row) {
    val s = loan.str("status")
    if (s == "rejected" || s == "cancelled") {
        LnTinted(LF.Red50, border = LF.Red200, padding = 16.dp) {
            Column {
                Text(
                    "${if (s == "rejected") "Rejected" else "Cancelled"} ${fmtDateTime(loan.strOrNull("rejected_at") ?: loan.strOrNull("cancelled_at"))}",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LF.Red800,
                )
                Text(loan.strOrNull("rejection_reason") ?: loan.strOrNull("cancellation_reason") ?: "", fontSize = 14.sp, color = LF.Red800)
            }
        }
        return
    }
    val signed = s in listOf("fully_signed", "ready_for_release", "active", "completed", "defaulted")
    data class Step(val label: String, val done: Boolean, val at: String?)
    val steps = listOf(
        Step("Application", loan.has("submitted_at") || s != "draft", loan.strOrNull("submitted_at") ?: loan.strOrNull("created_at")),
        Step("Approval", loan.has("approved_at"), loan.strOrNull("approved_at")),
        Step("Signatures", signed, null),
        Step("Released", loan.has("released_at"), loan.strOrNull("released_at")),
        Step(if (s == "defaulted") "Defaulted" else "Completed", s == "completed", loan.strOrNull("completed_at")),
    )
    val compact = widthClass() == WidthClass.Compact
    Row(
        Modifier.fillMaxWidth().card().padding(if (compact) 12.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp),
    ) {
        steps.forEachIndexed { i, st ->
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (st.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                        Modifier.size(16.dp), tint = if (st.done) LF.Green600 else LF.Slate200,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        st.label, fontSize = if (compact) 11.sp else 14.sp, fontWeight = FontWeight.Medium,
                        color = if (st.done) LF.Foreground else LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!compact) {
                    val sub = if (st.at != null) fmtDate(st.at) else if (i == 2 && loan.has("agreement_status")) titleCase(loan.strOrNull("agreement_status")) else " "
                    Text(sub, Modifier.padding(start = 21.dp, top = 2.dp), fontSize = 11.sp, color = LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Overview
// ---------------------------------------------------------------------------
@Composable
private fun OverviewTab(loan: Row, onSettle: () -> Unit) {
    val s = loan.str("status")
    val isOpen = s == "active" || s == "defaulted"
    val calc = com.lendflow.app.data.jsonOf("summary" to (loan.obj("calc_summary") ?: com.lendflow.app.data.EmptyRow), "schedule" to emptyList<Any>(), "disclosures" to emptyList<Any>(), "warnings" to emptyList<Any>())
    val wc = widthClass()
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        RenewalPanel(loan)
        if (isOpen || s == "completed") {
            val od = loan.num("overdue_amount")
            val cards: List<@Composable () -> Unit> = listOf(
                { StatCard("Remaining payable", peso(loan.num("total_outstanding")), tone = StatTone.navy, hint = if (od > 0) "${peso(od)} overdue" else "Nothing overdue") },
                { StatCard("Outstanding principal", peso(loan.num("outstanding_principal")), tone = StatTone.blue, hint = "Paid ${peso(loan.num("principal_paid"))}") },
                { StatCard("Outstanding interest", peso(loan.num("outstanding_interest")), tone = StatTone.teal, hint = "Collected ${peso(loan.num("interest_paid"))}") },
                { StatCard("Charges / penalties", peso(loan.num("outstanding_charges") + loan.num("outstanding_penalties")), tone = StatTone.amber, hint = "Penalties assessed ${peso(loan.num("penalties_assessed"))}") },
                { StatCard("Total paid", peso(loan.num("total_paid")), tone = StatTone.green, hint = "${loan.int("paid_installments")}/${loan.int("total_installments")} installments paid") },
            )
            LnEqualGrid(cards.size, if (wc == WidthClass.Expanded) 5 else 2) { i -> cards[i]() }
        }
        val terms: @Composable () -> Unit = {
            Panel(title = "Loan terms", description = "Frozen contractual terms calculated by the server") {
                SummaryGrid(calc)
                loan.strOrNull("purpose")?.takeIf { it.isNotBlank() }?.let { p ->
                    Text(
                        buildAnnotatedString {
                            pushStyle(SpanStyle(color = LF.MutedForeground)); append("Purpose:"); pop()
                            append(" $p")
                        },
                        Modifier.padding(top = 16.dp), fontSize = 14.sp,
                    )
                }
                loan.strOrNull("notes")?.takeIf { it.isNotBlank() }?.let { n ->
                    LnTinted(LF.Slate50, Modifier.padding(top = 8.dp)) { Text(n, fontSize = 14.sp, color = LF.Slate700) }
                }
            }
        }
        val side: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (isOpen) {
                    Panel(title = "Next payment") {
                        if (loan.strOrNull("next_due_date") != null) {
                            Text(peso(loan.num("next_due_amount")), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            Text("Due ${fmtDate(loan.strOrNull("next_due_date"))}", fontSize = 14.sp, color = LF.MutedForeground)
                        } else {
                            Text(
                                if (loan.num("overdue_amount") > 0) "All remaining installments are overdue." else "No upcoming installments.",
                                fontSize = 14.sp, color = LF.MutedForeground,
                            )
                        }
                        if (loan.int("days_overdue") > 0) {
                            LnTinted(LF.Red50, Modifier.padding(top = 8.dp), padding = 8.dp) {
                                Text("Oldest overdue installment: ${loan.int("days_overdue")} days", fontSize = 12.sp, color = LF.Red700)
                            }
                        }
                    }
                    Panel(title = "Early settlement quote", actions = { Btn("Settle", onSettle, variant = BtnVariant.Outline, small = true) }) {
                        SettlementQuoteCard(loan)
                    }
                }
                Panel(title = "Assignment & dates") {
                    val penalty = if (loan.bool("penalty_enabled"))
                        "${lnJsNum(loan.num("penalty_rate"))}${if (loan.str("penalty_type") == "fixed") " PHP" else "%"} · ${titleCase(loan.strOrNull("penalty_type"))} · ${loan.str("penalty_grace_days")}-day grace"
                    else "No penalty"
                    KeyValue(
                        listOf(
                            KV("Loan officer", loan.strOrNull("loan_officer_name") ?: "—"),
                            KV("Collector", loan.strOrNull("collector_name") ?: "Unassigned"),
                            KV("Product", loan.strOrNull("product_name") ?: "Custom terms"),
                            KV(
                                "Release date",
                                if (loan.strOrNull("actual_release_date") != null) "${fmtDate(loan.strOrNull("actual_release_date"))} (${titleCase(loan.strOrNull("release_method") ?: "")})"
                                else "Planned ${fmtDate(loan.strOrNull("release_date"))}",
                            ),
                            KV("Maturity date", fmtDate(loan.strOrNull("maturity_date"))),
                            KV("Late payment", penalty),
                            KV("Allocation order", loan.strings("allocation_order").joinToString(" → ")),
                        ),
                        columns = 1,
                    )
                }
            }
        }
        if (wc == WidthClass.Expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { terms() }
                Box(Modifier.width(340.dp)) { side() }
            }
        } else {
            terms()
            side()
        }
    }
}

// ---------------------------------------------------------------------------
// Repayment schedule
// ---------------------------------------------------------------------------
@Composable
private fun ScheduleTab(loan: Row, auth: AuthState, onCollect: (String) -> Unit) {
    val id = loan.str("id")
    val active = loan.has("activated_at")
    if (!active) {
        val params = mapOf(
            "product_id" to loan.strOrNull("product_id"), "principal" to loan.num("principal"), "interest_method" to loan.str("interest_method"),
            "interest_rate" to loan.num("interest_rate"), "rate_period" to loan.str("rate_period"), "repayment_frequency" to loan.str("repayment_frequency"),
            "term_count" to loan.int("term_count"), "release_date" to loan.strOrNull("release_date"), "first_due_date" to loan.strOrNull("first_due_date"),
            "processing_fee_type" to loan.strOrNull("processing_fee_type"), "processing_fee_value" to loan.num("processing_fee_value"),
            "other_charges" to loan.num("other_charges"), "charges_treatment" to loan.strOrNull("charges_treatment"),
            "day_count" to loan.strOrNull("day_count"), "rounding" to loan.strOrNull("rounding"), "penalty_enabled" to false,
        )
        val proposed = rememberLoad(id, "proposed", params.toString()) { LoansService.preview(params) }
        Panel(title = "Proposed repayment schedule", description = "Becomes the official schedule when the loan is released.") {
            val d = proposed.data
            when {
                d != null -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ScheduleTable(d.objs("schedule"), maxHeight = 600.dp)
                    Disclosures(d)
                }
                proposed.error != null -> ErrorState(proposed.error)
                else -> Spinner()
            }
        }
        return
    }
    val inst = rememberLoad(id, "installments") { LoansService.installments(id) }
    val canCollect = auth.can("payments.record") && loan.str("status") in listOf("active", "defaulted")
    Panel(title = "Official repayment schedule", description = "Balances update automatically as payments are posted", contentPadding = 0.dp) {
        val rows = inst.data
        when {
            rows == null && inst.error != null -> Box(Modifier.padding(20.dp)) { ErrorState(inst.error, inst.reload) }
            rows == null -> Box(Modifier.padding(20.dp)) { Spinner() }
            else -> LnGrid(
                listOf(
                    LnCol("#", 44.dp), LnCol("Due date", 130.dp), LnCol("Principal", 100.dp, true), LnCol("Interest", 110.dp, true),
                    LnCol("Charges", 90.dp, true), LnCol("Penalty", 90.dp, true), LnCol("Scheduled", 100.dp, true), LnCol("Paid", 100.dp, true),
                    LnCol("Remaining", 110.dp, true), LnCol("Status", 130.dp), LnCol("", 100.dp, true),
                ),
                bordered = false,
            ) {
                rows.forEach { r ->
                    val st = r.str("status")
                    val days = r.int("days_overdue")
                    LnRow(
                        listOf(
                            LnCell(r.str("installment_no"), color = LF.MutedForeground),
                            LnCell(content = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(fmtDate(r.strOrNull("due_date")), fontSize = 14.sp)
                                    if (days > 0) Text(" (${days}d)", fontSize = 12.sp, color = LF.Red600)
                                }
                            }),
                            LnCell(peso(r.num("principal_due"))),
                            LnCell(peso(r.num("interest_due")), small = if (r.num("interest_waived") > 0) "−${peso(r.num("interest_waived"))} waived" else null, smallColor = LF.Green700),
                            LnCell(peso(r.num("charges_due"))),
                            LnCell(if (r.num("penalty_due") > 0) peso(r.num("penalty_due")) else "—"),
                            LnCell(peso(r.num("scheduled_amount"))),
                            LnCell(if (r.num("amount_paid") > 0) peso(r.num("amount_paid")) else "—", color = LF.Green700),
                            LnCell(if (r.num("amount_remaining") > 0 && st != "rescheduled") peso(r.num("amount_remaining")) else "—", bold = true),
                            LnCell(content = { InstallmentStatusBadge(r.strOrNull("display_status")) }),
                            LnCell(content = {
                                if (st in listOf("pending", "partially_paid") && canCollect) {
                                    Btn("Collect", { onCollect(r.str("id")) }, variant = BtnVariant.Outline, small = true)
                                }
                            }),
                        ),
                        bg = if (r.str("display_status") == "overdue") LF.Red50.copy(alpha = 0.4f) else Color.Transparent,
                        faded = st == "rescheduled",
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Payments + ledger
// ---------------------------------------------------------------------------
@Composable
private fun PaymentsTab(loan: Row, auth: AuthState) {
    val router = LocalRouter.current
    val id = loan.str("id")
    val pays = rememberLoad(id, "payments") { LoansService.payments(id) }
    val ledger = rememberLoad(id, "ledger") { LoansService.ledger(id) }
    var reverse by remember { mutableStateOf<String?>(null) }
    val pdf = rememberPdfViewer()
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Panel(title = "Payments", description = "Posted payments are permanent; corrections are made through reversals.") {
            if (pays.data == null && pays.error != null) ErrorState(pays.error, pays.reload)
            else DataTable(
                columns = listOf(
                    Col<Row>("Date", 0.9f, mobile = CellRole.Subtitle, minWidth = 100.dp) { p -> CellText(fmtDate(p.strOrNull("payment_date"))) },
                    Col("Payment #", 1f, mobile = CellRole.Title, minWidth = 120.dp) { p -> CellText(p.str("payment_number"), bold = true) },
                    Col("Method", 1.1f, mobile = CellRole.Meta, minWidth = 120.dp) { p ->
                        CellText(titleCase(p.strOrNull("method")) + (p.strOrNull("reference_no")?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
                    },
                    Col("Principal / Interest / Other", 1.6f, mobile = CellRole.Hidden, minWidth = 200.dp) { p ->
                        Text(
                            "${peso(p.num("principal_applied"))} / ${peso(p.num("interest_applied"))} / ${peso(p.num("charges_applied") + p.num("penalty_applied"))}",
                            fontSize = 12.sp, color = LF.MutedForeground,
                        )
                    },
                    Col("Amount", 1f, Align.End, mobile = CellRole.Value, minWidth = 110.dp) { p ->
                        val rev = p.str("status") == "reversed"
                        Text(
                            peso(p.num("amount")), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
                            color = if (rev) LF.MutedForeground else LF.Foreground, textDecoration = if (rev) TextDecoration.LineThrough else null,
                        )
                    },
                    Col("Status", 0.9f, mobile = CellRole.Meta, minWidth = 100.dp) { p ->
                        when {
                            p.str("status") == "reversed" -> Pill("Reversed", Tone.red)
                            p.str("payment_type") == "settlement" -> Pill("Settlement", Tone.teal)
                            else -> Pill("Posted", Tone.green)
                        }
                    },
                    Col("", 1.3f, Align.End, mobile = CellRole.Meta, minWidth = 190.dp) { p ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Btn("Receipt", {
                                pdf.openUrl("Receipt ${p.str("payment_number")}", "receipt-${p.str("payment_number")}.pdf") {
                                    PaymentsService.receiptPdf(p.str("id")).str("url")
                                }
                            }, variant = BtnVariant.Ghost, icon = Icons.Default.Receipt, small = true)
                            if (p.str("status") == "posted" && auth.can("payments.reverse")) {
                                ReverseButton { reverse = p.str("id") }
                            }
                        }
                    },
                ),
                rows = pays.data,
                loading = pays.loading,
                onRowClick = { p -> router.go("/payments/${p.str("id")}") },
                empty = { EmptyState("No payments recorded yet", icon = Icons.Default.Receipt) },
            )
        }
        Panel(title = "Financial ledger", description = "Every balance change is recorded here permanently (running balances after each entry)", contentPadding = 0.dp) {
            val entries = ledger.data
            when {
                entries == null && ledger.error != null -> Box(Modifier.padding(20.dp)) { ErrorState(ledger.error, ledger.reload) }
                entries == null -> Box(Modifier.padding(20.dp)) { Spinner() }
                entries.isEmpty() -> EmptyState("No ledger entries yet", description = "Entries appear when the loan is released.")
                else -> LnGrid(
                    listOf(
                        LnCol("Date", 110.dp), LnCol("Entry", 140.dp), LnCol("Description", 220.dp), LnCol("Cash", 100.dp, true),
                        LnCol("Principal", 100.dp, true), LnCol("Interest", 100.dp, true), LnCol("Charges", 90.dp, true),
                        LnCol("Penalty", 90.dp, true), LnCol("Balance", 110.dp, true),
                    ),
                    bordered = false,
                ) {
                    entries.forEach { e ->
                        val cash = e.num("cash_delta")
                        fun delta(k: String): LnCell {
                            val d = e.num(k)
                            return LnCell(if (d != 0.0) (if (d > 0) "+" else "") + peso(d) else "—", color = LF.MutedForeground)
                        }
                        LnRow(listOf(
                            LnCell(fmtDate(e.strOrNull("entry_date"))),
                            LnCell(content = {
                                Text(
                                    titleCase(e.strOrNull("entry_type")),
                                    Modifier.clip(RoundedCornerShape(4.dp)).background(LF.Slate100).padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                )
                            }),
                            LnCell(content = {
                                Text(e.str("description"), fontSize = 14.sp, color = LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }),
                            LnCell(
                                if (cash != 0.0) peso(cash) else "—",
                                color = if (cash < 0) LF.Red600 else if (cash > 0) LF.Green700 else LF.MutedForeground,
                            ),
                            delta("principal_delta"), delta("interest_delta"), delta("charges_delta"), delta("penalty_delta"),
                            LnCell(peso(e.num("balance_total")), bold = true),
                        ))
                    }
                }
            }
        }
    }
    reverse?.let { pid ->
        ConfirmDialog(
            "Reverse this payment?", { reverse = null }, { r -> PaymentsService.reverse(pid, r) },
            message = "A reversal entry restores the balances and voids the receipt. Only the most recent payment on a loan can be reversed.",
            confirmText = "Reverse payment", destructive = true, reasonLabel = "Reason",
            reasonPlaceholder = "This will be recorded in the audit trail", successMessage = "Payment reversed",
        )
    }
}

@Composable
private fun ReverseButton(onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(16.dp), tint = LF.Red600)
        Spacer(Modifier.width(6.dp))
        Text("Reverse", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LF.Red600)
    }
}

// ---------------------------------------------------------------------------
// Borrower
// ---------------------------------------------------------------------------
@Composable
private fun BorrowerTab(loan: Row) {
    val router = LocalRouter.current
    val context = LocalContext.current
    val b = rememberLoad(loan.str("borrower_id"), "borrower") { BorrowersService.get(loan.str("borrower_id")) }
    val x = b.data
    if (x == null) {
        if (b.error != null) ErrorState(b.error, b.reload) else Spinner()
        return
    }
    Panel(
        title = x.str("full_name"), description = x.str("borrower_code"),
        actions = { Btn("Open profile", { router.go("/borrowers/${x.str("id")}") }, variant = BtnVariant.Outline, small = true) },
    ) {
        val address = listOf("address_line", "barangay", "city_municipality", "province").mapNotNull { x.strOrNull(it)?.takeIf { v -> v.isNotBlank() } }.joinToString(", ")
        val occupation = listOf("occupation", "employer_name").mapNotNull { x.strOrNull(it)?.takeIf { v -> v.isNotBlank() } }.joinToString(" · ")
        val items: List<Pair<String, @Composable () -> Unit>> = listOf(
            "Mobile" to {
                Row(Modifier.clip(RoundedCornerShape(4.dp)).clickable { callNumber(context, x.strOrNull("mobile_number")) }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Phone, null, Modifier.size(14.dp), tint = LF.Blue600)
                    Spacer(Modifier.width(4.dp))
                    Text(phMobile(x.strOrNull("mobile_number")), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Blue600)
                }
            },
            "Email" to { KVText(x.strOrNull("email") ?: "—") },
            "Date of birth" to { KVText(fmtDate(x.strOrNull("date_of_birth"))) },
            "Address" to { KVText(address.ifBlank { "—" }) },
            "Occupation" to { KVText(occupation.ifBlank { "—" }) },
            "Monthly income" to { KVText(if (x.num("monthly_income") != 0.0) peso(x.num("monthly_income")) else "—") },
            "Emergency contact" to {
                KVText(
                    x.strOrNull("emergency_contact_name")?.takeIf { it.isNotBlank() }?.let {
                        "$it (${x.strOrNull("emergency_contact_relationship") ?: "—"}) ${phMobile(x.strOrNull("emergency_contact_number"))}"
                    } ?: "—",
                )
            },
            "Risk rating" to { KVText(titleCase(x.strOrNull("risk_rating"))) },
            "Status" to { KVText(titleCase(x.strOrNull("status"))) },
        )
        val cols = when (widthClass()) { WidthClass.Compact -> 1; WidthClass.Medium -> 2; WidthClass.Expanded -> 3 }
        LnEqualGrid(items.size, cols, hGap = 16.dp) { i ->
            Column {
                Text(items[i].first, fontSize = 12.sp, color = LF.MutedForeground)
                Box(Modifier.padding(top = 2.dp)) { items[i].second() }
            }
        }
    }
}

@Composable
private fun KVText(text: String) {
    Text(text.ifBlank { "—" }, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Foreground)
}

// ---------------------------------------------------------------------------
// Notes & activities
// ---------------------------------------------------------------------------
private val NOTE_TYPES = listOf(Opt("note", "Note"), Opt("call", "Phone call"), Opt("visit", "Field visit"), Opt("promise_to_pay", "Promise to pay"))
private val NOTE_ICON = mapOf("note" to "📝", "call" to "📞", "visit" to "🏠", "promise_to_pay" to "🤝", "system" to "⚙️")

@Composable
private fun NotesTab(loan: Row) {
    val id = loan.str("id")
    val notes = rememberLoad(id, "notes") { LoansService.notes(id) }
    var body by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("note") }
    var promiseDate by remember { mutableStateOf("") }
    var promiseAmount by remember { mutableStateOf("") }
    val runner = rememberRunner()
    val form: @Composable () -> Unit = {
        Panel(title = "Log activity") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectField(type, NOTE_TYPES, { type = it })
                if (type == "promise_to_pay") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { DateField(promiseDate, { promiseDate = it }, clearable = true) }
                        Box(Modifier.weight(1f)) { MoneyInput(promiseAmount, { promiseAmount = it }, placeholder = "Amount") }
                    }
                }
                TextInput(body, { body = it }, singleLine = false, minLines = 4, placeholder = "What happened?")
                Btn("Add to timeline", {
                    if (body.isBlank()) return@Btn
                    runner.run {
                        LoansService.addNote(
                            loanId = id, borrowerId = loan.strOrNull("borrower_id"), body = body, noteType = type,
                            promiseDate = if (type == "promise_to_pay") promiseDate.ifBlank { null } else null,
                            promiseAmount = if (type == "promise_to_pay" && promiseAmount.isNotBlank()) promiseAmount.toDoubleOrNull() else null,
                        )
                        body = ""; promiseAmount = ""; promiseDate = ""
                    }
                }, Modifier.fillMaxWidth(), loading = runner.busy, enabled = body.isNotBlank())
            }
        }
    }
    val timeline: @Composable () -> Unit = {
        Panel(title = "Timeline") {
            val list = notes.data
            when {
                list == null && notes.error != null -> ErrorState(notes.error, notes.reload)
                list == null -> Spinner()
                list.isEmpty() -> EmptyState("No activity yet")
                else -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    list.forEach { n ->
                        val nt = n.str("note_type")
                        Row {
                            Box(Modifier.size(32.dp).clip(CircleShape).background(LF.Slate100), contentAlignment = Alignment.Center) {
                                Text(NOTE_ICON[nt] ?: "", fontSize = 14.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(n.str("body"), fontSize = 14.sp)
                                if (nt == "promise_to_pay" && (n.has("promise_date") || n.has("promise_amount"))) {
                                    val amt = if (n.num("promise_amount") != 0.0) peso(n.num("promise_amount")) else ""
                                    val by = if (n.has("promise_date")) "by ${fmtDate(n.strOrNull("promise_date"))}" else ""
                                    Text("Promised $amt $by", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.Blue700)
                                }
                                Text(
                                    "${if (nt == "system") "System" else n.objOrFirst("author").strOrNull("full_name") ?: "Staff"} · ${timeAgo(n.strOrNull("created_at"))}",
                                    fontSize = 12.sp, color = LF.MutedForeground,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (widthClass() == WidthClass.Expanded) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(360.dp)) { form() }
            Box(Modifier.weight(1f)) { timeline() }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { form(); timeline() }
    }
}

// ---------------------------------------------------------------------------
// Audit history: frozen term snapshots + change history
// ---------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuditTab(loan: Row, auth: AuthState) {
    val id = loan.str("id")
    val canAudit = auth.can("audit.view")
    val snaps = rememberLoad(id, "snapshots") { LoansService.snapshots(id) }
    val audit = rememberLoad(id, "audit", canAudit, enabled = canAudit) { LoansService.auditHistory(id) }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Panel(title = "Frozen terms snapshots", description = "Each agreement and amendment references an immutable snapshot of the loan terms") {
            val list = snaps.data
            when {
                list == null && snaps.error != null -> ErrorState(snaps.error, snaps.reload)
                list == null -> Spinner()
                list.isEmpty() -> Text("No snapshot yet — one is created when the agreement is generated.", fontSize = 14.sp, color = LF.MutedForeground)
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { sn ->
                        val sum = sn.obj("summary")
                        val shape = RoundedCornerShape(8.dp)
                        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, LF.Border, shape).padding(12.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Version ${sn.str("version")}", Modifier.align(Alignment.CenterVertically), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Pill(titleCase(sn.strOrNull("kind")), if (sn.str("kind") == "amendment") Tone.violet else Tone.blue, modifier = Modifier.align(Alignment.CenterVertically))
                                Text(fmtDateTime(sn.strOrNull("created_at")), Modifier.align(Alignment.CenterVertically), fontSize = 12.sp, color = LF.MutedForeground)
                                if (loan.strOrNull("terms_snapshot_id") == sn.str("id")) Pill("Official", Tone.green, modifier = Modifier.align(Alignment.CenterVertically))
                            }
                            Text(
                                "${peso(sum.num("principal"))} · ${sum.str("duration_label")} · total ${peso(sum.num("total_repayment"))}" +
                                    (sn.strOrNull("reason")?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                Modifier.padding(top = 4.dp), fontSize = 12.sp, color = LF.MutedForeground,
                            )
                            Text("SHA-256 ${sn.str("terms_hash")}", Modifier.padding(top = 4.dp), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = LF.Slate500)
                        }
                    }
                }
            }
        }
        if (canAudit) {
            Panel(title = "Change history") {
                val list = audit.data
                when {
                    list == null && audit.error != null -> ErrorState(audit.error, audit.reload)
                    list == null -> Spinner()
                    list.isEmpty() -> Text("No changes recorded.", fontSize = 14.sp, color = LF.MutedForeground)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        list.forEach { a ->
                            val fields = a.strings("changed_fields")
                            Column {
                                Text(
                                    buildAnnotatedString {
                                        pushStyle(SpanStyle(fontWeight = FontWeight.Medium)); append(a.strOrNull("actor_name") ?: "System"); pop()
                                        pushStyle(SpanStyle(color = LF.MutedForeground))
                                        append(" ${a.str("action")}d ")
                                        if (fields.isNotEmpty()) append("— ${fields.take(8).joinToString(", ") { titleCase(it) }}${if (fields.size > 8) "…" else ""}")
                                        pop()
                                    },
                                    fontSize = 14.sp,
                                )
                                val newStatus = a.obj("new_data").strOrNull("status")
                                val oldStatus = a.obj("old_data").strOrNull("status")
                                Text(
                                    fmtDateTime(a.strOrNull("created_at")) + (if (newStatus != null && oldStatus != newStatus) " · status → ${titleCase(newStatus)}" else ""),
                                    fontSize = 12.sp, color = LF.MutedForeground,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

