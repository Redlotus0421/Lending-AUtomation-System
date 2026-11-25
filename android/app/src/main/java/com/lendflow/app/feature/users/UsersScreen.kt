package com.lendflow.app.feature.users

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.data.Row
import com.lendflow.app.data.Supa
import com.lendflow.app.data.UsersService
import com.lendflow.app.data.bool
import com.lendflow.app.data.generatePassword
import com.lendflow.app.data.int
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.feature.settings.RadioCard
import com.lendflow.app.ui.components.Align
import com.lendflow.app.ui.components.Avatar
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CellRole
import com.lendflow.app.ui.components.CellText
import com.lendflow.app.ui.components.ChipRow
import com.lendflow.app.ui.components.Col
import com.lendflow.app.ui.components.ConfirmDialog
import com.lendflow.app.ui.components.AppDialog
import com.lendflow.app.ui.components.DataTable
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.LinkText
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.SkeletonRows
import com.lendflow.app.ui.components.Tabs
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.card
import com.lendflow.app.ui.components.copyToClipboard
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Opt
import com.lendflow.app.util.PERMISSIONS
import com.lendflow.app.util.ROLE_LABEL
import com.lendflow.app.util.Tone
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.initials
import com.lendflow.app.util.normalizePhMobile
import com.lendflow.app.util.timeAgo

// Port of src/features/users/UsersPage.tsx

private val ROLES = listOf("owner", "admin", "loan_officer", "collector")
private val ROLE_TONE = mapOf("owner" to Tone.navy, "admin" to Tone.blue, "loan_officer" to Tone.teal, "collector" to Tone.amber)
private val ROLE_DESC = mapOf(
    "owner" to "Full access, including users, settings, templates and audit logs.",
    "admin" to "Manages borrowers, loans, payments and agreements; views reports.",
    "loan_officer" to "Registers borrowers, prepares loan applications and agreements.",
    "collector" to "Sees assigned borrowers and records payments in the field.",
)
private val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
private val PH_MOBILE_RE = Regex("^\\+639\\d{9}$")

@Composable
internal fun RolePill(role: String?) {
    if (role != null) Pill(ROLE_LABEL[role] ?: role, ROLE_TONE[role] ?: Tone.slate, dot = false) else Pill("No role")
}

