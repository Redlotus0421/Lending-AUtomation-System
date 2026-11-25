package com.lendflow.app.feature.products

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.data.ProductsService
import com.lendflow.app.data.Row
import com.lendflow.app.data.TemplatesService
import com.lendflow.app.data.bool
import com.lendflow.app.data.has
import com.lendflow.app.data.num
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.feature.dashboard.GridRows
import com.lendflow.app.feature.dashboard.LocalBreaks
import com.lendflow.app.feature.dashboard.ResponsiveScreen
import com.lendflow.app.ui.components.AppDialog
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.ConfirmDialog
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.KV
import com.lendflow.app.ui.components.KeyValue
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.SkeletonRows
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.card
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.FREQUENCIES
import com.lendflow.app.util.FREQUENCY_LABEL
import com.lendflow.app.util.METHOD_LABEL
import com.lendflow.app.util.Opt
import com.lendflow.app.util.PENALTY_TYPES
import com.lendflow.app.util.RATE_PERIODS
import com.lendflow.app.util.Tone
import com.lendflow.app.util.peso
import com.lendflow.app.util.trimNumber

// Port of src/features/products/ProductsPage.tsx

private fun termUnit(freq: String?) = FREQUENCIES.firstOrNull { it.value == freq }?.unit ?: "installments"

private fun feeText(p: Row): String {
    if (p.str("processing_fee_type") == "none" || p.num("processing_fee_value") == 0.0) return "No processing fee"
    val v = if (p.str("processing_fee_type") == "percent") "${trimNumber(p.num("processing_fee_value"))}%" else peso(p.num("processing_fee_value"))
    return "$v (${if (p.str("charges_treatment") == "deducted") "deducted from proceeds" else "added to installments"})"
}

private fun penaltyText(p: Row): String {
    if (!p.bool("penalty_enabled")) return "No penalty"
    val type = p.strOrNull("penalty_type")
    val t = PENALTY_TYPES.firstOrNull { it.value == type }?.label ?: (type ?: "")
    val rate = if (type == "fixed") peso(p.num("penalty_rate")) else "${trimNumber(p.num("penalty_rate"))}%"
    val g = p.num("penalty_grace_days").toInt()
    return "$rate — $t; $g grace day${if (g == 1) "" else "s"}"
}

private fun truthy(p: Row, k: String) = p.has(k) && p.num(k) != 0.0

@Composable
fun ProductsScreen() {
    val auth by AppState.state.collectAsState()
    val canManage = auth.can("products.manage")
    val q = rememberLoad("products") { ProductsService.list(false) }
    var editing by remember { mutableStateOf<Row?>(null) }
    var creating by remember { mutableStateOf(false) }
    var toggle by remember { mutableStateOf<Row?>(null) }
    var showInactive by remember { mutableStateOf(true) }
    val rows = (q.data ?: emptyList()).filter { showInactive || it.bool("is_active") }

    ResponsiveScreen {
        val bp = LocalBreaks.current
        PageHeader(
            "Loan products",
            "Preset terms that pre-fill new loans. Each loan keeps its own frozen terms once created.",
            actions = if (canManage) ({ Btn("New product", { creating = true }, icon = Icons.Default.Add) }) else null,
        )
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Switch(showInactive, { showInactive = it }, colors = SwitchDefaults.colors(checkedTrackColor = LF.Navy))
            Spacer(Modifier.width(8.dp))
            Text("Show inactive", fontSize = 14.sp, color = LF.MutedForeground)
        }
        when {
            q.error != null && q.data == null -> ErrorState(q.error, q.reload)
            q.data == null -> SkeletonRows(4)
            rows.isEmpty() -> Box(Modifier.fillMaxWidth().card()) {
                EmptyState(
                    "No loan products", icon = Icons.Default.Inventory2,
                    description = "Create products to standardize rates, terms, fees and penalties.",
                    action = if (canManage) ({ Btn("New product", { creating = true }, icon = Icons.Default.Add) }) else null,
                )
            }
            else -> GridRows(if (bp.xl) 3 else if (bp.sm) 2 else 1, rows.size, gap = 16.dp) { i, m ->
                ProductCard(rows[i], m, canManage, onEdit = { editing = rows[i] }, onToggle = { toggle = rows[i] })
            }
        }
    }

    if (creating || editing != null) {
        ProductDialog(editing, onClose = { creating = false; editing = null })
    }
    toggle?.let { p ->
        val active = p.bool("is_active")
        ConfirmDialog(
            title = if (active) "Deactivate ${p.str("name")}?" else "Activate ${p.str("name")}?",
            message = if (active) "Inactive products cannot be selected for new loans. Existing loans are not affected." else "The product will be available for new loans.",
            confirmText = if (active) "Deactivate" else "Activate",
            destructive = active,
            successMessage = "Product ${if (active) "deactivated" else "activated"}",
            onDismiss = { toggle = null },
            onConfirm = { ProductsService.setActive(p.str("id"), !active) },
        )
    }
}

