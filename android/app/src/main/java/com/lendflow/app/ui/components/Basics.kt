package com.lendflow.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.ShieldMoon
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.ui.theme.toneColors
import com.lendflow.app.util.AGREEMENT_STATUS
import com.lendflow.app.util.INSTALLMENT_STATUS
import com.lendflow.app.util.LOAN_STATUS
import com.lendflow.app.util.StatusInfo
import com.lendflow.app.util.Tone
import com.lendflow.app.util.titleCase

// ---------------------------------------------------------------------------
// Screen size: phones (compact), small tablets / landscape phones (medium), tablets (expanded)
// ---------------------------------------------------------------------------
enum class WidthClass { Compact, Medium, Expanded }

@Composable
fun widthClass(): WidthClass {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w >= 840 -> WidthClass.Expanded
        w >= 600 -> WidthClass.Medium
        else -> WidthClass.Compact
    }
}

@Composable
fun isCompact(): Boolean = widthClass() == WidthClass.Compact

// ---------------------------------------------------------------------------
// Status badges
// ---------------------------------------------------------------------------
@Composable
fun Pill(text: String, tone: Tone = Tone.slate, dot: Boolean = true, modifier: Modifier = Modifier) {
    val c = toneColors(tone)
    Row(
        modifier
            .clip(CircleShape)
            .background(c.bg)
            .border(1.dp, c.ring, CircleShape)
            .padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dot) Box(Modifier.size(6.dp).clip(CircleShape).background(c.fg.copy(alpha = 0.7f)))
        Text(text, color = c.fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

private fun info(map: Map<String, StatusInfo>, status: String?) = map[status] ?: StatusInfo(titleCase(status), Tone.slate)

@Composable fun LoanStatusBadge(status: String?, modifier: Modifier = Modifier) = info(LOAN_STATUS, status).let { Pill(it.label, it.tone, modifier = modifier) }
@Composable fun InstallmentStatusBadge(status: String?, modifier: Modifier = Modifier) = info(INSTALLMENT_STATUS, status).let { Pill(it.label, it.tone, modifier = modifier) }
@Composable fun AgreementStatusBadge(status: String?, modifier: Modifier = Modifier) = info(AGREEMENT_STATUS, status).let { Pill(it.label, it.tone, modifier = modifier) }

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------
enum class BtnVariant { Primary, Blue, Outline, Ghost, Destructive, Secondary, Teal }

@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BtnVariant = BtnVariant.Primary,
    icon: ImageVector? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
    small: Boolean = false,
) {
    val h = if (small) 36.dp else 44.dp
    val pad = PaddingValues(horizontal = if (small) 12.dp else 16.dp, vertical = 0.dp)
    val shape = RoundedCornerShape(8.dp)
    val content: @Composable RowScope.() -> Unit = {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColorFor(variant))
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = if (small) 13.sp else 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val m = modifier.heightIn(min = h)
    val en = enabled && !loading
    when (variant) {
        BtnVariant.Outline -> OutlinedButton(onClick, m, en, shape, contentPadding = pad,
            border = BorderStroke(1.dp, LF.Input),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = LF.Foreground), content = content)
        BtnVariant.Ghost -> TextButton(onClick, m, en, shape, contentPadding = pad,
            colors = ButtonDefaults.textButtonColors(contentColor = LF.Foreground), content = content)
        else -> {
            val (bg, fg) = when (variant) {
                BtnVariant.Blue -> LF.Blue to Color.White
                BtnVariant.Destructive -> LF.Danger to Color.White
                BtnVariant.Secondary -> LF.Secondary to LF.Navy
                BtnVariant.Teal -> LF.Teal to Color.White
                else -> LF.Navy to Color.White
            }
            Button(onClick, m, en, shape, contentPadding = pad,
                colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg, disabledContainerColor = bg.copy(alpha = 0.5f), disabledContentColor = fg.copy(alpha = 0.8f)),
                content = content)
        }
    }
}

@Composable
private fun LocalContentColorFor(v: BtnVariant): Color = when (v) {
    BtnVariant.Outline, BtnVariant.Ghost -> LF.Foreground
    BtnVariant.Secondary -> LF.Navy
    else -> Color.White
}