@Composable
fun UsersScreen() {
    val auth by AppState.state.collectAsState()
    val q = rememberLoad { UsersService.list() }
    var tab by rememberSaveable { mutableStateOf("staff") }
    var adding by remember { mutableStateOf(false) }
    var roleFor by remember { mutableStateOf<Row?>(null) }
    var activeFor by remember { mutableStateOf<Row?>(null) }
    var pwFor by remember { mutableStateOf<Row?>(null) }
    val isOwner = auth.role == "owner"
    val minPw = auth.setting("security")?.takeIf { it.strOrNull("password_min_length") != null }?.int("password_min_length") ?: 10
    val me = auth.access.strOrNull("user_id") ?: auth.userId

    val columns = listOf(
        Col<Row>("Name", weight = 2.2f, mobile = CellRole.Title, minWidth = 200.dp) { u ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isCompact()) {
                    Avatar(initials(u.str("full_name")), size = 36.dp, bg = LF.Navy.copy(alpha = 0.1f), fg = LF.Navy)
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(u.str("full_name").ifBlank { "—" }, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (u.str("id") == me) Text(" (you)", fontSize = 12.sp, color = LF.MutedForeground)
                    }
                    Text(u.str("email"), fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        Col<Row>("Role", mobile = CellRole.Meta, minWidth = 120.dp) { u -> RolePill(u.strOrNull("role")) },
        Col<Row>("Phone", mobile = CellRole.Subtitle, minWidth = 120.dp) { u -> CellText(u.strOrNull("phone"), muted = true) },
        Col<Row>("Status", mobile = CellRole.Meta, minWidth = 110.dp) { u -> if (u.bool("is_active")) Pill("Active", Tone.green) else Pill("Deactivated", Tone.slate) },
        Col<Row>("Last active", mobile = CellRole.Hidden, minWidth = 110.dp) { u -> CellText(u.strOrNull("last_seen_at")?.let { timeAgo(it) }, muted = true) },
        Col<Row>("Added", mobile = CellRole.Hidden, minWidth = 110.dp) { u -> CellText(fmtDate(u.strOrNull("created_at")), muted = true) },
        Col<Row>("", weight = 0.5f, align = Align.End, mobile = CellRole.Value, minWidth = 56.dp) { u ->
            if (u.str("id") != me) {
                UserActions(
                    u, isOwner,
                    onRole = { roleFor = u },
                    onPassword = { pwFor = u },
                    onActive = { activeFor = u },
                )
            }
        },
    )

    val counts = remember(q.data) {
        val c = mutableMapOf<String, Int>()
        q.data?.forEach { u -> val r = u.strOrNull("role"); if (u.bool("is_active") && r != null) c[r] = (c[r] ?: 0) + 1 }
        c
    }

    Column(Modifier.fillMaxWidth()) {
        PageHeader("Users & staff", "Create staff accounts, assign roles and control what each role can do.", actions = {
            Btn("Add staff", { adding = true }, icon = Icons.Default.PersonAdd)
        })
        Tabs(listOf(Opt("staff", "Staff"), Opt("permissions", "Roles & permissions")), tab, { tab = it })
        Spacer(Modifier.height(16.dp))
        if (tab == "staff") {
            val perRow = if (widthClass() == WidthClass.Expanded) 4 else 2
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ROLES.chunked(perRow).forEach { chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        chunk.forEach { r ->
                            Column(Modifier.weight(1f).card().padding(12.dp)) {
                                Text((ROLE_LABEL[r] ?: r).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground, letterSpacing = 0.4.sp)
                                Text("${counts[r] ?: 0}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                                Text(ROLE_DESC[r] ?: "", fontSize = 12.sp, color = LF.MutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }
                Panel(contentPadding = if (isCompact()) 12.dp else 16.dp) {
                    if (q.error != null && q.data == null) ErrorState(q.error, q.reload)
                    else DataTable(
                        columns, q.data, loading = q.loading,
                        empty = { EmptyState("No staff yet", description = "Add administrators, loan officers and collectors.", icon = Icons.Default.ManageAccounts) },
                    )
                }
            }
        } else {
            PermissionsMatrix(isOwner)
        }
    }

    if (adding) AddStaffDialog(onClose = { adding = false }, isOwner = isOwner, minPw = minPw)
    roleFor?.let { u -> RoleDialog(u, isOwner) { roleFor = null } }
    pwFor?.let { u -> ResetPasswordDialog(u, minPw) { pwFor = null } }
    activeFor?.let { u ->
        val active = u.bool("is_active")
        ConfirmDialog(
            title = if (active) "Deactivate ${u.str("full_name")}?" else "Reactivate ${u.str("full_name")}?",
            onDismiss = { activeFor = null },
            message = if (active) "They will lose access immediately and their collector assignments will be released. Their history is preserved."
            else "They will regain access with their current role.",
            destructive = active,
            confirmText = if (active) "Deactivate" else "Reactivate",
            successMessage = if (active) "User deactivated" else "User reactivated",
            onConfirm = { UsersService.setActive(u.str("id"), !active) },
        )
    }
}

@Composable
private fun UserActions(u: Row, isOwner: Boolean, onRole: () -> Unit, onPassword: () -> Unit, onActive: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val locked = u.strOrNull("role") == "owner" && !isOwner
    val active = u.bool("is_active")
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreHoriz, "User actions") }
        DropdownMenu(open, { open = false }, modifier = Modifier.background(Color.White)) {
            Text(u.str("full_name"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            DropdownMenuItem(
                text = { Text("Change role") }, enabled = !locked,
                leadingIcon = { Icon(Icons.Default.ManageAccounts, null) },
                onClick = { open = false; onRole() },
            )
            DropdownMenuItem(
                text = { Text("Reset password") },
                leadingIcon = { Icon(Icons.Default.Key, null) },
                onClick = { open = false; onPassword() },
            )
            HorizontalDivider(color = LF.Border)
            DropdownMenuItem(
                text = { Text(if (active) "Deactivate" else "Reactivate", color = if (active && !locked) LF.Danger else Color.Unspecified) },
                enabled = !locked,
                leadingIcon = { Icon(Icons.Default.PowerSettingsNew, null, tint = if (active && !locked) LF.Danger else LF.MutedForeground) },
                onClick = { open = false; onActive() },
            )
        }
    }
}

/** Visible-by-default password with a "generate" icon button (PasswordInput defaultVisible + RotateCcw). */
@Composable
private fun TempPasswordField(value: String, onChange: (String) -> Unit, readOnly: Boolean = false, showGenerate: Boolean = true) {
    var show by remember { mutableStateOf(true) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextInput(
                value, onChange, readOnly = readOnly, keyboardType = KeyboardType.Password,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (show) "Hide password" else "Show password", tint = LF.MutedForeground)
                    }
                },
            )
        }
        if (showGenerate) {
            Spacer(Modifier.width(8.dp))
            OutlinedIconButton(onClick = { onChange(generatePassword()); show = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Refresh, "Generate password")
            }
        }
    }
}

