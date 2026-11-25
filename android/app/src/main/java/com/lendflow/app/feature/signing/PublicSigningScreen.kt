package com.lendflow.app.feature.signing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.data.JsonCodec
import com.lendflow.app.data.PublicSigning
import com.lendflow.app.data.Row
import com.lendflow.app.data.Supa
import com.lendflow.app.data.ApiError
import com.lendflow.app.data.bool
import com.lendflow.app.data.has
import com.lendflow.app.data.intOrNull
import com.lendflow.app.data.num
import com.lendflow.app.data.numOrNull
import com.lendflow.app.data.obj
import com.lendflow.app.data.objs
import com.lendflow.app.data.parseJson
import com.lendflow.app.data.readable
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.strings
import com.lendflow.app.data.toJson
import com.lendflow.app.feature.agreements.InlinePdf
import com.lendflow.app.feature.agreements.SignatureImage
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CheckboxRow
import com.lendflow.app.ui.components.DateField
import com.lendflow.app.ui.components.SignaturePad
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.rememberPdfViewer
import com.lendflow.app.ui.components.rememberSignatureState
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.FREQUENCY_LABEL
import com.lendflow.app.util.METHOD_LABEL
import com.lendflow.app.util.PERIOD_LABEL
import com.lendflow.app.util.SIGNER_LABEL
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.fmtDateTime
import com.lendflow.app.util.peso
import com.lendflow.app.util.trimNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// Port of src/features/signing/PublicSigningPage.tsx — the borrower / co-maker signing link (no login):
// load → identity check → review the summary and the full PDF → consent → draw + confirm → sign → download.

private enum class Step { review, consent, sign, done }

/** sessionStorage on the web: the verification nonce lives as long as the app process. */
private object SigningNonces {
    val map = HashMap<String, String>()
}

private val PageBg = Color(0xFFF5F7FB)

/**
 * The "verify" action answers 200 { ok: false, error, attempts_left } on a wrong answer; the shared
 * Supa.invoke turns any `error` field into an exception (losing attempts_left), so call it directly.
 */
private suspend fun verifyIdentity(token: String, answer: String): Row = withContext(Dispatchers.IO) {
    val payload = mapOf("action" to "verify", "token" to token, "answer" to answer).toJson().toString()
    val bearer = Supa.accessToken() ?: Supa.anonKey
    val req = Request.Builder().url("${Supa.url}/functions/v1/public-signing")
        .header("apikey", Supa.anonKey).header("Authorization", "Bearer $bearer")
        .post(payload.toRequestBody("application/json".toMediaType())).build()
    try {
        Supa.http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            val json = try { parseJson(text) as? JsonObject } catch (_: Exception) { null }
            if (r.code !in 200..299) throw ApiError(json?.strOrNull("error") ?: json?.strOrNull("message") ?: "Verification failed.")
            json ?: JsonObject(emptyMap())
        }
    } catch (e: IOException) {
        throw ApiError("Network error — check your internet connection and try again.", "network")
    }
}

