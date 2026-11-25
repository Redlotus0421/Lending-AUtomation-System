package com.lendflow.app.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Money
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.ReportsService
import com.lendflow.app.data.Row
import com.lendflow.app.data.int
import com.lendflow.app.data.jsonOf
import com.lendflow.app.data.num
import com.lendflow.app.data.obj
import com.lendflow.app.data.objs
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.ui.components.AutoSizeText
import com.lendflow.app.ui.components.DateRange
import com.lendflow.app.ui.components.DateRangeFilter
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.ExportMenu
import com.lendflow.app.ui.components.LoanStatusBadge
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.components.StatCard
import com.lendflow.app.ui.components.StatTone
import com.lendflow.app.ui.components.card
import com.lendflow.app.ui.components.makeRange
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.ExportCol
import com.lendflow.app.util.PdfSection
import com.lendflow.app.util.XlsxSheet
import com.lendflow.app.util.exportCsv
import com.lendflow.app.util.exportPdfReport
import com.lendflow.app.util.exportXlsxSheets
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.fmtDateTime
import com.lendflow.app.util.num
import com.lendflow.app.util.peso
import com.lendflow.app.util.titleCase

// Port of src/features/dashboard/DashboardPage.tsx

private val MONTH_COLUMNS = listOf(
    ExportCol("label", "Month"),
    ExportCol("loans_released", "Loans released", "number", true),
    ExportCol("released", "Principal released", "money", true),
    ExportCol("collected", "Total collected", "money", true),
    ExportCol("principal_collected", "Principal collected", "money", true),
    ExportCol("interest_collected", "Interest collected", "money", true),
    ExportCol("other_collected", "Charges & penalties collected", "money", true),
    ExportCol("scheduled_due", "Scheduled due", "money", true),
    ExportCol("paid_on_due", "Paid toward scheduled", "money", true),
)

private val SUMMARY_COLUMNS = listOf(ExportCol("group", "Group"), ExportCol("metric", "Metric"), ExportCol("value", "Value"), ExportCol("basis", "Basis"))

private fun summaryRows(d: Row): List<Row> {
    val range = d.obj("range"); val fin = d.obj("financial"); val col = d.obj("collections"); val od = d.obj("overdue"); val op = d.obj("operational")
    val p = "${fmtDate(range.str("from"))} – ${fmtDate(range.str("to"))}"
    fun r(group: String, metric: String, value: String, basis: String) = jsonOf("group" to group, "metric" to metric, "value" to value, "basis" to basis)
    return listOf(
        r("Financial", "Capital released", peso(fin.num("capital_released")), p),
        r("Financial", "Capital released (all time)", peso(fin.num("capital_released_all_time")), "All time"),
        r("Financial", "Outstanding principal", peso(fin.num("outstanding_principal")), "As of today"),
        r("Financial", "Remaining receivables", peso(fin.num("remaining_receivables")), "As of today"),
        r("Financial", "Interest expected (remaining, contractual)", peso(fin.num("interest_expected_remaining")), "As of today"),
        r("Financial", "Interest accrued (fell due)", peso(d.num("interest_accrued")), p),
        r("Financial", "Interest collected", peso(col.num("interest")), p),
        r("Financial", "Payments collected", "${peso(col.num("total"))} (${col.int("count")} payments)", p),
        r("Financial", "Overdue amount", peso(od.num("amount")), "As of today"),
        r("Operational", "Active borrowers", num(op.num("borrowers")), "As of today"),
        r("Operational", "Active loans", num(op.num("active_loans")), "As of today"),
        r("Operational", "Pending applications", num(op.num("pending_applications")), "As of today"),
        r("Operational", "Agreements awaiting signature", num(op.num("awaiting_signature")), "As of today"),
        r("Operational", "Ready for release", num(op.num("ready_for_release")), "As of today"),
        r("Operational", "Loans due today", "${num(op.num("due_today"))} (${peso(op.num("due_today_amount"))})", "Today"),
        r("Operational", "Overdue loans", num(od.num("loans")), "As of today"),
    )
}

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

