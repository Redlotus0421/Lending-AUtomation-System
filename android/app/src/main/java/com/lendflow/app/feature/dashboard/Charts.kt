package com.lendflow.app.feature.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.data.Row
import com.lendflow.app.data.int
import com.lendflow.app.data.num
import com.lendflow.app.data.str
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.peso
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// Native Canvas port of src/features/dashboard/charts.tsx (Recharts). Same palette as the web
// (validated with the dataviz validator on a light surface): blue, teal, yellow, violet.

object SERIES {
    val blue = Color(0xFF2563EB)
    val teal = Color(0xFF0D9488)
    val yellow = Color(0xFFEDA100)
    val violet = Color(0xFF6250D6)
}

/** Ordinal blue ramp for ageing buckets (current -> 90+). */
private val AGING_RAMP = listOf(Color(0xFF86B6EF), Color(0xFF5598E7), Color(0xFF2A78D6), Color(0xFF1C5CAB), Color(0xFF104281))
private val AXIS = Color(0xFF5B6B82)
private val GRID = Color(0xFFEEF1F6)
private val CURSOR = Color(0x0F2563EB) // rgba(37,99,235,0.06)
private val CURSOR_LINE = Color(0xFF94A3B8)

private fun compactPeso(v: Double) = peso(v, compact = true)

data class ChartSeries(val key: String, val name: String, val color: Color)

private enum class ChartKind { Bars, Stacked, Area }

/** "Nice" axis ticks from 0 to >= max (about 4–5 steps, like d3's nice()). */
private fun niceTicks(maxValue: Double, count: Int = 4): List<Double> {
    if (maxValue <= 0.0) return listOf(0.0, 1.0, 2.0, 3.0, 4.0).map { it * 250 }
    val rough = maxValue / count
    val mag = 10.0.pow(floor(log10(rough)))
    val norm = rough / mag
    val step = when {
        norm <= 1 -> 1.0
        norm <= 2 -> 2.0
        norm <= 2.5 -> 2.5
        norm <= 5 -> 5.0
        else -> 10.0
    } * mag
    val top = ceil(maxValue / step) * step
    val out = mutableListOf<Double>()
    var t = 0.0
    while (t <= top + step / 2) { out += t; t += step }
    return out
}

/** Indices of category labels to draw so they don't overlap (Recharts interval="preserveStartEnd"). */
private fun labelIndices(n: Int, band: Float, labelW: Float): List<Int> {
    if (n <= 0) return emptyList()
    val step = max(1, ceil((labelW + 8f) / max(band, 1f)).toInt())
    val out = (0 until n step step).toMutableList()
    if (out.last() != n - 1) {
        if ((n - 1 - out.last()) * band < labelW + 8f && out.size > 1) out.removeAt(out.size - 1)
        out += n - 1
    }
    return out
}

private data class TipLine(val name: String, val color: Color, val value: Double)

@Composable
private fun ChartTooltip(label: String, lines: List<TipLine>, extra: String?, modifier: Modifier = Modifier) {
    Column(
        modifier.width(188.dp).shadow(6.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(Color.White)
            .border(1.dp, LF.Border, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LF.Foreground, modifier = Modifier.padding(bottom = 4.dp))
        lines.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(l.color))
                Spacer(Modifier.width(6.dp))
                Text(l.name, Modifier.weight(1f), fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1)
                Text(peso(l.value), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.Foreground)
            }
        }
        if (lines.size > 1) {
            HorizontalDivider(Modifier.padding(top = 4.dp), color = LF.Border)
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("Total", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(peso(lines.sumOf { it.value }), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (extra != null) {
            HorizontalDivider(Modifier.padding(top = 4.dp), color = LF.Border)
            Text(extra, fontSize = 12.sp, color = LF.MutedForeground, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(series: List<ChartSeries>) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(s.color))
                Spacer(Modifier.width(5.dp))
                Text(s.name, fontSize = 12.sp, color = AXIS)
            }
        }
    }
}

private fun DrawScope.roundedBar(color: Color, left: Float, top: Float, width: Float, height: Float, radius: Float, roundTop: Boolean = true, horizontal: Boolean = false) {
    if (height <= 0f || width <= 0f) return
    val r = min(radius, if (horizontal) height / 2 else width / 2)
    val cr = CornerRadius(r, r)
    val rr = if (horizontal) {
        RoundRect(left, top, left + width, top + height, CornerRadius.Zero, if (roundTop) cr else CornerRadius.Zero,
            if (roundTop) cr else CornerRadius.Zero, CornerRadius.Zero)
    } else {
        RoundRect(left, top, left + width, top + height, if (roundTop) cr else CornerRadius.Zero, if (roundTop) cr else CornerRadius.Zero,
            CornerRadius.Zero, CornerRadius.Zero)
    }
    drawPath(Path().apply { addRoundRect(rr) }, color)
}

