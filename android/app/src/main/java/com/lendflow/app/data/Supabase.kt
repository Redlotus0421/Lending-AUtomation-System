package com.lendflow.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.lendflow.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** An error with a readable sentence, like the web app's ApiError. */
class ApiError(message: String, val code: String? = null, val status: Int = 0) : Exception(message)

/** The signed-in Supabase Auth session. */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    /** epoch seconds */
    val expiresAt: Long,
    val user: JsonObject,
) {
    val userId: String get() = user.str("id")
    val email: String get() = user.str("email")
    val userMetadata: JsonObject get() = user.obj("user_metadata") ?: EmptyRow
    val mustChangePassword: Boolean get() = userMetadata.bool("must_change_password")
}

/**
 * Minimal native Supabase client (Auth, PostgREST, RPC, Edge Functions, Storage) on OkHttp.
 * It talks to exactly the same project, RPCs, RLS policies and Edge Functions as the web app, so
 * every business rule is enforced by the server for both clients.
 */
object Supa {
    val url: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anonKey: String = BuildConfig.SUPABASE_ANON_KEY
    val webUrl: String = BuildConfig.WEB_URL.trimEnd('/')
    val isConfigured: Boolean get() = url.startsWith("http") && anonKey.isNotBlank()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private lateinit var prefs: SharedPreferences
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session
    private val refreshLock = Mutex()

    /** Called on sign-out (session cleared) so caches and drafts can be wiped. */
    var onSignedOut: (() -> Unit)? = null
    /** Called whenever a new access token is issued (Realtime re-authenticates with it). */
    var onTokenChanged: ((String?) -> Unit)? = null

    fun init(context: Context) {
        prefs = try {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, "lendflow.session", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // keystore problems on some devices: fall back to private prefs (still app-sandboxed)
            Log.w("Supa", "Encrypted prefs unavailable", e)
            context.getSharedPreferences("lendflow.session.plain", Context.MODE_PRIVATE)
        }
        _session.value = loadSession()
    }

    private fun loadSession(): Session? = try {
        val raw = prefs.getString("session", null) ?: return null
        val o = parseJson(raw) as JsonObject
        Session(o.str("access_token"), o.str("refresh_token"), o.long("expires_at"), o.obj("user") ?: EmptyRow)
    } catch (_: Exception) {
        null
    }

    private fun saveSession(s: Session?) {
        _session.value = s
        if (s == null) prefs.edit().remove("session").apply()
        else prefs.edit().putString(
            "session",
            jsonOf("access_token" to s.accessToken, "refresh_token" to s.refreshToken, "expires_at" to s.expiresAt, "user" to s.user).toString(),
        ).apply()
        onTokenChanged?.invoke(s?.accessToken)
    }

