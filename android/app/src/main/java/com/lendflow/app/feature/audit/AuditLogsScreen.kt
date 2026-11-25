package com.lendflow.app.feature.audit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.data.AuditService
import com.lendflow.app.data.Row
import com.lendflow.app.data.Supa
import com.lendflow.app.data.has
import com.lendflow.app.data.obj
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.strings
import com.lendflow.app.feature.dashboard.GridRows
import com.lendflow.app.feature.dashboard.LocalBreaks
import com.lendflow.app.feature.dashboard.ResponsiveScreen
import com.lendflow.app.ui.components.AppDialog
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CellRole
import com.lendflow.app.ui.components.CellText
import com.lendflow.app.ui.components.Col
import com.lendflow.app.ui.components.DataTable
import com.lendflow.app.ui.components.DateField
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.Pagination
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.SearchField
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.rememberDebounced
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Opt
import com.lendflow.app.util.Tone
import com.lendflow.app.util.fmtDateTime
import com.lendflow.app.util.titleCase
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Port of src/features/audit/AuditLogsPage.tsx

private const val PAGE_SIZE = 50

private val TABLES = listOf(
    Opt("loans", "Loans"),
    Opt("loan_payments", "Payments"),
    Opt("payment_receipts", "Receipts"),
    Opt("loan_agreements", "Agreements"),
    Opt("agreement_signatures", "Signatures"),
    Opt("agreement_signing_sessions", "Signing links"),
    Opt("borrowers", "Borrowers"),
    Opt("borrower_identities", "Borrower IDs"),
    Opt("borrower_documents", "Borrower documents"),
    Opt("guarantors", "Guarantors"),
    Opt("loan_charges", "Loan charges"),
    Opt("collector_assignments", "Collector assignments"),
    Opt("loan_products", "Loan products"),
    Opt("loan_agreement_templates", "Agreement templates"),
    Opt("loan_agreement_template_versions", "Template versions"),
    Opt("system_settings", "Settings"),
    Opt("organizations", "Organization"),
    Opt("user_profiles", "User profiles"),
    Opt("user_roles", "User roles"),
    Opt("role_permissions", "Role permissions"),
)

private fun tableLabel(t: String?) = TABLES.firstOrNull { it.value == t }?.label ?: titleCase(t)
private val ACTION_TONE = mapOf("insert" to Tone.green, "update" to Tone.blue, "delete" to Tone.red)
private val ACTION_LABEL = mapOf("insert" to "Created", "update" to "Updated", "delete" to "Deleted")
private fun actionLabel(a: String?) = ACTION_LABEL[a] ?: titleCase(a)

private fun recordLabel(l: Row): String {
    val d = l.obj("new_data") ?: l.obj("old_data")
    fun f(k: String) = d.strOrNull(k)?.ifBlank { null }
    return f("loan_number") ?: f("payment_number") ?: f("receipt_number") ?: f("agreement_number") ?: f("borrower_code")
        ?: f("full_name") ?: f("name") ?: f("code")
        ?: f("signer_role")?.let { "${titleCase(it)} signature" }
        ?: l.strOrNull("record_id")?.ifBlank { null }?.let { "${it.take(8)}…" }
        ?: "—"
}

@OptIn(ExperimentalSerializationApi::class)
private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

private fun fmtValue(v: JsonElement?): String = when (v) {
    null, is JsonNull -> "∅"
    is JsonObject, is JsonArray -> pretty.encodeToString(JsonElement.serializer(), v)
    is JsonPrimitive -> v.content
}

