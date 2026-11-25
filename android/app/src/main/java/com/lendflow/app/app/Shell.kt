package com.lendflow.app.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.data.DataBus
import com.lendflow.app.data.NotificationsService
import com.lendflow.app.data.Realtime
import com.lendflow.app.data.has
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.feature.loans.simplified.TransactionOptions
import com.lendflow.app.ui.components.AppDialog
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.Toasts
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.rememberLoad
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.util.ROLE_LABEL
import com.lendflow.app.util.initials
import com.lendflow.app.util.timeAgo
import kotlinx.coroutines.launch

data class NavItem(val to: String, val label: String, val icon: ImageVector, val perms: List<String> = emptyList(), val end: Boolean = false)
data class NavSection(val section: String, val items: List<NavItem>)

/** Same sections, items and permissions as the web sidebar (AppShell.tsx). */
val NAV = listOf(
    NavSection("Overview", listOf(NavItem("/", "Dashboard", Icons.Default.Dashboard, listOf("dashboard.view"), end = true))),
    NavSection("Lending", listOf(
        NavItem("/borrowers", "Borrowers", Icons.Default.People, listOf("borrowers.view")),
        NavItem("/applications", "Loan Applications", Icons.Default.Assignment, listOf("loans.view")),
        NavItem("/loans", "Active Loans", Icons.Default.AccountBalance, listOf("loans.view")),
        NavItem("/agreements", "Loan Agreements", Icons.Default.Draw, listOf("agreements.view"), end = true),
        NavItem("/agreements/templates", "Terms Templates", Icons.Default.Description, listOf("templates.manage")),
    )),
    NavSection("Collections", listOf(
        NavItem("/payments", "Payments & Collections", Icons.Default.Wallet, listOf("payments.view")),
        NavItem("/due", "Due Payments", Icons.Default.CalendarMonth, listOf("payments.view")),
        NavItem("/overdue", "Overdue Loans", Icons.Default.Warning, listOf("payments.view")),
        NavItem("/records", "Records", Icons.Default.MenuBook, listOf("reports.view")),
    )),
    NavSection("Management", listOf(
        NavItem("/products", "Loan Products", Icons.Default.Inventory2, listOf("products.manage", "loans.create")),
        NavItem("/reports", "Reports & Analytics", Icons.Default.BarChart, listOf("reports.view")),
        NavItem("/users", "Users & Staff", Icons.Default.ManageAccounts, listOf("users.manage")),
        NavItem("/settings", "Settings", Icons.Default.Settings, listOf("settings.manage", "templates.manage")),
        NavItem("/audit", "Audit Logs", Icons.Default.ReceiptLong, listOf("audit.view")),
    )),
)

val ADMIN_NAV = listOf(NavSection("Super admin", listOf(
    NavItem("/admin", "Overview", Icons.Default.Dashboard, end = true),
    NavItem("/admin/accounts", "Lender accounts", Icons.Default.Apartment),
    NavItem("/admin/profile", "My login", Icons.Default.Key),
)))

/** "New loan" entry point (NewLoanChooser on the web): popup in Simplified Lending, the wizard otherwise. */
class NewLoanLauncher(val open: () -> Unit)
val LocalNewLoan = staticCompositionLocalOf { NewLoanLauncher {} }