@Composable
private fun MonoBox(lines: List<String>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Slate50).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 14.sp) }
    }
}

@Composable
private fun AddStaffDialog(onClose: () -> Unit, isOwner: Boolean, minPw: Int) {
    val context = LocalContext.current
    val runner = rememberRunner()
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("loan_officer") }
    var password by remember { mutableStateOf(generatePassword()) }
    var submitted by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<Pair<String, String>?>(null) }

    val errors = buildMap {
        if (fullName.trim().length < 2) put("full_name", "Enter the full name")
        if (!EMAIL_RE.matches(email.trim())) put("email", "Enter a valid email")
        if (phone.trim().isNotEmpty() && !PH_MOBILE_RE.matches(normalizePhMobile(phone.trim()))) put("phone", "Enter a valid PH mobile number")
        if (password.length < minPw) put("password", "At least $minPw characters")
    }
    fun err(k: String) = if (submitted) errors[k] else null

    val c = created
    if (c != null) {
        AppDialog(
            title = "Staff account created",
            onDismiss = onClose,
            description = "Share these sign-in details securely. Ask them to change the password after signing in (My profile → Change password).",
            maxWidth = 460.dp,
            footer = {
                Btn("Copy", {
                    copyToClipboard(context, "Email: ${c.first}\nTemporary password: ${c.second}\nSign in: ${Supa.webUrl}/login")
                }, variant = BtnVariant.Outline)
                Btn("Done", onClose)
            },
        ) {
            MonoBox(listOf("Email: ${c.first}", "Temporary password: ${c.second}"))
        }
        return
    }

    AppDialog(
        title = "Add staff member",
        onDismiss = { if (!runner.busy) onClose() },
        description = "The account is active immediately — no email confirmation needed.",
        maxWidth = 460.dp,
        footer = {
            Btn("Cancel", onClose, variant = BtnVariant.Outline, enabled = !runner.busy)
            Btn("Create account", {
                submitted = true
                if (errors.isEmpty()) {
                    runner.run {
                        val e = email.trim()
                        UsersService.create(e, password, fullName.trim(), role, phone.trim().ifEmpty { null }?.let { normalizePhMobile(it) })
                        created = e to password
                    }
                }
            }, loading = runner.busy)
        },
    ) {
        Field("Full name", required = true, error = err("full_name")) { TextInput(fullName, { fullName = it }) }
        Field("Email", required = true, error = err("email")) { TextInput(email, { email = it }, keyboardType = KeyboardType.Email) }
        Field("Mobile number", error = err("phone")) { TextInput(phone, { phone = it }, keyboardType = KeyboardType.Phone) }
        Field("Role", hint = ROLE_DESC[role]) {
            SelectField(role, ROLES.filter { it != "owner" || isOwner }.map { Opt(it, ROLE_LABEL[it] ?: it) }, { role = it })
        }
        Field("Temporary password", error = err("password")) { TempPasswordField(password, { password = it }) }
    }
}

