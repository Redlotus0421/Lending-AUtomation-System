package com.lendflow.app.feature.admin

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.data.PlatformService
import com.lendflow.app.data.Row
import com.lendflow.app.data.Supa
import com.lendflow.app.data.generatePassword
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.feature.settings.Grid
import com.lendflow.app.feature.settings.cell
import com.lendflow.app.ui.components.AppDialog
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.SectionLabel
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.copyToClipboard
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.components.shareText
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Opt
import com.lendflow.app.util.ROLE_LABEL
import com.lendflow.app.util.Tone
import com.lendflow.app.util.normalizePhMobile
import kotlinx.coroutines.delay

// Port of src/features/admin/dialogs.tsx

@Composable
fun AccountStatusPill(status: String?) {
    if (status == "active") Pill("Active", Tone.green) else Pill("Suspended", Tone.red)
}

class LendingModeInfo(val value: String, val label: String, val icon: ImageVector, val description: String, val points: List<String>)

val LENDING_MODES = listOf(
    LendingModeInfo(
        "complete", "Complete Lending", Icons.Default.Checklist,
        "The comprehensive workflow: loan products, custom interest method and rate, fees, penalties, signing order and witnesses per loan.",
        listOf("Full borrower profiles", "Per-loan terms & products", "Review, approval, signing links, release"),
    ),
    LendingModeInfo(
        "simplified", "Simplified Lending", Icons.Default.Bolt,
        "A fast New Loan / Reloan-Renew flow. Staff enter only the amount and duration; rates and fees follow the business settings.",
        listOf("Quick borrower registration", "Automatic outstanding-balance deduction on renewal", "In-person signature on the device"),
    ),
)

@Composable
fun LendingModePill(mode: String?) {
    if (mode == "simplified") Pill("Simplified", Tone.teal, dot = false) else Pill("Complete", Tone.navy, dot = false)
}

/** Two-card picker for the business's lending mode (platform administrators only). */
@Composable
fun LendingModePicker(value: String, onChange: (String) -> Unit, disabled: Boolean = false) {
    Grid(
        2,
        *LENDING_MODES.map { m ->
            cell {
                val on = value == m.value
                val shape = RoundedCornerShape(12.dp)
                Column(
                    Modifier.fillMaxWidth().alpha(if (disabled) 0.6f else 1f).clip(shape)
                        .background(if (on) LF.Blue50.copy(alpha = 0.6f) else Color.White)
                        .border(2.dp, if (on) LF.Blue else LF.Slate200, shape)
                        .clickable(enabled = !disabled) { onChange(m.value) }
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(m.icon, null, Modifier.size(20.dp), tint = LF.Foreground)
                        Spacer(Modifier.width(8.dp))
                        Text(m.label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        if (on) Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = LF.Blue)
                    }
                    Text(m.description, fontSize = 12.sp, color = LF.MutedForeground, modifier = Modifier.padding(top = 4.dp))
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        m.points.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Check, null, Modifier.size(12.dp), tint = LF.Teal600)
                                Spacer(Modifier.width(6.dp))
                                Text(p, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }.toTypedArray(),
        spacing = 12.dp,
    )
}

/** Password input with show/hide and a generator. */
@Composable
private fun AdminPasswordInput(value: String, onChange: (String) -> Unit, invalid: Boolean = false) {
    var show by remember { mutableStateOf(true) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextInput(
                value, onChange, keyboardType = KeyboardType.Password, isError = invalid,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (show) "Hide password" else "Show password", tint = LF.MutedForeground)
                    }
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        Btn("Generate", { onChange(generatePassword()); show = true }, variant = BtnVariant.Outline, icon = Icons.Default.Refresh)
    }
}

data class Credentials(val title: String, val business: String?, val email: String, val password: String)

/**
 * The router only carries paths, so credentials of a just-created account travel to its detail page
 * through this in-memory holder (location.state on the web). Taken once by AccountDetailScreen.
 */
