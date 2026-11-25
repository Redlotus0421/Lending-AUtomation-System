package com.lendflow.app.feature.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Money
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.ReportsService
import com.lendflow.app.data.Row
import com.lendflow.app.data.UsersService
import com.lendflow.app.data.bool
import com.lendflow.app.data.obj
import com.lendflow.app.data.objs
import com.lendflow.app.data.primitiveString
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.toNum
import com.lendflow.app.feature.dashboard.LocalBreaks
import com.lendflow.app.feature.dashboard.ResponsiveScreen
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.DateRange
import com.lendflow.app.ui.components.DateRangeFilter
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.ExportMenu
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.ReportCell
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.SkeletonRows
import com.lendflow.app.ui.components.card
import com.lendflow.app.ui.components.makeRange
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.ExportCol
import com.lendflow.app.util.Opt
import com.lendflow.app.util.PdfSection
import com.lendflow.app.util.displayValue
import com.lendflow.app.util.exportColsFrom
import com.lendflow.app.util.exportCsv
import com.lendflow.app.util.exportPdfTable
import com.lendflow.app.util.exportXlsx
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.fmtDateTime
import com.lendflow.app.util.pdfReportBytes
import com.lendflow.app.util.printPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Port of src/features/reports/ReportsPage.tsx

private class ReportDef(val key: String, val label: String, val icon: ImageVector, val usesRange: Boolean, val description: String, val pointInTime: Boolean = false)

private class ReportGroup(val group: String, val reports: List<ReportDef>)

private val CATALOGUE = listOf(
    ReportGroup("Portfolio", listOf(
        ReportDef("loans_released", "Loans released", Icons.Default.Payments, true, "Loans disbursed within the period with principal, net proceeds and contractual interest."),
        ReportDef("principal_outstanding", "Principal outstanding", Icons.Default.AccountBalance, false, "Open loans with original principal, principal repaid and principal still outstanding — as of today.", pointInTime = true),
        ReportDef("outstanding_receivables", "Outstanding receivables", Icons.Default.Savings, false, "Everything still owed on open loans, split into principal, interest, charges and penalties, with the overdue portion.", pointInTime = true),
        ReportDef("completed_loans", "Completed loans", Icons.Default.AssignmentTurnedIn, true, "Loans fully paid (including early settlements) within the period."),
        ReportDef("defaulted_loans", "Defaulted loans", Icons.Default.ReportProblem, false, "Loans currently marked as defaulted, with amounts collected and still outstanding.", pointInTime = true),
    )),
    ReportGroup("Income", listOf(
        ReportDef("interest_earned", "Interest accrued vs collected", Icons.AutoMirrored.Filled.TrendingUp, true, "Accrued = scheduled interest that fell due in the period (earned). Collected = interest actually received in the period. Expected = contractual interest still to fall due after the period. These are never added together."),
        ReportDef("interest_collected", "Interest collected", Icons.Default.Paid, true, "Interest actually received through posted payments in the period (cash basis), per payment and receipt."),
    )),
    ReportGroup("Collections", listOf(
        ReportDef("total_collections", "Total collections", Icons.Default.AccountBalanceWallet, true, "All posted payments in the period with their allocation to principal, interest, charges and penalties."),
        ReportDef("daily_cash", "Daily cash collection", Icons.Default.CalendarMonth, true, "Collections per day by payment method, against cash released to borrowers, with net cash flow."),
        ReportDef("monthly_cash", "Monthly cash collection", Icons.Default.Money, true, "Collections per month by payment method, against cash released to borrowers, with net cash flow."),
        ReportDef("overdue_accounts", "Overdue accounts", Icons.Default.MonetizationOn, false, "Loans with unpaid installments past due today, oldest due date, days overdue and penalties accrued to date.", pointInTime = true),
    )),
    ReportGroup("Performance", listOf(
        ReportDef("collector_performance", "Collector performance", Icons.Default.HowToReg, true, "Per collector: assigned loans, scheduled amounts due in the period, amounts collected, collection rate and current overdue."),
        ReportDef("borrower_performance", "Borrower payment performance", Icons.Default.People, false, "Per borrower: installments due to date, paid on time, paid late, currently overdue and on-time rate.", pointInTime = true),
    )),
)

