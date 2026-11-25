package com.lendflow.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.EmptyRow
import com.lendflow.app.data.OrgService
import com.lendflow.app.data.Row
import com.lendflow.app.data.TemplatesService
import com.lendflow.app.data.bool
import com.lendflow.app.data.int
import com.lendflow.app.data.jsonOf
import com.lendflow.app.data.numbers
import com.lendflow.app.data.obj
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.strings
import com.lendflow.app.feature.agreements.TemplateEditor
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.ConfirmDialog
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.NumberInput
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.PageLoader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Runner
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.Tabs
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.rememberFilePicker
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.FREQUENCIES
import com.lendflow.app.util.Opt
import com.lendflow.app.util.PENALTY_TYPES
import com.lendflow.app.util.RATE_PERIODS

// Port of src/features/settings/SettingsPage.tsx

private fun Runner.saveSection(section: String, values: Map<String, Any?>) =
    run(success = "Settings saved") {
        OrgService.updateSettings(section, values)
        AppState.refresh()
    }

/** The section's current values with [changes] applied (the web spreads `{ ...v, ... }`). */
private fun Row.merged(vararg changes: Pair<String, Any?>): Map<String, Any?> =
    LinkedHashMap<String, Any?>(this).apply { changes.forEach { (k, v) -> put(k, v) } }

private fun numText(d: Double): String = jsNumber(d).toString()

@Composable
fun SettingsScreen() {
    val auth by AppState.state.collectAsState()
    val canSettings = auth.can("settings.manage")
    if (!canSettings) {
        Column(Modifier.fillMaxWidth()) {
            PageHeader("Settings", "Your role can manage the terms (agreement) templates.")
            TermsTemplateSection()
        }
        return
    }
    val q = rememberLoad { OrgService.getSettings() }
    val s = q.data
    if (s == null) {
        if (q.error != null) ErrorState(q.error, q.reload) else PageLoader()
        return
    }

    val tabs = buildList {
        add(Opt("company", "Company"))
        add(Opt("interest", "Interest"))
        add(Opt("repayment", "Repayment"))
        add(Opt("penalty", "Late payments"))
        add(Opt("agreement", "Agreements"))
        if (auth.can("templates.manage")) add(Opt("terms", "Terms template"))
        add(Opt("simplified", "Simplified lending"))
        add(Opt("receipt", "Receipts"))
        add(Opt("security", "Security"))
        if (auth.role == "owner") add(Opt("data", "Sample data"))
    }
    var tab by rememberSaveable { mutableStateOf("company") }
    if (tabs.none { it.value == tab }) tab = "company"

    Column(Modifier.fillMaxWidth()) {
        PageHeader("Settings", "Company profile and the lending rules applied to new loans. Existing loans keep their frozen terms.")
        Tabs(tabs, tab, { tab = it })
        Spacer(Modifier.padding(top = 16.dp))
        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
            when (tab) {
                "company" -> CompanySection()
                "interest" -> InterestSection(s.obj("interest") ?: EmptyRow)
                "repayment" -> RepaymentSection(s.obj("repayment") ?: EmptyRow)
                "penalty" -> PenaltySection(s.obj("penalty") ?: EmptyRow)
                "agreement" -> AgreementSection(s.obj("agreement") ?: EmptyRow)
                "terms" -> TermsTemplateSection()
                "simplified" -> SimplifiedSection(
                    s.obj("simplified") ?: jsonOf(
                        "allow_rate_override" to false, "default_term" to 6, "processing_fee_type" to "none",
                        "processing_fee_value" to 0, "approve_on_create" to true,
                    ),
                    s,
                )
                "receipt" -> ReceiptSection(s.obj("receipt") ?: EmptyRow)
                "security" -> SecuritySection(s.obj("security") ?: EmptyRow)
                "data" -> SampleDataSection()
            }
        }
    }
}

// ---------------------------------------------------------------------------
private val TIN_RE = Regex("^\\d{3}-?\\d{3}-?\\d{3}(-?\\d{3,5})?$")
private val ZIP_RE = Regex("^\\d{4}$")
private val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

private val COMPANY_KEYS = listOf("name", "legal_name", "registration_no", "tin", "address", "city", "province", "zip_code", "contact_email", "contact_phone", "website")

