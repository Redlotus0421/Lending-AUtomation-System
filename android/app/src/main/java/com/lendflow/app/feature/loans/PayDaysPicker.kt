package com.lendflow.app.feature.loans

// Port of src/features/loans/PayDaysPicker.tsx — semi-monthly pay days, e.g. the borrower is paid on
// the 5th and 20th. Tap a common pair, or tap two days on a small calendar, then tap the first
// payment date. The server repeats the same date rules and computes the schedule.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.addDays
import com.lendflow.app.util.parseDate
import com.lendflow.app.util.todayManila
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

val PAY_DAY_PRESETS: List<List<Int>> = listOf(listOf(15, 30), listOf(5, 20), listOf(10, 25))
val DEFAULT_PAY_DAYS: List<Int> = listOf(15, 30)

fun ordinal(n: Int): String {
    val t = n % 100
    return "$n" + when {
        t in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
}

fun payDaysLabel(d: List<Int>): String = "${ordinal(d[0])} & ${ordinal(d[1])}"

/** Problem with a pair of days, or null when the server will accept it (same rule as compute_loan_schedule). */
fun payDaysError(d: List<Int>): String? {
    if (d.size != 2) return "Tap two days"
    val (a, b) = d.sorted()
    if (b - a < 7) return "The two pay days must be at least 7 days apart"
    if (b - a > 23) return "The two pay days must be at most 23 days apart"
    return null
}

internal fun isoDate(y: Int, m0: Int, d: Int): String = "%04d-%02d-%02d".format(y, m0 + 1, d)
internal fun lastDayOf(y: Int, m0: Int): Int = YearMonth.of(y, m0 + 1).lengthOfMonth()

/** The next [count] pay dates, starting with the first one at least 10 days after [from] (YYYY-MM-DD). */
fun nextPayDates(days: List<Int>, from: String = todayManila(), count: Int = 4): List<String> {
    val start = parseDate(from) ?: LocalDate.parse(todayManila())
    val y0 = start.year
    val m0 = start.monthValue
    val min = addDays(start.toString(), 10)
    val out = mutableListOf<String>()
    var i = 0
    while (out.size < count && i < 24) {
        val y = y0 + (m0 - 1 + i) / 12
        val m = (m0 - 1 + i) % 12
        for (d in days) {
            val date = isoDate(y, m, minOf(d, lastDayOf(y, m)))
            if (date >= min && out.size < count && date !in out) out += date
        }
        i++
    }
    return out
}

private val CHIP = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)
/** "Fri, Nov 27" */
fun fmtChip(v: String): String = parseDate(v)?.format(CHIP) ?: v

/** Grid of equal cells, [cols] per row. */
@Composable
internal fun <T> CellGrid(items: List<T>, cols: Int, gap: Int = 8, cell: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(gap.dp)) {
        items.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                row.forEach { cell(it, Modifier.weight(1f)) }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Big option button with the blue selected border (rounded-lg border-2). */
@Composable
internal fun ChoiceBox(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, height: Int = 48, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.height(height.dp).clip(shape).background(if (selected) LF.Blue50 else Color.White)
            .border(2.dp, if (selected) LF.Blue else LF.Slate200, shape).clickable(onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 1–31 day buttons in 7 columns. */
@Composable
internal fun DayGrid(isOn: (Int) -> Boolean, onTap: (Int) -> Unit) {
    CellGrid((1..31).toList(), 7, gap = 6) { d, m ->
        val on = isOn(d)
        Box(
            m.height(40.dp).clip(RoundedCornerShape(8.dp)).background(if (on) LF.Blue else LF.Slate100).clickable { onTap(d) },
            contentAlignment = Alignment.Center,
        ) { Text("$d", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) Color.White else LF.Slate700) }
    }
}

@Composable
internal fun PickerFrame(title: String, subtitle: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(LF.Slate50.copy(alpha = 0.6f)).border(1.dp, LF.Border, shape).padding(if (isCompact()) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp).padding(top = 1.dp), tint = LF.Blue)
                Text(title, Modifier.padding(start = 6.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Foreground)
            }
            Text(subtitle, fontSize = 12.sp, color = LF.MutedForeground)
        }
        content()
    }
}