@Composable
private fun ProductCard(p: Row, modifier: Modifier, canManage: Boolean, onEdit: () -> Unit, onToggle: () -> Unit) {
    val active = p.bool("is_active")
    Column(modifier.card().alpha(if (active) 1f else 0.7f).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(p.str("name"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = LF.Foreground)
                Text(p.str("code"), fontSize = 12.sp, color = LF.MutedForeground)
            }
            Spacer(Modifier.width(8.dp))
            if (active) Pill("Active", Tone.green) else Pill("Inactive", Tone.slate)
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).clip(RoundedCornerShape(8.dp)).background(LF.Slate50).padding(12.dp)) {
            val period = RATE_PERIODS.firstOrNull { it.value == p.strOrNull("rate_period") }?.label ?: ""
            Text(rateText(trimNumber(p.num("interest_rate")), period))
            val freq = p.strOrNull("repayment_frequency")
            Text("${METHOD_LABEL[p.strOrNull("interest_method")] ?: ""} · paid ${FREQUENCY_LABEL[freq] ?: (freq ?: "")}", fontSize = 12.sp, color = LF.MutedForeground)
        }
        p.strOrNull("description")?.ifBlank { null }?.let { Text(it, Modifier.padding(bottom = 12.dp), fontSize = 14.sp, color = LF.MutedForeground) }
        val amount = if (truthy(p, "min_amount") || truthy(p, "max_amount"))
            "${if (truthy(p, "min_amount")) peso(p.num("min_amount"), 0) else "—"} – ${if (truthy(p, "max_amount")) peso(p.num("max_amount"), 0) else "—"}" else "Any"
        KeyValue(
            listOf(
                KV("Amount", amount),
                KV("Term", "${p.strOrNull("min_term") ?: "—"} – ${p.strOrNull("max_term") ?: "—"} ${termUnit(p.strOrNull("repayment_frequency"))}"),
                KV("Processing fee", feeText(p)),
                KV("Late payment", penaltyText(p)),
            ),
            columns = 2,
        )
        if (canManage) {
            HorizontalDivider(Modifier.padding(top = 16.dp), color = LF.Border)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Edit", onEdit, Modifier.weight(1f), variant = BtnVariant.Outline, icon = Icons.Default.Edit, small = true)
                Btn(if (active) "Deactivate" else "Activate", onToggle, Modifier.weight(1f), variant = BtnVariant.Ghost, icon = Icons.Default.PowerSettingsNew, small = true)
            }
        }
    }
}

private fun rateText(rate: String, period: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = LF.Navy)) { append("$rate%") }
    withStyle(SpanStyle(fontSize = 14.sp, color = LF.MutedForeground)) { append(" $period") }
}

// ---------------------------------------------------------------------------
// create / edit dialog (react-hook-form + zod schema on the web)
// ---------------------------------------------------------------------------
private fun s(p: Row?, k: String, default: String = ""): String = p?.strOrNull(k) ?: default

