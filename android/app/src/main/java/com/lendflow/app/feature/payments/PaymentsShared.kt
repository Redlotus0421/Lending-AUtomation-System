package com.lendflow.app.feature.payments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.data.Row
import com.lendflow.app.data.UsersService
import com.lendflow.app.data.bool
import com.lendflow.app.data.objOrFirst
import com.lendflow.app.data.str
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.sendSmsReminder
import com.lendflow.app.ui.components.callNumber
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Opt
import com.lendflow.app.util.Tone
import com.lendflow.app.util.phMobile

// Small helpers shared by the payments & collections screens.

/** payment_receipts embedded as an object or a one-element array. */
fun Row.receiptOf(): Row? = objOrFirst("payment_receipts")

/** Active staff (usersService.list filtered by is_active), loaded only when [enabled]. */
@Composable
fun rememberActiveStaff(enabled: Boolean): List<Row> {
    val q = rememberLoad(enabled, enabled = enabled) { UsersService.list() }
    return q.data.orEmpty().filter { it.bool("is_active", true) }
}

fun collectorOpts(staff: List<Row>, allLabel: String = "All collectors", withUnassigned: Boolean = false): List<Opt> =
    listOf(Opt("", allLabel)) + (if (withUnassigned) listOf(Opt("none", "Unassigned")) else emptyList()) +
        staff.map { Opt(it.str("id"), it.str("full_name")) }

@Composable
fun PostedPill(status: String?) = if (status == "reversed") Pill("Reversed", Tone.red) else Pill("Posted", Tone.green)

/** Stat-card grid like the web's `grid-cols-2 md:grid-cols-4 …`: cells are (span, content). */
@Composable
fun StatGrid(columns: Int, cells: List<Pair<Int, @Composable (Modifier) -> Unit>>) {
    val rows = mutableListOf<MutableList<Pair<Int, @Composable (Modifier) -> Unit>>>()
    var used = columns
    cells.forEach { c ->
        val span = c.first.coerceAtMost(columns)
        if (used + span > columns) { rows += mutableListOf<Pair<Int, @Composable (Modifier) -> Unit>>(); used = 0 }
        rows.last() += span to c.second
        used += span
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                r.forEach { (span, content) -> content(Modifier.weight(span.toFloat())) }
                val rest = columns - r.sumOf { it.first }
                if (rest > 0) Spacer(Modifier.weight(rest.toFloat()))
            }
        }
    }
}

/** Screen width in dp (for the web's md / lg / xl breakpoints). */
@Composable
fun screenWidthDp(): Int = LocalConfiguration.current.screenWidthDp

/** Phone number with call (and optional SMS reminder) buttons. */
@Composable
fun ContactCell(mobile: String?, name: String, sms: (() -> SmsArgs)? = null) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(phMobile(mobile), fontSize = 13.sp, color = LF.Foreground)
        Spacer(Modifier.width(2.dp))
        RoundIcon(Icons.Default.Phone, "Call $name", LF.Blue600) { callNumber(ctx, mobile) }
        if (sms != null) {
            RoundIcon(Icons.AutoMirrored.Filled.Message, "Text reminder to $name", LF.Teal700) {
                val a = sms()
                sendSmsReminder(ctx, mobile, a.name, a.amount, a.dueDate, a.loanNumber, a.lender, a.overdue)
            }
        }
    }
}

data class SmsArgs(val name: String, val amount: Any?, val dueDate: String, val loanNumber: String, val lender: String?, val overdue: Boolean)

@Composable
private fun RoundIcon(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(18.dp), tint = tint)
    }
}

/** Segmented tabs with icons and a navy selected tab (Due payments). */
@Composable
fun SegmentTabs(tabs: List<Triple<String, String, ImageVector>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
            .then(Modifier.padding(0.dp)).horizontalScroll(rememberScrollState()).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { (key, label, icon) ->
            val sel = key == selected
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) LF.Navy else Color.Transparent)
                    .clickable { onSelect(key) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(16.dp), tint = if (sel) Color.White else LF.MutedForeground)
                Spacer(Modifier.width(8.dp))
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (sel) Color.White else LF.MutedForeground, maxLines = 1)
            }
        }
    }
}

/** Grey summary bar under a table ("Posted on this page  ₱…"). */
@Composable
fun TotalBar(label: String, value: String, bg: Color = LF.Slate50, valueColor: Color = LF.Foreground) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = LF.MutedForeground)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}