@Composable
internal fun DateChoices(label: String, options: List<String>, selected: String?, onPick: (Int, String) -> Unit, earliestLabel: Boolean) {
    Column {
        Text(label, Modifier.padding(bottom = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        CellGrid(options.withIndex().toList(), if (isCompact()) 2 else 4) { (i, d), m ->
            val on = d == selected
            ChoiceBox(on, { onPick(i, d) }, m, height = 56) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (on) Icon(Icons.Default.Check, null, Modifier.size(14.dp).padding(end = 2.dp), tint = LF.Navy)
                        Text(fmtChip(d), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) LF.Navy else LF.Foreground, textAlign = TextAlign.Center)
                    }
                    if (earliestLabel && i == 0) Text("Earliest", fontSize = 10.sp, color = LF.MutedForeground)
                }
            }
        }
    }
}

/**
 * @param value the two pay days
 * @param releaseDate loan release date (YYYY-MM-DD); today when null
 * @param firstDue chosen first payment date, or null for the earliest one
 */
@Composable
fun PayDaysPicker(
    value: List<Int>,
    onChange: (List<Int>) -> Unit,
    firstDue: String?,
    onFirstDueChange: (String?) -> Unit,
    releaseDate: String? = null,
) {
    val isPreset = PAY_DAY_PRESETS.any { it[0] == value[0] && it[1] == value[1] }
    var custom by remember { mutableStateOf(!isPreset) }
    var picking by remember { mutableStateOf(if (isPreset) emptyList() else value.toList()) }
    val pickErr = if (picking.size == 2) payDaysError(picking) else null
    val options = nextPayDates(value, releaseDate?.ifBlank { null } ?: todayManila())
    val first = if (firstDue != null && firstDue in options) firstDue else options.firstOrNull()
    // a picked date that is no longer offered (other pay days or release date) goes back to the earliest
    val stale = firstDue != null && firstDue !in options
    LaunchedEffect(stale) { if (stale) onFirstDueChange(null) }

    fun choose(d: List<Int>) { onChange(d); onFirstDueChange(null) }
    fun tapDay(d: Int) {
        val next = if (d in picking) picking.filter { it != d } else if (picking.size >= 2) listOf(d) else picking + d
        picking = next
        if (next.size == 2 && payDaysError(next) == null) choose(next.sorted())
    }

    PickerFrame("Pay days — when does the borrower get paid?", "Payments fall due on these two days every month.") {
        val presetItems: List<List<Int>?> = PAY_DAY_PRESETS + listOf(null)
        CellGrid(presetItems, if (isCompact()) 2 else 4) { p, m ->
            if (p != null) {
                val on = !custom && p[0] == value[0] && p[1] == value[1]
                ChoiceBox(on, { custom = false; picking = emptyList(); choose(p) }, m) {
                    Text(payDaysLabel(p), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) LF.Navy else LF.Foreground)
                }
            } else {
                ChoiceBox(custom, { custom = true; picking = if (isPreset) emptyList() else value.toList() }, m) {
                    Text("Other days", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (custom) LF.Navy else LF.Foreground)
                }
            }
        }

        if (custom) {
            val shape = RoundedCornerShape(8.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(Color.White).border(1.dp, LF.Border, shape).padding(12.dp)) {
                Text(
                    when (picking.size) {
                        0 -> "Tap the first pay day"
                        1 -> "Now tap the second pay day (first: ${ordinal(picking[0])})"
                        else -> pickErr ?: "Every ${payDaysLabel(picking.sorted())}"
                    },
                    Modifier.padding(bottom = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.Slate600,
                )
                DayGrid({ it in picking }, ::tapDay)
                if (pickErr != null) Text("$pickErr. Tap a day to start again.", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = LF.Red600)
                Text("Days 29–31 fall on the last day of shorter months (e.g. February 28).", Modifier.padding(top = 8.dp), fontSize = 11.sp, color = LF.MutedForeground)
            }
        }

        DateChoices("First payment", options, first, { i, d -> onFirstDueChange(if (i == 0) null else d) }, earliestLabel = true)
    }
}