/** Borrower / co-maker signing link (no login). onClose returns to the app */
@Composable
fun PublicSigningScreen(token: String, onClose: () -> Unit) {
    val storageKey = "lf-sign-${token.take(16)}"
    var ctx by remember(token) { mutableStateOf<Row?>(null) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var loading by remember(token) { mutableStateOf(true) }
    var nonce by remember(token) { mutableStateOf(SigningNonces.map[storageKey]) }
    var step by remember(token) { mutableStateOf(Step.review) }
    val scope = rememberCoroutineScope()

    suspend fun loadDocument(n: String): Boolean = try {
        val full = PublicSigning.document(token, n)
        ctx = full
        if (full.obj("session").str("status") == "signed") step = Step.done
        true
    } catch (e: Exception) {
        val msg = e.readable()
        if (Regex("verify your identity", RegexOption.IGNORE_CASE).containsMatchIn(msg)) {
            SigningNonces.map.remove(storageKey)
            nonce = null
        } else error = msg
        false
    }

    LaunchedEffect(token) {
        loading = true
        try {
            val base = PublicSigning.load(token)
            ctx = base
            val n = nonce
            if (n != null && base.obj("session").str("status") in listOf("verified", "signed", "opened", "pending")) loadDocument(n)
        } catch (e: Exception) {
            error = e.readable()
        } finally {
            loading = false
        }
    }

    BackHandler {
        when (step) {
            Step.consent -> step = Step.review
            Step.sign -> step = Step.consent
            else -> onClose()
        }
    }

    val c = ctx
    val lender = c?.obj("lender")?.strOrNull("name")
    when {
        loading && c == null -> Shell(onClose) {
            Centered { Spinner(24.dp); Spacer(Modifier.height(12.dp)); Text("Opening your agreement…", fontSize = 14.sp, color = LF.MutedForeground) }
        }
        error != null && c == null -> Shell(onClose) { Problem("We couldn't open this link", error!!) }
        c == null -> Shell(onClose) {}
        else -> {
            val s = c.obj("session")
            val status = s.str("status")
            if (status == "expired" || status == "revoked") {
                Shell(onClose, lender) {
                    Problem(
                        if (status == "expired") "This signing link has expired" else "This signing link is no longer valid",
                        "For your security, signing links are time-limited and single-use. Please ask your lender to send you a new link.",
                    )
                }
                return
            }
            val n = nonce
            if (!c.has("loan") || n == null) {
                var answer by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                var msg by remember { mutableStateOf<String?>(null) }
                val method = s.str("verification_method")
                val submit = {
                    busy = true
                    msg = null
                    scope.launch {
                        try {
                            val r = verifyIdentity(token, if (method == "none") "" else answer)
                            val got = r.strOrNull("nonce")
                            if (r.bool("ok") && got != null) {
                                SigningNonces.map[storageKey] = got
                                nonce = got
                                loading = true
                                loadDocument(got)
                                loading = false
                            } else {
                                val left = r.intOrNull("attempts_left")
                                msg = (r.strOrNull("error") ?: "Verification failed.") + if (left != null && left > 0) " $left attempt(s) left." else ""
                            }
                        } catch (e: Exception) {
                            msg = e.readable()
                        } finally {
                            busy = false
                        }
                    }
                    Unit
                }
                Shell(onClose, lender, bottom = {
                    Btn("Continue", { submit() }, Modifier.weight(1f).height(48.dp), icon = Icons.AutoMirrored.Filled.ArrowForward, loading = busy || loading,
                        enabled = method == "none" || answer.isNotBlank())
                }) {
                    Verify(c, method, answer, { answer = it }, msg)
                }
                return
            }
            when (step) {
                Step.review -> {
                    var opened by remember { mutableStateOf(false) }
                    var read by remember { mutableStateOf(false) }
                    Shell(onClose, lender, step, bottom = {
                        Btn("Continue", { step = Step.consent }, Modifier.weight(1f).height(48.dp), icon = Icons.AutoMirrored.Filled.ArrowForward, enabled = read)
                    }) {
                        error?.let { ErrorBox(it) }
                        Review(c, opened, { opened = true }, read, { read = it })
                    }
                }
                Step.consent -> {
                    var agree by remember { mutableStateOf(false) }
                    val a = c.obj("agreement")
                    val lenderSigned = c.objs("signatures").any { it.str("signer_role") == "lender" }
                    val blocked = a.str("signing_order") == "lender_first" && s.str("signer_role") == "borrower" && "lender" in a.strings("required_signers") && !lenderSigned
                    Shell(onClose, lender, step, bottom = {
                        Btn("", { step = Step.review }, Modifier.height(48.dp), variant = BtnVariant.Outline, icon = Icons.AutoMirrored.Filled.ArrowBack)
                        Btn("Continue to sign", { step = Step.sign }, Modifier.weight(1f).height(48.dp), icon = Icons.Default.Draw, enabled = agree && !blocked)
                    }) {
                        error?.let { ErrorBox(it) }
                        Heading("Consent to sign electronically", "Your electronic signature will be permanently embedded in the PDF.")
                        Card { Text(c.str("consent_text"), fontSize = 14.sp, lineHeight = 22.sp) }
                        Card(padding = 4.dp) { CheckboxRow(agree, { agree = it }, "I agree and consent to sign electronically.") }
                        if (blocked) Tinted("The lender must sign first. Please come back to this link once you're notified.", LF.Amber50, LF.Amber800)
                    }
                }
                Step.sign -> {
                    val sig = rememberSignatureState()
                    var preview by remember { mutableStateOf<String?>(null) }
                    var typed by remember { mutableStateOf("") }
                    var busy by remember { mutableStateOf(false) }
                    val signerName = s.str("signer_name")
                    val role = s.str("signer_role")
                    fun norm(x: String) = x.trim().lowercase().replace(Regex("\\s+"), " ")
                    val nameOk = role != "borrower" || norm(typed) == norm(signerName)
                    val p = preview
                    Shell(onClose, lender, step, bottom = {
                        if (p != null) {
                            Btn("Redraw", { preview = null }, Modifier.height(48.dp), variant = BtnVariant.Outline, enabled = !busy)
                            Btn(if (busy) "Signing…" else "Confirm & sign", {
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        PublicSigning.sign(token, n, p, typed)
                                        loadDocument(n)
                                        step = Step.done
                                    } catch (e: Exception) {
                                        error = e.readable()
                                    } finally {
                                        busy = false
                                    }
                                }
                            }, Modifier.weight(1f).height(48.dp), variant = BtnVariant.Teal, icon = Icons.Default.CheckCircle, loading = busy, enabled = nameOk)
                        } else {
                            Btn("", { step = Step.consent }, Modifier.height(48.dp), variant = BtnVariant.Outline, icon = Icons.AutoMirrored.Filled.ArrowBack)
                            Btn("Preview signature", { preview = sig.toDataUrl() }, Modifier.weight(1f).height(48.dp), enabled = !sig.isEmpty)
                        }
                    }) {
                        error?.let { ErrorBox(it) }
                        Heading(
                            if (p != null) "Confirm your signature" else "Draw your signature",
                            if (p != null) "This exact signature will be placed on the agreement." else "Use your finger or a stylus. Turn your phone sideways for more space.",
                        )
                        if (p == null) {
                            SignaturePad(sig, height = 260.dp)
                        } else {
                            Card {
                                SignatureImage(p, maxHeight = 160.dp)
                                HorizontalDivider(Modifier.padding(top = 12.dp), color = LF.Border)
                                Text(signerName, Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                                Text("${SIGNER_LABEL[role] ?: role} · ${c.obj("agreement").str("agreement_number")}", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                    fontSize = 12.sp, color = LF.MutedForeground)
                            }
                            if (role == "borrower") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Type your full name to confirm", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    TextInput(typed, { typed = it }, placeholder = signerName)
                                    if (typed.isNotEmpty() && !nameOk) Text("Must match: $signerName", fontSize = 12.sp, color = LF.Red600)
                                }
                            }
                        }
                    }
                }
                Step.done -> Done(c, token, n, lender, onClose)
            }
        }
    }
}

