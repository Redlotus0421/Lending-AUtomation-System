package com.lendflow.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lendflow.app.app.AppState
import com.lendflow.app.data.DataBus
import com.lendflow.app.data.Realtime
import com.lendflow.app.data.Supa
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LendFlowApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poller: Job? = null

    override fun onCreate() {
        super.onCreate()
        Supa.init(this)
        Supa.onTokenChanged = { token -> Realtime.updateToken(token) }
        Supa.onSignedOut = {
            // unsaved loan drafts contain borrower details; shared files may hold PDFs and receipts
            getSharedPreferences("lendflow.drafts", MODE_PRIVATE).edit().clear().apply()
            cacheDir.resolve("shared").deleteRecursively()
        }
        AppState.start()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppState.onResume()
                if (Supa.session.value != null) Realtime.start()
                startPolling()
            }

            override fun onStop(owner: LifecycleOwner) {
                poller?.cancel()
                Realtime.stop()
            }
        })
    }

    /** Fallback sync: while Realtime is not connected, refresh visible data every 30 seconds. */
    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            while (isActive) {
                delay(30_000)
                if (Supa.session.value != null && !Realtime.connected.value) DataBus.bump(0)
            }
        }
    }
}