@Composable
private fun CompanySection() {
    val auth by AppState.state.collectAsState()
    val organization = auth.organization ?: return
    val orgId = organization.str("id")
    val initial = remember(organization) { COMPANY_KEYS.associateWith { organization.str(it) } }
    var values by remember(organization) { mutableStateOf(initial) }
    var submitted by remember(organization) { mutableStateOf(false) }
    val runner = rememberRunner()
    val uploader = rememberRunner()
    var logoNonce by remember { mutableIntStateOf(0) }
    val logoPath = organization.strOrNull("logo_path")
    val logo = rememberLoad(logoPath, logoNonce, live = false) { logoPath?.let { OrgService.logoUrl(it) } }

    fun v(k: String) = values[k] ?: ""
    fun set(k: String, x: String) { values = values + (k to x) }
    val errors = buildMap {
        if (v("name").trim().length < 2) put("name", "Business name is required")
        v("tin").trim().let { if (it.isNotEmpty() && !TIN_RE.matches(it)) put("tin", "TIN format: 000-000-000-000") }
        v("zip_code").trim().let { if (it.isNotEmpty() && !ZIP_RE.matches(it)) put("zip_code", "ZIP codes have 4 digits") }
        v("contact_email").trim().let { if (it.isNotEmpty() && !EMAIL_RE.matches(it)) put("contact_email", "Enter a valid email") }
    }
    fun err(k: String) = if (submitted) errors[k] else null
    val dirty = values != initial

    val picker = rememberFilePicker { f ->
        if (!(f.mime ?: "").startsWith("image/")) { Toasts.error("Upload a PNG, JPG, WEBP or SVG image"); return@rememberFilePicker }
        if (f.size > 2 * 1024 * 1024) { Toasts.error("Logo must be 2 MB or smaller"); return@rememberFilePicker }
        uploader.run(success = "Logo updated") {
            OrgService.uploadLogo(orgId, f)
            AppState.refresh()
            logoNonce++
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Panel(title = "Company logo", description = "Printed on agreements and receipts (PNG or JPG recommended for PDFs).") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(LF.Slate50).border(1.dp, LF.Border, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val url = logo.data
                    if (url != null) AsyncImage(model = url, contentDescription = "Company logo", modifier = Modifier.fillMaxWidth().padding(4.dp), contentScale = ContentScale.Fit)
                    else Icon(Icons.Default.Business, null, Modifier.size(32.dp), tint = LF.Slate200)
                }
                Spacer(Modifier.width(16.dp))
                Btn(
                    if (logoPath != null) "Replace logo" else "Upload logo",
                    { picker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/svg+xml")) },
                    variant = BtnVariant.Outline, icon = Icons.Default.AddPhotoAlternate, loading = uploader.busy,
                )
            }
        }
        Panel(title = "Company profile") {
            Grid(
                2,
                cell { Field("Business / trade name", required = true, error = err("name")) { TextInput(v("name"), { set("name", it) }) } },
                cell { Field("Registered legal name", hint = "Shown as the lender on agreements") { TextInput(v("legal_name"), { set("legal_name", it) }) } },
                cell { Field("SEC registration / Certificate of Authority no.") { TextInput(v("registration_no"), { set("registration_no", it) }) } },
                cell { Field("TIN", error = err("tin")) { TextInput(v("tin"), { set("tin", it) }, placeholder = "000-000-000-000") } },
                cell(2) { Field("Business address") { TextInput(v("address"), { set("address", it) }) } },
                cell { Field("City / municipality", hint = "Also used as the venue for disputes") { TextInput(v("city"), { set("city", it) }) } },
                cell { Field("Province") { TextInput(v("province"), { set("province", it) }) } },
                cell { Field("ZIP code", error = err("zip_code")) { TextInput(v("zip_code"), { set("zip_code", it.take(4)) }, keyboardType = KeyboardType.Number) } },
                cell { Field("Contact number") { TextInput(v("contact_phone"), { set("contact_phone", it) }, keyboardType = KeyboardType.Phone) } },
                cell { Field("Contact email", error = err("contact_email")) { TextInput(v("contact_email"), { set("contact_email", it) }, keyboardType = KeyboardType.Email) } },
                cell { Field("Website") { TextInput(v("website"), { set("website", it) }, placeholder = "https://", keyboardType = KeyboardType.Uri) } },
                cell { Field("Currency") { TextInput("Philippine peso (PHP, ₱)", {}, enabled = false) } },
                cell { Field("Timezone") { TextInput("Asia/Manila (PHT, UTC+8)", {}, enabled = false) } },
            )
            SaveBar(runner.busy, {
                submitted = true
                if (errors.isNotEmpty()) return@SaveBar
                fun blank(k: String) = v(k).trim().ifEmpty { null }
                runner.run(success = "Company profile saved") {
                    OrgService.update(orgId, mapOf(
                        "name" to v("name").trim(), "legal_name" to blank("legal_name"), "registration_no" to blank("registration_no"),
                        "tin" to blank("tin"), "address" to blank("address"), "city" to blank("city"), "province" to blank("province"),
                        "zip_code" to blank("zip_code"), "contact_email" to blank("contact_email"), "contact_phone" to blank("contact_phone"),
                        "website" to blank("website"),
                    ))
                    AppState.refresh()
                }
            }, dirty = dirty, text = "Save profile")
        }
    }
}

