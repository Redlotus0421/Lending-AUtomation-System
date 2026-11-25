package com.lendflow.app.feature.agreements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.AgreementsService
import com.lendflow.app.data.Row
import com.lendflow.app.data.int
import com.lendflow.app.data.objOrFirst
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.strings
import com.lendflow.app.ui.components.AgreementStatusBadge
import com.lendflow.app.ui.components.Align
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CellRole
import com.lendflow.app.ui.components.CellText
import com.lendflow.app.ui.components.Col
import com.lendflow.app.ui.components.DataTable
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.Pagination
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.SearchField
import com.lendflow.app.ui.components.Tabs
import com.lendflow.app.ui.components.rememberDebounced
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberPdfViewer
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Opt
import com.lendflow.app.util.Tone
import com.lendflow.app.util.fmtDateTime

private val TABS = listOf(
    Opt("all", "All"),
    Opt("pending_signatures", "Awaiting signatures"),
    Opt("partially_signed", "Partially signed"),
    Opt("fully_signed", "Fully signed"),
    Opt("voided", "Voided"),
    Opt("superseded", "Superseded"),
)

@Composable
fun AgreementsScreen() {
    val auth by AppState.state.collectAsState()
    val router = LocalRouter.current
    var status by remember { mutableStateOf("all") }
    var search by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    val term = rememberDebounced(search)
    val q = rememberLoad(status, term, page) { AgreementsService.list(status, term, page) }
    val pdf = rememberPdfViewer()

    fun download(a: Row) {
        val path = a.strOrNull("signed_pdf_path") ?: a.strOrNull("current_pdf_path") ?: a.strOrNull("unsigned_pdf_path") ?: return
        val name = "${a.str("agreement_number")}${if (a.strOrNull("signed_pdf_path") != null) "-signed" else ""}.pdf"
        pdf.openUrl(a.str("agreement_number"), name) { AgreementsService.pdfUrl(path, name) }
    }

    Column {
        PageHeader(
            "Loan Agreements",
            description = "Every generated agreement version with its signing status. Signed agreements are immutable.",
            actions = if (auth.can("templates.manage")) ({
                Btn("Agreement templates", { router.go("/agreements/templates") }, variant = BtnVariant.Outline, icon = Icons.Default.Dashboard)
            }) else null,
        )
        Panel(contentPadding = 12.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Tabs(TABS, status, { status = it; page = 0 })
                SearchField(search, { search = it; page = 0 }, placeholder = "Agreement number…")
            }
            Spacer(Modifier.height(12.dp))
            if (q.error != null && q.data == null) {
                ErrorState(q.error, onRetry = q.reload)
            } else {
                DataTable(
                    columns = listOf(
                        Col<Row>("Agreement", weight = 1.3f, mobile = CellRole.Title, minWidth = 150.dp) { a ->
                            Column {
                                Text(a.str("agreement_number"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("v${a.str("version")}${if (a.str("kind") == "amendment") " · amendment" else ""}", fontSize = 12.sp, color = LF.MutedForeground)
                            }
                        },
                        Col("Borrower", weight = 1.3f, mobile = CellRole.Subtitle, minWidth = 140.dp) { a ->
                            CellText(a.objOrFirst("loans")?.objOrFirst("borrowers")?.strOrNull("full_name") ?: "—")
                        },
                        Col("Loan", mobile = CellRole.Meta, minWidth = 120.dp) { a -> CellText(a.objOrFirst("loans")?.strOrNull("loan_number") ?: "—") },
                        Col("Signatures", mobile = CellRole.Meta, minWidth = 100.dp) { a ->
                            val count = a.int("current_signature_count")
                            val req = a.strings("required_signers").size
                            Pill("$count/$req", if (count >= req) Tone.green else Tone.slate, dot = false)
                        },
                        Col("Generated", mobile = CellRole.Hidden, minWidth = 150.dp) { a -> Text(fmtDateTime(a.strOrNull("generated_at")), fontSize = 12.sp) },
                        Col("Fully signed", mobile = CellRole.Hidden, minWidth = 150.dp) { a ->
                            val f = a.strOrNull("fully_signed_at")
                            if (f != null) Text(fmtDateTime(f), fontSize = 12.sp) else Text("—", color = LF.MutedForeground)
                        },
                        Col("Status", mobile = CellRole.Value, minWidth = 140.dp) { a -> AgreementStatusBadge(a.str("status")) },
                        Col("", align = Align.End, mobile = CellRole.Hidden, minWidth = 90.dp) { a ->
                            if (a.strOrNull("signed_pdf_path") != null || a.strOrNull("current_pdf_path") != null) {
                                Btn("PDF", { download(a) }, variant = BtnVariant.Ghost, small = true, icon = Icons.Default.Download)
                            }
                        },
                    ),
                    rows = q.data?.rows,
                    loading = q.loading,
                    onRowClick = { a -> router.go("/loans/${a.str("loan_id")}?tab=agreement") },
                    empty = {
                        EmptyState("No agreements", icon = Icons.Default.Draw,
                            description = "Agreements are generated from a loan’s Agreement tab or at the end of the loan wizard.")
                    },
                )
                Pagination(page, 25, q.data?.count ?: 0, { page = it })
            }
        }
    }
}
