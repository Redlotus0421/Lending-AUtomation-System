package com.lendflow.app.feature.payments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.LoansService
import com.lendflow.app.data.PaymentsService
import com.lendflow.app.data.ReportsService
import com.lendflow.app.data.Row
import com.lendflow.app.data.UsersService
import com.lendflow.app.data.bool
import com.lendflow.app.data.int
import com.lendflow.app.data.num
import com.lendflow.app.data.objs
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.with
import com.lendflow.app.ui.components.AppDialog
import com.lendflow.app.ui.components.Align
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CellRole
import com.lendflow.app.ui.components.ChipRow
import com.lendflow.app.ui.components.Col
import com.lendflow.app.ui.components.DataTable
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.ExportMenu
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.LoanStatusBadge
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
import com.lendflow.app.ui.components.rememberDebounced
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.ExportCol
import com.lendflow.app.util.Opt
import com.lendflow.app.util.ROLE_LABEL
import com.lendflow.app.util.Tone
import com.lendflow.app.util.exportCsv
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.peso
import com.lendflow.app.util.todayManila

// Port of src/features/payments/OverdueLoansPage.tsx

private const val PAGE_SIZE = 25

private data class Bucket(val key: String, val label: String, val min: Int?, val max: Int?)

private val BUCKETS = listOf(
    Bucket("all", "All overdue", null, null),
    Bucket("1-30", "1–30 days", 1, 30),
    Bucket("31-60", "31–60 days", 31, 60),
    Bucket("61-90", "61–90 days", 61, 90),
    Bucket("90+", "90+ days", 91, null),
)

private fun bucketTone(days: Int) = if (days > 60) Tone.red else Tone.amber