// ---------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterestSection(value: Row) {
    val runner = rememberRunner()
    var flat by remember(value) { mutableStateOf(value.bool("flat_enabled")) }
    var dim by remember(value) { mutableStateOf(value.bool("diminishing_enabled")) }
    var method by remember(value) { mutableStateOf(value.str("default_method").ifBlank { "flat" }) }
    var rate by remember(value) { mutableStateOf(value.str("default_rate")) }
    var period by remember(value) { mutableStateOf(value.str("default_rate_period").ifBlank { "monthly" }) }
    var presets by remember(value) { mutableStateOf(value.numbers("rate_presets").joinToString(", ") { numText(it) }) }
    var periods by remember(value) { mutableStateOf(value.strings("rate_periods")) }
    var dayCount by remember(value) { mutableStateOf(value.str("day_count").ifBlank { "30/360" }) }
    var rounding by remember(value) { mutableStateOf(value.str("rounding").ifBlank { "centavo" }) }

    fun onSave() {
        if (!flat && !dim) return Toasts.error("Enable at least one interest method")
        if ((method == "flat" && !flat) || (method == "diminishing" && !dim)) return Toasts.error("The default method must be enabled")
        if (!numberOk(rate, 0.0, 100.0)) return Toasts.error("Default rate must be between 0 and 100")
        val list = presets.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toDoubleOrNull() }
        if (list.any { it == null || it < 0 || it > 100 }) return Toasts.error("Rate presets must be numbers between 0 and 100")
        if (periods.isEmpty()) return Toasts.error("Select at least one rate period")
        if (period !in periods) return Toasts.error("The default rate period must be one of the allowed periods")
        runner.saveSection("interest", value.merged(
            "flat_enabled" to flat, "diminishing_enabled" to dim, "default_method" to method,
            "default_rate" to jsNumber(rate), "default_rate_period" to period,
            "rate_presets" to list.filterNotNull().distinct().sorted().map { jsNumber(it) },
            "rate_periods" to periods, "day_count" to dayCount, "rounding" to rounding,
        ))
    }

    Panel(title = "Interest settings", description = "Defaults offered when creating a loan. Rates are nominal rates for the selected period.") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Grid(
                2,
                cell { ToggleCard("Flat interest", "Interest on the original principal for the whole term.", flat, { flat = it }) },
                cell { ToggleCard("Diminishing balance", "Interest on the outstanding principal each period.", dim, { dim = it }) },
            )
            Grid(
                3,
                cell {
                    Field("Default method") {
                        SelectField(method, listOf(Opt("flat", "Flat interest"), Opt("diminishing", "Diminishing balance")), { method = it })
                    }
                },
                cell { Field("Default rate (%)") { NumberInput(rate, { rate = it }) } },
                cell { Field("Default rate period") { SelectField(period, RATE_PERIODS, { period = it }) } },
            )
            Field("Quick-pick rates (%)", hint = "Comma-separated values shown as shortcuts in the loan wizard") {
                TextInput(presets, { presets = it })
            }
            Field("Allowed rate periods") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RATE_PERIODS.forEach { r ->
                        InlineCheck(r.label, r.value in periods, { c -> periods = if (c) periods + r.value else periods - r.value })
                    }
                }
            }
            Grid(
                2,
                cell {
                    Field("Day-count basis", hint = "30/360 treats months as equal periods; actual bases accrue on real calendar days (leap years included).") {
                        SelectField(dayCount, listOf(Opt("30/360", "30/360 (periodic)"), Opt("actual/365", "Actual/365"), Opt("actual/actual", "Actual/actual")), { dayCount = it })
                    }
                },
                cell {
                    Field("Installment rounding", hint = "Amounts are always stored to the centavo; the final installment absorbs rounding differences.") {
                        SelectField(rounding, listOf(Opt("centavo", "Round to the centavo (₱0.01)"), Opt("peso", "Round installments to the whole peso")), { rounding = it })
                    }
                },
            )
        }
        SaveBar(runner.busy, ::onSave)
    }
}

