package com.lendflow.app.feature.payments

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.DataBus
import com.lendflow.app.data.LoansService
import com.lendflow.app.data.PaymentsService
import com.lendflow.app.data.PickedFile
import com.lendflow.app.data.Row
import com.lendflow.app.data.UsersService
import com.lendflow.app.data.bool
import com.lendflow.app.data.int
import com.lendflow.app.data.newIdempotencyKey
import com.lendflow.app.data.num
import com.lendflow.app.data.readable
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.feature.payments.receipt.ReceiptCard
import com.lendflow.app.ui.components.AppDialog
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CheckboxRow
import com.lendflow.app.ui.components.DateField
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.LinkText
import com.lendflow.app.ui.components.LoanStatusBadge
import com.lendflow.app.ui.components.MoneyInput
import com.lendflow.app.ui.components.SearchField
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.rememberDebounced
import com.lendflow.app.ui.components.rememberFilePicker
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Opt
import com.lendflow.app.util.PAYMENT_METHODS
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.peso
import com.lendflow.app.util.todayManila
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong

// Port of src/features/payments/RecordPaymentDialog.tsx

private const val DESCRIPTION = "Payments are allocated server-side to penalties, charges, interest and principal per the loan’s allocation order."

/**
 * Record payment flow (RecordPaymentDialog.tsx). Shown while composed; one idempotency key per opening.
 * loanId = preselected loan (null = search), installmentId = allocate first, defaultType = regular | settlement.
 * onRecorded receives the record_payment result.
 */
@Composable
fun RecordPaymentDialog(onDismiss: () -> Unit, loanId: String? = null, installmentId: String? = null, defaultType: String = "regular", onRecorded: ((Row) -> Unit)? = null) {
    // The body is (re)created each time this dialog is composed, so each opening gets ONE fresh idempotency key.
    AppDialog(title = "Record payment", onDismiss = onDismiss, description = DESCRIPTION, maxWidth = 672.dp) {
        PaymentFlow(loanId, installmentId, defaultType, onRecorded, onDismiss)
    }
}

@Composable
private fun PaymentFlow(initialLoan: String?, installmentId: String?, defaultType: String, onRecorded: ((Row) -> Unit)?, onClose: () -> Unit) {
    var loanId by remember { mutableStateOf(initialLoan) }
    var result by remember { mutableStateOf<Row?>(null) }
    var round by remember { mutableIntStateOf(0) }

    val res = result
    if (res != null) {
        SuccessView(res, onClose = onClose, onAnother = {
            result = null
            round++
            if (initialLoan == null) loanId = null
        })
        return
    }
    val id = loanId
    if (id == null) {
        LoanPicker { loanId = it }
        return
    }
    key(id, round) {
        PaymentForm(
            loanId = id,
            installmentId = if (round == 0) installmentId else null,
            defaultType = defaultType,
            canChangeLoan = initialLoan == null,
            onChangeLoan = { loanId = null },
            onDone = { r -> result = r; onRecorded?.invoke(r) },
        )
    }
}