/** Text link styled like the web's blue underlined buttons. */
@Composable
fun LinkText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = LF.Blue600, fontSize: TextUnit = 14.sp) {
    Text(text, modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 2.dp),
        color = color, fontSize = fontSize, fontWeight = FontWeight.Medium)
}

// ---------------------------------------------------------------------------
// Page scaffolding
// ---------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageHeader(
    title: String,
    description: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        if (onBack != null) {
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onBack).padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(16.dp), tint = LF.MutedForeground)
                Spacer(Modifier.width(4.dp))
                Text(backLabel, color = LF.MutedForeground, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = LF.Foreground)
        if (description != null) {
            Spacer(Modifier.height(4.dp))
            Text(description, fontSize = 14.sp, color = LF.MutedForeground)
        }
        if (actions != null) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
            }
        }
    }
}

val CardShape = RoundedCornerShape(12.dp)

/** White card with the soft web shadow. */
fun Modifier.card(): Modifier = this
    .shadow(2.dp, CardShape, ambientColor = Color(0x14102040), spotColor = Color(0x14102040))
    .clip(CardShape)
    .background(Color.White)
    .border(1.dp, LF.Border, CardShape)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    description: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().card()) {
        if (title != null || actions != null) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.padding(end = 8.dp).align(Alignment.CenterVertically)) {
                    if (title != null) Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LF.Foreground)
                    if (description != null) Text(description, fontSize = 12.sp, color = LF.MutedForeground, modifier = Modifier.padding(top = 2.dp))
                }
                if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
            }
            HorizontalDivider(color = LF.Border)
        }
        Column(Modifier.fillMaxWidth().padding(contentPadding)) { content() }
    }
}

enum class StatTone { blue, teal, navy, green, amber, red }