// ---------------------------------------------------------------------------
private val ALLOCATION_LABEL = mapOf("penalty" to "Penalties", "charges" to "Charges & fees", "interest" to "Interest", "principal" to "Principal")
private class SettlementRule(val value: String, val label: String, val desc: String)
private val SETTLEMENT_RULES = listOf(
    SettlementRule("prorated", "Prorated (recommended)", "Charge interest earned up to the settlement date: past-due interest in full plus a day-based share of the current period. Future interest is rebated."),
    SettlementRule("full_period", "Full current period", "Charge past-due interest plus the full interest of the current period. Later periods are rebated."),
    SettlementRule("due_only", "Due interest only", "Charge only interest of installments already due. The current period's interest is rebated."),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepaymentSection(value: Row) {
    val runner = rememberRunner()
    var frequencies by remember(value) { mutableStateOf(value.strings("frequencies")) }
    var defaultFrequency by remember(value) { mutableStateOf(value.str("default_frequency").ifBlank { "monthly" }) }
    var firstDueRule by remember(value) { mutableStateOf(value.str("first_due_rule").ifBlank { "one_period" }) }
    var graceDays by remember(value) { mutableStateOf(value.str("grace_days")) }
    var charges by remember(value) { mutableStateOf(value.str("charges_treatment").ifBlank { "deducted" }) }
    var separate by remember(value) { mutableStateOf(value.bool("require_separate_approver")) }
    var order by remember(value) { mutableStateOf(value.strings("allocation_order")) }
    var rule by remember(value) { mutableStateOf(value.str("early_settlement_rule").ifBlank { "prorated" }) }

    fun move(i: Int, d: Int) {
        val j = i + d
        if (j < 0 || j >= order.size) return
        order = order.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
    }
    fun onSave() {
        if (frequencies.isEmpty()) return Toasts.error("Select at least one repayment frequency")
        if (defaultFrequency !in frequencies) return Toasts.error("The default frequency must be one of the allowed frequencies")
        if (!numberOk(graceDays, 0.0, 60.0, int = true)) return Toasts.error("Grace days must be a whole number from 0 to 60")
        runner.saveSection("repayment", value.merged(
            "frequencies" to frequencies, "default_frequency" to defaultFrequency, "first_due_rule" to firstDueRule,
            "grace_days" to jsNumber(graceDays), "charges_treatment" to charges, "require_separate_approver" to separate,
            "allocation_order" to order, "early_settlement_rule" to rule,
        ))
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Panel(title = "Repayment schedule") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Field("Allowed repayment frequencies") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FREQUENCIES.forEach { f ->
                            InlineCheck(f.label, f.value in frequencies, { c -> frequencies = if (c) frequencies + f.value else frequencies - f.value })
                        }
                    }
                }
                Grid(
                    3,
                    cell { Field("Default frequency") { SelectField(defaultFrequency, FREQUENCIES.map { Opt(it.value, it.label) }, { defaultFrequency = it }) } },
                    cell { Field("First due date rule", hint = "Can be overridden per loan") { SelectField(firstDueRule, listOf(Opt("one_period", "One repayment period after release")), { firstDueRule = it }) } },
                    cell { Field("Payment grace period (days)", hint = "Informational; penalty grace is set under Late payments") { NumberInput(graceDays, { graceDays = it }, decimal = false) } },
                    cell(2) {
                        Field("Fees & charges") {
                            SelectField(charges, listOf(Opt("deducted", "Deduct from loan proceeds"), Opt("added", "Add to installments (financed)")), { charges = it })
                        }
                    },
                )
                ToggleCard("Require a separate approver", "The staff member who created a loan cannot approve it (owners are exempt).", separate, { separate = it })
            }
        }
        Panel(title = "Payment allocation order", description = "How each payment is applied within an installment, oldest installment first.") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                order.forEachIndexed { i, c ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, LF.Border, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(LF.Navy), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(ALLOCATION_LABEL[c] ?: c, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        IconButton(onClick = { move(i, -1) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Move up", Modifier.size(18.dp)) }
                        IconButton(onClick = { move(i, 1) }, enabled = i < order.size - 1) { Icon(Icons.Default.ArrowDownward, "Move down", Modifier.size(18.dp)) }
                    }
                }
            }
        }
        Panel(title = "Early settlement", description = "Unearned future interest is never charged automatically.") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SETTLEMENT_RULES.forEach { r -> RadioCard(rule == r.value, r.label, r.desc) { rule = r.value } }
            }
            SaveBar(runner.busy, ::onSave)
        }
    }
}

