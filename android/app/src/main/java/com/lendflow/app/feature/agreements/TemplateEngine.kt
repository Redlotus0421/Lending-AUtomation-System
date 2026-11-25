package com.lendflow.app.feature.agreements

import com.lendflow.app.data.Row
import com.lendflow.app.data.has
import com.lendflow.app.data.num
import com.lendflow.app.data.obj
import com.lendflow.app.data.objs
import com.lendflow.app.data.jsonOf
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.strings
import com.lendflow.app.data.numbers
import com.lendflow.app.util.FREQUENCY_LABEL
import com.lendflow.app.util.METHOD_LABEL
import com.lendflow.app.util.PERIOD_LABEL
import com.lendflow.app.util.amountInWords
import com.lendflow.app.util.durationLabel
import com.lendflow.app.util.longDate
import com.lendflow.app.util.money
import com.lendflow.app.util.parseDate
import com.lendflow.app.util.parseInstant
import com.lendflow.app.util.penaltyTerms
import com.lendflow.app.util.trimNumber
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

// Port of supabase/functions/_shared/template.ts — the agreement template engine shared by the PDF
// renderer (Edge Functions) and the template builder preview. Render data is kept as JSON rows
// (the shape returned by public.lf_agreement_render_data), like the web app.

data class Run(val text: String, val bold: Boolean)

sealed class Block {
    data class Heading(val level: Int, val runs: List<Run>) : Block()
    data class Paragraph(val runs: List<Run>) : Block()
    data class ListBlock(val ordered: Boolean, val items: List<List<Run>>) : Block()
    data object Rule : Block()
    data object Schedule : Block()
    data object Disclosure : Block()
    data object Signatures : Block()
}

val BLOCK_PLACEHOLDERS = listOf("repayment_schedule", "disclosure_table", "signature_block")

data class Placeholder(val key: String, val label: String, val group: String)

val PLACEHOLDERS: List<Placeholder> = listOf(
    Placeholder("agreement_number", "Agreement number", "Agreement"),
    Placeholder("agreement_date", "Agreement date", "Agreement"),
    Placeholder("loan_number", "Loan reference", "Agreement"),
    Placeholder("lender_name", "Lender name", "Lender"),
    Placeholder("lender_address", "Lender address", "Lender"),
    Placeholder("lender_city", "Lender city (venue)", "Lender"),
    Placeholder("lender_registration_no", "SEC registration / CA no.", "Lender"),
    Placeholder("lender_representative", "Lender representative", "Lender"),
    Placeholder("borrower_name", "Borrower name", "Borrower"),
    Placeholder("borrower_address", "Borrower address", "Borrower"),
    Placeholder("borrower_id_type", "Borrower ID type", "Borrower"),
    Placeholder("borrower_id_number", "Borrower ID number", "Borrower"),
    Placeholder("borrower_mobile", "Borrower mobile", "Borrower"),
    Placeholder("borrower_email", "Borrower email", "Borrower"),
    Placeholder("loan_amount", "Loan amount", "Loan terms"),
    Placeholder("loan_amount_words", "Loan amount in words", "Loan terms"),
    Placeholder("interest_rate", "Interest rate", "Loan terms"),
    Placeholder("interest_method", "Interest method", "Loan terms"),
    Placeholder("effective_interest_rate", "Effective interest rate", "Loan terms"),
    Placeholder("loan_duration", "Loan duration", "Loan terms"),
    Placeholder("payment_frequency", "Payment frequency", "Loan terms"),
    Placeholder("installment_count", "Number of installments", "Loan terms"),
    Placeholder("installment_amount", "Installment amount", "Loan terms"),
    Placeholder("total_interest", "Total interest", "Loan terms"),
    Placeholder("processing_fee", "Processing fee", "Loan terms"),
    Placeholder("total_charges", "Total fees & charges", "Loan terms"),
    Placeholder("net_proceeds", "Net proceeds", "Loan terms"),
    Placeholder("total_repayment", "Total repayment", "Loan terms"),
    Placeholder("release_date", "Release date", "Loan terms"),
    Placeholder("first_payment_date", "First payment date", "Loan terms"),
    Placeholder("last_payment_date", "Last payment date", "Loan terms"),
    Placeholder("penalty_terms", "Late payment terms", "Loan terms"),
    Placeholder("allocation_order", "Payment allocation order", "Loan terms"),
    Placeholder("co_maker_name", "Co-maker name", "Co-maker"),
    Placeholder("co_maker_relationship", "Co-maker relationship", "Co-maker"),
    Placeholder("co_maker_mobile", "Co-maker mobile", "Co-maker"),
    Placeholder("co_maker_address", "Co-maker address", "Co-maker"),
    Placeholder("transaction_type", "Transaction type (new loan / renewal)", "Renewal"),
    Placeholder("previous_loan_numbers", "Previous loan reference(s)", "Renewal"),
    Placeholder("previous_loan_balance", "Previous loan balance deducted", "Renewal"),
    Placeholder("net_release_amount", "Net amount released to borrower", "Renewal"),
    Placeholder("renewal_terms", "Renewal / offset clause", "Renewal"),
    Placeholder("repayment_schedule", "Repayment schedule (table)", "Blocks"),
    Placeholder("disclosure_table", "Disclosure statement (table)", "Blocks"),
    Placeholder("signature_block", "Signature section", "Blocks"),
)

