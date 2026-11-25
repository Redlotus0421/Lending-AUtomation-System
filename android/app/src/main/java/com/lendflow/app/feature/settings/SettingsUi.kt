package com.lendflow.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.theme.LF
import kotlin.math.abs

/** A cell of [Grid]; [span] columns wide on tablets (sm:col-span-n). */
internal class GCell(val span: Int = 1, val content: @Composable () -> Unit)

internal fun cell(span: Int = 1, content: @Composable () -> Unit) = GCell(span, content)

/** Web `grid sm:grid-cols-n`: one column on phones, [columns] on tablets. */
@Composable
internal fun Grid(columns: Int, vararg cells: GCell, spacing: Dp = 14.dp) {
    if (isCompact() || columns <= 1) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) { cells.forEach { it.content() } }
        return
    }
    val rows = mutableListOf<MutableList<GCell>>()
    var used = 0
    for (c in cells) {
        val s = c.span.coerceIn(1, columns)
        if (rows.isEmpty() || used + s > columns) { rows.add(mutableListOf()); used = 0 }
        rows.last().add(c)
        used += s
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                r.forEach { c -> Box(Modifier.weight(c.span.coerceIn(1, columns).toFloat())) { c.content() } }
                val rest = columns - r.sumOf { it.span.coerceIn(1, columns) }
                if (rest > 0) Spacer(Modifier.weight(rest.toFloat()))
            }
        }
    }
}

/** Bordered switch card (Toggle in SettingsPage.tsx). */
@Composable
internal fun ToggleCard(label: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, LF.Border, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onChange(!checked) }.padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Foreground)
            if (description != null) Text(description, fontSize = 12.sp, color = LF.MutedForeground, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = LF.Navy))
    }
}

/** Compact checkbox + label (inline groups). */
@Composable
internal fun InlineCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled) { onChange(!checked) }.padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChange, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = LF.Navy))
        Text(label, fontSize = 14.sp, color = LF.Foreground)
    }
}

/** Radio option drawn as a bordered card (highlighted when selected). */
@Composable
internal fun RadioCard(selected: Boolean, label: String, description: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) LF.Blue50.copy(alpha = 0.5f) else Color.White)
            .border(1.dp, if (selected) LF.Blue else LF.Border, shape)
            .clickable(onClick = onClick).padding(end = 12.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = LF.Blue))
        Column(Modifier.padding(top = 10.dp)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Foreground)
            if (description != null) Text(description, fontSize = 12.sp, color = LF.MutedForeground)
        }
    }
}

/** Divider + right-aligned primary save button. */
@Composable
internal fun SaveBar(saving: Boolean, onSave: () -> Unit, dirty: Boolean = true, text: String = "Save changes") {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        HorizontalDivider(color = LF.Border)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            Btn(text, onSave, icon = Icons.Default.Save, loading = saving, enabled = !saving && dirty)
        }
    }
}

/** Small tinted note with an icon (bg-amber-50 / bg-blue-50 boxes). */
@Composable
internal fun InfoBox(text: String, icon: ImageVector, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg).padding(12.dp)) {
        Icon(icon, null, Modifier.size(16.dp).padding(top = 1.dp), tint = fg)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = fg)
    }
}

/** numberOk() of the web page: not blank, numeric, within range, optionally whole. */
internal fun numberOk(v: String, min: Double, max: Double, int: Boolean = false): Boolean {
    val n = v.trim().toDoubleOrNull() ?: return false
    return n >= min && n <= max && (!int || n % 1.0 == 0.0)
}

/** Sends whole numbers as integers (3 rather than 3.0), like JavaScript's Number(). */
internal fun jsNumber(d: Double): Number = if (d % 1.0 == 0.0 && abs(d) < 1e15) d.toLong() else d

internal fun jsNumber(s: String): Number = jsNumber(s.trim().toDoubleOrNull() ?: 0.0)