// ---------------------------------------------------------------------------
@Composable
private fun PenaltySection(value: Row) {
    val runner = rememberRunner()
    var enabled by remember(value) { mutableStateOf(value.bool("enabled")) }
    var type by remember(value) { mutableStateOf(value.str("type").ifBlank { "percent_monthly" }) }
    var rate by remember(value) { mutableStateOf(value.str("rate")) }
    var grace by remember(value) { mutableStateOf(value.str("grace_days")) }
    val isFixed = type == "fixed"
    fun onSave() {
        if (enabled) {
            if (!numberOk(rate, 0.0001, if (isFixed) 1_000_000.0 else 100.0)) return Toasts.error(if (isFixed) "Enter the penalty fee amount" else "Penalty rate must be between 0 and 100%")
            if (!numberOk(grace, 0.0, 90.0, int = true)) return Toasts.error("Grace period must be a whole number from 0 to 90")
        }
        runner.saveSection("penalty", value.merged("enabled" to enabled, "type" to type, "rate" to jsNumber(rate), "grace_days" to jsNumber(grace)))
    }
    Panel(title = "Late payment penalties", description = "Defaults for new loans; each loan product can override them. Penalties accrue only on the unpaid installment amount.") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ToggleCard("Charge late payment penalties", null, enabled, { enabled = it })
            if (enabled) {
                Grid(
                    3,
                    cell(3) { Field("Penalty type") { SelectField(type, PENALTY_TYPES, { type = it }) } },
                    cell { Field(if (isFixed) "Fee per late installment (₱)" else "Rate (%)") { NumberInput(rate, { rate = it }) } },
                    cell { Field("Grace period (days)", hint = "Days after the due date before penalties start") { NumberInput(grace, { grace = it }, decimal = false) } },
                )
            }
            InfoBox(
                "Late payment charges must be disclosed in the agreement and should be reviewed for compliance with applicable SEC rules on interest and penalty caps.",
                Icons.Default.Info, LF.Amber50, LF.Amber800,
            )
        }
        SaveBar(runner.busy, ::onSave)
    }
}

// ---------------------------------------------------------------------------
/** Edit the terms printed on every loan agreement, with a live preview (Settings -> Terms template). */
@Composable
private fun TermsTemplateSection() {
    val router = LocalRouter.current
    val templates = rememberLoad { TemplatesService.list() }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val list = templates.data
    if (list == null) {
        if (templates.error != null) ErrorState(templates.error, templates.reload) else PageLoader()
        return
    }
    val fallback = list.firstOrNull { it.bool("is_default") && it.bool("is_active") } ?: list.firstOrNull { it.bool("is_active") } ?: list.firstOrNull()
    val selected = picked ?: fallback?.str("id") ?: "new"
    val options = list.map { t ->
        Opt(t.str("id"), t.str("name") + (if (t.bool("is_default")) " (default)" else "") + (if (t.bool("is_active")) "" else " — inactive"))
    } + Opt("new", "+ New template")
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel {
            val picker: @Composable (Modifier) -> Unit = { m ->
                Field("Template to edit", m, hint = "The default template is used for new agreements unless a loan product names another one.") {
                    SelectField(selected, options, { picked = it })
                }
            }
            if (isCompact()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    picker(Modifier)
                    Btn("All templates", { router.go("/agreements/templates") }, variant = BtnVariant.Ghost, icon = Icons.AutoMirrored.Filled.OpenInNew)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    picker(Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Btn("All templates", { router.go("/agreements/templates") }, variant = BtnVariant.Ghost, icon = Icons.AutoMirrored.Filled.OpenInNew)
                }
            }
        }
        key(selected) {
            TemplateEditor(id = selected, embedded = true, onSaved = { id -> picked = id })
        }
    }
}

