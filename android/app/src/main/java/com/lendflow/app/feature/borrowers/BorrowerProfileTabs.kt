package com.lendflow.app.feature.borrowers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.AgreementsService
import com.lendflow.app.data.BorrowersService
import com.lendflow.app.data.LoansService
import com.lendflow.app.data.PickedFile
import com.lendflow.app.data.Row as JRow
import com.lendflow.app.data.int
import com.lendflow.app.data.num
import com.lendflow.app.data.numOrNull
import com.lendflow.app.data.objOrFirst
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.ui.components.Align
import com.lendflow.app.ui.components.AgreementStatusBadge
import com.lendflow.app.ui.components.AppDialog
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CellRole
import com.lendflow.app.ui.components.CellText
import com.lendflow.app.ui.components.Col
import com.lendflow.app.ui.components.ConfirmDialog
import com.lendflow.app.ui.components.DataTable
import com.lendflow.app.ui.components.DateField
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.Field
import com.lendflow.app.ui.components.KV
import com.lendflow.app.ui.components.KeyValue
import com.lendflow.app.ui.components.LinkText
import com.lendflow.app.ui.components.LoanStatusBadge
import com.lendflow.app.ui.components.MoneyInput
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.components.TextInput
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.rememberPdfViewer
import com.lendflow.app.ui.components.rememberRunner
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.DOCUMENT_TYPES
import com.lendflow.app.util.Opt
import com.lendflow.app.util.RELATIONSHIPS
import com.lendflow.app.util.Tone
import com.lendflow.app.util.durationLabel
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.fmtDateTime
import com.lendflow.app.util.normalizePhMobile
import com.lendflow.app.util.peso
import com.lendflow.app.util.phMobile
import com.lendflow.app.util.timeAgo
import com.lendflow.app.util.titleCase
import com.lendflow.app.util.trimNumber
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.max
import kotlin.math.roundToInt

// Tabs of src/features/borrowers/BorrowerProfilePage.tsx

private fun JRow.isOpenLoan() = str("status") in listOf("active", "defaulted")

