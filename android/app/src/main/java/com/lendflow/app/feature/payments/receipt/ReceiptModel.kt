package com.lendflow.app.feature.payments.receipt

import com.lendflow.app.data.Row
import com.lendflow.app.data.has
import com.lendflow.app.data.num
import com.lendflow.app.data.obj
import com.lendflow.app.data.objs
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.fmtDateTime
import com.lendflow.app.util.fmtTime
import com.lendflow.app.util.peso
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToLong

// Port of src/features/payments/receipt/model.ts + the ESC/POS part of print.ts.
// Thermal receipt content, built from the frozen receipt snapshot (payment_receipts.data).
// Printing or reprinting only reads it: no payment or receipt is ever created here.

data class ReceiptCompany(val name: String, val address: String?, val phone: String?, val tin: String?)
data class ReceiptDue(val no: Int, val date: String)
data class ReceiptAtm(val withdrawal: Double, val payment: Double, val cashReturned: Double)

data class ReceiptModel(
    val company: ReceiptCompany,
    val receiptNo: String,
    val paymentNo: String,
    /** payment date (YYYY-MM-DD) and the time it was recorded */
    val date: String,
    val time: String?,
    val borrower: String,
    val borrowerCode: String?,
    val loanNo: String,
    val dueDates: List<ReceiptDue>,
    val method: String,
    val reference: String?,
    val amount: Double,
    val atm: ReceiptAtm?,
    val remaining: Double,
    val status: String,
    val receivedBy: String?,
    val footer: String?,
    val isVoid: Boolean,
    /** set when the receipt is printed again later */
    val reprintedAt: String?,
)

private val METHOD = mapOf(
    "cash" to "Cash", "bank_transfer" to "Bank transfer", "e_wallet" to "E-wallet", "check" to "Check", "other" to "Other", "renewal" to "Renewal offset",
)

/** `a || b` of the web: blank strings count as missing. */
private fun Row?.text(key: String): String? = strOrNull(key)?.ifBlank { null }

/** Payment status line: void, loan paid off, installment(s) paid, or a partial payment. */
fun receiptStatus(d: Row, isVoid: Boolean): String {
    if (isVoid) return "VOID - payment reversed"
    if (d.str("payment_type") == "settlement") return "Early settlement - loan paid"
    if (d.num("remaining_balance") <= 0) return "Loan fully paid"
    val inst = d.objs("installments")
    if (inst.isEmpty()) return "Posted"
    fun nos(list: List<Row>) = list.joinToString(", ") { "#${it.str("installment_no")}" }
    val partial = inst.filter { it.str("status") == "partially_paid" }
    if (partial.isNotEmpty()) return "Partial payment - installment ${nos(partial)}"
    if (inst.all { it.str("status") == "paid" }) return "Paid - installment${if (inst.size > 1) "s" else ""} ${nos(inst)}"
    return "Posted"
}

fun buildReceiptModel(d: Row, isVoid: Boolean = false, reprint: Boolean = false): ReceiptModel {
    val c = d.obj("company")
    val amount = d.num("amount")
    val atmW = if (d.has("atm_withdrawal")) d.num("atm_withdrawal") else null
    val recordedAt = d.text("recorded_at") ?: d.text("issued_at")
    return ReceiptModel(
        company = ReceiptCompany(c.text("legal_name") ?: c.text("name") ?: "", c.text("address"), c.text("phone"), c.text("tin")),
        receiptNo = d.str("receipt_number"),
        paymentNo = d.str("payment_number"),
        date = d.str("payment_date"),
        time = recordedAt?.let { fmtTime(it).ifBlank { null } },
        borrower = d.str("borrower_name"),
        borrowerCode = d.text("borrower_code"),
        loanNo = d.str("loan_number"),
        dueDates = d.objs("installments").map { ReceiptDue(it.num("installment_no").toInt(), it.str("due_date")) },
        method = METHOD[d.str("method")] ?: d.str("method"),
        reference = d.text("reference_no"),
        amount = amount,
        // cash returned is always withdrawal - payment, never typed in
        atm = atmW?.let { ReceiptAtm(it, amount, ((it - amount) * 100).roundToLong() / 100.0) },
        remaining = d.num("remaining_balance"),
        status = receiptStatus(d, isVoid),
        receivedBy = d.text("collector_name") ?: d.text("recorded_by"),
        footer = d.text("footer_note"),
        isVoid = isVoid,
        reprintedAt = if (reprint) fmtDateTime(Instant.now().toString()) else null,
    )
}

// ---------------------------------------------------------------------------
// Plain-text layout shared by the Bluetooth (ESC/POS) printout: 32 columns on 58mm, 48 on 80mm.
// ---------------------------------------------------------------------------
data class ReceiptLine(val text: String, val center: Boolean = false, val bold: Boolean = false, val big: Boolean = false)

fun columnsFor(paper: Int) = if (paper == 58) 32 else 48

private val COMBINING = Regex("[\\u0300-\\u036f]")
private val NON_ASCII = Regex("[^\\x20-\\x7E]")