    // -----------------------------------------------------------------------
    // HTTP plumbing
    // -----------------------------------------------------------------------
    private suspend fun execute(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { r: Response -> r.code to (r.body?.string() ?: "") }
        } catch (e: IOException) {
            throw ApiError("Network error — check your internet connection and try again.", "network")
        }
    }

    private fun baseRequest(url: HttpUrl, token: String?): Request.Builder =
        Request.Builder().url(url)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${token ?: anonKey}")
            .header("X-Client-Info", "lendflow-android/${BuildConfig.VERSION_NAME}")

    /** A valid access token (refreshed when it is about to expire), or null when signed out. */
    suspend fun accessToken(): String? {
        val s = _session.value ?: return null
        val now = System.currentTimeMillis() / 1000
        if (s.expiresAt - now > 60) return s.accessToken
        return refreshSession()?.accessToken
    }

    suspend fun refreshSession(): Session? {
        val before = _session.value ?: return null
        return refreshLock.withLock { refreshLocked(before) }
    }

    private suspend fun refreshLocked(before: Session): Session? {
        val s = _session.value ?: return null
        // another caller refreshed while this one waited for the lock
        if (s !== before) return s
        val (code, body) = execute(
            baseRequest("$url/auth/v1/token?grant_type=refresh_token".toHttpUrl(), null)
                .post(jsonOf("refresh_token" to s.refreshToken).toString().toRequestBody(JSON)).build(),
        )
        return if (code in 200..299) {
            val o = parseJson(body) as JsonObject
            val ns = sessionFrom(o)
            saveSession(ns)
            ns
        } else if (code in 400..499) {
            // refresh token revoked / expired: the user must sign in again
            signOutLocal()
            null
        } else {
            s
        }
    }

    private fun sessionFrom(o: JsonObject): Session {
        val expiresIn = o.long("expires_in").takeIf { it > 0 } ?: 3600
        val expiresAt = o.long("expires_at").takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000 + expiresIn)
        return Session(o.str("access_token"), o.str("refresh_token"), expiresAt, o.obj("user") ?: EmptyRow)
    }

    /** Runs a request with the current token; on an expired JWT refreshes once and retries. */
    private suspend fun send(build: (token: String?) -> Request): JsonElement {
        var token = accessToken()
        var (code, body) = execute(build(token))
        if (code == 401 && _session.value != null && body.contains("JWT", ignoreCase = true)) {
            token = refreshSession()?.accessToken
            val r = execute(build(token))
            code = r.first; body = r.second
        }
        if (code !in 200..299) throw errorFrom(code, body)
        return try { parseJson(body) } catch (_: Exception) { JsonNull }
    }

    private fun errorFrom(code: Int, body: String): ApiError {
        val o = try { parseJson(body) as? JsonObject } catch (_: Exception) { null }
        val msg = o?.strOrNull("message") ?: o?.strOrNull("error_description") ?: o?.strOrNull("msg") ?: o?.strOrNull("error") ?: ""
        val pgCode = o?.strOrNull("code") ?: o?.strOrNull("error_code")
        return ApiError(readableError(msg, pgCode, o?.strOrNull("details"), code), pgCode, code)
    }

    // -----------------------------------------------------------------------
    // Auth
    // -----------------------------------------------------------------------
    suspend fun signIn(email: String, password: String) {
        val (code, body) = execute(
            baseRequest("$url/auth/v1/token?grant_type=password".toHttpUrl(), null)
                .post(jsonOf("email" to email.trim(), "password" to password).toString().toRequestBody(JSON)).build(),
        )
        if (code !in 200..299) {
            val o = try { parseJson(body) as? JsonObject } catch (_: Exception) { null }
            val m = o?.strOrNull("error_description") ?: o?.strOrNull("msg") ?: o?.strOrNull("message") ?: "Sign in failed"
            throw ApiError(if (m.contains("Invalid login credentials", true)) "Incorrect email or password." else m)
        }
        saveSession(sessionFrom(parseJson(body) as JsonObject))
    }

    suspend fun resetPasswordForEmail(email: String) {
        val redirect = URLEncoder.encode("$webUrl/reset-password", "UTF-8")
        val (code, body) = execute(
            baseRequest("$url/auth/v1/recover?redirect_to=$redirect".toHttpUrl(), null)
                .post(jsonOf("email" to email.trim()).toString().toRequestBody(JSON)).build(),
        )
        if (code !in 200..299) throw errorFrom(code, body)
    }

    /** PUT /auth/v1/user — password and/or user metadata. */
    suspend fun updateUser(password: String? = null, data: Map<String, Any?>? = null) {
        val payload = jsonOfNotNull("password" to password, "data" to data)
        val res = send { t -> baseRequest("$url/auth/v1/user".toHttpUrl(), t).put(payload.toString().toRequestBody(JSON)).build() }
        val user = res as? JsonObject ?: return
        _session.value?.let { saveSession(it.copy(user = user)) }
    }

    suspend fun reloadUser() {
        val res = send { t -> baseRequest("$url/auth/v1/user".toHttpUrl(), t).get().build() }
        val user = res as? JsonObject ?: return
        _session.value?.let { saveSession(it.copy(user = user)) }
    }

    suspend fun signOut() {
        val t = _session.value?.accessToken
        if (t != null) {
            try {
                execute(baseRequest("$url/auth/v1/logout?scope=local".toHttpUrl(), t).post("{}".toRequestBody(JSON)).build())
            } catch (_: Exception) { /* signing out locally regardless */ }
        }
        signOutLocal()
    }

    fun signOutLocal() {
        if (_session.value == null) return
        saveSession(null)
        onSignedOut?.invoke()
    }

    // -----------------------------------------------------------------------
    // PostgREST
    // -----------------------------------------------------------------------
    fun from(table: String) = Query(table)

    class Page(val rows: List<Row>, val count: Int)

    class Query internal constructor(private val table: String) {
        private val params = mutableListOf<Pair<String, String>>()
        private val orders = mutableListOf<String>()
        private var select = "*"
        private var rangeFrom: Int? = null
        private var rangeTo: Int? = null

        fun select(columns: String) = apply { select = columns.replace(Regex("\\s+"), "") }
        private fun f(col: String, op: String, v: Any?) = apply { params += col to "$op.${v.literal()}" }
        fun eq(col: String, v: Any?) = f(col, "eq", v)
        fun neq(col: String, v: Any?) = f(col, "neq", v)
        fun gt(col: String, v: Any?) = f(col, "gt", v)
        fun gte(col: String, v: Any?) = f(col, "gte", v)
        fun lt(col: String, v: Any?) = f(col, "lt", v)
        fun lte(col: String, v: Any?) = f(col, "lte", v)
        fun ilike(col: String, pattern: String) = apply { params += col to "ilike.$pattern" }
        fun isNull(col: String) = apply { params += col to "is.null" }
        fun notNull(col: String) = apply { params += col to "not.is.null" }
        fun inList(col: String, values: Collection<Any?>) = apply {
            params += col to "in.(${values.joinToString(",") { quote(it.literal()) }})"
        }
        /** PostgREST logic tree, e.g. "full_name.ilike.%ana%,borrower_code.ilike.%ana%" */
        fun or(expr: String) = apply { params += "or" to "($expr)" }
        fun order(col: String, ascending: Boolean = true, nullsFirst: Boolean? = null) = apply {
            orders += buildString {
                append(col); append(if (ascending) ".asc" else ".desc")
                if (nullsFirst == true) append(".nullsfirst") else if (nullsFirst == false) append(".nullslast")
            }
        }
        fun limit(n: Int) = apply { params += "limit" to n.toString() }
        fun range(from: Int, to: Int) = apply { rangeFrom = from; rangeTo = to }
        fun page(page: Int, pageSize: Int) = range(page * pageSize, page * pageSize + pageSize - 1)

        private fun Any?.literal(): String = when (this) {
            null -> "null"
            is Boolean -> this.toString()
            else -> this.toString()
        }
        private fun quote(s: String) = if (s.any { it == ',' || it == '(' || it == ')' || it == '"' || it == ' ' }) "\"${s.replace("\"", "\\\"")}\"" else s

        private fun buildUrl(withSelect: Boolean = true): HttpUrl {
            val b = "$url/rest/v1/$table".toHttpUrl().newBuilder()
            if (withSelect) b.addQueryParameter("select", select)
            params.forEach { (k, v) -> b.addQueryParameter(k, v) }
            if (orders.isNotEmpty()) b.addQueryParameter("order", orders.joinToString(","))
            return b.build()
        }

        private fun Request.Builder.rangeHeaders(): Request.Builder {
            if (rangeFrom != null) header("Range-Unit", "items").header("Range", "$rangeFrom-$rangeTo")
            return this
        }

        suspend fun get(): List<Row> {
            val u = buildUrl()
            return send { t -> baseRequest(u, t).rangeHeaders().get().build() }.objList()
        }

        /** Rows plus the exact total (Prefer: count=exact), like `{ count: 'exact' }` in supabase-js. */
        suspend fun getPage(): Page = withContext(Dispatchers.IO) {
            val u = buildUrl()
            var token = accessToken()
            fun req(t: String?) = baseRequest(u, t).rangeHeaders().header("Prefer", "count=exact").get().build()
            var resp = try { http.newCall(req(token)).execute() } catch (e: IOException) {
                throw ApiError("Network error — check your internet connection and try again.", "network")
            }
            if (resp.code == 401 && session.value != null) {
                resp.close()
                token = refreshSession()?.accessToken
                resp = try { http.newCall(req(token)).execute() } catch (e: IOException) {
                    throw ApiError("Network error — check your internet connection and try again.", "network")
                }
            }
            resp.use { r ->
                val body = r.body?.string() ?: ""
                // 416 = page past the end
                if (r.code == 416) return@withContext Page(emptyList(), r.header("Content-Range")?.substringAfter('/')?.toIntOrNull() ?: 0)
                if (r.code !in 200..299) throw errorFrom(r.code, body)
                val total = r.header("Content-Range")?.substringAfter('/')?.toIntOrNull() ?: 0
                Page(parseJson(body).objList(), total)
            }
        }

        suspend fun single(): Row {
            val u = buildUrl()
            val res = send { t -> baseRequest(u, t).header("Accept", "application/vnd.pgrst.object+json").get().build() }
            return res as? JsonObject ?: throw ApiError("Record not found")
        }

        suspend fun maybeSingle(): Row? {
            val rows = limit(2).get()
            return rows.firstOrNull()
        }

        suspend fun insert(body: Any?, returning: Boolean = true): List<Row> {
            val u = buildUrl(withSelect = returning)
            val payload = body.toJson().toString()
            return send { t ->
                baseRequest(u, t).header("Prefer", if (returning) "return=representation" else "return=minimal")
                    .post(payload.toRequestBody(JSON)).build()
            }.objList()
        }

        suspend fun insertSingle(body: Any?): Row = insert(body).firstOrNull() ?: throw ApiError("The record was not saved")

        /** PATCH with the filters set on this query. */
        suspend fun update(patch: Any?, returning: Boolean = true): List<Row> {
            val u = buildUrl(withSelect = returning)
            val payload = patch.toJson().toString()
            return send { t ->
                baseRequest(u, t).header("Prefer", if (returning) "return=representation" else "return=minimal")
                    .patch(payload.toRequestBody(JSON)).build()
            }.objList()
        }

        suspend fun updateSingle(patch: Any?): Row = update(patch).firstOrNull()
            ?: throw ApiError("You do not have permission to perform this action.")

        suspend fun delete() {
            val u = buildUrl(withSelect = false)
            send { t -> baseRequest(u, t).header("Prefer", "return=minimal").delete().build() }
        }
    }

    // -----------------------------------------------------------------------
    // RPC and Edge Functions
    // -----------------------------------------------------------------------
    suspend fun rpc(fn: String, args: Map<String, Any?> = emptyMap()): JsonElement {
        val payload = args.toJson().toString()
        val u = "$url/rest/v1/rpc/$fn".toHttpUrl()
        return send { t -> baseRequest(u, t).post(payload.toRequestBody(JSON)).build() }
    }

    suspend fun rpcObj(fn: String, args: Map<String, Any?> = emptyMap()): Row = rpc(fn, args) as? JsonObject ?: EmptyRow
    suspend fun rpcList(fn: String, args: Map<String, Any?> = emptyMap()): List<Row> = rpc(fn, args).objList()

    suspend fun invoke(fn: String, body: Map<String, Any?>): JsonElement {
        val payload = body.toJson().toString()
        val u = "$url/functions/v1/$fn".toHttpUrl()
        val res = send { t -> baseRequest(u, t).post(payload.toRequestBody(JSON)).build() }
        val err = (res as? JsonObject)?.strOrNull("error")
        if (!err.isNullOrBlank()) throw ApiError(err)
        return res
    }

    suspend fun invokeObj(fn: String, body: Map<String, Any?>): Row = invoke(fn, body) as? JsonObject ?: EmptyRow

    // -----------------------------------------------------------------------
    // Storage
    // -----------------------------------------------------------------------
    private fun encPath(path: String) = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    suspend fun signedUrl(bucket: String, path: String, seconds: Int = 600, download: String? = null): String {
        val u = "$url/storage/v1/object/sign/$bucket/${encPath(path)}".toHttpUrl()
        val res = try {
            send { t -> baseRequest(u, t).post(jsonOf("expiresIn" to seconds).toString().toRequestBody(JSON)).build() } as? JsonObject
        } catch (e: ApiError) {
            throw ApiError(if (e.message.isNullOrBlank()) "Could not open the file" else e.message!!)
        }
        val signed = res?.strOrNull("signedURL") ?: res?.strOrNull("signedUrl") ?: throw ApiError("Could not open the file")
        val full = "$url/storage/v1$signed"
        return if (download != null) "$full&download=${URLEncoder.encode(download, "UTF-8")}" else full
    }

    suspend fun upload(bucket: String, path: String, bytes: ByteArray, contentType: String?, upsert: Boolean = false): String {
        val u = "$url/storage/v1/object/$bucket/${encPath(path)}".toHttpUrl()
        val media = (contentType ?: "application/octet-stream").toMediaType()
        try {
            send { t ->
                baseRequest(u, t).header("x-upsert", upsert.toString()).header("cache-control", "max-age=3600")
                    .post(bytes.toRequestBody(media)).build()
            }
        } catch (e: ApiError) {
            throw ApiError(e.message?.ifBlank { null } ?: "Upload failed")
        }
        return path
    }

    suspend fun removeFiles(bucket: String, paths: List<String>) {
        val u = "$url/storage/v1/object/$bucket".toHttpUrl()
        val payload = jsonOf("prefixes" to paths).toString()
        try {
            send { t -> baseRequest(u, t).delete(payload.toRequestBody(JSON)).build() }
        } catch (_: Exception) { /* best effort, like the web app */ }
    }

    /** Downloads any URL (signed storage links, PDFs) to bytes. */
    suspend fun download(fileUrl: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(reachableUrl(fileUrl)).build()).execute().use { r ->
                if (!r.isSuccessful) throw ApiError("Could not download the file (${r.code})")
                r.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: IOException) {
            throw ApiError("Network error — check your internet connection and try again.", "network")
        }
    }

    /**
     * Debug builds against a local Supabase stack: Edge Functions sign file URLs with the stack's
     * internal host (http://kong:8000), which a phone cannot reach. Point them at [url] instead.
     * Release builds use the hosted project, whose signed URLs are already public.
     */
    fun reachableUrl(fileUrl: String): String {
        if (!BuildConfig.DEBUG) return fileUrl
        val m = Regex("^https?://(kong:8000|127\\.0\\.0\\.1:\\d+|localhost:\\d+)").find(fileUrl) ?: return fileUrl
        return url + fileUrl.substring(m.range.last + 1)
    }

    /** Decodes the JWT payload of the current access token (for debugging / role claims). */
    fun jwtClaims(): JsonObject? = try {
        val t = _session.value?.accessToken ?: return null
        val part = t.split('.')[1]
        parseJson(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))) as? JsonObject
    } catch (_: Exception) { null }
}

