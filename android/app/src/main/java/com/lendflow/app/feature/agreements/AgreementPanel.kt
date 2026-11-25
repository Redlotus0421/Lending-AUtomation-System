package com.lendflow.app.feature.agreements

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.data.AgreementsService
import com.lendflow.app.data.BorrowersService
import com.lendflow.app.data.DataBus
import com.lendflow.app.data.Row
import com.lendflow.app.data.Supa
import com.lendflow.app.data.bool
import com.lendflow.app.data.int
import com.lendflow.app.data.intOrNull
import com.lendflow.app.data.obj
import com.lendflow.app.data.readable
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.strings
import com.lendflow.app.ui.components.AgreementStatusBadge
import com.lendflow.app.ui.components.AppDialog
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CheckboxRow
import com.lendflow.app.ui.components.ConfirmDialog
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.Notice
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.SignaturePad
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.openUrl
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.components.rememberSignatureState
import com.lendflow.app.ui.components.saveToDownloads
import com.lendflow.app.ui.components.shareText
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Opt
import com.lendflow.app.util.SIGNER_LABEL
import com.lendflow.app.util.Tone
import com.lendflow.app.util.fmtDateTime
import com.lendflow.app.util.phMobile
import com.lendflow.app.util.printPdf
import com.lendflow.app.util.timeAgo
import kotlinx.coroutines.launch

private val OPEN = listOf("pending_signatures", "partially_signed")
private val ACTIVE_LINK = listOf("pending", "opened", "verified")

private fun signerLabel(role: String) = (SIGNER_LABEL[role] ?: role) + if (role.startsWith("witness")) " ${role.last()}" else ""