// ---------------------------------------------------------------------------
// Step 1: choose loan
// ---------------------------------------------------------------------------
@Composable
private fun LoanPicker(onPick: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    val term = rememberDebounced(search, 250)
    val q = rememberLoad(term) { LoansService.list(statuses = listOf("active", "defaulted"), search = term, pageSize = 12, order = "next_due") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SearchField(search, { search = it }, placeholder = "Search borrower, loan # or borrower code")
        val rows = q.data?.rows
        when {
            q.initialLoading -> Row(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Spinner(); Spacer(Modifier.width(8.dp)); Text("Searching…", fontSize = 14.sp, color = LF.MutedForeground)
            }
            q.error != null -> ErrorBox(q.error)
            rows.isNullOrEmpty() -> Text("No active loans found${if (term.isNotBlank()) " for “$term”" else ""}.", Modifier.fillMaxWidth().padding(vertical = 40.dp),
                fontSize = 14.sp, color = LF.MutedForeground, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, LF.Border, RoundedCornerShape(12.dp))
                            .clickable { onPick(l.str("id")) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(l.str("borrower_name"), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${l.str("loan_number")} · ${l.str("borrower_code")}" + (l.strOrNull("next_due_date")?.let { " · next due ${fmtDate(it)}" } ?: ""),
                                fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (l.num("overdue_amount") > 0) {
                                Text("${peso(l.num("overdue_amount"))} overdue · ${l.int("days_overdue")} days", Modifier.padding(top = 2.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.Red600)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(peso(l.num("total_outstanding")), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("outstanding", fontSize = 11.sp, color = LF.MutedForeground)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step 2: payment form
// ---------------------------------------------------------------------------
private val REF_PLACEHOLDER = mapOf(
    "bank_transfer" to "Bank reference / transaction no.",
    "e_wallet" to "GCash / Maya reference no.",
    "check" to "Check number and bank",
    "other" to "Reference no.",
    "cash" to "Optional (e.g. collector slip no.)",
)

private val DECIMAL_2 = Regex("^\\d+(\\.\\d{1,2})?$")

private fun fixed2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
private fun round2(v: Double) = (v * 100).roundToLong() / 100.0

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentForm(
    loanId: String,
    installmentId: String?,
    defaultType: String,
    canChangeLoan: Boolean,
    onChangeLoan: () -> Unit,
    onDone: (Row) -> Unit,
) {
    val auth by AppState.state.collectAsState()
    val scope = rememberCoroutineScope()
    // ONE idempotency key for this form instance — reused on every retry/double-tap.
    val idemKey = remember { newIdempotencyKey() }
    var proofPath by remember { mutableStateOf<String?>(null) }
    var proof by remember { mutableStateOf<PickedFile?>(null) }
    var proofPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    val today = todayManila()
    val canAssign = auth.can("collections.assign")
    val myId = auth.access.strOrNull("user_id") ?: auth.userId

    var type by remember { mutableStateOf(if (defaultType == "settlement") "settlement" else "regular") }
    var targetId by remember { mutableStateOf(installmentId ?: "") }
    var amount by remember { mutableStateOf("") }
    var paymentDate by remember { mutableStateOf(today) }
    var method by remember { mutableStateOf("cash") }
    var reference by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var receivedBy by remember { mutableStateOf(myId ?: "") }
    var fromAtm by remember { mutableStateOf(false) }
    var atmWithdrawal by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }

    val loanQ = rememberLoad(loanId) { LoansService.get(loanId) }
    val instQ = rememberLoad(loanId) { PaymentsService.openInstallments(loanId) }
    val staffQ = rememberLoad(canAssign, enabled = canAssign) { if (canAssign) UsersService.list() else emptyList() }
    val loan = loanQ.data
    val installments = instQ.data.orEmpty()

    // projected (not yet posted) penalties as of the payment date — the server posts them before allocating
    val asOf = paymentDate.ifBlank { today }
    val overdueIds = installments.filter { it.str("due_date") < asOf }.map { it.str("id") }.take(24)
    val penEnabled = loan.bool("penalty_enabled") && overdueIds.isNotEmpty() && paymentDate.isNotBlank()
    val penQ = rememberLoad(loanId, paymentDate, overdueIds.joinToString(","), penEnabled, enabled = penEnabled) {
        if (!penEnabled) emptyMap() else coroutineScope {
            overdueIds.map { id -> async { id to PaymentsService.projectedPenalty(id, paymentDate) } }.awaitAll().toMap()
        }
    }
    val projected: Map<String, Double> = if (penEnabled) penQ.data.orEmpty() else emptyMap()
    val projectedTotal = projected.values.sum()

    val settleEnabled = type == "settlement" && paymentDate.isNotBlank() && paymentDate <= today
    val settleQ = rememberLoad(loanId, paymentDate, settleEnabled, enabled = settleEnabled) {
        if (settleEnabled) LoansService.settlementQuote(loanId, paymentDate) else null
    }
    val quote = if (settleEnabled) settleQ.data else null

    // lock the amount to the payoff quote in settlement mode
    LaunchedEffect(type, quote) {
        if (type == "settlement" && quote != null) {
            amount = fixed2(quote.num("total"))
            targetId = ""
        }
    }
    // default target: the requested installment, otherwise the oldest open one
    LaunchedEffect(installments, type) {
        if (installmentId == null && installments.isNotEmpty() && targetId.isBlank() && type == "regular") targetId = installments.first().str("id")
    }

    fun remainingOf(i: Row) = i.num("amount_remaining") + (projected[i.str("id")] ?: 0.0)
    val target = installments.firstOrNull { it.str("id") == targetId }
    val overdueTotal = installments.filter { it.str("due_date") < asOf }.sumOf { remainingOf(it) }
    val fullBalance = loan.num("total_outstanding") + projectedTotal
    val amt = amount.toDoubleOrNull() ?: 0.0
    val exceeds = type == "regular" && amt > fullBalance + 0.004

    val chips = buildList {
        if (target != null) add("Installment #${target.str("installment_no")}" to remainingOf(target))
        if (overdueTotal > 0 && (target == null || abs(overdueTotal - remainingOf(target)) > 0.004)) add("All overdue" to overdueTotal)
        if (fullBalance > 0) add("Full contract balance" to fullBalance)
    }

    // validation (zod schema of the web form)
    val errors = buildMap {
        val a = amount.trim()
        when {
            a.isEmpty() -> put("amount", "Enter the amount received")
            (a.toDoubleOrNull() ?: 0.0) <= 0 -> put("amount", "Amount must be greater than zero")
            !DECIMAL_2.matches(a) -> put("amount", "Use at most 2 decimal places")
        }
        when {
            paymentDate.isBlank() -> put("payment_date", "Select the payment date")
            paymentDate > today -> put("payment_date", "Payment date cannot be in the future")
        }
        if (reference.length > 80) put("reference_no", "At most 80 characters")
        else if (method != "cash" && reference.isBlank()) put("reference_no", "A transaction reference is required for non-cash payments")
        if (notes.length > 500) put("notes", "At most 500 characters")
        if (fromAtm) {
            val w = atmWithdrawal.trim()
            when {
                w.isEmpty() -> put("atm_withdrawal", "Enter the amount withdrawn from the ATM")
                !DECIMAL_2.matches(w) -> put("atm_withdrawal", "Use at most 2 decimal places")
                (w.toDoubleOrNull() ?: 0.0) < (a.toDoubleOrNull() ?: 0.0) -> put("atm_withdrawal", "The withdrawal cannot be less than the loan payment")
            }
        }
    }
    fun err(k: String) = if (submitted) errors[k] else null

    val picker = rememberFilePicker(maxBytes = Int.MAX_VALUE) { f ->
        if (f.size > 10 * 1024 * 1024) { Toasts.error("File is larger than 10 MB"); return@rememberFilePicker }
        proof = f
        proofPath = null
        proofPreview = if (f.mime?.startsWith("image/") == true) try { BitmapFactory.decodeByteArray(f.bytes, 0, f.bytes.size)?.asImageBitmap() } catch (_: Exception) { null } else null
    }

    fun submit() {
        if (posting) return
        submitted = true
        if (errors.isNotEmpty()) return
        if (type == "settlement" && quote == null) { Toasts.error("The settlement quote is still loading"); return }
        posting = true
        scope.launch {
            try {
                val orgId = auth.organization.strOrNull("id") ?: auth.organizationId
                val file = proof
                if (file != null && proofPath == null && orgId != null) proofPath = PaymentsService.uploadProof(orgId, loanId, file)
                val r = PaymentsService.record(mapOf(
                    "loan_id" to loanId,
                    "amount" to round2(amount.toDouble()),
                    "payment_date" to paymentDate,
                    "method" to method,
                    "reference_no" to reference.trim().ifBlank { null },
                    "notes" to notes.trim().ifBlank { null },
                    "proof_path" to proofPath,
                    "payment_type" to type,
                    "installment_id" to if (type == "regular") targetId.ifBlank { null } else null,
                    "received_by" to receivedBy.ifBlank { null },
                    "atm_withdrawal" to if (fromAtm && atmWithdrawal.isNotBlank()) round2(atmWithdrawal.toDouble()) else null,
                    "idempotency_key" to idemKey,
                ))
                DataBus.bump(0)
                if (r.bool("duplicate")) Toasts.info("This payment was already recorded — showing the original receipt.")
                else Toasts.success("Payment ${r.str("payment_number")} recorded · Receipt ${r.str("receipt_number")}")
                onDone(r)
            } catch (e: Exception) {
                Toasts.error(e.readable())
            } finally {
                posting = false
            }
        }
    }

    if (loanQ.initialLoading) {
        Row(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Spinner(); Spacer(Modifier.width(8.dp)); Text("Loading loan…", fontSize = 14.sp, color = LF.MutedForeground)
        }
        return
    }
    if (loanQ.error != null || loan == null) { ErrorBox(loanQ.error ?: "Loan not found"); return }

    val payable = loan.str("status") == "active" || loan.str("status") == "defaulted"
    val collectors = staffQ.data.orEmpty().filter { it.bool("is_active", true) }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        LoanHeader(loan, projectedTotal, canChangeLoan, onChangeLoan)

        if (!payable) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Amber50).border(1.dp, LF.Amber200, RoundedCornerShape(8.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = LF.Amber800)
                Spacer(Modifier.width(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Payments can only be recorded on active loans. This loan is", fontSize = 14.sp, color = LF.Amber800)
                    LoanStatusBadge(loan.str("status"))
                }
            }
            return@Column
        }

        // payment type
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("regular", "settlement").forEach { t ->
                val sel = type == t
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) LF.Blue50 else Color.White)
                        .border(2.dp, if (sel) LF.Blue600 else LF.Slate200, RoundedCornerShape(12.dp))
                        .clickable {
                            type = t
                            if (t == "regular") amount = ""
                        }.padding(12.dp),
                ) {
                    Text(if (t == "regular") "Regular payment" else "Early settlement", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (t == "regular") "Installments, partial or advance payment" else "Pay off the loan today — no unearned interest",
                        Modifier.padding(top = 2.dp), fontSize = 12.sp, color = LF.MutedForeground)
                }
            }
        }

        if (type == "settlement") {
            SettlementQuoteBox(settleQ.loading && quote == null, settleQ.error, quote, paymentDate)
        } else {
            Field("Apply first to installment", hint = "Any excess continues to the next installments (advance payment).") {
                if (instQ.initialLoading) {
                    Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(8.dp)).background(LF.Muted))
                } else {
                    val opts = listOf(Opt("", "Oldest unpaid installment first")) + installments.map { i ->
                        val overdue = i.str("due_date") < asOf
                        val pen = projected[i.str("id")] ?: 0.0
                        Opt(i.str("id"), buildString {
                            append("#${i.str("installment_no")} · due ${fmtDate(i.str("due_date"))} · ${peso(remainingOf(i))}")
                            if (overdue) append(" · OVERDUE")
                            if (pen > 0) append(" (incl. ${peso(pen)} penalty)")
                            if (i.str("status") == "partially_paid") append(" · partial")
                        })
                    }
                    SelectField(targetId, opts, { targetId = it })
                }
            }
        }

        // amount
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Amount received (₱)", required = true, error = err("amount")) {
                MoneyInput(amount, { amount = it }, enabled = type != "settlement", isError = err("amount") != null)
            }
            if (type == "regular" && chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { (label, value) ->
                        Text(
                            buildAnnotatedString { append("$label · "); withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(peso(value)) } },
                            Modifier.clip(RoundedCornerShape(50)).background(Color.White).border(1.dp, LF.Border, RoundedCornerShape(50))
                                .clickable { amount = fixed2(value) }.padding(horizontal = 12.dp, vertical = 8.dp),
                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            if (exceeds) InlineNote(Icons.Default.Warning, "The amount exceeds the total balance of ${peso(fullBalance)}.", LF.Red50, LF.Red700)
            if (type == "regular" && amt > 0 && !exceeds && abs(amt - fullBalance) < 0.005) {
                InlineNote(Icons.Default.Info,
                    "This pays every remaining installment including future interest. If the borrower is paying off early, use Early settlement instead so unearned interest is not charged.",
                    LF.Blue50, LF.Blue700)
            }
        }

        // ATM collection: the collector withdraws from the borrower's ATM, keeps the payment, returns the rest
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (fromAtm) LF.Blue50.copy(alpha = 0.5f) else Color.White)
                .border(1.dp, if (fromAtm) LF.Blue200 else LF.Border, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            CheckboxRow(fromAtm, { fromAtm = it }, "Collected from the borrower's ATM",
                description = "Enter the amount withdrawn; the change returned to the borrower is computed for the receipt.")
            if (fromAtm) {
                val w = atmWithdrawal.toDoubleOrNull() ?: 0.0
                val change = round2(w - amt)
                Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("ATM withdrawal (₱)", required = true, error = err("atm_withdrawal")) {
                        MoneyInput(atmWithdrawal, { atmWithdrawal = it }, isError = err("atm_withdrawal") != null)
                    }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, LF.Slate200, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AtmLine("ATM withdrawal", peso(w))
                        AtmLine("Loan payment", "− ${peso(amt)}")
                        Box(Modifier.fillMaxWidth().height(1.dp).background(LF.Border))
                        AtmLine("Cash returned to borrower", peso(change), bold = true, color = if (change < 0) LF.Red600 else LF.Foreground)
                    }
                }
            }
        }

        val dateField: @Composable () -> Unit = {
            Field("Payment date", required = true, error = err("payment_date")) { DateField(paymentDate, { paymentDate = it }, maxDate = today) }
        }
        val methodField: @Composable () -> Unit = {
            Field("Payment method", required = true) { SelectField(method, PAYMENT_METHODS, { method = it }) }
        }
        val wide = !isCompact()
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) { dateField() }
                Box(Modifier.weight(1f)) { methodField() }
            }
        } else { dateField(); methodField() }
        Field("Transaction reference", required = method != "cash", error = err("reference_no")) {
            TextInput(reference, { reference = it }, placeholder = REF_PLACEHOLDER[method] ?: "Reference no.", isError = err("reference_no") != null)
        }
        if (canAssign && collectors.isNotEmpty()) {
            Field("Received by") {
                SelectField(receivedBy, collectors.map { s -> Opt(s.str("id"), s.str("full_name") + if (s.str("id") == myId) " (me)" else "") }, { receivedBy = it })
            }
        }

        Field("Proof of payment", hint = "Photo of the deposit slip, e-wallet screenshot or signed collection slip (optional)") {
            val f = proof
            if (f != null) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Slate50).border(1.dp, LF.Border, RoundedCornerShape(8.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val pv = proofPreview
                    if (pv != null) Image(pv, null, Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
                    else Icon(Icons.Default.Description, null, Modifier.size(32.dp), tint = LF.Slate400)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${f.size / 1024} KB", fontSize = 12.sp, color = LF.MutedForeground)
                    }
                    IconButton(onClick = { proof = null; proofPreview = null; proofPath = null }) { Icon(Icons.Default.Close, "Remove file", tint = LF.MutedForeground) }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, LF.Input, RoundedCornerShape(8.dp))
                        .clickable { picker.launch(arrayOf("image/*", "application/pdf")) }.padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AttachFile, null, Modifier.size(14.dp), tint = LF.Foreground)
                        Spacer(Modifier.width(6.dp))
                        Text("Attach photo or PDF", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Text("Max 10 MB", fontSize = 12.sp, color = LF.MutedForeground)
                }
            }
        }

        Field("Notes", error = err("notes")) {
            TextInput(notes, { notes = it }, placeholder = "Optional remarks (e.g. paid at branch, promise for balance)", singleLine = false, minLines = 2)
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(LF.Border))
        val summary: @Composable () -> Unit = {
            Text(
                if (amt > 0) buildAnnotatedString {
                    append("Recording ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = LF.Foreground)) { append(peso(amt)) }
                    append(" · ${fmtDate(paymentDate)}")
                    val w = atmWithdrawal.toDoubleOrNull() ?: 0.0
                    if (fromAtm && w >= amt) {
                        append(" · return ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = LF.Foreground)) { append(peso(w - amt)) }
                    }
                } else buildAnnotatedString { append("Duplicate submissions are blocked automatically.") },
                fontSize = 12.sp, color = LF.MutedForeground,
            )
        }
        val button: @Composable (Modifier) -> Unit = { m ->
            Btn(
                if (posting) "Posting…" else if (type == "settlement") "Post settlement" else "Post payment",
                { submit() }, m.heightIn(min = 48.dp), icon = Icons.Default.Payments, loading = posting,
                enabled = !posting && !exceeds && !(type == "settlement" && quote == null),
            )
        }
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(end = 12.dp)) { summary() }
                button(Modifier.width(220.dp))
            }
        } else {
            summary()
            button(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AtmLine(label: String, value: String, bold: Boolean = false, color: Color = LF.Foreground) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = if (bold) color else LF.MutedForeground, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
        Text(value, fontSize = 14.sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun InlineNote(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, bg: Color, fg: Color) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Icon(icon, null, Modifier.size(14.dp).padding(top = 1.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = fg)
    }
}

@Composable
private fun ErrorBox(text: String) {
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Red50).padding(12.dp), fontSize = 14.sp, color = LF.Red700)
}