/** ASCII only: thermal printers' default code page has no peso sign or curly punctuation. */
fun ascii(s: String): String {
    val t = s.replace(Regex("₱\\s?"), "P").replace(Regex("[–—]"), "-").replace(Regex("[“”]"), "\"").replace(Regex("[‘’]"), "'")
        .replace("…", "...").replace("ñ", "n").replace("Ñ", "N")
    return Normalizer.normalize(t, Normalizer.Form.NFD).replace(COMBINING, "").replace(NON_ASCII, "")
}

private fun moneyP(n: Double) = peso(n).replace(Regex("₱\\s?"), "P")

private fun wrap(s: String, width: Int): List<String> {
    val out = mutableListOf<String>()
    for (para in ascii(s).split('\n')) {
        var line = ""
        for (word in para.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            line = when {
                line.isEmpty() -> word
                line.length + 1 + word.length <= width -> "$line $word"
                else -> { out += line; word }
            }
            while (line.length > width) { out += line.substring(0, width); line = line.substring(width) }
        }
        out += line
    }
    return out
}

/** Label on the left, value on the right; wraps the label when both do not fit. */
fun lr(label: String, value: String, width: Int): List<String> {
    val l = ascii(label)
    val v = ascii(value)
    if (l.length + 1 + v.length <= width) return listOf(l + " ".repeat(width - l.length - v.length) + v)
    return wrap(l, width) + (" ".repeat(max(width - v.length, 0)) + v.takeLast(width))
}

fun receiptLines(m: ReceiptModel, paper: Int): List<ReceiptLine> {
    val w = columnsFor(paper)
    val rule = "-".repeat(w)
    val out = mutableListOf<ReceiptLine>()
    fun center(t: String, bold: Boolean = false, big: Boolean = false) =
        wrap(t, if (big) w / 2 else w).forEach { out += ReceiptLine(it, center = true, bold = bold, big = big) }
    fun row(l: String, v: String, bold: Boolean = false) = lr(l, v, w).forEach { out += ReceiptLine(it, bold = bold) }

    if (m.company.name.isNotEmpty()) center(m.company.name, bold = true)
    m.company.address?.let { center(it) }
    m.company.phone?.let { center(it) }
    m.company.tin?.let { center("TIN $it") }
    out += ReceiptLine(rule)
    center("PAYMENT RECEIPT", bold = true)
    if (m.isVoid) center("*** VOID ***", bold = true, big = true)
    m.reprintedAt?.let { center("REPRINT $it") }
    out += ReceiptLine(rule)
    row("Receipt No.", m.receiptNo, true)
    row("Payment No.", m.paymentNo)
    row("Date", "${fmtDate(m.date)}${m.time?.let { " $it" } ?: ""}")
    row("Borrower", m.borrower, true)
    m.borrowerCode?.let { row("Borrower code", it) }
    row("Loan", m.loanNo)
    for (d in m.dueDates) row("Due date #${d.no}", fmtDate(d.date))
    row("Method", m.method)
    m.reference?.let { row("Reference", it) }
    out += ReceiptLine(rule)
    val atm = m.atm
    if (atm != null) {
        row("ATM Withdrawal", moneyP(atm.withdrawal))
        row("Loan Payment", moneyP(atm.payment))
        out += ReceiptLine(rule)
        row("Cash Returned", moneyP(atm.cashReturned), true)
    } else {
        row("Amount Paid", moneyP(m.amount), true)
    }
    out += ReceiptLine(rule)
    row("Remaining Balance", moneyP(m.remaining), true)
    row("Status", m.status)
    m.receivedBy?.let { row("Received by", it) }
    m.footer?.let {
        out += ReceiptLine("")
        center(it)
    }
    out += ReceiptLine("")
    center("Thank you!")
    return out
}

// ---------------------------------------------------------------------------
// ESC/POS bytes (escposBytes in print.ts)
// ---------------------------------------------------------------------------
private const val ESC = 0x1b
private const val GS = 0x1d

fun escposBytes(m: ReceiptModel, paper: Int): ByteArray {
    val out = ByteArrayOutputStream()
    fun cmd(vararg b: Int) = b.forEach { out.write(it) }
    cmd(ESC, 0x40) // initialise
    for (l in receiptLines(m, paper)) {
        cmd(ESC, 0x61, if (l.center) 1 else 0)
        cmd(ESC, 0x45, if (l.bold) 1 else 0)
        cmd(GS, 0x21, if (l.big) 0x11 else 0x00)
        out.write(ascii(l.text).toByteArray(Charsets.US_ASCII))
        out.write(0x0a)
    }
    cmd(ESC, 0x61, 0); cmd(GS, 0x21, 0); cmd(ESC, 0x45, 0)
    cmd(ESC, 0x64, 4) // feed 4 lines so the tear-off clears the text
    cmd(GS, 0x56, 0x42, 0x00) // partial cut (ignored by printers without a cutter)
    return out.toByteArray()
}
