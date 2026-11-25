package com.lendflow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Opt
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.parseDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Label + control + error/hint (Field on the web). */
@Composable
fun Field(
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    error: String? = null,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Foreground)
            if (required) Text(" *", fontSize = 14.sp, color = LF.Red500)
        }
        Spacer(Modifier.height(6.dp))
        content()
        if (error != null) Text(error, fontSize = 12.sp, color = LF.Red600, modifier = Modifier.padding(top = 4.dp))
        else if (hint != null) Text(hint, fontSize = 12.sp, color = LF.MutedForeground, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
    disabledContainerColor = LF.Muted,
    focusedBorderColor = LF.Blue,
    unfocusedBorderColor = LF.Input,
    disabledBorderColor = LF.Border,
    errorBorderColor = LF.Red500,
    cursorColor = LF.Blue,
)

/** Text input with the web styling. */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    isError: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    prefix: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        placeholder = placeholder?.let { { Text(it, color = LF.Slate400, fontSize = 14.sp) } },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = LF.Foreground),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (singleLine) imeAction else ImeAction.Default),
        keyboardActions = KeyboardActions(onAny = { onImeAction?.invoke() }),
        isError = isError,
        leadingIcon = leading,
        trailingIcon = trailing,
        prefix = prefix?.let { { Text(it, color = LF.MutedForeground) } },
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(8.dp),
        colors = fieldColors(),
    )
}

@Composable
fun PasswordInput(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String? = null, imeAction: ImeAction = ImeAction.Done, onImeAction: (() -> Unit)? = null) {
    var show by remember { mutableStateOf(false) }
    TextInput(
        value, onValueChange, modifier, placeholder = placeholder, keyboardType = KeyboardType.Password, imeAction = imeAction, onImeAction = onImeAction,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (show) "Hide password" else "Show password", tint = LF.MutedForeground)
            }
        },
    )
}

/** Keep digits and one decimal point (max 2 decimals), like cleanMoney() on the web. */
fun cleanMoney(text: String): String {
    var s = text.filter { it.isDigit() || it == '.' }
    val dot = s.indexOf('.')
    if (dot != -1) s = s.substring(0, dot + 1) + s.substring(dot + 1).replace(".", "").take(2)
    s = s.replace(Regex("^0+(?=\\d)"), "")
    return s.take(15)
}

fun groupMoney(raw: String): String {
    if (raw.isEmpty()) return ""
    val parts = raw.split('.')
    val int = parts[0].ifEmpty { "0" }
    val grouped = int.reversed().chunked(3).joinToString(",").reversed()
    return if (parts.size == 1) grouped else "$grouped.${parts[1]}"
}

/**
 * Peso amount field: decimal keypad, thousands separators while typing, reports the plain number
 * string ("20000.5", "" when empty).
 */
@Composable
fun MoneyInput(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String? = "0.00", enabled: Boolean = true, isError: Boolean = false) {
    var tfv by remember { mutableStateOf(TextFieldValue(groupMoney(cleanMoney(value)))) }
    LaunchedEffect(value) {
        if (cleanMoney(tfv.text) != cleanMoney(value)) {
            val g = groupMoney(cleanMoney(value))
            tfv = TextFieldValue(g, TextRange(g.length))
        }
    }
    OutlinedTextField(
        value = tfv,
        onValueChange = { v ->
            val raw = cleanMoney(v.text)
            val g = groupMoney(raw)
            tfv = TextFieldValue(g, TextRange(g.length))
            onValueChange(raw)
        },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        isError = isError,
        prefix = { Text("₱", color = LF.MutedForeground) },
        placeholder = placeholder?.let { { Text(it, color = LF.Slate400) } },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = LF.Foreground),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        shape = RoundedCornerShape(8.dp),
        colors = fieldColors(),
    )
}

/** Whole-number / decimal input (rates, terms, days). */
@Composable
fun NumberInput(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = true, placeholder: String? = null, suffix: String? = null, enabled: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = { t -> onValueChange(t.filter { it.isDigit() || (decimal && it == '.') }) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        placeholder = placeholder?.let { { Text(it, color = LF.Slate400) } },
        suffix = suffix?.let { { Text(it, color = LF.MutedForeground) } },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = LF.Foreground),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number, imeAction = ImeAction.Next),
        shape = RoundedCornerShape(8.dp),
        colors = fieldColors(),
    )
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search…") {
    TextInput(
        value, onValueChange, modifier, placeholder = placeholder, imeAction = ImeAction.Search,
        leading = { Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = LF.MutedForeground) },
        trailing = if (value.isNotEmpty()) ({
            IconButton(onClick = { onValueChange("") }) { Icon(Icons.Default.Close, "Clear search", Modifier.size(16.dp), tint = LF.MutedForeground) }
        }) else null,
    )
}