@Composable
private fun RoleDialog(user: Row, isOwner: Boolean, onClose: () -> Unit) {
    val runner = rememberRunner()
    val current = user.strOrNull("role")
    var role by remember { mutableStateOf(current ?: "collector") }
    AppDialog(
        title = "Change role — ${user.str("full_name")}",
        onDismiss = { if (!runner.busy) onClose() },
        description = "Permissions change immediately on their next action.",
        maxWidth = 460.dp,
        footer = {
            Btn("Cancel", onClose, variant = BtnVariant.Outline, enabled = !runner.busy)
            Btn("Save role", {
                runner.run(success = "Role updated") {
                    UsersService.setRole(user.str("id"), role)
                    onClose()
                }
            }, loading = runner.busy, enabled = role != current)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ROLES.filter { it != "owner" || isOwner }.forEach { r ->
                RadioCard(role == r, ROLE_LABEL[r] ?: r, ROLE_DESC[r]) { role = r }
            }
        }
    }
}

@Composable
private fun ResetPasswordDialog(user: Row, minPw: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val runner = rememberRunner()
    var pw by remember { mutableStateOf(generatePassword()) }
    var done by remember { mutableStateOf(false) }
    AppDialog(
        title = "Reset password — ${user.str("full_name")}",
        onDismiss = { if (!runner.busy) onClose() },
        description = if (done) "Password changed. Share the new password securely." else "Set a new temporary password for this user.",
        maxWidth = 460.dp,
        footer = {
            if (done) {
                Btn("Copy", { copyToClipboard(context, pw) }, variant = BtnVariant.Outline)
                Btn("Done", onClose)
            } else {
                Btn("Cancel", onClose, variant = BtnVariant.Outline, enabled = !runner.busy)
                Btn("Reset password", {
                    runner.run(success = "Password reset") {
                        UsersService.resetPassword(user.str("id"), pw)
                        done = true
                    }
                }, loading = runner.busy, enabled = pw.length >= minPw)
            }
        },
    ) {
        Field("New password", error = if (pw.length < minPw) "At least $minPw characters" else null) {
            TempPasswordField(pw, { if (!done) pw = it }, readOnly = done, showGenerate = !done)
        }
    }
}

// ---------------------------------------------------------------------------
private val EDITABLE = listOf("admin", "loan_officer", "collector")