// ----------------------------------------------------------------------------- formatting (_shared/format.ts)
private val SHORT_DOC = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US)

/** "Jan 05, 2026" (documents) */
fun shortDate(v: String?): String {
    if (v.isNullOrBlank()) return ""
    return if (v.length <= 10) parseDate(v)?.format(SHORT_DOC) ?: v else parseInstant(v)?.format(SHORT_DOC) ?: v
}

fun allocationOrderText(order: List<String>?): String {
    val labels = mapOf("penalty" to "penalties", "charges" to "charges", "interest" to "interest", "principal" to "principal")
    val o = (if (!order.isNullOrEmpty()) order else listOf("penalty", "charges", "interest", "principal")).map { labels[it] ?: it }
    return o.dropLast(1).joinToString(", ") + ", and finally " + o.last()
}

// ----------------------------------------------------------------------------- text helpers
private fun Row.n(key: String): Double = num(key)

/** Sentence describing the renewal offset (empty when the loan is not a renewal). */
fun renewalTermsText(d: Row): String {
    val r = d.obj("renewal") ?: return ""
    val loans = r.objs("loans")
    if (loans.isEmpty()) return ""
    val many = loans.size > 1
    val refs = loans.joinToString(", ") { "${it.str("loan_number")} (${money(it.n("amount"))})" }
    return "This loan renews the Borrower's previous loan${if (many) "s" else ""} $refs. The outstanding balance of " +
        "${money(r.n("total"))}, computed as of ${longDate(r.strOrNull("as_of"))}, is deducted from the net proceeds of this loan and applied as full " +
        "settlement of the previous loan${if (many) "s" else ""} on the release date. The net amount released to the Borrower " +
        "is ${money(r.n("net_release"))}. If the release takes place on a later date, the balance is recomputed as of the release date under " +
        "the Lender's early settlement rules and the net amount released is adjusted accordingly."
}

fun orgAddress(o: Row): String =
    listOf("address", "city", "province", "zip_code").mapNotNull { o.strOrNull(it) }.filter { it.isNotBlank() }.joinToString(", ")

fun interestRateText(s: Row): String {
    val rate = trimNumber(s.n("interest_rate"), 4)
    val rp = s.str("rate_period")
    val period = PERIOD_LABEL[rp] ?: rp
    val annual = s.n("annual_nominal_rate")
    return if (rp == "yearly") "$rate% per annum"
    else "$rate% per $period (${trimNumber(annual, 2)}% per annum, nominal)"
}

fun effectiveRateText(s: Row): String {
    if (!s.has("eir_monthly_pct")) return "Not applicable"
    return "${trimNumber(s.n("eir_monthly_pct"), 2)}% per month (${trimNumber(s.n("effective_annual_rate_pct"), 2)}% effective per annum)"
}

private fun ord(n: Int): String = "$n" + when {
    n % 100 in listOf(11, 12, 13) -> "th"
    n % 10 == 1 -> "st"
    n % 10 == 2 -> "nd"
    n % 10 == 3 -> "rd"
    else -> "th"
}

/** " (every 5th and 20th)" for semi-monthly loans with chosen pay days */
fun payDaysText(s: Row): String {
    val d = s["pay_days"] as? kotlinx.serialization.json.JsonArray ?: return ""
    if (d.size != 2) return ""
    val days = s.numbers("pay_days").map { it.toInt() }
    return " (every ${ord(days[0])} and ${ord(days[1])})"
}