@Composable
fun DashboardScreen() {
    val auth by AppState.state.collectAsState()
    val router = LocalRouter.current
    val context = LocalContext.current
    var preset by rememberSaveable { mutableStateOf("last_12") }
    var from by rememberSaveable { mutableStateOf(makeRange("last_12").from) }
    var to by rememberSaveable { mutableStateOf(makeRange("last_12").to) }
    val range = DateRange(preset, from, to)
    val q = rememberLoad(range.from, range.to) { ReportsService.dashboard(range.from, range.to) }
    val d = q.data
    val firstName = (auth.access.strOrNull("full_name") ?: "").split(' ').first()
    val periodLabel = "${fmtDate(range.from)} – ${fmtDate(range.to)}"
    val fileBase = "dashboard-${range.from}-to-${range.to}"
    val orgName = auth.organization.strOrNull("legal_name")?.ifBlank { null } ?: auth.organization.strOrNull("name")
    val canPayments = auth.can("payments.view")

    ResponsiveScreen {
        val bp = LocalBreaks.current
        PageHeader(
            title = if (firstName.isNotBlank()) "Good day, $firstName" else "Dashboard",
            description = "Lending performance for ${auth.organization.strOrNull("name") ?: "your organization"} · amounts in PHP",
            actions = {
                OutlinedIconButton(onClick = { q.reload() }, enabled = !q.loading, modifier = Modifier.size(44.dp)) {
                    if (q.loading) Spinner() else Icon(Icons.Default.Refresh, "Refresh", Modifier.size(18.dp))
                }
                ExportMenu(
                    disabled = d == null,
                    onPdf = {
                        if (d != null) exportPdfReport(
                            context, fileBase, "Lending dashboard summary",
                            listOf(
                                PdfSection(SUMMARY_COLUMNS, summaryRows(d), title = "Key figures",
                                    note = "Interest expected = contractual interest not yet paid on open loans. Interest accrued = scheduled interest that fell due in the period. Interest collected = interest actually received in the period."),
                                PdfSection(MONTH_COLUMNS, d.objs("monthly"), title = "Monthly series"),
                            ),
                            subtitle = periodLabel, organization = orgName, landscape = true,
                        )
                    },
                    onXlsx = {
                        if (d != null) exportXlsxSheets(context, fileBase, listOf(
                            XlsxSheet("Summary", SUMMARY_COLUMNS, summaryRows(d)),
                            XlsxSheet("Monthly", MONTH_COLUMNS, d.objs("monthly")),
                        ))
                    },
                    onCsv = { if (d != null) exportCsv(context, fileBase, MONTH_COLUMNS, d.objs("monthly")) },
                )
            },
        )
        Box(Modifier.widthIn(max = 420.dp).padding(bottom = 16.dp)) {
            DateRangeFilter(range, { r -> preset = r.preset; from = r.from; to = r.to }, presets = listOf("this_month", "last_3", "last_6", "last_12", "ytd", "custom"))
        }

        when {
            q.error != null && d == null -> ErrorState(q.error, q.reload)
            d == null -> DashboardSkeleton()
            else -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                val fin = d.obj("financial"); val col = d.obj("collections"); val od = d.obj("overdue"); val op = d.obj("operational")
                val monthly = d.objs("monthly")
                val aging = d.objs("portfolio_aging")

                // financial summary
                GridRows(if (bp.lg) 4 else 2, 4) { i, m ->
                    when (i) {
                        0 -> StatCard("Capital released", peso(fin.num("capital_released")), m, icon = Icons.Default.Payments, tone = StatTone.blue,
                            hint = "In period · ${peso(fin.num("capital_released_all_time"), compact = true)} all time")
                        1 -> StatCard("Outstanding principal", peso(fin.num("outstanding_principal")), m, icon = Icons.Default.AccountBalance, tone = StatTone.navy,
                            hint = "Unpaid principal on open loans")
                        2 -> StatCard("Remaining receivables", peso(fin.num("remaining_receivables")), m, icon = Icons.Default.Savings, tone = StatTone.teal,
                            hint = "Principal + interest + charges + penalties")
                        else -> StatCard("Overdue amount", peso(od.num("amount")), m, icon = Icons.Default.Warning, tone = StatTone.red,
                            hint = "${plural(od.int("installments"), "installment")} on ${plural(od.int("loans"), "loan")}",
                            onClick = if (canPayments) ({ router.go("/overdue") }) else null)
                    }
                }

                SplitRow(bp.lg, 1f, 1.4f, gap = 12.dp,
                    left = {
                        StatCard("Payments collected", peso(col.num("total")), Modifier.fillMaxWidth(), icon = Icons.Default.AccountBalanceWallet, tone = StatTone.green,
                            hint = "${col.int("count")} posted payment${if (col.int("count") == 1) "" else "s"} in period",
                            onClick = if (canPayments) ({ router.go("/payments") }) else null)
                    },
                    right = { InterestCard(fin.num("interest_expected_remaining"), d.num("interest_accrued"), col.num("interest")) },
                )

                // operational
                val ops = listOf(
                    OpItem("Borrowers", op.int("borrowers"), Icons.Default.People, "/borrowers"),
                    OpItem("Active loans", op.int("active_loans"), Icons.Default.Money, "/loans"),
                    OpItem("Pending applications", op.int("pending_applications"), Icons.AutoMirrored.Filled.Assignment, "/applications"),
                    OpItem("Awaiting signature", op.int("awaiting_signature"), Icons.Default.Draw, "/applications"),
                    OpItem("Ready for release", op.int("ready_for_release"), Icons.Default.Inventory, "/applications"),
                    OpItem("Due today", op.int("due_today"), Icons.Default.Schedule, "/due", "amber", peso(op.num("due_today_amount"))),
                    OpItem("Overdue loans", od.int("loans"), Icons.Default.Warning, "/overdue", "red"),
                )
                GridRows(if (bp.xl) 7 else if (bp.sm) 3 else 2, ops.size) { i, m -> OpCard(ops[i], m) { router.go(ops[i].to) } }

                // charts
                val hasReleased = monthly.any { it.num("released") > 0 }
                val hasCollected = monthly.any { it.num("collected") > 0 }
                val hasScheduled = monthly.any { it.num("scheduled_due") > 0 }
                val charts: List<@Composable () -> Unit> = listOf(
                    { Panel(title = "Monthly loan releases", description = "Principal released per month") {
                        if (hasReleased) ReleasesChart(monthly) else ChartEmpty("No loans were released in this period.") } },
                    { Panel(title = "Monthly collections", description = "Total posted payments per month") {
                        if (hasCollected) CollectionsChart(monthly) else ChartEmpty("No payments were collected in this period.") } },
                    { Panel(title = "Principal vs interest collected", description = "Where each month's collections were applied") {
                        if (hasCollected) ComponentsChart(monthly) else ChartEmpty("No payments were collected in this period.") } },
                    { Panel(title = "Payment performance", description = performanceSummary(monthly)) {
                        if (hasScheduled) PerformanceChart(monthly) else ChartEmpty("No installments were scheduled in this period.") } },
                )
                GridRows(if (bp.xl) 2 else 1, charts.size, gap = 16.dp) { i, _ -> charts[i]() }

                SplitRow(bp.xl, 1f, 1.6f,
                    left = {
                        Panel(title = "Outstanding portfolio", description = "Outstanding principal by days past due (portfolio at risk)") {
                            if (aging.isNotEmpty()) {
                                AgingChart(aging)
                                PortfolioAtRisk(aging)
                            } else ChartEmpty("No open loans yet.")
                        }
                    },
                    right = {
                        Panel(title = "Overdue borrowers", description = "Largest overdue balances", contentPadding = 0.dp,
                            actions = if (canPayments) ({ ViewAll { router.go("/overdue") } }) else null) {
                            MiniList("No overdue accounts — great job!", d.objs("overdue_borrowers").map { r ->
                                val days = r.int("days_overdue")
                                MiniRow(r.str("loan_id"), "/loans/${r.str("loan_id")}", r.str("borrower_name"),
                                    "${r.str("loan_number")} · $days day${if (days == 1) "" else "s"} overdue", peso(r.num("overdue_amount")), LF.Red600)
                            }) { router.go(it) }
                        }
                    },
                )

                val lists: List<@Composable () -> Unit> = listOf(
                    {
                        Panel(title = "Recent loan applications", contentPadding = 0.dp,
                            actions = if (auth.can("loans.view")) ({ ViewAll { router.go("/applications") } }) else null) {
                            MiniList("No applications in the pipeline.", d.objs("recent_applications").map { r ->
                                MiniRow(r.str("id"), "/loans/${r.str("id")}", r.str("borrower_name"), "${r.str("loan_number")} · ${fmtDate(r.strOrNull("created_at"))}",
                                    peso(r.num("principal")), badge = { LoanStatusBadge(r.strOrNull("status")) })
                            }) { router.go(it) }
                        }
                    },
                    {
                        Panel(title = "Upcoming payments", description = "Next 7 days", contentPadding = 0.dp,
                            actions = if (canPayments) ({ ViewAll { router.go("/due") } }) else null) {
                            MiniList("Nothing due in the next 7 days.", d.objs("upcoming_payments").map { r ->
                                MiniRow(r.str("installment_id"), "/loans/${r.str("loan_id")}", r.str("borrower_name"),
                                    "${r.str("loan_number")} · #${r.int("installment_no")} · due ${fmtDate(r.strOrNull("due_date"))}", peso(r.num("amount")))
                            }) { router.go(it) }
                        }
                    },
                    {
                        Panel(title = "Recent collections", contentPadding = 0.dp,
                            actions = if (canPayments) ({ ViewAll { router.go("/payments") } }) else null) {
                            MiniList("No payments recorded yet.", d.objs("recent_collections").map { r ->
                                MiniRow(r.str("payment_id"), "/payments/${r.str("payment_id")}", r.str("borrower_name"),
                                    "${r.str("payment_number")} · ${titleCase(r.strOrNull("method"))} · ${fmtDate(r.strOrNull("payment_date"))}", peso(r.num("amount")), LF.Green700)
                            }) { router.go(it) }
                        }
                    },
                )
                GridRows(if (bp.xl) 3 else if (bp.lg) 2 else 1, lists.size, gap = 16.dp) { i, _ -> lists[i]() }
                Panel(title = "Recently signed agreements", contentPadding = 0.dp,
                    actions = if (auth.can("agreements.view")) ({ ViewAll { router.go("/agreements") } }) else null) {
                    MiniList("No fully signed agreements yet.", d.objs("recently_signed").map { r ->
                        MiniRow(r.str("agreement_id"), "/loans/${r.str("loan_id")}", r.str("borrower_name"), "${r.str("agreement_number")} · ${r.str("loan_number")}",
                            fmtDateTime(r.strOrNull("fully_signed_at")), valueSmall = true,
                            badge = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Draw, null, Modifier.size(14.dp), tint = LF.Green700)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Signed", fontSize = 12.sp, color = LF.Green700)
                                }
                            })
                    }) { router.go(it) }
                }
                Text(
                    "Period figures cover $periodLabel; balances and overdue figures are as of ${fmtDate(d.obj("range").strOrNull("today"), "long")} (Asia/Manila).",
                    Modifier.fillMaxWidth(), fontSize = 11.sp, color = LF.MutedForeground, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun InterestCard(expected: Double, accrued: Double, collected: Double) {
    Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text("INTEREST — THREE DIFFERENT MEASURES", Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground, letterSpacing = 0.4.sp)
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(LF.Teal50), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.TrendingUp, null, Modifier.size(16.dp), tint = LF.Teal600)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            InterestMeasure("Expected", "Remaining contractual, not yet paid", expected, Modifier.weight(1f), first = true)
            InterestMeasure("Accrued", "Fell due in period (earned)", accrued, Modifier.weight(1f))
            InterestMeasure("Collected", "Actually received in period", collected, Modifier.weight(1f), strong = true, last = true)
        }
    }
}