/** Agreement tab of the loan page (AgreementPanel.tsx). loan = v_loans row */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgreementPanel(loan: Row) {
    val auth by AppState.state.collectAsState()
    val loanId = loan.str("id")
    val loanStatus = loan.str("status")
    val agreements = rememberLoad(loanId) { AgreementsService.forLoan(loanId) }
    val list = agreements.data ?: emptyList()
    val current = list.firstOrNull { it.str("status") !in listOf("superseded", "voided", "failed") } ?: list.firstOrNull()
    var selectedId by remember(loanId) { mutableStateOf<String?>(null) }
    val shown = list.firstOrNull { it.str("id") == selectedId } ?: current
    var voidOpen by remember { mutableStateOf(false) }
    val runner = rememberRunner()

    val generate = {
        runner.run {
            val r = AgreementsService.generate(loanId)
            Toasts.success("Agreement ${r.str("agreement_number")} generated")
            val missing = r.strings("missing_placeholders")
            if (missing.isNotEmpty()) Toasts.info("Unknown placeholders left in template: ${missing.joinToString(", ")}")
            selectedId = null
        }
    }

    val canGenerateStatus = loanStatus in listOf("draft", "pending_review", "approved", "awaiting_borrower_signature", "awaiting_lender_signature")
    val activeLoan = loanStatus in listOf("active", "defaulted")
    val hasSigned = current != null && current.int("current_signature_count") > 0
    val generateDisabled = !auth.can("agreements.generate") || runner.busy || (!canGenerateStatus && !activeLoan)

    if (agreements.initialLoading) {
        Panel(title = "Loan agreement") { Spinner() }
        return
    }
    if (agreements.error != null && agreements.data == null) {
        Panel(title = "Loan agreement") { com.lendflow.app.ui.components.ErrorState(agreements.error, onRetry = agreements.reload) }
        return
    }

    if (current == null) {
        Panel(title = "Loan agreement") {
            EmptyState(
                "No agreement generated yet",
                icon = Icons.Default.Draw,
                description = if (canGenerateStatus) "Generate the agreement PDF from the selected template. Borrower details, loan terms, today’s date and the full schedule are filled in automatically."
                else "Agreements can be generated while the loan is in draft, review or approved status.",
                action = if (auth.can("agreements.generate") && canGenerateStatus) ({
                    Btn("Generate agreement", { generate() }, icon = Icons.Default.Draw, loading = runner.busy)
                }) else null,
            )
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (list.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Version:", fontSize = 14.sp, color = LF.MutedForeground)
                list.forEach { a ->
                    val sel = shown?.str("id") == a.str("id")
                    Box(
                        Modifier.clip(CircleShape).background(if (sel) LF.Blue50 else Color.White)
                            .border(1.dp, if (sel) LF.Blue else LF.Border, CircleShape)
                            .clickable { selectedId = a.str("id") }.padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Text("v${a.str("version")} · ${a.str("status").replace('_', ' ')}", fontSize = 12.sp, color = if (sel) LF.Blue700 else LF.Foreground)
                    }
                }
            }
        }
        if (shown != null) {
            androidx.compose.runtime.key(shown.str("id")) {
                AgreementDetail(shown, loan, isCurrent = shown.str("id") == current.str("id"))
            }
        }

        if (shown?.str("id") == current.str("id")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canGenerateStatus && auth.can("agreements.generate") && !hasSigned) {
                    Btn("Regenerate (new version)", { generate() }, variant = BtnVariant.Outline, icon = Icons.Default.Refresh, loading = runner.busy, enabled = !generateDisabled)
                }
                if (current.str("status") in OPEN && auth.can("agreements.generate")) {
                    Btn("Void agreement", { voidOpen = true }, variant = BtnVariant.Outline, icon = Icons.Default.Block)
                }
                if (activeLoan && auth.can("agreements.generate")) {
                    Btn("Generate amendment agreement", { generate() }, variant = BtnVariant.Outline, icon = Icons.Default.Draw, loading = runner.busy)
                }
            }
        }
    }

    if (voidOpen) {
        ConfirmDialog(
            title = "Void this agreement?",
            onDismiss = { voidOpen = false },
            message = "Voided agreements are kept for the audit trail (including any signature already collected). You can then edit the loan and generate a new version.",
            confirmText = "Void agreement",
            destructive = true,
            reasonLabel = "Reason",
            reasonPlaceholder = "This will be recorded in the audit trail",
            successMessage = "Agreement voided",
            onConfirm = { reason -> AgreementsService.void(current.str("id"), reason) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgreementDetail(a: Row, loan: Row, isCurrent: Boolean) {
    val auth by AppState.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = a.str("id")
    val signatures = rememberLoad(id) { AgreementsService.signatures(id) }
    val sessions = rememberLoad(id, enabled = auth.can("agreements.view")) { AgreementsService.sessions(id) }
    val pdfPath = a.strOrNull("signed_pdf_path") ?: a.strOrNull("current_pdf_path") ?: a.strOrNull("unsigned_pdf_path")
    val required = a.strings("required_signers")
    val needsCoMaker = "co_maker" in required
    val borrowerId = loan.str("borrower_id")
    val guarantors = rememberLoad(borrowerId, needsCoMaker, enabled = needsCoMaker) { if (needsCoMaker) BorrowersService.guarantors(borrowerId) else emptyList() }
    val coMaker = guarantors.data?.lastOrNull()
    var linkFor by remember { mutableStateOf<String?>(null) }
    var signRole by remember { mutableStateOf<String?>(null) }
    var showTrail by remember { mutableStateOf(false) }
    val open = a.str("status") in OPEN
    val signed = (signatures.data ?: emptyList()).associateBy { it.str("signer_role") }
    val needsRebuild = (signatures.data?.size ?: 0) > a.int("current_signature_count") && a.str("status") != "fully_signed"
    val rebuild = rememberRunner()
    val number = a.str("agreement_number")
    val signingOrder = a.str("signing_order")

    fun orderBlocked(role: String): String? {
        if (signingOrder == "borrower_first" && role == "lender" && "borrower" !in signed) return "The borrower signs first"
        if (signingOrder == "lender_first" && role == "borrower" && "lender" in required && "lender" !in signed) return "The lender signs first"
        return null
    }

    val downloadName = "$number${if (a.str("status") == "fully_signed") "-signed" else ""}.pdf"
    val wide = widthClass() == WidthClass.Expanded

    val document: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(number, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                AgreementStatusBadge(a.str("status"))
                if (a.str("kind") == "amendment") Pill("Amendment", Tone.violet)
                Text("v${a.str("version")} · ${a.intOrNull("page_count")?.toString() ?: "—"} pages · generated ${fmtDateTime(a.strOrNull("generated_at"))}", fontSize = 12.sp, color = LF.MutedForeground)
            }
            if (a.str("status") == "failed") Notice("Generation failed: ${a.str("error_message")}", Tone.red)
            if (a.str("status") == "voided") Notice("Voided ${fmtDateTime(a.strOrNull("voided_at"))} — ${a.str("void_reason")}", Tone.red)
            // re-render when a signature lands in the PDF
            val pdfKey = pdfPath?.let { "$it|${a.int("current_signature_count")}|${a.str("status")}" }
            InlinePdf(pdfKey, downloadName, number, 560.dp, load = { Supa.download(AgreementsService.pdfUrl(pdfPath!!)) })
        }
    }

    val side: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(title = "Signatures", description = "Signing order: ${signingOrder.replaceFirst('_', ' ')}") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    required.forEach { role ->
                        val s = signed[role]
                        val session = sessions.data?.firstOrNull { it.str("signer_role") == role && it.str("status") in ACTIVE_LINK }
                        val blocked = orderBlocked(role)
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, LF.Border, RoundedCornerShape(8.dp)).padding(12.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                if (s != null) Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp).padding(top = 1.dp), tint = LF.Green600)
                                else Icon(Icons.Default.RadioButtonUnchecked, null, Modifier.size(16.dp).padding(top = 1.dp), tint = LF.Slate200)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(signerLabel(role), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    val sub = when {
                                        s != null -> "${s.str("signer_name")} · ${fmtDateTime(s.strOrNull("signed_at"))}"
                                        session != null -> "Link ${session.str("status")} · expires ${fmtDateTime(session.strOrNull("expires_at"))}"
                                        else -> blocked ?: "Not signed yet"
                                    }
                                    Text(sub, fontSize = 12.sp, color = LF.MutedForeground)
                                }
                            }
                            if (s == null && open && isCurrent) {
                                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (role == "borrower" && auth.can("agreements.generate")) {
                                        Btn(if (session != null) "Share again" else "Send signing link", { linkFor = role }, small = true, icon = Icons.Default.Smartphone,
                                            variant = if (session != null) BtnVariant.Outline else BtnVariant.Primary)
                                    }
                                    if (role == "lender" && auth.can("agreements.sign_lender")) {
                                        Btn("Sign as lender", { signRole = "lender" }, small = true, icon = Icons.Default.Draw, enabled = blocked == null)
                                    }
                                    if (role == "co_maker" && auth.can("agreements.generate")) {
                                        Btn("Sign in person", { signRole = role }, small = true, variant = BtnVariant.Outline, icon = Icons.Default.Draw)
                                        Btn(if (session != null) "Share again" else "Send signing link", { linkFor = role }, small = true, icon = Icons.Default.Smartphone,
                                            variant = if (session != null) BtnVariant.Outline else BtnVariant.Ghost)
                                    }
                                    if (role.startsWith("witness") && auth.can("agreements.generate")) {
                                        Btn("Sign in person", { signRole = role }, small = true, variant = BtnVariant.Outline, icon = Icons.Default.Draw)
                                        Btn("Link", { linkFor = role }, small = true, variant = BtnVariant.Ghost, icon = Icons.Default.Link)
                                    }
                                }
                            }
                        }
                    }
                }
                if (needsRebuild && auth.can("agreements.generate")) {
                    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(LF.Amber50).padding(12.dp)) {
                        Text("A signature was committed but the signed PDF was not finalized (e.g. connection lost). The signature is safe.", fontSize = 12.sp, color = LF.Amber800)
                        Spacer(Modifier.height(8.dp))
                        Btn("Rebuild signed PDF", {
                            rebuild.run(success = "Signed PDF rebuilt from committed signatures") { AgreementsService.rebuild(id) }
                        }, Modifier.fillMaxWidth(), variant = BtnVariant.Outline, small = true, icon = Icons.Default.Build, loading = rebuild.busy)
                    }
                }
            }

            Panel(title = "Document integrity") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HashRow("Original (unsigned) SHA-256", a.strOrNull("unsigned_pdf_hash"))
                    a.strOrNull("signed_pdf_hash")?.let { HashRow("Final signed SHA-256", it) }
                    a.strOrNull("fully_signed_at")?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VerifiedUser, null, Modifier.size(14.dp), tint = LF.Green700)
                            Spacer(Modifier.width(6.dp))
                            Text("Fully signed ${fmtDateTime(it)} — locked against changes", fontSize = 12.sp, color = LF.Green700)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                val buttons = mutableListOf<@Composable (Modifier) -> Unit>()
                a.strOrNull("unsigned_pdf_path")?.let { p -> buttons += { m -> DownloadButton(p, "$number-unsigned.pdf", "Unsigned", Icons.Outlined.Description, false, m) } }
                a.strOrNull("signed_pdf_path")?.let { p -> buttons += { m -> DownloadButton(p, "$number-signed.pdf", "Signed PDF", Icons.Default.Download, true, m) } }
                if (pdfPath != null) {
                    buttons += { m ->
                        var busy by remember { mutableStateOf(false) }
                        Btn("Print", {
                            busy = true
                            scope.launch {
                                try { printPdf(context, downloadName, Supa.download(AgreementsService.pdfUrl(pdfPath))) }
                                catch (e: Exception) { Toasts.error(e.readable()) }
                                finally { busy = false }
                            }
                        }, m, variant = BtnVariant.Outline, small = true, icon = Icons.Default.Print, loading = busy)
                    }
                }
                buttons += { m -> Btn("Audit trail", { showTrail = true }, m, variant = BtnVariant.Outline, small = true, icon = Icons.Default.History) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    buttons.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { b -> b(Modifier.weight(1f)) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { document() }
            Box(Modifier.width(380.dp)) { side() }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { document(); side() }
    }

    linkFor?.let { role ->
        SigningLinkDialog(a, loan, role, coMaker) { linkFor = null; DataBus.bump(0) }
    }
    signRole?.let { role ->
        InAppSignDialog(a, role, coMaker?.str("full_name") ?: "") { done -> signRole = null; if (done) DataBus.bump(0) }
    }
    if (showTrail) AuditTrailDialog(a) { showTrail = false }
}

