package com.lendflow.app.feature.loans

// Port of src/features/loans/LoanWizardPage.tsx — the Complete Lending loan wizard (new loan and
// editing a draft / pending loan). Figures are calculated and validated on the server.

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.AgreementsService
import com.lendflow.app.data.BorrowersService
import com.lendflow.app.data.LoansService
import com.lendflow.app.data.ProductsService
import com.lendflow.app.data.Row
import com.lendflow.app.data.TemplatesService
import com.lendflow.app.data.UsersService
import com.lendflow.app.data.bool
import com.lendflow.app.data.boolOrNull
import com.lendflow.app.data.int
import com.lendflow.app.data.intOrNull
import com.lendflow.app.data.jsonOf
import com.lendflow.app.data.num
import com.lendflow.app.data.numOrNull
import com.lendflow.app.data.numbers
import com.lendflow.app.data.obj
import com.lendflow.app.data.objOrFirst
import com.lendflow.app.data.objs
import com.lendflow.app.data.readable
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.strings
import com.lendflow.app.data.toJson
import com.lendflow.app.feature.borrowers.RiskBadge
import com.lendflow.app.ui.components.Avatar
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CheckboxRow
import com.lendflow.app.ui.components.DateField
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.FormRow
import com.lendflow.app.ui.components.LoanStatusBadge
import com.lendflow.app.ui.components.MoneyInput
import com.lendflow.app.ui.components.NumberInput
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.PageLoader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.SearchField
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.card
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.rememberDebounced
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.FREQUENCIES
import com.lendflow.app.util.FREQUENCY_LABEL
import com.lendflow.app.util.Opt
import com.lendflow.app.util.PENALTY_TYPES
import com.lendflow.app.util.RATE_PERIODS
import com.lendflow.app.util.addDays
import com.lendflow.app.util.addMonths
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.initials
import com.lendflow.app.util.peso
import com.lendflow.app.util.phMobile
import com.lendflow.app.util.todayManila
import com.lendflow.app.util.trimNumber
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal

// ---------------------------------------------------------------------------
// Form values (numbers are kept as typed text; "" is the web's NaN)
// ---------------------------------------------------------------------------
data class LoanForm(
    val productId: String = "",
    val principal: String = "",
    val interestMethod: String = "flat",
    val interestRate: String = "2",
    val ratePeriod: String = "monthly",
    val repaymentFrequency: String = "monthly",
    val termCount: String = "12",
    val releaseDate: String = "",
    val firstDueDate: String = "",
    val payDays: List<Int> = DEFAULT_PAY_DAYS,
    val processingFeeType: String = "none",
    val processingFeeValue: String = "0",
    val otherCharges: String = "0",
    val otherChargesDescription: String = "",
    val chargesTreatment: String = "deducted",
    val penaltyEnabled: Boolean = true,
    val penaltyType: String = "percent_monthly",
    val penaltyRate: String = "3",
    val penaltyGraceDays: String = "3",
    val dayCount: String = "30/360",
    val rounding: String = "centavo",
    val purpose: String = "",
    val notes: String = "",
    val collectorId: String = "",
    val agreementTemplateId: String = "",
    val signingOrder: String = "borrower_first",
    val witnessCount: Int = 0,
) {
    fun toJson(): JsonObject = jsonOf(
        "product_id" to productId, "principal" to principal, "interest_method" to interestMethod, "interest_rate" to interestRate,
        "rate_period" to ratePeriod, "repayment_frequency" to repaymentFrequency, "term_count" to termCount, "release_date" to releaseDate,
        "first_due_date" to firstDueDate, "pay_days" to payDays, "processing_fee_type" to processingFeeType, "processing_fee_value" to processingFeeValue,
        "other_charges" to otherCharges, "other_charges_description" to otherChargesDescription, "charges_treatment" to chargesTreatment,
        "penalty_enabled" to penaltyEnabled, "penalty_type" to penaltyType, "penalty_rate" to penaltyRate, "penalty_grace_days" to penaltyGraceDays,
        "day_count" to dayCount, "rounding" to rounding, "purpose" to purpose, "notes" to notes, "collector_id" to collectorId,
        "agreement_template_id" to agreementTemplateId, "signing_order" to signingOrder, "witness_count" to witnessCount,
    )

    companion object {
        /** Saved values over the defaults (missing keys keep the default). */
        fun revive(j: JsonObject?, d: LoanForm): LoanForm {
            if (j == null) return d
            fun s(k: String, def: String) = if (j.containsKey(k)) j.strOrNull(k) ?: "" else def
            val pd = j.numbers("pay_days").map { it.toInt() }
            return LoanForm(
                productId = s("product_id", d.productId), principal = s("principal", d.principal), interestMethod = s("interest_method", d.interestMethod),
                interestRate = s("interest_rate", d.interestRate), ratePeriod = s("rate_period", d.ratePeriod),
                repaymentFrequency = s("repayment_frequency", d.repaymentFrequency), termCount = s("term_count", d.termCount),
                releaseDate = s("release_date", d.releaseDate), firstDueDate = s("first_due_date", d.firstDueDate),
                payDays = if (pd.size == 2) pd else d.payDays, processingFeeType = s("processing_fee_type", d.processingFeeType),
                processingFeeValue = s("processing_fee_value", d.processingFeeValue), otherCharges = s("other_charges", d.otherCharges),
                otherChargesDescription = s("other_charges_description", d.otherChargesDescription), chargesTreatment = s("charges_treatment", d.chargesTreatment),
                penaltyEnabled = j.boolOrNull("penalty_enabled") ?: d.penaltyEnabled, penaltyType = s("penalty_type", d.penaltyType),
                penaltyRate = s("penalty_rate", d.penaltyRate), penaltyGraceDays = s("penalty_grace_days", d.penaltyGraceDays),
                dayCount = s("day_count", d.dayCount), rounding = s("rounding", d.rounding), purpose = s("purpose", d.purpose), notes = s("notes", d.notes),
                collectorId = s("collector_id", d.collectorId), agreementTemplateId = s("agreement_template_id", d.agreementTemplateId),
                signingOrder = s("signing_order", d.signingOrder), witnessCount = j.intOrNull("witness_count") ?: d.witnessCount,
            )
        }
    }
}

