package com.lendflow.app.feature.borrowers

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.app.Route
import com.lendflow.app.data.BorrowersService
import com.lendflow.app.data.Row as JRow
import com.lendflow.app.data.num
import com.lendflow.app.data.numOrNull
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.ConfirmDialog
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.KV
import com.lendflow.app.ui.components.KeyValue
import com.lendflow.app.ui.components.PageLoader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.Pill
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.components.StatCard
import com.lendflow.app.ui.components.StatTone
import com.lendflow.app.ui.components.Tabs
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.card
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.openUrl
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Opt
import com.lendflow.app.util.Tone
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.peso
import com.lendflow.app.util.phMobile
import com.lendflow.app.util.titleCase
import androidx.compose.material.icons.automirrored.filled.ArrowBack

// Port of src/features/borrowers/BorrowerProfilePage.tsx

private class StatusCopy(val title: String, val label: String, val desc: String)

private val STATUS_COPY = mapOf(
    "archived" to StatusCopy("Archive borrower?", "Archive", "Archived borrowers are hidden from lists and cannot receive new loans. Borrowers with open loans cannot be archived."),
    "blacklisted" to StatusCopy("Blacklist borrower?", "Blacklist", "Blacklisted borrowers cannot receive new loans. Their existing loans and history remain unchanged."),
    "active" to StatusCopy("Reactivate borrower?", "Reactivate", "The borrower will be able to apply for new loans again."),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BorrowerProfileScreen(id: String) {
    val auth by AppState.state.collectAsState()
    val router = LocalRouter.current
    val context = LocalContext.current
    val initialTab = remember { Route(router.current).param("tab") }
    var tab by rememberSaveable(id) { mutableStateOf(initialTab ?: "overview") }
    var statusAction by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val viewer = rememberBorrowerFileViewer()

    val borrower = rememberLoad(id) { BorrowersService.get(id) }
    val identity = rememberLoad(id, enabled = auth.can("borrowers.view_sensitive")) { BorrowersService.identity(id) }
    val loans = rememberLoad(id, enabled = auth.can("loans.view")) { BorrowersService.loans(id) }

    val b = borrower.data
    if (b == null) {
        if (borrower.error != null) ErrorState(borrower.error, borrower.reload) else PageLoader()
        return
    }
    val bid = b.str("id")
    val status = b.str("status")

    val loanRows = loans.data ?: emptyList()
    val booked = loanRows.filter { it.str("status") in listOf("active", "completed", "defaulted") }
    val totalBorrowed = booked.sumOf { it.num("principal") }
    val outstanding = loanRows.filter { it.str("status") in listOf("active", "defaulted") }.sumOf { it.num("total_outstanding") }
    val overdue = loanRows.sumOf { it.num("overdue_amount") }
    val overdueLoans = loanRows.count { it.num("overdue_amount") > 0 }
    val compact = isCompact()
    val wide = widthClass() == WidthClass.Expanded

    Column {
        Row(
            Modifier.padding(bottom = 12.dp).clip(RoundedCornerShape(6.dp)).clickable { router.backTo("/borrowers") }.padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(16.dp), tint = LF.MutedForeground)
            Spacer(Modifier.width(4.dp))
            Text("Borrowers", fontSize = 14.sp, color = LF.MutedForeground)
        }

        // ---------------- header ----------------
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp).card().padding(if (compact) 16.dp else 20.dp)) {
            val info: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(b.str("full_name"), fontSize = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.SemiBold, color = LF.Foreground,
                            modifier = Modifier.align(Alignment.CenterVertically))
                        Box(Modifier.align(Alignment.CenterVertically)) { BorrowerStatusBadge(status) }
                        Box(Modifier.align(Alignment.CenterVertically)) { RiskBadge(b.strOrNull("risk_rating")) }
                    }
                    Text("${b.str("borrower_code")} · Registered ${fmtDate(b.strOrNull("created_at"))}", fontSize = 14.sp, color = LF.MutedForeground,
                        modifier = Modifier.padding(top = 2.dp))
                    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ContactItem(Icons.Default.Phone, phMobile(b.strOrNull("mobile_number")), LF.Blue700) { openUrl(context, "tel:${b.str("mobile_number")}") }
                        b.strOrNull("email")?.ifBlank { null }?.let { e -> ContactItem(Icons.Default.Email, e, LF.Blue700) { openUrl(context, "mailto:$e") } }
                        b.strOrNull("city_municipality")?.ifBlank { null }?.let {
                            ContactItem(Icons.Default.Place, listOfNotNull(it, b.strOrNull("province")?.ifBlank { null }).joinToString(", "), LF.MutedForeground)
                        }
                        b.strOrNull("occupation")?.ifBlank { null }?.let { ContactItem(Icons.Default.Work, it, LF.MutedForeground) }
                    }
                }
            }
            val actions: @Composable () -> Unit = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (auth.can("loans.create") && status == "active") {
                        Btn(if (auth.isSimplified) "Reloan / Renew" else "New loan", { router.go("/loans/new?borrower=$bid") }, icon = Icons.Default.AddCircle)
                    }
                    if (auth.can("borrowers.edit")) Btn("Edit", { router.go("/borrowers/$bid/edit") }, variant = BtnVariant.Outline, icon = Icons.Default.Edit)
                    if (auth.can("borrowers.archive")) {
                        Box {
                            OutlinedIconButton(onClick = { menu = true }, modifier = Modifier.size(44.dp), shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, LF.Input)) {
                                Icon(Icons.Default.MoreHoriz, "More actions", tint = LF.Foreground)
                            }
                            DropdownMenu(menu, { menu = false }, modifier = Modifier.background(Color.White)) {
                                if (status != "active") DropdownMenuItem(text = { Text("Reactivate") }, leadingIcon = { Icon(Icons.Default.Restore, null) },
                                    onClick = { menu = false; statusAction = "active" })
                                if (status != "blacklisted") DropdownMenuItem(text = { Text("Blacklist") }, leadingIcon = { Icon(Icons.Default.Block, null) },
                                    onClick = { menu = false; statusAction = "blacklisted" })
                                if (status != "archived") {
                                    HorizontalDivider(color = LF.Border)
                                    DropdownMenuItem(text = { Text("Archive", color = LF.Red600) }, leadingIcon = { Icon(Icons.Default.Archive, null, tint = LF.Red600) },
                                        onClick = { menu = false; statusAction = "archived" })
                                }
                            }
                        }
                    }
                }
            }
            if (compact) {
                BorrowerAvatar(b.str("full_name"), b.strOrNull("profile_photo_path"), 72.dp)
                Spacer(Modifier.height(16.dp))
                info(Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                actions()
            } else {
                Row(verticalAlignment = Alignment.Top) {
                    BorrowerAvatar(b.str("full_name"), b.strOrNull("profile_photo_path"), 72.dp)
                    Spacer(Modifier.width(16.dp))
                    info(Modifier.weight(1f))
                    Spacer(Modifier.width(16.dp))
                    Box(Modifier.widthIn(max = 360.dp)) { actions() }
                }
            }
            if (status == "blacklisted") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(8.dp)).background(LF.Red50).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.GppMaybe, null, Modifier.size(16.dp), tint = LF.Red800)
                    Spacer(Modifier.width(8.dp))
                    Text("This borrower is blacklisted and cannot receive new loans.", fontSize = 14.sp, color = LF.Red800)
                }
            }
        }

        // ---------------- stats ----------------
        if (auth.can("loans.view")) {
            val cards: List<@Composable (Modifier) -> Unit> = listOf(
                { m -> StatCard("Total borrowed", peso(totalBorrowed), m, hint = "${booked.size} released loan${if (booked.size == 1) "" else "s"}", icon = Icons.Default.Paid, tone = StatTone.navy) },
                { m -> StatCard("Outstanding", peso(outstanding), m, hint = "Principal + interest + charges + penalties", icon = Icons.Default.AccountBalanceWallet, tone = StatTone.blue) },
                { m -> StatCard("Loans", loanRows.size.toString(), m, hint = "${loanRows.count { it.str("status") == "active" }} active", icon = Icons.Default.HistoryEdu, tone = StatTone.teal) },
                { m ->
                    StatCard("Overdue", peso(overdue), m,
                        hint = if (overdueLoans > 0) "$overdueLoans loan${if (overdueLoans == 1) "" else "s"} past due" else "No overdue amounts",
                        icon = Icons.Default.GppMaybe, tone = if (overdue > 0) StatTone.red else StatTone.green)
                },
            )
            Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cards.chunked(if (wide) 4 else 2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { it(Modifier.weight(1f)) } }
                }
            }
        }

        // ---------------- tabs ----------------
        val tabs = buildList {
            add(Opt("overview", "Overview"))
            if (auth.can("loans.view")) add(Opt("loans", "Loans"))
            if (auth.can("payments.view")) add(Opt("payments", "Payments"))
            add(Opt("documents", "Documents"))
            if (auth.can("agreements.view")) add(Opt("agreements", "Agreements"))
            add(Opt("guarantors", "Guarantors"))
            add(Opt("notes", "Notes & risk"))
        }
        val current = if (tabs.any { it.value == tab }) tab else "overview"
        Tabs(tabs, current, { tab = it })
        Spacer(Modifier.height(16.dp))

        when (current) {
            "overview" -> OverviewTab(b, identity.data, identity.loading, auth.can("borrowers.view_sensitive"), auth.can("borrowers.edit"), wide, viewer) {
                router.go("/borrowers/$bid/edit")
            }
            "loans" -> Panel(
                title = "Loan history",
                actions = if (auth.can("loans.create") && status == "active") ({
                    Btn("New loan", { router.go("/loans/new?borrower=$bid") }, icon = Icons.Default.AddCircle, small = true)
                }) else null,
            ) {
                if (loans.error != null && loans.data == null) ErrorState(loans.error, loans.reload)
                else BorrowerLoansTable(loans.data, loans.loading) { l -> router.go("/loans/${l.str("id")}") }
            }
            "payments" -> PaymentsTab(bid)
            "documents" -> DocumentsTab(bid, viewer)
            "agreements" -> AgreementsTab(bid)
            "guarantors" -> GuarantorsTab(bid)
            "notes" -> NotesTab(bid, b.strOrNull("assessment_notes"), b.strOrNull("internal_notes"), b.strOrNull("risk_rating") ?: "unrated")
        }
    }

    statusAction?.let { action ->
        val copy = STATUS_COPY.getValue(action)
        ConfirmDialog(
            title = copy.title,
            onDismiss = { statusAction = null },
            message = copy.desc,
            confirmText = copy.label,
            destructive = action != "active",
            successMessage = "Borrower ${if (action == "active") "reactivated" else action}",
            onConfirm = { BorrowersService.setStatus(bid, action) },
        )
    }
}