/** Monotone cubic path through the points (d3 curveMonotoneX, which Recharts' type="monotone" uses). */
private fun monotonePath(pts: List<Offset>, path: Path = Path()): Path {
    if (pts.isEmpty()) return path
    path.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) return path
    if (pts.size == 2) { path.lineTo(pts[1].x, pts[1].y); return path }
    val n = pts.size
    val d = DoubleArray(n - 1) { i -> ((pts[i + 1].y - pts[i].y) / (pts[i + 1].x - pts[i].x)).toDouble() }
    val m = DoubleArray(n)
    m[0] = d[0]; m[n - 1] = d[n - 2]
    for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0) 0.0 else (d[i - 1] + d[i]) / 2
    for (i in 0 until n - 1) {
        if (d[i] == 0.0) { m[i] = 0.0; m[i + 1] = 0.0; continue }
        val a = m[i] / d[i]; val b = m[i + 1] / d[i]
        val s = a * a + b * b
        if (s > 9) { val t = 3 / kotlin.math.sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
    }
    for (i in 0 until n - 1) {
        val h = pts[i + 1].x - pts[i].x
        path.cubicTo(
            pts[i].x + h / 3, (pts[i].y + m[i] * h / 3).toFloat(),
            pts[i + 1].x - h / 3, (pts[i + 1].y - m[i + 1] * h / 3).toFloat(),
            pts[i + 1].x, pts[i + 1].y,
        )
    }
    return path
}

/**
 * Vertical category chart over month points: grouped bars, stacked bars or an area. Tap a month to
 * see the tooltip (tap again or outside to hide).
 */
