package com.lendflow.app.app

import androidx.compose.runtime.Immutable
import com.lendflow.app.data.DataBus
import com.lendflow.app.data.OrgService
import com.lendflow.app.data.Realtime
import com.lendflow.app.data.Row
import com.lendflow.app.data.Session
import com.lendflow.app.data.Supa
import com.lendflow.app.data.bool
import com.lendflow.app.data.int
import com.lendflow.app.data.jsonOf
import com.lendflow.app.data.obj
import com.lendflow.app.data.str
import com.lendflow.app.data.strOrNull
import com.lendflow.app.data.strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Everything the AuthProvider holds on the web: session, access (role + permissions), organization, settings. */
@Immutable
data class AuthState(
    val loading: Boolean = true,
    val session: Session? = null,
    /** my_access(): user_id, organization_id, role, full_name, permissions[], is_platform_admin, suspended_organization */
    val access: Row? = null,
    val organization: Row? = null,
    val settings: Row? = null,
) {
    private val perms: Set<String> = access.strings("permissions").toSet()
    val role: String? get() = access.strOrNull("role")
    val organizationId: String? get() = access.strOrNull("organization_id")
    val isPlatformAdmin: Boolean get() = access.bool("is_platform_admin")
    val suspendedOrganization: Row? get() = access.obj("suspended_organization")
    val fullName: String get() = access.strOrNull("full_name") ?: session?.email ?: "User"
    val userId: String? get() = session?.userId
    val lendingMode: String get() = organization.strOrNull("lending_mode") ?: "complete"
    val isSimplified: Boolean get() = lendingMode == "simplified"

    /** Owners can do everything; everyone else needs the permission (same as the web `can`). */
    fun can(p: String): Boolean = role == "owner" || p in perms
    fun canAny(vararg ps: String): Boolean = ps.any { can(it) }

    /** settings.<section>.<key> */
    fun setting(section: String): Row? = settings.obj(section)
}

object AppState {
    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state
    val current: AuthState get() = _state.value

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadedFor: String? = null
    private var timeoutJob: Job? = null
    @Volatile private var lastActivity = System.currentTimeMillis()

    fun start() {
        scope.launch {
            Supa.session.collect { s ->
                if (s == null) {
                    loadedFor = null
                    _state.value = AuthState(loading = false)
                    Realtime.stop()
                } else if (loadedFor != s.userId) {
                    _state.value = _state.value.copy(loading = true, session = s)
                    load(s)
                } else {
                    _state.value = _state.value.copy(session = s)
                }
            }
        }
        startSessionTimeout()
    }

    private suspend fun load(s: Session) {
        try {
            val a = OrgService.myAccess()
            var org: Row? = null
            var st: Row? = null
            if (a.strOrNull("organization_id") != null) {
                org = OrgService.get()
                st = OrgService.getSettings()
            }
            loadedFor = s.userId
            _state.value = AuthState(loading = false, session = Supa.session.value ?: s, access = a, organization = org, settings = st)
            Realtime.start()
        } catch (e: Exception) {
            if (Supa.session.value == null) return
            _state.value = AuthState(
                loading = false, session = s,
                access = jsonOf("user_id" to s.userId, "organization_id" to null, "role" to null, "full_name" to null, "permissions" to emptyList<String>(), "load_error" to (e.message ?: "")),
            )
        }
    }

    /** Re-reads access, organization and settings (after profile / settings changes). */
    suspend fun refresh() {
        val s = Supa.session.value ?: return
        loadedFor = null
        load(s)
    }

    fun refreshAsync() { scope.launch { refresh() } }

    fun signOut() {
        scope.launch { Supa.signOut() }
    }

    /** Called on every touch (MainActivity) so the inactivity timeout restarts. */
    fun touch() { lastActivity = System.currentTimeMillis() }

    /** Settings → Security → session timeout (minutes; 0 = never), like the web app. */
    private fun startSessionTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            while (isActive) {
                delay(30_000)
                val st = _state.value
                if (st.session == null || st.settings == null) continue
                val minutes = st.setting("security")?.int("session_timeout_minutes") ?: 60
                if (minutes <= 0) continue
                if (System.currentTimeMillis() - lastActivity > minutes * 60_000L) {
                    Supa.signOut()
                }
            }
        }
    }

    /** When the app comes back after a long time in the background, enforce the timeout right away. */
    fun onResume() {
        val st = _state.value
        val minutes = st.setting("security")?.int("session_timeout_minutes") ?: 60
        if (st.session != null && minutes > 0 && System.currentTimeMillis() - lastActivity > minutes * 60_000L) {
            scope.launch { Supa.signOut() }
            return
        }
        DataBus.bump(0)
    }

    @Suppress("unused") private fun keep(r: Row) = r.str("id")
}
