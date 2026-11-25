package com.lendflow.app.feature.loans.simplified

// Port of src/features/loans/simplified/TransactionOptions.tsx

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.ui.components.card
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.theme.LF

private class TxOption(val t: String, val icon: ImageVector, val title: String, val desc: String, val points: List<String>)

private val OPTIONS = listOf(
    TxOption(
        "new", Icons.Default.PersonAdd, "New loan", "First loan of a new borrower.",
        listOf("Register the borrower with photo, ID and ATM card", "Add the co-maker", "Enter amount and duration — the rate is automatic"),
    ),
    TxOption(
        "renewal", Icons.Default.Refresh, "Reloan / Renew", "Another loan for a registered borrower.",
        listOf("Search the borrower — no re-registration", "Outstanding balance is fetched automatically", "Balance is deducted from the new loan"),
    ),
)

/** New loan / Reloan-Renew picker. onPick("new" | "renewal"); disabled = option -> reason */
@Composable
fun TransactionOptions(compact: Boolean = false, onPick: (String) -> Unit, disabled: Map<String, String> = emptyMap()) {
    val side = !compact && !isCompact()
    if (side) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OPTIONS.forEach { o -> Box(Modifier.weight(1f)) { OptionCard(o, compact, disabled[o.t], onPick) } }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)) {
            OPTIONS.forEach { o -> OptionCard(o, compact, disabled[o.t], onPick) }
        }
    }
}

@Composable
private fun OptionCard(o: TxOption, compact: Boolean, blocked: String?, onPick: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val base = if (compact) Modifier.fillMaxWidth().clip(shape).background(Color.White) else Modifier.fillMaxWidth().card()
    Row(
        base.border(2.dp, LF.Slate200, shape).alpha(if (blocked != null) 0.6f else 1f)
            .clickable(enabled = blocked == null) { onPick(o.t) }
            .padding(if (compact) 16.dp else 20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (compact) {
            IconTile(o.icon, 44)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            if (!compact) { IconTile(o.icon, 48); Spacer(Modifier.height(16.dp)) }
            Text(o.title, fontSize = if (compact) 16.sp else 18.sp, fontWeight = FontWeight.SemiBold, color = LF.Foreground)
            Text(o.desc, fontSize = 14.sp, color = LF.MutedForeground)
            Column(Modifier.padding(top = if (compact) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
                o.points.forEach { p ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.padding(top = 2.dp).size(if (compact) 14.dp else 16.dp), tint = LF.Teal600)
                        Text(p, Modifier.padding(start = 8.dp), fontSize = if (compact) 12.sp else 14.sp, color = if (compact) LF.Slate600 else LF.Foreground)
                    }
                }
            }
            if (blocked != null) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Lock, null, Modifier.padding(top = 2.dp).size(14.dp), tint = LF.Amber600)
                    Text(blocked, Modifier.padding(start = 6.dp), fontSize = 12.sp, color = Color(0xFFB45309))
                }
            } else if (!compact) {
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Start", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LF.Blue)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(start = 4.dp).size(16.dp), tint = LF.Blue)
                }
            }
        }
        if (compact && blocked == null) Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(top = 12.dp).size(20.dp), tint = LF.Blue)
    }
}

@Composable
private fun IconTile(icon: ImageVector, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(LF.Navy.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(24.dp), tint = LF.Navy)
    }
}
