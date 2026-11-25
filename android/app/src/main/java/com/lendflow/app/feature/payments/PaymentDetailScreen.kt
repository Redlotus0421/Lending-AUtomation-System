package com.lendflow.app.feature.payments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.PaymentsService
import com.lendflow.app.data.Row
import com.lendflow.app.data.Supa
import com.lendflow.app.data.bool
import com.lendflow.app.data.has
import com.lendflow.app.data.int
import com.lendflow.app.data.num
import com.lendflow.app.data.obj
import com.lendflow.app.data.objOrFirst
import com.lendflow.app.data.objs
import com.lendflow.app.data.readable
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.feature.payments.receipt.ReceiptDialog
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.ConfirmDialog
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.LinkText
import com.lendflow.app.ui.components.LoanStatusBadge
import com.lendflow.app.ui.components.Notice
import com.lendflow.app.ui.components.PageLoader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.card
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.rememberDownloader
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberPdfViewer
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Tone
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.fmtDateTime
import com.lendflow.app.util.longDate
import com.lendflow.app.util.peso
import com.lendflow.app.util.printPdf
import com.lendflow.app.util.titleCase
import kotlinx.coroutines.launch

// Port of src/features/payments/PaymentDetailPage.tsx

@Composable
fun PaymentDetailScreen(id: String) {
    val q = rememberLoad(id) { PaymentsService.get(id) }
    val p = q.data
    when {
        q.initialLoading -> PageLoader()
        q.error != null || p == null -> ErrorState(q.error ?: "Payment not found", onRetry = q.reload)
        else -> PaymentDetail(p)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentDetail(p: Row) {
    val router = LocalRouter.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth by AppState.state.collectAsState()
    val pdf = rememberPdfViewer()
    val download = rememberDownloader()
    var reverseOpen by remember { mutableStateOf(false) }
    var thermalOpen by remember { mutableStateOf(false) }
    var printBusy by remember { mutableStateOf(false) }

    val id = p.str("id")
    val receipt = p.receiptOf()
    val allocations = p.objs("payment_allocations").sortedWith(
        compareBy<Row> { if (it.bool("is_reversal")) 1 else 0 }.thenBy { it.objOrFirst("loan_installments")?.int("installment_no") ?: 0 },
    )
    val reversed = p.str("status") == "reversed"
    val r = receipt?.obj("data") ?: com.lendflow.app.data.EmptyRow
    val loanNo = p.obj("loans").str("loan_number")
    val pn = p.str("payment_number")

    fun printReceipt() {
        printBusy = true
        scope.launch {
            try {
                val url = PaymentsService.receiptPdf(id, false).str("url")
                val bytes = Supa.download(url)
                printPdf(ctx, "Receipt ${receipt.str("receipt_number")}", bytes)
            } catch (e: Exception) {
                Toasts.error(e.readable())
            } finally {
                printBusy = false
            }
        }
    }
    fun openProof() {
        val path = p.str("proof_path")
        scope.launch {
            try {
                val url = PaymentsService.proofUrl(path)
                val ext = path.substringAfterLast('.', "").lowercase()
                val mime = when (ext) { "pdf" -> "application/pdf"; "png" -> "image/png"; "webp" -> "image/webp"; "heic" -> "image/heic"; else -> "image/jpeg" }
                download(url, path.substringAfterLast('/'), mime, false)
            } catch (e: Exception) {
                Toasts.error(e.readable())
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // header
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { router.back() }.padding(vertical = 4.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(14.dp), tint = LF.MutedForeground)
                Spacer(Modifier.width(4.dp))
                Text("Back", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground)
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pn, style = MaterialTheme.typography.headlineSmall, color = LF.Foreground)
                PostedPill(p.str("status"))
            }
            Spacer(Modifier.height(4.dp))
            FlowRow {
                Text("Receipt ${receipt?.strOrNull("receipt_number") ?: "—"} · ${p.obj("borrowers").str("full_name")} · ", fontSize = 14.sp, color = LF.MutedForeground)
                LinkText(loanNo, { router.go("/loans/${p.str("loan_id")}") })
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Print receipt", { printReceipt() }, variant = BtnVariant.Outline, icon = Icons.Default.Print, loading = printBusy, enabled = receipt != null)
                Btn("Thermal receipt", { thermalOpen = true }, variant = BtnVariant.Outline, icon = Icons.Default.ReceiptLong, enabled = receipt != null)
                Btn("Download PDF", {
                    pdf.openUrl("Receipt ${receipt.str("receipt_number")}", "receipt-${receipt.str("receipt_number").ifBlank { pn }}.pdf") {
                        PaymentsService.receiptPdf(id, true).str("url")
                    }
                }, variant = BtnVariant.Outline, icon = Icons.Default.Download, enabled = receipt != null)
                if (auth.can("payments.reverse") && !reversed) {
                    Btn("Reverse", { reverseOpen = true }, variant = BtnVariant.Outline, icon = Icons.Default.Undo)
                }
            }
        }

        if (reversed) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LF.Red50).border(1.dp, LF.Red200, RoundedCornerShape(12.dp)).padding(16.dp)) {
                Icon(Icons.Default.GppMaybe, null, Modifier.size(20.dp), tint = LF.Red800)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("This payment was reversed on ${fmtDateTime(p.strOrNull("reversed_at"))}.", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LF.Red800)
                    Text("Reason: ${p.str("reversal_reason")}", fontSize = 14.sp, color = LF.Red800, modifier = Modifier.padding(top = 2.dp))
                    Text("The original entries are preserved; offsetting reversal entries restored the installment and loan balances. The receipt is void.",
                        fontSize = 12.sp, color = LF.Red800.copy(alpha = 0.8f), modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        val details: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Panel(title = "Payment details") {
                    val items = listOf<Pair<String, @Composable () -> Unit>>(
                        "Amount" to { Text(peso(p.num("amount")), fontSize = 16.sp, fontWeight = FontWeight.Medium) },
                        "Payment date" to { V(fmtDate(p.str("payment_date"), "long")) },
                        "Type" to { V(if (p.str("payment_type") == "settlement") "Early settlement" else "Regular") },
                        "Method" to { V(titleCase(p.str("method"))) },
                        "Reference" to { V(p.strOrNull("reference_no")?.ifBlank { null } ?: "—") },
                        "Loan" to {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LinkText(loanNo, { router.go("/loans/${p.str("loan_id")}") })
                                p.obj("loans").strOrNull("status")?.let { Spacer(Modifier.width(8.dp)); LoanStatusBadge(it) }
                            }
                        },
                        "Borrower" to { LinkText(p.obj("borrowers").str("full_name"), { router.go("/borrowers/${p.str("borrower_id")}") }) },
                        "Received by" to { V(r.strOrNull("collector_name") ?: "—") },
                        "Recorded" to { V("${fmtDateTime(p.strOrNull("created_at"))}${r.strOrNull("recorded_by")?.let { " by $it" } ?: ""}") },
                        "Balance after payment" to { V(if (p.has("balance_after")) peso(p.num("balance_after")) else "—") },
                    ) + (if (!p.strOrNull("notes").isNullOrBlank()) listOf<Pair<String, @Composable () -> Unit>>("Notes" to { V(p.str("notes")) }) else emptyList())
                    val cols = if (isCompact()) 2 else 3
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items.chunked(cols).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                row.forEach { (label, value) ->
                                    Column(Modifier.weight(1f)) {
                                        Text(label, fontSize = 12.sp, color = LF.MutedForeground)
                                        Box(Modifier.padding(top = 2.dp)) { value() }
                                    }
                                }
                                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    if (!p.strOrNull("proof_path").isNullOrBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Btn("View proof of payment", { openProof() }, variant = BtnVariant.Outline, icon = Icons.Default.AttachFile, small = true)
                    }
                }

                Panel(title = "Allocation by installment", description = "How the payment was applied, following the loan's allocation order", contentPadding = 0.dp) {
                    AllocationTable(p, allocations)
                }
            }
        }
        val receiptBox: @Composable () -> Unit = {
            if (receipt != null) {
                Box(Modifier.fillMaxWidth().card().padding(24.dp)) { ReceiptView(r, receipt.bool("is_void")) }
            } else {
                Panel(title = "Receipt") { Text("No receipt was issued for this payment.", fontSize = 14.sp, color = LF.MutedForeground) }
            }
        }

        if (widthClass() == WidthClass.Expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.weight(1f)) { details() }
                Box(Modifier.width(420.dp)) { receiptBox() }
            }
        } else {
            details()
            receiptBox()
        }
    }

    if (reverseOpen) {
        ConfirmDialog(
            title = "Reverse $pn?",
            onDismiss = { reverseOpen = false },
            onConfirm = { reason -> PaymentsService.reverse(id, reason) },
            message = "This posts offsetting entries for ${peso(p.num("amount"))}, restores the installment and loan balances, and voids receipt ${receipt?.strOrNull("receipt_number") ?: ""}. Nothing is deleted.",
            confirmText = "Reverse payment",
            destructive = true,
            reasonLabel = "Reason for reversal",
            successMessage = "$pn reversed",
            extra = {
                Notice("Only the most recent payment on a loan can be reversed (last in, first out). To correct an older payment, reverse the later payments first, then re-record them.", Tone.amber)
            },
        )
    }
    if (thermalOpen) ReceiptDialog(id, reprint = true, onClose = { thermalOpen = false })
}

