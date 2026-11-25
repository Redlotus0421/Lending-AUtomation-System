package com.lendflow.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.data.primitiveString
import com.lendflow.app.data.readable
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.ExportCol
import com.lendflow.app.util.MANILA
import com.lendflow.app.util.Opt
import com.lendflow.app.util.addDays
import com.lendflow.app.util.addMonths
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.num
import com.lendflow.app.util.parseDate
import com.lendflow.app.util.peso
import com.lendflow.app.util.startOfMonth
import com.lendflow.app.util.todayManila
import com.lendflow.app.util.toNumber
import com.lendflow.app.util.trimNumber
import kotlinx.coroutines.launch

// Port of src/features/reports/shared.tsx (dashboard, reports and payments pages).

val PRESET_LABEL = linkedMapOf(
    "today" to "Today", "this_week" to "This week", "this_month" to "This month", "last_month" to "Last month",
    "last_3" to "Last 3 months", "last_6" to "Last 6 months", "last_12" to "Last 12 months", "ytd" to "Year to date", "custom" to "Custom range",
)

data class DateRange(val preset: String, val from: String, val to: String)

fun presetRange(preset: String, today: String = todayManila()): Pair<String, String> = when (preset) {
    "today" -> today to today
    "this_week" -> {
        val dow = (parseDate(today)!!.dayOfWeek.value + 6) % 7 // Monday start
        addDays(today, -dow) to today
    }
    "this_month" -> startOfMonth(today) to today
    "last_month" -> addMonths(startOfMonth(today), -1) to addDays(startOfMonth(today), -1)
    "last_3" -> addMonths(startOfMonth(today), -2) to today
    "last_6" -> addMonths(startOfMonth(today), -5) to today
    "last_12" -> addMonths(startOfMonth(today), -11) to today
    "ytd" -> "${today.take(4)}-01-01" to today
    else -> startOfMonth(today) to today
}

fun makeRange(preset: String): DateRange = presetRange(preset).let { DateRange(preset, it.first, it.second) }

@Composable
fun DateRangeFilter(
    value: DateRange,
    onChange: (DateRange) -> Unit,
    modifier: Modifier = Modifier,
    presets: List<String> = listOf("this_month", "last_month", "last_3", "last_6", "last_12", "ytd", "custom"),
) {
    val today = todayManila()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectField(value.preset, presets.map { Opt(it, PRESET_LABEL[it] ?: it) }, { p ->
            onChange(if (p == "custom") value.copy(preset = p) else makeRange(p))
        })
        if (value.preset == "custom") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DateField(value.from, { if (it.isNotBlank()) onChange(value.copy(from = it)) }, Modifier.weight(1f), maxDate = value.to.ifBlank { today })
                Text("to", fontSize = 12.sp, color = LF.MutedForeground)
                DateField(value.to, { if (it.isNotBlank()) onChange(value.copy(to = it)) }, Modifier.weight(1f), minDate = value.from, maxDate = today)
            }
        } else {
            Text("${fmtDate(value.from)} – ${fmtDate(value.to)}", fontSize = 12.sp, color = LF.MutedForeground)
        }
    }
}

/** Export button with PDF / Excel / CSV / Print choices. Handlers may suspend. */
@Composable
fun ExportMenu(
    onPdf: (suspend () -> Unit)? = null,
    onXlsx: (suspend () -> Unit)? = null,
    onCsv: (suspend () -> Unit)? = null,
    onPrint: (suspend () -> Unit)? = null,
    disabled: Boolean = false,
    label: String = "Export",
    small: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(fn: (suspend () -> Unit)?) {
        open = false
        if (fn == null) return
        busy = true
        scope.launch {
            try { fn() } catch (e: Exception) { Toasts.error(e.readable().ifBlank { "Export failed" }) } finally { busy = false }
        }
    }
    Box {
        Btn(label, { open = true }, variant = BtnVariant.Outline, icon = Icons.Default.Download, loading = busy, enabled = !disabled, small = small)
        DropdownMenu(open, { open = false }, modifier = Modifier.background(Color.White).width(200.dp)) {
            Text("Download as", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, color = LF.MutedForeground)
            if (onPdf != null) DropdownMenuItem({ Text("PDF") }, { run(onPdf) }, leadingIcon = { Icon(Icons.Default.Description, null) })
            if (onXlsx != null) DropdownMenuItem({ Text("Excel (.xlsx)") }, { run(onXlsx) }, leadingIcon = { Icon(Icons.Default.GridOn, null) })
            if (onCsv != null) DropdownMenuItem({ Text("CSV") }, { run(onCsv) }, leadingIcon = { Icon(Icons.Default.TableChart, null) })
            if (onPrint != null) {
                HorizontalDivider(color = LF.Border)
                DropdownMenuItem({ Text("Print") }, { run(onPrint) }, leadingIcon = { Icon(Icons.Default.Print, null) })
            }
        }
    }
}


/** Screen formatting for a report cell (formatReportCell on the web); loan/payment numbers link to their pages. */
@Composable
fun ReportCell(col: ExportCol, value: String?, row: com.lendflow.app.data.Row? = null, onOpen: ((String) -> Unit)? = null) {
    if (value.isNullOrEmpty()) {
        Text(if (col.type == "money") peso(0) else "—", fontSize = 14.sp, color = LF.MutedForeground)
        return
    }
    when (col.type) {
        "money" -> Text(peso(value), fontSize = 14.sp)
        "number" -> Text(num(value, if (toNumber(value) % 1.0 == 0.0) 0 else 2), fontSize = 14.sp)
        "percent" -> Text("${trimNumber(value, 1)}%", fontSize = 14.sp)
        "date" -> Text(fmtDate(value), fontSize = 14.sp)
        "status" -> LoanStatusBadge(value)
        else -> {
            val loanId = row?.get("loan_id").primitiveString()
            val paymentId = row?.get("payment_id").primitiveString()
            when {
                col.key == "loan_number" && loanId != null && onOpen != null -> LinkText(value, { onOpen("/loans/$loanId") })
                col.key == "payment_number" && paymentId != null && onOpen != null -> LinkText(value, { onOpen("/payments/$paymentId") })
                else -> Text(value, fontSize = 14.sp)
            }
        }
    }
}

/** Opens the dialer. */
fun callNumber(context: Context, mobile: String?) {
    if (mobile.isNullOrBlank()) return
    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$mobile")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Bilingual (English/Filipino) payment reminder text, same wording as the web. */
fun reminderText(name: String, amount: Any?, dueDate: String, loanNumber: String, lender: String?, overdue: Boolean): String {
    val first = name.split(' ').firstOrNull() ?: name
    val whenText = fmtDate(dueDate)
    val amt = peso(amount)
    val who = lender ?: "your lender"
    return if (overdue) "Hi $first, this is $who. Your payment of $amt for loan $loanNumber was due on $whenText and is now overdue. Paki-settle po sa lalong madaling panahon. Salamat po!"
    else "Hi $first, this is $who. Friendly reminder: your payment of $amt for loan $loanNumber is due on $whenText. Paalala po na bayaran sa takdang araw. Salamat po!"
}

/** Opens the SMS app with the reminder pre-filled. */
fun sendSmsReminder(context: Context, mobile: String?, name: String, amount: Any?, dueDate: String, loanNumber: String, lender: String?, overdue: Boolean) {
    if (mobile.isNullOrBlank()) { Toasts.error("No mobile number on file."); return }
    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$mobile")).putExtra("sms_body", reminderText(name, amount, dueDate, loanNumber, lender, overdue))
    try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { Toasts.error("No SMS app found.") }
}

@Suppress("unused") private val keepZone = MANILA