@Composable
private fun HashRow(label: String, value: String?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Fingerprint, null, Modifier.size(14.dp), tint = LF.MutedForeground)
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 12.sp, color = LF.MutedForeground)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            value ?: "—", Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(LF.Slate50).padding(6.dp),
            fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, lineHeight = 14.sp, color = LF.Slate700,
        )
    }
}

@Composable
private fun DownloadButton(path: String, name: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Btn(label, {
        busy = true
        scope.launch {
            try { saveToDownloads(context, name, Supa.download(AgreementsService.pdfUrl(path, name)), "application/pdf") }
            catch (e: Exception) { Toasts.error(e.readable()) }
            finally { busy = false }
        }
    }, modifier, variant = if (primary) BtnVariant.Primary else BtnVariant.Outline, small = true, icon = icon, loading = busy)
}

// ----------------------------------------------------------------------------- signing link
private data class LinkInfo(val url: String, val expires: String, val verification: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SigningLinkDialog(agreement: Row, loan: Row, role: String, coMaker: Row?, onClose: () -> Unit) {
    val auth by AppState.state.collectAsState()
    val context = LocalContext.current
    // the borrower's and co-maker's names come from the borrower record on the server
    val fixedName = role == "borrower" || role == "co_maker"
    val recipientName = if (role == "co_maker") coMaker?.str("full_name") ?: "" else loan.str("borrower_name")
    val recipientMobile = if (role == "co_maker") coMaker?.str("mobile_number") ?: "" else loan.str("borrower_mobile")
    val agreementSettings = auth.setting("agreement")
    var hours by remember { mutableStateOf(agreementSettings?.intOrNull("link_expiry_hours") ?: 72) }
    var name by remember { mutableStateOf(if (role == "borrower") loan.str("borrower_name") else "") }
    var link by remember { mutableStateOf<LinkInfo?>(null) }
    val agreementId = agreement.str("id")
    val sessions = rememberLoad(agreementId, "link-dialog") { AgreementsService.sessions(agreementId) }
    val create = rememberRunner()
    val revoke = rememberRunner()
    val number = agreement.str("agreement_number")
    val borrowerName = loan.str("borrower_name")
    val l = link
    val message = when {
        l == null -> ""
        role == "co_maker" -> "Hi ${recipientName.split(" ").first()}, please review and sign as co-maker the loan agreement $number of $borrowerName here: ${l.url} (valid until ${fmtDateTime(l.expires)})."
        else -> "Hi ${borrowerName.split(" ").first()}, please review and sign your loan agreement $number here: ${l.url} (valid until ${fmtDateTime(l.expires)})."
    }

    AppDialog(
        title = if (role == "borrower") "Borrower signing link" else "${SIGNER_LABEL[role] ?: role} signing link",
        onDismiss = onClose,
        description = "A secure, single-use, time-limited link. The signer reviews the agreement on their phone and signs with their finger.",
        footer = {
            if (l == null) {
                Btn("Close", onClose, variant = BtnVariant.Outline)
                Btn("Create link", {
                    create.run(refresh = false) {
                        val r = AgreementsService.createLink(agreementId, role, if (fixedName) null else name, null, hours)
                        link = LinkInfo(AgreementsService.signingLink(r.str("token")), r.str("expires_at"), r.str("verification_method"))
                    }
                }, icon = Icons.Default.Link, loading = create.busy, enabled = fixedName || name.isNotBlank())
            } else {
                Btn("Done", onClose)
            }
        },
    ) {
        if (l == null) {
            if (role == "co_maker") {
                Notice("Co-maker: ${recipientName.ifBlank { "—" }}${if (recipientMobile.isNotBlank()) " · ${phMobile(recipientMobile)}" else ""}. Creating a new link revokes any previous unused link.", Tone.blue)
            }
            if (!fixedName) Field("Signer name") { TextInput(name, { name = it }) }
            Field("Link valid for") {
                SelectField(hours.toString(), listOf(2, 24, 48, 72, 168).map { h ->
                    Opt(h.toString(), if (h < 24) "$h hours" else "${h / 24} day${if (h > 24) "s" else ""}")
                }, { hours = it.toInt() })
            }
            if (role == "borrower") {
                val iv = agreementSettings?.strOrNull("identity_verification")
                val check = when (iv) { "dob" -> "date of birth"; "id_last4" -> "last 4 characters of ID number"; else -> "none" }
                Notice("Identity check: $check. Creating a new link revokes any previous unused link. Mobile on file: ${phMobile(loan.strOrNull("borrower_mobile"))}.", Tone.blue)
            }
            val previous = sessions.data?.filter { it.str("signer_role") == role } ?: emptyList()
            if (previous.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("PREVIOUS LINKS", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LF.MutedForeground, letterSpacing = 0.5.sp)
                    previous.take(4).forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).border(1.dp, LF.Border, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val failed = s.int("failed_attempts")
                            Text("${s.str("status")} · created ${timeAgo(s.strOrNull("created_at"))}${if (failed > 0) " · $failed failed checks" else ""}", Modifier.weight(1f), fontSize = 12.sp)
                            if (s.str("status") in ACTIVE_LINK) {
                                Text("Revoke", Modifier.clickable(enabled = !revoke.busy) {
                                    revoke.run(success = "Link revoked") { AgreementsService.revokeLink(s.str("id")); sessions.reload() }
                                }.padding(4.dp), fontSize = 12.sp, color = LF.Red600)
                            }
                        }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextInput(l.url, {}, Modifier.weight(1f), readOnly = true)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { copyText(context, l.url, "Link copied") }, modifier = Modifier.size(48.dp).border(1.dp, LF.Input, RoundedCornerShape(8.dp))) {
                    Icon(Icons.Default.ContentCopy, "Copy link", Modifier.size(18.dp))
                }
            }
            val actions: List<@Composable (Modifier) -> Unit> = listOf(
                { m -> Btn("Share", { shareText(context, message, "Loan agreement") }, m, variant = BtnVariant.Outline, icon = Icons.Default.Share) },
                { m ->
                    Btn("SMS", {
                        try {
                            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${recipientMobile}")).putExtra("sms_body", message).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) { Toasts.error("No app can send SMS on this device.") }
                    }, m, variant = BtnVariant.Outline, icon = Icons.Default.Sms)
                },
                { m -> Btn("Message", { copyText(context, message, "Message copied — paste it in Messenger/Viber") }, m, variant = BtnVariant.Outline, icon = Icons.AutoMirrored.Filled.Message) },
                { m -> Btn("Open", { openUrl(context, l.url) }, m, variant = BtnVariant.Outline, icon = Icons.Default.OpenInNew) },
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.chunked(if (widthClass() == WidthClass.Compact) 2 else 4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { it(Modifier.weight(1f)) } }
                }
            }
            Text("Expires ${fmtDateTime(l.expires)}. The link is shown only once — create a new one if it is lost.", fontSize = 12.sp, color = LF.MutedForeground)
        }
    }
}