// ---------------------------------------------------------------------------
// Brand mark (the LendFlow logo drawn natively)
// ---------------------------------------------------------------------------
@Composable
fun BrandMark(size: Dp = 36.dp, onNavy: Boolean = true) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.25f))
            .background(if (onNavy) Color.White.copy(alpha = 0.10f) else LF.Navy)
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(size * 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.68f)) {
            val s = this.size.width / 32f
            val p1 = Path().apply { moveTo(9 * s, 8 * s); lineTo(9 * s, 24 * s); lineTo(23 * s, 24 * s) }
            drawPath(p1, LF.Teal, style = Stroke(3.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val p2 = Path().apply { moveTo(14 * s, 18 * s); lineTo(18 * s, 13 * s); lineTo(21 * s, 16 * s) }
            drawPath(p2, Color.White, style = Stroke(2.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun Brand(subtitle: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        BrandMark()
        Spacer(Modifier.width(10.dp))
        Column {
            Text("LendFlow", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = LF.SidebarForeground.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SidebarContent(sections: List<NavSection>, current: Route, subtitle: String, footer: String?, onNavigate: (String) -> Unit) {
    Column(Modifier.fillMaxHeight().background(LF.Sidebar)) {
        Box(Modifier.statusBarsPadding().height(64.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            Brand(subtitle) { onNavigate(sections.first().items.first().to) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            sections.forEach { s ->
                Column {
                    Text(s.section.uppercase(), Modifier.padding(start = 12.dp, bottom = 6.dp), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = LF.SidebarForeground.copy(alpha = 0.5f), letterSpacing = 1.2.sp)
                    s.items.forEach { i ->
                        val active = current.isUnder(i.to, i.end) && !(i.to == "/agreements" && current.isUnder("/agreements/templates"))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (active) LF.SidebarAccent else Color.Transparent)
                                .clickable { onNavigate(i.to) }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(i.icon, null, Modifier.size(18.dp), tint = if (active) LF.Teal else LF.SidebarForeground)
                            Spacer(Modifier.width(12.dp))
                            Text(i.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (active) Color.White else LF.SidebarForeground)
                        }
                    }
                }
            }
        }
        if (footer != null) {
            HorizontalDivider(color = LF.SidebarBorder)
            Text(footer, Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), fontSize = 11.sp, color = LF.SidebarForeground.copy(alpha = 0.6f))
        }
    }
}

// ---------------------------------------------------------------------------
// Top bar pieces
// ---------------------------------------------------------------------------
@Composable
private fun NotificationsButton(router: Router) {
    var open by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(60_000); tick++ }
    }
    val q = rememberLoad(tick) { NotificationsService.list() }
    val scope = rememberCoroutineScope()
    val unread = q.data?.count { !it.has("read_at") } ?: 0
    Box {
        IconButton(onClick = { open = true }) {
            Box {
                Icon(Icons.Default.Notifications, "Notifications", tint = LF.Foreground)
                if (unread > 0) {
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp).heightIn(min = 16.dp).widthIn(min = 16.dp).clip(CircleShape).background(LF.Red500).padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center) {
                        Text("$unread", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.width(320.dp).background(Color.White)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Notifications", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (unread > 0) Text("Mark all read", Modifier.clickable {
                    scope.launch { try { NotificationsService.markRead(); q.reload() } catch (e: Exception) { Toasts.error(e.message ?: "") } }
                }, color = LF.Blue600, fontSize = 12.sp)
            }
            HorizontalDivider(color = LF.Border)
            val list = q.data.orEmpty()
            if (list.isEmpty()) Text("You're all caught up.", Modifier.fillMaxWidth().padding(vertical = 28.dp), fontSize = 14.sp, color = LF.MutedForeground,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            list.forEach { n ->
                Column(
                    Modifier.fillMaxWidth().background(if (!n.has("read_at")) LF.Blue50.copy(alpha = 0.5f) else Color.White)
                        .clickable {
                            open = false
                            scope.launch {
                                if (!n.has("read_at")) try { NotificationsService.markRead(listOf(n.str("id"))); q.reload() } catch (_: Exception) {}
                            }
                            n.strOrNull("link")?.let { router.go(it) }
                        }.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(n.str("title"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    n.strOrNull("body")?.let { Text(it, fontSize = 12.sp, color = LF.MutedForeground) }
                    Text(timeAgo(n.strOrNull("created_at")), fontSize = 11.sp, color = LF.MutedForeground, modifier = Modifier.padding(top = 2.dp))
                }
                HorizontalDivider(color = LF.Border)
            }
        }
    }
}

@Composable
private fun UserMenu(auth: AuthState, roleLabel: String, profilePath: String, router: Router) {
    var open by remember { mutableStateOf(false) }
    val name = auth.fullName
    Box {
        Row(Modifier.clip(CircleShape).clickable { open = true }.padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(LF.Navy), contentAlignment = Alignment.Center) {
                Text(initials(name), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            if (widthClass() == WidthClass.Expanded) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp)) {
                    Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                    Text(roleLabel, fontSize = 11.sp, color = LF.MutedForeground)
                }
            }
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.width(240.dp).background(Color.White)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(auth.session?.email ?: "", fontSize = 12.sp, color = LF.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = LF.Border)
            DropdownMenuItem({ Text(if (profilePath == "/admin/profile") "My login" else "My profile") }, { open = false; router.go(profilePath) },
                leadingIcon = { Icon(Icons.Default.VerifiedUser, null) })
            DropdownMenuItem({ Text("Sign out") }, { open = false; AppState.signOut() }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) })
        }
    }
}

/** Snackbar toasts (top-right sonner toasts on the web). */
@Composable
fun ToastHost() {
    val host = remember { SnackbarHostState() }
    var isError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Toasts.messages.collect { m ->
            host.currentSnackbarData?.dismiss()
            isError = m.isError
            launch { host.showSnackbar(m.text, withDismissAction = true, duration = if (m.isError) SnackbarDuration.Long else SnackbarDuration.Short) }
        }
    }
    SnackbarHost(host, Modifier.navigationBarsPadding().padding(bottom = 72.dp)) { data ->
        androidx.compose.material3.Snackbar(
            data, containerColor = if (isError) LF.Red700 else LF.Navy, contentColor = Color.White,
            dismissActionContentColor = Color.White, shape = RoundedCornerShape(10.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// The shell
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShellFrame(
    router: Router,
    sections: List<NavSection>,
    subtitle: String,
    footer: String?,
    auth: AuthState,
    roleLabel: String,
    profilePath: String,
    showNewLoan: Boolean,
    bottomItems: List<NavItem>,
    content: @Composable (String) -> Unit,
) {
    val wc = widthClass()
    val route = Route(router.current)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val newLoan = LocalNewLoan.current
    val stateHolder = rememberSaveableStateHolder()

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && router.canGoBack) { router.back() }

    val navigate: (String) -> Unit = { to -> scope.launch { drawer.close() }; router.root(to) }

    val body: @Composable () -> Unit = {
        Scaffold(
            containerColor = LF.Background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Row(
                    Modifier.fillMaxWidth().background(Color.White).statusBarsPadding().height(60.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (wc != WidthClass.Expanded) {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Open menu", tint = LF.Foreground) }
                        Text("LendFlow", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = LF.Navy)
                    }
                    Spacer(Modifier.weight(1f))
                    if (showNewLoan && wc != WidthClass.Compact) {
                        Btn("New loan", newLoan.open, icon = Icons.Default.AddCircle, small = true)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (profilePath != "/admin/profile") NotificationsButton(router)
                    UserMenu(auth, roleLabel, profilePath, router)
                    Spacer(Modifier.width(4.dp))
                }
            },
            bottomBar = {
                if (wc != WidthClass.Expanded && bottomItems.isNotEmpty()) BottomNav(bottomItems, route, showNewLoan, newLoan, onMenu = { scope.launch { drawer.open() } }, onGo = navigate)
            },
        ) { pad ->
            var refreshing by remember { mutableStateOf(false) }
            val version by DataBus.version.collectAsState()
            LaunchedEffect(version) { refreshing = false }
            PullToRefreshBox(refreshing, onRefresh = { refreshing = true; DataBus.bumpNow() }, modifier = Modifier.padding(pad).fillMaxSize()) {
                val path = router.current
                stateHolder.SaveableStateProvider(path) {
                    val scroll = rememberScrollState()
                    Column(Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(
                            Modifier.widthIn(max = 1400.dp).fillMaxWidth()
                                .padding(horizontal = if (wc == WidthClass.Compact) 12.dp else 24.dp, vertical = 18.dp)
                                .padding(bottom = 24.dp),
                        ) { content(path) }
                    }
                }
            }
        }
    }

    if (wc == WidthClass.Expanded) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(256.dp).fillMaxHeight()) { SidebarContent(sections, route, subtitle, footer, navigate) }
            Box(Modifier.weight(1f)) { body() }
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet(Modifier.width(288.dp), drawerContainerColor = LF.Sidebar, drawerShape = RoundedCornerShape(0.dp), windowInsets = WindowInsets(0, 0, 0, 0)) {
                    SidebarContent(sections, route, subtitle, footer, navigate)
                }
            },
        ) { body() }
    }
}

@Composable
private fun BottomNav(items: List<NavItem>, route: Route, showNewLoan: Boolean, newLoan: NewLoanLauncher, onMenu: () -> Unit, onGo: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().shadow(8.dp).background(Color.White).navigationBarsPadding().height(62.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { b ->
            if (b.to == "/loans/new") {
                if (showNewLoan) Column(Modifier.weight(1f).clickable { newLoan.open() }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.offset(y = (-10).dp).size(48.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(LF.Blue).border(4.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center) { Icon(b.icon, null, tint = Color.White) }
                    Text(b.label, Modifier.offset(y = (-8).dp), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LF.Slate600)
                }
            } else {
                val active = route.isUnder(b.to, b.end)
                Column(Modifier.weight(1f).clickable { onGo(b.to) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(b.icon, null, Modifier.size(22.dp), tint = if (active) LF.Blue600 else LF.Slate500)
                    Text(b.label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (active) LF.Blue600 else LF.Slate500)
                }
            }
        }
        Column(Modifier.weight(1f).clickable(onClick = onMenu).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Menu, null, Modifier.size(22.dp), tint = LF.Slate500)
            Text("Menu", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LF.Slate500)
        }
    }
}

/** Lender workspace: sidebar / drawer, header, bottom navigation and the routed screen. */
@Composable
fun WorkspaceShell(router: Router, auth: AuthState) {
    val sections = NAV.map { s -> s.copy(items = s.items.filter { it.perms.isEmpty() || auth.canAny(*it.perms.toTypedArray()) }) }.filter { it.items.isNotEmpty() }
    val bottom = listOfNotNull(
        NavItem("/", "Home", Icons.Default.Dashboard, end = true).takeIf { auth.can("dashboard.view") },
        NavItem("/borrowers", "Borrowers", Icons.Default.People).takeIf { auth.can("borrowers.view") },
        NavItem("/loans/new", "New loan", Icons.Default.AddCircle).takeIf { auth.can("loans.create") },
        NavItem("/due", "Collect", Icons.Default.Wallet).takeIf { auth.can("payments.view") },
    )
    var chooser by remember { mutableStateOf(false) }
    var nonce by remember { mutableIntStateOf(0) }
    val launcher = remember(auth.isSimplified) {
        NewLoanLauncher {
            if (auth.isSimplified) chooser = true else { nonce++; router.go("/loans/new?n=$nonce") }
        }
    }
    CompositionLocalProvider(LocalRouter provides router, LocalNewLoan provides launcher) {
        ShellFrame(
            router, sections, auth.organization.strOrNull("name") ?: "Lending Management", "Asia/Manila · PHP · ${if (Realtime.connected.collectAsState().value) "Live sync on" else "Auto-refresh"}",
            auth, ROLE_LABEL[auth.role] ?: "", "/profile", auth.can("loans.create"), bottom,
        ) { path -> WorkspaceRoute(path, auth, router) }
        if (chooser) {
            val canRegister = auth.can("borrowers.create") && auth.can("borrowers.view_sensitive")
            AppDialog("Create a loan", { chooser = false }, description = "Choose the type of transaction.", maxWidth = 440.dp) {
                TransactionOptions(
                    compact = true,
                    onPick = { t -> chooser = false; nonce++; router.go("/loans/new?type=$t&n=$nonce") },
                    disabled = if (canRegister) emptyMap() else mapOf("new" to "Needs permission to register borrowers and handle identity documents."),
                )
            }
        }
    }
}

/** Platform super-admin console. */
@Composable
fun AdminShell(router: Router, auth: AuthState) {
    CompositionLocalProvider(LocalRouter provides router) {
        ShellFrame(
            router, ADMIN_NAV, "Platform administration", "LendFlow platform console",
            auth, "Super admin", "/admin/profile", false, emptyList(),
        ) { path -> AdminRoute(path, router) }
    }
}

@Suppress("unused") private val keepIcons = listOf(Icons.Default.AdminPanelSettings, Icons.Default.Shield)
@Suppress("unused") private fun keepOffset() = Offset.Zero
