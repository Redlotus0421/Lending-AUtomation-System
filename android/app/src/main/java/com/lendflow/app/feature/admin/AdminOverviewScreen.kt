package com.lendflow.app.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.PlatformService
import com.lendflow.app.data.Row
import com.lendflow.app.data.num
import com.lendflow.app.data.obj
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.CellRole
import com.lendflow.app.ui.components.CellText
import com.lendflow.app.ui.components.Col
import com.lendflow.app.ui.components.DataTable
import com.lendflow.app.ui.components.EmptyState
import com.lendflow.app.ui.components.ErrorState
import com.lendflow.app.ui.components.PageHeader
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.StatCard
import com.lendflow.app.ui.components.StatTone
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.num
import com.lendflow.app.util.peso

// Port of src/features/admin/AdminOverviewPage.tsx

/** Lays [cards] out [perRow] per row with equal widths (web grid-cols-n). */
@Composable
internal fun CardGrid(perRow: Int, cards: List<@Composable (Modifier) -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(perRow).forEach { chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                chunk.forEach { c -> c(Modifier.weight(1f)) }
                repeat(perRow - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun AdminOverviewScreen() {
    val router = LocalRouter.current
    val auth by AppState.state.collectAsState()
    val overview = rememberLoad { PlatformService.overview() }
    val orgs = rememberLoad { PlatformService.listOrganizations() }
    val o = overview.data
    val create = { router.go("/admin/accounts?new=1") }
    val firstName = auth.access.strOrNull("full_name")?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() }

    val columns = listOf(
        Col<Row>("Business", weight = 1.6f, mobile = CellRole.Title, minWidth = 180.dp) { r -> Text(r.str("name"), fontSize = 14.sp, fontWeight = FontWeight.Medium) },
        Col<Row>("Owner", weight = 1.6f, mobile = CellRole.Subtitle, minWidth = 180.dp) { r -> CellText(r.obj("owner")?.strOrNull("email"), muted = true) },
        Col<Row>("Status", mobile = CellRole.Meta, minWidth = 110.dp) { r -> AccountStatusPill(r.strOrNull("status")) },
        Col<Row>("Created", mobile = CellRole.Meta, minWidth = 110.dp) { r -> CellText(fmtDate(r.strOrNull("created_at")), muted = true) },
    )

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageHeader(
            "Welcome" + (firstName?.let { ", $it" } ?: ""),
            "Create and manage the lending businesses that use LendFlow.",
            actions = { Btn("Create account", create, icon = Icons.Default.Add) },
        )

        if (overview.error != null && o == null) {
            ErrorState(overview.error, overview.reload)
        } else {
            val w = LocalConfiguration.current.screenWidthDp
            val perRow = when {
                w >= 1280 -> 6
                widthClass() == WidthClass.Expanded -> 3
                else -> 2
            }
            val dots = "…"
            CardGrid(perRow, listOf(
                { m -> StatCard("Lender accounts", if (o != null) num(o.num("organizations")) else dots, m,
                    hint = o?.let { "${num(it.num("new_organizations_30d"))} new in 30 days" }, icon = Icons.Default.Business, tone = StatTone.navy,
                    onClick = { router.go("/admin/accounts") }) },
                { m -> StatCard("Suspended", if (o != null) num(o.num("suspended_organizations")) else dots, m,
                    icon = Icons.Default.Block, tone = if ((o?.num("suspended_organizations") ?: 0.0) > 0) StatTone.red else StatTone.green) },
                { m -> StatCard("Users", if (o != null) num(o.num("users")) else dots, m, hint = "Active logins in all accounts", icon = Icons.Default.Groups, tone = StatTone.blue) },
                { m -> StatCard("Borrowers", if (o != null) num(o.num("borrowers")) else dots, m, icon = Icons.Default.Contacts, tone = StatTone.teal) },
                { m -> StatCard("Active loans", if (o != null) num(o.num("active_loans")) else dots, m,
                    hint = o?.let { "${peso(it.num("outstanding"), compact = true)} outstanding" }, icon = Icons.Default.Payments, tone = StatTone.amber) },
                { m -> StatCard("Collected (30 days)", if (o != null) peso(o.num("collected_30d"), compact = true) else dots, m, icon = Icons.Default.AccountBalanceWallet, tone = StatTone.green) },
            ))
        }

        Panel(
            title = "Recent accounts",
            contentPadding = if (isCompact()) 12.dp else 16.dp,
            actions = { Btn("View all", { router.go("/admin/accounts") }, variant = BtnVariant.Ghost, small = true) },
        ) {
            if (orgs.error != null && orgs.data == null) ErrorState(orgs.error, orgs.reload)
            else DataTable(
                columns, orgs.data?.take(8), loading = orgs.loading,
                onRowClick = { r -> router.go("/admin/accounts/${r.str("id")}") },
                empty = {
                    EmptyState(
                        "No lender accounts yet",
                        description = "Create an account for each lending business. You set the owner's email and a temporary password; they add their own staff.",
                        icon = Icons.Default.Business,
                        action = { Btn("Create the first account", create, icon = Icons.Default.Add) },
                    )
                },
            )
        }

        Text(
            "For privacy, the console shows only counts and totals per account. Borrower, loan and payment records stay visible only to each account's own staff.",
            fontSize = 12.sp, color = LF.MutedForeground, modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}