/** String(n) in JS: "10", "2.5" */
private fun plain(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

private fun truthyNum(r: Row, key: String): Boolean = r.has(key) && r.n(key) != 0.0

fun buildPlaceholderValues(d: Row): Map<String, String> {
    val s = d.obj("summary") ?: jsonOf()
    val o = d.obj("organization") ?: jsonOf()
    val b = d.obj("borrower") ?: jsonOf()
    val loan = d.obj("loan") ?: jsonOf()
    val agreement = d.obj("agreement") ?: jsonOf()
    val coMaker = d.obj("co_maker")
    val renewal = d.obj("renewal")
    val renewalLoans = renewal.objs("loans")
    val freq = s.str("repayment_frequency")
    val count = s.n("installment_count")
    val countText = plain(count)
    fun or(v: String?, fallback: String) = v?.takeIf { it.isNotEmpty() } ?: fallback
    return mapOf(
        "agreement_number" to agreement.str("agreement_number"),
        "agreement_date" to longDate(agreement.strOrNull("generated_at")),
        "loan_number" to loan.str("loan_number"),
        "lender_name" to or(o.strOrNull("legal_name"), o.str("name")),
        "lender_address" to orgAddress(o).ifEmpty { "the Lender's principal office" },
        "lender_city" to or(o.strOrNull("city"), "the city where the Lender's principal office is located"),
        "lender_registration_no" to or(o.strOrNull("registration_no"), "—"),
        "lender_representative" to (d.obj("officer")?.strOrNull("full_name") ?: ""),
        "borrower_name" to b.str("full_name"),
        "borrower_address" to or(b.strOrNull("address_full"), "—"),
        "borrower_id_type" to or(b.strOrNull("id_type"), "a valid government-issued ID"),
        "borrower_id_number" to or(b.strOrNull("id_number"), "—"),
        "borrower_mobile" to or(b.strOrNull("mobile_number"), "—"),
        "borrower_email" to or(b.strOrNull("email"), "—"),
        "loan_amount" to money(s.n("principal")),
        "loan_amount_words" to amountInWords(s.n("principal")).uppercase(),
        "interest_rate" to interestRateText(s),
        "interest_method" to (METHOD_LABEL[s.str("interest_method")] ?: s.str("interest_method")).lowercase(),
        "effective_interest_rate" to effectiveRateText(s),
        "loan_duration" to if (truthyNum(s, "term_months")) {
            val tm = s.n("term_months")
            "${plain(tm)} month${if (tm == 1.0) "" else "s"} ($countText ${FREQUENCY_LABEL[freq] ?: freq} installment${if (count == 1.0) "" else "s"})"
        } else durationLabel(count.toInt(), freq),
        "payment_frequency" to (FREQUENCY_LABEL[freq] ?: freq) + payDaysText(s),
        "installment_count" to countText,
        "installment_amount" to money(s.n("installment_amount")),
        "total_interest" to money(s.n("total_interest")),
        "processing_fee" to money(s.n("processing_fee")),
        "total_charges" to money(s.n("total_charges")),
        "net_proceeds" to money(s.n("net_proceeds")),
        "total_repayment" to money(s.n("total_repayment")),
        "release_date" to longDate(s.strOrNull("release_date")),
        "first_payment_date" to longDate(s.strOrNull("first_due_date")),
        "last_payment_date" to longDate(s.strOrNull("last_due_date")),
        "penalty_terms" to penaltyTerms(loan),
        "allocation_order" to allocationOrderText(loan.strings("allocation_order")),
        "co_maker_name" to or(coMaker?.strOrNull("full_name"), "—"),
        "co_maker_relationship" to or(coMaker?.strOrNull("relationship"), "—"),
        "co_maker_mobile" to or(coMaker?.strOrNull("mobile_number"), "—"),
        "co_maker_address" to or(coMaker?.strOrNull("address"), "—"),
        "transaction_type" to if (renewalLoans.isNotEmpty() ||
            (loan.str("lending_mode") == "simplified" && Regex("renewal", RegexOption.IGNORE_CASE).containsMatchIn(loan.str("purpose")))
        ) "Loan renewal" else "New loan",
        "previous_loan_numbers" to renewalLoans.joinToString(", ") { it.str("loan_number") }.ifEmpty { "Not applicable" },
        "previous_loan_balance" to if (renewal != null) money(renewal.n("total")) else money(0.0),
        "net_release_amount" to money(if (renewal != null) renewal.n("net_release") else s.n("net_proceeds")),
        "renewal_terms" to renewalTermsText(d).ifEmpty { "Not applicable." },
    )
}

/** Disclosure statement rows (label, value) — Truth in Lending style. */
fun disclosureRows(d: Row): List<Pair<String, String>> {
    val s = d.obj("summary") ?: jsonOf()
    val loan = d.obj("loan") ?: jsonOf()
    val deducted = s.str("charges_treatment") == "deducted"
    val renewal = d.obj("renewal")
    val rl = renewal.objs("loans")
    val freq = s.str("repayment_frequency")
    val out = mutableListOf(
        "Principal amount of the loan" to money(s.n("principal")),
        "Fees and charges (${if (deducted) "deducted from proceeds" else "added to installments"})" to money(s.n("total_charges")),
        "Net proceeds to the Borrower" to money(s.n("net_proceeds")),
    )
    if (renewal != null && rl.isNotEmpty()) {
        out += "Less: outstanding balance of previous loan${if (rl.size > 1) "s" else ""} ${rl.joinToString(", ") { it.str("loan_number") }} (as of ${shortDate(renewal.strOrNull("as_of"))})" to money(renewal.n("total"))
        out += "Net amount released to the Borrower" to money(renewal.n("net_release"))
    }
    out += "Total interest" to money(s.n("total_interest"))
    out += "Finance charge (interest + fees and charges)" to money(s.n("finance_charge"))
    out += "Total amount payable" to money(s.n("total_repayment"))
    out += "Nominal interest rate" to interestRateText(s)
    out += "Interest computation method" to (METHOD_LABEL[s.str("interest_method")] ?: s.str("interest_method"))
    out += "Effective interest rate (EIR)" to effectiveRateText(s)
    if (truthyNum(s, "term_months")) {
        val tm = s.n("term_months")
        out += "Repayment period" to "${plain(tm)} month${if (tm == 1.0) "" else "s"}"
    }
    out += "Number and frequency of installments" to "${plain(s.n("installment_count"))} ${FREQUENCY_LABEL[freq] ?: ""} installments${payDaysText(s)}"
    out += "Regular installment amount" to money(s.n("installment_amount")) +
        (if (s.n("last_installment_amount") != s.n("installment_amount")) " (final: ${money(s.n("last_installment_amount"))})" else "")
    out += "First / last due date" to "${shortDate(s.strOrNull("first_due_date"))} / ${shortDate(s.strOrNull("last_due_date"))}"
    out += "Late payment charges" to penaltyTerms(loan)
    return out
}

data class Filled(val text: String, val missing: List<String>)

private val PH_RE = Regex("\\{\\{\\s*([a-z0-9_]+)\\s*\\}\\}", RegexOption.IGNORE_CASE)

/** Replace inline placeholders. Block placeholders are preserved for the layout stage. */
fun fillPlaceholders(body: String, values: Map<String, String>): Filled {
    val missing = LinkedHashSet<String>()
    val text = PH_RE.replace(body) { m ->
        val k = m.groupValues[1].lowercase()
        when {
            k in BLOCK_PLACEHOLDERS -> "{{$k}}"
            k in values -> values.getValue(k)
            else -> { missing += k; m.value }
        }
    }
    return Filled(text, missing.toList())
}

fun parseInline(text: String): List<Run> {
    val runs = mutableListOf<Run>()
    text.split("**").forEachIndexed { i, p -> if (p.isNotEmpty()) runs += Run(p, i % 2 == 1) }
    return runs.ifEmpty { listOf(Run("", false)) }
}

private val BLOCK_LINE = Regex("^\\{\\{\\s*(repayment_schedule|disclosure_table|signature_block)\\s*\\}\\}$", RegexOption.IGNORE_CASE)
private val HEADING_LINE = Regex("^(#{1,3})\\s+(.*)$")
private val RULE_LINE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
private val OL_LINE = Regex("^(\\d+)[.)]\\s+(.*)$")
private val UL_LINE = Regex("^[-*•]\\s+(.*)$")
private val WS = Regex("\\s+")

fun parseTemplate(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val lines = text.replace(Regex("\\r\\n?"), "\n").split("\n")
    val para = mutableListOf<String>()
    var listOrdered: Boolean? = null
    var listItems = mutableListOf<List<Run>>()

    fun flushPara() {
        if (para.isNotEmpty()) {
            blocks += Block.Paragraph(parseInline(para.joinToString(" ").replace(WS, " ").trim()))
            para.clear()
        }
    }
    fun flushList() {
        if (listOrdered != null) {
            blocks += Block.ListBlock(listOrdered!!, listItems)
            listOrdered = null
            listItems = mutableListOf()
        }
    }

    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty()) { flushPara(); flushList(); continue }
        val block = BLOCK_LINE.find(line)
        if (block != null) {
            flushPara(); flushList()
            blocks += when (block.groupValues[1].lowercase()) {
                "repayment_schedule" -> Block.Schedule
                "disclosure_table" -> Block.Disclosure
                else -> Block.Signatures
            }
            continue
        }
        val h = HEADING_LINE.find(line)
        if (h != null) {
            flushPara(); flushList()
            blocks += Block.Heading(h.groupValues[1].length, parseInline(h.groupValues[2]))
            continue
        }
        if (RULE_LINE.matches(line)) {
            flushPara(); flushList()
            blocks += Block.Rule
            continue
        }
        val ol = OL_LINE.find(line)
        val ul = if (ol == null) UL_LINE.find(line) else null
        if (ol != null || ul != null) {
            flushPara()
            val ordered = ol != null
            if (listOrdered == null || listOrdered != ordered) {
                flushList()
                listOrdered = ordered
            }
            listItems.add(parseInline((if (ol != null) ol.groupValues[2] else ul!!.groupValues[1]).trim()))
            continue
        }
        if (listOrdered != null) flushList()
        para += line
    }
    flushPara()
    flushList()
    if (blocks.none { it is Block.Signatures }) blocks += Block.Signatures
    return blocks
}

