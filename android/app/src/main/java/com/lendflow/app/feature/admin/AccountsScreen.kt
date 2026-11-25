package com.lendflow.app.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Business
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.PlatformService
import com.lendflow.app.data.Row
import com.lendflow.app.data.num
import com.lendflow.app.data.obj
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.ui.components.Align
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.CellRole
import com.lendflow.app.ui.components.CellText
import com.lendflow.app.ui.components.Col
import com.lendflow.app.ui.components.DataTable
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.SearchField
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Opt
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.num
import com.lendflow.app.util.peso
import com.lendflow.app.util.timeAgo

// Port of src/features/admin/AccountsPage.tsx

/** openNew: open the Create account dialog right away (?new=1) */
@Composable
fun AccountsScreen(openNew: Boolean) {
    val router = LocalRouter.current
    var search by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("all") }
    var creating by remember { mutableStateOf(openNew) }
    val q = rememberLoad { PlatformService.listOrganizations() }

    val rows = remember(q.data, search, status) {
        val term = search.trim().lowercase()
        (q.data ?: emptyList()).filter { o ->
            (status == "all" || o.str("status") == status) && (term.isEmpty() || listOf(
                o.strOrNull("name"), o.strOrNull("legal_name"), o.strOrNull("city"), o.strOrNull("province"),
                o.obj("owner")?.strOrNull("full_name"), o.obj("owner")?.strOrNull("email"), o.strOrNull("contact_phone"),
            ).any { it?.lowercase()?.contains(term) == true })
        }
    }

    val compact = isCompact()
    val columns = listOf(
        Col<Row>("Business", weight = 1.8f, mobile = CellRole.Title, minWidth = 180.dp) { o ->
            Column {
                Text(o.str("name"), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(o.strOrNull("city")?.ifBlank { null }, o.strOrNull("province")?.ifBlank { null }).joinToString(", ").ifBlank { o.strOrNull("legal_name") ?: "—" },
                    fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        Col<Row>("Owner", weight = 1.6f, mobile = CellRole.Subtitle, minWidth = 170.dp) { o ->
            val owner = o.obj("owner")
            if (owner != null) {
                Column {
                    Text(owner.str("full_name"), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(owner.str("email"), fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else Text("No owner", fontSize = 14.sp, color = LF.MutedForeground)
        },
        Col<Row>("Status", mobile = CellRole.Meta, minWidth = 110.dp) { o -> AccountStatusPill(o.strOrNull("status")) },
        Col<Row>("Lending mode", mobile = CellRole.Meta, minWidth = 120.dp) { o -> LendingModePill(o.strOrNull("lending_mode") ?: "complete") },
        Col<Row>("Users", weight = 0.6f, align = Align.End, mobile = CellRole.Hidden, minWidth = 70.dp) { o -> CellText(num(o.obj("stats").num("users"))) },
        Col<Row>("Active loans", weight = 0.8f, align = Align.End, mobile = CellRole.Meta, minWidth = 100.dp) { o ->
            CellText(num(o.obj("stats").num("active_loans")) + if (compact) " active loans" else "")
        },
        Col<Row>("Outstanding", align = Align.End, mobile = CellRole.Value, minWidth = 110.dp) { o -> CellText(peso(o.obj("stats").num("outstanding"), compact = true)) },
        Col<Row>("Last sign-in", mobile = CellRole.Hidden, minWidth = 110.dp) { o ->
            CellText(o.obj("stats")?.strOrNull("last_activity_at")?.let { timeAgo(it) } ?: "Never", muted = true)
        },
        Col<Row>("Created", mobile = CellRole.Hidden, minWidth = 110.dp) { o -> CellText(fmtDate(o.strOrNull("created_at")), muted = true) },
    )

    fun closeCreate() {
        creating = false
        if (openNew) router.replace("/admin/accounts")
    }

    Column(Modifier.fillMaxWidth()) {
        PageHeader(
            "Lender accounts",
            "Each account is a separate lending business with its own private data, staff and settings.",
            actions = { Btn("Create account", { creating = true }, icon = Icons.Default.Add) },
        )
        Panel(contentPadding = if (compact) 12.dp else 16.dp) {
            val statusOpts = listOf(Opt("all", "All statuses"), Opt("active", "Active"), Opt("suspended", "Suspended"))
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchField(search, { search = it }, placeholder = "Search business, owner, email, city…")
                    SelectField(status, statusOpts, { status = it })
                }
            } else {
                Row {
                    Box(Modifier.widthIn(max = 380.dp).weight(1f, fill = false)) { SearchField(search, { search = it }, placeholder = "Search business, owner, email, city…") }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.width(180.dp)) { SelectField(status, statusOpts, { status = it }) }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (q.error != null && q.data == null) ErrorState(q.error, q.reload)
            else DataTable(
                columns, if (q.data == null) null else rows, loading = q.loading,
                onRowClick = { o -> router.go("/admin/accounts/${o.str("id")}") },
                empty = {
                    if (!q.data.isNullOrEmpty()) EmptyState("No matching accounts", description = "Try a different search or status.")
                    else EmptyState(
                        "No lender accounts yet",
                        description = "Create the first account for a lending business. Its owner signs in with the email and temporary password you set.",
                        icon = Icons.Default.Business,
                        action = { Btn("Create account", { creating = true }, icon = Icons.Default.Add) },
                    )
                },
            )
        }
    }

    if (creating) {
        CreateAccountDialog(onClose = { closeCreate() }, onCreated = { id, creds ->
            closeCreate()
            PendingCredentials.put(id, creds)
            router.go("/admin/accounts/$id")
        })
    }
}
