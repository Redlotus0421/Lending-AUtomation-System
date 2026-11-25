package com.lendflow.app.feature.loans.simplified

// Loan details, computation and review steps of SimplifiedLoanPage.tsx.

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.data.Row
import com.lendflow.app.data.bool
import com.lendflow.app.data.int
import com.lendflow.app.data.intOrNull
import com.lendflow.app.data.num
import com.lendflow.app.data.numOrNull
import com.lendflow.app.data.obj
import com.lendflow.app.data.objs
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.strings
import com.lendflow.app.feature.borrowers.BorrowerAvatar
import com.lendflow.app.feature.loans.ActionBar
import com.lendflow.app.feature.loans.CollectionDatePicker
import com.lendflow.app.feature.loans.ErrorBox
import com.lendflow.app.feature.loans.MainAside
import com.lendflow.app.feature.loans.PayDaysPicker
import com.lendflow.app.feature.loans.ScheduleTable
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CheckboxRow
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.KV
import com.lendflow.app.ui.components.KeyValue
import com.lendflow.app.ui.components.MoneyInput
import com.lendflow.app.ui.components.NumberInput
import com.lendflow.app.ui.components.PageLoader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.FREQUENCIES
import com.lendflow.app.util.FREQUENCY_LABEL
import com.lendflow.app.util.METHOD_LABEL
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.normalizePhMobile
import com.lendflow.app.util.peso
import com.lendflow.app.util.phMobile
import com.lendflow.app.util.trimNumber

// ---------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LoanStep(
    type: String,
    s: SimplifiedState,
    allowOverride: Boolean,
    borrowerName: String,
    preview: Row?,
    previewError: String?,
    fetching: Boolean,
    fresh: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val auth by AppState.state.collectAsState()
    val repayment = auth.setting("repayment")
    val interest = auth.setting("interest")
    val fee = auth.setting("simplified")
    val freq = s.frequency
    val allowed = repayment.strings("frequencies")
    val defaultFreq = repayment.strOrNull("default_frequency")
    val enabled = FREQUENCIES.filter { it.value in allowed || it.value == defaultFreq }
    val n = s.term.trim().toDoubleOrNull()
    var scheduleOpen by remember { mutableStateOf(false) }

    MainAside(
        asideWidth = 400.dp,
        main = {
            Panel(title = if (type == "renewal") "New loan details" else "Loan details", description = if (borrowerName.isNotBlank()) "For $borrowerName" else null) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Cols(listOf({
                        Field(if (type == "renewal") "New loan amount (₱)" else "Loan amount (₱)", required = true) {
                            MoneyInput(s.principal, { s.principal = it }, placeholder = "20,000.00")
                        }
                    }, {}))
                    if (enabled.size > 1) {
                        Field("Collection / payment frequency") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                enabled.forEach { f ->
                                    val on = freq == f.value
                                    val shape = RoundedCornerShape(8.dp)
                                    Box(
                                        Modifier.height(44.dp).clip(shape).background(if (on) LF.Blue50 else Color.White)
                                            .border(2.dp, if (on) LF.Blue else LF.Slate200, shape)
                                            .clickable { if (f.value != freq) { s.frequency = f.value; s.firstDue = null } }
                                            .padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) { Text(f.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) LF.Navy else LF.Foreground) }
                                }
                            }
                        }
                    }
                    if (freq == "semimonthly") {
                        PayDaysPicker(value = s.payDays, onChange = { s.payDays = it }, firstDue = s.firstDue, onFirstDueChange = { s.firstDue = it })
                    }
                    if (freq == "monthly" || freq == "weekly" || freq == "daily") {
                        key(freq) { CollectionDatePicker(frequency = freq, value = s.firstDue, onChange = { s.firstDue = it }) }
                    }
                    Cols(listOf({
                        val bad = s.term != "" && !(n != null && n % 1.0 == 0.0 && n >= 1 && n <= 360)
                        Field(
                            "Repayment period (months)", required = true,
                            error = if (bad) "Enter a whole number of months from 1 to 360" else null,
                            hint = installmentsHint(freq, n, preview),
                        ) { NumberInput(s.term, { s.term = it }, decimal = false) }
                    }, {
                        val per = mapOf("daily" to "day", "weekly" to "week", "monthly" to "month", "yearly" to "year")[interest.strOrNull("default_rate_period") ?: "monthly"]
                        Field("Interest rate (%)", hint = if (allowOverride) "Prefilled from your business settings; your settings allow changing it." else "Fixed by your business settings") {
                            TextInput(
                                s.rate, { t -> s.rate = t.filter { it.isDigit() || it == '.' } },
                                readOnly = !allowOverride, keyboardType = KeyboardType.Decimal,
                                trailing = {
                                    Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (!allowOverride) Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = LF.MutedForeground)
                                        Text(" per $per", fontSize = 12.sp, color = LF.MutedForeground)
                                    }
                                },
                            )
                        }
                    }))
                    Field("Method & fees") {
                        val feeType = fee.strOrNull("processing_fee_type")
                        val feeText = when (feeType) {
                            "percent" -> "${trimNumber(fee.num("processing_fee_value"), 4)}% fee"
                            "fixed" -> "${peso(fee.num("processing_fee_value"))} fee"
                            else -> "no processing fee"
                        }
                        Text(
                            "${METHOD_LABEL[interest.strOrNull("default_method") ?: "flat"] ?: ""} · $feeText",
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(6.dp)).background(LF.Slate50)
                                .border(1.dp, LF.Border, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 14.dp),
                            fontSize = 14.sp, color = LF.MutedForeground,
                        )
                    }
                }
                Row(Modifier.padding(top = 12.dp)) {
                    Icon(Icons.Default.VerifiedUser, null, Modifier.size(16.dp), tint = LF.Teal600)
                    Text(
                        "Interest, fees, penalties and the schedule come from your business settings and are computed on the server.",
                        Modifier.padding(start = 8.dp), fontSize = 12.sp, color = LF.MutedForeground,
                    )
                }
            }
            ActionBar {
                Btn("Back", onBack, Modifier.height(48.dp), variant = BtnVariant.Outline, icon = Icons.AutoMirrored.Filled.ArrowBack)
                Btn("REVIEW LOAN", onNext, Modifier.weight(1f).height(48.dp), enabled = preview != null && previewError == null, icon = Icons.Default.Assignment)
            }
        },
        aside = {
            Panel(
                title = "Loan computation",
                actions = when {
                    fetching -> ({ Spinner() })
                    fresh -> ({ Text("Up to date", fontSize = 12.sp, color = LF.Green600) })
                    else -> null
                },
            ) {
                when {
                    previewError != null -> ErrorBox(previewError)
                    preview != null -> Column(Modifier.alpha(if (fresh) 1f else 0.6f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ComputationFigures(preview)
                        if (type == "renewal") RenewalTable(preview)
                        val shape = RoundedCornerShape(8.dp)
                        Row(
                            Modifier.fillMaxWidth().clip(shape).border(1.dp, LF.Border, shape).clickable { scheduleOpen = !scheduleOpen }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Payment schedule (${preview.objs("schedule").size} payments)", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Icon(if (scheduleOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(16.dp), tint = LF.MutedForeground)
                        }
                        if (scheduleOpen) ScheduleTable(preview.objs("schedule"), 320.dp)
                    }
                    else -> Text(
                        "Enter the amount and repayment period to see the computation.",
                        Modifier.fillMaxWidth().padding(vertical = 24.dp), fontSize = 14.sp, color = LF.MutedForeground, textAlign = TextAlign.Center,
                    )
                }
            }
        },
    )
}

