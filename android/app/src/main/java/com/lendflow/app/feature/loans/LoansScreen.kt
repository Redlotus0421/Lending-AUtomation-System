package com.lendflow.app.feature.loans

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalNewLoan
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.app.Route
import com.lendflow.app.data.LoansService
import com.lendflow.app.data.Row
import com.lendflow.app.data.Supa
import com.lendflow.app.data.int
import com.lendflow.app.data.num
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
import com.lendflow.app.ui.components.SearchField
import com.lendflow.app.ui.components.Tabs
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.rememberDebounced
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Opt
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.peso

private class LoanTab(val value: String, val label: String, val statuses: List<String>, val overdue: Boolean = false, val order: String? = null)

private val TABS = listOf(
    LoanTab("active", "Active", listOf("active"), order = "next_due"),
    LoanTab("overdue", "Overdue", listOf("active", "defaulted"), overdue = true, order = "overdue"),
    LoanTab("completed", "Completed", listOf("completed")),
    LoanTab("defaulted", "Defaulted", listOf("defaulted")),
    LoanTab("all", "All released", listOf("active", "completed", "defaulted")),
)

/** Keeps the previous page on screen while the next one loads (keepPreviousData). */
@Composable
internal fun lnRememberLastPage(page: Supa.Page?): Supa.Page? {
    var last by remember { mutableStateOf<Supa.Page?>(null) }
    LaunchedEffect(page) { if (page != null) last = page }
    return page ?: last
}

@Composable
fun LoansScreen(status: String?) {
    val auth by AppState.state.collectAsState()
    val router = LocalRouter.current
    val newLoan = LocalNewLoan.current
    // the web uses ?tab=…; the route table passes ?status=…, accept both
    val initial = status ?: Route(router.current).param("tab")
    var tabValue by remember(initial) { mutableStateOf(TABS.firstOrNull { it.value == initial }?.value ?: TABS[0].value) }
    val tab = TABS.first { it.value == tabValue }
    var search by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    val term = rememberDebounced(search)
    val q = rememberLoad(tab.value, term, page) {
        LoansService.list(statuses = tab.statuses, overdueOnly = tab.overdue, search = term, page = page, order = tab.order)
    }
    val data = lnRememberLastPage(q.data)
    val rows = data?.rows
    val totOut = rows?.sumOf { it.num("total_outstanding") } ?: 0.0
    val totOd = rows?.sumOf { it.num("overdue_amount") } ?: 0.0

    Column {
        PageHeader(
            "Active Loans", "Released loans with live balances, next due dates and overdue status.",
            actions = if (auth.can("loans.create")) ({ Btn("New loan", { newLoan.open() }, icon = Icons.Default.AddCircle) }) else null,
        )
        Panel(contentPadding = if (widthClass() == WidthClass.Compact) 12.dp else 16.dp) {
            LnListToolbar(
                tabs = TABS.map { Opt(it.value, it.label) }, selected = tab.value,
                onTab = { tabValue = it; page = 0 },
                search = search, onSearch = { search = it; page = 0 },
            )
            if (q.error != null && data == null) {
                ErrorState(q.error, q.reload)
            } else {
                DataTable(
                    columns = listOf(
                        Col<Row>("Loan", 1.2f, mobile = CellRole.Title, minWidth = 130.dp) { l ->
                            Column {
                                Text(l.str("loan_number"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(l.strOrNull("product_name") ?: "Custom", fontSize = 12.sp, color = LF.MutedForeground)
                            }
                        },
                        Col("Borrower", 1.4f, mobile = CellRole.Subtitle, minWidth = 150.dp) { l ->
                            Column {
                                Text(l.str("borrower_name"), fontSize = 14.sp)
                                Text(l.str("borrower_code"), fontSize = 12.sp, color = LF.MutedForeground)
                            }
                        },
                        Col("Principal", 1f, Align.End, mobile = CellRole.Hidden, minWidth = 110.dp) { l -> CellText(peso(l.num("principal")), align = TextAlign.End) },
                        Col("Outstanding", 1f, Align.End, mobile = CellRole.Value, minWidth = 110.dp) { l -> CellText(peso(l.num("total_outstanding")), bold = true, align = TextAlign.End) },
                        Col("Next due", 1f, mobile = CellRole.Meta, minWidth = 110.dp) { l ->
                            if (l.strOrNull("next_due_date") != null) Column {
                                Text(fmtDate(l.strOrNull("next_due_date")), fontSize = 14.sp)
                                Text(peso(l.num("next_due_amount")), fontSize = 12.sp, color = LF.MutedForeground)
                            } else CellText("—")
                        },
                        Col("Overdue", 1f, Align.End, mobile = CellRole.Meta, minWidth = 100.dp) { l ->
                            if (l.num("overdue_amount") > 0) Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(peso(l.num("overdue_amount")), fontSize = 14.sp, color = LF.Red600)
                                Text("${l.int("days_overdue")} days", fontSize = 12.sp, color = LF.Red600)
                            } else CellText("—", muted = true)
                        },
                        Col("Progress", 1f, mobile = CellRole.Hidden, minWidth = 110.dp) { l -> ProgressCell(l.int("paid_installments"), l.int("total_installments")) },
                        Col("Collector", 1f, mobile = CellRole.Hidden, minWidth = 110.dp) { l ->
                            CellText(l.strOrNull("collector_name") ?: "—", muted = l.strOrNull("collector_name") == null)
                        },
                        Col("Status", 1f, mobile = CellRole.Meta, minWidth = 110.dp) { l -> LoanStatusBadge(l.strOrNull("status")) },
                    ),
                    rows = rows,
                    loading = q.loading,
                    onRowClick = { l -> router.go("/loans/${l.str("id")}") },
                    empty = { EmptyState("No loans in this view", description = "Released loans appear here once funds are disbursed.", icon = Icons.Default.Payments) },
                )
                if (!rows.isNullOrEmpty()) {
                    Text(
                        buildAnnotatedString {
                            append("Page totals — outstanding ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = LF.Foreground)) { append(peso(totOut)) }
                            if (totOd > 0) {
                                append(" · overdue ")
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = LF.Red600)) { append(peso(totOd)) }
                            }
                        },
                        Modifier.fillMaxWidth().padding(top = 12.dp), fontSize = 12.sp, color = LF.MutedForeground, textAlign = TextAlign.End,
                    )
                }
                Pagination(page, 25, data?.count ?: 0, { page = it })
            }
        }
    }
}

@Composable
private fun ProgressCell(paid: Int, total: Int) {
    Column(Modifier.width(96.dp)) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(LF.Slate100)) {
            val f = if (total > 0) (paid.toFloat() / total).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(50)).background(LF.Teal))
        }
        Text("$paid/$total paid", Modifier.padding(top = 4.dp), fontSize = 11.sp, color = LF.MutedForeground)
    }
}

/** Tabs + search box above a list (stacked on phones, side by side on wider screens). */
@Composable
internal fun LnListToolbar(tabs: List<Opt>, selected: String, onTab: (String) -> Unit, search: String, onSearch: (String) -> Unit) {
    if (widthClass() == WidthClass.Compact) {
        Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Tabs(tabs, selected, onTab)
            SearchField(search, onSearch, placeholder = "Search loan #, borrower…")
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Tabs(tabs, selected, onTab) }
            Box(Modifier.width(288.dp)) { SearchField(search, onSearch, placeholder = "Search loan #, borrower…") }
        }
    }
}
