package com.lendflow.app.util

import com.lendflow.app.data.Row
import com.lendflow.app.data.num
import com.lendflow.app.data.str
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

// Ports of src/lib/format.ts and supabase/functions/_shared/format.ts (₱ on screen, Asia/Manila).

val MANILA: ZoneId = ZoneId.of("Asia/Manila")
private val EN = Locale.US

private fun grouped(decimals: Int, minDecimals: Int = decimals): DecimalFormat {
    val pattern = buildString {
        append("#,##0")
        if (decimals > 0) {
            append('.')
            repeat(minDecimals) { append('0') }
            repeat(decimals - minDecimals) { append('#') }
        }
    }
    return DecimalFormat(pattern, DecimalFormatSymbols(EN)).apply { roundingMode = java.math.RoundingMode.HALF_UP }
}

fun toNumber(v: Any?): Double = when (v) {
    null -> 0.0
    is kotlinx.serialization.json.JsonNull -> 0.0
    is kotlinx.serialization.json.JsonPrimitive -> v.content.toDoubleOrNull() ?: 0.0
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull() ?: 0.0
    else -> v.toString().toDoubleOrNull() ?: 0.0
}

/** ₱1,234.50 — negative values as -₱1,234.50. compact: ₱1.2K / ₱3.4M */
fun peso(v: Any?, decimals: Int = 2, compact: Boolean = false): String {
    val n = toNumber(v)
    if (compact && abs(n) >= 1000) {
        val (div, suffix) = when {
            abs(n) >= 1e12 -> 1e12 to "T"
            abs(n) >= 1e9 -> 1e9 to "B"
            abs(n) >= 1e6 -> 1e6 to "M"
            else -> 1e3 to "K"
        }
        return "₱" + grouped(1, 0).format(n / div) + suffix
    }
    return (if (n < 0) "-₱" else "₱") + grouped(decimals).format(abs(n))
}

/** "PHP 50,000.00" (documents and thermal receipts). */
fun money(v: Any?, prefix: String = "PHP "): String {
    val n = toNumber(v)
    return (if (n < 0) "-" else "") + prefix + grouped(2).format(abs(n))
}

fun plainAmount(v: Any?): String = grouped(2).format(toNumber(v))

fun num(v: Any?, decimals: Int = 0): String = grouped(decimals).format(toNumber(v))

fun trimNumber(v: Any?, maxDecimals: Int = 4): String = grouped(maxDecimals, 0).format(toNumber(v))

fun pct(v: Any?, decimals: Int = 2): String {
    if (v == null || v is kotlinx.serialization.json.JsonNull || (v is String && v.isBlank()) ||
        (v is kotlinx.serialization.json.JsonPrimitive && v.content.isBlank())) return "—"
    return grouped(decimals, 0).format(toNumber(v)) + "%"
}

// ---------------------------------------------------------------------------
// dates
// ---------------------------------------------------------------------------
fun todayManila(): String = LocalDate.now(MANILA).toString()

fun parseDate(v: String?): LocalDate? = try {
    if (v.isNullOrBlank()) null else LocalDate.parse(v.take(10))
} catch (_: Exception) { null }

fun parseInstant(v: String?): ZonedDateTime? = try {
    if (v.isNullOrBlank()) null
    else OffsetDateTime.parse(v.replace(' ', 'T').let { if (Regex("[+-]\\d\\d$").containsMatchIn(it)) "$it:00" else it }).atZoneSameInstant(MANILA)
} catch (_: Exception) {
    try { Instant.parse(v).atZone(MANILA) } catch (_: Exception) { null }
}

private val SHORT = DateTimeFormatter.ofPattern("MMM d, yyyy", EN)
private val LONG = DateTimeFormatter.ofPattern("MMMM d, yyyy", EN)
private val NUMERIC = DateTimeFormatter.ofPattern("MM/dd/yyyy", EN)
private val DATETIME = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", EN)
private val TIME = DateTimeFormatter.ofPattern("h:mm a", EN)

/** Formats a YYYY-MM-DD business date or an ISO timestamp (shown in Manila time). style: short | long | numeric */
fun fmtDate(v: String?, style: String = "short"): String {
    if (v.isNullOrBlank()) return "—"
    val f = when (style) { "long" -> LONG; "numeric" -> NUMERIC; else -> SHORT }
    return if (v.length <= 10) parseDate(v)?.format(f) ?: v else parseInstant(v)?.format(f) ?: v
}

fun fmtDateTime(v: String?): String {
    if (v.isNullOrBlank()) return "—"
    return parseInstant(v)?.format(DATETIME) ?: v
}

fun fmtTime(v: String?): String = parseInstant(v)?.format(TIME) ?: ""

/** Long date used by documents: "January 5, 2026" */
fun longDate(v: String?): String = if (v.isNullOrBlank()) "" else fmtDate(v, "long")

fun timeAgo(v: String?): String {
    val t = parseInstant(v) ?: return ""
    val s = (System.currentTimeMillis() - t.toInstant().toEpochMilli()) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        s < 86400 * 7 -> "${s / 86400}d ago"
        else -> fmtDate(v)
    }
}

fun addDays(date: String, days: Int): String = (parseDate(date) ?: LocalDate.now(MANILA)).plusDays(days.toLong()).toString()
/** Clamps to the last day of the month, like the web helper. */
fun addMonths(date: String, months: Int): String = (parseDate(date) ?: LocalDate.now(MANILA)).plusMonths(months.toLong()).toString()
fun startOfMonth(date: String): String = "${date.take(7)}-01"
fun daysBetween(from: String, to: String): Long {
    val a = parseDate(from) ?: return 0
    val b = parseDate(to) ?: return 0
    return java.time.temporal.ChronoUnit.DAYS.between(a, b)
}