private class ProductForm(p: Row?) {
    var name by mutableStateOf(s(p, "name"))
    var code by mutableStateOf(s(p, "code"))
    var description by mutableStateOf(s(p, "description"))
    var interestMethod by mutableStateOf(s(p, "interest_method", "flat"))
    var interestRate by mutableStateOf(s(p, "interest_rate", "2"))
    var ratePeriod by mutableStateOf(s(p, "rate_period", "monthly"))
    var frequency by mutableStateOf(s(p, "repayment_frequency", "monthly"))
    var minAmount by mutableStateOf(s(p, "min_amount"))
    var maxAmount by mutableStateOf(s(p, "max_amount"))
    var minTerm by mutableStateOf(s(p, "min_term"))
    var maxTerm by mutableStateOf(s(p, "max_term"))
    var defaultTerm by mutableStateOf(s(p, "default_term", "12"))
    var feeType by mutableStateOf(s(p, "processing_fee_type", "none"))
    var feeValue by mutableStateOf(s(p, "processing_fee_value", "0"))
    var chargesTreatment by mutableStateOf(s(p, "charges_treatment", "deducted"))
    var dayCount by mutableStateOf(s(p, "day_count", "30/360"))
    var penaltyEnabled by mutableStateOf(if (p != null && p.has("penalty_enabled")) p.bool("penalty_enabled") else true)
    var penaltyType by mutableStateOf(s(p, "penalty_type", "percent_monthly"))
    var penaltyRate by mutableStateOf(s(p, "penalty_rate", "3"))
    var graceDays by mutableStateOf(s(p, "penalty_grace_days", "3"))
    var templateId by mutableStateOf(s(p, "agreement_template_id"))
    var isActive by mutableStateOf(if (p != null && p.has("is_active")) p.bool("is_active") else true)
}

private fun parseNum(v: String): Double? = v.trim().replace(",", "").let { if (it.isEmpty()) null else it.toDoubleOrNull() }

/** zod `num()` refinement: '' passes; otherwise must parse and satisfy the bounds. */
private fun numOk(v: String, min: Double? = null, max: Double? = null, int: Boolean = false): Boolean {
    val t = v.trim()
    if (t.isEmpty()) return true
    val x = parseNum(t) ?: return false
    if (int && x % 1.0 != 0.0) return false
    if (min != null && x < min) return false
    if (max != null && x > max) return false
    return true
}

private fun validate(f: ProductForm): Map<String, String> {
    val e = linkedMapOf<String, String>()
    val name = f.name.trim(); val code = f.code.trim()
    if (name.length < 2) e["name"] = "Name is required"
    when {
        code.length < 2 -> e["code"] = "Code is required"
        code.length > 30 -> e["code"] = "String must contain at most 30 character(s)"
        !Regex("^[A-Za-z0-9\\- _]+$").matches(code) -> e["code"] = "Letters, numbers and dashes only"
    }
    if (!numOk(f.interestRate, 0.0, 100.0)) e["interest_rate"] = "Enter a rate between 0 and 100"
    else if (f.interestRate.trim().isEmpty()) e["interest_rate"] = "Interest rate is required"
    if (!numOk(f.minAmount, 0.01)) e["min_amount"] = "Enter a positive amount"
    if (!numOk(f.maxAmount, 0.01)) e["max_amount"] = "Enter a positive amount"
    if (!numOk(f.minTerm, 1.0, int = true)) e["min_term"] = "Whole number ≥ 1"
    if (!numOk(f.maxTerm, 1.0, int = true)) e["max_term"] = "Whole number ≥ 1"
    if (!numOk(f.defaultTerm, 1.0, int = true)) e["default_term"] = "Whole number ≥ 1"
    if (!numOk(f.feeValue, 0.0)) e["processing_fee_value"] = "Enter a non-negative value"
    if (!numOk(f.penaltyRate, 0.0)) e["penalty_rate"] = "Enter a non-negative value"
    if (!numOk(f.graceDays, 0.0, int = true)) e["penalty_grace_days"] = "Whole number ≥ 0"
    if (e.isNotEmpty()) return e
    // object-level refinements (zod runs them only once every field is valid)
    val minA = parseNum(f.minAmount); val maxA = parseNum(f.maxAmount)
    if (minA != null && maxA != null && maxA < minA) e["max_amount"] = "Maximum must be ≥ minimum"
    val minT = parseNum(f.minTerm); val maxT = parseNum(f.maxTerm); val defT = parseNum(f.defaultTerm)
    if (minT != null && maxT != null && maxT < minT) e["max_term"] = "Maximum must be ≥ minimum"
    if (defT != null && ((minT != null && defT < minT) || (maxT != null && defT > maxT))) e["default_term"] = "Default must be within the min/max term"
    if (f.feeType == "percent" && (parseNum(f.feeValue) ?: 0.0) > 100) e["processing_fee_value"] = "Percentage cannot exceed 100%"
    if (f.penaltyEnabled && f.penaltyType.isBlank()) e["penalty_type"] = "Choose a penalty type"
    if (f.penaltyEnabled && (parseNum(f.penaltyRate) ?: 0.0) <= 0) e["penalty_rate"] = "Enter the penalty rate or amount"
    return e
}

