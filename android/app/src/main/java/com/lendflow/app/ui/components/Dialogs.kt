package com.lendflow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lendflow.app.ui.theme.LF
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Modal dialog (shadcn Dialog): title, description, scrollable body and a footer of actions.
 * Full width on phones, max [maxWidth] on tablets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    description: String? = null,
    maxWidth: Dp = 560.dp,
    dismissible: Boolean = true,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = dismissible, dismissOnBackPress = dismissible, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().imePadding().padding(12.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = maxWidth).fillMaxWidth().heightIn(max = 900.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Color.White),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(top = 6.dp)) {
                        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = LF.Foreground)
                        if (description != null) Text(description, fontSize = 13.sp, color = LF.MutedForeground, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (dismissible) IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = LF.MutedForeground) }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = content,
                )
                if (footer != null) {
                    HorizontalDivider(color = LF.Border)
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { footer() } }
                }
            }
        }
    }
}

/**
 * Confirmation with an optional required reason (reject, cancel, reverse, void…). [onConfirm]
 * receives the reason; the dialog stays open and shows a spinner while it runs and closes on success.
 */
@Composable
fun ConfirmDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (reason: String) -> Unit,
    message: String? = null,
    confirmText: String = "Confirm",
    destructive: Boolean = false,
    reasonLabel: String? = null,
    reasonRequired: Boolean = true,
    reasonPlaceholder: String? = null,
    successMessage: String? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var reason by remember { mutableStateOf("") }
    val runner = rememberRunner()
    val blocked = reasonLabel != null && reasonRequired && reason.isBlank()
    AppDialog(
        title = title,
        onDismiss = { if (!runner.busy) onDismiss() },
        description = message,
        maxWidth = 480.dp,
        footer = {
            Btn("Cancel", { onDismiss() }, variant = BtnVariant.Outline, enabled = !runner.busy)
            Btn(confirmText, {
                runner.run(success = successMessage) {
                    onConfirm(reason.trim())
                    onDismiss()
                }
            }, variant = if (destructive) BtnVariant.Destructive else BtnVariant.Primary, loading = runner.busy, enabled = !blocked)
        },
    ) {
        extra?.invoke(this)
        if (reasonLabel != null) {
            Field(reasonLabel, required = reasonRequired) {
                TextInput(reason, { reason = it }, singleLine = false, minLines = 3, placeholder = reasonPlaceholder)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Responsive data table: a table on tablets, cards on phones (DataTable on the web)
// ---------------------------------------------------------------------------
enum class CellRole { Title, Subtitle, Value, Meta, Hidden }
enum class Align { Start, End, Center }

class Col<T>(
    val header: String,
    val weight: Float = 1f,
    val align: Align = Align.Start,
    /** where the value goes on the phone card */
    val mobile: CellRole = CellRole.Meta,
    /** minimum width of the column in the table */
    val minWidth: Dp = 90.dp,
    val cell: @Composable (T) -> Unit,
)

/** Plain text cell helper. */
@Composable
fun CellText(text: String?, bold: Boolean = false, muted: Boolean = false, align: TextAlign = TextAlign.Start, maxLines: Int = 2) {
    Text(
        text?.ifBlank { null } ?: "—", fontSize = 14.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = if (muted) LF.MutedForeground else LF.Foreground, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun <T> DataTable(
    columns: List<Col<T>>,
    rows: List<T>?,
    modifier: Modifier = Modifier,
    onRowClick: ((T) -> Unit)? = null,
    loading: Boolean = false,
    empty: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    forceCards: Boolean = false,
) {
    if (loading && rows == null) { SkeletonRows(); return }
    if (rows.isNullOrEmpty()) { if (empty != null) empty() else EmptyState("Nothing here yet"); return }
    if (forceCards || widthClass() == WidthClass.Compact) {
        val title = columns.firstOrNull { it.mobile == CellRole.Title }
        val subtitle = columns.firstOrNull { it.mobile == CellRole.Subtitle }
        val value = columns.firstOrNull { it.mobile == CellRole.Value }
        val metas = columns.filter { it.mobile == CellRole.Meta }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { r -> MobileRowCard(r, title, subtitle, value, metas, onRowClick) }
            footer?.invoke()
        }
    } else {
        val scroll = rememberScrollState()
        Column(modifier.horizontalScroll(scroll)) {
            val totalMin = columns.sumOf { it.minWidth.value.toDouble() }.toFloat()
            Column(Modifier.widthIn(min = max(totalMin, 600f).dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    columns.forEach { c ->
                        Text(c.header.uppercase(), Modifier.weight(c.weight).widthIn(min = c.minWidth).padding(horizontal = 10.dp),
                            fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground, letterSpacing = 0.4.sp,
                            textAlign = c.align.textAlign(), maxLines = 1)
                    }
                }
                HorizontalDivider(color = LF.Border)
                rows.forEachIndexed { i, r ->
                    Row(
                        Modifier.fillMaxWidth().then(if (onRowClick != null) Modifier.clickable { onRowClick(r) } else Modifier).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        columns.forEach { c ->
                            Box(Modifier.weight(c.weight).widthIn(min = c.minWidth).padding(horizontal = 10.dp), contentAlignment = c.align.boxAlign()) { c.cell(r) }
                        }
                    }
                    if (i < rows.size - 1) HorizontalDivider(color = LF.Border)
                }
                footer?.invoke()
            }
        }
    }
}

@Composable
private fun <T> MobileRowCard(r: T, title: Col<T>?, subtitle: Col<T>?, value: Col<T>?, metas: List<Col<T>>, onRowClick: ((T) -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White)
            .border(1.dp, LF.Border, RoundedCornerShape(10.dp))
            .then(if (onRowClick != null) Modifier.clickable { onRowClick(r) } else Modifier)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                title?.let { Box { it.cell(r) } }
                subtitle?.let { Box(Modifier.padding(top = 2.dp)) { androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = LF.MutedForeground)) { it.cell(r) } } }
            }
            if (value != null) { Spacer(Modifier.width(10.dp)); Box(contentAlignment = Alignment.TopEnd) { value.cell(r) } }
        }
        if (metas.isNotEmpty()) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                metas.forEach { m -> Box { androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = LF.MutedForeground)) { m.cell(r) } } }
            }
        }
    }
}

