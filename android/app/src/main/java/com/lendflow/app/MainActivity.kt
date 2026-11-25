package com.lendflow.app

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.app.AdminShell
import com.lendflow.app.app.AppState
import com.lendflow.app.app.BrandMark
import com.lendflow.app.app.Router
import com.lendflow.app.app.ToastHost
import com.lendflow.app.app.WorkspaceShell
import com.lendflow.app.feature.auth.AccountSuspendedScreen
import com.lendflow.app.feature.auth.ForcePasswordChangeScreen
import com.lendflow.app.feature.auth.LoginScreen
import com.lendflow.app.feature.auth.NoAccountScreen
import com.lendflow.app.feature.signing.PublicSigningScreen
import com.lendflow.app.ui.components.Spinner
import com.lendflow.app.ui.theme.LF
import com.lendflow.app.ui.theme.LendFlowTheme

class MainActivity : ComponentActivity() {
    /** A /sign/<token> link opened from SMS, chat or the browser. */
    private var signingToken by mutableStateOf<String?>(null)
    /** Any other https://<site>/... link (e.g. a notification) to open once signed in. */
    private var pendingPath by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // links arrive through LinkActivity (in memory), so a relaunch never replays an old link
        PendingLink.take()?.let { handleLink(it) }
        setContent {
            LendFlowTheme {
                Box(Modifier.fillMaxSize().background(LF.Background)) {
                    Root(signingToken, { signingToken = null }, pendingPath, { pendingPath = null })
                    Box(Modifier.align(Alignment.BottomCenter)) { ToastHost() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        PendingLink.take()?.let { handleLink(it) }
    }

    private fun handleLink(uri: android.net.Uri) {
        val segs = uri.pathSegments
        if (segs.size >= 2 && segs[0] == "sign") signingToken = segs[1]
        else if (!uri.path.isNullOrBlank()) pendingPath = uri.path + (uri.query?.let { "?$it" } ?: "")
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        AppState.touch()
        return super.dispatchTouchEvent(ev)
    }
}

@Composable
private fun Root(signingToken: String?, onSigningClosed: () -> Unit, pendingPath: String?, onPathConsumed: () -> Unit) {
    val auth by AppState.state.collectAsState()

    // the public signing page works without signing in (the borrower's own phone)
    if (signingToken != null) {
        PublicSigningScreen(signingToken, onClose = onSigningClosed)
        return
    }
    val session = auth.session
    when {
        auth.loading -> Splash()
        session == null -> LoginScreen()
        session.mustChangePassword -> ForcePasswordChangeScreen()
        auth.suspendedOrganization != null -> AccountSuspendedScreen()
        auth.organizationId == null && auth.isPlatformAdmin -> {
            val router = remember(session.userId) { Router("/admin") }
            OpenPending(router, pendingPath, onPathConsumed, admin = true)
            AdminShell(router, auth)
        }
        auth.organizationId == null -> NoAccountScreen()
        else -> {
            val router = remember(session.userId) { Router("/") }
            OpenPending(router, pendingPath, onPathConsumed, admin = false)
            WorkspaceShell(router, auth)
        }
    }
}

@Composable
private fun OpenPending(router: Router, path: String?, consumed: () -> Unit, admin: Boolean) {
    LaunchedEffect(path) {
        if (path != null) {
            val p = Router.normalize(path)
            if (admin == p.startsWith("/admin") && p != "/" && p != "/login") router.go(p)
            consumed()
        }
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize().background(LF.Navy), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(64.dp)
            Spacer(Modifier.height(16.dp))
            Text("Loading your workspace…", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            Spinner(color = Color.White)
        }
    }
}
