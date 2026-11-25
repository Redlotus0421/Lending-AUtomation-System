package com.lendflow.app.feature.loans

// Port of src/features/loans/wizard.ts — shared helpers for the loan-creation wizards.
//
// * The current step is kept by the wizard screen; the phone's back gesture moves to the previous
//   step instead of leaving the page (BackHandler), so nothing typed is lost.
// * Entries are also saved to SharedPreferences "lendflow.drafts" (cleared on sign-out), so leaving
//   the page by accident, following a link or the app being closed restores them.
// * Picked files are not written to disk; they are kept in memory for the app session and survive
//   in-app navigation (not a restart of the app).

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lendflow.app.data.Row
import com.lendflow.app.data.parseJson
import com.lendflow.app.data.readable
import com.lendflow.app.ui.components.Btn
import com.lendflow.app.ui.components.BtnVariant
import com.lendflow.app.ui.components.WidthClass
import com.lendflow.app.ui.components.isCompact
import com.lendflow.app.ui.components.widthClass
import com.lendflow.app.ui.theme.LF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

const val DRAFT_PREFIX = "lendflow:loan-draft:"
private const val PREFS = "lendflow.drafts"

object Drafts {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context, key: String): JsonObject? = try {
        prefs(ctx).getString(DRAFT_PREFIX + key, null)?.let { parseJson(it) as? JsonObject }
    } catch (_: Exception) {
        null
    }

    fun save(ctx: Context, key: String, value: JsonObject) {
        try { prefs(ctx).edit().putString(DRAFT_PREFIX + key, value.toString()).apply() } catch (_: Exception) { /* the in-page state still works */ }
    }

    fun clear(ctx: Context, key: String) {
        try { prefs(ctx).edit().remove(DRAFT_PREFIX + key).apply() } catch (_: Exception) { }
        memoryFiles.remove(key)
    }

    /** Remove every saved loan draft (drafts contain borrower details). */
    fun clearAll(ctx: Context) {
        try {
            val p = prefs(ctx)
            val e = p.edit()
            p.all.keys.filter { it.startsWith(DRAFT_PREFIX) }.forEach { e.remove(it) }
            e.apply()
        } catch (_: Exception) { }
        memoryFiles.clear()
    }

    private val memoryFiles = mutableMapOf<String, Any?>()

    /** Non-serializable values (picked files) kept for the lifetime of the app process. */
    @Suppress("UNCHECKED_CAST")
    fun <T> getFiles(key: String): T? = memoryFiles[key] as T?
    fun setFiles(key: String, value: Any?) { memoryFiles[key] = value }
}

/**
 * Debounced autosave of a draft. Call [update] on every composition with the latest value; the draft
 * is written 300 ms after the last change and when the screen is left. [stop] disables it right away
 * (before navigating to the saved loan) so a pending save cannot resurrect the cleared draft.
 */
class DraftSaver(private val ctx: Context, val key: String, enabled: Boolean) {
    var enabled: Boolean = enabled
    private var latest: JsonObject? = null
    fun update(v: JsonObject) { latest = v }
    fun flush() { val v = latest; if (enabled && v != null) Drafts.save(ctx, key, v) }
    fun stop() { enabled = false; Drafts.clear(ctx, key) }
}

@Composable
fun rememberDraftSaver(key: String, enabled: Boolean, value: JsonObject): DraftSaver {
    val ctx = LocalContext.current
    val saver = remember(key) { DraftSaver(ctx, key, enabled) }
    saver.update(value)
    val json = value.toString()
    LaunchedEffect(json) {
        delay(300)
        saver.flush()
    }
    DisposableEffect(saver) { onDispose { saver.flush() } }
    return saver
}

// ---------------------------------------------------------------------------
// Live server preview (useQuery with keepPreviousData, retry: false)
// ---------------------------------------------------------------------------
class PreviewState(
    val data: Row?,
    val error: String?,
    val fetching: Boolean,
    /** the request (JSON) whose result is [data] */
    val dataKey: String?,
    val refetch: () -> Unit,
) {
    /** The last request succeeded and matches [key]. */
    fun freshFor(key: String?): Boolean = key != null && dataKey == key && error == null && !fetching
}

@Composable
fun rememberPreview(json: String?, enabled: Boolean = true, fetch: suspend (JsonObject) -> Row): PreviewState {
    var data by remember { mutableStateOf<Row?>(null) }
    var dataKey by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var counter by remember { mutableIntStateOf(0) }
    LaunchedEffect(json, enabled, counter) {
        if (json == null || !enabled) {
            fetching = false
            error = null
            return@LaunchedEffect
        }
        fetching = true
        error = null
        try {
            val r = fetch(parseJson(json) as JsonObject)
            data = r
            dataKey = json
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.readable()
            dataKey = null
        } finally {
            fetching = false
        }
    }
    return PreviewState(data, error, fetching, dataKey) { counter++ }
}

// ---------------------------------------------------------------------------
// UI shared by both wizards
// ---------------------------------------------------------------------------

/** Every step change starts at the top of the page (window.scrollTo on the web). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScrollToTopOnChange(key: Any?, requester: BringIntoViewRequester) {
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(key) {
        if (first) { first = false; return@LaunchedEffect }
        try { requester.bringIntoView() } catch (_: Exception) { }
    }
}

/** Progress bars with numbered steps (the wizard stepper). */
@Composable
fun WizardStepper(labels: List<String>, current: Int, enabled: (Int) -> Boolean, onClick: (Int) -> Unit, showLabels: Boolean = !isCompact()) {
    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, s ->
            val on = enabled(i)
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).clickable(enabled = on) { onClick(i) },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(if (i <= current) LF.Blue else LF.Slate200))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.size(20.dp).clip(CircleShape).background(
                            when {
                                i < current -> LF.Blue
                                i == current -> LF.Navy
                                else -> LF.Slate200
                            },
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (i < current) Icon(Icons.Default.Check, null, Modifier.size(12.dp), tint = Color.White)
                        else Text("${i + 1}", fontSize = 10.sp, color = if (i == current) Color.White else LF.Slate600)
                    }
                    if (showLabels) {
                        Text(
                            s, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (i == current) LF.Navy else LF.MutedForeground,
                        )
                    }
                }
            }
        }
    }
}

/** "Your unsaved entries were restored." banner. */
@Composable
fun RestoredBanner(text: String, onStartOver: (() -> Unit)?, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(12.dp)).background(LF.Blue50)
            .border(1.dp, LF.Blue200, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.History, null, Modifier.size(16.dp), tint = Color(0xFF1E3A8A))
        Text(text, Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 14.sp, color = Color(0xFF1E3A8A))
        Column(horizontalAlignment = Alignment.End) {
            if (onStartOver != null) Btn("Start over", onStartOver, variant = BtnVariant.Outline, small = true)
            Btn("Dismiss", onDismiss, variant = BtnVariant.Ghost, small = true)
        }
    }
}

/**
 * Main column and a side panel (`lg:grid-cols-[1fr_380px]`): side by side on tablets, stacked on
 * phones (the side panel below).
 */
@Composable
fun MainAside(asideWidth: Dp = 380.dp, main: @Composable ColumnScope.() -> Unit, aside: @Composable ColumnScope.() -> Unit) {
    if (widthClass() == WidthClass.Expanded) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp), content = main)
            Column(Modifier.width(asideWidth), verticalArrangement = Arrangement.spacedBy(12.dp), content = aside)
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            main()
            aside()
        }
    }
}

/** White bar with the step's buttons (`flex gap-2 rounded-xl border bg-white p-3`). */
@Composable
fun ActionBar(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, LF.Border, RoundedCornerShape(12.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