@Composable
private fun CategoryChart(
    data: List<Row>,
    series: List<ChartSeries>,
    kind: ChartKind,
    height: Dp = 240.dp,
    maxBar: Dp = 28.dp,
    barGap: Dp = 2.dp,
    legend: Boolean = false,
    labelKey: String = "label",
    extra: ((Row) -> String?)? = null,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var selected by remember(data) { mutableStateOf<Int?>(null) }
    val axisStyle = TextStyle(fontSize = 11.sp, color = AXIS)
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val px = { d: Dp -> with(density) { d.toPx() } }
            val left = px(64.dp); val right = px(8.dp); val top = px(8.dp); val bottom = px(22.dp)
            val plotW = (w - left - right).coerceAtLeast(1f)
            val plotH = (h - top - bottom).coerceAtLeast(1f)
            val n = data.size
            val values = data.map { r -> series.map { s -> r.num(s.key) } }
            val maxV = values.maxOfOrNull { vs -> if (kind == ChartKind.Stacked) vs.sum() else (vs.maxOrNull() ?: 0.0) } ?: 0.0
            val ticks = niceTicks(maxV)
            val topV = ticks.last().coerceAtLeast(1.0)
            val band = plotW / max(n, 1)
            fun yOf(v: Double) = top + plotH * (1f - (v / topV).toFloat())
            fun xPoint(i: Int) = if (n <= 1) left + plotW / 2 else left + plotW * i / (n - 1)
            fun centerOf(i: Int) = if (kind == ChartKind.Area) xPoint(i) else left + band * (i + 0.5f)

            Canvas(
                Modifier.fillMaxSize().pointerInput(data, kind, w, h) {
                    detectTapGestures { o ->
                        if (o.x < left || o.x > w - right || n == 0) { selected = null; return@detectTapGestures }
                        val i = if (kind == ChartKind.Area) {
                            if (n <= 1) 0 else ((o.x - left) / plotW * (n - 1)).roundToInt().coerceIn(0, n - 1)
                        } else ((o.x - left) / band).toInt().coerceIn(0, n - 1)
                        selected = if (selected == i) null else i
                    }
                },
            ) {
                // grid + y axis
                ticks.forEach { t ->
                    val y = yOf(t)
                    drawLine(GRID, Offset(left, y), Offset(w - right, y), strokeWidth = 1f)
                    val tl = measurer.measure(compactPeso(t), axisStyle)
                    drawText(tl, topLeft = Offset(left - 6f - tl.size.width, y - tl.size.height / 2f))
                }
                // cursor
                selected?.let { i ->
                    if (kind == ChartKind.Area) {
                        val x = xPoint(i)
                        drawLine(CURSOR_LINE, Offset(x, top), Offset(x, top + plotH), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    } else drawRect(CURSOR, Offset(left + band * i, top), Size(band, plotH))
                }
                val radius = px(4.dp)
                when (kind) {
                    ChartKind.Bars -> {
                        val groupW = band * 0.8f
                        val gap = px(barGap)
                        val count = series.size
                        val barW = min(px(maxBar), (groupW - gap * (count - 1)) / count)
                        val totalW = barW * count + gap * (count - 1)
                        values.forEachIndexed { i, vs ->
                            var x = left + band * i + (band - totalW) / 2
                            vs.forEachIndexed { si, v ->
                                if (v > 0) roundedBar(series[si].color, x, yOf(v), barW, top + plotH - yOf(v), radius)
                                x += barW + gap
                            }
                        }
                    }
                    ChartKind.Stacked -> {
                        val barW = min(px(maxBar), band * 0.8f)
                        values.forEachIndexed { i, vs ->
                            val x = left + band * i + (band - barW) / 2
                            var acc = 0.0
                            val lastNonZero = vs.indexOfLast { it > 0 }
                            vs.forEachIndexed { si, v ->
                                if (v > 0) {
                                    val y1 = yOf(acc + v); val y0 = yOf(acc)
                                    roundedBar(series[si].color, x, y1, barW, y0 - y1, radius, roundTop = si == lastNonZero)
                                    // 1px white separator like the web's white stroke
                                    if (acc > 0) drawLine(Color.White, Offset(x, y0), Offset(x + barW, y0), strokeWidth = 1.5f)
                                    acc += v
                                }
                            }
                        }
                    }
                    ChartKind.Area -> {
                        series.forEachIndexed { si, s ->
                            val pts = values.mapIndexed { i, vs -> Offset(xPoint(i), yOf(vs[si])) }
                            if (pts.isEmpty()) return@forEachIndexed
                            val line = monotonePath(pts)
                            val fill = monotonePath(pts).apply {
                                lineTo(pts.last().x, top + plotH); lineTo(pts.first().x, top + plotH); close()
                            }
                            drawPath(fill, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.28f), s.color.copy(alpha = 0.02f)), startY = top, endY = top + plotH))
                            drawPath(line, s.color, style = Stroke(width = px(2.dp)))
                            selected?.let { i ->
                                val p = pts[i]
                                drawCircle(Color.White, radius = px(7.dp), center = p)
                                drawCircle(s.color, radius = px(5.dp), center = p)
                            }
                        }
                    }
                }
                // x axis labels
                if (n > 0) {
                    val layouts = data.map { measurer.measure(it.str(labelKey), axisStyle) }
                    val labelW = layouts.maxOf { it.size.width }.toFloat()
                    val spacing = if (kind == ChartKind.Area && n > 1) plotW / (n - 1) else band
                    labelIndices(n, spacing, labelW).forEach { i ->
                        val tl = layouts[i]
                        val cx = centerOf(i)
                        val x = (cx - tl.size.width / 2f).coerceIn(0f, w - tl.size.width)
                        drawText(tl, topLeft = Offset(x, top + plotH + px(6.dp)))
                    }
                }
            }

            selected?.let { i ->
                if (i < n) {
                    val r = data[i]
                    val tipW = px(188.dp)
                    val cx = centerOf(i)
                    val gap = px(12.dp)
                    val x = if (cx + gap + tipW <= w) cx + gap else (cx - gap - tipW).coerceAtLeast(0f)
                    ChartTooltip(
                        r.str(labelKey),
                        series.map { s -> TipLine(s.name, s.color, r.num(s.key)) },
                        extra?.invoke(r),
                        Modifier.offset { IntOffset(x.roundToInt(), top.roundToInt()) },
                    )
                }
            }
        }
        if (legend) Legend(series)
    }
}

@Composable
fun ReleasesChart(data: List<Row>) = CategoryChart(
    data, listOf(ChartSeries("released", "Released", SERIES.blue)), ChartKind.Bars,
    extra = { p -> val n = p.int("loans_released"); "$n loan${if (n == 1) "" else "s"} released" },
)

@Composable
fun CollectionsChart(data: List<Row>) = CategoryChart(
    data, listOf(ChartSeries("collected", "Collected", SERIES.teal)), ChartKind.Area,
)

