package com.lendflow.app.feature.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.PlatformService
import com.lendflow.app.data.Row
import com.lendflow.app.data.bool
import com.lendflow.app.data.num
import com.lendflow.app.data.obj
import com.lendflow.app.data.objs
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.feature.settings.Grid
import com.lendflow.app.feature.settings.cell
import com.lendflow.app.ui.components.Align
import com.lendflow.app.ui.components.AppDialog
import com.lendflow.app.ui.components.Avatar
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CellRole
import com.lendflow.app.ui.components.CellText
import com.lendflow.app.ui.components.Col
import com.lendflow.app.ui.components.ConfirmDialog
import com.lendflow.app.ui.components.DataTable
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.KV
import com.lendflow.app.ui.components.KeyValue
import com.lendflow.app.ui.components.PageLoader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.StatCard
import com.lendflow.app.ui.components.StatTone
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.ROLE_LABEL
import com.lendflow.app.util.Tone
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.fmtDateTime
import com.lendflow.app.util.initials
import com.lendflow.app.util.num
import com.lendflow.app.util.peso
import com.lendflow.app.util.timeAgo

// Port of src/features/admin/AccountDetailPage.tsx

private val ROLE_TONE = mapOf("owner" to Tone.navy, "admin" to Tone.blue, "loan_officer" to Tone.teal, "collector" to Tone.amber)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountDetailScreen(id: String) {
    val router = LocalRouter.current
    val q = rememberLoad(id) { PlatformService.getOrganization(id) }
    var creds by remember(id) { mutableStateOf(PendingCredentials.take(id)) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var pwFor by remember { mutableStateOf<Row?>(null) }
    var statusAction by remember { mutableStateOf<String?>(null) }

    val o = q.data
    if (o == null) {
        if (q.error != null) ErrorState(q.error, q.reload) else PageLoader()
        // keep the credentials of a just-created account visible even while the page loads
        creds?.let { c -> CredentialsDialog(c) { creds = null } }
        return
    }
    val name = o.str("name")
    val stats = o.obj("stats")
    val active = o.str("status") == "active"
    val compact = isCompact()

    val columns = listOf(
        Col<Row>("Name", weight = 2f, mobile = CellRole.Title, minWidth = 190.dp) { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!compact) {
                    Avatar(initials(m.strOrNull("full_name")?.ifBlank { null } ?: m.strOrNull("email") ?: "?"), size = 36.dp, bg = LF.Navy.copy(alpha = 0.1f), fg = LF.Navy)
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(m.str("full_name").ifBlank { "—" }, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.str("email"), fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        Col<Row>("Role", mobile = CellRole.Meta, minWidth = 110.dp) { m ->
            val r = m.strOrNull("role")
            if (r != null) Pill(ROLE_LABEL[r] ?: r, ROLE_TONE[r] ?: Tone.slate, dot = false) else Pill("No role")
        },
        Col<Row>("Status", mobile = CellRole.Meta, minWidth = 110.dp) { m -> if (m.bool("is_active")) Pill("Active", Tone.green) else Pill("Deactivated", Tone.slate) },
        Col<Row>("Last sign-in", mobile = CellRole.Subtitle, minWidth = 120.dp) { m ->
            CellText(m.strOrNull("last_sign_in_at")?.let { timeAgo(it) } ?: "Never signed in", muted = true)
        },
        Col<Row>("Added", mobile = CellRole.Hidden, minWidth = 100.dp) { m -> CellText(fmtDate(m.strOrNull("created_at")), muted = true) },
        Col<Row>("", weight = 1.2f, align = Align.End, mobile = CellRole.Value, minWidth = 160.dp) { m ->
            Btn("Reset password", { pwFor = m }, variant = BtnVariant.Outline, small = true, icon = Icons.Default.Key)
        },
    )

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // Header (PageHeader with pills in the title)
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable { if (!router.back()) router.replace("/admin/accounts") }.padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(16.dp), tint = LF.MutedForeground)
                Spacer(Modifier.width(4.dp))
                Text("Lender accounts", color = LF.MutedForeground, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = MaterialTheme.typography.headlineSmall, color = LF.Foreground, modifier = Modifier.align(Alignment.CenterVertically))
                Row(Modifier.align(Alignment.CenterVertically), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountStatusPill(o.strOrNull("status"))
                    LendingModePill(o.strOrNull("lending_mode") ?: "complete")
                }
            }
            Text(
                "Created ${fmtDate(o.strOrNull("created_at"), "long")}" + (o.strOrNull("legal_name")?.ifBlank { null }?.let { " · $it" } ?: ""),
                fontSize = 14.sp, color = LF.MutedForeground, modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Edit details", { editing = true }, variant = BtnVariant.Outline, icon = Icons.Default.Edit)
                Btn("Add login", { adding = true }, variant = BtnVariant.Outline, icon = Icons.Default.PersonAdd)
                if (active) Btn("Suspend", { statusAction = "suspend" }, variant = BtnVariant.Destructive, icon = Icons.Default.Block)
                else Btn("Reactivate", { statusAction = "reactivate" }, icon = Icons.Default.PlayCircle)
            }
        }

        if (o.str("status") == "suspended") {
            val shape = RoundedCornerShape(12.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(LF.Red50).border(1.dp, LF.Red200, shape).padding(16.dp)) {
                Text(
                    "This account is suspended" + (o.strOrNull("status_changed_at")?.let { " since ${fmtDateTime(it)}" } ?: "") + ".",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LF.Red800,
                )
                Text(
                    "None of its users can sign in to the workspace. Its records are kept unchanged. Reason: ${o.strOrNull("status_reason")?.ifBlank { null } ?: "—"}",
                    fontSize = 14.sp, color = LF.Red800, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // Stats: 2 per row on phones (the last one full width), 5 in a row on large screens
        val cards: List<@Composable (Modifier) -> Unit> = listOf(
            { m -> StatCard("Users", num(stats.num("users")), m, icon = Icons.Default.Groups, tone = StatTone.navy) },
            { m -> StatCard("Borrowers", num(stats.num("borrowers")), m, icon = Icons.Default.Contacts, tone = StatTone.blue) },
            { m -> StatCard("Active loans", num(stats.num("active_loans")), m, hint = "${num(stats.num("loans"))} loans in total", icon = Icons.Default.Payments, tone = StatTone.teal) },
            { m -> StatCard("Outstanding", peso(stats.num("outstanding"), compact = true), m, icon = Icons.Default.AccountBalanceWallet, tone = StatTone.amber) },
            { m -> StatCard("Collected", peso(stats.num("collected"), compact = true), m, hint = "All posted payments", icon = Icons.Default.AccountBalanceWallet, tone = StatTone.green) },
        )
        if (widthClass() == WidthClass.Expanded) CardGrid(5, cards)
        else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardGrid(2, cards.take(4))
            cards[4](Modifier.fillMaxWidth())
        }

        key(o.strOrNull("lending_mode")) { LendingModePanel(o) }

        AccountTemplatesPanel(o)

        val details: @Composable (Modifier) -> Unit = { m ->
            Panel(m, title = "Business details") {
                val owner = o.obj("owner")
                KeyValue(
                    listOf(
                        KV("Business / trade name", o.strOrNull("name")),
                        KV("Registered legal name", o.strOrNull("legal_name")),
                        KV("SEC reg. / Certificate of Authority", o.strOrNull("registration_no")),
                        KV("TIN", o.strOrNull("tin")),
                        KV("Address", listOf("address", "city", "province", "zip_code").mapNotNull { o.strOrNull(it)?.ifBlank { null } }.joinToString(", ").ifBlank { null }),
                        KV("Contact", listOf("contact_email", "contact_phone").mapNotNull { o.strOrNull(it)?.ifBlank { null } }.joinToString(" · ").ifBlank { null }),
                        KV("Owner", owner?.let { "${it.str("full_name")} (${it.str("email")})" }),
                    ),
                    columns = 1,
                )
            }
        }
        val logins: @Composable (Modifier) -> Unit = { m ->
            Panel(
                m, title = "Logins", description = "Staff added by the owner appear here too.",
                contentPadding = if (compact) 12.dp else 16.dp,
                actions = { Btn("Add login", { adding = true }, variant = BtnVariant.Outline, small = true, icon = Icons.Default.PersonAdd) },
            ) {
                DataTable(columns, o.objs("members"), empty = { EmptyState("No logins") })
            }
        }
        if (widthClass() == WidthClass.Expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                details(Modifier.weight(1f))
                logins(Modifier.weight(1.6f))
            }
        } else {
            details(Modifier)
            logins(Modifier)
        }
    }

    if (adding) AddUserDialog(o.str("id"), name, onClose = { adding = false }, onCreated = { c -> adding = false; creds = c })
    pwFor?.let { m -> AdminResetPasswordDialog(m, name, onClose = { pwFor = null }, onDone = { c -> pwFor = null; creds = c }) }
    if (editing) EditAccountDialog(o) { editing = false }
    creds?.let { c -> CredentialsDialog(c) { creds = null } }
    when (statusAction) {
        "suspend" -> ConfirmDialog(
            title = "Suspend $name?",
            onDismiss = { statusAction = null },
            message = "All of this account's users are blocked from the workspace until you reactivate it. No records are deleted.",
            confirmText = "Suspend account",
            destructive = true,
            reasonLabel = "Reason (visible to the account's users)",
            successMessage = "$name is suspended",
            onConfirm = { reason -> PlatformService.setStatus(o.str("id"), "suspended", reason) },
        )
        "reactivate" -> ConfirmDialog(
            title = "Reactivate $name?",
            onDismiss = { statusAction = null },
            message = "Its users can sign in and work again immediately.",
            confirmText = "Reactivate",
            successMessage = "$name is active again",
            onConfirm = { PlatformService.setStatus(o.str("id"), "active", null) },
        )
    }
}

