package com.lendflow.app.feature.loans.simplified

// Agreement & in-person signature step of SimplifiedLoanPage.tsx: the generated PDF, the borrower and
// co-maker sign on this device, then the lender; release when the loan is ready.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.AgreementsService
import com.lendflow.app.data.BorrowersService
import com.lendflow.app.data.LoansService
import com.lendflow.app.data.Row
import com.lendflow.app.data.Supa
import com.lendflow.app.data.num
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.strings
import com.lendflow.app.feature.loans.ReleaseDialog
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CheckboxRow
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.LoanStatusBadge
import com.lendflow.app.ui.components.PageLoader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.PdfPages
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.SignaturePad
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberPdfViewer
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.components.rememberSignatureState
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Tone
import com.lendflow.app.util.peso

@Composable
internal fun RetryAgreement(loan: Row, onDone: (String) -> Unit) {
    val runner = rememberRunner()
    EmptyState(
        "Loan ${loan.str("loan_number")} saved",
        description = "The agreement has not been generated yet.",
        icon = Icons.Default.Draw,
        action = {
            Btn("Generate agreement", { runner.run { val a = AgreementsService.generate(loan.str("id")); onDone(a.str("id")) } }, loading = runner.busy, icon = Icons.Default.Draw)
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgreementStep(loan: Row, agreementId: String, borrowerName: String, onFinished: () -> Unit) {
    val auth by AppState.state.collectAsState()
    val router = LocalRouter.current
    val loanId = loan.str("id")
    val agreement = rememberLoad(agreementId) { AgreementsService.get(agreementId) }
    val signatures = rememberLoad(agreementId, "signatures") { AgreementsService.signatures(agreementId) }
    val a = agreement.data
    val pdfPath = a?.let { it.strOrNull("signed_pdf_path") ?: it.strOrNull("current_pdf_path") ?: it.strOrNull("unsigned_pdf_path") }
    // re-read after every signature (the signed PDF may be written to the same path)
    val pdf = rememberLoad(pdfPath, signatures.data?.size, a.str("status"), live = false, enabled = pdfPath != null) { Supa.download(AgreementsService.pdfUrl(pdfPath!!)) }
    val viewer = rememberPdfViewer()
    val loanView = rememberLoad(loanId) { LoansService.get(loanId) }
    val borrowerId = loanView.data.strOrNull("borrower_id")
    val guarantors = rememberLoad(borrowerId, enabled = borrowerId != null) { BorrowersService.guarantors(borrowerId!!) }
    val coMakerName = guarantors.data?.lastOrNull().str("full_name")
    var releaseOpen by remember { mutableStateOf(false) }
    val fullySigned = a.str("status") == "fully_signed"
    LaunchedEffect(fullySigned) { if (fullySigned) onFinished() }

    if ((a == null && agreement.error == null) || (signatures.data == null && signatures.error == null)) return PageLoader("Preparing the agreement…")
    if (agreement.error != null || a == null) return ErrorState(agreement.error ?: "Agreement not found", agreement.reload)

    val signed = signatures.data.orEmpty().map { it.str("signer_role") }.toSet()
    val required = a.strings("required_signers")
    // the co-maker signs right after the borrower, while the device is still with them
    val order = if (a.str("signing_order") == "lender_first") listOf("lender", "borrower", "co_maker") else listOf("borrower", "co_maker", "lender")
    val pending = order.filter { it in required && it !in signed }
    val next = pending.firstOrNull()
    val othersPending = required.filter { it !in signed && it !in order }
    val lv = loanView.data
    val done = a.str("status") == "fully_signed"
    val number = a.str("agreement_number")
    val renewalDeduction = loan.num("renewal_deduction")

    val document: @Composable ColumnScope.() -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(number, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterVertically))
            Pill(if (done) "Fully signed" else "Awaiting signatures", if (done) Tone.green else Tone.amber, modifier = Modifier.align(Alignment.CenterVertically))
            Text("Loan ${loan.str("loan_number")} · ${a.strOrNull("page_count") ?: "—"} pages", fontSize = 12.sp, color = LF.MutedForeground, modifier = Modifier.align(Alignment.CenterVertically))
            if (pdfPath != null) {
                Btn("Full screen", { viewer.openUrl(number, "$number.pdf") { AgreementsService.pdfUrl(pdfPath) } }, variant = BtnVariant.Ghost, small = true, icon = Icons.Default.Fullscreen)
            }
        }
        val shape = RoundedCornerShape(12.dp)
        Box(Modifier.fillMaxWidth().height(620.dp).clip(shape).background(LF.Slate100).border(1.dp, LF.Border, shape).padding(8.dp)) {
            val bytes = pdf.data
            when {
                pdfPath == null -> Text("The PDF is not available yet.", Modifier.align(Alignment.Center), fontSize = 14.sp, color = LF.MutedForeground)
                pdf.error != null -> ErrorState(pdf.error, pdf.reload)
                bytes == null -> PageLoader("Loading document…")
                else -> key(bytes) { PdfPages(bytes) }
            }
        }
    }

    val side: @Composable ColumnScope.() -> Unit = {
        Panel(title = "Loan saved") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Cell("Loan number", Modifier.weight(1f)) { Text(loan.str("loan_number"), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                    Cell("Status", Modifier.weight(1f)) { LoanStatusBadge(lv?.str("status") ?: loan.str("status")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Cell("Net to release", Modifier.weight(1f)) { Text(peso(loan.num("net_release")), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                    Cell("Deducted balance", Modifier.weight(1f)) { Text(peso(renewalDeduction), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }

        if (!done && next == "borrower") {
            if (auth.can("agreements.generate")) key("borrower") {
                SignBox(
                    title = "Borrower signature", signerName = borrowerName, agreementNumber = number,
                    consent = "I, $borrowerName, have reviewed loan agreement $number, including the loan amount, interest, total repayment" +
                        (if (renewalDeduction > 0) ", the deduction of my previous loan balance" else "") +
                        " and the payment schedule, and I sign it electronically. My electronic signature has the same legal effect as a handwritten signature (R.A. 8792).",
                    hint = "Hand the device to the borrower. They sign with a finger or stylus.",
                ) { png -> AgreementsService.signInApp(a.str("id"), "borrower", png, inPerson = true) }
            } else NoteSign("Borrower signature", "Your role cannot collect signatures.")
        }

        if (!done && next == "co_maker") {
            if (auth.can("agreements.generate")) key("co_maker") {
                SignBox(
                    title = "Co-maker signature", signerName = coMakerName, agreementNumber = number,
                    consent = "I, ${coMakerName.ifBlank { "the co-maker" }}, have reviewed loan agreement $number of $borrowerName, including the loan amount, interest, total repayment and the payment schedule, and I sign it electronically as co-maker. My electronic signature has the same legal effect as a handwritten signature (R.A. 8792).",
                    hint = "Now hand the device to the co-maker. They sign with a finger or stylus.",
                ) { png -> AgreementsService.signInApp(a.str("id"), "co_maker", png, inPerson = true) }
            } else NoteSign("Co-maker signature", "Your role cannot collect signatures.")
        }

        if (!done && next == "lender") {
            if (auth.can("agreements.sign_lender")) key("lender") {
                SignBox(
                    title = "Lender signature", signerName = auth.access.strOrNull("full_name") ?: "", agreementNumber = number,
                    consent = "I have reviewed agreement $number and agree to sign it electronically on behalf of the lender (R.A. 8792).",
                    hint = null,
                ) { png -> AgreementsService.signInApp(a.str("id"), "lender", png) }
            } else NoteSign("Lender signature", "The borrower has signed. A user with lender-signing rights completes the agreement from the loan page.")
        }

        if (!done && next == null && othersPending.isNotEmpty()) {
            NoteSign("Witness signatures", "Witness signatures are collected from the loan's Agreement tab.")
        }

        if (done) {
            Panel(title = "Agreement signed") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp), tint = LF.Green700)
                        Text("Signed agreement saved to the borrower's loan record.", Modifier.padding(start = 8.dp), fontSize = 14.sp, color = LF.Green700)
                    }
                    val st = lv?.str("status")
                    Text(
                        if (st == "ready_for_release") "The loan is approved and fully signed — ready for disbursement."
                        else "Next: ${if (st == "fully_signed") "approval by a user with approval rights, then release." else "continue on the loan page."}",
                        fontSize = 14.sp, color = LF.MutedForeground,
                    )
                    if (st == "ready_for_release" && auth.can("loans.release")) {
                        Btn("Release ${peso(loan.num("net_release"))}", { releaseOpen = true }, Modifier.fillMaxWidth().height(48.dp), icon = Icons.Default.Payments)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Open loan ${loan.str("loan_number")}", { router.go("/loans/$loanId") }, Modifier.fillMaxWidth(), variant = BtnVariant.Outline, icon = Icons.AutoMirrored.Filled.ArrowForward)
            if (!done && (next == "borrower" || next == "co_maker")) {
                Btn("Send a signing link instead", { router.go("/loans/$loanId?tab=agreement") }, Modifier.fillMaxWidth(), variant = BtnVariant.Ghost, icon = Icons.Default.Smartphone)
            }
        }
    }

    if (widthClass() == WidthClass.Expanded) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = document)
            Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = side)
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = document)
            side()
        }
    }

    if (releaseOpen && lv != null) {
        ReleaseDialog(lv, onDismiss = { releaseOpen = false }, onDone = { releaseOpen = false; router.go("/loans/$loanId") })
    }
}