object PendingCredentials {
    private var pending: Pair<String, Credentials>? = null
    fun put(orgId: String, creds: Credentials) { pending = orgId to creds }
    fun take(orgId: String): Credentials? = pending?.takeIf { it.first == orgId }?.second?.also { pending = null }
}

/** Shown once after creating a login or resetting a password, so the admin can hand it over. */
@Composable
fun CredentialsDialog(creds: Credentials, onClose: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) { delay(1500); copied = null }
    }
    val loginUrl = "${Supa.webUrl}/login"
    val message = listOf(
        if (creds.business != null) "Your LendFlow account for ${creds.business} is ready." else "Your LendFlow login is ready.",
        "Sign in: $loginUrl",
        "Email: ${creds.email}",
        "Temporary password: ${creds.password}",
        "You will be asked to choose your own password the first time you sign in.",
    ).joinToString("\n")
    fun copy(key: String, text: String) {
        try { copyToClipboard(context, text); copied = key } catch (_: Exception) { Toasts.error("Could not copy. Select the text and copy it manually.") }
    }
    val rows = listOf(Triple("url", "Sign-in page", loginUrl), Triple("email", "Email", creds.email), Triple("password", "Temporary password", creds.password))
    AppDialog(
        title = creds.title,
        onDismiss = onClose,
        description = "Send these details to the user. The temporary password is shown only now; they must change it when they first sign in.",
        maxWidth = 460.dp,
        footer = {
            Btn("Copy message", { copy("all", message) }, variant = BtnVariant.Outline, icon = if (copied == "all") Icons.Default.Check else Icons.Default.ContentCopy)
            Btn("Share", { shareText(context, message, subject = creds.title) }, variant = BtnVariant.Outline, icon = Icons.Default.Share)
            Btn("Done", onClose)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(LF.Green50), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = LF.Green700)
            }
            Spacer(Modifier.width(8.dp))
            Text(creds.business ?: creds.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Foreground)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, LF.Border, RoundedCornerShape(8.dp))) {
            rows.forEachIndexed { i, (key, label, value) ->
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, fontSize = 12.sp, color = LF.MutedForeground)
                        Text(
                            value, fontSize = 14.sp,
                            fontWeight = if (key == "password") FontWeight.SemiBold else FontWeight.Medium,
                            fontFamily = if (key == "password") FontFamily.Monospace else FontFamily.Default,
                        )
                    }
                    IconButton(onClick = { copy(key, value) }) {
                        if (copied == key) Icon(Icons.Default.Check, "Copied", tint = LF.Green600) else Icon(Icons.Default.ContentCopy, "Copy $label", tint = LF.MutedForeground)
                    }
                }
                if (i < rows.size - 1) HorizontalDivider(color = LF.Border)
            }
        }
    }
}

private val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

private fun passwordError(pw: String): String? = when {
    pw.length < 10 -> "At least 10 characters"
    pw.length > 72 -> "At most 72 characters"
    else -> null
}

/** normalizePhMobile(v) ?? v on the web: the cleaned number when it is a PH mobile, else what was typed. */
private fun phoneOrNull(v: String): String? = v.trim().ifEmpty { null }?.let { normalizePhMobile(it) }

