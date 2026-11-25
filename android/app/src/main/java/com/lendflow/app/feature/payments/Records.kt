package com.lendflow.app.feature.payments

import com.lendflow.app.data.Row
import com.lendflow.app.data.bool
import com.lendflow.app.data.num
import com.lendflow.app.data.obj
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.util.StatusInfo
import com.lendflow.app.util.Tone
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max

// Port of src/features/payments/records.ts: status and payment details of each scheduled installment
// for the Payment Records page.

/** paid_on_time | paid_late | overdue | partially_paid | upcoming | rescheduled | waived */
val RECORD_STATUS = mapOf(
    "paid_on_time" to StatusInfo("Paid on time", Tone.green),
    "paid_late" to StatusInfo("Paid late", Tone.amber),
    "overdue" to StatusInfo("Overdue", Tone.red),
    "partially_paid" to StatusInfo("Partially paid", Tone.blue),
    "upcoming" to StatusInfo("Upcoming", Tone.slate),
    "rescheduled" to StatusInfo("Rescheduled", Tone.violet),
    "waived" to StatusInfo("Waived", Tone.teal),
)

data class InstallmentPayment(val paymentId: String, val paymentNumber: String, val paymentDate: String, var amount: Double)

data class RecordRow(
    val inst: Row,
    val status: String,
    /** days paid after the due date (paid late) or days past due (overdue) */
    val days: Int,
    val scheduled: Double,
    val paid: Double,
    val remaining: Double,
    /** date the installment was fully paid, else the latest partial payment */
    val paidDate: String?,
    val payments: List<InstallmentPayment>,
    val rebate: Double,
)

private fun daysBetween(from: String, to: String): Int = try {
    ChronoUnit.DAYS.between(LocalDate.parse(from.take(10)), LocalDate.parse(to.take(10))).toInt()
} catch (_: Exception) { 0 }

/**
 * On time means fully paid on or before the due date (penalty grace days do not count as on time).
 * An unpaid or partly paid installment past its due date is overdue, even if something was paid.
 */
fun recordStatus(i: Row, today: String, lastPaid: String?): Pair<String, Int> {
    val status = i.str("status")
    val due = i.str("due_date")
    if (status == "rescheduled") return "rescheduled" to 0
    if (status == "waived") return "waived" to 0
    if (status == "paid") {
        val paid = i.strOrNull("paid_at") ?: lastPaid ?: due
        val late = daysBetween(due, paid)
        return if (late > 0) "paid_late" to late else "paid_on_time" to 0
    }
    if (due < today) return "overdue" to daysBetween(due, today)
    if (status == "partially_paid") return "partially_paid" to 0
    return "upcoming" to 0
}

/** One row per scheduled installment, with the posted payments applied to it (reversed payments excluded). */
fun buildRecordRows(installments: List<Row>, allocations: List<Row>, today: String): List<RecordRow> {
    val byInst = mutableMapOf<String, MutableList<InstallmentPayment>>()
    val rebates = mutableMapOf<String, Double>()
    for (a in allocations) {
        val lp = a.obj("loan_payments")
        if (a.bool("is_reversal") || lp.str("status") != "posted") continue
        val instId = a.str("installment_id")
        rebates[instId] = (rebates[instId] ?: 0.0) + a.num("interest_rebate")
        val amount = a.num("total_amount")
        if (amount <= 0) continue
        val list = byInst.getOrPut(instId) { mutableListOf() }
        val same = list.firstOrNull { it.paymentId == a.str("payment_id") }
        if (same != null) same.amount += amount
        else list += InstallmentPayment(a.str("payment_id"), lp.str("payment_number"), lp.str("payment_date"), amount)
    }
    return installments.map { inst ->
        val id = inst.str("id")
        val payments = byInst[id].orEmpty().sortedBy { it.paymentDate }
        val lastPaid = payments.lastOrNull()?.paymentDate
        val (status, days) = recordStatus(inst, today, lastPaid)
        RecordRow(
            inst = inst, status = status, days = days, payments = payments,
            scheduled = inst.num("scheduled_amount"),
            paid = inst.num("amount_paid"),
            remaining = if (inst.str("status") == "rescheduled") 0.0 else max(inst.num("amount_remaining"), 0.0),
            paidDate = if (inst.str("status") == "paid") (inst.strOrNull("paid_at") ?: lastPaid) else lastPaid,
            rebate = rebates[id] ?: 0.0,
        )
    }
}