@Composable
private fun V(text: String) = Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Foreground)

@Composable
private fun AllocationTable(p: Row, allocations: List<Row>) {
    val widths = listOf(200.dp, 90.dp, 90.dp, 90.dp, 100.dp, 90.dp, 100.dp)
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.widthIn(min = 620.dp)) {
            @Composable
            fun cells(values: List<@Composable () -> Unit>, bg: Color = Color.Transparent) {
                Row(Modifier.fillMaxWidth().background(bg).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    values.forEachIndexed { i, v ->
                        Box(Modifier.width(widths[i]).padding(horizontal = if (i == 0 || i == values.lastIndex) 16.dp else 12.dp),
                            contentAlignment = if (i == 0) Alignment.CenterStart else Alignment.CenterEnd) { v() }
                    }
                }
            }
            val head = listOf("Installment", "Penalty", "Charges", "Interest", "Principal", "Rebate", "Total")
            cells(head.map { h -> { Text(h.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground, letterSpacing = 0.4.sp) } })
            HorizontalDivider(color = LF.Border)
            allocations.forEach { a ->
                val rev = a.bool("is_reversal")
                val fg = if (rev) LF.Red800 else LF.Foreground
                val inst = a.objOrFirst("loan_installments")
                val nums = listOf("penalty_amount", "charges_amount", "interest_amount", "principal_amount", "interest_rebate").map { a.num(it) }
                cells(
                    listOf<@Composable () -> Unit>({
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("#${inst.str("installment_no")}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg)
                            Text("  due ${fmtDate(inst.strOrNull("due_date"))}", fontSize = 12.sp, color = LF.MutedForeground)
                            if (rev) { Spacer(Modifier.width(6.dp)); Pill("Reversal", Tone.red) }
                        }
                    }) + nums.map { v -> { Text(peso(v), fontSize = 14.sp, color = if (v == 0.0) LF.MutedForeground else fg) } } +
                        listOf<@Composable () -> Unit>({ Text(peso(a.num("total_amount")), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg) }),
                    if (rev) LF.Red50.copy(alpha = 0.5f) else Color.Transparent,
                )
                HorizontalDivider(color = LF.Border)
            }
            val totals = listOf("penalty_applied", "charges_applied", "interest_applied", "principal_applied", "interest_rebated", "amount")
            cells(
                listOf<@Composable () -> Unit>({ Text("Applied (original)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }) +
                    totals.map { k -> { Text(peso(p.num(k)), fontSize = 14.sp, fontWeight = FontWeight.SemiBold) } },
                LF.Slate50,
            )
        }
    }
}

/** The formal receipt shown beside the payment (ReceiptView on the web). */
@Composable
private fun ReceiptView(r: Row, isVoid: Boolean) {
    val c = r.obj("company")
    Box {
        Column {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(LF.Navy))
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.End) {
                Text(c.strOrNull("legal_name")?.ifBlank { null } ?: c.str("name"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LF.Navy, textAlign = TextAlign.End)
                c.strOrNull("address")?.ifBlank { null }?.let { Text(it, fontSize = 12.sp, color = LF.Slate500, textAlign = TextAlign.End) }
                listOfNotNull(c.strOrNull("phone")?.ifBlank { null }, c.strOrNull("email")?.ifBlank { null }).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), fontSize = 12.sp, color = LF.Slate500, textAlign = TextAlign.End)
                }
                c.strOrNull("tin")?.ifBlank { null }?.let { Text("TIN $it", fontSize = 12.sp, color = LF.Slate500) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("PAYMENT RECEIPT", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Issued ${fmtDateTime(r.strOrNull("issued_at"))}", fontSize = 12.sp, color = LF.Slate500)
                }
                Text("No. ${r.str("receipt_number")}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LF.Red600)
            }
            HorizontalDivider(Modifier.padding(top = 12.dp), color = LF.Border)
            val inst = r.objs("installments")
            val lines = listOf(
                Triple("Received from", r.strOrNull("borrower_name"), true),
                Triple("Borrower code", r.strOrNull("borrower_code"), false),
                Triple("Loan reference", r.strOrNull("loan_number"), false),
                Triple("Payment date", longDate(r.strOrNull("payment_date")), false),
                Triple("Method", titleCase(r.strOrNull("method")), false),
                Triple("Reference", r.strOrNull("reference_no"), false),
                Triple("Installment(s)", if (inst.isNotEmpty()) inst.joinToString(", ") { "#${it.str("installment_no")}" } else null, false),
                Triple("Payment type", if (r.str("payment_type") == "settlement") "Early full settlement" else null, true),
            ).filter { !it.second.isNullOrBlank() }
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { (l, v, b) -> RLine(l, v!!, bold = b) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(8.dp)).background(LF.Slate100).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("AMOUNT RECEIVED", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LF.Navy, letterSpacing = 0.5.sp)
                Text(peso(r.num("amount")), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text("ALLOCATION", Modifier.padding(top = 16.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LF.Navy, letterSpacing = 0.5.sp)
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RLine("Principal", peso(r.num("principal_paid")))
                RLine("Interest", peso(r.num("interest_paid")))
                RLine("Charges", peso(r.num("charges_paid")))
                RLine("Penalties", peso(r.num("penalty_paid")))
                if (r.num("interest_rebated") > 0) RLine("Unearned interest rebated", peso(r.num("interest_rebated")))
                HorizontalDivider(color = LF.Border)
                RLine("Remaining loan balance", peso(r.num("remaining_balance")), bold = true, strongLabel = true)
                RLine("Outstanding principal", peso(r.num("outstanding_principal")))
            }
            Row(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalArrangement = Arrangement.End) {
                Column(Modifier.width(192.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    HorizontalDivider(color = LF.Foreground)
                    Text(r.strOrNull("collector_name")?.ifBlank { null } ?: r.str("recorded_by"), Modifier.padding(top = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text("Received by", fontSize = 11.sp, color = LF.Slate500)
                }
            }
            r.strOrNull("footer_note")?.ifBlank { null }?.let { Text(it, Modifier.padding(top = 24.dp), fontSize = 11.sp, color = LF.Slate500, lineHeight = 16.sp) }
            Text("Payment ${r.str("payment_number")}", Modifier.padding(top = 8.dp), fontSize = 10.sp, color = LF.Slate400)
        }
        if (isVoid) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                Text("VOID", Modifier.rotate(-25f), fontSize = 96.sp, fontWeight = FontWeight.Black, color = LF.Red500.copy(alpha = 0.15f), letterSpacing = 8.sp)
            }
        }
    }
}

@Composable
private fun RLine(label: String, value: String, bold: Boolean = false, strongLabel: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, fontSize = 13.sp, color = if (strongLabel) LF.Foreground else LF.Slate500, fontWeight = if (strongLabel) FontWeight.SemiBold else FontWeight.Normal)
        Text(value, Modifier.weight(1f), fontSize = 13.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.End)
    }
}

@Suppress("unused") private fun ColumnScope.keep() = Unit