// ---------------------------------------------------------------------------
// Loans
// ---------------------------------------------------------------------------
@Composable
internal fun BorrowerLoansTable(loans: List<JRow>?, loading: Boolean, onOpen: (JRow) -> Unit) {
    val columns = listOf(
        Col<JRow>("Loan #", mobile = CellRole.Title, minWidth = 120.dp) { l -> CellText(l.str("loan_number"), bold = true) },
        Col<JRow>("Product", weight = 1.8f, mobile = CellRole.Subtitle, minWidth = 180.dp) { l ->
            val product = l.strOrNull("product_name")?.ifBlank { null } ?: titleCase(l.strOrNull("interest_method"))
            Text("$product · ${durationLabel(l.int("term_count"), l.str("repayment_frequency"))}", fontSize = if (isCompact()) 12.sp else 14.sp,
                color = if (isCompact()) LF.MutedForeground else LF.Foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        Col<JRow>("Principal", align = Align.End, mobile = CellRole.Value, minWidth = 110.dp) { l -> CellText(peso(l.num("principal")), bold = isCompact()) },
        Col<JRow>("Outstanding", align = Align.End, mobile = CellRole.Meta, minWidth = 110.dp) { l ->
            MetaText(if (l.isOpenLoan()) peso(l.num("total_outstanding")) else "—")
        },
        Col<JRow>("Next due", mobile = CellRole.Meta, minWidth = 100.dp) { l -> MetaText(l.strOrNull("next_due_date")?.ifBlank { null }?.let { fmtDate(it) } ?: "—") },
        Col<JRow>("Overdue", align = Align.End, mobile = CellRole.Meta, minWidth = 100.dp) { l ->
            if (l.num("overdue_amount") > 0) Text(peso(l.num("overdue_amount")), fontSize = if (isCompact()) 12.sp else 14.sp, fontWeight = FontWeight.Medium, color = LF.Red600)
            else MetaText("—")
        },
        Col<JRow>("Status", mobile = CellRole.Meta, minWidth = 120.dp) { l -> LoanStatusBadge(l.strOrNull("status")) },
        Col<JRow>("Created", mobile = CellRole.Hidden, minWidth = 100.dp) { l -> CellText(fmtDate(l.strOrNull("created_at")), muted = true) },
    )
    DataTable(
        columns, loans, loading = loading, onRowClick = onOpen,
        empty = { EmptyState("No loans yet", description = "Loans created for this borrower will appear here.", icon = Icons.Default.Paid) },
    )
}

/** Plain value in a table cell (14sp) or on a phone card meta line (12sp muted). */
@Composable
private fun MetaText(text: String) {
    val compact = isCompact()
    Text(text, fontSize = if (compact) 12.sp else 14.sp, color = if (compact) LF.MutedForeground else LF.Foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

// ---------------------------------------------------------------------------
// Payments
// ---------------------------------------------------------------------------
@Composable
internal fun PaymentsTab(borrowerId: String) {
    val router = LocalRouter.current
    val q = rememberLoad(borrowerId) { BorrowersService.payments(borrowerId) }
    if (q.error != null && q.data == null) { ErrorState(q.error, q.reload); return }
    val rows = q.data
    val posted = rows.orEmpty().filter { it.str("status") == "posted" }
    val total = posted.sumOf { it.num("amount") }
    val columns = listOf(
        Col<JRow>("Date", mobile = CellRole.Subtitle, minWidth = 100.dp) { p -> MetaText(fmtDate(p.strOrNull("payment_date"))) },
        Col<JRow>("Payment #", mobile = CellRole.Title, minWidth = 130.dp) { p -> CellText(p.str("payment_number"), bold = true) },
        Col<JRow>("Loan", mobile = CellRole.Meta, minWidth = 110.dp) { p -> MetaText(p.objOrFirst("loans").strOrNull("loan_number") ?: "—") },
        Col<JRow>("Method", mobile = CellRole.Meta, minWidth = 100.dp) { p -> MetaText(titleCase(p.strOrNull("method"))) },
        Col<JRow>("Principal / interest", weight = 1.4f, mobile = CellRole.Hidden, minWidth = 160.dp) { p ->
            Text("${peso(p.num("principal_applied"))} / ${peso(p.num("interest_applied"))}", fontSize = 12.sp, color = LF.MutedForeground)
        },
        Col<JRow>("Amount", align = Align.End, mobile = CellRole.Value, minWidth = 110.dp) { p ->
            val reversed = p.str("status") == "reversed"
            Text(peso(p.num("amount")), fontSize = 14.sp, fontWeight = if (reversed) FontWeight.Normal else FontWeight.Medium,
                color = if (reversed) LF.MutedForeground else LF.Foreground, textDecoration = if (reversed) TextDecoration.LineThrough else null)
        },
        Col<JRow>("Status", mobile = CellRole.Meta, minWidth = 110.dp) { p ->
            when {
                p.str("status") == "reversed" -> Pill("Reversed", Tone.red)
                p.str("payment_type") == "settlement" -> Pill("Settlement", Tone.teal)
                else -> Pill("Posted", Tone.green)
            }
        },
    )
    Panel(
        title = "Payment history",
        description = if (!rows.isNullOrEmpty()) "${peso(total)} collected across ${posted.size} payments" else null,
    ) {
        DataTable(
            columns, rows, loading = q.loading, onRowClick = { p -> router.go("/payments/${p.str("id")}") },
            empty = { EmptyState("No payments recorded", icon = Icons.Default.AccountBalanceWallet) },
        )
    }
}

// ---------------------------------------------------------------------------
// Documents
// ---------------------------------------------------------------------------
@Composable
internal fun DocumentsTab(borrowerId: String, viewer: BorrowerFileViewer) {
    val auth by AppState.state.collectAsState()
    val q = rememberLoad(borrowerId) { BorrowersService.documents(borrowerId) }
    var type by remember { mutableStateOf("proof_of_income") }
    var title by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<PickedFile?>(null) }
    var toDelete by remember { mutableStateOf<JRow?>(null) }
    val upload = rememberRunner()
    val canUpload = auth.can("borrowers.edit") || auth.can("borrowers.create")
    fun typeLabel(t: String) = DOCUMENT_TYPES.firstOrNull { it.value == t }?.label ?: titleCase(t)

    val list: @Composable (Modifier) -> Unit = { m ->
        Panel(m, title = "Supporting documents") {
            val docs = q.data
            when {
                q.error != null && docs == null -> ErrorState(q.error)
                docs == null -> Spinner()
                docs.isEmpty() -> EmptyState("No documents", description = "Upload payslips, proof of billing, business permits and other requirements.", icon = Icons.Default.Description)
                else -> Column {
                    docs.forEachIndexed { i, d ->
                        if (i > 0) HorizontalDivider(color = LF.Border)
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(LF.Blue50), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Description, null, Modifier.size(20.dp), tint = LF.Blue600)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(d.strOrNull("title")?.ifBlank { null } ?: d.str("file_name"), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    color = LF.Foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val size = d.numOrNull("file_size")?.takeIf { it > 0 }?.let { "${max(1, (it / 1024).roundToInt())} KB · " } ?: ""
                                Text("${typeLabel(d.str("document_type"))} · $size${fmtDate(d.strOrNull("created_at"))}", fontSize = 12.sp, color = LF.MutedForeground)
                            }
                            IconButton(onClick = {
                                viewer.open(d.str("file_path"), d.strOrNull("title")?.ifBlank { null } ?: d.str("file_name"), d.strOrNull("mime_type"))
                            }) { Icon(Icons.Default.Visibility, "Open document", tint = LF.Foreground) }
                            if (auth.can("borrowers.edit")) {
                                IconButton(onClick = { toDelete = d }) { Icon(Icons.Default.Delete, "Delete document", tint = LF.Red600) }
                            }
                        }
                    }
                }
            }
        }
    }
    val uploader: @Composable (Modifier) -> Unit = { m ->
        Panel(m, title = "Upload document") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Document type") { SelectField(type, DOCUMENT_TYPES, { type = it }) }
                Field("Title", hint = "Optional — defaults to the file name") { TextInput(title, { title = it }) }
                FileDrop(
                    onFile = { file = it },
                    label = file?.name ?: "Choose an image or PDF (max 10 MB)",
                    picked = file,
                )
                Btn("Upload", {
                    upload.run(success = "Document uploaded") {
                        val f = file
                        val orgId = auth.organizationId ?: auth.organization.strOrNull("id")
                        if (f == null || orgId == null) throw IllegalStateException("Choose a file to upload")
                        if (f.size > MAX_BORROWER_FILE) throw IllegalStateException(FILE_TOO_LARGE)
                        BorrowersService.addDocument(orgId, borrowerId, f, type, title.trim().ifEmpty { null })
                        file = null
                        title = ""
                    }
                }, Modifier.fillMaxWidth(), icon = Icons.Default.Upload, loading = upload.busy, enabled = file != null)
            }
        }
    }
    if (widthClass() == WidthClass.Expanded) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
            list(Modifier.weight(1f))
            if (canUpload) uploader(Modifier.width(340.dp))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            list(Modifier)
            if (canUpload) uploader(Modifier)
        }
    }

    toDelete?.let { d ->
        ConfirmDialog(
            title = "Delete document?",
            onDismiss = { toDelete = null },
            message = "\"${d.strOrNull("title")?.ifBlank { null } ?: d.str("file_name")}\" will be permanently removed.",
            confirmText = "Delete",
            destructive = true,
            successMessage = "Document deleted",
            onConfirm = { BorrowersService.deleteDocument(d) },
        )
    }
}

