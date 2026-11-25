package com.lendflow.app.feature.loans

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.ui.theme.LF
import kotlin.math.floor

// ---------------------------------------------------------------------------
// Plain HTML-style tables used by the loan pages (schedule, ledger, renewals). They fill the width
// on tablets and scroll horizontally on phones (min-w-[900px] overflow-x-auto on the web).
// ---------------------------------------------------------------------------

internal class LnCol(val header: String, val width: Dp, val end: Boolean = false)

/** One cell: text (+ an optional small second line) or custom content. */
internal class LnCell(
    val text: String? = null,
    val color: Color = LF.Foreground,
    val bold: Boolean = false,
    val small: String? = null,
    val smallColor: Color = LF.MutedForeground,
    val strike: Boolean = false,
    val mono: Boolean = false,
    val size: TextUnit = 14.sp,
    val content: (@Composable () -> Unit)? = null,
)

internal class LnGridScope(val cols: List<LnCol>, val widths: List<Dp>)

@Composable
internal fun LnGrid(
    cols: List<LnCol>,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    bordered: Boolean = true,
    headerBg: Color = LF.Slate50,
    footer: (@Composable LnGridScope.() -> Unit)? = null,
    body: @Composable LnGridScope.() -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    BoxWithConstraints(
        modifier.fillMaxWidth().then(if (bordered) Modifier.clip(shape).border(1.dp, LF.Border, shape) else Modifier),
    ) {
        val total = cols.fold(0.dp) { a, c -> a + c.width }
        val scale = if (maxWidth > total && total > 0.dp) maxWidth / total else 1f
        val widths = cols.map { it.width * scale }
        val scope = LnGridScope(cols, widths)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(headerBg), verticalAlignment = Alignment.CenterVertically) {
                cols.forEachIndexed { i, c ->
                    Text(
                        c.header.uppercase(), Modifier.width(widths[i]).padding(horizontal = 10.dp, vertical = 8.dp),
                        fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground, letterSpacing = 0.4.sp,
                        textAlign = if (c.end) TextAlign.End else TextAlign.Start, maxLines = 1,
                    )
                }
            }
            Column(
                if (maxHeight != null) Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()) else Modifier,
            ) { scope.body() }
            footer?.invoke(scope)
        }
    }
}

@Composable
internal fun LnGridScope.LnRow(cells: List<LnCell>, bg: Color = Color.Transparent, faded: Boolean = false, topBorder: Boolean = true, vPad: Dp = 8.dp) {
    Column(Modifier.background(bg).then(if (faded) Modifier.alpha(0.5f) else Modifier)) {
        if (topBorder) HorizontalDivider(Modifier.width(widths.fold(0.dp) { a, w -> a + w }), color = LF.Border)
        Row(verticalAlignment = Alignment.CenterVertically) {
            cells.forEachIndexed { i, c ->
                val col = cols.getOrNull(i) ?: return@forEachIndexed
                Box(
                    Modifier.width(widths[i]).padding(horizontal = 10.dp, vertical = vPad),
                    contentAlignment = if (col.end) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    if (c.content != null) c.content.invoke()
                    else Column(horizontalAlignment = if (col.end) Alignment.End else Alignment.Start) {
                        Text(
                            c.text ?: "", fontSize = c.size, color = c.color,
                            fontWeight = if (c.bold) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = if (col.end) TextAlign.End else TextAlign.Start,
                            textDecoration = if (c.strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                            fontFamily = if (c.mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (c.small != null) Text(c.small, fontSize = 11.sp, color = c.smallColor, textAlign = if (col.end) TextAlign.End else TextAlign.Start)
                    }
                }
            }
        }
    }
}

/** Responsive grid of equal cells: [cols] per row. */
@Composable
internal fun LnEqualGrid(count: Int, cols: Int, modifier: Modifier = Modifier, hGap: Dp = 12.dp, vGap: Dp = 12.dp, item: @Composable (Int) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(vGap)) {
        (0 until count).chunked(cols.coerceAtLeast(1)).forEach { chunk ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(hGap)) {
                chunk.forEach { i -> Box(Modifier.weight(1f)) { item(i) } }
                repeat(cols - chunk.size) { Box(Modifier.weight(1f)) {} }
            }
        }
    }
}

/** JavaScript String(number): 5 → "5", 2.5 → "2.5". */
internal fun lnJsNum(v: Double): String = if (v == floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

/** LnTinted rounded box (rounded-lg bg-*-50 p-3). */
@Composable
internal fun LnTinted(bg: Color, modifier: Modifier = Modifier, border: Color? = null, padding: Dp = 12.dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.fillMaxWidth().clip(shape).background(bg).then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier).padding(padding),
    ) { content() }
}