@Composable
private fun PermissionsMatrix(isOwner: Boolean) {
    val q = rememberLoad { UsersService.rolePermissions() }
    var version by remember { mutableIntStateOf(0) }
    val data = q.data
    var draft by remember(data, version) { mutableStateOf(EDITABLE.associateWith { (data?.get(it) ?: emptyList()).toSet() }) }
    var saving by remember { mutableStateOf<String?>(null) }
    var resetRole by remember { mutableStateOf<String?>(null) }
    var phoneRole by rememberSaveable { mutableStateOf("admin") }
    val runner = rememberRunner()

    if (q.error != null && data == null) { ErrorState(q.error, q.reload); return }
    if (data == null) { SkeletonRows(); return }

    fun dirty(r: String): Boolean = draft[r] != (data[r] ?: emptyList()).toSet()
    fun toggle(r: String, p: String) {
        val cur = draft[r] ?: emptySet()
        draft = draft + (r to (if (p in cur) cur - p else cur + p))
    }
    fun save(r: String) {
        saving = r
        runner.run(success = "${ROLE_LABEL[r]} permissions saved") {
            try {
                UsersService.setRolePermissions(r, (draft[r] ?: emptySet()).toList())
            } finally {
                saving = null
            }
        }
    }
    val groups = PERMISSIONS.map { it.group }.distinct()
    val disabled = saving != null

    Panel(title = "Role permissions", description = "Owners always have every permission. Changes take effect immediately for all users with that role.") {
        if (isCompact()) {
            // Phones: one role at a time (the web table scrolls sideways on small screens)
            ChipRow(EDITABLE.map { Opt(it, ROLE_LABEL[it] ?: it) }, phoneRole, { phoneRole = it })
            Spacer(Modifier.height(8.dp))
            groups.forEach { g ->
                Text(g.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LF.Navy, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                PERMISSIONS.filter { it.group == g }.forEach { p ->
                    val locked = disabled || (p.key == "users.manage" && !isOwner)
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !locked) { toggle(phoneRole, p.key) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(p.key, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = LF.MutedForeground)
                        }
                        Checkbox(draft[phoneRole]?.contains(p.key) == true, { toggle(phoneRole, p.key) }, enabled = !locked, colors = CheckboxDefaults.colors(checkedColor = LF.Navy))
                    }
                    HorizontalDivider(color = LF.Border)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VerifiedUser, null, Modifier.size(14.dp), tint = LF.MutedForeground)
                Spacer(Modifier.width(4.dp))
                Text("Only owners can grant user management.", fontSize = 12.sp, color = LF.MutedForeground)
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LinkText("Reset defaults", { resetRole = phoneRole }, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Btn("Save", { save(phoneRole) }, small = true, loading = saving == phoneRole, enabled = dirty(phoneRole) && !disabled)
            }
        } else {
            val ownerW = 96.dp
            val roleW = 112.dp
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Column(Modifier.widthIn(min = 640.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("PERMISSION", Modifier.weight(1f).widthIn(min = 260.dp).padding(end = 12.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground, letterSpacing = 0.4.sp)
                        Text("OWNER", Modifier.width(ownerW), textAlign = TextAlign.Center, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground, letterSpacing = 0.4.sp)
                        EDITABLE.forEach { r ->
                            Text((ROLE_LABEL[r] ?: r).uppercase(), Modifier.width(roleW), textAlign = TextAlign.Center, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground, letterSpacing = 0.4.sp)
                        }
                    }
                    HorizontalDivider(color = LF.Border)
                    groups.forEach { g ->
                        Text(g.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LF.Navy, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                        val items = PERMISSIONS.filter { it.group == g }
                        items.forEachIndexed { i, p ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).widthIn(min = 260.dp).padding(end = 12.dp)) {
                                    Text(p.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(p.key, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = LF.MutedForeground)
                                }
                                Box(Modifier.width(ownerW), contentAlignment = Alignment.Center) {
                                    Checkbox(true, null, enabled = false, colors = CheckboxDefaults.colors(checkedColor = LF.Navy))
                                }
                                EDITABLE.forEach { r ->
                                    Box(Modifier.width(roleW), contentAlignment = Alignment.Center) {
                                        Checkbox(
                                            draft[r]?.contains(p.key) == true, { toggle(r, p.key) },
                                            enabled = !(disabled || (p.key == "users.manage" && !isOwner)),
                                            colors = CheckboxDefaults.colors(checkedColor = LF.Navy),
                                        )
                                    }
                                }
                            }
                            if (i < items.size - 1) HorizontalDivider(color = LF.Border)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                        Row(Modifier.weight(1f).widthIn(min = 260.dp).padding(end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VerifiedUser, null, Modifier.size(14.dp), tint = LF.MutedForeground)
                            Spacer(Modifier.width(4.dp))
                            Text("Only owners can grant user management.", fontSize = 12.sp, color = LF.MutedForeground)
                        }
                        Spacer(Modifier.width(ownerW))
                        EDITABLE.forEach { r ->
                            Column(Modifier.width(roleW), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Btn("Save", { save(r) }, small = true, loading = saving == r, enabled = dirty(r) && !disabled)
                                LinkText("Reset defaults", { resetRole = r }, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    resetRole?.let { r ->
        ConfirmDialog(
            title = "Reset ${ROLE_LABEL[r] ?: ""} permissions?",
            onDismiss = { resetRole = null },
            message = "The role will get LendFlow's recommended default permissions.",
            confirmText = "Reset",
            successMessage = "Permissions reset to defaults",
            onConfirm = {
                UsersService.resetRolePermissions(r)
                version++
            },
        )
    }
}

