package com.lendflow.app.feature.loans

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.data.Row
import com.lendflow.app.data.has
import com.lendflow.app.data.int
import com.lendflow.app.data.num
import com.lendflow.app.data.numOrNull
import com.lendflow.app.data.obj
import com.lendflow.app.data.objs
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.strings
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.FREQUENCY_LABEL
import com.lendflow.app.util.METHOD_LABEL
import com.lendflow.app.util.PERIOD_LABEL
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.num
import com.lendflow.app.util.pct
import com.lendflow.app.util.peso
import com.lendflow.app.util.trimNumber
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** JSON primitives → their text, so the number formatters accept values read straight from a row. */
private fun plain(v: Any?): Any? = when (v) {
    is JsonNull -> null
    is JsonPrimitive -> v.content
    else -> v
}

fun rateLabel(rate: Any?, period: String): String = "${trimNumber(plain(rate), 4)}% per ${PERIOD_LABEL[period] ?: period}"

private data class SummaryItem(val label: String, val value: String, val hint: String? = null)

/** calc = preview_loan / LoanCalculation result (summary, schedule, disclosures, warnings) */
@Composable
fun SummaryGrid(calc: Row, compact: Boolean = false) {
    val s = calc.obj("summary")
    val freq = s.str("repayment_frequency")
    val items = listOf(
        SummaryItem("Principal", peso(s.num("principal"))),
        SummaryItem("Interest method", METHOD_LABEL[s.str("interest_method")] ?: s.str("interest_method")),
        SummaryItem("Interest rate", rateLabel(s.num("interest_rate"), s.str("rate_period")), "${trimNumber(s.num("annual_nominal_rate"), 2)}% p.a. nominal"),
        SummaryItem("Duration", s.str("duration_label"), "${FREQUENCY_LABEL[freq] ?: freq} payments"),
        SummaryItem("Installments", num(s.num("installment_count"))),
        SummaryItem(
            "Installment amount", peso(s.num("installment_amount")),
            if (s.num("last_installment_amount") != s.num("installment_amount")) "Final: ${peso(s.num("last_installment_amount"))}" else null,
        ),
        SummaryItem("Total interest", peso(s.num("total_interest"))),
        SummaryItem("Total charges", peso(s.num("total_charges")), if (s.str("charges_treatment") == "added") "Added to installments" else "Deducted from proceeds"),
        SummaryItem("Total repayment", peso(s.num("total_repayment"))),
        SummaryItem("Net proceeds", peso(s.num("net_proceeds"))),
        SummaryItem("First due date", fmtDate(s.strOrNull("first_due_date"))),
        SummaryItem("Last due date", fmtDate(s.strOrNull("last_due_date"))),
        SummaryItem(
            "Effective rate (EIR)",
            if (s.has("eir_monthly_pct")) "${pct(s.numOrNull("eir_monthly_pct"))} / month" else "—",
            if (s.has("effective_annual_rate_pct")) "${pct(s.numOrNull("effective_annual_rate_pct"))} effective p.a." else null,
        ),
        SummaryItem("Rate per period", "${trimNumber(s.num("periodic_rate_pct"), 6)}%", "per ${PERIOD_LABEL[freq]} (nominal)"),
    )
    val cols = if (compact) 2 else when (widthClass()) {
        WidthClass.Compact -> 2
        WidthClass.Medium -> 3
        WidthClass.Expanded -> 4
    }
    LnEqualGrid(items.size, cols, hGap = 16.dp, vGap = 12.dp) { i ->
        val it = items[i]
        Column {
            Text(it.label, fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(it.value, Modifier.padding(top = 2.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LF.Foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (it.hint != null) Text(it.hint, fontSize = 11.sp, color = LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun HeadlineFigures(calc: Row) {
    val s = calc.obj("summary")
    val freq = s.str("repayment_frequency")
    val faint = Color.White.copy(alpha = 0.6f)
    val big = if (widthClass() == WidthClass.Compact) 18.sp else 20.sp
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LF.Navy).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("INSTALLMENT", fontSize = 11.sp, color = faint, letterSpacing = 0.4.sp)
            Text(peso(s.num("installment_amount")), fontSize = big, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
            Text("× ${s.int("installment_count")} ${FREQUENCY_LABEL[freq] ?: freq}", fontSize = 11.sp, color = faint)
        }
        Column(Modifier.weight(1f)) {
            Text("TOTAL INTEREST", fontSize = 11.sp, color = faint, letterSpacing = 0.4.sp)
            Text(peso(s.num("total_interest")), fontSize = big, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
            Text(METHOD_LABEL[s.str("interest_method")] ?: s.str("interest_method"), fontSize = 11.sp, color = faint)
        }
        Column(Modifier.weight(1f)) {
            Text("TOTAL REPAYMENT", fontSize = 11.sp, color = faint, letterSpacing = 0.4.sp)
            Text(peso(s.num("total_repayment")), fontSize = big, fontWeight = FontWeight.SemiBold, color = LF.Teal, maxLines = 1)
            Text("Net proceeds ${peso(s.num("net_proceeds"), compact = true)}", fontSize = 11.sp, color = faint)
        }
    }
}

/** rows = calc.schedule */
@Composable
fun ScheduleTable(rows: List<Row>, maxHeight: Dp? = null) {
    val tp = rows.sumOf { it.num("principal") }
    val ti = rows.sumOf { it.num("interest") }
    val tc = rows.sumOf { it.num("charges") }
    val tt = rows.sumOf { it.num("total") }
    val showCharges = tc > 0
    val cols = buildList {
        add(LnCol("#", 48.dp))
        add(LnCol("Due date", 110.dp))
        add(LnCol("Opening balance", 120.dp, end = true))
        add(LnCol("Principal", 100.dp, end = true))
        add(LnCol("Interest", 90.dp, end = true))
        if (showCharges) add(LnCol("Charges", 90.dp, end = true))
        add(LnCol("Total payment", 110.dp, end = true))
        add(LnCol("Closing balance", 120.dp, end = true))
    }
    LnGrid(
        cols, maxHeight = maxHeight,
        footer = {
            LnRow(
                buildList {
                    add(LnCell("Total", bold = true))
                    add(LnCell(""))
                    add(LnCell(""))
                    add(LnCell(num(tp, 2), bold = true))
                    add(LnCell(num(ti, 2), bold = true))
                    if (showCharges) add(LnCell(num(tc, 2), bold = true))
                    add(LnCell(num(tt, 2), bold = true))
                    add(LnCell(""))
                },
                bg = LF.Slate50,
            )
        },
    ) {
        rows.forEach { r ->
            LnRow(
                buildList {
                    add(LnCell(r.str("installment_no"), color = LF.MutedForeground))
                    add(LnCell(fmtDate(r.strOrNull("due_date"))))
                    add(LnCell(num(r.num("opening_balance"), 2)))
                    add(LnCell(num(r.num("principal"), 2)))
                    add(LnCell(num(r.num("interest"), 2)))
                    if (showCharges) add(LnCell(num(r.num("charges"), 2)))
                    add(LnCell(num(r.num("total"), 2), bold = true))
                    add(LnCell(num(r.num("closing_balance"), 2)))
                },
                vPad = 6.dp,
            )
        }
    }
}

@Composable
fun Disclosures(calc: Row) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        calc.strings("warnings").forEach { w ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Amber50).padding(12.dp)) {
                Icon(Icons.Default.Warning, null, Modifier.padding(top = 1.dp).size(14.dp), tint = LF.Amber800)
                Spacer(Modifier.width(8.dp))
                Text(w, fontSize = 12.sp, color = LF.Amber800)
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Slate50).padding(12.dp)) {
            Row(Modifier.padding(bottom = 4.dp)) {
                Icon(Icons.Default.Info, null, Modifier.padding(top = 1.dp).size(14.dp), tint = LF.Slate700)
                Spacer(Modifier.width(6.dp))
                Text("Rate disclosure", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LF.Foreground)
            }
            calc.strings("disclosures").forEach { d ->
                Row(Modifier.padding(start = 8.dp, top = 2.dp)) {
                    Text("•", fontSize = 12.sp, color = LF.Slate700)
                    Spacer(Modifier.width(8.dp))
                    Text(d, fontSize = 12.sp, color = LF.Slate700)
                }
            }
        }
    }
}