@Composable
private fun ContactItem(icon: ImageVector, text: String, color: Color, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(4.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 14.sp, color = color)
    }
}

// ---------------------------------------------------------------------------
// Overview
// ---------------------------------------------------------------------------
@Composable
private fun OverviewTab(
    b: JRow, identity: JRow?, identityLoading: Boolean, canSensitive: Boolean, canEdit: Boolean, wide: Boolean,
    viewer: BorrowerFileViewer, onEdit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        val personal: @Composable (Modifier) -> Unit = { m ->
            Panel(m, title = "Personal information") {
                KeyValue(listOf(
                    KV("Full name", b.str("full_name")),
                    KV("Date of birth", b.strOrNull("date_of_birth")?.ifBlank { null }?.let { fmtDate(it, "long") } ?: "—"),
                    KV("Gender", titleCase(b.strOrNull("gender")).ifBlank { "—" }),
                    KV("Civil status", titleCase(b.strOrNull("civil_status")).ifBlank { "—" }),
                    KV("Mobile", phMobile(b.strOrNull("mobile_number"))),
                    KV("Email", b.strOrNull("email")?.ifBlank { null } ?: "—"),
                ))
            }
        }
        val address: @Composable (Modifier) -> Unit = { m ->
            Panel(m, title = "Residential address") {
                KeyValue(listOf(
                    KV("Street", b.str("address_line").ifBlank { "—" }),
                    KV("Barangay", b.str("barangay").ifBlank { "—" }),
                    KV("City / municipality", b.str("city_municipality").ifBlank { "—" }),
                    KV("Province", b.str("province").ifBlank { "—" }),
                    KV("Region", b.str("region").ifBlank { "—" }),
                    KV("ZIP code", b.str("zip_code").ifBlank { "—" }),
                ))
            }
        }
        val employment: @Composable (Modifier) -> Unit = { m ->
            Panel(m, title = "Employment & income") {
                KeyValue(listOf(
                    KV("Occupation", b.str("occupation").ifBlank { "—" }),
                    KV("Employment type", b.str("employment_type").ifBlank { "—" }),
                    KV("Employer / business", b.str("employer_name").ifBlank { "—" }),
                    KV("Monthly income", b.numOrNull("monthly_income")?.let { peso(it) } ?: "—"),
                ))
            }
        }
        val emergency: @Composable (Modifier) -> Unit = { m ->
            Panel(m, title = "Emergency contact") {
                KeyValue(listOf(
                    KV("Name", b.str("emergency_contact_name").ifBlank { "—" }),
                    KV("Relationship", b.str("emergency_contact_relationship").ifBlank { "—" }),
                    KV("Mobile", b.strOrNull("emergency_contact_number")?.ifBlank { null }?.let { phMobile(it) } ?: "—"),
                ))
            }
        }
        PanelPair(wide, personal, address)
        PanelPair(wide, employment, emergency)

        Panel(title = "Identification", description = "Restricted to authorized staff") {
            when {
                !canSensitive -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = LF.MutedForeground)
                    Spacer(Modifier.width(8.dp))
                    Text("You do not have access to identity documents.", fontSize = 14.sp, color = LF.MutedForeground)
                }
                identityLoading && identity == null -> Spinner()
                identity != null -> {
                    val details: @Composable (Modifier) -> Unit = { m ->
                        Column(m, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LabeledValue("ID type") { ValueText(identity.str("id_type").ifBlank { "—" }) }
                            LabeledValue("ID number") { ValueText(identity.str("id_number").ifBlank { "—" }, mono = true) }
                            LabeledValue("Expiry") { ValueText(identity.strOrNull("id_expiry")?.ifBlank { null }?.let { fmtDate(it) } ?: "—") }
                            LabeledValue("Verification") {
                                val verified = identity.strOrNull("verified_at")?.ifBlank { null }
                                if (verified != null) Pill("Verified ${fmtDate(verified)}", Tone.green) else Pill("Not verified", Tone.amber)
                            }
                        }
                    }
                    val images: @Composable (Modifier) -> Unit = { m ->
                        Row(m, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IdImage(identity.strOrNull("id_front_path"), "Front", viewer, Modifier.weight(1f))
                            IdImage(identity.strOrNull("id_back_path"), "Back", viewer, Modifier.weight(1f))
                        }
                    }
                    if (wide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            details(Modifier.weight(1f))
                            images(Modifier.weight(1.4f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            details(Modifier.fillMaxWidth())
                            images(Modifier.fillMaxWidth())
                        }
                    }
                }
                else -> EmptyState(
                    "No ID on file", Modifier.padding(vertical = 0.dp),
                    description = "Add a government-issued ID before releasing a loan.",
                    action = if (canEdit) ({ Btn("Add ID", onEdit, variant = BtnVariant.Outline) }) else null,
                )
            }
        }
    }
}

@Composable
internal fun LabeledValue(label: String, value: @Composable () -> Unit) {
    Column {
        Text(label, fontSize = 12.sp, color = LF.MutedForeground)
        Box(Modifier.padding(top = 2.dp)) { value() }
    }
}

@Composable
internal fun ValueText(text: String, mono: Boolean = false) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Foreground, fontFamily = if (mono) FontFamily.Monospace else null)
}