@Composable
private fun LoanHeader(loan: Row, projectedPenalty: Double, canChange: Boolean, onChange: () -> Unit) {
    val items = listOf(
        "Principal" to loan.num("outstanding_principal"),
        "Interest" to loan.num("outstanding_interest"),
        "Charges" to loan.num("outstanding_charges"),
        "Penalties" to loan.num("outstanding_penalties") + projectedPenalty,
    )
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LF.Slate50).border(1.dp, LF.Border, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(loan.str("borrower_name"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${loan.str("loan_number")} · ${loan.str("borrower_code")} · ${loan.str("borrower_mobile")}", fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoanStatusBadge(loan.str("status"))
                if (canChange) Btn("Change", onChange, variant = BtnVariant.Ghost, icon = Icons.AutoMirrored.Filled.ArrowBack, small = true)
            }
        }
        val total = loan.num("total_outstanding") + projectedPenalty
        val tile: @Composable (Modifier, String, Double) -> Unit = { m, label, v ->
            Column(m.clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, LF.Slate200, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(label, fontSize = 11.sp, color = LF.MutedForeground)
                Text(peso(v), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        val totalTile: @Composable (Modifier) -> Unit = { m ->
            Column(m.clip(RoundedCornerShape(8.dp)).background(LF.Navy).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text("Total balance", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                Text(peso(total), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (isCompact()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { (l, v) -> tile(Modifier.weight(1f), l, v) } }
                }
                totalTile(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { (l, v) -> tile(Modifier.weight(1f), l, v) }
                totalTile(Modifier.weight(1f))
            }
        }
        if (projectedPenalty > 0) {
            Text("Includes ${peso(projectedPenalty)} of late penalties accrued up to the payment date (posted automatically when the payment is saved).",
                Modifier.padding(top = 8.dp), fontSize = 11.sp, color = LF.Amber800)
        }
        if (loan.num("overdue_amount") > 0) {
            Text("${loan.int("overdue_count")} installment(s) overdue · ${peso(loan.num("overdue_amount"))} · ${loan.int("days_overdue")} days",
                Modifier.padding(top = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LF.Red600)
        }
    }
}

@Composable
private fun SettlementQuoteBox(loading: Boolean, error: String?, quote: Row?, paymentDate: String) {
    if (loading) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, LF.Border, RoundedCornerShape(12.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Spinner(); Spacer(Modifier.width(8.dp)); Text("Computing the payoff amount…", fontSize = 14.sp, color = LF.MutedForeground)
        }
        return
    }
    if (error != null) { ErrorBox(error); return }
    if (quote == null) return
    val rows = listOf(
        "Outstanding principal" to quote.num("principal"),
        "Interest earned to date" to quote.num("interest"),
        "Unpaid charges" to quote.num("charges"),
        "Penalties" to quote.num("penalties"),
    )
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LF.Teal50.copy(alpha = 0.5f)).border(1.dp, LF.Teal200, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Payoff as of ${fmtDate(paymentDate)}", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            val rule = when (quote.str("rule")) { "prorated" -> "Interest prorated to date"; "full_period" -> "Current period interest"; else -> "Due interest only" }
            Text(rule, Modifier.clip(RoundedCornerShape(50)).background(Color.White).border(1.dp, LF.Teal200, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LF.Teal700)
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (l, v) ->
                Row { Text(l, Modifier.weight(1f), fontSize = 14.sp, color = LF.MutedForeground); Text(peso(v), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(LF.Border))
            Row { Text("Settlement amount", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold); Text(peso(quote.num("total")), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
        Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, LF.Teal50, RoundedCornerShape(8.dp)).padding(10.dp)) {
            Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp).padding(top = 1.dp), tint = LF.Teal600)
            Spacer(Modifier.width(8.dp))
            Text(
                buildAnnotatedString {
                    append("Unearned future interest of ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(peso(quote.num("interest_rebate"))) }
                    append(" is rebated. Remaining contract balance was ${peso(quote.num("contract_remaining"))}.")
                },
                fontSize = 12.sp, color = LF.Teal700,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Step 3: success
// ---------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuccessView(result: Row, onClose: () -> Unit, onAnother: () -> Unit) {
    val router = LocalRouter.current
    val dup = result.bool("duplicate")
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LF.Green50).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(LF.Green200.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(24.dp), tint = LF.Green600)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${if (dup) "Payment already recorded" else "Payment posted"} · ${peso(result.num("amount"))}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("${result.str("payment_number")} · Receipt ${result.str("receipt_number")} · Balance ${peso(result.num("total_outstanding"))}",
                    fontSize = 12.sp, color = LF.MutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (dup) {
            Text("A payment with this submission ID already exists — no duplicate was created.",
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(LF.Blue50).padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp, color = LF.Blue700)
        }
        ReceiptCard(result.str("payment_id"), onClose = onClose)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = LF.Blue)
                LinkText("Record another", onAnother, color = LF.Blue)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Receipt, null, Modifier.size(16.dp), tint = LF.Blue)
                LinkText("Payment details", { onClose(); router.go("/payments/${result.str("payment_id")}") }, color = LF.Blue)
            }
        }
    }
}