private val ALL = CATALOGUE.flatMap { it.reports }

private const val ROW_CHUNK = 200

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportsScreen() {
    val auth by AppState.state.collectAsState()
    val router = LocalRouter.current
    val context = LocalContext.current
    var reportKey by rememberSaveable { mutableStateOf("total_collections") }
    var preset by rememberSaveable { mutableStateOf("this_month") }
    var from by rememberSaveable { mutableStateOf(makeRange("this_month").from) }
    var to by rememberSaveable { mutableStateOf(makeRange("this_month").to) }
    var collector by rememberSaveable { mutableStateOf("") }
    val range = DateRange(preset, from, to)
    val def = ALL.first { it.key == reportKey }

    val canStaff = auth.can("collections.assign") || auth.can("users.manage")
    val staff = rememberLoad("staff", enabled = canStaff) { UsersService.list() }
    val collectors = (staff.data ?: emptyList()).filter { it.bool("is_active") && it.str("role") in listOf("collector", "loan_officer", "admin") }

    val q = rememberLoad(reportKey, range.from, range.to, collector) {
        ReportsService.get(reportKey, range.from, range.to, if (collector.isNotBlank()) mapOf("collector_id" to collector) else emptyMap())
    }
    val result = q.data
    val columns = remember(result) { exportColsFrom(result.objs("columns")) }
    val rows = result.objs("rows")
    val totals: Map<String, Double>? = result.obj("totals")?.mapValues { it.value.toNum() }

    val periodText = if (def.pointInTime) "As of ${fmtDate(result.strOrNull("to") ?: range.to, "long")}" else "${fmtDate(range.from, "long")} – ${fmtDate(range.to, "long")}"
    val collectorName = collectors.firstOrNull { it.str("id") == collector }?.strOrNull("full_name")
    val subtitle = "$periodText${if (collectorName != null) " · Collector: $collectorName" else ""}"
    val fileBase = "${def.label}-${range.from}-to-${range.to}"
    val orgName = auth.organization.strOrNull("legal_name")?.ifBlank { null } ?: auth.organization.strOrNull("name")

    ResponsiveScreen {
        val bp = LocalBreaks.current
        PageHeader(
            "Reports & Analytics",
            "Financial and operational reports computed from posted transactions. Export to PDF, Excel or CSV.",
            actions = {
                Btn("Refresh", { q.reload() }, variant = BtnVariant.Outline, icon = Icons.Default.Refresh, loading = q.loading)
                ExportMenu(
                    disabled = rows.isEmpty(),
                    onPdf = { exportPdfTable(context, fileBase, def.label, columns, rows, subtitle = subtitle, organization = orgName, totals = totals, note = def.description) },
                    onXlsx = { exportXlsx(context, fileBase, def.label, columns, rows, totals) },
                    onCsv = { exportCsv(context, fileBase, columns, rows, totals) },
                    onPrint = {
                        val bytes = pdfReportBytes(def.label, listOf(PdfSection(columns, rows, totals = totals, note = def.description)), subtitle, orgName)
                        withContext(Dispatchers.Main) { printPdf(context, def.label, bytes) }
                    },
                )
            },
        )

        val content: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // filters
                FlowRow(
                    Modifier.fillMaxWidth().card().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (def.usesRange) {
                        Box(Modifier.widthIn(max = 420.dp)) {
                            DateRangeFilter(range, { r -> preset = r.preset; from = r.from; to = r.to },
                                presets = listOf("today", "this_week", "this_month", "last_month", "last_3", "last_6", "last_12", "ytd", "custom"))
                        }
                    } else {
                        Row(
                            Modifier.clip(RoundedCornerShape(6.dp)).background(LF.Muted).padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.BarChart, null, Modifier.size(14.dp), tint = LF.MutedForeground)
                            Spacer(Modifier.width(6.dp))
                            Text("Point-in-time report — as of today", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground)
                        }
                    }
                    if (canStaff && collectors.isNotEmpty()) {
                        Box(Modifier.widthIn(min = 176.dp, max = 280.dp)) {
                            SelectField(collector, listOf(Opt("", "All collectors")) + collectors.map { Opt(it.str("id"), it.str("full_name")) }, { collector = it })
                        }
                    }
                }

                Panel(
                    title = def.label,
                    description = "$subtitle${if (result != null) " · Generated ${fmtDateTime(result.strOrNull("generated_at"))}" else ""}",
                    contentPadding = 0.dp,
                ) {
                    Text(
                        def.description, Modifier.fillMaxWidth().background(LF.Slate50.copy(alpha = 0.7f)).padding(horizontal = 16.dp, vertical = 10.dp),
                        fontSize = 12.sp, color = LF.MutedForeground, lineHeight = 18.sp,
                    )
                    HorizontalDivider(color = LF.Border)
                    Box(Modifier.fillMaxWidth().padding(12.dp)) {
                        when {
                            q.error != null && result == null -> ErrorState(q.error, q.reload)
                            result == null -> SkeletonRows(8)
                            rows.isEmpty() -> EmptyState(
                                "No records for this report",
                                description = if (def.usesRange) "Try a wider date range or a different collector." else "There is nothing to report right now.",
                            )
                            else -> ReportTable(columns, rows, totals, onOpen = { router.go(it) })
                        }
                    }
                }
            }
        }

        if (bp.lg) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                Catalogue(reportKey, { reportKey = it }, Modifier.width(260.dp))
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            ReportPicker(reportKey) { reportKey = it }
            Spacer(Modifier.padding(top = 16.dp))
            content()
        }
    }
}

