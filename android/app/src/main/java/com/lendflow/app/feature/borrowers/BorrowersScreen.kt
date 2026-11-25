package com.lendflow.app.feature.borrowers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AppState
import com.lendflow.app.app.LocalRouter
import com.lendflow.app.data.BorrowersService
import com.lendflow.app.data.Row as JRow
import com.lendflow.app.data.Supa
import com.lendflow.app.data.num
import com.lendflow.app.data.objs
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
import com.lendflow.app.ui.components.Pagination
import com.lendflow.app.ui.components.Panel
import com.lendflow.app.ui.components.SearchField
import com.lendflow.app.ui.components.SelectField
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.rememberDebounced
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.Opt
import com.lendflow.app.util.fmtDate
import com.lendflow.app.util.peso
import com.lendflow.app.util.phMobile

// Port of src/features/borrowers/BorrowersPage.tsx

private const val PAGE_SIZE = 25

private data class LoanTotals(val open: Int, val pipeline: Int, val total: Int, val outstanding: Double)

private fun loanTotals(b: JRow): LoanTotals {
    val loans = b.objs("loans")
    val open = loans.filter { it.str("status") in listOf("active", "defaulted") }
    val pipeline = loans.filter { it.str("status") !in listOf("active", "defaulted", "completed", "rejected", "cancelled") }
    return LoanTotals(open.size, pipeline.size, loans.size, open.sumOf { it.num("total_outstanding") })
}

private val STATUS_FILTERS = listOf(
    Opt("", "Active & blacklisted"), Opt("active", "Active"), Opt("blacklisted", "Blacklisted"), Opt("archived", "Archived"), Opt("all", "All statuses"),
)
private val RISK_FILTERS = listOf(
    Opt("all", "All risk ratings"), Opt("low", "Low risk"), Opt("medium", "Medium risk"), Opt("high", "High risk"), Opt("unrated", "Unrated"),
)

@Composable
fun BorrowersScreen() {
    val auth by AppState.state.collectAsState()
    val router = LocalRouter.current
    var search by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("") }
    var risk by rememberSaveable { mutableStateOf("all") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val debounced = rememberDebounced(search)

    val q = rememberLoad(debounced, status, risk, page) {
        BorrowersService.list(search = debounced, status = status, risk = risk, page = page, pageSize = PAGE_SIZE)
    }
    // keepPreviousData: show the last page while the next one loads
    var last by remember { mutableStateOf<Supa.Page?>(null) }
    LaunchedEffect(q.data) { if (q.data != null) last = q.data }
    val data = q.data ?: last

    val compact = isCompact()
    val columns = listOf(
        Col<JRow>("Borrower", weight = 2.2f, mobile = CellRole.Title, minWidth = 200.dp) { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!compact) {
                    BorrowerAvatar(b.str("full_name"), b.strOrNull("profile_photo_path"), 36.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(b.str("full_name"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(b.str("borrower_code"), fontSize = 12.sp, color = LF.MutedForeground)
                }
            }
        },
        Col<JRow>("Mobile", weight = 1.3f, mobile = CellRole.Subtitle, minWidth = 130.dp) { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (compact) {
                    Icon(Icons.Default.Phone, null, Modifier.size(14.dp), tint = LF.MutedForeground)
                    Spacer(Modifier.width(6.dp))
                }
                Text(phMobile(b.strOrNull("mobile_number")), fontSize = if (compact) 12.sp else 14.sp, color = if (compact) LF.MutedForeground else LF.Foreground)
            }
        },
        Col<JRow>("City", mobile = CellRole.Meta, minWidth = 110.dp) { b ->
            Text(b.strOrNull("city_municipality")?.ifBlank { null } ?: "—", fontSize = if (compact) 12.sp else 14.sp, color = LF.MutedForeground)
        },
        Col<JRow>("Loans", align = Align.Center, mobile = CellRole.Meta, minWidth = 120.dp) { b ->
            val t = loanTotals(b)
            Row {
                Text("${t.open} active", fontSize = if (compact) 12.sp else 14.sp, color = if (compact) LF.MutedForeground else LF.Foreground)
                if (t.pipeline > 0) Text(" · ${t.pipeline} in process", fontSize = if (compact) 12.sp else 14.sp, color = LF.MutedForeground)
            }
        },
        Col<JRow>("Outstanding", align = Align.End, mobile = CellRole.Value, minWidth = 120.dp) { b ->
            val t = loanTotals(b)
            if (t.outstanding > 0) Text(peso(t.outstanding), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LF.Foreground)
            else Text("—", fontSize = 14.sp, color = LF.MutedForeground)
        },
        Col<JRow>("Risk", mobile = CellRole.Meta, minWidth = 110.dp) { b -> RiskBadge(b.strOrNull("risk_rating")) },
        Col<JRow>("Status", mobile = CellRole.Meta, minWidth = 110.dp) { b -> BorrowerStatusBadge(b.strOrNull("status")) },
        Col<JRow>("Registered", mobile = CellRole.Hidden, minWidth = 110.dp) { b -> CellText(fmtDate(b.strOrNull("created_at")), muted = true) },
    )

    Column {
        PageHeader(
            "Borrowers",
            description = "Register borrowers, verify identities and review their loan history.",
            actions = if (auth.can("borrowers.create")) ({
                Btn("Register borrower", { router.go("/borrowers/new") }, icon = Icons.Default.PersonAdd)
            }) else null,
        )
        Panel(contentPadding = if (compact) 12.dp else 16.dp) {
            val searchBox: @Composable (Modifier) -> Unit = { m ->
                SearchField(search, { search = it; page = 0 }, m, placeholder = "Search name, code, mobile or email")
            }
            val statusBox: @Composable (Modifier) -> Unit = { m -> SelectField(status, STATUS_FILTERS, { status = it; page = 0 }, m) }
            val riskBox: @Composable (Modifier) -> Unit = { m -> SelectField(risk, RISK_FILTERS, { risk = it; page = 0 }, m) }
            if (compact) {
                Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    searchBox(Modifier)
                    statusBox(Modifier)
                    riskBox(Modifier)
                }
            } else {
                Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    searchBox(Modifier.weight(1f))
                    statusBox(Modifier.width(200.dp))
                    riskBox(Modifier.width(200.dp))
                }
            }
            if (q.error != null && data == null) {
                ErrorState(q.error, q.reload)
            } else {
                DataTable(
                    columns = columns,
                    rows = data?.rows,
                    loading = q.loading,
                    onRowClick = { b -> router.go("/borrowers/${b.str("id")}") },
                    empty = {
                        EmptyState(
                            title = if (debounced.isNotBlank() || status.isNotEmpty() || risk != "all") "No borrowers match your filters" else "No borrowers yet",
                            description = if (debounced.isNotBlank()) "Try a different name, code or mobile number." else "Register your first borrower to start creating loans.",
                            icon = Icons.Default.Groups,
                            action = if (auth.can("borrowers.create") && debounced.isBlank()) ({
                                Btn("Register borrower", { router.go("/borrowers/new") }, icon = Icons.Default.PersonAdd)
                            }) else null,
                        )
                    },
                )
                Pagination(page, PAGE_SIZE, data?.count ?: 0, { page = it })
            }
        }
    }
}