// ---------------------------------------------------------------------------
// text
// ---------------------------------------------------------------------------
/** +639171234567 -> 0917 123 4567 */
fun phMobile(v: String?): String {
    if (v.isNullOrBlank()) return "—"
    val m = Regex("^\\+63(9\\d{2})(\\d{3})(\\d{4})$").find(v) ?: return v
    return "0${m.groupValues[1]} ${m.groupValues[2]} ${m.groupValues[3]}"
}

fun normalizePhMobile(v: String): String {
    val d = v.filter { it.isDigit() }
    return when {
        Regex("^09\\d{9}$").matches(d) -> "+63${d.drop(1)}"
        Regex("^639\\d{9}$").matches(d) -> "+$d"
        Regex("^9\\d{9}$").matches(d) -> "+63$d"
        else -> v
    }
}

fun initials(name: String?): String {
    if (name.isNullOrBlank()) return "?"
    val parts = name.trim().split(Regex("\\s+"))
    return ((parts.firstOrNull()?.firstOrNull()?.toString() ?: "") + (if (parts.size > 1) parts.last().first().toString() else "")).uppercase()
}

fun titleCase(v: String?): String {
    if (v.isNullOrBlank()) return ""
    return v.replace('_', ' ').split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}

private val ONES = listOf("", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve",
    "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen")
private val TENS = listOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")
private val SCALES = listOf("", "Thousand", "Million", "Billion", "Trillion")

private fun chunkToWords(n: Int): String {
    val parts = mutableListOf<String>()
    val h = n / 100
    val r = n % 100
    if (h > 0) parts += "${ONES[h]} Hundred"
    if (r > 0) parts += if (r < 20) ONES[r] else TENS[r / 10] + (if (r % 10 > 0) "-${ONES[r % 10]}" else "")
    return parts.joinToString(" ")
}

fun integerToWords(value: Long): String {
    var n = abs(value)
    if (n == 0L) return "Zero"
    val words = mutableListOf<String>()
    var scale = 0
    while (n > 0) {
        val chunk = (n % 1000).toInt()
        if (chunk > 0) words.add(0, chunkToWords(chunk) + (if (SCALES[scale].isNotEmpty()) " ${SCALES[scale]}" else ""))
        n /= 1000
        scale++
    }
    return words.joinToString(" ")
}

/** "Fifty Thousand Pesos" / "One Thousand Two Hundred Pesos and 50/100" */
fun amountInWords(v: Any?): String {
    val n = (toNumber(v) * 100).roundToLong() / 100.0
    val pesos = floor(n).toLong()
    val centavos = ((n - pesos) * 100).roundToLong()
    val w = "${integerToWords(pesos)} Peso${if (pesos == 1L) "" else "s"}"
    return if (centavos > 0) "$w and ${centavos.toString().padStart(2, '0')}/100" else w
}

val FREQUENCY_LABEL = mapOf("daily" to "daily", "weekly" to "weekly", "semimonthly" to "semi-monthly", "monthly" to "monthly")
val PERIOD_LABEL = mapOf("daily" to "day", "weekly" to "week", "semimonthly" to "half-month", "monthly" to "month", "yearly" to "year")
val PERIOD_PLURAL = mapOf("daily" to "days", "weekly" to "weeks", "semimonthly" to "half-months", "monthly" to "months", "yearly" to "years")
val METHOD_LABEL = mapOf("flat" to "Flat interest", "diminishing" to "Diminishing balance")
val SIGNER_LABEL = mapOf("borrower" to "Borrower", "co_maker" to "Co-maker", "lender" to "Lender", "witness_1" to "Witness", "witness_2" to "Witness")

fun durationLabel(count: Int, frequency: String): String {
    if (frequency == "semimonthly") {
        val months = count / 2.0
        return "$count semi-monthly payment${if (count == 1) "" else "s"} (${trimNumber(months)} month${if (months == 1.0) "" else "s"})"
    }
    val unit = if (count == 1) PERIOD_LABEL[frequency] else PERIOD_PLURAL[frequency]
    return "$count ${unit ?: frequency}"
}

fun penaltyTerms(loan: Row): String {
    val enabled = loan["penalty_enabled"]?.toString() == "true"
    val type = loan.str("penalty_type")
    val rate = loan.num("penalty_rate")
    if (!enabled || type.isBlank() || rate <= 0) {
        return "No late payment penalty shall be charged under this Agreement. Unpaid amounts nevertheless remain due and demandable."
    }
    val grace = loan.num("penalty_grace_days").toInt()
    val graceText = if (grace > 0) "$grace-day grace period after the due date" else "due date"
    val r = trimNumber(rate)
    return when (type) {
        "fixed" -> "A one-time late payment fee of ${money(rate)} shall be charged for each installment that remains unpaid after the $graceText."
        "percent_once" -> "A one-time late payment charge of $r% of the unpaid installment amount shall be charged for each installment that remains unpaid after the $graceText."
        "percent_daily" -> "A late payment penalty of $r% per day of the unpaid installment amount shall accrue for each day of delay after the $graceText."
        "percent_monthly" -> "A late payment penalty of $r% per month of the unpaid installment amount, computed daily on a 30-day month, shall accrue for each day of delay after the $graceText."
        else -> "Late payment penalties apply as stated in the Disclosure Statement."
    }
}

/** Parses user-typed money ("1,234.50", "₱500") to a number, or null when empty/invalid. */
fun parseMoney(s: String?): Double? {
    if (s.isNullOrBlank()) return null
    return s.replace(Regex("[₱,\\s]"), "").replace("PHP", "", ignoreCase = true).toDoubleOrNull()
}

/** Rounds to centavos. */
fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0
