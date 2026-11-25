package com.lendflow.app.feature.loans

// Port of src/features/loans/CollectionDatePicker.tsx — collection day for monthly, weekly and daily
// loans, so the schedule can follow the borrower's payday (semi-monthly loans use PayDaysPicker).
// Staff tap the day the borrower is paid, then the first collection date; every later collection
// repeats from that date on the server (lf_due_date).

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.addDays
import com.lendflow.app.util.addMonths
import com.lendflow.app.util.parseDate
import com.lendflow.app.util.todayManila

private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val WEEKDAY_NAMES = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

private fun dayOf(d: String): Int = d.substring(8, 10).toInt()
/** 0 = Sunday … 6 = Saturday (getUTCDay) */
private fun weekdayOf(d: String): Int = (parseDate(d)?.dayOfWeek?.value ?: 7) % 7

/** The server's first due date when none is chosen (lf_default_first_due). */
fun defaultFirstDue(freq: String, release: String = todayManila()): String = when (freq) {
    "monthly" -> addMonths(release, 1)
    "weekly" -> addDays(release, 7)
    else -> addDays(release, 1)
}

/**
 * First collection dates to offer. Monthly: the next 3 months that have that day, starting at least a
 * week after release (a 31st is only offered in 31-day months; later months fall on the last day).
 * Weekly: the next 3 of that weekday. Daily: the next 4 days.
 */
fun collectionDates(freq: String, day: Int, release: String = todayManila()): List<String> {
    val out = mutableListOf<String>()
    when (freq) {
        "monthly" -> {
            if (day !in 1..31) return out
            val min = addDays(release, 7)
            val y0 = release.substring(0, 4).toInt()
            val m0 = release.substring(5, 7).toInt()
            var i = 0
            while (out.size < 3 && i < 24) {
                val y = y0 + (m0 - 1 + i) / 12
                val m = (m0 - 1 + i) % 12
                i++
                if (day > lastDayOf(y, m)) continue
                val d = isoDate(y, m, day)
                if (d >= min) out += d
            }
        }
        "weekly" -> {
            if (day !in 0..6) return out
            val start = addDays(release, 1)
            val d = addDays(start, (day - weekdayOf(start) + 7) % 7)
            for (i in 0 until 3) out += addDays(d, i * 7)
        }
        else -> for (i in 1..4) out += addDays(release, i)
    }
    return out
}

/** A saved first collection date that can still be used (after the release date), else null for the server default. */
fun validFirstDue(firstDue: String?, release: String = todayManila()): String? =
    if (!firstDue.isNullOrBlank() && firstDue > release) firstDue else null

/**
 * The caller remounts this picker when the frequency changes (`key(frequency) { … }`), so the chosen
 * day never carries over between monthly and weekly.
 * @param value chosen first collection date, or null for the server default
 * @param releaseDate loan release date (YYYY-MM-DD); today when null
 */
@Composable
fun CollectionDatePicker(frequency: String, value: String?, onChange: (String?) -> Unit, releaseDate: String? = null) {
    val freq = frequency
    val release = releaseDate?.ifBlank { null } ?: todayManila()
    val current = validFirstDue(value, release) ?: defaultFirstDue(freq, release)
    // "day" is a day of the month (monthly) or of the week (weekly)
    var day by remember { mutableIntStateOf(if (freq == "weekly") weekdayOf(current) else dayOf(current)) }

    val options = collectionDates(freq, day, release)
    val selected = if (current in options) current else null
    // the chosen date is no longer offered (other day, or a draft resumed later): use the first option
    LaunchedEffect(selected, options.joinToString()) {
        if (selected == null && options.isNotEmpty() && value != options[0]) onChange(options[0])
    }

    fun pickDay(d: Int) {
        day = d
        onChange(collectionDates(freq, d, release).firstOrNull())
    }

    val title = when (freq) {
        "monthly" -> "Collection day — which day of the month is the borrower paid?"
        "weekly" -> "Collection day — which day of the week is the borrower paid?"
        else -> "First collection — when do daily collections start?"
    }
    val summary = when (freq) {
        "monthly" -> "Collected every ${ordinal(day)} of the month"
        "weekly" -> "Collected every ${WEEKDAY_NAMES.getOrElse(day) { "" }}"
        else -> "Collected every day"
    }

    PickerFrame(title, "$summary. Match it to the borrower's payday.") {
        if (freq == "monthly") {
            val shape = RoundedCornerShape(8.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(Color.White).border(1.dp, LF.Border, shape).padding(12.dp)) {
                DayGrid({ it == day }, ::pickDay)
                if (day > 28) Text(
                    "In months without the ${ordinal(day)}, the payment falls on the last day of the month.",
                    Modifier.padding(top = 8.dp), fontSize = 11.sp, color = LF.MutedForeground,
                )
            }
        }
        if (freq == "weekly") {
            CellGrid(WEEKDAYS.withIndex().toList(), if (isCompact()) 4 else 7) { (i, w), m ->
                val on = i == day
                ChoiceBox(on, { pickDay(i) }, m, height = 44) {
                    Text(w, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) LF.Navy else LF.Foreground)
                }
            }
        }
        DateChoices("First collection", options, selected, { _, d -> onChange(d) }, earliestLabel = false)
    }
}
