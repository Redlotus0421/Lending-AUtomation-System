package com.lendflow.app.app

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Navigation uses the same paths as the web app ("/loans/123?tab=agreement"), so notification links,
 * signing links and in-app links work unchanged on Android.
 */
@Stable
class Router(start: String) {
    val stack = mutableStateListOf(normalize(start))
    val current: String get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun go(path: String) {
        val p = normalize(path)
        if (p != current) stack.add(p)
    }

    /** Replace the current entry (redirects, wizard → created loan). */
    fun replace(path: String) {
        stack[stack.lastIndex] = normalize(path)
    }

    /** Top-level navigation from the menu: clears the history down to the dashboard. */
    fun root(path: String) {
        val p = normalize(path)
        val home = stack.first()
        stack.clear()
        stack.add(home)
        if (p != home) stack.add(p)
    }

    fun reset(path: String) {
        stack.clear()
        stack.add(normalize(path))
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    companion object {
        fun normalize(path: String): String {
            var p = path.trim()
            if (p.startsWith("http")) p = Uri.parse(p).let { u -> (u.path ?: "/") + (u.query?.let { "?$it" } ?: "") }
            if (!p.startsWith("/")) p = "/$p"
            if (p.length > 1 && p.endsWith("/") && !p.contains('?')) p = p.dropLast(1)
            return p
        }
    }
}

/** A parsed path: segments and query parameters. */
class Route(val path: String) {
    private val uri = Uri.parse("app://x$path")
    val segments: List<String> = uri.pathSegments
    fun param(name: String): String? = uri.getQueryParameter(name)
    fun seg(i: Int): String? = segments.getOrNull(i)
    /** Matches "loans/:id/edit" style patterns; returns the captured params or null. */
    fun match(pattern: String): Map<String, String>? {
        val ps = pattern.trim('/').split('/').filter { it.isNotEmpty() }
        if (ps.size != segments.size) return null
        val out = mutableMapOf<String, String>()
        ps.forEachIndexed { i, p ->
            if (p.startsWith(":")) out[p.drop(1)] = segments[i]
            else if (p != segments[i]) return null
        }
        return out
    }
    /** "/loans" is active for "/loans/123" unless [end]. */
    fun isUnder(base: String, end: Boolean = false): Boolean {
        val b = base.trimEnd('/').ifEmpty { "/" }
        val p = uri.path ?: "/"
        return if (end || b == "/") p == b else p == b || p.startsWith("$b/")
    }
}

val LocalRouter = staticCompositionLocalOf<Router> { error("Router not provided") }

@Composable
fun rememberRoute(router: Router): Route = Route(router.current)
