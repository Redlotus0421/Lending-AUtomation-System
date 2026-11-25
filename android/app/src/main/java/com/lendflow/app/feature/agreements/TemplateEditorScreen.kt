package com.lendflow.app.feature.agreements

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.Row
import com.lendflow.app.data.TemplatesService
import com.lendflow.app.data.bool
import com.lendflow.app.data.jsonOf
import com.lendflow.app.data.obj
import com.lendflow.app.data.objs
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.with
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.FormRow
import com.lendflow.app.ui.components.NumberInput
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.PageLoader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.SwitchRow
import com.lendflow.app.ui.components.Tabs
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.fieldColors
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Opt
import com.lendflow.app.util.Tone
import com.lendflow.app.util.fmtDateTime
import com.lendflow.app.util.trimNumber
import kotlinx.serialization.json.JsonObject

/** Where a template editor loads and saves (the lender's own templates, or a platform admin editing an account's). */
interface TemplateSource {
    /** { template, versions } */
    suspend fun get(id: String): Row
    /** { id, version_id, version_no } */
    suspend fun save(p: Map<String, Any?>): Row
    /** business shown in the preview letterhead (null = the signed-in user's organization) */
    val organization: Row? get() = null
}

object LenderTemplateSource : TemplateSource {
    override suspend fun get(id: String): Row = TemplatesService.get(id)
    override suspend fun save(p: Map<String, Any?>): Row = TemplatesService.save(p)
}

const val STARTER = """This LOAN AGREEMENT is made on **{{agreement_date}}** between **{{lender_name}}** ("LENDER") and **{{borrower_name}}** ("BORROWER"), residing at {{borrower_address}}.

## 1. The Loan

The LENDER lends the BORROWER **{{loan_amount_words}} ({{loan_amount}})** at **{{interest_rate}}** ({{interest_method}}), payable in {{installment_count}} {{payment_frequency}} installments of {{installment_amount}}.

## 2. Disclosure Statement

{{disclosure_table}}

## 3. Repayment Schedule

{{repayment_schedule}}

## 4. Late Payment

{{penalty_terms}}

{{signature_block}}"""

private val NEW_SETTINGS: JsonObject = jsonOf("show_logo" to true, "footer_text" to "This document is electronically generated and signed.", "schedule_font_size" to 7.5)
private val SETTING_DEFAULTS: JsonObject = jsonOf("show_logo" to true, "schedule_font_size" to 7.5)

/** id = "new" for a new template */
@Composable
fun TemplateEditorScreen(id: String) {
    key(id) { TemplateEditor(id) }
}

private fun withDefaults(s: JsonObject?): JsonObject = JsonObject(SETTING_DEFAULTS + (s ?: jsonOf()))
private fun fontText(s: JsonObject): String = s["schedule_font_size"]?.let { trimNumber(s.str("schedule_font_size").toDoubleOrNull() ?: 7.5, 2) } ?: "7.5"