@Composable
private fun InterestMeasure(label: String, sub: String, value: Double, modifier: Modifier, strong: Boolean = false, first: Boolean = false, last: Boolean = false) {
    Column(
        modifier
            .drawBehind { if (!first) drawLine(LF.Border, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1.dp.toPx()) }
            .padding(start = if (first) 0.dp else 10.dp, end = if (last) 0.dp else 10.dp),
    ) {
        Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LF.MutedForeground, letterSpacing = 0.4.sp)
        AutoSizeText(peso(value, compact = value >= 1_000_000), Modifier.padding(top = 4.dp), maxSize = 18.sp, minSize = 11.sp,
            fontWeight = FontWeight.SemiBold, color = if (strong) LF.Teal700 else LF.Foreground)
        Text(sub, fontSize = 11.sp, color = LF.MutedForeground, lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

private data class OpItem(val label: String, val value: Int, val icon: ImageVector, val to: String, val tone: String = "slate", val sub: String? = null)

@Composable
private fun OpCard(item: OpItem, modifier: Modifier, onClick: () -> Unit) {
    val (fg, bg) = when (item.tone) {
        "amber" -> Color(0xFFB45309) to LF.Amber50
        "red" -> LF.Red600 to LF.Red50
        else -> LF.Navy to LF.Navy.copy(alpha = 0.08f)
    }
    Column(modifier.card().clickable(onClick = onClick).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(bg), contentAlignment = Alignment.Center) {
                Icon(item.icon, null, Modifier.size(16.dp), tint = fg)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = LF.MutedForeground.copy(alpha = 0.5f))
        }
        Text(num(item.value), Modifier.padding(top = 8.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = LF.Foreground)
        Text(item.label, fontSize = 12.sp, color = LF.MutedForeground, maxLines = 2)
        if (item.sub != null) Text(item.sub, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFFB45309), maxLines = 1)
    }
}