// ----------------------------------------------------------------------------- in-app signing
@Composable
private fun InAppSignDialog(agreement: Row, role: String, coMakerName: String, onClose: (Boolean) -> Unit) {
    val auth by AppState.state.collectAsState()
    val isCoMaker = role == "co_maker"
    val sig = rememberSignatureState()
    var consent by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    val runner = rememberRunner()
    val number = agreement.str("agreement_number")
    val signerName = when { role == "lender" -> auth.fullName; isCoMaker -> coMakerName; else -> name }
    val p = preview

    AppDialog(
        title = when { role == "lender" -> "Sign as lender"; isCoMaker -> "Co-maker signature (in person)"; else -> "Witness signature (in person)" },
        onDismiss = { if (!runner.busy) onClose(false) },
        description = "${if (isCoMaker) "Hand the device to ${coMakerName.ifBlank { "the co-maker" }}. " else ""}$number — the signature will be permanently embedded in the PDF with the signer's name and a timestamp.",
        dismissible = !runner.busy,
        maxWidth = 640.dp,
        footer = {
            if (p != null) {
                Btn("Redraw", { preview = null }, variant = BtnVariant.Outline, enabled = !runner.busy)
                Btn("Confirm & sign", {
                    runner.run {
                        val r = AgreementsService.signInApp(
                            agreement.str("id"), role, p,
                            signerName = if (role == "lender" || isCoMaker) null else name,
                            inPerson = isCoMaker,
                        )
                        Toasts.success(if (r.obj("result").bool("finalized")) "Agreement fully signed — final PDF sealed" else "Signature embedded in the agreement")
                        onClose(true)
                    }
                }, icon = Icons.Default.Draw, loading = runner.busy, enabled = consent && signerName.isNotBlank())
            } else {
                Btn("Cancel", { onClose(false) }, variant = BtnVariant.Outline)
                Btn("Preview signature", { preview = sig.toDataUrl() }, enabled = !sig.isEmpty && signerName.isNotBlank())
            }
        },
    ) {
        if (isCoMaker) Text(coMakerName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (role != "lender" && !isCoMaker) Field("Witness full name") { TextInput(name, { name = it }) }
        if (p == null) {
            SignaturePad(sig)
        } else {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, LF.Border, RoundedCornerShape(12.dp)).background(Color.White).padding(16.dp)) {
                Text("Signature preview", fontSize = 12.sp, color = LF.MutedForeground)
                Spacer(Modifier.height(8.dp))
                SignatureImage(p, maxHeight = 144.dp)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(LF.Border))
                Text(signerName, Modifier.fillMaxWidth().padding(top = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        CheckboxRow(consent, { consent = it },
            "I have reviewed agreement $number and agree to sign it electronically. My electronic signature has the same legal effect as a handwritten signature (R.A. 8792).")
    }
}

// ----------------------------------------------------------------------------- audit trail
private val EVENT_LABEL = mapOf(
    "generated" to "Agreement generated", "link_created" to "Signing link created", "link_revoked" to "Signing link revoked", "viewed" to "Signing page opened",
    "verification_failed" to "Identity check failed", "verified" to "Identity verified", "consent_given" to "Consent to e-sign given", "signed" to "Signed",
    "pdf_finalized" to "Final PDF sealed", "downloaded" to "Document downloaded", "voided" to "Agreement voided",
)

@Composable
private fun AuditTrailDialog(agreement: Row, onClose: () -> Unit) {
    val id = agreement.str("id")
    val events = rememberLoad(id, "events") { AgreementsService.events(id) }
    AppDialog(title = "Signing audit trail — ${agreement.str("agreement_number")}", onDismiss = onClose) {
        if (events.initialLoading) Spinner()
        else if (events.error != null) Text(events.error, color = LF.Red700, fontSize = 13.sp)
        else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            events.data?.forEach { e ->
                val type = e.str("event_type")
                val meta = e.obj("metadata")
                val dot = when (type) {
                    "verification_failed", "voided" -> LF.Red500
                    "signed", "pdf_finalized" -> LF.Green600
                    else -> LF.Slate400
                }
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        val signerRole = meta?.strOrNull("signer_role")
                        Text((EVENT_LABEL[type] ?: type) + (if (signerRole != null) " — ${SIGNER_LABEL[signerRole] ?: signerRole}" else ""), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        val actor = e.strOrNull("actor_name")
                        val ip = e.strOrNull("ip_address")
                        Text(fmtDateTime(e.strOrNull("created_at")) + (if (actor != null) " · $actor" else "") + (if (ip != null) " · IP $ip" else ""), fontSize = 12.sp, color = LF.MutedForeground)
                        meta?.strOrNull("reason")?.let { Text("Reason: $it", fontSize = 12.sp, color = LF.MutedForeground) }
                    }
                }
            }
        }
    }
}