@Composable
private fun TemplatesLinkCard() {
    val router = LocalRouter.current
    Panel {
        val text: @Composable (Modifier) -> Unit = { m ->
            Row(m, verticalAlignment = Alignment.Top) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(LF.Blue50), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Draw, null, Modifier.size(20.dp), tint = LF.Blue600)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Agreement template builder", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Create, version, preview and set the default loan agreement template.", fontSize = 14.sp, color = LF.MutedForeground)
                }
            }
        }
        if (isCompact()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                text(Modifier)
                Btn("Open templates", { router.go("/agreements/templates") }, variant = BtnVariant.Outline)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                text(Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Btn("Open templates", { router.go("/agreements/templates") }, variant = BtnVariant.Outline)
            }
        }
    }
}

@Composable
private fun AgreementSection(value: Row) {
    val auth by AppState.state.collectAsState()
    val runner = rememberRunner()
    val templates = rememberLoad { TemplatesService.list() }
    var templateId by remember(value) { mutableStateOf(value.str("default_template_id")) }
    var signingOrder by remember(value) { mutableStateOf(value.str("signing_order").ifBlank { "borrower_first" }) }
    var identity by remember(value) { mutableStateOf(value.str("identity_verification").ifBlank { "dob" }) }
    var witnesses by remember(value) { mutableStateOf(value.int("witness_count").toString()) }
    var expiry by remember(value) { mutableStateOf(value.str("link_expiry_hours")) }
    var lenderSig by remember(value) { mutableStateOf(value.bool("require_lender_signature")) }
    var coMaker by remember(value) { mutableStateOf(value.bool("require_co_maker_signature", default = true)) }

    fun onSave() {
        if (!numberOk(expiry, 1.0, 720.0, int = true)) return Toasts.error("Link expiry must be between 1 and 720 hours")
        runner.saveSection("agreement", value.merged(
            "default_template_id" to templateId.ifBlank { null }, "signing_order" to signingOrder, "identity_verification" to identity,
            "witness_count" to (witnesses.toIntOrNull() ?: 0), "link_expiry_hours" to jsNumber(expiry),
            "require_lender_signature" to lenderSig, "require_co_maker_signature" to coMaker,
        ))
    }
    val templateOptions = listOf(Opt("", "Organization default template")) +
        (templates.data ?: emptyList()).filter { it.bool("is_active") }.map { Opt(it.str("id"), it.str("name") + if (it.bool("is_default")) " (default)" else "") }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Panel(title = "Agreement & signing", description = "Applied when agreements are generated for new loans.") {
            Grid(
                2,
                cell { Field("Default agreement template", hint = "Products can specify their own template") { SelectField(templateId, templateOptions, { templateId = it }) } },
                cell {
                    Field("Signing order") {
                        SelectField(signingOrder, listOf(Opt("borrower_first", "Borrower signs first"), Opt("lender_first", "Lender signs first"), Opt("any", "Any order")), { signingOrder = it })
                    }
                },
                cell {
                    Field("Borrower identity verification", hint = "Asked before the borrower can view and sign") {
                        SelectField(identity, listOf(Opt("dob", "Confirm date of birth"), Opt("id_last4", "Confirm last 4 characters of ID number"), Opt("none", "No verification (link only)")), { identity = it })
                    }
                },
                cell {
                    Field("Witnesses") {
                        SelectField(witnesses, listOf(Opt("0", "No witnesses"), Opt("1", "1 witness"), Opt("2", "2 witnesses")), { witnesses = it })
                    }
                },
                cell { Field("Signing link expiry (hours)", hint = "1 – 720 hours") { NumberInput(expiry, { expiry = it }, decimal = false) } },
                cell(2) { ToggleCard("Require lender signature", "Both borrower and lender must sign before the loan can be released.", lenderSig, { lenderSig = it }) },
                cell(2) {
                    ToggleCard("Co-maker signs the agreement", "When the borrower has a co-maker on file, the co-maker must also sign — in person on your device or through a signing link.", coMaker, { coMaker = it })
                },
            )
            SaveBar(runner.busy, ::onSave)
        }
        if (auth.can("templates.manage")) TemplatesLinkCard()
    }
}

