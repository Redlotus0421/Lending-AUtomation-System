package com.lendflow.app.feature.borrowers

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lendflow.app.ui.components.Avatar
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.StorageImage
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Tone
import com.lendflow.app.util.initials

// Port of src/features/borrowers/shared.tsx (used by several modules).

/** Profile photo from the private borrower-files bucket, or initials. */
@Composable
fun BorrowerAvatar(fullName: String?, photoPath: String?, size: Dp = 40.dp) {
    if (!photoPath.isNullOrBlank()) {
        StorageImage("borrower-files", photoPath, Modifier.size(size).clip(CircleShape).border(1.dp, Color(0x0D000000), CircleShape), contentDescription = fullName)
    } else {
        Avatar(initials(fullName), size, bg = LF.Navy.copy(alpha = 0.1f), fg = LF.Navy)
    }
}

data class RiskInfo(val label: String, val tone: Tone)

val RISK = mapOf(
    "unrated" to RiskInfo("Unrated", Tone.slate),
    "low" to RiskInfo("Low risk", Tone.green),
    "medium" to RiskInfo("Medium risk", Tone.amber),
    "high" to RiskInfo("High risk", Tone.red),
)

@Composable
fun RiskBadge(risk: String?) {
    val r = RISK[risk] ?: RISK.getValue("unrated")
    Pill(r.label, r.tone)
}

@Composable
fun BorrowerStatusBadge(status: String?) {
    val tone = when (status) { "active" -> Tone.green; "blacklisted" -> Tone.red; else -> Tone.slate }
    Pill(when (status) { "active" -> "Active"; "blacklisted" -> "Blacklisted"; else -> "Archived" }, tone)
}

val OPEN_LOAN_STATUSES = listOf("active", "defaulted", "ready_for_release", "fully_signed", "awaiting_borrower_signature", "awaiting_lender_signature", "approved", "pending_review", "draft")