// ----------------------------------------------------------------------------- layout
@Composable
private fun Shell(
    onClose: () -> Unit,
    lender: String? = null,
    step: Step? = null,
    bottom: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(PageBg)) {
        Column(Modifier.fillMaxWidth().background(LF.Navy).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().widthIn(max = 672.dp).align(Alignment.CenterHorizontally).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.VerifiedUser, null, Modifier.size(20.dp), tint = LF.Teal)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(lender ?: "Secure document signing", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, Modifier.size(11.dp), tint = Color.White.copy(alpha = 0.6f))
                        Spacer(Modifier.width(4.dp))
                        Text("Secure, encrypted signing session", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            }
            if (step != null) {
                val steps = Step.entries
                Row(Modifier.fillMaxWidth().widthIn(max = 672.dp).align(Alignment.CenterHorizontally).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.forEachIndexed { i, _ ->
                        Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (steps.indexOf(step) >= i) LF.Teal else Color.White.copy(alpha = 0.2f)))
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 672.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
                Spacer(Modifier.height(24.dp))
            }
        }
        if (bottom != null) {
            Column(Modifier.fillMaxWidth().background(Color.White)) {
                HorizontalDivider(color = LF.Border)
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().widthIn(max = 672.dp).align(Alignment.CenterHorizontally)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = bottom,
                )
            }
        } else {
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content)
}

