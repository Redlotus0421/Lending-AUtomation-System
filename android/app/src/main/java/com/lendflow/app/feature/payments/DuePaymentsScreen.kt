package com.lendflow.app.feature.payments

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MonetizationOn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.PaymentsService
import com.lendflow.app.data.Row
import com.lendflow.app.data.bool
import com.lendflow.app.data.num
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.ui.components.Align
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CellRole
import com.lendflow.app.ui.components.CellText
import com.lendflow.app.ui.components.Col
import com.lendflow.app.ui.components.DataTable
import com.lendflow.app.ui.components.DateRangeFilter
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.ExportMenu
import com.lendflow.app.ui.components.InstallmentStatusBadge
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.Pagination
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.SearchField
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.makeRange
import com.lendflow.app.ui.components.rememberDebounced
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.ExportCol
import com.lendflow.app.util.Opt
import com.lendflow.app.util.exportCsv
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.peso
import com.lendflow.app.util.todayManila

// Port of src/features/payments/DuePaymentsPage.tsx

private const val PAGE_SIZE = 50

private val TABS = listOf(
    Triple("due_today", "Due today", Icons.Default.Event),
    Triple("upcoming", "Upcoming", Icons.Default.DateRange),
    Triple("partially_paid", "Partially paid", Icons.Default.MonetizationOn),
    Triple("paid", "Completed", Icons.Default.EventAvailable),
)