/** Port of the web app's errorMessage(): turns Supabase/PostgREST errors into a readable sentence. */
fun readableError(message: String?, code: String?, details: String? = null, status: Int = 0): String {
    var msg = message ?: ""
    if (code == "23505" || msg.contains("duplicate key", true)) {
        return when {
            msg.contains("borrowers_dup_name_dob") -> "A borrower with the same name and date of birth already exists."
            msg.contains("borrower_identities_doc") -> "Another borrower is already registered with this ID document."
            msg.contains("loan_payments_reference") -> "A payment with this transaction reference already exists."
            msg.contains("loan_products_organization_id_code") -> "A product with this code already exists."
            msg.contains("duplicate key") -> "This record already exists."
            else -> msg
        }
    }
    if (code == "42501" || msg.contains("row-level security", true)) {
        return if (msg.contains("row-level security", true)) "You do not have permission to perform this action." else msg
    }
    if (code == "PGRST301" || msg.contains("JWT expired", true)) return "Your session expired. Please sign in again."
    msg = msg.replace(Regex("^Edge Function returned a non-2xx status code$"), "")
    if (msg.isBlank() && !details.isNullOrBlank()) return details
    if (msg.isBlank()) return if (status >= 500) "The server could not complete the request. Please try again." else "Something went wrong. Please try again."
    return msg
}

/** Readable message for any exception shown to the user. */
fun Throwable.readable(): String = when (this) {
    is ApiError -> message ?: "Something went wrong. Please try again."
    is IOException -> "Network error — check your internet connection and try again."
    else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again."
}

/** Same rules as the web app's safeFileName(). */
fun safeFileName(name: String): String {
    val ext = if (name.contains('.')) name.substringAfterLast('.').lowercase().replace(Regex("[^a-z0-9]"), "") else "bin"
    val base = name.replace(Regex("\\.[^.]+$"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "file" }
    return "$base-${System.currentTimeMillis().toString(36)}.${ext.ifEmpty { "bin" }}"
}

fun newIdempotencyKey(): String = java.util.UUID.randomUUID().toString()

/** Mirrors `.replace(/[%,()]/g, ' ')` used before building PostgREST `or` filters. */
fun cleanSearch(s: String): String = s.trim().replace(Regex("[%,()]"), " ")

@Suppress("unused")
private val keepImports = JsonArray(emptyList())