/** Sample data for template previews in the builder. */
fun sampleRenderData(org: Row? = null): Row {
    val schedule = (0 until 10).map { i ->
        mapOf(
            "installment_no" to i + 1,
            "due_date" to "2026-${(i + 2).toString().padStart(2, '0')}-15",
            "opening_balance" to 50000 - i * 5000,
            "principal" to 5000,
            "interest" to 1000,
            "charges" to 0,
            "total" to 6000,
            "closing_balance" to 45000 - i * 5000,
        )
    }
    return jsonOf(
        "agreement" to mapOf(
            "id" to "sample", "agreement_number" to "LA-2026-000123", "version" to 1, "kind" to "original",
            "generated_at" to Instant.now().toString(), "required_signers" to listOf("borrower", "co_maker", "lender"), "signing_order" to "borrower_first",
        ),
        "organization" to (org ?: mapOf("name" to "Your Lending Company", "address" to "123 Rizal Avenue", "city" to "Quezon City", "province" to "Metro Manila", "zip_code" to "1100")),
        "borrower" to mapOf(
            "full_name" to "Juan Santos Dela Cruz", "address_full" to "45 Mabini St., Brgy. San Roque, Marikina City, Metro Manila 1801",
            "id_type" to "PhilSys National ID", "id_number" to "1234-5678-9012-3456", "mobile_number" to "+639171234567", "email" to "juan@example.com",
        ),
        "loan" to mapOf(
            "loan_number" to "LN-2026-000123", "penalty_enabled" to true, "penalty_type" to "percent_monthly", "penalty_rate" to 3,
            "penalty_grace_days" to 3, "allocation_order" to listOf("penalty", "charges", "interest", "principal"),
        ),
        "summary" to mapOf(
            "principal" to 50000, "interest_method" to "flat", "interest_rate" to 2, "rate_period" to "monthly", "annual_nominal_rate" to 24,
            "repayment_frequency" to "monthly", "installment_count" to 10, "installment_amount" to 6000, "last_installment_amount" to 6000,
            "total_interest" to 10000, "processing_fee" to 0, "total_charges" to 0, "charges_treatment" to "deducted", "net_proceeds" to 50000,
            "total_repayment" to 60000, "finance_charge" to 10000, "first_due_date" to "2026-02-15", "last_due_date" to "2026-11-15", "release_date" to "2026-01-15",
            "eir_monthly_pct" to 3.46, "effective_annual_rate_pct" to 50.54,
        ),
        "schedule" to schedule,
        "template" to mapOf("title" to "Loan Agreement", "body" to "", "settings" to emptyMap<String, Any?>()),
        "officer" to mapOf("full_name" to "Maria Santos"),
        "co_maker" to mapOf(
            "full_name" to "Rosa Reyes Dela Cruz", "relationship" to "Spouse", "mobile_number" to "+639181234567",
            "address" to "45 Mabini St., Brgy. San Roque, Marikina City",
        ),
    )
}