// ---------------------------------------------------------------------------
// Agreements
// ---------------------------------------------------------------------------
@Composable
internal fun AgreementsTab(borrowerId: String) {
    val router = LocalRouter.current
    val pdf = rememberPdfViewer()
    val q = rememberLoad(borrowerId) { BorrowersService.agreements(borrowerId) }
    if (q.error != null && q.data == null) { ErrorState(q.error, q.reload); return }
    val columns = listOf(
        Col<JRow>("Agreement #", weight = 1.3f, mobile = CellRole.Title, minWidth = 150.dp) { a -> CellText(a.str("agreement_number"), bold = true) },
        Col<JRow>("Loan", mobile = CellRole.Subtitle, minWidth = 110.dp) { a ->
            LinkText(a.objOrFirst("loans").str("loan_number"), { router.go("/loans/${a.str("loan_id")}") }, color = LF.Blue700,
                fontSize = if (isCompact()) 12.sp else 14.sp)
        },
        Col<JRow>("Version", mobile = CellRole.Meta, minWidth = 100.dp) { a ->
            MetaText("v${a.int("version")}${if (a.str("kind") == "amendment") " (amendment)" else ""}")
        },
        Col<JRow>("Generated", mobile = CellRole.Meta, minWidth = 100.dp) { a -> MetaText(fmtDate(a.strOrNull("generated_at"))) },
        Col<JRow>("Fully signed", weight = 1.3f, mobile = CellRole.Meta, minWidth = 140.dp) { a ->
            MetaText(a.strOrNull("fully_signed_at")?.ifBlank { null }?.let { fmtDateTime(it) } ?: "—")
        },
        Col<JRow>("Status", mobile = CellRole.Meta, minWidth = 120.dp) { a -> AgreementStatusBadge(a.strOrNull("status")) },
        Col<JRow>("", align = Align.End, mobile = CellRole.Value, minWidth = 120.dp) { a ->
            val signed = a.strOrNull("signed_pdf_path")?.ifBlank { null }
            val path = signed ?: a.strOrNull("current_pdf_path")?.ifBlank { null } ?: a.strOrNull("unsigned_pdf_path")?.ifBlank { null }
            if (path != null) {
                Btn(if (signed != null) "Signed PDF" else "PDF", {
                    pdf.openUrl("Agreement ${a.str("agreement_number")}", "${a.str("agreement_number")}.pdf") { AgreementsService.pdfUrl(path) }
                }, variant = BtnVariant.Outline, icon = Icons.Default.Visibility, small = true)
            }
        },
    )
    Panel(title = "Loan agreements", description = "Signed agreements are stored privately with a SHA-256 fingerprint.") {
        DataTable(
            columns, q.data, loading = q.loading, onRowClick = { a -> router.go("/loans/${a.str("loan_id")}") },
            empty = { EmptyState("No agreements yet", icon = Icons.Default.HistoryEdu) },
        )
    }
}