private val EXPORT_COLUMNS = listOf(
    ExportCol("borrower_name", "Borrower"),
    ExportCol("mobile_number", "Mobile"),
    ExportCol("loan_number", "Loan #"),
    ExportCol("installment_no", "Installment", "number"),
    ExportCol("due_date", "Due date", "date"),
    ExportCol("scheduled_amount", "Scheduled", "money", true),
    ExportCol("amount_paid", "Paid", "money", true),
    ExportCol("amount_remaining", "Remaining", "money", true),
    ExportCol("display_status", "Status", "status"),
    ExportCol("paid_at", "Paid on", "date"),
    ExportCol("collector_name", "Collector"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DuePaymentsScreen(tab: String?) {
    val router = LocalRouter.current
    val ctx = LocalContext.current
    val auth by AppState.state.collectAsState()
    val today = todayManila()
    // ?tab= opens a view directly; "overdue" (not one of the web tabs) is listed as an extra tab when asked for
    val tabs = if (tab == "overdue") TABS + Triple("overdue", "Overdue", Icons.Default.Warning) else TABS
    var view by remember(tab) { mutableStateOf(tabs.firstOrNull { it.first == tab }?.first ?: "due_today") }
    var days by remember { mutableIntStateOf(14) }
    var range by remember { mutableStateOf(makeRange("this_month")) }
    var search by remember { mutableStateOf("") }
    var collector by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var collect by remember { mutableStateOf<Pair<String, String>?>(null) }
    val term = rememberDebounced(search)
    val canAssign = auth.can("collections.assign")
    val staff = rememberActiveStaff(canAssign)
    val from = if (view == "paid") range.from else null
    val to = if (view == "paid") range.to else null

    val q = rememberLoad(view, days, term, collector, from, to, page) {
        PaymentsService.installments(view, today, days, term, collector.ifBlank { null }, from, to, page, PAGE_SIZE)
    }
    val rows = q.data?.rows.orEmpty()
    val count = q.data?.count ?: 0
    val totalRemaining = rows.sumOf { it.num("amount_remaining") }
    val totalPaid = rows.sumOf { it.num("amount_paid") }
    val lender = auth.organization.strOrNull("name")

    val columns = listOf<Col<Row>>(
        Col("Borrower", 1.4f, mobile = CellRole.Title, minWidth = 150.dp) { r ->
            Column {
                Text(r.str("borrower_name"), Modifier.clip(RoundedCornerShape(4.dp)).clickable { router.go("/borrowers/${r.str("borrower_id")}") },
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(r.str("borrower_code"), fontSize = 12.sp, color = LF.MutedForeground)
            }
        },
        Col("Contact", 1.4f, mobile = CellRole.Meta, minWidth = 170.dp) { r ->
            ContactCell(r.strOrNull("mobile_number"), r.str("borrower_name"), if (view != "paid") ({
                SmsArgs(r.str("borrower_name"), r.str("amount_remaining"), r.str("due_date"), r.str("loan_number"), lender, r.str("due_date") < today)
            }) else null)
        },
        Col("Loan", 1.1f, mobile = CellRole.Subtitle, minWidth = 130.dp) { r ->
            Row {
                Text(r.str("loan_number"), Modifier.clip(RoundedCornerShape(4.dp)).clickable { router.go("/loans/${r.str("loan_id")}") },
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LF.Blue600)
                Text(" · #${r.str("installment_no")}", fontSize = 13.sp, color = LF.MutedForeground)
            }
        },
        Col(if (view == "paid") "Due / paid" else "Due date", 1f, mobile = CellRole.Meta) { r ->
            val od = r.str("display_status") == "overdue"
            Column {
                Text(fmtDate(r.str("due_date")), fontSize = 14.sp, fontWeight = if (od) FontWeight.Medium else FontWeight.Normal, color = if (od) LF.Red600 else LF.Foreground)
                if (view == "paid" && r.strOrNull("paid_at") != null) Text("paid ${fmtDate(r.str("paid_at"))}", fontSize = 12.sp, color = LF.MutedForeground)
                if (od) Text("${r.str("days_overdue")}d overdue", fontSize = 12.sp, color = LF.Red600)
            }
        },
        Col("Status", 1f, mobile = CellRole.Meta) { r ->
            InstallmentStatusBadge(if (r.str("display_status") == "upcoming" && r.bool("is_partial")) "partially_paid" else r.str("display_status"))
        },
        Col("Collector", 1f, mobile = CellRole.Hidden) { r -> CellText(r.strOrNull("collector_name"), muted = r.strOrNull("collector_name") == null) },
        Col(if (view == "paid") "Paid" else "Remaining", 1f, Align.End, mobile = CellRole.Value) { r ->
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(peso(if (view == "paid") r.num("amount_paid") else r.num("amount_remaining")), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (view != "paid" && r.num("amount_paid") > 0) Text("of ${peso(r.num("scheduled_amount"))}", fontSize = 12.sp, color = LF.MutedForeground)
            }
        },
        Col("", 0.9f, Align.End, mobile = CellRole.Meta, minWidth = 110.dp) { r ->
            if (auth.can("payments.record") && view != "paid" && r.str("loan_status") in listOf("active", "defaulted")) {
                Btn("Collect", { collect = r.str("loan_id") to r.str("id") }, icon = Icons.Default.Wallet, small = true)
            }
        },
    )

    val fileBase = "${view.replaceFirst("_", "-")}-installments-$today"

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageHeader(
            "Due Payments",
            "Installments due today and coming up, partial payments, and completed collections.",
            actions = {
                ExportMenu(
                    disabled = count == 0,
                    onCsv = {
                        val all = PaymentsService.installments(view, today, days, term, collector.ifBlank { null }, from, to, 0, 5000).rows
                        exportCsv(ctx, fileBase, EXPORT_COLUMNS, all)
                    },
                )
            },
        )

        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LF.Border).padding(1.dp)) {
            SegmentTabs(tabs, view) { view = it; page = 0 }
        }

        Panel(contentPadding = if (isCompact()) 12.dp else 16.dp) {
            val windowOpts = listOf(Opt("7", "Next 7 days"), Opt("14", "Next 14 days"), Opt("30", "Next 30 days"))
            val presets = listOf("today", "this_week", "this_month", "last_month", "last_3", "custom")
            if (isCompact()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchField(search, { search = it; page = 0 }, placeholder = "Borrower or loan #")
                    if (view == "upcoming") SelectField(days.toString(), windowOpts, { days = it.toInt(); page = 0 })
                    if (view == "paid") DateRangeFilter(range, { range = it; page = 0 }, presets = presets)
                    if (canAssign) SelectField(collector, collectorOpts(staff), { collector = it; page = 0 })
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchField(search, { search = it; page = 0 }, Modifier.weight(1.2f), placeholder = "Borrower or loan #")
                    if (view == "upcoming") SelectField(days.toString(), windowOpts, { days = it.toInt(); page = 0 }, Modifier.weight(1f))
                    if (view == "paid") Box(Modifier.weight(1.2f)) { DateRangeFilter(range, { range = it; page = 0 }, presets = presets) }
                    if (canAssign) SelectField(collector, collectorOpts(staff), { collector = it; page = 0 }, Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(16.dp))

            if (rows.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(8.dp)).background(LF.Slate50).padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("$count installment${if (count == 1) "" else "s"}${if (count > rows.size) " · showing ${rows.size}" else ""}", fontSize = 14.sp, color = LF.MutedForeground)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (view == "paid") {
                            Row { Text("Collected ", fontSize = 14.sp); Text(peso(totalPaid), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                        } else {
                            if (totalPaid > 0) Text("Paid so far ${peso(totalPaid)}", fontSize = 14.sp, color = LF.MutedForeground)
                            Row { Text("To collect ", fontSize = 14.sp); Text(peso(totalRemaining), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }

            if (q.error != null) {
                ErrorState(q.error, onRetry = q.reload)
            } else {
                DataTable(
                    columns, q.data?.rows, loading = q.loading,
                    empty = {
                        EmptyState(
                            when (view) {
                                "due_today" -> "Nothing due today"
                                "upcoming" -> "Nothing due in the next $days days"
                                "partially_paid" -> "No partially paid installments"
                                "overdue" -> "No overdue installments"
                                else -> "No completed installments in this period"
                            },
                            description = if (view == "due_today") "Overdue installments are listed on the Overdue Loans page." else null,
                            icon = if (view == "paid") Icons.Default.EventAvailable else Icons.Default.Event,
                            action = if (view == "due_today") ({ Btn("View overdue loans", { router.go("/overdue") }, variant = BtnVariant.Outline) }) else null,
                        )
                    },
                )
                Pagination(page, PAGE_SIZE, count, { page = it })
            }
        }
    }

    collect?.let { (loanId, installmentId) ->
        RecordPaymentDialog(onDismiss = { collect = null }, loanId = loanId, installmentId = installmentId)
    }
}

@Suppress("unused") private val keepW = Modifier.width(0.dp)
