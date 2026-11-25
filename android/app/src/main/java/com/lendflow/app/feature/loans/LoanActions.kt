package com.lendflow.app.feature.loans

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.data.LoansService
import com.lendflow.app.data.PaymentsService
import com.lendflow.app.data.Row
import com.lendflow.app.data.UsersService
import com.lendflow.app.data.arr
import com.lendflow.app.data.bool
import com.lendflow.app.data.int
import com.lendflow.app.data.num
import com.lendflow.app.data.objOrFirst
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.toNum
import com.lendflow.app.ui.components.AppDialog
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.DateField
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.FormRow
import com.lendflow.app.ui.components.MoneyInput
import com.lendflow.app.ui.components.NumberInput
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.FREQUENCIES
import com.lendflow.app.util.Opt
import com.lendflow.app.util.PAYMENT_METHODS
import com.lendflow.app.util.RATE_PERIODS
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.peso
import com.lendflow.app.util.round2
import com.lendflow.app.util.todayManila

// ---------------------------------------------------------------------------
// Loan lifecycle dialogs (LoanActions.tsx). Each dialog is shown while it is composed.
// ---------------------------------------------------------------------------

@Composable
private fun MiniFigure(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = LF.Foreground, bold: Boolean = false) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = LF.MutedForeground)
        Text(value, fontSize = 14.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium, color = valueColor)
    }
}

@Composable
fun ApproveDialog(loan: Row, onDismiss: () -> Unit, onDone: () -> Unit) {
    var notes by remember { mutableStateOf("") }
    val runner = rememberRunner()
    AppDialog(
        title = "Approve ${loan.str("loan_number")}",
        onDismiss = { if (!runner.busy) onDismiss() },
        description = "Approval is recorded separately from signing and disbursement. The loan can only be released after approval and all signatures.",
        maxWidth = 480.dp,
        footer = {
            Btn("Cancel", onDismiss, variant = BtnVariant.Outline)
            Btn("Approve loan", {
                runner.run {
                    val r = LoansService.approve(loan.str("id"), notes)
                    Toasts.success(if (r.str("status") == "ready_for_release") "Approved — the loan is ready for release" else "Loan approved")
                    onDismiss()
                    onDone()
                }
            }, loading = runner.busy)
        },
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Slate50).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniFigure("Borrower", loan.str("borrower_name"), Modifier.weight(1f))
                MiniFigure("Principal", peso(loan.num("principal")), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniFigure("Total repayment", peso(loan.num("total_repayment")), Modifier.weight(1f))
                MiniFigure("Installment", "${peso(loan.num("installment_amount"))} × ${loan.int("installment_count")}", Modifier.weight(1f))
            }
        }
        Field("Approval notes") {
            TextInput(notes, { notes = it }, singleLine = false, minLines = 3, placeholder = "Credit assessment, conditions…")
        }
    }
}

