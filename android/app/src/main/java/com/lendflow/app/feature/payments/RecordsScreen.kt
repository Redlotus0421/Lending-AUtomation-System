package com.lendflow.app.feature.payments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.RecordsService
import com.lendflow.app.data.Row
import com.lendflow.app.data.bool
import com.lendflow.app.data.int
import com.lendflow.app.data.jsonOf
import com.lendflow.app.data.num
import com.lendflow.app.data.objOrFirst
import com.lendflow.app.data.objs
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.feature.borrowers.BorrowerAvatar
import com.lendflow.app.feature.payments.receipt.ReceiptDialog
import com.lendflow.app.ui.components.Align
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CellRole
import com.lendflow.app.ui.components.CellText
import com.lendflow.app.ui.components.Col
import com.lendflow.app.ui.components.DataTable
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.ExportMenu
import com.lendflow.app.ui.components.LoanStatusBadge
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.SearchField
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.components.StatCard
import com.lendflow.app.ui.components.StatTone
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.rememberDebounced
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.ExportCol
import com.lendflow.app.util.FREQUENCY_LABEL
import com.lendflow.app.util.PdfSection
import com.lendflow.app.util.exportCsv
import com.lendflow.app.util.exportPdfTable
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.pdfReportBytes
import com.lendflow.app.util.peso
import com.lendflow.app.util.phMobile
import com.lendflow.app.util.printPdf
import com.lendflow.app.util.todayManila

// Port of src/features/payments/RecordsPage.tsx — Payment Records (Collections): pick a borrower, see every
// scheduled installment of a loan next to what was actually paid and when, plus the loan's payment history.
// Read-only: every figure comes from the posted payments and the loan's schedule.

private val EXPORT_COLUMNS = listOf(
    ExportCol("no", "#", "number"),
    ExportCol("due_date", "Due date", "date"),
    ExportCol("scheduled", "Scheduled amount", "money", true),
    ExportCol("paid", "Amount paid", "money", true),
    ExportCol("paid_date", "Payment date", "date"),
    ExportCol("payments", "Payment #"),
    ExportCol("status", "Status"),
    ExportCol("remaining", "Remaining", "money", true),
)

private fun receiptNo(p: Row): String? = p.objOrFirst("payment_receipts").strOrNull("receipt_number")

@Composable
fun RecordsScreen(borrowerId: String?, loanId: String?) {
    val router = LocalRouter.current
    val borrowerQ = rememberLoad(borrowerId, enabled = borrowerId != null) { borrowerId?.let { RecordsService.borrower(it) } }
    val loansQ = rememberLoad(borrowerId, enabled = borrowerId != null) { borrowerId?.let { RecordsService.loans(it) } ?: emptyList() }
    val loans = loansQ.data.orEmpty()
    // default: the borrower's active (or defaulted) loan, otherwise the most recent one
    val loan = loans.firstOrNull { it.str("id") == loanId }
        ?: loans.firstOrNull { it.str("status") == "active" || it.str("status") == "defaulted" } ?: loans.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageHeader("Payment Records", "A borrower's full repayment schedule next to what was actually paid. Figures come straight from posted payments — nothing is entered here.")

        if (borrowerId == null) {
            BorrowerPicker { b -> router.go("/records?borrower=${b.str("id")}") }
        } else if (borrowerQ.error != null) {
            ErrorState(borrowerQ.error, onRetry = borrowerQ.reload)
        } else {
            val b = borrowerQ.data
            Panel {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.widthIn(min = 200.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (b != null) BorrowerAvatar(b.str("full_name"), b.strOrNull("profile_photo_path"), 44.dp)
                        else Box(Modifier.size(44.dp).clip(CircleShape).background(LF.Muted))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(b?.str("full_name") ?: "…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (b != null) "${b.str("borrower_code")} · ${phMobile(b.strOrNull("mobile_number"))}" else "", fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("Profile", { router.go("/borrowers/$borrowerId") }, variant = BtnVariant.Outline, icon = Icons.Default.Person)
                        Btn("Change borrower", { router.go("/records") }, variant = BtnVariant.Outline, icon = Icons.Default.Search)
                    }
                }
                if (loans.size > 1) {
                    Row(Modifier.padding(top = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        loans.forEach { l ->
                            val sel = l.str("id") == loan?.str("id")
                            Column(
                                Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) LF.Blue50 else Color.White)
                                    .border(2.dp, if (sel) LF.Blue else LF.Slate200, RoundedCornerShape(8.dp))
                                    .clickable { router.replace("/records?borrower=$borrowerId&loan=${l.str("id")}") }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(l.str("loan_number"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                val st = when (l.str("status")) { "completed" -> "Completed"; "defaulted" -> "Defaulted"; else -> "Active" }
                                Text("${peso(l.num("principal"))} · $st", fontSize = 12.sp, color = LF.MutedForeground)
                            }
                        }
                    }
                }
            }

            when {
                loansQ.initialLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) { Spinner(24.dp) }
                loansQ.error != null -> ErrorState(loansQ.error, onRetry = loansQ.reload)
                loan == null -> Panel {
                    EmptyState("No released loans yet", description = "Records appear once a loan for this borrower has been released.", icon = Icons.Default.Payments)
                }
                else -> LoanRecord(loan, b?.str("full_name") ?: loan.str("borrower_name"))
            }
        }
    }
}