@Composable
private fun Problem(title: String, message: String) {
    Centered {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0xFFFEF3C7)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Warning, null, Modifier.size(28.dp), tint = LF.Amber600)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(message, Modifier.widthIn(max = 380.dp).padding(top = 8.dp), fontSize = 14.sp, color = LF.MutedForeground, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Card(padding: androidx.compose.ui.unit.Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Color.White).border(1.dp, LF.Border, shape).padding(padding), content = content)
}

@Composable
private fun Heading(title: String, sub: String) {
    Column {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, fontSize = 14.sp, color = LF.MutedForeground)
    }
}

@Composable
private fun Tinted(text: String, bg: Color, fg: Color) {
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg).padding(12.dp), fontSize = 14.sp, color = fg)
}

@Composable
private fun ErrorBox(text: String) = Tinted(text, LF.Red50, LF.Red700)

// ----------------------------------------------------------------------------- steps
@Composable
private fun Verify(ctx: Row, method: String, answer: String, onAnswer: (String) -> Unit, msg: String?) {
    val s = ctx.obj("session")
    val a = ctx.obj("agreement")
    val lender = ctx.obj("lender").str("name")
    val signed = s.str("status") == "signed"
    Column {
        Text("Hi ${s.str("signer_name")},", fontSize = 14.sp, color = LF.MutedForeground)
        Text("$lender has sent you ${if (signed) "your signed" else "a"} loan agreement ${if (signed) "" else "to review and sign"}".trim(),
            Modifier.padding(top = 4.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
        Text(buildAnnotatedString {
            append("Agreement ${a.str("agreement_number")} · You are signing as ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(SIGNER_LABEL[s.str("signer_role")] ?: s.str("signer_role")) }
        }, Modifier.padding(top = 8.dp), fontSize = 14.sp, color = LF.MutedForeground)
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VerifiedUser, null, Modifier.size(16.dp), tint = LF.Teal)
            Spacer(Modifier.width(8.dp))
            Text("Confirm it's you", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (method == "none") {
            Text("Tap continue to open the document.", Modifier.padding(top = 8.dp), fontSize = 14.sp, color = LF.MutedForeground)
        } else {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (method == "dob") "Your date of birth" else "Last 4 characters of your ID number", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (method == "dob") DateField(answer, onAnswer, placeholder = "Select your date of birth", maxDate = com.lendflow.app.util.todayManila())
                else TextInput(answer, { onAnswer(it.take(12)) }, placeholder = "e.g. 3456")
                Text("This protects your personal and loan information.", fontSize = 12.sp, color = LF.MutedForeground)
            }
        }
        if (msg != null) { Spacer(Modifier.height(12.dp)); ErrorBox(msg) }
    }
    s.strOrNull("expires_at")?.let {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = LF.MutedForeground)
            Spacer(Modifier.width(6.dp))
            Text("Link valid until ${fmtDateTime(it)}", fontSize = 12.sp, color = LF.MutedForeground)
        }
    }
}

@Composable
private fun SumRow(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = LF.MutedForeground)
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = if (strong) 16.sp else 14.sp, fontWeight = if (strong) FontWeight.SemiBold else null, textAlign = TextAlign.End)
    }
}

