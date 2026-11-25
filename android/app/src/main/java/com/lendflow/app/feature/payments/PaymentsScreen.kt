package com.lendflow.app.feature.payments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.PaymentsService
import com.lendflow.app.data.Row
import com.lendflow.app.data.int
import com.lendflow.app.data.jsonOf
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
import com.lendflow.app.ui.components.DateRangeFilter
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.ExportMenu
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.Pagination
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.SearchField
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.SkeletonRows
import com.lendflow.app.ui.components.StatCard
import com.lendflow.app.ui.components.StatTone
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.makeRange
import com.lendflow.app.ui.components.rememberDebounced
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.ExportCol
import com.lendflow.app.util.Opt
import com.lendflow.app.util.PAYMENT_METHODS
import com.lendflow.app.util.SYSTEM_PAYMENT_METHODS
import com.lendflow.app.util.Tone
import com.lendflow.app.util.exportCsv
import com.lendflow.app.util.exportXlsx
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.peso
import com.lendflow.app.util.titleCase

// Port of src/features/payments/PaymentsPage.tsx

private const val PAGE_SIZE = 25

private val EXPORT_COLUMNS = listOf(
    ExportCol("payment_date", "Date", "date"),
    ExportCol("payment_number", "Payment #"),
    ExportCol("receipt_number", "Receipt #"),
    ExportCol("loan_number", "Loan #"),
    ExportCol("borrower_name", "Borrower"),
    ExportCol("method", "Method"),
    ExportCol("reference_no", "Reference"),
    ExportCol("principal_applied", "Principal", "money", true),
    ExportCol("interest_applied", "Interest", "money", true),
    ExportCol("charges_applied", "Charges", "money", true),
    ExportCol("penalty_applied", "Penalties", "money", true),
    ExportCol("amount", "Amount", "money", true),
    ExportCol("status", "Status"),
    ExportCol("received_by", "Received by"),
)

private fun exportRows(rows: List<Row>): List<Row> = rows.map { p ->
    val posted = p.str("status") == "posted"
    fun m(k: String) = if (posted) p.num(k) else 0.0
    jsonOf(
        "payment_date" to p.str("payment_date"),
        "payment_number" to p.str("payment_number"),
        "receipt_number" to (p.receiptOf().strOrNull("receipt_number") ?: ""),
        "loan_number" to (p.obj("loans").strOrNull("loan_number") ?: ""),
        "borrower_name" to (p.obj("borrowers").strOrNull("full_name") ?: ""),
        "method" to titleCase(p.str("method")),
        "reference_no" to (p.strOrNull("reference_no") ?: ""),
        "principal_applied" to m("principal_applied"),
        "interest_applied" to m("interest_applied"),
        "charges_applied" to m("charges_applied"),
        "penalty_applied" to m("penalty_applied"),
        "amount" to m("amount"),
        "status" to if (p.str("status") == "reversed") "Reversed" else "Posted",
        "received_by" to (p.obj("receiver").strOrNull("full_name") ?: ""),
    )
}