/**
 * Agreement template editor with live preview. Used as its own page, embedded in Settings → Terms
 * template and in the platform admin's account page (embedded, source). Saving a new template calls onSaved.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateEditor(id: String, embedded: Boolean = false, onSaved: ((String) -> Unit)? = null, source: TemplateSource = LenderTemplateSource) {
    val isNew = id == "new"
    val auth by AppState.state.collectAsState()
    val router = LocalRouter.current
    val organization = source.organization ?: auth.organization
    val q = rememberLoad(id, live = false, enabled = !isNew) { source.get(id) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("Loan Agreement") }
    var body by remember { mutableStateOf(TextFieldValue(STARTER, TextRange(STARTER.length))) }
    var settings by remember { mutableStateOf(NEW_SETTINGS) }
    var fontSize by remember { mutableStateOf("7.5") }
    var note by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<Row?>(null) }
    var aiOpen by remember { mutableStateOf(false) }
    var aiDraft by remember { mutableStateOf<Pair<TermsDraft, String>?>(null) }
    var tab by remember { mutableStateOf("preview") }
    val runner = rememberRunner()

    val data = q.data
    val template = data?.obj("template")
    val versions = data.objs("versions")
    val currentVersion = versions.firstOrNull { it.str("id") == template.str("current_version_id") }

    LaunchedEffect(data) {
        if (data == null) return@LaunchedEffect
        val t = data.obj("template")
        val vs = data.objs("versions")
        val cur = vs.firstOrNull { it.str("id") == t.str("current_version_id") } ?: vs.firstOrNull()
        name = t.str("name")
        description = t.strOrNull("description") ?: ""
        if (cur != null) {
            title = cur.str("title")
            body = TextFieldValue(cur.str("body"), TextRange(cur.str("body").length))
            settings = withDefaults(cur.obj("settings"))
            fontSize = fontText(settings)
        }
    }

    fun applyAi(d: TermsDraft, file: String) {
        title = d.title
        body = TextFieldValue(d.body, TextRange(d.body.length))
        if (isNew || name.isBlank()) name = d.suggestedName
        note = "Imported from $file with the built-in assistant"
        aiDraft = d to file
        Toasts.success("Draft ready — check the preview, then save")
    }

    fun save(asDefault: Boolean) = runner.run {
        val p = linkedMapOf<String, Any?>()
        if (!isNew) p["id"] = id
        p["name"] = name
        p["description"] = description
        p["title"] = title
        p["body"] = body.text
        p["settings"] = settings
        if (note.isNotEmpty()) p["change_note"] = note
        if (asDefault) p["is_default"] = true
        val r = source.save(p)
        Toasts.success("Saved as version ${r.str("version_no")}")
        note = ""
        aiDraft = null
        if (isNew) {
            val newId = r.str("id")
            if (onSaved != null) onSaved(newId) else router.replace("/agreements/templates/$newId")
        } else q.reload()
    }

    fun insert(k: String) {
        val token = if (k in BLOCK_PLACEHOLDERS) "\n\n{{$k}}\n\n" else "{{$k}}"
        val text = body.text
        val start = minOf(body.selection.start, body.selection.end).coerceIn(0, text.length)
        val end = maxOf(body.selection.start, body.selection.end).coerceIn(0, text.length)
        body = TextFieldValue(text.substring(0, start) + token + text.substring(end), TextRange(start + token.length))
    }

    val dirty = if (data == null) true else {
        val cur = currentVersion
        cur == null || cur.str("body") != body.text || cur.str("title") != title || (cur.obj("settings") ?: jsonOf()) != settings ||
            template.str("name") != name || (template.strOrNull("description") ?: "") != description
    }

    if (!isNew && q.initialLoading) { PageLoader(); return }
    if (!isNew && q.error != null && data == null) { ErrorState(q.error); return }

    val isDefault = template.bool("is_default")
    val incomplete = name.isBlank() || title.isBlank() || body.text.isBlank()
    val actions: @Composable () -> Unit = {
        if (!isNew && isDefault) Pill("Default", Tone.blue)
        if (!isDefault) Btn("Save & set default", { save(true) }, variant = BtnVariant.Outline, icon = Icons.Default.Star, enabled = !runner.busy && !incomplete)
        Btn("Save ${if (isNew) "template" else "new version"}", { save(false) }, icon = Icons.Default.Save, loading = runner.busy,
            enabled = !incomplete && (isNew || dirty))
    }

    Column {
        if (embedded) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saving creates a new version. Agreements already generated keep the version they were created from.", fontSize = 12.sp, color = LF.MutedForeground)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
            }
        } else {
            PageHeader(
                if (isNew) "New agreement template" else name.ifBlank { "Template" },
                description = "Saving creates a new immutable version. Agreements already generated keep the version they were created from.",
                onBack = { router.go("/agreements/templates") },
                backLabel = "Templates",
                actions = { actions() },
            )
        }

        // "Already have your own terms?"
        val bannerShape = RoundedCornerShape(12.dp)
        Column(
            Modifier.fillMaxWidth().padding(bottom = 20.dp).clip(bannerShape)
                .background(Brush.horizontalGradient(listOf(LF.Violet50, LF.Blue50))).border(1.dp, LF.Violet200, bannerShape).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(20.dp), tint = Color(0xFF7C3AED))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Already have your own terms?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Upload your loan agreement (PDF, Word or text). The built-in assistant turns it into a template with the right fields filled in for every loan — on this device, no internet AI.",
                        fontSize = 12.sp, color = LF.MutedForeground)
                }
            }
            Btn("Upload terms", { aiOpen = true }, variant = BtnVariant.Outline, icon = Icons.Default.AutoAwesome)
        }

        aiDraft?.let { (d, file) ->
            Column(Modifier.fillMaxWidth().padding(bottom = 20.dp).clip(RoundedCornerShape(12.dp)).background(LF.Amber50)
                .border(1.dp, LF.Amber200, RoundedCornerShape(12.dp)).padding(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text("Draft from $file — not saved yet", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LF.Amber800)
                    IconButton(onClick = { aiDraft = null }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(16.dp), tint = LF.Amber800) }
                }
                Text("Read the preview carefully before saving. The assistant can make mistakes.", fontSize = 12.sp, color = LF.Amber800)
                if (d.notes.isNotEmpty()) {
                    Column(Modifier.padding(top = 8.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        d.notes.forEach { n ->
                            Row { Text("•  ", fontSize = 12.sp, color = LF.Amber800); Text(n, fontSize = 12.sp, color = LF.Amber800) }
                        }
                    }
                }
            }
        }
        if (aiOpen) AiImportDialog(onDismiss = { aiOpen = false }, onDraft = { d, f -> applyAi(d, f) })

        val wide = widthClass() == WidthClass.Expanded
        val left: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Panel(title = "Template details") {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        FormRow(
                            { Field("Template name", required = true) { TextInput(name, { name = it }, placeholder = "e.g. Standard Loan Agreement") } },
                            { Field("Agreement title (printed)", required = true) { TextInput(title, { title = it }) } },
                        )
                        Field("Description") { TextInput(description, { description = it }) }
                        FormRow(
                            {
                                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, LF.Border, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                                    SwitchRow(settings.bool("show_logo", true), { settings = settings.with("show_logo", it) }, "Show company logo")
                                }
                            },
                            {
                                Field("Schedule table font size") {
                                    NumberInput(fontSize, { v ->
                                        fontSize = v
                                        val n = v.toDoubleOrNull() ?: 0.0
                                        settings = settings.with("schedule_font_size", if (n == Math.floor(n)) n.toLong() else n)
                                    })
                                }
                            },
                        )
                        Field("Footer text") { TextInput(settings.str("footer_text"), { settings = settings.with("footer_text", it) }) }
                    }
                }
                Panel(title = "Agreement body", description = "Use ## for section headings, **bold**, numbered lists (1.) and bullets (-). Tap a placeholder to insert it at the cursor.") {
                    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PLACEHOLDERS.groupBy { it.group }.forEach { (g, items) ->
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(g.uppercase(), Modifier.width(84.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground, letterSpacing = 0.4.sp)
                                items.forEach { p ->
                                    Row(
                                        Modifier.clip(RoundedCornerShape(6.dp)).background(LF.Slate50).border(1.dp, LF.Border, RoundedCornerShape(6.dp))
                                            .clickable { insert(p.key) }.padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.DataObject, p.label, Modifier.size(12.dp), tint = LF.Blue)
                                        Spacer(Modifier.width(4.dp))
                                        Text(p.key, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = LF.Slate700)
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = body, onValueChange = { body = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 640.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp, color = LF.Foreground),
                        shape = RoundedCornerShape(8.dp), colors = fieldColors(),
                    )
                    if (!isNew) {
                        Spacer(Modifier.height(12.dp))
                        Field("Change note for this version") { TextInput(note, { note = it }, placeholder = "What changed and why") }
                    }
                }
            }
        }
        val right: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val tabs = buildList {
                    add(Opt("preview", "Preview"))
                    if (!isNew) add(Opt("versions", "Versions (${versions.size})"))
                }
                Tabs(tabs, tab, { tab = it })
                if (tab == "preview" || isNew) {
                    TemplatePreview(title, body.text, organization, maxHeight = if (wide) 900.dp else null)
                } else {
                    Panel {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            versions.forEach { v ->
                                val isCur = v.str("id") == template.str("current_version_id")
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, LF.Border, RoundedCornerShape(8.dp)).padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Version ${v.str("version_no")}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                            if (isCur) { Spacer(Modifier.width(6.dp)); Pill("Current", Tone.green) }
                                        }
                                        val cn = v.strOrNull("change_note")
                                        Text(fmtDateTime(v.strOrNull("created_at")) + (if (!cn.isNullOrEmpty()) " · $cn" else ""), fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                    Btn("View", { viewing = v }, variant = BtnVariant.Ghost, small = true)
                                    if (!isCur) {
                                        Btn("Restore", {
                                            title = v.str("title")
                                            body = TextFieldValue(v.str("body"), TextRange(v.str("body").length))
                                            settings = v.obj("settings") ?: jsonOf()
                                            fontSize = fontText(settings)
                                            note = "Restored from version ${v.str("version_no")}"
                                            Toasts.info("Loaded version ${v.str("version_no")} into the editor — save to make it current")
                                        }, variant = BtnVariant.Outline, small = true)
                                    }
                                }
                            }
                        }
                    }
                    viewing?.let { v ->
                        Text("Viewing version ${v.str("version_no")} (read-only)", fontSize = 12.sp, color = LF.MutedForeground)
                        TemplatePreview(v.str("title"), v.str("body"), organization, maxHeight = if (wide) 900.dp else null)
                    }
                }
            }
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { left() }
                Box(Modifier.weight(1f)) { right() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { left(); right() }
        }
    }
}