/** Shows the code in capitals like the web's `uppercase` class (the value itself is saved as typed). */
private object UppercaseTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(AnnotatedString(text.text.uppercase()), OffsetMapping.Identity)
}

@Composable
private fun ProductDialog(product: Row?, onClose: () -> Unit) {
    val templates = rememberLoad("templates") { TemplatesService.list() }
    val f = remember(product) { ProductForm(product) }
    var submitted by remember(product) { mutableStateOf(false) }
    val runner = rememberRunner()
    val errors = if (submitted) validate(f) else emptyMap()
    val unit = termUnit(f.frequency)
    val wide = widthClass() != WidthClass.Compact

    fun submit() {
        submitted = true
        if (validate(f).isNotEmpty()) return
        runner.run(success = if (product != null) "Product updated" else "Product created") {
            val payload = mutableMapOf<String, Any?>(
                "name" to f.name.trim(), "code" to f.code.trim(), "description" to f.description.trim().ifBlank { null },
                "interest_method" to f.interestMethod, "interest_rate" to (f.interestRate.trim().toDoubleOrNull() ?: 0.0), "rate_period" to f.ratePeriod,
                "repayment_frequency" to f.frequency,
                "min_amount" to parseNum(f.minAmount), "max_amount" to parseNum(f.maxAmount),
                "min_term" to parseNum(f.minTerm)?.toInt(), "max_term" to parseNum(f.maxTerm)?.toInt(), "default_term" to parseNum(f.defaultTerm)?.toInt(),
                "processing_fee_type" to f.feeType, "processing_fee_value" to if (f.feeType == "none") 0.0 else (parseNum(f.feeValue) ?: 0.0),
                "charges_treatment" to f.chargesTreatment, "day_count" to f.dayCount,
                "penalty_enabled" to f.penaltyEnabled, "penalty_type" to if (f.penaltyEnabled) f.penaltyType.ifBlank { null } else null,
                "penalty_rate" to if (f.penaltyEnabled) (parseNum(f.penaltyRate) ?: 0.0) else 0.0,
                "penalty_grace_days" to (parseNum(f.graceDays)?.toInt() ?: 0),
                "agreement_template_id" to f.templateId.ifBlank { null }, "is_active" to f.isActive,
            )
            if (product != null) payload["id"] = product.str("id")
            ProductsService.save(payload)
            onClose()
        }
    }

    @Composable
    fun Grid(cols: Int, vararg cells: @Composable () -> Unit) {
        val c = if (wide) cols else 1
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            cells.toList().chunked(c).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    row.forEach { cell -> Box(Modifier.weight(1f)) { cell() } }
                    repeat(c - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    fun H3(text: String) = Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LF.Foreground)

    AppDialog(
        title = if (product != null) "Edit ${product.str("name")}" else "New loan product",
        onDismiss = { if (!runner.busy) onClose() },
        description = "Changes apply to new loans only. Existing loans keep the terms they were created with.",
        maxWidth = 720.dp,
        dismissible = !runner.busy,
        footer = {
            Btn("Cancel", onClose, variant = BtnVariant.Outline, enabled = !runner.busy)
            Btn(if (product != null) "Save changes" else "Create product", { submit() }, loading = runner.busy)
        },
    ) {
        Grid(2,
            { Field("Product name", required = true, error = errors["name"]) { TextInput(f.name, { f.name = it }) } },
            { Field("Code", required = true, error = errors["code"], hint = "Unique short code, e.g. PERSONAL") {
                TextInput(f.code, { f.code = it }, visualTransformation = UppercaseTransformation) } },
        )
        Field("Description") { TextInput(f.description, { f.description = it }, singleLine = false, minLines = 2) }

        H3("Interest & repayment")
        Grid(3,
            { Field("Interest method") {
                SelectField(f.interestMethod, listOf(Opt("flat", "Flat interest"), Opt("diminishing", "Diminishing balance")), { f.interestMethod = it }) } },
            { Field("Interest rate (%)", required = true, error = errors["interest_rate"]) {
                TextInput(f.interestRate, { f.interestRate = it }, keyboardType = KeyboardType.Decimal) } },
            { Field("Rate period", hint = "Nominal rate per period") { SelectField(f.ratePeriod, RATE_PERIODS, { f.ratePeriod = it }) } },
            { Field("Repayment frequency") { SelectField(f.frequency, FREQUENCIES.map { Opt(it.value, it.label) }, { f.frequency = it }) } },
            { Field("Day-count basis", hint = "Used for daily rates & actual-day accrual") {
                SelectField(f.dayCount, listOf(Opt("30/360", "30/360 (periodic)"), Opt("actual/365", "Actual/365"), Opt("actual/actual", "Actual/actual (leap-year aware)")), { f.dayCount = it }) } },
            { Field("Agreement template") {
                SelectField(f.templateId, listOf(Opt("", "Organization default")) + (templates.data ?: emptyList()).filter { it.bool("is_active") }.map { Opt(it.str("id"), it.str("name")) },
                    { f.templateId = it }) } },
        )

        H3("Limits")
        Grid(3,
            { Field("Minimum amount (₱)", error = errors["min_amount"]) { TextInput(f.minAmount, { f.minAmount = it }, keyboardType = KeyboardType.Decimal) } },
            { Field("Maximum amount (₱)", error = errors["max_amount"]) { TextInput(f.maxAmount, { f.maxAmount = it }, keyboardType = KeyboardType.Decimal) } },
            { },
            { Field("Minimum term ($unit)", error = errors["min_term"]) { TextInput(f.minTerm, { f.minTerm = it }, keyboardType = KeyboardType.Number) } },
            { Field("Maximum term ($unit)", error = errors["max_term"]) { TextInput(f.maxTerm, { f.maxTerm = it }, keyboardType = KeyboardType.Number) } },
            { Field("Default term ($unit)", error = errors["default_term"]) { TextInput(f.defaultTerm, { f.defaultTerm = it }, keyboardType = KeyboardType.Number) } },
        )

        H3("Processing fee")
        Grid(3,
            { Field("Fee type") {
                SelectField(f.feeType, listOf(Opt("none", "No fee"), Opt("percent", "Percentage of principal"), Opt("fixed", "Fixed amount")), { f.feeType = it }) } },
            { Field(if (f.feeType == "percent") "Fee (%)" else "Fee (₱)", error = errors["processing_fee_value"]) {
                TextInput(f.feeValue, { f.feeValue = it }, keyboardType = KeyboardType.Decimal, enabled = f.feeType != "none") } },
            { Field("Charges treatment") {
                SelectField(f.chargesTreatment, listOf(Opt("deducted", "Deducted from proceeds"), Opt("added", "Added to installments")), { f.chargesTreatment = it }) } },
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { H3("Late payment penalty") }
            Switch(f.penaltyEnabled, { f.penaltyEnabled = it }, colors = SwitchDefaults.colors(checkedTrackColor = LF.Navy))
            Spacer(Modifier.width(8.dp))
            Text("Enabled", fontSize = 14.sp)
        }
        if (f.penaltyEnabled) {
            Field("Penalty type", error = errors["penalty_type"]) {
                SelectField(f.penaltyType, listOf(Opt("", "Select")) + PENALTY_TYPES, { f.penaltyType = it })
            }
            Grid(3,
                { Field(if (f.penaltyType == "fixed") "Fee per late installment (₱)" else "Rate (%)", error = errors["penalty_rate"]) {
                    TextInput(f.penaltyRate, { f.penaltyRate = it }, keyboardType = KeyboardType.Decimal) } },
                { Field("Grace period (days)", error = errors["penalty_grace_days"]) {
                    TextInput(f.graceDays, { f.graceDays = it }, keyboardType = KeyboardType.Number) } },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(f.isActive, { f.isActive = it }, colors = SwitchDefaults.colors(checkedTrackColor = LF.Navy))
            Spacer(Modifier.width(8.dp))
            Text("Product is active", fontSize = 14.sp)
        }
    }
}
