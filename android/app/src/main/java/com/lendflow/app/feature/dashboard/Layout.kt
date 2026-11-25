package com.lendflow.app.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Breakpoints measured on the screen's content width (the web's sm / lg / xl viewport breakpoints,
 * minus the sidebar), so a tablet with the sidebar open lays out like the web at the same width.
 */
class Breaks(val width: Dp) {
    val sm: Boolean get() = width >= 560.dp
    val lg: Boolean get() = width >= 720.dp
    val xl: Boolean get() = width >= 960.dp
}

val LocalBreaks = compositionLocalOf { Breaks(400.dp) }

/** Measures the content width once and provides [LocalBreaks] to the screen. */
@Composable
fun ResponsiveScreen(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalBreaks provides Breaks(maxWidth)) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

/** Grid of [count] cells in [columns] equal columns (CSS grid-cols-n). */
@Composable
fun GridRows(columns: Int, count: Int, gap: Dp = 12.dp, cell: @Composable (index: Int, modifier: Modifier) -> Unit) {
    val cols = columns.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        (0 until count).chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
                row.forEach { i -> Box(Modifier.weight(1f)) { cell(i, Modifier.fillMaxWidth()) } }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Two cells side by side with the given weights when [wide], stacked otherwise (grid-cols-[1fr_1.4fr]). */
@Composable
fun SplitRow(wide: Boolean, leftWeight: Float, rightWeight: Float, gap: Dp = 16.dp, left: @Composable () -> Unit, right: @Composable () -> Unit) {
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(leftWeight)) { left() }
            Box(Modifier.weight(rightWeight)) { right() }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) { left(); right() }
    }
}