private val EXPORT_COLUMNS = listOf(
    ExportCol("borrower_name", "Borrower"),
    ExportCol("borrower_mobile", "Mobile"),
    ExportCol("loan_number", "Loan #"),
    ExportCol("status", "Status", "status"),
    ExportCol("oldest_overdue_date", "Oldest due", "date"),
    ExportCol("days_overdue", "Days overdue", "number"),
    ExportCol("overdue_count", "Installments overdue", "number", true),
    ExportCol("overdue_amount", "Overdue amount", "money", true),
    ExportCol("penalties", "Penalties (to date)", "money", true),
    ExportCol("total_outstanding", "Total outstanding", "money", true),
    ExportCol("collector_name", "Collector"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverdueLoansScreen() {
    val router = LocalRouter.current
    val ctx = LocalContext.current
    val auth by AppState.state.collectAsState()
    var bucket by remember { mutableStateOf("all") }
    var search by remember { mutableStateOf("") }
    var collector by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var collect by remember { mutableStateOf<String?>(null) }
    var assign by remember { mutableStateOf<Row?>(null) }
    val term = rememberDebounced(search)
    val b = BUCKETS.first { it.key == bucket }
    val canAssign = auth.can("collections.assign")
    val canReports = auth.can("reports.view")

    val staff = rememberActiveStaff(canAssign)
    val q = rememberLoad(term, collector, bucket, page) {
        PaymentsService.overdueLoans(term, collector.ifBlank { null }, b.min, b.max, page, PAGE_SIZE)
    }
    // Penalties accrued to date (posted + not yet posted) come from the overdue accounts report when permitted.
    val penaltiesQ = rememberLoad(canReports, enabled = canReports) {
        if (canReports) ReportsService.get("overdue_accounts", todayManila(), todayManila()) else null
    }
    val reportRows = penaltiesQ.data?.objs("rows").orEmpty()
    val penaltyMap = remember(penaltiesQ.data) { reportRows.associate { it.str("loan_id") to it.num("penalties") } }
    fun penaltyOf(l: Row) = penaltyMap[l.str("id")] ?: l.num("outstanding_penalties")

    fun sum(min: Int, max: Int? = null) = reportRows.filter { r -> r.num("days_overdue") >= min && (max == null || r.num("days_overdue") <= max) }.sumOf { it.num("overdue_amount") }
    val rows = q.data?.rows.orEmpty()
    val lender = auth.organization.strOrNull("name")

    val columns = listOf<Col<Row>>(
        Col("Borrower", 1.4f, mobile = CellRole.Title, minWidth = 150.dp) { l ->
            Column {
                Text(l.str("borrower_name"), Modifier.clip(RoundedCornerShape(4.dp)).clickable { router.go("/borrowers/${l.str("borrower_id")}") },
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(l.str("loan_number") + if (l.str("status") == "defaulted") " · defaulted" else "", fontSize = 12.sp, color = LF.MutedForeground)
            }
        },
        Col("Contact", 1.4f, mobile = CellRole.Meta, minWidth = 170.dp) { l ->
            ContactCell(l.strOrNull("borrower_mobile"), l.str("borrower_name")) {
                SmsArgs(l.str("borrower_name"), l.str("overdue_amount"), l.strOrNull("oldest_overdue_date") ?: todayManila(), l.str("loan_number"), lender, true)
            }
        },
        Col("Overdue since", 1.5f, mobile = CellRole.Subtitle, minWidth = 170.dp) { l ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Pill("${l.int("days_overdue")} days", bucketTone(l.int("days_overdue")))
                Text("since ${fmtDate(l.strOrNull("oldest_overdue_date"))} · ${l.int("overdue_count")} inst.", fontSize = 12.sp, color = LF.MutedForeground)
            }
        },
        Col(if (canReports) "Penalties to date" else "Penalties posted", 1f, Align.End, mobile = CellRole.Meta) { l ->
            val p = penaltyOf(l)
            Text(peso(p), fontSize = 14.sp, color = if (p > 0) LF.Amber800 else LF.MutedForeground)
        },
        Col("Collector", 1f, mobile = CellRole.Meta) { l ->
            val c = l.strOrNull("collector_name")
            Text(c ?: "Unassigned", fontSize = 14.sp, color = if (c == null) LF.MutedForeground else LF.Foreground, maxLines = 1)
        },
        Col("Overdue", 1f, Align.End, mobile = CellRole.Value) { l ->
            Column(horizontalAlignment = Alignment.End) {
                Text(peso(l.num("overdue_amount")), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LF.Red600)
                Text("of ${peso(l.num("total_outstanding"))}", fontSize = 12.sp, color = LF.MutedForeground)
            }
        },
        Col("", 1.3f, Align.End, mobile = CellRole.Meta, minWidth = 190.dp) { l ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (canAssign) Btn("Assign", { assign = l }, variant = BtnVariant.Outline, icon = Icons.Default.PersonAdd, small = true)
                if (auth.can("payments.record")) Btn("Collect", { collect = l.str("id") }, icon = Icons.Default.Wallet, small = true)
            }
        },
    )

    suspend fun fetchAll() = PaymentsService.overdueLoans(term, collector.ifBlank { null }, b.min, b.max, 0, 5000).rows.map { it.with("penalties", penaltyOf(it)) }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageHeader(
            "Overdue Loans",
            "Loans with installments past their due date, oldest first. Penalties accrue automatically per each loan's late-payment terms.",
            actions = { ExportMenu(disabled = (q.data?.count ?: 0) == 0, onCsv = { exportCsv(ctx, "overdue-loans-${todayManila()}", EXPORT_COLUMNS, fetchAll()) }) },
        )

        if (canReports && penaltiesQ.data != null) {
            val n = reportRows.size
            fun pick(k: String) { bucket = k; page = 0 }
            val cols = if (isCompact()) 2 else 5
            StatGrid(cols, listOf(
                (if (cols == 2) 2 else 1) to { m -> StatCard("Total overdue", peso(reportRows.sumOf { it.num("overdue_amount") }), m, hint = "$n loan${if (n == 1) "" else "s"}", icon = Icons.Default.Warning, tone = StatTone.red) },
                1 to { m -> StatCard("1–30 days", peso(sum(1, 30)), m, tone = StatTone.amber, onClick = { pick("1-30") }) },
                1 to { m -> StatCard("31–60 days", peso(sum(31, 60)), m, tone = StatTone.amber, onClick = { pick("31-60") }) },
                1 to { m -> StatCard("61–90 days", peso(sum(61, 90)), m, tone = StatTone.red, onClick = { pick("61-90") }) },
                1 to { m -> StatCard("90+ days", peso(sum(91)), m, tone = StatTone.red, onClick = { pick("90+") }) },
            ))
        } else if (canReports && penaltiesQ.initialLoading) {
            SkeletonRows(1)
        }

        Panel(contentPadding = if (isCompact()) 12.dp else 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipRow(BUCKETS.map { Opt(it.key, it.label) }, bucket, { bucket = it; page = 0 })
                if (isCompact()) {
                    SearchField(search, { search = it; page = 0 }, placeholder = "Borrower or loan #")
                    if (canAssign) SelectField(collector, collectorOpts(staff, withUnassigned = true), { collector = it; page = 0 })
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchField(search, { search = it; page = 0 }, Modifier.weight(1f), placeholder = "Borrower or loan #")
                        if (canAssign) SelectField(collector, collectorOpts(staff, withUnassigned = true), { collector = it; page = 0 }, Modifier.weight(1f))
                        else Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            if (q.error != null) {
                ErrorState(q.error, onRetry = q.reload)
            } else {
                DataTable(
                    columns, q.data?.rows, loading = q.loading,
                    onRowClick = { l -> router.go("/loans/${l.str("id")}") },
                    empty = {
                        EmptyState("No overdue loans", description = if (bucket == "all") "Every active loan is current. 🎉" else "No loans fall in this ageing bucket.", icon = Icons.Default.GppMaybe)
                    },
                )
                if (rows.isNotEmpty()) TotalBar("Overdue on this page", peso(rows.sumOf { it.num("overdue_amount") }), bg = LF.Red50, valueColor = LF.Red700)
                Pagination(page, PAGE_SIZE, q.data?.count ?: 0, { page = it })
            }
        }
    }

    collect?.let { id -> RecordPaymentDialog(onDismiss = { collect = null }, loanId = id) }
    assign?.let { l -> AssignCollectorDialog(l) { assign = null } }
}

@Composable
private fun AssignCollectorDialog(loan: Row, onClose: () -> Unit) {
    val staff = rememberLoad(loan.str("id")) { UsersService.list() }
    val runner = rememberRunner()
    val current = loan.strOrNull("collector_id") ?: ""
    var value by remember(loan) { mutableStateOf(current) }
    LaunchedEffect(loan) { value = loan.strOrNull("collector_id") ?: "" }
    val options = staff.data.orEmpty().filter { it.bool("is_active", true) && it.str("role") in listOf("collector", "loan_officer", "admin") }

    AppDialog(
        title = "Assign collector",
        onDismiss = { if (!runner.busy) onClose() },
        description = "Loan ${loan.str("loan_number")} · ${loan.str("borrower_name")}",
        maxWidth = 448.dp,
        footer = {
            Btn("Cancel", onClose, variant = BtnVariant.Outline, enabled = !runner.busy)
            Btn("Save", {
                runner.run(success = if (value.isNotBlank()) "Collector assigned" else "Collector removed") {
                    LoansService.assignStaff(loan.str("id"), null, value.ifBlank { null })
                    onClose()
                }
            }, loading = runner.busy, enabled = value != current)
        },
    ) {
        LoanStatusBadge(loan.str("status"))
        Field("Collector", hint = "The collector will see this borrower and loan and can record payments. They are notified of the assignment.") {
            if (staff.initialLoading) SkeletonRows(1)
            else SelectField(value, listOf(Opt("", "— Unassigned —")) + options.map { s ->
                Opt(s.str("id"), "${s.str("full_name")} · ${ROLE_LABEL[s.str("role")] ?: s.str("role")}")
            }, { value = it })
        }
    }
}

@Suppress("unused") private val keepW = Modifier.width(0.dp)
