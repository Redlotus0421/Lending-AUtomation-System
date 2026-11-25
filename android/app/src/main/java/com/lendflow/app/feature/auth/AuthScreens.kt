package com.lendflow.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.BuildConfig
import com.lendflow.app.app.AppState
import com.lendflow.app.app.BrandMark
import com.lendflow.app.data.AuthService
import com.lendflow.app.data.OrgService
import com.lendflow.app.data.Supa
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.LinkText
import com.lendflow.app.ui.components.Notice
import com.lendflow.app.ui.components.PasswordInput
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.card
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Tone

/** Navy brand panel on tablets, form on the right (AuthLayout on the web). */
@Composable
fun AuthLayout(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    val wide = widthClass() == WidthClass.Expanded
    Row(Modifier.fillMaxSize().background(LF.Background)) {
        if (wide) {
            Column(Modifier.weight(1.05f).fillMaxHeight().background(LF.Navy).statusBarsPadding().padding(48.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("LendFlow", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                Text("From application to collection — one secure lending workflow.", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp)
                Spacer(Modifier.height(28.dp))
                listOf(
                    Icons.Default.ShowChart to "Server-side flat and diminishing-balance calculations with full amortization schedules",
                    Icons.Default.Draw to "Agreements generated as PDFs and signed on the borrower’s phone — signatures embedded permanently",
                    Icons.Default.VerifiedUser to "Separate principal, interest, charges and penalty balances with an immutable ledger",
                ).forEach { (icon, text) ->
                    Row(Modifier.padding(bottom = 16.dp)) {
                        Icon(icon, null, Modifier.size(20.dp), tint = LF.Teal)
                        Spacer(Modifier.width(12.dp))
                        Text(text, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(32.dp))
                Text("Built for Philippine lenders · PHP · Asia/Manila", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().imePadding().verticalScroll(rememberScrollState()).statusBarsPadding().padding(20.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 400.dp).fillMaxWidth().padding(vertical = 24.dp)) {
                if (!wide) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(40.dp, onNavy = false)
                        Spacer(Modifier.width(10.dp))
                        Text("LendFlow", color = LF.Navy, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(32.dp))
                }
                Text(title, style = MaterialTheme.typography.headlineMedium)
                if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = LF.MutedForeground, modifier = Modifier.padding(top = 4.dp))
                if (!Supa.isConfigured) {
                    Spacer(Modifier.height(16.dp))
                    Notice("This build has no backend configured. Add SUPABASE_URL and SUPABASE_ANON_KEY to android/supabase.properties and rebuild.", Tone.amber)
                }
                Spacer(Modifier.height(24.dp))
                content()
            }
        }
    }
}

private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

@Composable
fun LoginScreen() {
    var forgot by rememberSaveable { mutableStateOf(false) }
    if (forgot) { ForgotPassword { forgot = false }; return }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var emailErr by remember { mutableStateOf<String?>(null) }
    var pwErr by remember { mutableStateOf<String?>(null) }
    val runner = rememberRunner()
    fun submit() {
        emailErr = if (!EMAIL.matches(email.trim())) "Enter a valid email" else null
        pwErr = if (password.isEmpty()) "Password is required" else null
        if (emailErr != null || pwErr != null) return
        runner.run(refresh = false) { AuthService.signIn(email, password) }
    }
    AuthLayout("Sign in", "Welcome back. Sign in to your lending workspace.") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Field("Email", error = emailErr) {
                TextInput(email, { email = it }, keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
            }
            Field("Password", error = pwErr) {
                PasswordInput(password, { password = it }, onImeAction = { submit() })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LinkText("Forgot password?", { forgot = true }, fontSize = 13.sp)
            }
            Btn("Sign in", { submit() }, Modifier.fillMaxWidth(), loading = runner.busy)
            Text("Need an account for your lending business? Contact your LendFlow administrator.", fontSize = 14.sp, color = LF.MutedForeground,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Text("LendFlow for Android · version ${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = LF.Slate400,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ForgotPassword(onBack: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(false) }
    val runner = rememberRunner()
    AuthLayout("Reset password", "We'll email you a link to set a new password.") {
        if (sent) {
            Notice("If an account exists for $email, a reset link is on its way.", Tone.green)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Field("Email") { TextInput(email, { email = it }, keyboardType = KeyboardType.Email, imeAction = ImeAction.Done) }
                Btn("Send reset link", {
                    if (email.isBlank()) return@Btn
                    runner.run(refresh = false) { AuthService.resetPassword(email); sent = true }
                }, Modifier.fillMaxWidth(), loading = runner.busy)
            }
        }
        Spacer(Modifier.height(24.dp))
        LinkText("Back to sign in", onBack)
    }
}

/** Shown after signing in with a temporary password set by an administrator. */
@Composable
fun ForcePasswordChangeScreen() {
    val auth by AppState.state.collectAsState()
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val runner = rememberRunner()
    AuthLayout("Choose your own password", "You signed in as ${auth.session?.email ?: ""} with a temporary password. Set a new one to continue.") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Field("New password", hint = "At least 10 characters") { PasswordInput(pw, { pw = it }, imeAction = ImeAction.Next) }
            Field("Confirm new password") { PasswordInput(pw2, { pw2 = it }) }
            Btn("Save and continue", {
                when {
                    pw.length < 10 -> Toasts.error("Use at least 10 characters")
                    pw != pw2 -> Toasts.error("The passwords do not match")
                    else -> runner.run(success = "Password updated", refresh = false) { AuthService.updatePassword(pw) }
                }
            }, Modifier.fillMaxWidth(), icon = Icons.Default.Key, loading = runner.busy)
        }
        Spacer(Modifier.height(24.dp))
        LinkText("Sign out", { AppState.signOut() })
    }
}

/** Shown to users whose lender account was suspended by the platform administrator. */
@Composable
fun AccountSuspendedScreen() {
    val auth by AppState.state.collectAsState()
    val org = auth.suspendedOrganization
    AuthLayout("Account suspended") {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(LF.Red50).border(1.dp, LF.Red200, RoundedCornerShape(10.dp)).padding(20.dp)) {
            Icon(Icons.Default.Block, null, Modifier.size(24.dp), tint = LF.Red600)
            Spacer(Modifier.height(8.dp))
            Text("The LendFlow account for ${org.strOrNull("name") ?: "your company"} is suspended, so its workspace can't be opened right now. Your records are kept safe and unchanged.",
                fontSize = 14.sp, color = LF.Red800)
            org.strOrNull("reason")?.let { Text("Reason: $it", fontSize = 14.sp, color = LF.Red800, modifier = Modifier.padding(top = 8.dp)) }
            Text("Please contact your LendFlow administrator.", fontSize = 14.sp, color = LF.Red800, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(24.dp))
        Btn("Sign out", { AppState.signOut() }, Modifier.fillMaxWidth(), variant = BtnVariant.Outline)
    }
}

/** A signed-in login that is not attached to any lender account. */
@Composable
fun NoAccountScreen() {
    val auth by AppState.state.collectAsState()
    val loadError = auth.access.str("load_error")
    AuthLayout("No lender account yet") {
        Column(Modifier.fillMaxWidth().card().padding(20.dp)) {
            Icon(Icons.Default.Apartment, null, Modifier.size(24.dp), tint = LF.Navy)
            Spacer(Modifier.height(8.dp))
            Text("You're signed in as ${auth.session?.email ?: ""}, but this login isn't linked to a lending business.", fontSize = 14.sp)
            Text("LendFlow accounts are created by the LendFlow administrator, and staff logins by the business owner. Ask them to set up your access, then sign in again.",
                fontSize = 14.sp, color = LF.MutedForeground, modifier = Modifier.padding(top = 8.dp))
        }
        if (loadError.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Notice("Your access could not be loaded: $loadError", Tone.amber)
            Spacer(Modifier.height(8.dp))
            Btn("Try again", { AppState.refreshAsync() }, Modifier.fillMaxWidth(), variant = BtnVariant.Outline)
        }
        Spacer(Modifier.height(24.dp))
        Btn("Sign out", { AppState.signOut() }, Modifier.fillMaxWidth(), variant = BtnVariant.Outline)
    }
}

/** My profile: name, mobile number, password. */
@Composable
fun ProfileScreen() {
    val auth by AppState.state.collectAsState()
    var name by rememberSaveable { mutableStateOf(auth.access.strOrNull("full_name") ?: "") }
    val profile = rememberLoad(auth.userId, live = false) { OrgService.profile() }
    var phoneEdit by rememberSaveable { mutableStateOf<String?>(null) }
    val phone = phoneEdit ?: profile.data.strOrNull("phone") ?: ""
    var pw by remember { mutableStateOf("") }
    val save = rememberRunner()
    val pwRunner = rememberRunner()
    LaunchedEffect(auth.access) { if (name.isBlank()) name = auth.access.strOrNull("full_name") ?: "" }
    Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("My profile", style = MaterialTheme.typography.headlineSmall)
        Column(Modifier.fillMaxWidth().card().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Field("Email") { TextInput(auth.session?.email ?: "", {}, enabled = false) }
            Field("Full name") { TextInput(name, { name = it }) }
            Field("Mobile number") { TextInput(phone, { phoneEdit = it }, placeholder = "09XX XXX XXXX", keyboardType = KeyboardType.Phone) }
            Btn("Save", {
                save.run(success = "Profile updated") {
                    OrgService.updateMyProfile(name, phone)
                    AppState.refresh()
                    phoneEdit = null
                    profile.reload()
                }
            }, loading = save.busy, enabled = !profile.initialLoading)
        }
        Column(Modifier.fillMaxWidth().card().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Field("New password", hint = "At least 10 characters") { PasswordInput(pw, { pw = it }) }
            Btn("Change password", {
                if (pw.length < 10) Toasts.error("Use at least 10 characters")
                else pwRunner.run(success = "Password changed", refresh = false) { AuthService.updatePassword(pw); pw = "" }
            }, variant = BtnVariant.Outline, loading = pwRunner.busy)
        }
        Text("LendFlow for Android · version ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = LF.MutedForeground)
    }
}