// ---------------------------------------------------------------------------
@Composable
private fun ReceiptSection(value: Row) {
    val runner = rememberRunner()
    val original = value.str("footer_note")
    var note by remember(value) { mutableStateOf(original) }
    Panel(title = "Payment receipts", description = "Footer text printed on every receipt.") {
        Field("Receipt footer note", hint = "${note.length}/300 characters") {
            TextInput(note, { note = it.take(300) }, singleLine = false, minLines = 3)
        }
        SaveBar(runner.busy, { runner.saveSection("receipt", mapOf("footer_note" to note.trim())) }, dirty = note != original)
    }
}

@Composable
private fun SecuritySection(value: Row) {
    val runner = rememberRunner()
    var timeout by remember(value) { mutableStateOf(value.str("session_timeout_minutes")) }
    var minLen by remember(value) { mutableStateOf(value.str("password_min_length")) }
    var audit by remember(value) { mutableStateOf(value.bool("audit_logging")) }
    fun onSave() {
        if (!numberOk(timeout, 0.0, 1440.0, int = true)) return Toasts.error("Session timeout must be 0–1440 minutes (0 disables it)")
        if (!numberOk(minLen, 8.0, 64.0, int = true)) return Toasts.error("Minimum password length must be 8–64")
        runner.saveSection("security", value.merged("session_timeout_minutes" to jsNumber(timeout), "password_min_length" to jsNumber(minLen), "audit_logging" to audit))
    }
    Panel(title = "Security", description = "Row Level Security isolates each organization; these settings control sessions and staff passwords.") {
        Grid(
            2,
            cell { Field("Idle session timeout (minutes)", hint = "Users are signed out after inactivity. 0 = never.") { NumberInput(timeout, { timeout = it }, decimal = false) } },
            cell { Field("Minimum staff password length") { NumberInput(minLen, { minLen = it }, decimal = false) } },
            cell(2) {
                ToggleCard("Audit logging", "Financial and document changes are always recorded in the immutable ledger and audit trail; this flag is kept for reporting.", audit, { audit = it })
            },
        )
        SaveBar(runner.busy, ::onSave)
    }
}

@Composable
private fun SampleDataSection() {
    var open by remember { mutableStateOf(false) }
    Panel(title = "Sample data", description = "For training and demos only.") {
        Text(
            "Loads 13 sample borrowers and 14 loans in every stage — drafts, applications, active loans with on-time, late and partial payments, " +
                "an early settlement, a completed loan and a defaulted loan. Payments go through the real payment engine, so dashboards and reports " +
                "show realistic figures. Demo loans are activated without signed agreements and are marked as sample data.",
            fontSize = 14.sp, color = LF.MutedForeground,
        )
        Spacer(Modifier.padding(top = 16.dp))
        InfoBox(
            "Sample financial records follow the same no-delete rules as real records. Load them only in a test or training workspace.",
            Icons.Default.Info, LF.Amber50, LF.Amber800,
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            Btn("Load sample data", { open = true }, variant = BtnVariant.Outline, icon = Icons.Default.Storage)
        }
    }
    if (open) {
        ConfirmDialog(
            title = "Load sample data?",
            onDismiss = { open = false },
            message = "Sample borrowers, loans and payments will be added to this organization. This cannot be undone.",
            confirmText = "Load sample data",
            onConfirm = {
                val r = OrgService.seedDemoData()
                Toasts.success("Loaded ${r.int("borrowers")} borrowers and ${r.int("loans")} loans")
            },
        )
    }
}