@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    icon: ImageVector? = null,
    tone: StatTone = StatTone.blue,
    onClick: (() -> Unit)? = null,
) {
    val (bg, fg) = when (tone) {
        StatTone.blue -> LF.Blue50 to LF.Blue600
        StatTone.teal -> LF.Teal50 to LF.Teal600
        StatTone.navy -> LF.Navy.copy(alpha = 0.1f) to LF.Navy
        StatTone.green -> LF.Green50 to LF.Green600
        StatTone.amber -> LF.Amber50 to LF.Amber600
        StatTone.red -> LF.Red50 to LF.Red600
    }
    Column(modifier.card().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(label.uppercase(), Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground, letterSpacing = 0.4.sp, maxLines = 2)
            if (icon != null) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(bg), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(16.dp), tint = fg)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // the amount shrinks to fit narrow cards (₱1,234,567.89)
        AutoSizeText(value, maxSize = 22.sp, minSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LF.Foreground)
        if (hint != null) Text(hint, fontSize = 12.sp, color = LF.MutedForeground, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Single-line text that scales its font size down to fit the available width. */
@Composable
fun AutoSizeText(text: String, modifier: Modifier = Modifier, maxSize: TextUnit = 22.sp, minSize: TextUnit = 12.sp, fontWeight: FontWeight? = null, color: Color = Color.Unspecified) {
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        // approx: average glyph width ≈ 0.58em for digits and punctuation
        val fit = if (text.isEmpty() || widthPx <= 0f) maxSize.value else (widthPx / (text.length * 0.6f)) / (LocalDensityScale())
        val size = fit.coerceIn(minSize.value, maxSize.value).sp
        Text(text, fontSize = size, fontWeight = fontWeight, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun LocalDensityScale(): Float = androidx.compose.ui.platform.LocalDensity.current.let { it.density * it.fontScale }

data class KV(val label: String, val value: String?, val hidden: Boolean = false)

/** Definition list: label above value, laid out in [columns] on wide screens. */
@Composable
fun KeyValue(items: List<KV>, columns: Int = 2, modifier: Modifier = Modifier) {
    val visible = items.filter { !it.hidden }
    val cols = if (isCompact()) 1.coerceAtLeast(if (columns >= 2) 2 else 1) else columns
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        visible.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { kv ->
                    Column(Modifier.weight(1f)) {
                        Text(kv.label, fontSize = 12.sp, color = LF.MutedForeground)
                        Text(kv.value?.ifBlank { null } ?: "—", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Foreground, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A label/value row (label left, value right) used in summaries. */
@Composable
fun SummaryRow(label: String, value: String, bold: Boolean = false, valueColor: Color = LF.Foreground, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp), fontSize = 14.sp, color = if (bold) LF.Foreground else LF.MutedForeground, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
        Text(value, fontSize = 14.sp, color = valueColor, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
fun EmptyState(title: String, modifier: Modifier = Modifier, description: String? = null, icon: ImageVector = Icons.Default.Inbox, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(LF.Muted), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = LF.MutedForeground)
        }
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (description != null) Text(description, fontSize = 14.sp, color = LF.MutedForeground, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp).widthIn(max = 360.dp))
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

@Composable
fun ErrorState(error: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(LF.Red50.copy(alpha = 0.6f)).border(1.dp, LF.Red200, CardShape).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Warning, null, Modifier.size(24.dp), tint = LF.Red500)
        Spacer(Modifier.height(8.dp))
        Text("Could not load data", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LF.Red800)
        Text(error, fontSize = 14.sp, color = LF.Red700.copy(alpha = 0.8f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        if (onRetry != null) { Spacer(Modifier.height(16.dp)); Btn("Try again", onRetry, variant = BtnVariant.Outline, small = true) }
    }
}

@Composable
fun Spinner(size: Dp = 16.dp, color: Color = LF.MutedForeground) {
    CircularProgressIndicator(Modifier.size(size), strokeWidth = 2.dp, color = color)
}

@Composable
fun PageLoader(label: String = "Loading…", modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 240.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Spinner()
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp, color = LF.MutedForeground)
    }
}

/** Standard handling for a [Loadable]: loader, error with retry, or the content. */
@Composable
fun <T> LoadBox(state: Loadable<T>, loadingLabel: String = "Loading…", content: @Composable (T) -> Unit) {
    when {
        state.data != null -> content(state.data)
        state.error != null -> ErrorState(state.error, state.reload)
        else -> PageLoader(loadingLabel)
    }
}

@Composable
fun SkeletonRows(rows: Int = 6) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) { Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(LF.Muted)) }
    }
}

/** Access restricted card (RequirePermission on the web). */
@Composable
fun AccessRestricted(onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 420.dp).card().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ShieldMoon, null, Modifier.size(32.dp), tint = LF.Warning)
            Spacer(Modifier.height(12.dp))
            Text("Access restricted", style = MaterialTheme.typography.titleLarge)
            Text("Your role does not have permission to open this page. Contact your administrator if you need access.",
                fontSize = 14.sp, color = LF.MutedForeground, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(20.dp))
            Btn("Back to dashboard", onBack, variant = BtnVariant.Outline)
        }
    }
}

/** Tinted notice box (info / warning / success / danger). */
@Composable
fun Notice(text: String, tone: Tone = Tone.blue, modifier: Modifier = Modifier, title: String? = null, icon: ImageVector? = null) {
    val c = toneColors(tone)
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.bg).border(1.dp, c.ring, RoundedCornerShape(8.dp)).padding(12.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp).padding(top = 1.dp), tint = c.fg); Spacer(Modifier.width(8.dp)) }
        Column {
            if (title != null) Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.fg)
            Text(text, fontSize = 13.sp, color = c.fg)
        }
    }
}

/** Round initials avatar. */
@Composable
fun Avatar(initials: String, size: Dp = 36.dp, bg: Color = LF.Navy, fg: Color = Color.White) {
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(initials, color = fg, fontSize = (size.value * 0.34f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(bottom = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LF.MutedForeground, letterSpacing = 0.6.sp)
}

/** Surface with a thin border, for nested blocks inside a Panel. */
@Composable
fun Bordered(modifier: Modifier = Modifier, bg: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = bg, border = BorderStroke(1.dp, LF.Border)) {
        Column(Modifier.padding(12.dp), content = content)
    }
}