/** Dropdown select (NativeSelect on the web). */
@Composable
fun SelectField(
    value: String?,
    options: List<Opt>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select…",
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.value == value }?.label
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp))
                .background(if (enabled) Color.White else LF.Muted)
                .border(1.dp, LF.Input, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label ?: placeholder, Modifier.weight(1f), fontSize = 15.sp, color = if (label == null) LF.Slate400 else LF.Foreground, maxLines = 2)
            Icon(Icons.Default.ExpandMore, null, tint = LF.MutedForeground)
        }
        DropdownMenu(open, { open = false }, properties = PopupProperties(focusable = true), modifier = Modifier.background(Color.White)) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o.label, fontSize = 14.sp, fontWeight = if (o.value == value) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { open = false; onSelect(o.value) },
                )
            }
        }
    }
}

/** String list convenience (value == label). */
@Composable
fun SelectText(value: String?, options: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Select…", enabled: Boolean = true) {
    SelectField(value, options.map { Opt(it, it) }, onSelect, modifier, placeholder, enabled)
}

/** YYYY-MM-DD date field with a Material date picker. Empty string = no date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select date",
    enabled: Boolean = true,
    clearable: Boolean = false,
    minDate: String? = null,
    maxDate: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Color.White else LF.Muted)
            .border(1.dp, LF.Input, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { open = true }
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp), tint = LF.MutedForeground)
        Spacer(Modifier.width(10.dp))
        Text(if (value.isBlank()) placeholder else fmtDate(value), Modifier.weight(1f), fontSize = 15.sp, color = if (value.isBlank()) LF.Slate400 else LF.Foreground)
        if (clearable && value.isNotBlank() && enabled) {
            IconButton(onClick = { onValueChange("") }) { Icon(Icons.Default.Close, "Clear date", Modifier.size(16.dp), tint = LF.MutedForeground) }
        }
    }
    if (open) {
        val initial = parseDate(value)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val minMs = parseDate(minDate)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val maxMs = parseDate(maxDate)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial,
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    (minMs == null || utcTimeMillis >= minMs) && (maxMs == null || utcTimeMillis <= maxMs)
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms -> onValueChange(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
fun CheckboxRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, description: String? = null, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled) { onCheckedChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked, onCheckedChange, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = LF.Navy))
        Column(Modifier.padding(top = 12.dp, end = 4.dp)) {
            Text(label, fontSize = 14.sp, color = LF.Foreground)
            if (description != null) Text(description, fontSize = 12.sp, color = LF.MutedForeground)
        }
    }
}

@Composable
fun SwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, description: String? = null, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Foreground)
            if (description != null) Text(description, fontSize = 12.sp, color = LF.MutedForeground)
        }
        Switch(checked, onCheckedChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = LF.Navy))
    }
}

@Composable
fun RadioOptions(value: String?, options: List<Opt>, onSelect: (String) -> Unit, modifier: Modifier = Modifier, descriptions: Map<String, String> = emptyMap()) {
    Column(modifier) {
        options.forEach { o ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onSelect(o.value) }.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                RadioButton(o.value == value, { onSelect(o.value) }, colors = RadioButtonDefaults.colors(selectedColor = LF.Navy))
                Column(Modifier.padding(top = 12.dp)) {
                    Text(o.label, fontSize = 14.sp)
                    descriptions[o.value]?.let { Text(it, fontSize = 12.sp, color = LF.MutedForeground) }
                }
            }
        }
    }
}

/** Horizontal tab strip (Tabs on the web) — scrolls when the tabs do not fit. */
@Composable
fun Tabs(tabs: List<Opt>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(LF.Muted).padding(3.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tabs.forEach { t ->
            val sel = t.value == selected
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) Color.White else Color.Transparent)
                    .clickable { onSelect(t.value) }.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(t.label, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, color = if (sel) LF.Foreground else LF.MutedForeground, maxLines = 1)
            }
        }
    }
}

/** Filter chips row. */
@Composable
fun ChipRow(options: List<Opt>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val sel = o.value == selected
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (sel) LF.Navy else Color.White)
                    .border(1.dp, if (sel) LF.Navy else LF.Input, RoundedCornerShape(50))
                    .clickable { onSelect(o.value) }.padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text(o.label, fontSize = 13.sp, color = if (sel) Color.White else LF.Foreground, fontWeight = FontWeight.Medium) }
        }
    }
}

/** Two equal columns on tablets, stacked on phones. */
@Composable
fun FormRow(vararg cells: @Composable () -> Unit) {
    if (isCompact()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { cells.forEach { it() } }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            cells.forEach { c -> Box(Modifier.weight(1f)) { c() } }
        }
    }
}

@Suppress("unused") private fun keepLocalDate(d: LocalDate) = d