@Composable
private fun Catalogue(selected: String, onSelect: (String) -> Unit, modifier: Modifier) {
    Column(modifier.card().padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CATALOGUE.forEach { g ->
            Column {
                Text(g.group.uppercase(), Modifier.padding(start = 8.dp, bottom = 4.dp), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    color = LF.MutedForeground, letterSpacing = 1.2.sp)
                g.reports.forEach { r ->
                    val sel = r.key == selected
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (sel) LF.Blue50 else Color.Transparent)
                            .clickable { onSelect(r.key) }.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(r.icon, null, Modifier.size(16.dp), tint = if (sel) LF.Blue600 else LF.MutedForeground)
                        Spacer(Modifier.width(10.dp))
                        Text(r.label, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal, color = if (sel) LF.Blue700 else LF.Foreground)
                    }
                }
            }
        }
    }
}

/** Grouped report select (NativeSelect with optgroups on the web, shown below lg). */
@Composable
private fun ReportPicker(selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val def = ALL.first { it.key == selected }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)
                .border(1.dp, LF.Input, RoundedCornerShape(8.dp)).clickable { open = true }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(def.icon, null, Modifier.size(18.dp), tint = LF.Blue600)
            Spacer(Modifier.width(10.dp))
            Text(def.label, Modifier.weight(1f), fontSize = 15.sp, color = LF.Foreground)
            Icon(Icons.Default.ExpandMore, null, tint = LF.MutedForeground)
        }
        DropdownMenu(open, { open = false }, properties = PopupProperties(focusable = true), modifier = Modifier.background(Color.White)) {
            CATALOGUE.forEach { g ->
                Text(g.group.uppercase(), Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp), fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold, color = LF.MutedForeground, letterSpacing = 1.2.sp)
                g.reports.forEach { r ->
                    DropdownMenuItem(
                        text = { Text(r.label, fontSize = 14.sp, fontWeight = if (r.key == selected) FontWeight.SemiBold else FontWeight.Normal) },
                        leadingIcon = { Icon(r.icon, null, Modifier.size(16.dp), tint = if (r.key == selected) LF.Blue600 else LF.MutedForeground) },
                        onClick = { open = false; onSelect(r.key) },
                    )
                }
            }
        }
    }
}

