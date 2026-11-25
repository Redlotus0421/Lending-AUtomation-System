package com.lendflow.app.feature.loans.simplified

// Port of src/features/loans/simplified/shared.tsx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.peso

/** "Monthly payment", "Weekly payment", … */
fun paymentLabel(freq: String?): String = when (freq) {
    "semimonthly" -> "Semi-monthly payment"
    "weekly" -> "Weekly payment"
    "daily" -> "Daily payment"
    else -> "Monthly payment"
}

fun termUnit(freq: String?, n: Int = 2): String {
    val one = when (freq) { "semimonthly" -> "payment"; "weekly" -> "week"; "daily" -> "day"; else -> "month" }
    return if (n == 1) one else "${one}s"
}

/** Line-by-line computation of the amount actually handed to the borrower. */
@Composable
fun NetReleaseTable(
    principal: Double,
    deductedCharges: Double,
    previousBalance: Double,
    netRelease: Double,
    modifier: Modifier = Modifier,
    previousLabel: String? = null,
    footnote: String? = null,
) {
    val rows = mutableListOf(Triple("New loan principal", principal, false))
    if (deductedCharges > 0) rows += Triple("Less: fees & charges deducted", deductedCharges, true)
    if (previousBalance > 0) rows += Triple(previousLabel ?: "Less: existing outstanding balance", previousBalance, true)
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().clip(shape).border(1.dp, LF.Border, shape).background(Color.White)) {
        rows.forEach { (label, amount, minus) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // ArrowDownRight
                    if (minus) Icon(Icons.AutoMirrored.Filled.CallReceived, null, Modifier.size(14.dp).rotate(-90f), tint = LF.Red500)
                    Text(label, Modifier.padding(start = if (minus) 6.dp else 0.dp), fontSize = 14.sp, color = LF.MutedForeground)
                }
                Text((if (minus) "− " else "") + peso(amount), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (minus) LF.Red600 else LF.Foreground, textAlign = TextAlign.End)
            }
            HorizontalDivider(color = LF.Border)
        }
        Row(Modifier.fillMaxWidth().background(LF.Navy).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Net amount to release", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(peso(netRelease), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
        if (footnote != null) {
            HorizontalDivider(color = LF.Border)
            Text(footnote, Modifier.fillMaxWidth().background(LF.Slate50).padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, color = LF.MutedForeground)
        }
    }
}