/** Release funds dialog (LoanActions.tsx ReleaseDialog). Shown while composed. */
@Composable
fun ReleaseDialog(loan: Row, onDismiss: () -> Unit, onDone: () -> Unit) {
    val id = loan.str("id")
    val renewals = rememberLoanRenewals(id)
    val pendingRenewals = (renewals.data ?: emptyList()).filter { it.str("new_loan_id") == id && it.str("status") == "pending" }
    val estimate = pendingRenewals.sumOf { it.num("quoted_amount") }
    var date by remember { mutableStateOf(todayManila()) }
    var method by remember { mutableStateOf("cash") }
    var reference by remember { mutableStateOf("") }
    val runner = rememberRunner()
    val deducted = loan.num("total_charges") - loan.num("financed_charges")
    AppDialog(
        title = "Release funds",
        onDismiss = { if (!runner.busy) onDismiss() },
        description = "Records the disbursement in the ledger, generates the official repayment schedule from the signed agreement and activates the loan.",
        maxWidth = 480.dp,
        footer = {
            Btn("Cancel", onDismiss, variant = BtnVariant.Outline)
            Btn("Release & activate", {
                runner.run {
                    val r = LoansService.release(id, date, method, reference)
                    Toasts.success(
                        if (r.num("renewal_deduction") > 0)
                            "Released ${peso(r.num("net_release"))} after settling ${peso(r.num("renewal_deduction"))} of previous balance — ${r.int("installments")} installments scheduled"
                        else "Loan released and activated — ${r.int("installments")} installments scheduled",
                    )
                    onDismiss()
                    onDone()
                }
            }, loading = runner.busy)
        },
    ) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LF.Navy).padding(16.dp)) {
            Text("NET PROCEEDS TO RELEASE", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f), letterSpacing = 0.4.sp)
            Text(peso(loan.num("net_proceeds")), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(
                "Principal ${peso(loan.num("principal"))}" + (if (deducted > 0) " less deducted charges ${peso(deducted)}" else ""),
                fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f),
            )
        }
        if (pendingRenewals.isNotEmpty()) {
            LnTinted(LF.Amber50, border = LF.Amber200) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val amber = Color(0xFF451A03)
                    Text(
                        "Renewal: previous loan${if (pendingRenewals.size > 1) "s" else ""} ${pendingRenewals.joinToString(", ") { it.objOrFirst("old_loan").str("loan_number") }} will be settled first",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = amber,
                    )
                    Row {
                        Text("Previous balance (quoted ${fmtDate(pendingRenewals[0].strOrNull("quoted_as_of"))})", Modifier.weight(1f), fontSize = 14.sp, color = amber)
                        Text("− ${peso(estimate)}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = amber)
                    }
                    HorizontalDivider(color = LF.Amber200)
                    Row {
                        Text("Estimated amount to hand over", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = amber)
                        Text(peso(loan.num("net_proceeds") - estimate), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = amber)
                    }
                    Text("The payoff is recomputed as of the release date and posted as a settlement payment on the previous loan.", fontSize = 12.sp, color = amber)
                }
            }
        }
        FormRow(
            { Field("Release date") { DateField(date, { date = it }, maxDate = todayManila()) } },
            { Field("Release method") { SelectField(method, PAYMENT_METHODS, { method = it }) } },
        )
        Field("Reference (optional)") { TextInput(reference, { reference = it }, placeholder = "Transfer / check number") }
        if (date != loan.str("release_date")) {
            LnTinted(LF.Amber50) {
                Text(
                    "The agreement states a release date of ${fmtDate(loan.strOrNull("release_date"))}. The repayment schedule follows the signed agreement.",
                    fontSize = 12.sp, color = Color(0xFF78350F),
                )
            }
        }
    }
}

@Composable
fun AssignStaffDialog(loan: Row, onDismiss: () -> Unit, onDone: () -> Unit) {
    val staff = rememberLoad("users") { UsersService.list() }
    var officer by remember { mutableStateOf(loan.strOrNull("loan_officer_id") ?: "") }
    var collector by remember { mutableStateOf(loan.strOrNull("collector_id") ?: "") }
    val runner = rememberRunner()
    val active = staff.data?.filter { it.bool("is_active") } ?: emptyList()
    val people = active.map { Opt(it.str("id"), "${it.str("full_name")} (${it.str("role").replaceFirst('_', ' ')})") }
    AppDialog(
        title = "Assign staff",
        onDismiss = { if (!runner.busy) onDismiss() },
        description = "Collectors only see borrowers and loans assigned to them.",
        maxWidth = 480.dp,
        footer = {
            Btn("Cancel", onDismiss, variant = BtnVariant.Outline)
            Btn("Save", {
                runner.run(success = "Assignment updated") {
                    LoansService.assignStaff(loan.str("id"), officer.ifBlank { null }, collector.ifBlank { null })
                    onDismiss()
                    onDone()
                }
            }, loading = runner.busy)
        },
    ) {
        Field("Loan officer") { SelectField(officer, listOf(Opt("", "—")) + people, { officer = it }) }
        Field("Collector") { SelectField(collector, listOf(Opt("", "Unassigned")) + people, { collector = it }) }
    }
}

private val ADJUSTMENT_TYPES = listOf(
    Opt("waive_penalty", "Waive penalty"),
    Opt("waive_interest", "Waive interest"),
    Opt("waive_charges", "Waive charges"),
    Opt("add_charge", "Add a charge"),
)