@Composable
fun ComponentsChart(data: List<Row>) = CategoryChart(
    data,
    listOf(
        ChartSeries("principal_collected", "Principal", SERIES.blue),
        ChartSeries("interest_collected", "Interest", SERIES.teal),
        ChartSeries("other_collected", "Charges & penalties", SERIES.yellow),
    ),
    ChartKind.Stacked, height = 236.dp, legend = true,
)

@Composable
fun PerformanceChart(data: List<Row>) = CategoryChart(
    data,
    listOf(ChartSeries("scheduled_due", "Scheduled due", SERIES.violet), ChartSeries("paid_on_due", "Paid toward it", SERIES.teal)),
    ChartKind.Bars, height = 236.dp, maxBar = 18.dp, legend = true,
    extra = { p ->
        val due = p.num("scheduled_due"); val paid = p.num("paid_on_due")
        if (due > 0) "Collection rate: ${String.format(java.util.Locale.US, "%.1f", paid / due * 100)}%" else "Nothing scheduled"
    },
)

val BUCKET_ORDER = listOf("Current", "1–30 days", "31–60 days", "61–90 days", "90+ days")

/** Horizontal bars of outstanding principal per ageing bucket. */
@Composable
fun AgingChart(data: List<Row>) {
    val full = BUCKET_ORDER.map { b -> data.firstOrNull { it.str("bucket") == b }?.let { Triple(b, it.int("loans"), it.num("outstanding_principal")) } ?: Triple(b, 0, 0.0) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var selected by remember(data) { mutableStateOf<Int?>(null) }
    val axisStyle = TextStyle(fontSize = 11.sp, color = AXIS)
    BoxWithConstraints(Modifier.fillMaxWidth().height(230.dp)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val px = { d: Dp -> with(density) { d.toPx() } }
        val left = px(84.dp); val right = px(16.dp); val top = px(4.dp); val bottom = px(22.dp)
        val plotW = (w - left - right).coerceAtLeast(1f)
        val plotH = (h - top - bottom).coerceAtLeast(1f)
        val n = full.size
        val band = plotH / n
        val ticks = niceTicks(full.maxOf { it.third })
        val topV = ticks.last().coerceAtLeast(1.0)
        fun xOf(v: Double) = left + plotW * (v / topV).toFloat()
        Canvas(
            Modifier.fillMaxSize().pointerInput(data, w, h) {
                detectTapGestures { o ->
                    if (o.y < top || o.y > top + plotH) { selected = null; return@detectTapGestures }
                    val i = ((o.y - top) / band).toInt().coerceIn(0, n - 1)
                    selected = if (selected == i) null else i
                }
            },
        ) {
            ticks.forEachIndexed { ti, t ->
                val x = xOf(t)
                drawLine(GRID, Offset(x, top), Offset(x, top + plotH), strokeWidth = 1f)
                val tl = measurer.measure(compactPeso(t), axisStyle)
                // thin out x ticks on narrow phones
                if (ticks.size <= 5 || ti % 2 == 0 || ti == ticks.size - 1) {
                    drawText(tl, topLeft = Offset((x - tl.size.width / 2f).coerceIn(0f, w - tl.size.width), top + plotH + px(6.dp)))
                }
            }
            selected?.let { i -> drawRect(CURSOR, Offset(left, top + band * i), Size(plotW, band)) }
            val barH = min(px(22.dp), band * 0.8f)
            full.forEachIndexed { i, b ->
                val y = top + band * i + (band - barH) / 2
                roundedBar(AGING_RAMP[i], left, y, xOf(b.third) - left, barH, px(4.dp), horizontal = true)
                val tl = measurer.measure(b.first, axisStyle)
                drawText(tl, topLeft = Offset(left - 6f - tl.size.width, top + band * i + band / 2 - tl.size.height / 2f))
            }
        }
        selected?.let { i ->
            val b = full[i]
            val tipW = px(188.dp)
            val y = top + band * i + band / 2
            val x = (left + plotW / 2 - tipW / 2).coerceAtLeast(0f)
            val tipY = if (y > h / 2) y - px(90.dp) else y + px(14.dp)
            ChartTooltip(
                b.first, listOf(TipLine("Outstanding principal", AGING_RAMP[i], b.third)), "${b.second} loan${if (b.second == 1) "" else "s"}",
                Modifier.offset { IntOffset(x.roundToInt(), tipY.coerceAtLeast(0f).roundToInt()) },
            )
        }
    }
}

@Suppress("unused") private fun keepAbs(v: Double) = abs(v)