@Composable
private fun BorrowerPicker(onPick: (Row) -> Unit) {
    var term by remember { mutableStateOf("") }
    val debounced = rememberDebounced(term)
    val q = rememberLoad(debounced) { RecordsService.searchBorrowers(debounced, 12) }
    Panel(title = "Select a borrower", description = "Search by name, borrower code or mobile number.") {
        SearchField(term, { term = it }, Modifier.widthIn(max = 576.dp), placeholder = "e.g. Dela Cruz, BRW-000001, 0917…")
        Spacer(Modifier.height(12.dp))
        val rows = q.data
        when {
            q.initialLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { Spinner(24.dp) }
            q.error != null -> ErrorState(q.error, onRetry = q.reload)
            rows.isNullOrEmpty() -> EmptyState("No borrowers found", icon = Icons.Default.Person)
            else -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, LF.Border, RoundedCornerShape(8.dp)).background(Color.White)) {
                rows.forEachIndexed { i, b ->
                    if (i > 0) HorizontalDivider(color = LF.Border)
                    Row(Modifier.fillMaxWidth().clickable { onPick(b) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        BorrowerAvatar(b.str("full_name"), b.strOrNull("profile_photo_path"), 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.str("full_name"), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${b.str("borrower_code")} · ${phMobile(b.strOrNull("mobile_number"))}", fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1)
                        }
                        Icon(Icons.Default.Assignment, null, Modifier.size(16.dp), tint = LF.MutedForeground)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoanRecord(loan: Row, borrowerName: String) {
    val router = LocalRouter.current
    val ctx = LocalContext.current
    val auth by AppState.state.collectAsState()
    var reprint by remember { mutableStateOf<Row?>(null) }
    val today = todayManila()
    val loanId = loan.str("id")
    val instQ = rememberLoad(loanId) { RecordsService.installments(loanId) }
    val allocQ = rememberLoad(loanId) { RecordsService.allocations(loanId) }
    val payQ = rememberLoad(loanId) { RecordsService.payments(loanId) }

    val rows = remember(instQ.data, allocQ.data, today) { buildRecordRows(instQ.data.orEmpty(), allocQ.data.orEmpty(), today) }
    val live = rows.filter { it.status != "rescheduled" }
    val counts = live.groupingBy { it.status }.eachCount()
    fun c(k: String) = counts[k] ?: 0
    val paidCount = c("paid_on_time") + c("paid_late") + c("waived")
    val overdueAmount = live.filter { it.status == "overdue" }.sumOf { it.remaining }
    val loading = (instQ.loading && instQ.data == null) || (allocQ.loading && allocQ.data == null)
    val error = instQ.error ?: allocQ.error

    fun plural(n: Int) = if (n == 1) "" else "s"
    val exportRows = rows.map { r ->
        jsonOf(
            "no" to r.inst.int("installment_no"), "due_date" to r.inst.str("due_date"), "scheduled" to r.scheduled, "paid" to r.paid,
            "paid_date" to r.paidDate, "payments" to r.payments.joinToString(", ") { it.paymentNumber },
            "status" to RECORD_STATUS.getValue(r.status).label + (if (r.days != 0) " (${r.days} day${plural(r.days)})" else ""),
            "remaining" to r.remaining,
        )
    }
    val fileBase = "records-${loan.str("loan_number")}"
    val freq = FREQUENCY_LABEL[loan.str("repayment_frequency")] ?: loan.str("repayment_frequency")
    val subtitle = "$borrowerName · ${loan.str("loan_number")} · $freq · as of ${fmtDate(today, "long")}"
    val org = auth.organization.strOrNull("legal_name")?.ifBlank { null } ?: auth.organization.strOrNull("name")

    val columns = listOf<Col<RecordRow>>(
        Col("#", 0.5f, mobile = CellRole.Title, minWidth = 50.dp) { r -> Text("#${r.inst.str("installment_no")}", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
        Col("Due date", 1f, mobile = CellRole.Subtitle) { r -> CellText(fmtDate(r.inst.str("due_date"))) },
        Col("Scheduled", 1f, Align.End, mobile = CellRole.Meta) { r -> CellText(peso(r.scheduled)) },
        Col("Amount paid", 1.1f, Align.End, mobile = CellRole.Value) { r ->
            Column(horizontalAlignment = Alignment.End) {
                Text(peso(r.paid), fontSize = 14.sp, fontWeight = if (r.paid > 0) FontWeight.SemiBold else FontWeight.Normal, color = if (r.paid > 0) LF.Foreground else LF.MutedForeground)
                if (r.rebate > 0) Text("+ ${peso(r.rebate)} rebate", fontSize = 11.sp, color = LF.Teal700)
            }
        },
        Col("Payment date", 1.2f, mobile = CellRole.Meta, minWidth = 110.dp) { r ->
            if (r.payments.isEmpty()) CellText("—", muted = true) else Column {
                Text(fmtDate(r.paidDate), fontSize = 14.sp)
                if (r.payments.size > 1) Text("${r.payments.size} payments: ${r.payments.joinToString(", ") { fmtDate(it.paymentDate) }}", fontSize = 11.sp, color = LF.MutedForeground)
            }
        },
        Col("Status", 1.2f, mobile = CellRole.Meta, minWidth = 120.dp) { r ->
            val info = RECORD_STATUS.getValue(r.status)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Pill(info.label, info.tone)
                when {
                    r.status == "paid_late" -> Text("${r.days} day${plural(r.days)} late", fontSize = 11.sp, color = LF.MutedForeground)
                    r.status == "overdue" -> Text("${r.days} day${plural(r.days)} past due${if (r.paid > 0) " · partly paid" else ""}", fontSize = 11.sp, color = LF.Red700)
                    r.status == "upcoming" && r.inst.str("due_date") == today -> Text("Due today", fontSize = 11.sp, color = LF.Amber800)
                }
            }
        },
        Col("Remaining", 1f, Align.End, mobile = CellRole.Meta) { r -> CellText(peso(if (r.remaining > 0) r.remaining else 0.0), muted = r.remaining <= 0) },
    )

    val payColumns = listOf<Col<Row>>(
        Col("Date", 0.9f, mobile = CellRole.Subtitle) { p -> CellText(fmtDate(p.str("payment_date"))) },
        Col("Payment / receipt", 1.4f, mobile = CellRole.Title, minWidth = 150.dp) { p ->
            Text(p.str("payment_number") + (receiptNo(p)?.let { " · $it" } ?: ""), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
        },
        Col("Applied to", 0.9f, mobile = CellRole.Meta) { p ->
            val nos = p.objs("payment_allocations").filter { !it.bool("is_reversal") }
                .mapNotNull { it.objOrFirst("loan_installments")?.int("installment_no")?.takeIf { n -> n > 0 } }.distinct().sorted()
            CellText(if (nos.isEmpty()) "—" else nos.joinToString(", ") { "#$it" })
        },
        Col("Type", 0.8f, mobile = CellRole.Hidden) { p -> CellText(p.str("payment_type").replaceFirstChar { it.uppercase() }, muted = true) },
        Col("Method", 0.9f, mobile = CellRole.Meta) { p -> CellText(p.str("method").replace("_", " ").replaceFirstChar { it.uppercase() }) },
        Col("Status", 0.8f, mobile = CellRole.Meta) { p -> PostedPill(p.str("status")) },
        Col("Amount", 0.9f, Align.End, mobile = CellRole.Value) { p ->
            val rev = p.str("status") == "reversed"
            Text(peso(p.num("amount")), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (rev) LF.MutedForeground else LF.Foreground,
                textDecoration = if (rev) TextDecoration.LineThrough else null)
        },
        Col("", 0.9f, Align.End, mobile = CellRole.Meta, minWidth = 110.dp) { p ->
            if (receiptNo(p) != null) Btn("Reprint", { reprint = p }, variant = BtnVariant.Outline, icon = Icons.Default.Print, small = true)
        },
    )

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        val total = loan.num("total_paid") + loan.num("total_outstanding")
        StatGrid(if (isCompact()) 2 else 4, listOf(
            1 to { m -> StatCard("Total paid", peso(loan.num("total_paid")), m, hint = "$paidCount of ${live.size} installments paid", icon = Icons.Default.Wallet, tone = StatTone.green) },
            1 to { m -> StatCard("Remaining balance", peso(loan.num("total_outstanding")), m, hint = "Total payable ${peso(total)}", icon = Icons.Default.Payments, tone = StatTone.navy) },
            1 to { m -> StatCard("Overdue", peso(overdueAmount), m, hint = "${c("overdue")} installment${plural(c("overdue"))}", icon = Icons.Default.Warning, tone = StatTone.red) },
            1 to { m ->
                StatCard("Paid on time", "${c("paid_on_time")} / ${c("paid_on_time") + c("paid_late")}", m,
                    hint = "${c("paid_late")} paid late · ${c("upcoming") + c("partially_paid")} upcoming", icon = Icons.Default.EventAvailable, tone = StatTone.teal)
            },
        ))

        Panel(
            title = "Repayment schedule",
            description = "${loan.str("loan_number")} · ${peso(loan.num("principal"))} · $freq · released ${fmtDate(loan.strOrNull("actual_release_date") ?: loan.strOrNull("release_date"))}",
            actions = {
                LoanStatusBadge(loan.str("status"))
                Btn("Open loan", { router.go("/loans/$loanId") }, variant = BtnVariant.Outline, small = true)
                ExportMenu(
                    disabled = loading || rows.isEmpty(), small = true,
                    onCsv = { exportCsv(ctx, fileBase, EXPORT_COLUMNS, exportRows) },
                    onPdf = { exportPdfTable(ctx, fileBase, "Payment record", EXPORT_COLUMNS, exportRows, subtitle = subtitle, organization = org, landscape = true) },
                    onPrint = {
                        val bytes = pdfReportBytes("Payment record", listOf(PdfSection(EXPORT_COLUMNS, exportRows)), subtitle, org, landscape = true)
                        printPdf(ctx, "Payment record ${loan.str("loan_number")}", bytes)
                    },
                )
            },
        ) {
            if (error != null) {
                ErrorState(error, onRetry = { instQ.reload(); allocQ.reload() })
            } else {
                DataTable(
                    columns, if (loading) null else rows, loading = loading,
                    empty = { EmptyState("No schedule yet") },
                    footer = {
                        val sched = peso(live.sumOf { it.scheduled })
                        val paid = peso(rows.sumOf { it.paid })
                        val rem = peso(rows.sumOf { it.remaining })
                        if (isCompact()) {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(LF.Muted).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Total", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                FooterLine("Scheduled", sched); FooterLine("Amount paid", paid); FooterLine("Remaining", rem)
                            }
                        } else {
                            HorizontalDivider(color = LF.Border)
                            Row(Modifier.fillMaxWidth().background(LF.Muted.copy(alpha = 0.6f)).padding(vertical = 10.dp)) {
                                val cells = listOf("Total", "", sched, paid, "", "", rem)
                                columns.forEachIndexed { i, col ->
                                    Box(Modifier.weight(col.weight).widthIn(min = col.minWidth).padding(horizontal = 10.dp),
                                        contentAlignment = if (col.align == Align.End) Alignment.CenterEnd else Alignment.CenterStart) {
                                        Text(cells[i], fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                }
                            }
                        }
                    },
                )
            }
            if (rows.any { it.status == "rescheduled" }) {
                Text("Rescheduled installments were replaced by the amended schedule; their payments stay on record.",
                    Modifier.padding(top = 12.dp), fontSize = 12.sp, color = LF.MutedForeground)
            }
        }

        Panel(title = "Payment history", description = "Every payment recorded for this loan, newest first. Tap a payment to see its receipt.") {
            if (payQ.error != null) ErrorState(payQ.error, onRetry = payQ.reload)
            else DataTable(
                payColumns, payQ.data, loading = payQ.loading,
                onRowClick = { p -> router.go("/payments/${p.str("id")}") },
                empty = { EmptyState("No payments recorded yet", icon = Icons.Default.Wallet) },
            )
        }
    }

    // reprinting reads the stored receipt; no payment or receipt is created
    reprint?.let { p -> ReceiptDialog(p.str("id"), reprint = true, onClose = { reprint = null }) }
}

@Composable
private fun FooterLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = LF.MutedForeground)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
