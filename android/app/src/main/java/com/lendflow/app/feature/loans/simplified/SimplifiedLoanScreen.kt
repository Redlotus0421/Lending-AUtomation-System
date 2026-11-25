package com.lendflow.app.feature.loans.simplified

// Port of src/features/loans/simplified/SimplifiedLoanPage.tsx — Simplified Lending: New Loan /
// Reloan-Renew in a few guided steps. The business's lending mode (set by the platform administrator)
// decides that this page is used; staff never pick the mode. Every figure — rate, fees, outstanding
// balances, deductions — is computed by the database.

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.AgreementsService
import com.lendflow.app.data.BorrowersService
import com.lendflow.app.data.PickedFile
import com.lendflow.app.data.Row
import com.lendflow.app.data.SimplifiedService
import com.lendflow.app.data.bool
import com.lendflow.app.data.boolOrNull
import com.lendflow.app.data.int
import com.lendflow.app.data.jsonOf
import com.lendflow.app.data.numOrNull
import com.lendflow.app.data.numbers
import com.lendflow.app.data.obj
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.toJson
import com.lendflow.app.feature.loans.DEFAULT_PAY_DAYS
import com.lendflow.app.feature.loans.Drafts
import com.lendflow.app.feature.loans.RestoredBanner
import com.lendflow.app.feature.loans.ScrollToTopOnChange
import com.lendflow.app.feature.loans.WizardStepper
import com.lendflow.app.feature.loans.nextPayDates
import com.lendflow.app.feature.loans.numText
import com.lendflow.app.feature.loans.rememberDraftSaver
import com.lendflow.app.feature.loans.rememberPreview
import com.lendflow.app.feature.loans.validFirstDue
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.PageLoader
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.rememberDebounced
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Tone
import com.lendflow.app.util.normalizePhMobile
import com.lendflow.app.util.trimNumber
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToLong

// ---------------------------------------------------------------------------
// Steps
// ---------------------------------------------------------------------------
internal data class StepDef(val key: String, val label: String)

internal val SIMPLIFIED_STEPS: Map<String, List<StepDef>> = mapOf(
    "new" to listOf(
        StepDef("type", "Transaction"), StepDef("borrower", "Borrower"), StepDef("loan", "Loan details"),
        StepDef("review", "Review"), StepDef("agreement", "Agreement & signature"),
    ),
    "renewal" to listOf(
        StepDef("type", "Transaction"), StepDef("search", "Find borrower"), StepDef("balance", "Balance check"),
        StepDef("loan", "Loan details"), StepDef("review", "Review"), StepDef("agreement", "Agreement & signature"),
    ),
)

// ---------------------------------------------------------------------------
// New borrower entries
// ---------------------------------------------------------------------------
internal data class BorrowerDraft(
    val first_name: String = "", val middle_name: String = "", val last_name: String = "", val address: String = "",
    val mobile: String = "", val employer: String = "", val id_type: String = "", val id_number: String = "",
    val co_name: String = "", val co_relationship: String = "", val co_mobile: String = "", val co_address: String = "",
) {
    fun toJson(): JsonObject = jsonOf(
        "first_name" to first_name, "middle_name" to middle_name, "last_name" to last_name, "address" to address, "mobile" to mobile,
        "employer" to employer, "id_type" to id_type, "id_number" to id_number, "co_name" to co_name, "co_relationship" to co_relationship,
        "co_mobile" to co_mobile, "co_address" to co_address,
    )
    fun anyEntered(): Boolean = listOf(
        first_name, middle_name, last_name, address, mobile, employer, id_type, id_number, co_name, co_relationship, co_mobile, co_address,
    ).any { it.isNotBlank() }
    val fullName: String get() = listOf(first_name, middle_name, last_name).filter { it.isNotBlank() }.joinToString(" ")

    companion object {
        fun from(j: JsonObject?): BorrowerDraft = if (j == null) BorrowerDraft() else BorrowerDraft(
            j.str("first_name"), j.str("middle_name"), j.str("last_name"), j.str("address"), j.str("mobile"), j.str("employer"),
            j.str("id_type"), j.str("id_number"), j.str("co_name"), j.str("co_relationship"), j.str("co_mobile"), j.str("co_address"),
        )
    }
}

private val PH_MOBILE = Regex("^\\+639\\d{9}$")

