package com.lendflow.app.feature.loans

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.Row
import com.lendflow.app.data.SimplifiedService
import com.lendflow.app.data.has
import com.lendflow.app.data.num
import com.lendflow.app.data.objOrFirst
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.ui.components.Loadable
import com.lendflow.app.ui.components.LinkText
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Tone
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.peso

private val RENEWAL_STATUS = mapOf(
    "pending" to ("Settles at release" to Tone.amber),
    "settled" to ("Settled" to Tone.green),
    "cancelled" to ("Cancelled" to Tone.slate),
    "closed_before_release" to ("Already closed" to Tone.slate),
)

/** Renewal settlements in which this loan is the new loan or one of the settled previous loans. */
@Composable
fun rememberLoanRenewals(loanId: String): Loadable<List<Row>> = rememberLoad(loanId, "renewals") { SimplifiedService.renewals(loanId) }

/** Traceability of renewals: which previous loans this loan settles, or which renewal settled this loan. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RenewalPanel(loan: Row) {
    val router = LocalRouter.current
    val q = rememberLoanRenewals(loan.str("id"))
    val rows = q.data ?: emptyList()
    val id = loan.str("id")
    val asNew = rows.filter { it.str("new_loan_id") == id && it.str("status") != "cancelled" }
    val asOld = rows.filter { it.str("old_loan_id") == id && it.str("status") == "settled" }
    if (asNew.isEmpty() && asOld.isEmpty()) return

    val released = loan.has("released_at")
    val deduction = if (released) loan.num("renewal_deduction") else asNew.sumOf { it.num("quoted_amount") }
    val deductedCharges = if (loan.str("charges_treatment") == "deducted") loan.num("total_charges") else 0.0
    val wide = widthClass() == WidthClass.Expanded

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (asNew.isNotEmpty()) {
            Panel(
                title = "↻ Renewal — previous balance offset",
                description = if (released) "Previous loans were settled in full from this loan’s proceeds on release." else "The balance is recomputed and settled when this loan is released.",
            ) {
                val table: @Composable () -> Unit = {
                    LnGrid(listOf(LnCol("Previous loan", 130.dp), LnCol("Quoted", 120.dp, end = true), LnCol("Settled", 120.dp, end = true), LnCol("Status", 170.dp))) {
                        asNew.forEach { r ->
                            val st = RENEWAL_STATUS[r.str("status")] ?: (r.str("status") to Tone.slate)
                            LnRow(listOf(
                                LnCell(content = {
                                    LinkText(r.objOrFirst("old_loan").strOrNull("loan_number") ?: "Loan", { router.go("/loans/${r.str("old_loan_id")}") }, color = LF.Blue)
                                }),
                                LnCell(peso(r.num("quoted_amount")), small = "as of ${fmtDate(r.strOrNull("quoted_as_of"))}"),
                                if (r.has("settled_amount")) LnCell(peso(r.num("settled_amount")), small = fmtDate(r.strOrNull("settled_as_of"))) else LnCell("—"),
                                LnCell(content = {
                                    Column {
                                        Pill(st.first, st.second)
                                        if (r.has("settlement_payment_id")) {
                                            LinkText("View settlement payment", { router.go("/payments/${r.str("settlement_payment_id")}") }, color = LF.Blue, fontSize = 11.sp)
                                        }
                                    }
                                }),
                            ))
                        }
                    }
                }
                val breakdown: @Composable () -> Unit = {
                    RenewalNetRelease(
                        principal = loan.num("principal"), deductedCharges = deductedCharges, previousBalance = deduction,
                        previousLabel = if (released) "Less: previous balance settled" else "Less: previous balance (estimate)",
                        netRelease = loan.num("net_proceeds") - deduction,
                    )
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Box(Modifier.weight(1.4f)) { table() }
                        Box(Modifier.weight(1f)) { breakdown() }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { table(); breakdown() }
                }
            }
        }
        asOld.forEach { r ->
            val text = buildAnnotatedString {
                append("Settled in full by renewal loan ")
                withLink(LinkAnnotation.Clickable("loan", TextLinkStyles(SpanStyle(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline))) {
                    router.go("/loans/${r.str("new_loan_id")}")
                }) { append(r.objOrFirst("new_loan").str("loan_number")) }
                append(" on ${fmtDate(r.strOrNull("settled_as_of"))} — ${peso(r.num("settled_amount"))} offset against its proceeds")
                if (r.num("settled_interest_rebate") > 0) append(", ${peso(r.num("settled_interest_rebate"))} unearned interest rebated")
                append(".")
            }
            val shape = RoundedCornerShape(12.dp)
            FlowRow(
                Modifier.fillMaxWidth().clip(shape).background(LF.Green50).border(1.dp, LF.Green200, shape).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.Autorenew, null, Modifier.size(16.dp).align(Alignment.CenterVertically), tint = LF.Green800)
                Text(text, fontSize = 14.sp, color = LF.Green800)
                if (r.has("settlement_payment_id")) {
                    LinkText("Settlement payment", { router.go("/payments/${r.str("settlement_payment_id")}") }, color = LF.Green800, fontSize = 12.sp)
                }
            }
        }
    }
}

/** Line-by-line computation of the amount actually handed to the borrower (NetReleaseTable on the web). */
@Composable
private fun RenewalNetRelease(principal: Double, deductedCharges: Double, previousBalance: Double, previousLabel: String, netRelease: Double) {
    val rows = buildList {
        add(Triple("New loan principal", principal, false))
        if (deductedCharges > 0) add(Triple("Less: fees & charges deducted", deductedCharges, true))
        if (previousBalance > 0) add(Triple(previousLabel, previousBalance, true))
    }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, LF.Border, shape)) {
        rows.forEachIndexed { i, (label, amount, minus) ->
            if (i > 0) HorizontalDivider(color = LF.Border)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (minus) {
                    Icon(Icons.AutoMirrored.Filled.CallMade, null, Modifier.size(14.dp).rotate(90f), tint = LF.Red500)
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, Modifier.weight(1f), fontSize = 14.sp, color = LF.MutedForeground)
                Text((if (minus) "− " else "") + peso(amount), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (minus) LF.Red600 else LF.Foreground)
            }
        }
        Row(Modifier.fillMaxWidth().background(LF.Navy).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Net amount to release", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(peso(netRelease), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}