/** Create a lender account: the business (organization) and its owner login. */
@Composable
fun CreateAccountDialog(onClose: () -> Unit, onCreated: (orgId: String, creds: Credentials) -> Unit) {
    val runner = rememberRunner()
    val keys = listOf("name", "legal_name", "registration_no", "tin", "address", "city", "province", "contact_phone", "owner_name", "owner_email", "owner_phone")
    var values by remember { mutableStateOf(keys.associateWith { "" }) }
    var password by remember { mutableStateOf(generatePassword()) }
    var mode by remember { mutableStateOf("complete") }
    var submitted by remember { mutableStateOf(false) }
    fun v(k: String) = values[k] ?: ""
    fun set(k: String, x: String) { values = values + (k to x) }
    fun opt(k: String) = v(k).trim().ifEmpty { null }
    val errors = buildMap {
        if (v("name").trim().length < 2) put("name", "Enter the business name")
        if (v("owner_name").trim().length < 2) put("owner_name", "Enter the owner's full name")
        if (!EMAIL_RE.matches(v("owner_email").trim().lowercase())) put("owner_email", "Enter a valid email")
        passwordError(password)?.let { put("password", it) }
    }
    fun err(k: String) = if (submitted) errors[k] else null

    AppDialog(
        title = "Create lender account",
        onDismiss = { if (!runner.busy) onClose() },
        description = "Sets up a new lending business with its own private workspace: default loan products, an agreement template and role permissions. The owner can then add their own staff.",
        maxWidth = 720.dp,
        footer = {
            Btn("Cancel", onClose, variant = BtnVariant.Outline, enabled = !runner.busy)
            Btn("Create account", {
                submitted = true
                if (errors.isEmpty()) {
                    runner.run {
                        val name = v("name").trim()
                        val ownerEmail = v("owner_email").trim().lowercase()
                        val r = PlatformService.createAccount(
                            organization = mapOf(
                                "name" to name, "legal_name" to opt("legal_name"), "registration_no" to opt("registration_no"), "tin" to opt("tin"),
                                "address" to opt("address"), "city" to opt("city"), "province" to opt("province"), "contact_phone" to opt("contact_phone"),
                                "contact_email" to ownerEmail, "lending_mode" to mode,
                            ).filterValues { it != null },
                            owner = mapOf(
                                "full_name" to v("owner_name").trim(), "email" to ownerEmail, "phone" to phoneOrNull(v("owner_phone")), "password" to password,
                            ).filterValues { it != null },
                        )
                        Toasts.success("$name is ready")
                        onCreated(r.str("organization_id"), Credentials("Account created", name, r.strOrNull("email") ?: ownerEmail, password))
                    }
                }
            }, loading = runner.busy)
        },
    ) {
        SectionLabel("Business")
        Grid(
            2,
            cell { Field("Business / trade name", required = true, error = err("name")) { TextInput(v("name"), { set("name", it) }, placeholder = "e.g. Bayanihan Lending") } },
            cell { Field("Registered legal name", hint = "As registered with SEC/DTI") { TextInput(v("legal_name"), { set("legal_name", it) }) } },
            cell { Field("SEC reg. / Certificate of Authority no.") { TextInput(v("registration_no"), { set("registration_no", it) }) } },
            cell { Field("TIN") { TextInput(v("tin"), { set("tin", it) }, placeholder = "000-000-000-000") } },
            cell(2) { Field("Business address") { TextInput(v("address"), { set("address", it) }, placeholder = "Unit / street / barangay") } },
            cell { Field("City / municipality") { TextInput(v("city"), { set("city", it) }) } },
            cell { Field("Province") { TextInput(v("province"), { set("province", it) }) } },
            cell { Field("Business phone") { TextInput(v("contact_phone"), { set("contact_phone", it) }, placeholder = "+63 2 8xxx xxxx", keyboardType = KeyboardType.Phone) } },
            spacing = 12.dp,
        )
        SectionLabel("Lending mode", Modifier.padding(top = 8.dp))
        LendingModePicker(mode, { mode = it }, disabled = runner.busy)
        Text("Decides the loan-creation workflow for every user of this business. You can change it later.", fontSize = 12.sp, color = LF.MutedForeground)
        SectionLabel("Owner login", Modifier.padding(top = 8.dp))
        Grid(
            2,
            cell { Field("Owner's full name", required = true, error = err("owner_name")) { TextInput(v("owner_name"), { set("owner_name", it) }) } },
            cell { Field("Mobile number") { TextInput(v("owner_phone"), { set("owner_phone", it) }, placeholder = "0917 123 4567", keyboardType = KeyboardType.Phone) } },
            cell { Field("Email (used to sign in)", required = true, error = err("owner_email")) { TextInput(v("owner_email"), { set("owner_email", it) }, keyboardType = KeyboardType.Email) } },
            cell {
                Field("Temporary password", required = true, error = err("password"), hint = "They must change it at first sign-in.") {
                    AdminPasswordInput(password, { password = it }, invalid = err("password") != null)
                }
            },
            spacing = 12.dp,
        )
    }
}