/** Hint under the months field: how many collections the period produces (from the server preview when available). */
private fun installmentsHint(freq: String, months: Double?, preview: Row?): String? {
    if (months == null || months % 1.0 != 0.0 || months <= 0) return if (freq == "semimonthly") "Collected twice a month on the pay days above" else null
    val m = months.toInt()
    val s = preview.obj("summary")
    if (s != null && s.intOrNull("term_months") == m && s.str("repayment_frequency") == freq) {
        val c = s.int("installment_count")
        return "$c ${FREQUENCY_LABEL[freq] ?: freq} payment${if (c == 1) "" else "s"}"
    }
    return when (freq) {
        "monthly" -> "$m monthly payment${if (m == 1) "" else "s"}"
        "semimonthly" -> "${m * 2} semi-monthly payments (twice a month)"
        else -> "${FREQUENCY_LABEL[freq] ?: freq} payments on every due date within $m month${if (m == 1) "" else "s"}"
    }
}

@Composable
internal fun ComputationFigures(p: Row) {
    val s = p.obj("summary")
    val ratePeriod = s.str("rate_period")
    val monthly = s.numOrNull("monthly_rate_pct") ?: if (ratePeriod == "monthly") s.numOrNull("interest_rate") else null
    val freq = s.str("repayment_frequency")
    val termMonths = s.int("term_months")
    val rows = listOf(
        "Principal amount" to peso(s.num("principal")),
        "Monthly interest rate" to (
            if (monthly != null) "${trimNumber(monthly, 4)}% per month" +
                (if (ratePeriod != "monthly") " (${trimNumber(s.num("interest_rate"), 4)}% per ${ratePeriod.replaceFirst("ly", "")})" else "") +
                (if (p.bool("rate_overridden")) " (override)" else "")
            else "${trimNumber(s.num("interest_rate"), 4)}% per $ratePeriod"
            ),
        "Total interest" to peso(s.num("total_interest")),
        "Fees & charges" to (
            if (s.num("total_charges") > 0) "${peso(s.num("total_charges"))} (${if (s.str("charges_treatment") == "added") "added to installments" else "deducted"})" else "None"
            ),
        "Total repayment amount" to peso(s.num("total_repayment")),
        "Repayment period" to (if (termMonths > 0) "$termMonths month${if (termMonths == 1) "" else "s"}" else s.str("duration_label")),
        "Number of installments" to "${s.int("installment_count")} · ${FREQUENCY_LABEL[freq] ?: freq}",
        "Payment dates" to "${fmtDate(s.strOrNull("first_due_date"))} – ${fmtDate(s.strOrNull("last_due_date"))}",
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LF.Navy).padding(16.dp)) {
            Text("PAYMENT PER COLLECTION · ${(FREQUENCY_LABEL[freq] ?: freq).uppercase()}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f), letterSpacing = 0.5.sp)
            Text(peso(s.num("installment_amount")), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            if (s.num("last_installment_amount") != s.num("installment_amount")) {
                Text("Final payment ${peso(s.num("last_installment_amount"))}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
            }
        }
        val shape = RoundedCornerShape(12.dp)
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, LF.Border, shape)) {
            rows.forEachIndexed { i, (k, v) ->
                if (i > 0) HorizontalDivider(color = LF.Border)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Text(k, Modifier.weight(1f).padding(end = 12.dp), fontSize = 14.sp, color = LF.MutedForeground)
                    Text(v, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
internal fun RenewalTable(p: Row) {
    val s = p.obj("summary")
    val deducted = if (s.str("charges_treatment") == "deducted") s.num("total_charges") else 0.0
    val loans = p.obj("renewal").objs("loans")
    val deduction = p.num("renewal_deduction")
    NetReleaseTable(
        principal = s.num("principal"), deductedCharges = deducted, previousBalance = deduction,
        previousLabel = "Less: existing outstanding balance${if (loans.isNotEmpty()) " (${loans.joinToString(", ") { it.str("loan_number") }})" else ""}",
        netRelease = p.num("net_release"),
        footnote = if (deduction > 0) "Amount deducted: ${peso(deduction)}. The previous loan is settled in full when this loan is released." else "No outstanding balance — nothing is deducted.",
    )
}

// ---------------------------------------------------------------------------
@Composable
internal fun ReviewStep(
    type: String,
    preview: Row?,
    fresh: Boolean,
    fetching: Boolean,
    refetch: () -> Unit,
    draft: BorrowerDraft?,
    files: BorrowerFiles,
    status: Row?,
    onBack: () -> Unit,
    finalize: suspend () -> Unit,
) {
    val auth by AppState.state.collectAsState()
    val runner = rememberRunner()
    var agree by remember { mutableStateOf(false) }
    val photo = rememberThumb(files.photo, 400)
    if (preview == null) {
        if (fetching) PageLoader("Computing on the server…") else ErrorState("Computation unavailable", refetch)
        return
    }
    val s = preview.obj("summary")
    val sb = status.obj("borrower")
    val name = draft?.fullName ?: sb.str("full_name")
    val coMaker = status.obj("co_maker")

    MainAside(
        main = {
            Panel(title = "Borrower information") {
                Row(verticalAlignment = Alignment.Top) {
                    if (draft != null) {
                        if (photo != null) Image(photo, name, Modifier.size(64.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    } else {
                        BorrowerAvatar(name, sb.strOrNull("profile_photo_path"), 64.dp)
                    }
                    Column(Modifier.weight(1f).padding(start = if (draft != null && photo == null) 0.dp else 16.dp)) {
                        Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (type == "renewal") "${sb.str("borrower_code")} · Reloan / Renew" else "New borrower", fontSize = 14.sp, color = LF.MutedForeground)
                    }
                }
                val items = if (draft != null) listOf(
                    KV("Complete address", draft.address),
                    KV("Contact number", phMobile(normalizePhMobile(draft.mobile))),
                    KV("Company / employer", draft.employer),
                    KV("ID", "${draft.id_type} · ${draft.id_number}"),
                    KV("Uploads", listOfNotNull(files.photo?.let { "Photo" }, files.id?.let { "ID" }, files.atm?.let { "ATM card" }).joinToString(", ")),
                    KV("Co-maker", "${draft.co_name} (${draft.co_relationship}) · ${phMobile(normalizePhMobile(draft.co_mobile))}${if (draft.co_address.isNotEmpty()) " · ${draft.co_address}" else ""}"),
                ) else listOf(
                    KV("Address", sb.strOrNull("address")?.ifBlank { null } ?: "—"),
                    KV("Contact number", phMobile(sb.strOrNull("mobile_number"))),
                    KV("Company / employer", sb.strOrNull("employer_name")?.ifBlank { null } ?: "—"),
                    KV("Co-maker", if (coMaker != null) "${coMaker.str("full_name")}${coMaker.strOrNull("relationship")?.let { " ($it)" } ?: ""}" else "—"),
                )
                KeyValue(items, columns = 2, modifier = Modifier.padding(top = 16.dp))
            }

            Panel(
                title = "Loan details & repayment computation",
                description = "Server-calculated; nothing has been saved yet.",
                actions = { Btn("Recalculate", refetch, variant = BtnVariant.Outline, small = true, icon = Icons.Default.Refresh, loading = fetching) },
            ) {
                val wide = widthClass() != WidthClass.Compact
                Box(Modifier.alpha(if (fresh) 1f else 0.6f)) {
                    val table: @Composable () -> Unit = {
                        if (type == "renewal") RenewalTable(preview)
                        else NetReleaseTable(
                            principal = s.num("principal"),
                            deductedCharges = if (s.str("charges_treatment") == "deducted") s.num("total_charges") else 0.0,
                            previousBalance = 0.0, netRelease = preview.num("net_release"),
                        )
                    }
                    if (wide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Box(Modifier.weight(1f)) { ComputationFigures(preview) }
                            Box(Modifier.weight(1f)) { table() }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { ComputationFigures(preview); table() }
                    }
                }
                val prev = preview.obj("renewal").objs("loans")
                if (type == "renewal" && prev.isNotEmpty()) {
                    val shape = RoundedCornerShape(8.dp)
                    Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(shape).border(1.dp, LF.Border, shape).horizontalScroll(rememberScrollState())) {
                        val w = listOf(140.dp, 130.dp, 110.dp, 160.dp)
                        Row(Modifier.background(LF.Slate50).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            listOf("Previous loan", "Contract balance", "Rebate", "Settled from new loan").forEachIndexed { i, h ->
                                Text(h, Modifier.width(w[i]), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground, textAlign = if (i == 0) TextAlign.Start else TextAlign.End)
                            }
                        }
                        prev.forEach { l ->
                            HorizontalDivider(color = LF.Border)
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(l.str("loan_number"), Modifier.width(w[0]), fontSize = 14.sp)
                                Text(peso(l.num("contract_balance")), Modifier.width(w[1]), fontSize = 14.sp, textAlign = TextAlign.End)
                                Text(peso(l.num("interest_rebate")), Modifier.width(w[2]), fontSize = 14.sp, textAlign = TextAlign.End)
                                Text(peso(l.num("payoff")), Modifier.width(w[3]), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }

            val freq = s.str("repayment_frequency")
            Panel(title = "Payment schedule", description = "${preview.objs("schedule").size} ${FREQUENCY_LABEL[freq] ?: freq} payments") {
                ScheduleTable(preview.objs("schedule"), 360.dp)
            }
        },
        aside = {
            Panel(title = "Confirm loan") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LF.Navy).padding(16.dp)) {
                        Text("BORROWER RECEIVES", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f), letterSpacing = 0.5.sp)
                        Text(peso(preview.num("net_release")), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(
                            "${paymentLabel(s.str("repayment_frequency"))} ${peso(s.num("installment_amount"))} × ${s.int("installment_count")}",
                            fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, LF.Border, RoundedCornerShape(8.dp)).padding(horizontal = 4.dp)) {
                        CheckboxRow(
                            agree, { agree = it },
                            "The borrower reviewed the loan amount, interest, total repayment" +
                                (if (type == "renewal" && preview.num("renewal_deduction") > 0) ", the deduction of the previous balance" else "") +
                                " and the payment schedule, and agrees to the terms.",
                        )
                    }
                    val approveOnCreate = auth.setting("simplified").bool("approve_on_create") && auth.can("loans.approve")
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("1. The ${if (draft != null) "borrower is registered and the " else ""}loan is saved${if (approveOnCreate) " and approved" else " and sent for approval"}.", fontSize = 12.sp, color = LF.MutedForeground)
                        Text("2. The agreement is generated from your template.", fontSize = 12.sp, color = LF.MutedForeground)
                        Text("3. The borrower signs on this device.", fontSize = 12.sp, color = LF.MutedForeground)
                    }
                    val canGenerate = auth.can("agreements.generate")
                    Btn(
                        "Confirm & generate agreement", { runner.run { finalize() } }, Modifier.fillMaxWidth().height(48.dp),
                        enabled = agree && fresh && canGenerate, loading = runner.busy, icon = Icons.Default.Draw,
                    )
                    if (!canGenerate) Text("Your role cannot generate agreements.", fontSize = 12.sp, color = LF.Red600)
                    Btn("Edit loan details", onBack, Modifier.fillMaxWidth(), variant = BtnVariant.Ghost, enabled = !runner.busy, icon = Icons.AutoMirrored.Filled.ArrowBack)
                }
            }
        },
    )
}