private fun numeric(c: ExportCol) = c.type == "money" || c.type == "number" || c.type == "percent"

/** Width of a column from its longest value (like the web's auto table layout). */
private fun columnWidth(c: ExportCol, rows: List<Row>): Float {
    val longest = rows.take(100).fold(c.label.length) { m, r -> maxOf(m, displayValue(c, r[c.key].primitiveString()).length) }
    val base = if (c.type == "status") 120f else longest * 7.6f + 28f
    return base.coerceIn(84f, 280f)
}

@Composable
private fun ReportTable(columns: List<ExportCol>, rows: List<Row>, totals: Map<String, Double>?, onOpen: (String) -> Unit) {
    val hasTotals = columns.any { it.total }
    var shown by remember(rows) { mutableIntStateOf(ROW_CHUNK) }
    val widths = remember(columns, rows) { columns.map { columnWidth(it, rows) } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val avail = maxWidth.value
        val natural = widths.sum().coerceAtLeast(1f)
        val target = maxOf(natural, 640f, avail)
        val scaled: List<Dp> = widths.map { (it * target / natural).dp }
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                // header
                Row(Modifier.padding(vertical = 10.dp)) {
                    columns.forEachIndexed { i, c ->
                        Text(c.label.uppercase(), Modifier.width(scaled[i]).padding(horizontal = 10.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            color = LF.MutedForeground, letterSpacing = 0.4.sp, textAlign = if (numeric(c)) TextAlign.End else TextAlign.Start, maxLines = 2)
                    }
                }
                HorizontalDivider(Modifier.width(scaled.fold(0.dp) { a, b -> a + b }), color = LF.Border)
                val visible = rows.take(shown)
                visible.forEachIndexed { ri, r ->
                    val loanId = r["loan_id"].primitiveString()
                    Row(
                        Modifier.then(if (loanId != null) Modifier.clickable { onOpen("/loans/$loanId") } else Modifier).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        columns.forEachIndexed { i, c ->
                            Box(Modifier.width(scaled[i]).padding(horizontal = 10.dp), contentAlignment = if (numeric(c)) Alignment.CenterEnd else Alignment.CenterStart) {
                                ReportCell(c, r[c.key].primitiveString(), r, onOpen)
                            }
                        }
                    }
                    if (ri < visible.size - 1 || hasTotals) HorizontalDivider(Modifier.width(scaled.fold(0.dp) { a, b -> a + b }), color = LF.Border)
                }
                if (hasTotals && shown >= rows.size) {
                    HorizontalDivider(Modifier.width(scaled.fold(0.dp) { a, b -> a + b }), thickness = 1.dp, color = LF.Border)
                    Row(Modifier.background(LF.Slate50).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProvideTextStyle(TextStyle(fontWeight = FontWeight.SemiBold)) {
                            columns.forEachIndexed { i, c ->
                                Box(Modifier.width(scaled[i]).padding(horizontal = 10.dp), contentAlignment = if (numeric(c)) Alignment.CenterEnd else Alignment.CenterStart) {
                                    when {
                                        c.total -> ReportCell(c, (totals?.get(c.key) ?: 0.0).toString())
                                        i == 0 -> Text("Total (${rows.size})", fontSize = 14.sp)
                                        else -> {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (shown < rows.size) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("Showing $shown of ${rows.size} rows", fontSize = 12.sp, color = LF.MutedForeground)
                    Spacer(Modifier.width(10.dp))
                    Btn("Show more", { shown += ROW_CHUNK }, variant = BtnVariant.Outline, small = true)
                    Spacer(Modifier.width(6.dp))
                    Btn("Show all", { shown = rows.size }, variant = BtnVariant.Ghost, small = true)
                }
            }
        }
    }
}