@Composable
fun AuditLogsScreen() {
    var table by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Row?>(null) }
    val debounced = rememberDebounced(search)

    val q = rememberLoad(table, action, from, to, debounced, page) {
        AuditService.list(table = table, action = action, search = debounced, from = from, to = to, page = page, pageSize = PAGE_SIZE)
    }
    // keepPreviousData: show the last page while the next one loads
    var last by remember { mutableStateOf<Supa.Page?>(null) }
    LaunchedEffect(q.data) { q.data?.let { last = it } }
    val data = q.data ?: last

    fun reset() { table = ""; action = ""; from = ""; to = ""; search = ""; page = 0 }

    val columns = listOf(
        Col<Row>("When", 1.1f, mobile = CellRole.Subtitle, minWidth = 150.dp) { l -> Text(fmtDateTime(l.strOrNull("created_at")), fontSize = 14.sp, maxLines = 1) },
        Col("User", 1f, mobile = CellRole.Meta) { l -> CellText(l.strOrNull("actor_name")?.ifBlank { null } ?: if (l.has("actor_id")) "Staff" else "System / signer") },
        Col("Action", 0.8f, mobile = CellRole.Meta) { l -> Pill(actionLabel(l.strOrNull("action")), ACTION_TONE[l.strOrNull("action")] ?: Tone.slate) },
        Col("Record type", 1f, mobile = CellRole.Title) { l -> CellText(tableLabel(l.strOrNull("table_name")), bold = true) },
        Col("Record", 1f, mobile = CellRole.Meta) { l -> Text(recordLabel(l), fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        Col("Changed fields", 1.6f, mobile = CellRole.Hidden, minWidth = 160.dp) { l ->
            val fields = l.strings("changed_fields")
            if (fields.isNotEmpty()) {
                Text(fields.take(6).joinToString(", ") + if (fields.size > 6) " +${fields.size - 6}" else "", fontSize = 12.sp, color = LF.MutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else Text("—", fontSize = 12.sp, color = LF.MutedForeground)
        },
    )

    ResponsiveScreen {
        val bp = LocalBreaks.current
        PageHeader("Audit logs", "Immutable record of changes to borrowers, loans, payments, documents and settings.")
        Panel(contentPadding = 12.dp) {
            val filters: List<@Composable () -> Unit> = listOf(
                { SearchField(search, { search = it; page = 0 }, placeholder = "Search user or record ID") },
                { SelectField(table, listOf(Opt("", "All record types")) + TABLES, { table = it; page = 0 }) },
                { SelectField(action, listOf(Opt("", "All actions"), Opt("insert", "Created"), Opt("update", "Updated"), Opt("delete", "Deleted")), { action = it; page = 0 }) },
                { DateField(from, { from = it; page = 0 }, placeholder = "From date", clearable = true) },
                { DateField(to, { to = it; page = 0 }, placeholder = "To date", clearable = true) },
            )
            if (bp.xl) {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(1.4f, 1f, 1f, 0.8f, 0.8f).forEachIndexed { i, w -> Box(Modifier.weight(w)) { filters[i]() } }
                    Btn("Reset", { reset() }, variant = BtnVariant.Ghost)
                }
            } else {
                Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GridRows(if (bp.sm) 2 else 1, filters.size + 1, gap = 8.dp) { i, _ ->
                        if (i < filters.size) filters[i]() else Btn("Reset", { reset() }, variant = BtnVariant.Ghost)
                    }
                }
            }
            if (q.error != null && data == null) ErrorState(q.error, q.reload)
            else {
                DataTable(
                    columns, data?.rows, loading = q.loading, onRowClick = { selected = it },
                    empty = { EmptyState("No audit entries", icon = Icons.AutoMirrored.Filled.ReceiptLong, description = "Try widening the date range or clearing filters.") },
                )
                Pagination(page, PAGE_SIZE, data?.count ?: 0, { page = it })
            }
        }
    }

    selected?.let { AuditDetail(it) { selected = null } }
}

@Composable
private fun AuditDetail(log: Row, onDismiss: () -> Unit) {
    val action = log.strOrNull("action")
    val rows = remember(log) {
        val oldD = log.obj("old_data") ?: JsonObject(emptyMap())
        val newD = log.obj("new_data") ?: JsonObject(emptyMap())
        val keys = if (action == "update") log.strings("changed_fields")
        else (if (action == "delete") oldD else newD).keys.filter { it != "organization_id" }
        keys.map { k -> Triple(k, oldD[k], newD[k]) }
    }
    val actor = log.strOrNull("actor_name")?.ifBlank { null } ?: if (log.has("actor_id")) "staff user" else "system / external signer"
    AppDialog(
        title = "${actionLabel(action)} · ${tableLabel(log.strOrNull("table_name"))}",
        onDismiss = onDismiss,
        description = "${fmtDateTime(log.strOrNull("created_at"))} by $actor",
        maxWidth = 640.dp,
    ) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Slate50).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Record", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.Foreground)
                Text(recordLabel(log), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Record ID", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.Foreground)
                Text(log.strOrNull("record_id") ?: "—", fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (rows.isEmpty()) {
            Text("No field details recorded.", fontSize = 14.sp, color = LF.MutedForeground)
        } else {
            val showBefore = action != "insert"
            val showAfter = action != "delete"
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, LF.Border, RoundedCornerShape(8.dp))) {
                Row(Modifier.fillMaxWidth().background(LF.Slate50).padding(vertical = 8.dp)) {
                    Text("Field", Modifier.weight(1f).padding(horizontal = 12.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground)
                    if (showBefore) Text("Before", Modifier.weight(1.5f).padding(horizontal = 12.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground)
                    if (showAfter) Text("After", Modifier.weight(1.5f).padding(horizontal = 12.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground)
                }
                rows.forEach { (k, old, next) ->
                    HorizontalDivider(color = LF.Border)
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                        Text(k, Modifier.weight(1f).padding(horizontal = 12.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        if (showBefore) Box(Modifier.weight(1.5f).padding(horizontal = 12.dp)) { DiffValue(fmtValue(old), LF.Red50, LF.Red800) }
                        if (showAfter) Box(Modifier.weight(1.5f).padding(horizontal = 12.dp)) { DiffValue(fmtValue(next), LF.Green50, LF.Green800) }
                    }
                }
            }
        }
        Spacer(Modifier.width(0.dp))
    }
}

@Composable
private fun DiffValue(text: String, bg: Color, fg: Color) {
    Text(text, Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = fg)
}