private class EditField(val key: String, val label: String, val wide: Boolean = false)

private val EDIT_FIELDS = listOf(
    EditField("name", "Business / trade name"),
    EditField("legal_name", "Registered legal name"),
    EditField("registration_no", "SEC reg. / Certificate of Authority no."),
    EditField("tin", "TIN"),
    EditField("address", "Business address", wide = true),
    EditField("city", "City / municipality"),
    EditField("province", "Province"),
    EditField("contact_email", "Contact email"),
    EditField("contact_phone", "Contact phone"),
)

@Composable
private fun EditAccountDialog(org: Row, onClose: () -> Unit) {
    val runner = rememberRunner()
    var values by remember { mutableStateOf(EDIT_FIELDS.associate { it.key to org.str(it.key) }) }
    AppDialog(
        title = "Edit account details",
        onDismiss = { if (!runner.busy) onClose() },
        description = "These details print on the account's agreements and receipts. The owner can also change them in Settings.",
        maxWidth = 720.dp,
        footer = {
            Btn("Cancel", onClose, variant = BtnVariant.Outline, enabled = !runner.busy)
            Btn("Save", {
                if ((values["name"] ?: "").trim().length < 2) { Toasts.error("Business name is required"); return@Btn }
                runner.run(success = "Account details saved") {
                    PlatformService.updateOrganization(org.str("id"), values)
                    onClose()
                }
            }, loading = runner.busy)
        },
    ) {
        Grid(
            2,
            *EDIT_FIELDS.map { f ->
                cell(if (f.wide) 2 else 1) {
                    Field(f.label, required = f.key == "name") {
                        TextInput(values[f.key] ?: "", { values = values + (f.key to it) })
                    }
                }
            }.toTypedArray(),
            spacing = 12.dp,
        )
    }
}

