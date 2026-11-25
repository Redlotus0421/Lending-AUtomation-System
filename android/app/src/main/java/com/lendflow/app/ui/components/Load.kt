package com.lendflow.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.lendflow.app.data.DataBus
import com.lendflow.app.data.readable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** State of a server read (like a React Query result). Old data is kept while re-loading. */
@Stable
class Loadable<T>(
    val data: T?,
    val loading: Boolean,
    val error: String?,
    val reload: () -> Unit,
) {
    /** first load (no data yet) */
    val initialLoading: Boolean get() = loading && data == null && error == null
}

/**
 * Loads data for a screen and re-loads it when [keys] change, when [reload] is called, and — when
 * [live] — whenever [DataBus] reports that data changed (Realtime, writes, app resume).
 */
@Composable
fun <T> rememberLoad(vararg keys: Any?, live: Boolean = true, enabled: Boolean = true, block: suspend () -> T): Loadable<T> {
    var data by remember(*keys) { mutableStateOf<T?>(null) }
    var loading by remember(*keys) { mutableStateOf(true) }
    var error by remember(*keys) { mutableStateOf<String?>(null) }
    var manual by remember { mutableIntStateOf(0) }
    val version by DataBus.version.collectAsState()
    val liveKey = if (live) version else 0L

    LaunchedEffect(*keys, liveKey, manual, enabled) {
        if (!enabled) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            data = block()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // keep showing the previous data after a failed background refresh
            if (data == null) error = e.readable() else Toasts.error(e.readable())
        } finally {
            loading = false
        }
    }
    return Loadable(data, loading, error) { manual++ }
}

/** Value that follows [value] after [delayMs] without changes (search boxes). */
@Composable
fun <T> rememberDebounced(value: T, delayMs: Long = 300): T {
    var v by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        delay(delayMs)
        v = value
    }
    return v
}

// ---------------------------------------------------------------------------
// Toasts (sonner on the web)
// ---------------------------------------------------------------------------
data class ToastMessage(val text: String, val isError: Boolean, val id: Long = System.nanoTime())

object Toasts {
    private val _messages = MutableSharedFlow<ToastMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<ToastMessage> = _messages
    fun success(text: String) { _messages.tryEmit(ToastMessage(text, false)) }
    fun error(text: String) { _messages.tryEmit(ToastMessage(text, true)) }
    fun info(text: String) { _messages.tryEmit(ToastMessage(text, false)) }
}

// ---------------------------------------------------------------------------
// Mutations
// ---------------------------------------------------------------------------
/** Runs a write: tracks busy state, shows errors as toasts and refreshes every screen on success. */
@Stable
class Runner(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set

    /**
     * @param success toast text shown when [block] succeeds (null = none)
     * @param refresh bump [DataBus] after success so lists and details re-load
     */
    fun run(success: String? = null, refresh: Boolean = true, onError: ((String) -> Unit)? = null, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
                if (success != null) Toasts.success(success)
                if (refresh) DataBus.bump(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = e.readable()
                if (onError != null) onError(msg) else Toasts.error(msg)
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun rememberRunner(): Runner {
    val scope = rememberCoroutineScope()
    return remember { Runner(scope) }
}