/** Same rules and messages as the web borrowerSchema. */
internal fun validateBorrower(v: BorrowerDraft): Map<String, String> {
    val e = linkedMapOf<String, String>()
    if (v.first_name.trim().isEmpty()) e["first_name"] = "Enter the first name"
    if (v.last_name.trim().isEmpty()) e["last_name"] = "Enter the last name"
    if (v.address.trim().length < 8) e["address"] = "Enter the complete address"
    if (!PH_MOBILE.matches(normalizePhMobile(v.mobile.trim()))) e["mobile"] = "Use a PH mobile number, e.g. 0917 123 4567"
    if (v.employer.trim().length < 2) e["employer"] = "Enter the company or employer"
    if (v.id_type.isEmpty()) e["id_type"] = "Select the ID type"
    if (v.id_number.trim().length < 3) e["id_number"] = "Enter the ID number"
    if (v.co_name.trim().length < 3) e["co_name"] = "Enter the co-maker's full name"
    if (v.co_relationship.isEmpty()) e["co_relationship"] = "Select the relationship"
    if (!PH_MOBILE.matches(normalizePhMobile(v.co_mobile.trim()))) e["co_mobile"] = "Enter the co-maker's PH mobile number"
    return e
}

internal data class BorrowerFiles(val photo: PickedFile? = null, val id: PickedFile? = null, val atm: PickedFile? = null)

/** Everything the page holds (the web keeps the same values in useState / react-hook-form). */
@Stable
internal class SimplifiedState(initial: JsonObject?, typeParam: String?, borrowerParam: String?, files: BorrowerFiles, defaultTerm: String, defaultRate: String, defaultFrequency: String) {
    var step by mutableStateOf(initial.strOrNull("step") ?: "type")
    var type by mutableStateOf(if (initial.strOrNull("step") != null && initial.strOrNull("step") != "type") initial.strOrNull("type") else typeParam)
    var borrower by mutableStateOf(BorrowerDraft.from(initial.obj("borrower")))
    var touched by mutableStateOf(setOf<String>())
    var submitted by mutableStateOf(false)
    var files by mutableStateOf(files)
    var fileErrors by mutableStateOf(mapOf<String, String>())
    var createdBorrowerId by mutableStateOf(initial.strOrNull("createdBorrowerId"))
    var uploadsDone by mutableStateOf(initial.bool("uploadsDone"))
    var borrowerId by mutableStateOf(initial.strOrNull("borrowerId") ?: borrowerParam)
    var principal by mutableStateOf(initial.strOrNull("principal") ?: "")
    var term by mutableStateOf(initial.strOrNull("term") ?: defaultTerm)
    var rate by mutableStateOf(initial.strOrNull("rate") ?: defaultRate)
    var frequency by mutableStateOf(initial.strOrNull("frequency") ?: defaultFrequency)
    var payDays by mutableStateOf(initial.numbers("payDays").map { it.toInt() }.takeIf { it.size == 2 } ?: DEFAULT_PAY_DAYS)
    var firstDue by mutableStateOf(initial.strOrNull("firstDue"))
    var created by mutableStateOf(initial.obj("created"))
    var agreementId by mutableStateOf(initial.strOrNull("agreementId"))
    var finished by mutableStateOf(false)