// ---------------------------------------------------------------------------
// Guarantors
// ---------------------------------------------------------------------------
private val GUARANTOR_FIELDS = listOf("full_name", "relationship", "mobile_number", "email", "address", "occupation", "monthly_income", "id_type", "id_number", "notes")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuarantorsTab(borrowerId: String) {
    val auth by AppState.state.collectAsState()
    val q = rememberLoad(borrowerId) { BorrowersService.guarantors(borrowerId) }
    var editing by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    val form = remember { mutableStateMapOf<String, String>() }
    var toDelete by remember { mutableStateOf<JRow?>(null) }
    val runner = rememberRunner()
    val canEdit = auth.can("borrowers.edit")

    fun startEdit(g: JRow?) {
        form.clear()
        GUARANTOR_FIELDS.forEach { k -> form[k] = "" }
        editId = g?.str("id")
        if (g != null) {
            GUARANTOR_FIELDS.forEach { k -> form[k] = g.str(k) }
            form["mobile_number"] = g.strOrNull("mobile_number")?.ifBlank { null }?.let { phMobile(it) } ?: ""
            form["monthly_income"] = g.numOrNull("monthly_income")?.let { trimNumber(it, 2).replace(",", "") } ?: ""
        }
        editing = true
    }

    fun save() {
        val name = form["full_name"].orEmpty().trim()
        if (name.isEmpty()) { Toasts.error("Guarantor name is required"); return }
        val mobile = form["mobile_number"].orEmpty().ifEmpty { null }?.let { normalizePhMobile(it) }
        if (mobile != null && !Regex("^\\+639\\d{9}$").matches(mobile)) { Toasts.error("Enter a valid PH mobile number"); return }
        runner.run(success = "Guarantor saved") {
            val incText = form["monthly_income"].orEmpty()
            val income = if (incText.isEmpty()) null else incText.replace(",", "").toDoubleOrNull()
            if (incText.isNotEmpty() && (income == null || income < 0)) throw IllegalStateException("Enter a valid monthly income")
            fun opt(k: String) = form[k].orEmpty().ifEmpty { null }
            BorrowersService.saveGuarantor(borrowerId, mapOf(
                "id" to editId, "full_name" to name, "mobile_number" to mobile, "monthly_income" to income,
                "relationship" to opt("relationship"), "email" to opt("email"), "address" to opt("address"),
                "occupation" to opt("occupation"), "id_type" to opt("id_type"), "id_number" to opt("id_number"), "notes" to opt("notes"),
            ))
            editing = false
        }
    }

    Panel(
        title = "Guarantors / co-makers",
        actions = if (canEdit) ({ Btn("Add guarantor", { startEdit(null) }, icon = Icons.Default.PersonAdd, small = true) }) else null,
    ) {
        val list = q.data
        when {
            list == null -> Spinner()
            list.isEmpty() -> EmptyState("No guarantors", description = "Add a co-maker who agrees to answer for the loan if the borrower defaults.", icon = Icons.Default.PersonAdd)
            else -> {
                val perRow = if (isCompact()) 1 else 2
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    list.chunked(perRow).forEach { row ->
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { g ->
                                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).border(1.dp, LF.Border, RoundedCornerShape(8.dp)).padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Column(Modifier.weight(1f)) {
                                            Text(g.str("full_name"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Foreground)
                                            Text(g.strOrNull("relationship")?.ifBlank { null } ?: "Guarantor", fontSize = 12.sp, color = LF.MutedForeground)
                                        }
                                        if (canEdit) {
                                            IconButton(onClick = { startEdit(g) }, Modifier.size(36.dp)) { Icon(Icons.Default.Edit, "Edit guarantor", Modifier.size(18.dp), tint = LF.Foreground) }
                                            IconButton(onClick = { toDelete = g }, Modifier.size(36.dp)) { Icon(Icons.Default.Delete, "Delete guarantor", Modifier.size(18.dp), tint = LF.Red600) }
                                        }
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    KeyValue(listOf(
                                        KV("Mobile", g.strOrNull("mobile_number")?.ifBlank { null }?.let { phMobile(it) } ?: "—"),
                                        KV("Occupation", g.str("occupation").ifBlank { "—" }),
                                        KV("Monthly income", g.numOrNull("monthly_income")?.let { peso(it) } ?: "—"),
                                        KV("ID", g.strOrNull("id_type")?.ifBlank { null }?.let { t -> t + (g.strOrNull("id_number")?.ifBlank { null }?.let { " · $it" } ?: "") } ?: "—"),
                                        KV("Address", g.str("address").ifBlank { "—" }),
                                    ))
                                }
                            }
                            if (row.size < perRow) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    if (editing) {
        fun f(k: String) = form[k] ?: ""
        val compact = isCompact()
        AppDialog(
            title = if (editId != null) "Edit guarantor" else "Add guarantor",
            onDismiss = { if (!runner.busy) editing = false },
            maxWidth = 512.dp,
            footer = {
                Btn("Cancel", { editing = false }, variant = BtnVariant.Outline, enabled = !runner.busy)
                Btn("Save", { save() }, loading = runner.busy)
            },
        ) {
            FormGrid(if (compact) 1 else 2, listOf(
                GridCell(2) { Field("Full name", required = true) { TextInput(f("full_name"), { form["full_name"] = it }) } },
                GridCell { Field("Relationship") { SelectField(f("relationship"), listOf(Opt("", "Select")) + RELATIONSHIPS.map { Opt(it, it) }, { form["relationship"] = it }, placeholder = "Select") } },
                GridCell { Field("Mobile number") { TextInput(f("mobile_number"), { form["mobile_number"] = it }, keyboardType = KeyboardType.Phone) } },
                GridCell { Field("Email") { TextInput(f("email"), { form["email"] = it }, keyboardType = KeyboardType.Email) } },
                GridCell { Field("Occupation") { TextInput(f("occupation"), { form["occupation"] = it }) } },
                GridCell { Field("Monthly income (₱)") { MoneyInput(f("monthly_income"), { form["monthly_income"] = it }, placeholder = null) } },
                GridCell { Field("Address") { TextInput(f("address"), { form["address"] = it }) } },
                GridCell { Field("ID type") { TextInput(f("id_type"), { form["id_type"] = it }) } },
                GridCell { Field("ID number") { TextInput(f("id_number"), { form["id_number"] = it }) } },
                GridCell(2) { Field("Notes") { TextInput(f("notes"), { form["notes"] = it }, singleLine = false, minLines = 2) } },
            ), gap = 12.dp)
        }
    }

    toDelete?.let { g ->
        ConfirmDialog(
            title = "Remove guarantor?",
            onDismiss = { toDelete = null },
            message = "${g.str("full_name")} will be removed from this borrower.",
            confirmText = "Remove",
            destructive = true,
            successMessage = "Guarantor removed",
            onConfirm = { BorrowersService.deleteGuarantor(g.str("id")) },
        )
    }
}

// ---------------------------------------------------------------------------
// Notes & risk
// ---------------------------------------------------------------------------
private class NoteType(val value: String, val label: String, val icon: ImageVector)

private val NOTE_TYPES = listOf(
    NoteType("note", "Note", Icons.Default.Description),
    NoteType("call", "Phone call", Icons.Default.Phone),
    NoteType("visit", "Field visit", Icons.Default.Place),
    NoteType("promise_to_pay", "Promise to pay", Icons.Default.Paid),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotesTab(borrowerId: String, assessment: String?, internal: String?, risk: String) {
    val auth by AppState.state.collectAsState()
    val router = LocalRouter.current
    val q = rememberLoad(borrowerId) { BorrowersService.notes(borrowerId) }
    var type by remember { mutableStateOf("note") }
    var body by remember { mutableStateOf("") }
    var promiseDate by remember { mutableStateOf("") }
    var promiseAmount by remember { mutableStateOf("") }
    val add = rememberRunner()
    val compact = isCompact()

    val activity: @Composable (Modifier) -> Unit = { m ->
        Panel(m, title = "Activity & notes") {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(8.dp)).background(LF.Slate50.copy(alpha = 0.6f))
                    .border(1.dp, LF.Border, RoundedCornerShape(8.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NOTE_TYPES.forEach { t ->
                        val sel = type == t.value
                        Row(
                            Modifier.clip(CircleShape).background(if (sel) LF.Blue600 else Color.White)
                                .border(1.dp, if (sel) LF.Blue600 else LF.Border, CircleShape)
                                .clickable { type = t.value }.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(t.icon, null, Modifier.size(14.dp), tint = if (sel) Color.White else LF.Foreground)
                            Spacer(Modifier.width(6.dp))
                            Text(t.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (sel) Color.White else LF.Foreground)
                        }
                    }
                }
                TextInput(body, { body = it }, singleLine = false, minLines = 3, placeholder = "What happened? e.g. Called borrower, will pay on Friday.")
                if (type == "promise_to_pay") {
                    FormGrid(if (compact) 1 else 2, listOf(
                        GridCell { Field("Promised date") { DateField(promiseDate, { promiseDate = it }, clearable = true) } },
                        GridCell { Field("Promised amount (₱)") { MoneyInput(promiseAmount, { promiseAmount = it }, placeholder = null) } },
                    ), gap = 8.dp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Btn("Add note", {
                        add.run(success = "Note added") {
                            if (body.isBlank()) throw IllegalStateException("Write a note first")
                            if (type == "promise_to_pay" && promiseDate.isBlank()) throw IllegalStateException("Enter the promised payment date")
                            LoansService.addNote(
                                loanId = null, borrowerId = borrowerId, body = body.trim(), noteType = type,
                                promiseDate = if (type == "promise_to_pay") promiseDate else null,
                                promiseAmount = if (type == "promise_to_pay" && promiseAmount.isNotEmpty()) promiseAmount.replace(",", "").toDoubleOrNull() else null,
                            )
                            body = ""; promiseDate = ""; promiseAmount = ""
                        }
                    }, loading = add.busy, small = true)
                }
            }
            val notes = q.data
            when {
                notes == null -> Spinner()
                notes.isEmpty() -> EmptyState("No activity yet")
                else -> Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    notes.forEachIndexed { i, n ->
                        val nt = n.str("note_type")
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            // timeline rail with a dot
                            Box(Modifier.width(20.dp).fillMaxHeight()) {
                                Box(Modifier.width(1.dp).fillMaxHeight().background(LF.Border).align(Alignment.TopCenter))
                                Box(
                                    Modifier.padding(top = 4.dp).size(12.dp).clip(CircleShape).background(Color.White).padding(0.dp)
                                        .align(Alignment.TopCenter),
                                ) {
                                    Box(Modifier.fillMaxWidth().fillMaxHeight().clip(CircleShape).background(
                                        when (nt) { "system" -> LF.Slate200; "promise_to_pay" -> LF.Amber600; else -> LF.Blue600 }))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f).padding(bottom = if (i < notes.size - 1) 16.dp else 0.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (nt == "system") "System" else n.objOrFirst("author").strOrNull("full_name") ?: "Staff",
                                        fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LF.Foreground)
                                    Text(titleCase(nt), fontSize = 12.sp, color = LF.MutedForeground)
                                    Text(timeAgo(n.strOrNull("created_at")), fontSize = 12.sp, color = LF.MutedForeground)
                                }
                                Text(n.str("body"), fontSize = 14.sp, color = LF.Foreground, modifier = Modifier.padding(top = 4.dp))
                                if (nt == "promise_to_pay") {
                                    val amt = n.numOrNull("promise_amount")?.takeIf { it != 0.0 }
                                    Text("Promised ${amt?.let { peso(it) } ?: "payment"} on ${fmtDate(n.strOrNull("promise_date"))}",
                                        fontSize = 12.sp, color = LF.Amber800, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val side: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Panel(
                title = "Risk assessment",
                actions = if (auth.can("borrowers.view_all") && auth.can("borrowers.edit")) ({
                    Btn("Edit", { router.go("/borrowers/$borrowerId/edit") }, variant = BtnVariant.Outline, icon = Icons.Default.Edit, small = true)
                }) else null,
            ) {
                Box(Modifier.padding(bottom = 12.dp)) { RiskBadge(risk) }
                Text(assessment?.ifBlank { null } ?: "No assessment notes recorded.", fontSize = 14.sp, color = LF.MutedForeground)
            }
            Panel(title = "Internal notes") {
                Text(internal?.ifBlank { null } ?: "No internal notes.", fontSize = 14.sp, color = LF.MutedForeground)
            }
        }
    }
    if (widthClass() == WidthClass.Expanded) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
            activity(Modifier.weight(1f))
            side(Modifier.width(360.dp))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            activity(Modifier)
            side(Modifier)
        }
    }
}