@Composable
private fun Cell(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = LF.MutedForeground)
        Box(Modifier.padding(top = 2.dp)) { content() }
    }
}

@Composable
private fun NoteSign(title: String, text: String) {
    Panel(title = title) { Text(text, fontSize = 14.sp, color = LF.MutedForeground) }
}

/** Signature pad, consent and Sign button for one signer (signs in person on this device). */
@Composable
private fun SignBox(title: String, signerName: String, agreementNumber: String, consent: String, hint: String?, sign: suspend (String) -> Unit) {
    val sig = rememberSignatureState()
    var ok by remember { mutableStateOf(false) }
    val runner = rememberRunner()
    Panel(title = title, description = hint) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(signerName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            SignaturePad(sig)
            CheckboxRow(ok, { ok = it }, consent)
            Btn(
                "Sign agreement",
                {
                    runner.run(success = "$title embedded in $agreementNumber") {
                        val png = sig.toDataUrl() ?: throw IllegalStateException("Draw the signature first")
                        sign(png)
                    }
                },
                Modifier.fillMaxWidth().height(48.dp),
                enabled = !sig.isEmpty && ok && signerName.isNotBlank(), loading = runner.busy, icon = Icons.Default.Badge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CreditCard, null, Modifier.size(12.dp), tint = LF.MutedForeground)
                Text("The signature, time and witnessing staff member are recorded in the audit trail.", Modifier.padding(start = 4.dp), fontSize = 11.sp, color = LF.MutedForeground)
            }
        }
    }
}

@Suppress("unused") private val keepColor = Color.Unspecified