@Composable
fun PaymentsScreen() {
    val router = LocalRouter.current
    val ctx = LocalContext.current
    val auth by AppState.state.collectAsState()
    var recordOpen by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(makeRange("this_month")) }
    var method by remember { mutableStateOf("all") }
    var status by remember { mutableStateOf("all") }
    var collector by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    val term = rememberDebounced(search)
    val canAssign = auth.can("collections.assign")

    val summary = rememberLoad { PaymentsService.collectionsSummary() }
    val staff = rememberActiveStaff(canAssign)
    val list = rememberLoad(range, method, status, term, collector, page) {
        PaymentsService.list(range.from, range.to, method, status, term, collector.ifBlank { null }, page, PAGE_SIZE)
    }
    suspend fun fetchAll() = PaymentsService.list(range.from, range.to, method, status, term, collector.ifBlank { null }, 0, 5000).rows
    val fileBase = "payments-${range.from}-to-${range.to}"
    val s = summary.data

    val columns = listOf<Col<Row>>(
        Col("Date", 0.8f, mobile = CellRole.Meta) { p -> CellText(fmtDate(p.str("payment_date"))) },
        Col("Payment / receipt", 1.3f, mobile = CellRole.Subtitle, minWidth = 150.dp) { p ->
            val r = p.receiptOf()
            Text(
                buildString { append(p.str("payment_number")); r?.let { append(" · ${it.str("receipt_number")}") } },
                fontSize = 13.sp, color = LF.Foreground, maxLines = 2,
            )
        },
        Col("Borrower", 1.4f, mobile = CellRole.Title, minWidth = 150.dp) { p ->
            Column {
                Text(p.obj("borrowers").str("full_name"), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(p.obj("loans").str("loan_number"), fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1)
            }
        },
        Col("Method", 1f, mobile = CellRole.Meta) { p ->
            Column {
                Text(titleCase(p.str("method")), fontSize = 14.sp)
                p.strOrNull("reference_no")?.ifBlank { null }?.let { Text(it, fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1) }
            }
        },
        Col("Type", 0.8f, mobile = CellRole.Hidden) { p ->
            if (p.str("payment_type") == "settlement") Pill("Settlement", Tone.teal) else CellText("Regular", muted = true)
        },
        Col("Received by", 1f, mobile = CellRole.Hidden) { p -> CellText(p.obj("receiver").strOrNull("full_name") ?: "—") },
        Col("Status", 0.8f, mobile = CellRole.Meta) { p -> PostedPill(p.str("status")) },
        Col("Amount", 0.9f, Align.End, mobile = CellRole.Value) { p ->
            val rev = p.str("status") == "reversed"
            Text(peso(p.num("amount")), fontSize = 14.sp, fontWeight = if (rev) FontWeight.Normal else FontWeight.SemiBold,
                color = if (rev) LF.MutedForeground else LF.Foreground, textDecoration = if (rev) TextDecoration.LineThrough else null)
        },
    )

    val visible = list.data?.rows.orEmpty()
    val visibleTotal = visible.filter { it.str("status") == "posted" }.sumOf { it.num("amount") }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageHeader(
            "Payments & Collections",
            "Every confirmed payment is permanent: corrections are made through authorized reversals.",
            actions = if (auth.can("payments.record")) ({ Btn("Record payment", { recordOpen = true }, icon = Icons.Default.Add) }) else null,
        )

        val w = screenWidthDp()
        val cols = if (isCompact()) 2 else if (w >= 1280) 7 else 4
        when {
            summary.initialLoading -> SkeletonRows(2)
            s != null -> {
                val count = s.int("count_today")
                StatGrid(cols, listOf(
                    1 to { m -> StatCard("Collected today", peso(s.num("collected_today")), m, hint = "$count payment${if (count == 1) "" else "s"}", icon = Icons.Default.Wallet, tone = StatTone.green) },
                    1 to { m -> StatCard("This week", peso(s.num("collected_week")), m, icon = Icons.Default.DateRange, tone = StatTone.teal) },
                    1 to { m -> StatCard("This month", peso(s.num("collected_month")), m, icon = Icons.Default.CalendarMonth, tone = StatTone.blue) },
                    1 to { m -> StatCard("Due today", peso(s.num("due_today")), m, icon = Icons.Default.Event, tone = StatTone.amber, onClick = { router.go("/due") }) },
                    1 to { m -> StatCard("Overdue", peso(s.num("overdue")), m, icon = Icons.Default.Warning, tone = StatTone.red, onClick = { router.go("/overdue") }) },
                    1 to { m -> StatCard("Next 7 days", peso(s.num("upcoming_7d")), m, icon = Icons.Default.DateRange, tone = StatTone.navy, onClick = { router.go("/due") }) },
                    (if (cols == 2) 2 else 1) to { m -> StatCard("Receivables", peso(s.num("receivables")), m, hint = "Total still owed", icon = Icons.Default.AccountBalance, tone = StatTone.navy) },
                ))
            }
            summary.error != null -> ErrorState(summary.error, onRetry = summary.reload)
        }

        Panel(contentPadding = if (isCompact()) 12.dp else 16.dp) {
            val methodOpts = listOf(Opt("all", "All methods")) + PAYMENT_METHODS + SYSTEM_PAYMENT_METHODS.map { Opt(it.key, it.value) }
            val statusOpts = listOf(Opt("all", "Posted & reversed"), Opt("posted", "Posted"), Opt("reversed", "Reversed"))
            val export: @Composable () -> Unit = {
                ExportMenu(
                    disabled = (list.data?.count ?: 0) == 0,
                    onCsv = { exportCsv(ctx, fileBase, EXPORT_COLUMNS, exportRows(fetchAll())) },
                    onXlsx = { exportXlsx(ctx, fileBase, "Payments", EXPORT_COLUMNS, exportRows(fetchAll())) },
                )
            }
            if (isCompact()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchField(search, { search = it; page = 0 }, placeholder = "Payment #, reference…")
                    DateRangeFilter(range, { range = it; page = 0 }, presets = listOf("today", "this_week", "this_month", "last_month", "last_3", "ytd", "custom"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectField(method, methodOpts, { method = it; page = 0 }, Modifier.weight(1f))
                        SelectField(status, statusOpts, { status = it; page = 0 }, Modifier.weight(1f))
                    }
                    if (canAssign) SelectField(collector, collectorOpts(staff), { collector = it; page = 0 })
                    export()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchField(search, { search = it; page = 0 }, Modifier.weight(1f), placeholder = "Payment #, reference…")
                        Box(Modifier.weight(1f)) {
                            DateRangeFilter(range, { range = it; page = 0 }, presets = listOf("today", "this_week", "this_month", "last_month", "last_3", "ytd", "custom"))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectField(method, methodOpts, { method = it; page = 0 }, Modifier.weight(1f))
                        SelectField(status, statusOpts, { status = it; page = 0 }, Modifier.weight(1f))
                        if (canAssign) SelectField(collector, collectorOpts(staff), { collector = it; page = 0 }, Modifier.weight(1f))
                        export()
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            if (list.error != null) {
                ErrorState(list.error, onRetry = list.reload)
            } else {
                DataTable(
                    columns, list.data?.rows, loading = list.loading,
                    onRowClick = { p -> router.go("/payments/${p.str("id")}") },
                    empty = {
                        EmptyState(
                            "No payments in this view", description = "Try another date range or filter.", icon = Icons.Default.Wallet,
                            action = if (auth.can("payments.record")) ({ Btn("Record payment", { recordOpen = true }, icon = Icons.Default.Add) }) else null,
                        )
                    },
                )
                if (visible.isNotEmpty()) TotalBar("Posted on this page", peso(visibleTotal))
                Pagination(page, PAGE_SIZE, list.data?.count ?: 0, { page = it })
            }
        }
    }

    if (recordOpen) RecordPaymentDialog(onDismiss = { recordOpen = false })
}

@Suppress("unused") private val keepW = Modifier.width(0.dp)