@Composable
private fun Review(ctx: Row, opened: Boolean, onOpened: () -> Unit, read: Boolean, onRead: (Boolean) -> Unit) {
    val l = ctx.obj("loan")
    val number = ctx.obj("agreement").str("agreement_number")
    Heading("Review your loan", "Please read the summary and the complete agreement before signing.")
    Card {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LF.Navy).padding(16.dp)) {
            Text("YOU WILL PAY", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f), letterSpacing = 0.5.sp)
            Text(buildAnnotatedString {
                append(peso(l.num("installment_amount")))
                withStyle(SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, color = Color.White.copy(alpha = 0.7f))) {
                    append(" × ${l.str("installment_count")} ${FREQUENCY_LABEL[l.str("repayment_frequency")] ?: l.str("repayment_frequency")}")
                }
            }, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("From ${fmtDate(l.strOrNull("first_due_date"))} to ${fmtDate(l.strOrNull("last_due_date"))}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(12.dp))
        val rows = buildList {
            add(Triple("Loan amount", peso(l.num("principal")), false))
            add(Triple("Amount you receive", peso(l.num("net_proceeds")), false))
            add(Triple("Interest", "${trimNumber(l.num("interest_rate"))}% per ${PERIOD_LABEL[l.str("rate_period")] ?: ""} · ${METHOD_LABEL[l.str("interest_method")] ?: ""}", false))
            add(Triple("Total interest", peso(l.num("total_interest")), false))
            if (l.num("total_charges") > 0) add(Triple("Fees & charges", peso(l.num("total_charges")), false))
            add(Triple("Effective interest rate", l.numOrNull("eir_monthly_pct")?.let { "${trimNumber(it, 2)}% / month" } ?: "—", false))
            add(Triple("Total amount to repay", peso(l.num("total_repayment")), true))
        }
        rows.forEachIndexed { i, (k, v, strong) ->
            if (i > 0) HorizontalDivider(color = LF.Border)
            SumRow(k, v, strong)
        }
    }
    Column {
        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Full agreement", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        val url = ctx.strOrNull("pdf_url")
        InlinePdf(url, "$number.pdf", number, 480.dp, load = { Supa.download(url!!) }, onInteract = onOpened)
        Text("Tap ⤢ for full screen. Pinch to zoom.", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = LF.MutedForeground)
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, LF.Border, RoundedCornerShape(12.dp))
        .then(if (opened) Modifier else Modifier.background(Color.White.copy(alpha = 0.4f))).padding(4.dp)) {
        CheckboxRow(read, onRead, "I have opened and read the entire agreement, including the Disclosure Statement and Repayment Schedule.", enabled = opened)
    }
    if (!opened) Text("Open the agreement above to continue.", Modifier.padding(top = 0.dp), fontSize = 12.sp, color = LF.MutedForeground)
}

@Composable
private fun Done(ctx: Row, token: String, nonce: String, lender: String?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pdf = rememberPdfViewer()
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val a = ctx.obj("agreement")
    val number = a.str("agreement_number")
    val fully = a.str("status") == "fully_signed"
    val sigs = ctx.objs("signatures")
    val required = a.strings("required_signers")
    val pending = required.filter { r -> sigs.none { it.str("signer_role") == r } }
    Shell(onClose, lender, Step.done, bottom = {
        Btn("Download ${if (fully) "signed agreement" else "agreement (so far)"}", {
            busy = true
            err = null
            scope.launch {
                try {
                    val r = PublicSigning.download(token, nonce)
                    val url = r.str("url")
                    val name = "$number${if (fully) "-signed" else ""}.pdf"
                    pdf.open(number, name) { Supa.download(url) }
                } catch (e: Exception) {
                    err = e.readable()
                } finally {
                    busy = false
                }
            }
        }, Modifier.weight(1f).height(48.dp), icon = Icons.Default.Download, loading = busy)
    }) {
        Centered {
            Box(Modifier.size(64.dp).clip(CircleShape).background(LF.Green50), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(36.dp), tint = LF.Green600)
            }
            Spacer(Modifier.height(16.dp))
            Text("Thank you — you've signed", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Your signature is permanently embedded in agreement $number." +
                    if (fully) " All parties have signed. Download your copy below." else " Waiting for: ${pending.joinToString(", ") { SIGNER_LABEL[it] ?: it }}.",
                Modifier.widthIn(max = 380.dp).padding(top = 8.dp), fontSize = 14.sp, color = LF.MutedForeground, textAlign = TextAlign.Center,
            )
        }
        Card {
            Text("Signing status", Modifier.padding(bottom = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                required.forEach { r ->
                    val s = sigs.firstOrNull { it.str("signer_role") == r }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s != null) Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = LF.Green600)
                        else Icon(Icons.Default.Schedule, null, Modifier.size(16.dp), tint = LF.Slate400)
                        Spacer(Modifier.width(8.dp))
                        Text(SIGNER_LABEL[r] ?: r, Modifier.weight(1f), fontSize = 14.sp)
                        Text(if (s != null) fmtDateTime(s.strOrNull("signed_at")) else "Pending", fontSize = 12.sp, color = LF.MutedForeground)
                    }
                }
            }
        }
        err?.let { ErrorBox(it) }
        Text("You can return to this link until it expires to download your agreement.", Modifier.fillMaxWidth(), fontSize = 12.sp, color = LF.MutedForeground, textAlign = TextAlign.Center)
    }
}

@Suppress("unused") private fun keepCodec() = JsonCodec
