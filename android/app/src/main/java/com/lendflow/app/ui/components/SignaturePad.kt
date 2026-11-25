package com.lendflow.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Paint
import android.graphics.Path as APath
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.ui.theme.LF
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Strokes drawn on a [SignaturePad]; exports a trimmed transparent PNG data URL like the web pad. */
@Stable
class SignatureState {
    internal val strokes = mutableStateListOf<List<Offset>>()
    internal val current = mutableStateListOf<Offset>()
    internal var size: IntSize = IntSize.Zero
    var onChange: ((Boolean) -> Unit)? = null

    val isEmpty: Boolean get() = strokes.isEmpty() && current.isEmpty()

    fun clear() { strokes.clear(); current.clear(); onChange?.invoke(true) }
    fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex); onChange?.invoke(isEmpty) }

    /** "data:image/png;base64,…" with transparent borders trimmed (12px padding), ink #0b1f44. */
    fun toDataUrl(): String? {
        if (strokes.isEmpty()) return null
        val all = strokes.flatten()
        val pad = 12f
        val stroke = 5f
        val minX = max(0f, all.minOf { it.x } - pad - stroke)
        val minY = max(0f, all.minOf { it.y } - pad - stroke)
        val maxX = min(size.width.toFloat().coerceAtLeast(1f), all.maxOf { it.x } + pad + stroke)
        val maxY = min(size.height.toFloat().coerceAtLeast(1f), all.maxOf { it.y } + pad + stroke)
        val w = (maxX - minX).toInt().coerceAtLeast(2)
        val h = (maxY - minY).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = ACanvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0b1f44")
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        strokes.forEach { pts ->
            val p = APath()
            pts.forEachIndexed { i, o -> if (i == 0) p.moveTo(o.x - minX, o.y - minY) else p.lineTo(o.x - minX, o.y - minY) }
            if (pts.size == 1) p.lineTo(pts[0].x - minX + 0.5f, pts[0].y - minY + 0.5f)
            c.drawPath(p, paint)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

@Composable
fun rememberSignatureState() = remember { SignatureState() }

/** Large touch-friendly signature area with Undo / Clear. */
@Composable
fun SignaturePad(state: SignatureState, modifier: Modifier = Modifier, height: Dp = 220.dp) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp)).background(Color.White)
                .border(2.dp, LF.Slate200, RoundedCornerShape(12.dp)),
        ) {
            if (state.isEmpty) {
                Text("Draw your signature here", Modifier.align(Alignment.Center), color = LF.Slate400, fontSize = 14.sp)
            }
            // signature line
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).align(Alignment.BottomCenter).padding(bottom = 48.dp).height(1.dp).background(LF.Slate200))
            Canvas(
                Modifier.fillMaxSize()
                    .onSizeChanged { state.size = it }
                    .pointerInput(Unit) {
                        detectTapGestures { o -> state.strokes.add(listOf(o)); state.onChange?.invoke(false) }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { o -> state.current.clear(); state.current.add(o) },
                            onDrag = { change, _ -> change.consume(); state.current.add(change.position) },
                            onDragEnd = {
                                if (state.current.isNotEmpty()) state.strokes.add(state.current.toList())
                                state.current.clear()
                                state.onChange?.invoke(false)
                            },
                            onDragCancel = { state.current.clear() },
                        )
                    },
            ) {
                val style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val ink = Color(0xFF0B1F44)
                (state.strokes + listOf(state.current.toList())).forEach { pts ->
                    if (pts.isEmpty()) return@forEach
                    if (pts.size == 1) {
                        drawCircle(ink, 1.8.dp.toPx(), pts[0])
                    } else {
                        val p = Path()
                        pts.forEachIndexed { i, o -> if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
                        drawPath(p, ink, style = style)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Undo", { state.undo() }, variant = BtnVariant.Outline, icon = Icons.AutoMirrored.Filled.Undo, small = true, enabled = !state.isEmpty)
            Btn("Clear", { state.clear() }, variant = BtnVariant.Outline, icon = Icons.Default.Delete, small = true, enabled = !state.isEmpty)
        }
    }
}