/** Number as plain text for an input ("2", "2.5"). */
internal fun numText(v: Double?): String = if (v == null) "" else BigDecimal.valueOf(v).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }

private fun numOf(s: String): Double? = s.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

/** Same rules and messages as the zod schema of the web wizard. */
internal fun validateLoanForm(v: LoanForm): Map<String, String> {
    val e = linkedMapOf<String, String>()
    numOf(v.principal).let {
        when {
            it == null -> e["principal"] = "Enter the loan amount"
            it <= 0 -> e["principal"] = "Loan amount must be greater than zero"
            it > 999_999_999 -> e["principal"] = "Amount is too large"
        }
    }
    numOf(v.interestRate).let {
        when {
            it == null -> e["interest_rate"] = "Enter the interest rate"
            it < 0 -> e["interest_rate"] = "Rate cannot be negative"
            it > 100 -> e["interest_rate"] = "Rate is too high"
        }
    }
    numOf(v.termCount).let {
        when {
            it == null -> e["term_count"] = "Enter the duration"
            it % 1.0 != 0.0 -> e["term_count"] = "Use whole numbers"
            it < 1 -> e["term_count"] = "At least 1"
            it > 3660 -> e["term_count"] = "Too long"
        }
    }
    if (v.releaseDate.length < 10) e["release_date"] = "Select the release date"
    numOf(v.processingFeeValue).let {
        when {
            it == null -> e["processing_fee_value"] = "Enter a fee value"
            it < 0 -> e["processing_fee_value"] = "Must be 0 or more"
        }
    }
    numOf(v.otherCharges).let {
        when {
            it == null -> e["other_charges"] = "Enter charges (0 if none)"
            it < 0 -> e["other_charges"] = "Must be 0 or more"
        }
    }
    numOf(v.penaltyRate).let {
        when {
            it == null -> e["penalty_rate"] = "Enter the penalty rate"
            it < 0 -> e["penalty_rate"] = "Must be 0 or more"
        }
    }
    numOf(v.penaltyGraceDays).let {
        when {
            it == null -> e["penalty_grace_days"] = "Enter grace days"
            it % 1.0 != 0.0 -> e["penalty_grace_days"] = "Use whole numbers"
            it < 0 -> e["penalty_grace_days"] = "Must be 0 or more"
            it > 365 -> e["penalty_grace_days"] = "At most 365"
        }
    }
    if (v.firstDueDate.isNotEmpty() && v.firstDueDate <= v.releaseDate && "first_due_date" !in e) e["first_due_date"] = "Must be after the release date"
    if (v.processingFeeType == "percent" && (numOf(v.processingFeeValue) ?: 0.0) > 50 && "processing_fee_value" !in e) e["processing_fee_value"] = "Fee percentage looks too high"
    if (v.penaltyEnabled && v.penaltyType.isBlank()) e["penalty_type"] = "Choose a penalty type"
    return e
}

/** LoanInput sent to preview_loan / create_loan / update_loan (only for a valid form). */
internal fun loanPayload(v: LoanForm): Map<String, Any?> {
    val m = linkedMapOf<String, Any?>(
        "product_id" to v.productId.ifBlank { null },
        "principal" to numOf(v.principal),
        "interest_method" to v.interestMethod,
        "interest_rate" to numOf(v.interestRate),
        "rate_period" to v.ratePeriod,
        "repayment_frequency" to v.repaymentFrequency,
        "term_count" to numOf(v.termCount)?.toInt(),
        "release_date" to v.releaseDate,
        "first_due_date" to v.firstDueDate.ifBlank { null },
    )
    if (v.repaymentFrequency == "semimonthly") m["pay_days"] = v.payDays
    m += linkedMapOf(
        "processing_fee_type" to v.processingFeeType,
        "processing_fee_value" to if (v.processingFeeType == "none") 0 else numOf(v.processingFeeValue),
        "other_charges" to numOf(v.otherCharges),
        "other_charges_description" to v.otherChargesDescription.ifBlank { null },
        "charges_treatment" to v.chargesTreatment,
        "day_count" to v.dayCount,
        "rounding" to v.rounding,
        "penalty_enabled" to v.penaltyEnabled,
        "penalty_type" to if (v.penaltyEnabled) v.penaltyType else null,
        "penalty_rate" to if (v.penaltyEnabled) numOf(v.penaltyRate) else 0,
        "penalty_grace_days" to if (v.penaltyEnabled) numOf(v.penaltyGraceDays)?.toInt() else 0,
        "purpose" to v.purpose.ifBlank { null },
        "notes" to v.notes.ifBlank { null },
        "collector_id" to v.collectorId.ifBlank { null },
        "agreement_template_id" to v.agreementTemplateId.ifBlank { null },
        "signing_order" to v.signingOrder,
        "witness_count" to v.witnessCount,
    )
    return m
}