@Composable
fun AdjustmentDialog(loan: Row, onDismiss: () -> Unit, onDone: () -> Unit, installmentId: String? = null) {
    val inst = rememberLoad(loan.str("id"), "open-installments") { PaymentsService.openInstallments(loan.str("id")) }
    var iid by remember { mutableStateOf(installmentId ?: "") }
    var type by remember { mutableStateOf("waive_penalty") }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    val runner = rememberRunner()
    val list = inst.data ?: emptyList()
    val selected = list.firstOrNull { it.str("id") == iid.ifBlank { list.firstOrNull()?.str("id") ?: "" } }
    val max = if (selected == null) 0.0 else when (type) {
        "waive_penalty" -> selected.num("penalty_outstanding")
        "waive_interest" -> selected.num("interest_due") - selected.num("interest_paid") - selected.num("interest_waived")
        "waive_charges" -> selected.num("charges_due") - selected.num("charges_paid") - selected.num("charges_waived")
        else -> Double.POSITIVE_INFINITY
    }
    val amt = amount.toDoubleOrNull() ?: 0.0
    AppDialog(
        title = "Waiver / adjustment",
        onDismiss = { if (!runner.busy) onDismiss() },
        description = "Every adjustment creates a permanent ledger entry and an audit record. Confirmed transactions are never edited.",
        maxWidth = 480.dp,
        footer = {
            Btn("Cancel", onDismiss, variant = BtnVariant.Outline)
            Btn("Post adjustment", {
                val sel = selected ?: return@Btn
                runner.run(success = "Adjustment posted to the ledger") {
                    LoansService.adjust(loan.str("id"), sel.str("id"), type, amt, reason)
                    onDismiss()
                    onDone()
                }
            }, loading = runner.busy, enabled = selected != null && reason.isNotBlank() && amt > 0 && amt <= max)
        },
    ) {
        Field("Installment") {
            SelectField(
                selected?.str("id") ?: "",
                list.map { Opt(it.str("id"), "#${it.str("installment_no")} · due ${fmtDate(it.strOrNull("due_date"))} · remaining ${peso(it.num("amount_remaining"))}") },
                { iid = it },
            )
        }
        Field("Adjustment type") { SelectField(type, ADJUSTMENT_TYPES, { type = it }) }
        Field("Amount (₱)", hint = if (max.isFinite()) "Up to ${peso(max)}" else null) { MoneyInput(amount, { amount = it }) }
        Field("Reason", required = true) { TextInput(reason, { reason = it }, singleLine = false, minLines = 2) }
    }
}