    fun toJson(): JsonObject = jsonOf(
        "v" to 1, "step" to step, "type" to type, "borrower" to borrower.toJson(), "borrowerId" to borrowerId, "principal" to principal,
        "term" to term, "rate" to rate, "frequency" to frequency, "payDays" to payDays, "firstDue" to firstDue,
        "createdBorrowerId" to createdBorrowerId, "uploadsDone" to uploadsDone, "created" to created, "agreementId" to agreementId,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SimplifiedLoanScreen(type: String?, borrowerId: String?) {
    val auth by AppState.state.collectAsState()
    val router = LocalRouter.current
    val ctx = LocalContext.current
    val settings = auth.settings
    val simplified = auth.setting("simplified")
    val interest = auth.setting("interest")
    val repayment = auth.setting("repayment")
    val draftKey = "simplified:${auth.access.strOrNull("user_id") ?: "anon"}"
    val defaultTerm = numText(simplified.numOrNull("default_term") ?: 6.0)
    val defaultRate = interest.numOrNull("default_rate")?.let { numText(it) } ?: ""
    val defaultFrequency = repayment.strOrNull("default_frequency") ?: "monthly"

    // Restore saved entries once. A "Reloan" link for a specific borrower starts fresh unless the
    // saved draft is for that same borrower.
    val initial: JsonObject? = remember {
        val d = Drafts.load(ctx, draftKey)
        when {
            d == null || d.int("v") != 1 -> null
            borrowerId != null && d.strOrNull("borrowerId") != null && d.strOrNull("borrowerId") != borrowerId -> { Drafts.clear(ctx, draftKey); null }
            // Picked a transaction in the New loan popup: a draft of the other kind is discarded, unless
            // its loan was already created (that one must be finished first).
            type != null && d.strOrNull("type") != null && d.strOrNull("type") != type && d.obj("created") == null -> { Drafts.clear(ctx, draftKey); null }
            else -> d
        }
    }
    val s = remember {
        SimplifiedState(
            initial, type, borrowerId,
            files = if (initial != null) Drafts.getFiles<BorrowerFiles>(draftKey) ?: BorrowerFiles() else BorrowerFiles(),
            defaultTerm = defaultTerm, defaultRate = defaultRate, defaultFrequency = defaultFrequency,
        )
    }
    // only announce a restore when something was actually entered
    var restored by remember {
        mutableStateOf(
            initial != null && initial.strOrNull("step") != "type" && (
                initial.obj("created") != null || initial.strOrNull("borrowerId") != null || initial.str("principal") != "" ||
                    BorrowerDraft.from(initial.obj("borrower")).anyEntered()
                ),
        )
    }
    val top = remember { BringIntoViewRequester() }
    ScrollToTopOnChange(s.step, top)

    fun setFiles(f: BorrowerFiles) { s.files = f; Drafts.setFiles(draftKey, f) }

    fun go(step: String) { s.step = step }

    fun startType(t: String, replace: Boolean = false) {
        if (t == "new" && !(auth.can("borrowers.create") && auth.can("borrowers.view_sensitive"))) {
            Toasts.error("Registering a new borrower needs permission to create borrowers and handle identity documents.")
            if (replace) { s.type = null; s.step = "type" }
            return
        }
        s.type = t
        s.step = if (t == "new") "borrower" else if (s.borrowerId != null) "balance" else "search"
    }

    // entry point: resume a saved draft, open a "Reloan" link on the balance step, or start the
    // transaction picked in the New loan popup
    LaunchedEffect(Unit) {
        val iStep = initial.strOrNull("step")
        val iType = initial.strOrNull("type")
        if (initial != null && iStep != null && iStep != "type" && iType != null) {
            s.type = iType
            s.step = iStep
        } else if (borrowerId != null) {
            s.type = "renewal"
            s.step = "balance"
        } else if (type != null) {
            startType(type, true)
        }
    }

    LaunchedEffect(settings) {
        if (settings != null) {
            if (s.term.isEmpty()) s.term = defaultTerm
            if (s.rate.isEmpty()) s.rate = defaultRate
        }
    }

    // autosave everything typed so far (cleared on sign-out)
    val saver = rememberDraftSaver(draftKey, enabled = true, value = s.toJson())
    if (s.finished) saver.enabled = false

    fun startOver() {
        Drafts.clear(ctx, draftKey)
        s.borrower = BorrowerDraft()
        s.touched = emptySet()
        s.submitted = false
        s.files = BorrowerFiles()
        s.fileErrors = emptyMap()
        s.createdBorrowerId = null
        s.uploadsDone = false
        s.borrowerId = null
        s.principal = ""
        s.term = defaultTerm
        s.rate = defaultRate
        s.frequency = defaultFrequency
        s.payDays = DEFAULT_PAY_DAYS
        s.firstDue = null
        s.created = null
        s.agreementId = null
        restored = false
        s.type = null
        s.step = "type"
    }

    val curType = s.type
    val steps = if (curType != null) SIMPLIFIED_STEPS.getValue(curType) else SIMPLIFIED_STEPS.getValue("new").take(1)
    val stepIndex = steps.indexOfFirst { it.key == s.step }
    val locked = s.created != null // once the loan exists, earlier steps are read-only

    val statusId = s.borrowerId
    val status = rememberLoad(statusId, curType, enabled = curType == "renewal" && statusId != null) { SimplifiedService.borrowerStatus(statusId!!) }

    val allowOverride = simplified.bool("allow_rate_override")
    val input: Map<String, Any?>? = run {
        val t = curType ?: return@run null
        val p = s.principal.trim().toDoubleOrNull() ?: 0.0
        val tm = s.term.trim().toDoubleOrNull()
        if (!(p > 0) || tm == null || tm % 1.0 != 0.0 || tm < 1 || tm > 360) return@run null
        if (t == "renewal" && s.borrowerId == null) return@run null
        val r = s.rate.trim().toDoubleOrNull()
        val semi = s.frequency == "semimonthly"
        val m = linkedMapOf<String, Any?>("transaction_type" to t)
        if (t == "renewal") m["borrower_id"] = s.borrowerId
        m["principal"] = (p * 100).roundToLong() / 100.0
        m["term_months"] = tm.toInt()
        m["repayment_frequency"] = s.frequency
        if (semi) m["pay_days"] = s.payDays
        // a saved first payment date that is no longer offered (e.g. a draft resumed days later) falls back to the earliest
        val fd = s.firstDue
        m["first_due_date"] = if (semi) (if (fd != null && fd in nextPayDates(s.payDays)) fd else null) else validFirstDue(fd)
        m["interest_rate"] = if (allowOverride && s.rate != "" && r != null && r.isFinite()) r else null
        m
    }
    val inputJson = input?.toJson()?.toString()

    // Any step can be reached from a restored draft: send it to the nearest step whose data exists.
    LaunchedEffect(s.step, curType, s.created, s.borrowerId, input != null) {
        val step = s.step
        when {
            s.created != null && step != "agreement" -> go("agreement")
            step == "type" -> Unit
            curType == null -> go("type")
            steps.none { it.key == step } -> go(if (curType == "new") "borrower" else "search")
            step == "balance" && s.borrowerId == null -> go("search")
            (step == "loan" || step == "review") && curType == "renewal" && s.borrowerId == null -> go("search")
            step == "review" && input == null -> go("loan")
            step == "agreement" && s.created == null -> go(if (input != null) "review" else "loan")
        }
    }

    val debounced = rememberDebounced(inputJson, 400)
    val preview = rememberPreview(debounced, enabled = s.step == "loan" || s.step == "review") { SimplifiedService.preview(it) }
    val previewFresh = inputJson != null && debounced == inputJson && preview.freshFor(debounced)

    val borrowerName = if (curType == "renewal") status.data.obj("borrower").str("full_name") else s.borrower.fullName

    BackHandler(enabled = !locked && stepIndex > 0) { go(steps[stepIndex - 1].key) }

    if (settings == null) return PageLoader()

    suspend fun finalize() {
        val t = curType ?: return
        val inp = input ?: throw IllegalStateException("Enter the loan amount and a repayment period of 1 to 360 whole months")
        val f = s.files
        if (t == "new" && (f.photo == null || f.id == null || f.atm == null) && !s.uploadsDone) {
            go("borrower")
            throw IllegalStateException("Re-attach the borrower photo, ID and ATM card before saving.")
        }
        // 1. register the borrower once (retries reuse the record), then the documents
        var bid = if (t == "renewal") s.borrowerId else s.createdBorrowerId
        if (t == "new") {
            val v = s.borrower
            if (bid == null) {
                val b = BorrowersService.create(
                    mapOf(
                        "first_name" to v.first_name.trim(), "middle_name" to v.middle_name.trim().ifEmpty { null }, "last_name" to v.last_name.trim(),
                        "mobile_number" to normalizePhMobile(v.mobile), "address_line" to v.address.trim(), "employer_name" to v.employer.trim(),
                    ),
                    mapOf("id_type" to v.id_type, "id_number" to v.id_number.trim()),
                    mapOf(
                        "full_name" to v.co_name.trim(), "relationship" to v.co_relationship,
                        "mobile_number" to normalizePhMobile(v.co_mobile), "address" to v.co_address.trim().ifEmpty { null },
                    ),
                )
                bid = b.str("id")
                s.createdBorrowerId = bid
            }
            val id = bid!!
            if (!s.uploadsDone) {
                val org = BorrowersService.get(id).str("organization_id")
                f.photo?.let { photo ->
                    val path = BorrowersService.uploadBorrowerFile(org, id, "profile", photo)
                    BorrowersService.update(id, mapOf("profile_photo_path" to path))
                }
                f.id?.let { idFile ->
                    val path = BorrowersService.uploadBorrowerFile(org, id, "identity", idFile)
                    BorrowersService.saveIdentity(id, mapOf("id_front_path" to path))
                }
                f.atm?.let { BorrowersService.addDocument(org, id, it, "atm_card", "ATM card", "identity") }
                s.uploadsDone = true
            }
        }
        // 2. create the loan (server computes everything and settles nothing until release)
        var loan: Row? = s.created
        if (loan == null) {
            loan = SimplifiedService.create(inp + ("borrower_id" to bid))
            s.created = loan
        }
        // 3. generate the agreement from the existing template
        if (s.agreementId == null) {
            val a = AgreementsService.generate(loan.str("id"))
            s.agreementId = a.str("id")
        }
        go("agreement")
    }

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.bringIntoViewRequester(top)) {
            SimplifiedHeader(
                title = when (curType) { "renewal" -> "Reloan / Renew"; "new" -> "New loan"; else -> "Create a loan" },
                rateText = "Rate ${trimNumber(interest.numOrNull("default_rate") ?: 0.0, 4)}% / ${interest.strOrNull("default_rate_period") ?: "monthly"}",
                onBack = { if (!router.back()) router.root("/loans") },
                startOver = if (s.step != "type" && s.created == null) ::startOver else null,
            )
        }

        if (restored) {
            val created = s.created
            val f = s.files
            val text = (if (created != null) "Resumed loan ${created.str("loan_number")} where you left off." else "Your unsaved entries were restored.") +
                (if (created == null && curType == "new" && f.photo == null && f.id == null && f.atm == null && BorrowerDraft.from(initial.obj("borrower")).first_name != "")
                    " Attached files are not kept after the app is closed — check the photo, ID and ATM card." else "")
            RestoredBanner(text, if (created == null) ::startOver else null) { restored = false }
        }

        if (curType != null) {
            WizardStepper(
                steps.map { it.label }, stepIndex,
                enabled = { i -> !(i > stepIndex || (locked && steps[i].key != "agreement")) },
                onClick = { i -> go(steps[i].key) },
                showLabels = !com.lendflow.app.ui.components.isCompact(),
            )
        }

        when {
            s.step == "type" -> TransactionOptions(onPick = { startType(it) })

            s.step == "borrower" && curType == "new" -> NewBorrowerStep(
                s = s, setFiles = ::setFiles,
                onBack = { go("type") },
                onRenewInstead = { id -> s.borrowerId = id; s.type = "renewal"; go("balance") },
                onNext = { go("loan") },
            )

            s.step == "search" && curType == "renewal" -> SearchStep(s.borrowerId, onBack = { go("type") }) { b -> s.borrowerId = b.str("id"); go("balance") }

            s.step == "balance" && curType == "renewal" -> {
                val d = status.data
                when {
                    d == null && status.error == null -> PageLoader("Checking outstanding balance…")
                    d == null -> ErrorState(status.error ?: "", status.reload)
                    else -> BalanceStep(d, onBack = { go("search") }, onNext = { go("loan") })
                }
            }

            s.step == "loan" && curType != null -> LoanStep(
                type = curType, s = s, allowOverride = allowOverride, borrowerName = borrowerName,
                preview = preview.data, previewError = if (input != null) preview.error else null,
                fetching = preview.fetching, fresh = previewFresh,
                onBack = { go(if (curType == "new") "borrower" else "balance") },
                onNext = {
                    when {
                        input == null -> Toasts.error("Enter the loan amount and a repayment period of 1 to 360 whole months")
                        preview.error != null -> Toasts.error(preview.error)
                        else -> go("review")
                    }
                },
            )

            s.step == "review" && curType != null && input != null -> ReviewStep(
                type = curType, preview = preview.data, fresh = previewFresh, fetching = preview.fetching, refetch = preview.refetch,
                draft = if (curType == "new") s.borrower else null, files = s.files, status = status.data,
                onBack = { go("loan") }, finalize = { finalize() },
            )

            s.step == "agreement" && s.created != null -> {
                val created = s.created!!
                val aid = s.agreementId
                if (aid != null) {
                    AgreementStep(created, aid, borrowerName) { s.finished = true; saver.stop() }
                } else {
                    RetryAgreement(created) { id -> s.agreementId = id }
                }
            }
        }
    }
}

/** PageHeader with the rich description (rate pill) of the web page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimplifiedHeader(title: String, rateText: String, onBack: () -> Unit, startOver: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onBack).padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(16.dp), tint = LF.MutedForeground)
            Spacer(Modifier.width(4.dp))
            Text("Back", color = LF.MutedForeground, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = LF.Foreground)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Simplified lending", fontSize = 14.sp, color = LF.MutedForeground, modifier = Modifier.align(Alignment.CenterVertically))
            Pill(rateText, Tone.teal, dot = false, modifier = Modifier.align(Alignment.CenterVertically))
            Text("Rates and fees follow your business settings; every figure is computed on the server.", fontSize = 14.sp, color = LF.MutedForeground)
        }
        if (startOver != null) {
            Spacer(Modifier.height(12.dp))
            Btn("Start over", startOver, variant = BtnVariant.Ghost, icon = Icons.Default.RestartAlt, small = true)
        }
    }
}

@Suppress("unused") private fun keepBool(r: Row) = r.boolOrNull("x")