@Composable
private fun ViewAll(onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("View all", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.Blue600)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(12.dp), tint = LF.Blue600)
    }
}

private class MiniRow(
    val key: String,
    val to: String,
    val title: String,
    val sub: String,
    val value: String,
    val valueColor: Color = LF.Foreground,
    val valueSmall: Boolean = false,
    val badge: (@Composable () -> Unit)? = null,
)

@Composable
private fun MiniList(empty: String, rows: List<MiniRow>, onOpen: (String) -> Unit) {
    if (rows.isEmpty()) { EmptyState(empty, Modifier.padding(vertical = 0.dp)); return }
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, r ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(r.to) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.sub, fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (r.valueSmall) Text(r.value, fontSize = 12.sp, color = LF.MutedForeground)
                    else Text(r.value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = r.valueColor)
                    r.badge?.invoke()
                }
            }
            if (i < rows.size - 1) HorizontalDivider(color = LF.Border)
        }
    }
}

@Composable
private fun ChartEmpty(text: String) {
    Box(
        Modifier.fillMaxWidth().height(220.dp).drawBehind {
            drawRoundRect(LF.Border, cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Default.Paid, null, Modifier.size(20.dp), tint = LF.MutedForeground.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            Text(text, fontSize = 14.sp, color = LF.MutedForeground, textAlign = TextAlign.Center)
        }
    }
}

private fun performanceSummary(data: List<Row>): String {
    val due = data.sumOf { it.num("scheduled_due") }
    val paid = data.sumOf { it.num("paid_on_due") }
    val base = "Scheduled installments vs amounts paid toward them"
    return if (due > 0) "$base · ${String.format(java.util.Locale.US, "%.1f", paid / due * 100)}% collection rate" else base
}

@Composable
private fun PortfolioAtRisk(data: List<Row>) {
    val total = data.sumOf { it.num("outstanding_principal") }
    val atRisk = data.filter { it.str("bucket") != "Current" }.sumOf { it.num("outstanding_principal") }
    val par30 = data.filter { it.str("bucket") !in listOf("Current", "1–30 days") }.sumOf { it.num("outstanding_principal") }
    fun pct(v: Double) = if (total > 0) "${String.format(java.util.Locale.US, "%.1f", v / total * 100)}%" else "0%"
    Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(8.dp)).background(LF.Slate50).padding(12.dp)) {
        listOf(Triple("Portfolio", peso(total, compact = true), LF.Foreground), Triple("PAR 1+", pct(atRisk), Color(0xFFB45309)), Triple("PAR 30+", pct(par30), LF.Red600))
            .forEach { (label, value, color) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 11.sp, color = LF.MutedForeground)
                    Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
                }
            }
    }
}

@Composable
private fun DashboardSkeleton() {
    val bp = LocalBreaks.current
    @Composable fun Sk(h: Int, m: Modifier) = Box(m.height(h.dp).clip(RoundedCornerShape(12.dp)).background(LF.Muted))
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GridRows(if (bp.lg) 4 else 2, 4) { _, m -> Sk(112, m) }
        GridRows(if (bp.xl) 7 else if (bp.sm) 3 else 2, 7) { _, m -> Sk(96, m) }
        GridRows(if (bp.xl) 2 else 1, 4, gap = 16.dp) { _, m -> Sk(288, m) }
    }
}