@Composable
fun RescheduleDialog(loan: Row, onDismiss: () -> Unit, onDone: () -> Unit) {
    var method by remember { mutableStateOf(loan.str("interest_method")) }
    var rate by remember { mutableStateOf(lnJsNum(loan.num("interest_rate"))) }
    var period by remember { mutableStateOf(loan.str("rate_period")) }
    var freq by remember { mutableStateOf(loan.str("repayment_frequency")) }
    var term by remember { mutableStateOf("6") }
    var firstDue by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    val runner = rememberRunner()
    val id = loan.str("id")
    val inst = rememberLoad(id, "open-installments") { PaymentsService.openInstallments(id) }
    val today = todayManila()
    val list = inst.data
    val dueInterest = list?.filter { it.str("due_date") <= today }?.sumOf { it.num("interest_due") - it.num("interest_paid") - it.num("interest_waived") } ?: 0.0
    val openCharges = list?.sumOf { it.num("charges_due") - it.num("charges_paid") - it.num("charges_waived") } ?: 0.0
    val newPrincipal = loan.num("outstanding_principal") + dueInterest + openCharges
    // same inputs reschedule_loan uses: the loan's own day count / rounding, and its semi-monthly pay days
    val loanPayDays = loan.arr("pay_days").map { it.toNum().toInt() }
    val payDays = if (freq == "semimonthly" && loanPayDays.size == 2) loanPayDays else null
    val rateN = rate.toDoubleOrNull() ?: 0.0
    val termN = term.toIntOrNull() ?: 0
    val params = mapOf(
        "principal" to round2(newPrincipal), "interest_method" to method, "interest_rate" to rateN, "rate_period" to period,
        "repayment_frequency" to freq, "term_count" to termN, "release_date" to today, "first_due_date" to firstDue.ifBlank { null },
        "processing_fee_type" to "none", "processing_fee_value" to 0, "other_charges" to 0, "charges_treatment" to "added",
        "penalty_enabled" to false, "day_count" to loan.strOrNull("day_count"), "rounding" to loan.strOrNull("rounding"), "pay_days" to payDays,
    )
    val enabled = list != null && termN > 0 && rateN >= 0 && newPrincipal > 0
    val preview = rememberLoad(id, params.toString(), enabled, live = false, enabled = enabled) { LoansService.preview(params) }
    val previewData = if (enabled) preview.data else null

    AppDialog(
        title = "Reschedule loan (amendment)",
        onDismiss = { if (!runner.busy) onDismiss() },
        description = "Outstanding principal plus interest already due and open charges are re-amortized. Unearned future interest is removed. Penalties must be settled or waived first. A new agreement version must then be generated and signed.",
        maxWidth = 672.dp,
        footer = {
            Btn("Cancel", onDismiss, variant = BtnVariant.Outline)
            Btn("Reschedule", {
                runner.run {
                    val r = LoansService.reschedule(id, mapOf(
                        "interest_method" to method, "interest_rate" to rateN, "rate_period" to period, "repayment_frequency" to freq,
                        "term_count" to termN, "first_due_date" to firstDue.ifBlank { null }, "pay_days" to payDays, "reason" to reason,
                    ))
                    Toasts.success("Loan rescheduled (amendment v${r.str("version")}). Generate the amendment agreement for signing.")
                    onDismiss()
                    onDone()
                }
            }, loading = runner.busy, enabled = reason.isNotBlank() && previewData != null)
        },
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Slate50).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MiniFigure("Outstanding principal", peso(loan.num("outstanding_principal")), Modifier.weight(1f), bold = true)
            MiniFigure("+ Due interest & charges", peso(dueInterest + openCharges), Modifier.weight(1f), bold = true)
            MiniFigure("New principal", peso(newPrincipal), Modifier.weight(1f), valueColor = LF.Blue700, bold = true)
        }
        if (loan.num("outstanding_penalties") > 0) {
            LnTinted(LF.Red50) {
                Text("Outstanding penalties of ${peso(loan.num("outstanding_penalties"))} must be paid or waived before rescheduling.", fontSize = 12.sp, color = LF.Red700)
            }
        }
        val fields: List<@Composable () -> Unit> = listOf(
            { Field("Method") { SelectField(method, listOf(Opt("flat", "Flat"), Opt("diminishing", "Diminishing")), { method = it }) } },
            { Field("Rate (%)") { NumberInput(rate, { rate = it }) } },
            { Field("Rate period") { SelectField(period, RATE_PERIODS, { period = it }) } },
            { Field("Frequency") { SelectField(freq, FREQUENCIES.map { Opt(it.value, it.label) }, { freq = it }) } },
            { Field("New duration (installments)") { NumberInput(term, { term = it }, decimal = false) } },
            { Field("First due date") { DateField(firstDue, { firstDue = it }, minDate = today, clearable = true) } },
        )
        if (isCompact()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { fields.forEach { it() } }
        } else {
            LnEqualGrid(fields.size, 3) { i -> fields[i]() }
        }
        if (previewData != null) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HeadlineFigures(previewData)
                ScheduleTable(previewData.arr("schedule").mapNotNull { it as? Row }, maxHeight = 220.dp)
            }
        } else if (enabled && preview.loading) {
            Spinner()
        }
        if (enabled && preview.error != null) Text(preview.error, fontSize = 14.sp, color = LF.Red600)
        Field("Reason (documented amendment)", required = true) { TextInput(reason, { reason = it }, singleLine = false, minLines = 2) }
    }
}

@Composable
fun SettlementQuoteCard(loan: Row) {
    var asOf by remember { mutableStateOf(todayManila()) }
    val q = rememberLoad(loan.str("id"), "quote", asOf) { LoansService.settlementQuote(loan.str("id"), asOf) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field("Payoff as of") { DateField(asOf, { asOf = it }, minDate = todayManila()) }
        val d = q.data
        when {
            d == null && q.error != null -> Text(q.error, fontSize = 14.sp, color = LF.Red600)
            d == null -> Spinner()
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Principal" to d.num("principal"), "Interest earned to date" to d.num("interest"),
                    "Charges" to d.num("charges"), "Penalties" to d.num("penalties"),
                ).forEach { (l, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(l, Modifier.weight(1f), fontSize = 14.sp, color = LF.MutedForeground)
                        Text(peso(v), fontSize = 14.sp, color = LF.Foreground)
                    }
                }
                HorizontalDivider(color = LF.Border)
                Row(Modifier.fillMaxWidth()) {
                    Text("Payoff amount", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(peso(d.num("total")), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                LnTinted(LF.Green50, padding = 8.dp) {
                    Text(
                        "Unearned interest rebated: ${peso(d.num("interest_rebate"))} (contract balance ${peso(d.num("contract_remaining"))}). Rule: ${d.str("rule").replaceFirst('_', ' ')}.",
                        fontSize = 12.sp, color = LF.Green800,
                    )
                }
            }
        }
    }
}