private val ROLES = listOf("owner", "admin", "loan_officer", "collector")

/** Add another login to an existing account (e.g. a second owner, or when the owner is locked out). */
@Composable
fun AddUserDialog(orgId: String, orgName: String, onClose: () -> Unit, onCreated: (Credentials) -> Unit) {
    val runner = rememberRunner()
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf(generatePassword()) }
    var submitted by remember { mutableStateOf(false) }
    val errors = buildMap {
        if (fullName.trim().length < 2) put("full_name", "Enter the full name")
        if (!EMAIL_RE.matches(email.trim().lowercase())) put("email", "Enter a valid email")
        passwordError(password)?.let { put("password", it) }
    }
    fun err(k: String) = if (submitted) errors[k] else null
    AppDialog(
        title = "Add login to $orgName",
        onDismiss = { if (!runner.busy) onClose() },
        description = "Owners normally add their own staff. Use this to add a second owner or restore access.",
        maxWidth = 560.dp,
        footer = {
            Btn("Cancel", onClose, variant = BtnVariant.Outline, enabled = !runner.busy)
            Btn("Create login", {
                submitted = true
                if (errors.isEmpty()) {
                    runner.run {
                        val e = email.trim().lowercase()
                        val r = PlatformService.addUser(orgId, fullName.trim(), e, phoneOrNull(phone), role, password)
                        onCreated(Credentials("Login created", orgName, r.strOrNull("email") ?: e, password))
                    }
                }
            }, loading = runner.busy)
        },
    ) {
        Grid(
            2,
            cell { Field("Full name", required = true, error = err("full_name")) { TextInput(fullName, { fullName = it }) } },
            cell { Field("Role", required = true) { SelectField(role, ROLES.map { Opt(it, ROLE_LABEL[it] ?: it) }, { role = it }) } },
            cell { Field("Email", required = true, error = err("email")) { TextInput(email, { email = it }, keyboardType = KeyboardType.Email) } },
            cell { Field("Mobile number") { TextInput(phone, { phone = it }, keyboardType = KeyboardType.Phone) } },
            cell(2) { Field("Temporary password", required = true, error = err("password")) { AdminPasswordInput(password, { password = it }, invalid = err("password") != null) } },
            spacing = 12.dp,
        )
    }
}

/** user: a platform member row (id, full_name, email). */
@Composable
fun AdminResetPasswordDialog(user: Row, orgName: String, onClose: () -> Unit, onDone: (Credentials) -> Unit) {
    val runner = rememberRunner()
    var pw by remember { mutableStateOf(generatePassword()) }
    val who = user.str("full_name").ifBlank { user.str("email") }
    AppDialog(
        title = "Reset password",
        onDismiss = { if (!runner.busy) onClose() },
        description = "Set a temporary password for $who. Their current password stops working immediately.",
        maxWidth = 460.dp,
        footer = {
            Btn("Cancel", onClose, variant = BtnVariant.Outline, enabled = !runner.busy)
            Btn("Reset password", {
                if (pw.length < 10) { Toasts.error("Use at least 10 characters"); return@Btn }
                runner.run {
                    PlatformService.resetPassword(user.str("id"), pw)
                    onDone(Credentials("Password reset", orgName, user.str("email"), pw))
                }
            }, icon = Icons.Default.Key, loading = runner.busy)
        },
    ) {
        Field("Temporary password", hint = "They must change it at next sign-in.") { AdminPasswordInput(pw, { pw = it }) }
    }
}