@Composable
private fun LendingModePanel(org: Row) {
    val current = org.strOrNull("lending_mode") ?: "complete"
    var mode by remember { mutableStateOf(current) }
    var confirm by remember { mutableStateOf(false) }
    val target = LENDING_MODES.first { it.value == mode }
    val name = org.str("name")
    Panel(
        title = "Lending mode",
        description = "Controls the loan-creation workflow for every lender and staff member of this business. Users are never asked to choose it themselves.",
    ) {
        LendingModePicker(mode, { mode = it })
        val note = (org.strOrNull("lending_mode_changed_at")?.let { "Last changed ${fmtDateTime(it)}. " } ?: "") +
            "Loans already in progress keep their workflow; only new loans follow the selected mode."
        val apply: @Composable () -> Unit = { Btn("Apply ${target.label}", { confirm = true }, enabled = mode != current) }
        Spacer(Modifier.height(12.dp))
        if (isCompact()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(note, fontSize = 12.sp, color = LF.MutedForeground)
                apply()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(note, Modifier.weight(1f).padding(end = 12.dp), fontSize = 12.sp, color = LF.MutedForeground)
                apply()
            }
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = "Switch $name to ${target.label}?",
            onDismiss = { confirm = false },
            message = if (mode == "simplified")
                "All users will create loans with the simplified New Loan / Reloan-Renew workflow, using the interest rate and fees in the business settings. Existing loans are not changed."
            else "All users will create loans with the comprehensive workflow. Existing simplified loans keep their terms and continue through approval, signing and release.",
            confirmText = "Switch to ${target.label}",
            successMessage = "$name now uses ${target.label}",
            onConfirm = { PlatformService.setLendingMode(org.str("id"), mode) },
        )
    }
}