private val STEPS = listOf("Borrower", "Loan details", "Preview", "Agreement")
private val EDITABLE = listOf("draft", "pending_review", "approved", "awaiting_borrower_signature", "awaiting_lender_signature")

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun LoanWizardScreen(editId: String?, borrowerId: String?) {
    val auth by AppState.state.collectAsState()
    val router = LocalRouter.current
    val ctx = LocalContext.current
    val runner = rememberRunner()
    val interest = auth.setting("interest")
    val repayment = auth.setting("repayment")
    val penalty = auth.setting("penalty")
    val agreementSet = auth.setting("agreement")
    val today = remember { todayManila() }
    val draftKey = "complete:${auth.access.strOrNull("user_id") ?: "anon"}"

    val defaults = remember(auth.settings, today) {
        LoanForm(
            interestMethod = interest.strOrNull("default_method") ?: "flat",
            interestRate = numText(interest.numOrNull("default_rate") ?: 2.0),
            ratePeriod = interest.strOrNull("default_rate_period") ?: "monthly",
            repaymentFrequency = repayment.strOrNull("default_frequency") ?: "monthly",
            releaseDate = today,
            chargesTreatment = repayment.strOrNull("charges_treatment") ?: "deducted",
            penaltyEnabled = penalty?.boolOrNull("enabled") ?: true,
            penaltyType = penalty.strOrNull("type") ?: "percent_monthly",
            penaltyRate = numText(penalty.numOrNull("rate") ?: 3.0),
            penaltyGraceDays = numText(penalty.numOrNull("grace_days") ?: 3.0),
            dayCount = interest.strOrNull("day_count") ?: "30/360",
            rounding = interest.strOrNull("rounding") ?: "centavo",
            signingOrder = agreementSet.strOrNull("signing_order") ?: "borrower_first",
            witnessCount = agreementSet.intOrNull("witness_count") ?: 0,
        )
    }

    // a saved draft (new loans only); a link for another borrower starts fresh
    val initial: JsonObject? = remember {
        if (editId != null) null else {
            val d = Drafts.load(ctx, draftKey)
            if (d == null || d.int("v") != 1) null
            else if (borrowerId != null && d.strOrNull("borrowerId") != null && d.strOrNull("borrowerId") != borrowerId) { Drafts.clear(ctx, draftKey); null }
            else d
        }
    }
    val initialStep = initial.int("step")
    var step by remember { mutableIntStateOf(if (editId != null) 1 else initialStep.coerceIn(0, 3)) }
    var restored by remember { mutableStateOf(initial != null && initialStep > 0) }
    var form by remember { mutableStateOf(if (initial != null) LoanForm.revive(initial.obj("values"), defaults) else defaults) }
    var touched by remember { mutableStateOf(setOf<String>()) }
    var submitted by remember { mutableStateOf(false) }
    var borrower by remember { mutableStateOf<Row?>(null) }
    var approveNow by remember { mutableStateOf(initial.bool("approveNow")) }
    var savingMode by remember { mutableStateOf<String?>(null) }
    val top = remember { BringIntoViewRequester() }
    ScrollToTopOnChange(step, top)

    fun set(field: String, f: (LoanForm) -> LoanForm) { form = f(form); touched = touched + field }

    val editLoan = rememberLoad(editId, live = false, enabled = editId != null) { LoansService.raw(editId!!) }
    val products = rememberLoad("products-active") { ProductsService.list(true) }
    val templates = rememberLoad("templates", enabled = auth.can("agreements.generate") || auth.can("templates.manage")) { TemplatesService.list() }
    val staff = rememberLoad("users") { UsersService.list() }

    // preselect borrower (?borrower=) or load from the loan being edited
    val preselectId = editLoan.data.strOrNull("borrower_id") ?: borrowerId ?: initial.strOrNull("borrowerId")
    val preselected = rememberLoad(preselectId, live = false, enabled = preselectId != null) { BorrowersService.get(preselectId!!) }
    LaunchedEffect(preselected.data) {
        val b = preselected.data
        if (b != null && borrower == null) {
            borrower = b
            if (editId == null && initialStep == 0 && borrowerId != null) step = 1
        }
    }

    // prefill when editing
    var prefilled by remember { mutableStateOf(false) }
    LaunchedEffect(editLoan.data) {
        val l = editLoan.data ?: return@LaunchedEffect
        if (prefilled) return@LaunchedEffect
        prefilled = true
        val pd = l.numbers("pay_days").map { it.toInt() }
        form = defaults.copy(
            productId = l.str("product_id"),
            principal = numText(l.num("principal")),
            interestMethod = l.str("interest_method"),
            interestRate = numText(l.num("interest_rate")),
            ratePeriod = l.str("rate_period"),
            repaymentFrequency = l.str("repayment_frequency"),
            termCount = l.int("term_count").toString(),
            releaseDate = l.str("release_date"),
            firstDueDate = l.str("first_due_date"),
            payDays = if (pd.size == 2) pd else DEFAULT_PAY_DAYS,
            processingFeeType = l.str("processing_fee_type"),
            processingFeeValue = numText(l.num("processing_fee_value")),
            otherCharges = numText(l.num("other_charges")),
            otherChargesDescription = l.str("other_charges_description"),
            chargesTreatment = l.str("charges_treatment"),
            penaltyEnabled = l.bool("penalty_enabled"),
            penaltyType = l.strOrNull("penalty_type") ?: "percent_monthly",
            penaltyRate = numText(l.num("penalty_rate")),
            penaltyGraceDays = l.int("penalty_grace_days").toString(),
            dayCount = l.str("day_count"),
            rounding = l.str("rounding"),
            purpose = l.str("purpose"),
            notes = l.str("notes"),
            collectorId = l.str("collector_id"),
            agreementTemplateId = l.str("agreement_template_id"),
            signingOrder = l.str("signing_order"),
            witnessCount = l.int("witness_count"),
        )
    }

    val product = products.data?.firstOrNull { it.str("id") == form.productId && form.productId.isNotEmpty() }
    fun applyProduct(p: Row?) {
        if (p == null) { set("product_id") { it.copy(productId = "") }; return }
        var f = form.copy(
            productId = p.str("id"),
            interestMethod = p.str("interest_method"),
            interestRate = numText(p.num("interest_rate")),
            ratePeriod = p.str("rate_period"),
            repaymentFrequency = p.str("repayment_frequency"),
            processingFeeType = p.str("processing_fee_type"),
            processingFeeValue = numText(p.num("processing_fee_value")),
            chargesTreatment = p.str("charges_treatment"),
            dayCount = p.str("day_count"),
            penaltyEnabled = p.bool("penalty_enabled"),
            firstDueDate = "",
        )
        if (p.int("default_term") > 0) f = f.copy(termCount = p.int("default_term").toString())
        if (p.bool("penalty_enabled")) f = f.copy(
            penaltyType = p.strOrNull("penalty_type") ?: "percent_monthly",
            penaltyRate = numText(p.num("penalty_rate")),
            penaltyGraceDays = p.int("penalty_grace_days").toString(),
        )
        if (!p.strOrNull("agreement_template_id").isNullOrBlank()) f = f.copy(agreementTemplateId = p.str("agreement_template_id"))
        form = f
        touched = touched + listOf("product_id", "interest_rate", "term_count", "processing_fee_value", "penalty_rate", "penalty_grace_days")
    }

    // ---- live server-side preview ----
    val errors = validateLoanForm(form)
    val formValid = errors.isEmpty()
    val payloadJson = if (formValid) loanPayload(form).toJson().toString() else null
    val debounced = rememberDebounced(payloadJson, 450)
    val preview = rememberPreview(debounced) { LoansService.preview(it) }
    val previewFresh = payloadJson != null && debounced == payloadJson && preview.freshFor(debounced)
    fun err(k: String) = if (submitted || k in touched) errors[k] else null

    val unit = FREQUENCIES.firstOrNull { it.value == form.repaymentFrequency }?.unit ?: "installments"
    val autoFirstDue = if (form.releaseDate.isNotBlank()) {
        if (form.repaymentFrequency == "monthly") addMonths(form.releaseDate, 1)
        else addDays(form.releaseDate, when (form.repaymentFrequency) { "semimonthly" -> 15; "weekly" -> 7; else -> 1 })
    } else ""

    // autosave new-loan entries (cleared on sign-out)
    val saver = rememberDraftSaver(
        draftKey, enabled = editId == null,
        value = jsonOf("v" to 1, "step" to step, "values" to form.toJson(), "borrowerId" to borrower.strOrNull("id"), "approveNow" to approveNow),
    )

    fun save(mode: String) {
        savingMode = mode
        runner.run {
            val v = form
            val errs = validateLoanForm(v)
            if (errs.isNotEmpty()) throw IllegalStateException(errs.values.first())
            val p = loanPayload(v) + mapOf("borrower_id" to borrower!!.str("id"), "submit" to (mode != "draft"))
            val loan = if (editId != null) LoansService.update(editId, p) else LoansService.create(p)
            val id = loan.str("id")
            val number = loan.str("loan_number")
            // From here on the loan exists: follow-up failures are reported, never thrown, so the page
            // moves on to the saved loan and a second Submit cannot create a duplicate.
            var followUpFailed = false
            if (approveNow && mode != "draft" && auth.can("loans.approve")) {
                try { LoansService.approve(id, "Approved at loan creation") } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                    followUpFailed = true
                    Toasts.error("Loan $number saved, but it could not be approved: ${e.readable()}")
                }
            }
            if (mode == "agreement") {
                try { AgreementsService.generate(id) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                    followUpFailed = true
                    Toasts.error("Loan $number saved, but the agreement could not be generated: ${e.readable()}")
                }
            }
            saver.stop()
            if (!followUpFailed) Toasts.success(
                when (mode) {
                    "draft" -> "Draft $number saved"
                    "agreement" -> "Loan $number created and agreement generated"
                    else -> "Loan $number submitted for review"
                },
            )
            router.replace("/loans/$id${if (mode == "agreement") "?tab=agreement" else ""}")
        }
    }

    // Any step can be reached from a restored draft: send it to the nearest step whose data exists.
    LaunchedEffect(step, borrower, preselectId, formValid) {
        if (editId != null) return@LaunchedEffect
        if (step > 0 && borrower == null && preselectId == null) step = 0
        else if (step >= 2 && !formValid) step = 1
    }

    fun startOver() {
        Drafts.clear(ctx, draftKey)
        form = defaults
        touched = emptySet()
        submitted = false
        borrower = null
        approveNow = false
        restored = false
        step = 0
    }

    val minStep = if (editId != null) 1 else 0
    BackHandler(enabled = step > minStep) { step -= 1 }

    if (editId != null && editLoan.data == null && editLoan.error == null) return PageLoader()
    if (editId != null && editLoan.error != null) return ErrorState(editLoan.error, editLoan.reload)
    val el = editLoan.data
    if (el != null && el.str("status") !in EDITABLE) {
        return EmptyState(
            "This loan can no longer be edited",
            description = "Contractual terms are frozen once signing has started or the loan is active. Use a reschedule amendment instead.",
            action = { Btn("Back to loan", { router.go("/loans/$editId") }, variant = BtnVariant.Outline) },
        )
    }

    fun goPreview() {
        submitted = true
        if (!formValid) { Toasts.error("Please fix the highlighted fields"); return }
        step = 2
    }

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.bringIntoViewRequester(top)) {
            PageHeader(
                title = if (editId != null) "Edit loan ${el.str("loan_number")}" else "New loan",
                description = if (editId != null) "Editing returns the loan to draft and voids any unsigned agreement." else "Create a loan in four guided steps. Figures are calculated and validated on the server.",
                onBack = { if (!router.back()) router.root("/loans") },
                actions = when {
                    el != null -> ({ LoanStatusBadge(el.str("status")) })
                    editId == null && (step > 0 || borrower != null) -> ({ Btn("Start over", ::startOver, variant = BtnVariant.Ghost, icon = Icons.Default.RestartAlt, small = true) })
                    else -> null
                },
            )
        }

        if (restored) RestoredBanner("Your unsaved loan entries were restored.", ::startOver) { restored = false }

        WizardStepper(STEPS, step, enabled = { i -> i <= step && !(i == 0 && editId != null) }, onClick = { i -> step = i })

        if (step == 0) BorrowerStep(borrower) { b -> borrower = b; step = 1 }

        val b = borrower
        if (step >= 1 && b != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp).card().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(initials(b.str("full_name")), 40.dp, bg = LF.Navy.copy(alpha = 0.1f), fg = LF.Navy)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(b.str("full_name"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${b.str("borrower_code")} · ${phMobile(b.strOrNull("mobile_number"))}", fontSize = 12.sp, color = LF.MutedForeground)
                }
                if (editId == null) Btn("Change", { step = 0 }, variant = BtnVariant.Ghost, icon = Icons.Default.Edit, small = true)
            }
        }

        if (step == 1) {
            MainAside(
                main = {
                    LoanDetailsForm(
                        form = form, set = ::set, err = ::err, products = products.data.orEmpty(), product = product, onProduct = ::applyProduct,
                        staff = staff.data.orEmpty(), unit = unit, autoFirstDue = autoFirstDue,
                    )
                    ActionBar {
                        if (editId == null) Btn("Back", { step = 0 }, Modifier.height(48.dp), variant = BtnVariant.Outline, icon = Icons.AutoMirrored.Filled.ArrowBack)
                        Btn("PREVIEW LOAN", ::goPreview, Modifier.weight(1f).height(48.dp), variant = BtnVariant.Blue, icon = Icons.Default.Calculate)
                    }
                },
                aside = {
                    Panel(
                        title = "Live calculation",
                        actions = when {
                            preview.fetching -> ({ Spinner() })
                            previewFresh -> ({ Text("Up to date", fontSize = 12.sp, color = LF.Green600) })
                            else -> null
                        },
                    ) {
                        val pd = preview.data
                        if (preview.error != null && payloadJson != null) {
                            ErrorBox(preview.error)
                        } else if (pd != null) {
                            Column(Modifier.alpha(if (previewFresh) 1f else 0.6f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                HeadlineFigures(pd)
                                SummaryGrid(pd, compact = true)
                            }
                        } else {
                            Text("Enter the loan amount and terms to see the calculation.", Modifier.fillMaxWidth().padding(vertical = 24.dp), fontSize = 14.sp, color = LF.MutedForeground, textAlign = TextAlign.Center)
                        }
                    }
                },
            )
        }

        if (step == 2) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                val pd = preview.data
                if (preview.fetching && pd == null) PageLoader("Calculating on the server…")
                if (preview.error != null) ErrorState(preview.error, preview.refetch)
                if (pd != null) {
                    Panel(
                        title = "Loan calculation preview",
                        description = "Server-calculated with decimal-safe arithmetic. Nothing has been saved yet.",
                        actions = {
                            Btn("Recalculate", preview.refetch, variant = BtnVariant.Outline, small = true, icon = Icons.Default.Refresh, loading = preview.fetching)
                        },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            HeadlineFigures(pd)
                            SummaryGrid(pd)
                            Disclosures(pd)
                        }
                    }
                    val sum = pd.obj("summary")
                    Panel(
                        title = "Amortization schedule",
                        description = "${pd.objs("schedule").size} installments · ${fmtDate(sum.strOrNull("first_due_date"))} to ${fmtDate(sum.strOrNull("last_due_date"))}",
                    ) { ScheduleTable(pd.objs("schedule"), 520.dp) }
                    ActionBar {
                        Btn("Edit loan details", { step = 1 }, Modifier.height(48.dp), variant = BtnVariant.Outline, icon = Icons.Default.Edit)
                        Btn("Continue to agreement", { step = 3 }, Modifier.weight(1f).height(48.dp), icon = Icons.AutoMirrored.Filled.ArrowForward, enabled = previewFresh)
                    }
                }
            }
        }

        val pd = preview.data
        if (step == 3 && pd != null && b != null) {
            MainAside(
                asideWidth = 360.dp,
                main = {
                    Panel(title = "Agreement settings", description = "The agreement uses a frozen snapshot of the template and these exact loan terms.") {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Field("Agreement template") {
                                val opts = listOf(Opt("", "Default template")) + templates.data.orEmpty().filter { it.bool("is_active") }.map { t ->
                                    Opt(t.str("id"), "${t.str("name")}${if (t.bool("is_default")) " (default)" else ""} · v${t.objOrFirst("current").str("version_no")}")
                                }
                                SelectField(form.agreementTemplateId, opts, { v -> set("agreement_template_id") { it.copy(agreementTemplateId = v) } })
                            }
                            FormRow(
                                {
                                    Field("Signing order") {
                                        SelectField(form.signingOrder, listOf(Opt("borrower_first", "Borrower first"), Opt("lender_first", "Lender first"), Opt("any", "Any order")),
                                            { v -> set("signing_order") { it.copy(signingOrder = v) } })
                                    }
                                },
                                {
                                    Field("Witnesses") {
                                        SelectField(form.witnessCount.toString(), listOf(Opt("0", "None"), Opt("1", "1 witness"), Opt("2", "2 witnesses")),
                                            { v -> set("witness_count") { it.copy(witnessCount = v.toInt()) } })
                                    }
                                },
                            )
                        }
                    }
                    Panel(title = "What happens next") {
                        val items = listOf(
                            "Loan application is saved" to "A persistent loan record with server-validated figures is created and submitted for review.",
                            "Agreement PDF is generated" to "Borrower details, terms, today’s date and the full repayment schedule are filled in automatically.",
                            "Borrower signs on their phone" to "Share a secure, time-limited link or QR code. The borrower verifies their identity, reviews and signs.",
                            "Lender signs & approves" to "Both signatures are embedded permanently into the PDF and the document is hashed (SHA-256).",
                            "Release funds" to "Only after approval and all signatures can the loan be released and activated.",
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items.forEachIndexed { i, (t, d) ->
                                Row {
                                    Box(Modifier.size(24.dp).clip(CircleShape).background(LF.Navy), contentAlignment = Alignment.Center) {
                                        Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text("$t.", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        Text(d, fontSize = 14.sp, color = LF.MutedForeground)
                                    }
                                }
                            }
                        }
                        Text(
                            "Generated agreements are templates and must be reviewed for compliance with applicable Philippine laws (SEC lending regulations, Truth in Lending Act, Data Privacy Act, E-Commerce Act). A signature alone does not make an agreement legally compliant.",
                            Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Amber50).padding(12.dp),
                            fontSize = 12.sp, color = Color(0xFF78350F),
                        )
                    }
                },
                aside = {
                    Panel(title = "Confirm loan") {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            HeadlineFigures(pd)
                            val s = pd.obj("summary")
                            Column {
                                Text(b.str("full_name"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("${peso(s.num("principal"))} · ${s.str("duration_label")} · ${s.str("interest_method")}", fontSize = 14.sp, color = LF.MutedForeground)
                            }
                            if (auth.can("loans.approve")) {
                                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, LF.Border, RoundedCornerShape(8.dp)).padding(horizontal = 4.dp)) {
                                    CheckboxRow(approveNow, { approveNow = it }, "Approve now", description = "You have approval rights. Approval is recorded separately from signing.")
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (auth.can("agreements.generate")) {
                                    Btn("Submit & generate agreement", { save("agreement") }, Modifier.fillMaxWidth().height(48.dp), icon = Icons.Default.Draw,
                                        enabled = !runner.busy, loading = runner.busy && savingMode == "agreement")
                                }
                                Btn("Submit for review only", { save("submit") }, Modifier.fillMaxWidth(), variant = BtnVariant.Outline,
                                    enabled = !runner.busy, loading = runner.busy && savingMode == "submit")
                                Btn("Save as draft", { save("draft") }, Modifier.fillMaxWidth(), variant = BtnVariant.Ghost,
                                    enabled = !runner.busy, loading = runner.busy && savingMode == "draft")
                            }
                            Btn("Back to preview", { step = 2 }, Modifier.fillMaxWidth(), variant = BtnVariant.Ghost, icon = Icons.AutoMirrored.Filled.ArrowBack)
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun ErrorBox(text: String) {
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Red50).padding(12.dp), fontSize = 14.sp, color = LF.Red700)
}

// ---------------------------------------------------------------------------
// Step 2 — loan details form
// ---------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoanDetailsForm(
    form: LoanForm,
    set: (String, (LoanForm) -> LoanForm) -> Unit,
    err: (String) -> String?,
    products: List<Row>,
    product: Row?,
    onProduct: (Row?) -> Unit,
    staff: List<Row>,
    unit: String,
    autoFirstDue: String,
) {
    val auth by AppState.state.collectAsState()
    val interest = auth.setting("interest")
    val repayment = auth.setting("repayment")
    var advanced by remember { mutableStateOf(false) }

    Panel(title = "Loan product & amount") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Field("Loan product", hint = if (product != null) product.strOrNull("description") else "Optional — fills in the product’s default terms") {
                val opts = listOf(Opt("", "Custom terms (no product)")) + products.map { p ->
                    Opt(p.str("id"), "${p.str("name")} — ${trimNumber(p.num("interest_rate"), 4)}% / ${p.str("rate_period")} · ${p.str("interest_method")}")
                }
                SelectField(form.productId, opts, { id -> onProduct(products.firstOrNull { it.str("id") == id }) })
            }
            FormRow(
                {
                    val hint = if (product != null && (product.num("min_amount") > 0 || product.num("max_amount") > 0)) {
                        "Product range ${peso(product.num("min_amount"), 0)} – ${if (product.num("max_amount") > 0) peso(product.num("max_amount"), 0) else "no limit"}"
                    } else null
                    Field("Loan amount (₱)", required = true, error = err("principal"), hint = hint) {
                        MoneyInput(form.principal, { r -> set("principal") { it.copy(principal = r) } }, placeholder = "50,000.00", isError = err("principal") != null)
                    }
                },
                {
                    Field("Purpose of loan") {
                        TextInput(form.purpose, { v -> set("purpose") { it.copy(purpose = v) } }, placeholder = "e.g. Business capital, tuition")
                    }
                },
            )
        }
    }

    Panel(title = "Interest", description = "The entered rate is a nominal rate for the chosen period. Conversions are disclosed in the preview.") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val methods = listOf(
                Triple("flat", "Flat interest", "Interest on the original principal for the whole term. Equal installments."),
                Triple("diminishing", "Diminishing balance", "Interest on the outstanding principal each period. Interest falls as you pay."),
            )
            FormRow(
                *methods.map { (v, title, desc) ->
                    @Composable {
                        val disabled = (v == "flat" && interest?.boolOrNull("flat_enabled") == false) || (v == "diminishing" && interest?.boolOrNull("diminishing_enabled") == false)
                        val on = form.interestMethod == v
                        val shape = RoundedCornerShape(12.dp)
                        Column(
                            Modifier.fillMaxWidth().alpha(if (disabled) 0.4f else 1f).clip(shape).background(if (on) LF.Blue50.copy(alpha = 0.6f) else Color.White)
                                .border(2.dp, if (on) LF.Blue else LF.Slate200, shape).clickable(enabled = !disabled) { set("interest_method") { it.copy(interestMethod = v) } }
                                .padding(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                if (on) Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = LF.Blue)
                            }
                            Text(desc, Modifier.padding(top = 4.dp), fontSize = 12.sp, color = LF.MutedForeground)
                        }
                    }
                }.toTypedArray(),
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) {
                    Field("Interest rate (%)", required = true, error = err("interest_rate")) {
                        NumberInput(form.interestRate, { v -> set("interest_rate") { it.copy(interestRate = v) } })
                    }
                }
                Box(Modifier.weight(1f)) {
                    Field("Rate period") {
                        SelectField(form.ratePeriod, RATE_PERIODS, { v -> set("rate_period") { it.copy(ratePeriod = v) } })
                    }
                }
            }
            val presets = interest.numbers("rate_presets")
            if (presets.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { r ->
                        val on = numOf(form.interestRate) == r
                        Text(
                            "${numText(r)}%",
                            Modifier.clip(CircleShape).background(if (on) LF.Blue50 else Color.White)
                                .border(1.dp, if (on) LF.Blue else LF.Border, CircleShape)
                                .clickable { set("interest_rate") { it.copy(interestRate = numText(r)) } }.padding(horizontal = 10.dp, vertical = 4.dp),
                            fontSize = 12.sp, color = if (on) LF.Blue700 else LF.Foreground,
                        )
                    }
                }
            }
        }
    }

    Panel(title = "Repayment") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FormRow(
                {
                    Field("Repayment frequency") {
                        val allowed = repayment.strings("frequencies")
                        val opts = FREQUENCIES.filter { auth.settings == null || it.value in allowed }.map { Opt(it.value, it.label) }
                        SelectField(form.repaymentFrequency, opts, { v -> set("repayment_frequency") { it.copy(repaymentFrequency = v, firstDueDate = "") } })
                    }
                },
                {
                    val n = numOf(form.termCount)
                    Field(
                        "Repayment duration ($unit)", required = true, error = err("term_count"),
                        hint = if (n != null && n > 0) "${numText(n)} ${FREQUENCY_LABEL[form.repaymentFrequency] ?: form.repaymentFrequency} installments" else null,
                    ) { NumberInput(form.termCount, { v -> set("term_count") { it.copy(termCount = v) } }, decimal = false) }
                },
            )
            FormRow(
                {
                    Field("Loan release date", required = true, error = err("release_date")) {
                        DateField(form.releaseDate, { v -> set("release_date") { it.copy(releaseDate = v) } })
                    }
                },
                {
                    if (form.repaymentFrequency != "semimonthly") {
                        Field(
                            "First repayment date", error = err("first_due_date"),
                            hint = if (form.firstDueDate.isBlank() && autoFirstDue.isNotBlank()) "Automatic: ${fmtDate(autoFirstDue)}" else "Leave blank for one period after release",
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DateField(form.firstDueDate, { v -> set("first_due_date") { it.copy(firstDueDate = v) } }, Modifier.weight(1f))
                                if (form.firstDueDate.isNotBlank()) {
                                    IconButton(onClick = { set("first_due_date") { it.copy(firstDueDate = "") } }) {
                                        Icon(Icons.Default.Close, "Use automatic date", tint = LF.MutedForeground)
                                    }
                                }
                            }
                        }
                    }
                },
            )
            if (form.repaymentFrequency == "semimonthly") {
                PayDaysPicker(
                    value = form.payDays,
                    releaseDate = form.releaseDate,
                    onChange = { d -> set("pay_days") { it.copy(payDays = d) } },
                    firstDue = form.firstDueDate.ifBlank { null },
                    onFirstDueChange = { d -> set("first_due_date") { it.copy(firstDueDate = d ?: "") } },
                )
            }
        }
    }

    Panel(title = "Fees & charges") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FormRow(
                {
                    Field("Processing fee") {
                        SelectField(form.processingFeeType, listOf(Opt("none", "None"), Opt("percent", "Percent of principal"), Opt("fixed", "Fixed amount")),
                            { v -> set("processing_fee_type") { it.copy(processingFeeType = v) } })
                    }
                },
                {
                    Field(if (form.processingFeeType == "percent") "Fee (%)" else "Fee (₱)", error = err("processing_fee_value")) {
                        NumberInput(form.processingFeeValue, { v -> set("processing_fee_value") { it.copy(processingFeeValue = v) } }, enabled = form.processingFeeType != "none")
                    }
                },
                {
                    Field("How charges are collected") {
                        SelectField(form.chargesTreatment, listOf(Opt("deducted", "Deducted from proceeds"), Opt("added", "Added to installments")),
                            { v -> set("charges_treatment") { it.copy(chargesTreatment = v) } })
                    }
                },
            )
            FormRow(
                {
                    Field("Other charges (₱)", error = err("other_charges")) {
                        MoneyInput(form.otherCharges, { r -> set("other_charges") { it.copy(otherCharges = r) } })
                    }
                },
                {
                    Field("Other charges description") {
                        TextInput(form.otherChargesDescription, { v -> set("other_charges_description") { it.copy(otherChargesDescription = v) } }, placeholder = "e.g. Notarial fee, documentary stamp")
                    }
                },
            )
        }
    }

    Panel(
        title = "Late payment penalty",
        actions = {
            Switch(form.penaltyEnabled, { c -> set("penalty_enabled") { it.copy(penaltyEnabled = c) } }, colors = SwitchDefaults.colors(checkedTrackColor = LF.Navy))
        },
    ) {
        if (form.penaltyEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Field("Penalty type", error = err("penalty_type")) {
                    SelectField(form.penaltyType, PENALTY_TYPES, { v -> set("penalty_type") { it.copy(penaltyType = v) } })
                }
                FormRow(
                    {
                        Field(if (form.penaltyType == "fixed") "Fee (₱)" else "Rate (%)", error = err("penalty_rate")) {
                            NumberInput(form.penaltyRate, { v -> set("penalty_rate") { it.copy(penaltyRate = v) } })
                        }
                    },
                    {
                        Field("Grace period (days)", error = err("penalty_grace_days")) {
                            NumberInput(form.penaltyGraceDays, { v -> set("penalty_grace_days") { it.copy(penaltyGraceDays = v) } }, decimal = false)
                        }
                    },
                )
            }
        } else {
            Text("No late-payment penalty will be charged on this loan.", fontSize = 14.sp, color = LF.MutedForeground)
        }
    }

    Column(Modifier.fillMaxWidth().card()) {
        Row(
            Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Advanced & internal", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.padding(start = 8.dp).size(16.dp), tint = LF.MutedForeground)
        }
        if (advanced) {
            HorizontalDivider(color = LF.Border)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FormRow(
                    {
                        Field("Day-count basis", hint = "Diminishing loans: actual-day accrual handles leap years and irregular periods") {
                            SelectField(
                                form.dayCount,
                                listOf(Opt("30/360", "30/360 (equal periods)"), Opt("actual/365", "Actual/365"), Opt("actual/actual", "Actual/Actual (leap-year aware)")),
                                { v -> set("day_count") { it.copy(dayCount = v) } },
                            )
                        }
                    },
                    {
                        Field("Installment rounding") {
                            SelectField(
                                form.rounding,
                                listOf(Opt("centavo", "To the centavo"), Opt("peso", "To the whole peso (final installment adjusts)")),
                                { v -> set("rounding") { it.copy(rounding = v) } },
                            )
                        }
                    },
                )
                Field("Assigned collector") {
                    val opts = listOf(Opt("", "Unassigned")) + staff
                        .filter { it.bool("is_active") && it.str("role") in listOf("collector", "loan_officer", "admin") }
                        .map { Opt(it.str("id"), "${it.str("full_name")} (${it.str("role").replaceFirst("_", " ")})") }
                    SelectField(form.collectorId, opts, { v -> set("collector_id") { it.copy(collectorId = v) } })
                }
                Field("Internal loan notes") {
                    TextInput(form.notes, { v -> set("notes") { it.copy(notes = v) } }, placeholder = "Visible to staff only", singleLine = false, minLines = 3)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step 1 — borrower
// ---------------------------------------------------------------------------
@Composable
private fun BorrowerStep(borrower: Row?, onSelect: (Row) -> Unit) {
    val auth by AppState.state.collectAsState()
    val router = LocalRouter.current
    var term by remember { mutableStateOf("") }
    val debounced = rememberDebounced(term, 250)
    val results = rememberLoad(debounced) { BorrowersService.search(debounced, 10) }
    var lastResults by remember { mutableStateOf<List<Row>?>(null) }
    results.data?.let { lastResults = it }
    val list = results.data ?: lastResults
    var picked by remember { mutableStateOf(borrower) }
    val pickedId = picked.strOrNull("id")
    val history = rememberLoad(pickedId, enabled = pickedId != null) { BorrowersService.loans(pickedId!!) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) { } }

    MainAside(
        main = {
            Panel(
                title = "Select borrower",
                description = "Search by name, borrower code or mobile number.",
                actions = if (auth.can("borrowers.create")) ({
                    Btn("Register new", { router.go("/borrowers/new?return=/loans/new") }, variant = BtnVariant.Outline, small = true, icon = Icons.Default.PersonAdd)
                }) else null,
            ) {
                SearchField(term, { term = it }, Modifier.padding(bottom = 12.dp).focusRequester(focus), placeholder = "e.g. Dela Cruz, BRW-000012, 0917…")
                when {
                    list == null -> PageLoader()
                    list.isEmpty() -> EmptyState(
                        "No borrowers found",
                        description = "Register the borrower first — you'll come right back to this loan.",
                        action = if (auth.can("borrowers.create")) ({
                            Btn("Register borrower", { router.go("/borrowers/new?return=/loans/new") }, icon = Icons.Default.PersonAdd)
                        }) else null,
                    )
                    else -> {
                        val shape = RoundedCornerShape(8.dp)
                        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, LF.Border, shape)) {
                            list.forEachIndexed { i, b ->
                                if (i > 0) HorizontalDivider(color = LF.Border)
                                val sel = pickedId == b.str("id")
                                Row(
                                    Modifier.fillMaxWidth().background(if (sel) LF.Blue50 else Color.White).clickable { picked = b }.padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Avatar(initials(b.str("full_name")), 40.dp, bg = LF.Slate100, fg = LF.Slate700)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(b.str("full_name"), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${b.str("borrower_code")} · ${phMobile(b.strOrNull("mobile_number"))} · ${b.strOrNull("city_municipality") ?: "—"}",
                                            fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    val risk = b.strOrNull("risk_rating")
                                    if (risk != null && risk != "unrated") RiskBadge(risk)
                                    if (sel) Icon(Icons.Default.CheckCircle, null, Modifier.padding(start = 6.dp).size(20.dp), tint = LF.Blue)
                                }
                            }
                        }
                    }
                }
            }
        },
        aside = {
            Panel(title = "Borrower summary") {
                val p = picked
                if (p == null) {
                    Text("Select a borrower to see their history.", Modifier.fillMaxWidth().padding(vertical = 24.dp), fontSize = 14.sp, color = LF.MutedForeground, textAlign = TextAlign.Center)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column {
                            Text(p.str("full_name"), fontWeight = FontWeight.SemiBold)
                            Text(
                                (p.strOrNull("occupation") ?: "Occupation not set") + (p.strOrNull("employer_name")?.let { " · $it" } ?: ""),
                                fontSize = 12.sp, color = LF.MutedForeground,
                            )
                            if (p.num("monthly_income") > 0) Text("Monthly income ${peso(p.num("monthly_income"), 0)}", fontSize = 12.sp, color = LF.MutedForeground)
                        }
                        Column {
                            Text("LOAN HISTORY", Modifier.padding(bottom = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground, letterSpacing = 0.5.sp)
                            val h = history.data
                            when {
                                h == null && history.error == null -> Spinner()
                                h.isNullOrEmpty() -> Text("First loan with you.", fontSize = 14.sp, color = LF.MutedForeground)
                                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    h.take(5).forEach { l ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(l.str("loan_number"), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("${peso(l.num("principal"), 0)} · ${fmtDate(l.strOrNull("created_at"))}", fontSize = 12.sp, color = LF.MutedForeground)
                                            }
                                            LoanStatusBadge(l.str("status"))
                                        }
                                    }
                                }
                            }
                            if (h.orEmpty().any { it.num("overdue_amount") > 0 }) {
                                Text(
                                    "This borrower has overdue installments on an existing loan.",
                                    Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(LF.Red50).padding(8.dp),
                                    fontSize = 12.sp, color = LF.Red700,
                                )
                            }
                        }
                        Btn("Continue with this borrower", { onSelect(p) }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Filled.ArrowForward)
                    }
                }
            }
        },
    )
}