// ---------------------------------------------------------------------------
@Composable
private fun SimplifiedSection(value: Row, settings: Row) {
    val auth by AppState.state.collectAsState()
    val runner = rememberRunner()
    var allowOverride by remember(value) { mutableStateOf(value.bool("allow_rate_override")) }
    var approveOnCreate by remember(value) { mutableStateOf(value.bool("approve_on_create", default = true)) }
    var term by remember(value) { mutableStateOf(value.str("default_term")) }
    var feeType by remember(value) { mutableStateOf(value.str("processing_fee_type").ifBlank { "none" }) }
    var feeValue by remember(value) { mutableStateOf(value.str("processing_fee_value")) }
    val active = auth.organization.str("lending_mode") == "simplified"
    val interest = settings.obj("interest")
    val repayment = settings.obj("repayment")

    fun onSave() {
        if (!numberOk(term, 1.0, 360.0, int = true)) return Toasts.error("Default repayment period must be a whole number of months from 1 to 360")
        if (feeType != "none" && !numberOk(feeValue, 0.0, if (feeType == "percent") 50.0 else 1_000_000.0)) {
            return Toasts.error(if (feeType == "percent") "Fee percentage must be between 0 and 50" else "Enter the fee amount")
        }
        runner.saveSection("simplified", value.merged(
            "allow_rate_override" to allowOverride, "approve_on_create" to approveOnCreate, "default_term" to jsNumber(term),
            "processing_fee_type" to feeType, "processing_fee_value" to if (feeType == "none") 0 else jsNumber(feeValue),
        ))
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape)
                .background(if (active) LF.Teal50 else LF.Slate50)
                .border(1.dp, if (active) LF.Teal200 else LF.Border, shape).padding(16.dp),
        ) {
            val fg = if (active) LF.Teal700 else LF.Slate700
            Icon(Icons.Default.Lock, null, Modifier.size(16.dp).padding(top = 2.dp), tint = fg)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Lending mode: ${if (active) "Simplified Lending" else "Complete Lending"}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg)
                Text(
                    "Set by your LendFlow platform administrator. " + if (active) "Every user creates loans with the New Loan / Reloan-Renew workflow below."
                    else "These settings apply only if your account is switched to Simplified Lending.",
                    fontSize = 12.sp, color = fg,
                )
            }
        }
        Panel(title = "Simplified lending rules", description = "Applied automatically to every simplified loan. Staff enter only the amount and the duration.") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Slate50).padding(12.dp)) {
                    val periodWord = interest.str("default_rate_period").replace("ly", "").replace("dai", "day")
                    Grid(
                        3,
                        cell { SummaryCell("Interest rate (Interest tab)", "${interest.str("default_rate")}% per $periodWord") },
                        cell { SummaryCell("Method", if (interest.str("default_method") == "flat") "Flat" else "Diminishing balance") },
                        cell { SummaryCell("Payments (Repayment tab)", repayment.str("default_frequency").replaceFirstChar { it.uppercase() }) },
                        spacing = 12.dp,
                    )
                }
                ToggleCard(
                    "Allow staff to change the interest rate per loan",
                    "Off: every simplified loan uses the default rate above and the server rejects any other rate.",
                    allowOverride, { allowOverride = it },
                )
                ToggleCard(
                    "Approve at creation when the user has approval rights",
                    "Owners and admins skip the separate review step. Segregation-of-duties rules in Repayment still apply.",
                    approveOnCreate, { approveOnCreate = it },
                )
                Grid(
                    3,
                    cell { Field("Default repayment period (months)", hint = "Suggested on new loans; installments follow the payment frequency") { NumberInput(term, { term = it }, decimal = false) } },
                    cell {
                        Field("Processing fee") {
                            SelectField(feeType, listOf(Opt("none", "None"), Opt("percent", "Percent of principal"), Opt("fixed", "Fixed amount")), { feeType = it })
                        }
                    },
                    cell {
                        Field(
                            if (feeType == "percent") "Fee (%)" else "Fee (₱)",
                            hint = "Collected: ${if (repayment.str("charges_treatment") == "added") "added to installments" else "deducted from proceeds"}",
                        ) { NumberInput(feeValue, { feeValue = it }, enabled = feeType != "none") }
                    },
                )
                InfoBox(
                    "Renewals deduct the borrower's outstanding balance (early-settlement payoff, Repayment tab) from the new loan and settle the previous loan in full when the new loan is released.",
                    Icons.Default.Info, LF.Blue50, LF.Blue700,
                )
            }
            SaveBar(runner.busy, ::onSave)
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String) {
    Column {
        Text(label, fontSize = 12.sp, color = LF.MutedForeground)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

