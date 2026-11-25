package com.lendflow.app.feature.loans

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalNewLoan
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.LOAN_PIPELINE
import com.lendflow.app.data.LoansService
import com.lendflow.app.data.Row
import com.lendflow.app.data.num
import com.lendflow.app.data.obj
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.ui.components.Align
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.CellRole
import com.lendflow.app.ui.components.CellText
import com.lendflow.app.ui.components.Col
import com.lendflow.app.ui.components.DataTable
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.LoanStatusBadge
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.Pagination
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.StatCard
import com.lendflow.app.ui.components.StatTone
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.rememberDebounced
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.util.Opt
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.peso
import com.lendflow.app.util.timeAgo

private class AppTab(val value: String, val label: String, val statuses: List<String>)

private val APP_TABS = listOf(
    AppTab("pipeline", "In progress", LOAN_PIPELINE),
    AppTab("review", "Pending review", listOf("pending_review")),
    AppTab("approved", "Approved", listOf("approved")),
    AppTab("signing", "Awaiting signature", listOf("awaiting_borrower_signature", "awaiting_lender_signature")),
    AppTab("release", "Ready for release", listOf("fully_signed", "ready_for_release")),
    AppTab("draft", "Drafts", listOf("draft")),
    AppTab("closed", "Rejected / cancelled", listOf("rejected", "cancelled")),
)

@Composable
fun LoanApplicationsScreen(tab: String?) {
    val auth by AppState.state.collectAsState()
    val router = LocalRouter.current
    val newLoan = LocalNewLoan.current
    var tabValue by remember(tab) { mutableStateOf(APP_TABS.firstOrNull { it.value == tab }?.value ?: APP_TABS[0].value) }
    val current = APP_TABS.first { it.value == tabValue }
    var search by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    val term = rememberDebounced(search)
    val q = rememberLoad("applications", current.value, term, page) { LoansService.list(statuses = current.statuses, search = term, page = page) }
    val data = lnRememberLastPage(q.data)
    val counts = rememberLoad("applications-counts") {
        val r = LoansService.list(statuses = LOAN_PIPELINE, pageSize = 500)
        r.rows.groupingBy { it.str("status") }.eachCount()
    }
    val c = counts.data ?: emptyMap()
    val select: (String) -> Unit = { tabValue = it; page = 0 }

    Column {
        PageHeader(
            "Loan Applications", "Applications move through review, approval and signing before funds are released.",
            actions = if (auth.can("loans.create")) ({ Btn("New loan", { newLoan.open() }, icon = Icons.Default.AddCircle) }) else null,
        )
        val cards: List<@Composable (Modifier) -> Unit> = listOf(
            { m -> StatCard("Pending review", "${c["pending_review"] ?: 0}", m, tone = StatTone.amber, onClick = { select("review") }) },
            { m -> StatCard("Approved", "${c["approved"] ?: 0}", m, tone = StatTone.blue, onClick = { select("approved") }) },
            { m -> StatCard("Awaiting signature", "${(c["awaiting_borrower_signature"] ?: 0) + (c["awaiting_lender_signature"] ?: 0)}", m, tone = StatTone.navy, onClick = { select("signing") }) },
            { m -> StatCard("Ready for release", "${(c["ready_for_release"] ?: 0) + (c["fully_signed"] ?: 0)}", m, tone = StatTone.teal, onClick = { select("release") }) },
        )
        LnEqualGrid(cards.size, if (widthClass() == WidthClass.Expanded) 4 else 2, Modifier.padding(bottom = 20.dp)) { i -> cards[i](Modifier) }
        Panel(contentPadding = if (widthClass() == WidthClass.Compact) 12.dp else 16.dp) {
            LnListToolbar(APP_TABS.map { Opt(it.value, it.label) }, current.value, select, search) { search = it; page = 0 }
            if (q.error != null && data == null) {
                ErrorState(q.error, q.reload)
            } else {
                DataTable(
                    columns = listOf(
                        Col<Row>("Loan", 1f, mobile = CellRole.Title, minWidth = 120.dp) { l -> CellText(l.str("loan_number"), bold = true) },
                        Col("Borrower", 1.3f, mobile = CellRole.Subtitle, minWidth = 150.dp) { l -> CellText(l.str("borrower_name")) },
                        Col("Amount", 1f, Align.End, mobile = CellRole.Value, minWidth = 110.dp) { l -> CellText(peso(l.num("principal")), bold = true, align = TextAlign.End) },
                        Col("Terms", 1.8f, mobile = CellRole.Meta, minWidth = 200.dp) { l ->
                            val label = l.obj("calc_summary").str("duration_label")
                            CellText("$label · ${l.str("interest_method")} · ${peso(l.num("installment_amount"))}/${l.str("repayment_frequency").replaceFirst("ly", "")}", muted = true)
                        },
                        Col("Officer", 1f, mobile = CellRole.Hidden, minWidth = 120.dp) { l -> CellText(l.strOrNull("loan_officer_name") ?: "—") },
                        Col("Approved", 1f, mobile = CellRole.Hidden, minWidth = 100.dp) { l ->
                            if (l.strOrNull("approved_at") != null) CellText(fmtDate(l.strOrNull("approved_at"))) else CellText("—", muted = true)
                        },
                        Col("Updated", 0.9f, mobile = CellRole.Meta, minWidth = 90.dp) { l -> CellText(timeAgo(l.strOrNull("updated_at")), muted = true) },
                        Col("Status", 1.2f, mobile = CellRole.Meta, minWidth = 130.dp) { l -> LoanStatusBadge(l.strOrNull("status")) },
                    ),
                    rows = data?.rows,
                    loading = q.loading,
                    onRowClick = { l -> router.go("/loans/${l.str("id")}") },
                    empty = { EmptyState("No applications here", description = "Create a loan to start a new application.", icon = Icons.AutoMirrored.Filled.Assignment) },
                )
                Pagination(page, 25, data?.count ?: 0, { page = it })
            }
        }
    }
}