private fun Align.textAlign() = when (this) { Align.Start -> TextAlign.Start; Align.End -> TextAlign.End; Align.Center -> TextAlign.Center }
private fun Align.boxAlign() = when (this) { Align.Start -> Alignment.CenterStart; Align.End -> Alignment.CenterEnd; Align.Center -> Alignment.Center }

/** "1–25 of 120  < Page 1 / 5 >" */
@Composable
fun Pagination(page: Int, pageSize: Int, count: Int, onPage: (Int) -> Unit, modifier: Modifier = Modifier) {
    val pages = max(1, ceil(count / pageSize.toDouble()).toInt())
    if (count <= pageSize) {
        if (count > 0) Text("$count record${if (count == 1) "" else "s"}", modifier.padding(top = 12.dp, start = 4.dp), fontSize = 12.sp, color = LF.MutedForeground)
        return
    }
    Row(modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${page * pageSize + 1}–${min(count, (page + 1) * pageSize)} of $count", Modifier.weight(1f), fontSize = 12.sp, color = LF.MutedForeground)
        OutlinedIconButton(onClick = { onPage(page - 1) }, enabled = page > 0, modifier = Modifier.size(34.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page")
        }
        Text("Page ${page + 1} / $pages", Modifier.padding(horizontal = 8.dp), fontSize = 12.sp, color = LF.MutedForeground)
        OutlinedIconButton(onClick = { onPage(page + 1) }, enabled = page + 1 < pages, modifier = Modifier.size(34.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page")
        }
    }
}

@Suppress("unused") private val keepH = Modifier.height(0.dp)
