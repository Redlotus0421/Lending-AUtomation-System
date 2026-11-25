package com.lendflow.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Rows come back from PostgREST / RPCs as JSON. Like the web app (which reads them as loosely typed
 * objects), the Android client keeps them as [JsonObject]s and reads fields with the helpers below.
 * Money values may arrive as numbers or numeric strings ("1234.50"); [num] accepts both.
 */
val JsonCodec = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
    encodeDefaults = true
}

typealias Row = JsonObject

val EmptyRow: Row = JsonObject(emptyMap())

fun JsonElement?.isNullish(): Boolean = this == null || this is JsonNull

fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

/** A JSON array of objects (non-objects are dropped). */
fun JsonElement?.objList(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

fun JsonElement?.primitiveString(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> this.contentOrNull
    else -> this.toString()
}

/** String value of [key], or null when missing / null. Numbers and booleans are returned as text. */
fun JsonObject?.strOrNull(key: String): String? = this?.get(key).primitiveString()

/** String value of [key], or "" */
fun JsonObject?.str(key: String): String = strOrNull(key) ?: ""

/** Numeric value of [key] (numbers or numeric strings); 0 when missing. */
fun JsonObject?.num(key: String): Double = this?.get(key).toNum()

fun JsonObject?.numOrNull(key: String): Double? {
    val e = this?.get(key) ?: return null
    if (e is JsonNull) return null
    val p = e as? JsonPrimitive ?: return null
    if (p.isString && p.content.isBlank()) return null
    return p.doubleOrNull ?: p.content.toDoubleOrNull()
}

fun JsonElement?.toNum(): Double {
    val p = this as? JsonPrimitive ?: return 0.0
    if (this is JsonNull) return 0.0
    return p.doubleOrNull ?: p.content.toDoubleOrNull() ?: 0.0
}

fun JsonObject?.int(key: String): Int = num(key).toInt()
fun JsonObject?.intOrNull(key: String): Int? = numOrNull(key)?.toInt()
fun JsonObject?.long(key: String): Long = (this?.get(key) as? JsonPrimitive)?.longOrNull ?: num(key).toLong()

fun JsonObject?.bool(key: String, default: Boolean = false): Boolean {
    val p = this?.get(key) as? JsonPrimitive ?: return default
    if (this[key] is JsonNull) return default
    return p.booleanOrNull ?: when (p.content.lowercase()) {
        "true", "t", "1" -> true
        "false", "f", "0" -> false
        else -> default
    }
}

fun JsonObject?.boolOrNull(key: String): Boolean? {
    val e = this?.get(key) ?: return null
    if (e is JsonNull) return null
    return bool(key)
}

fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

/** Embedded relations can be an object or a one-element array (PostgREST). */
fun JsonObject?.objOrFirst(key: String): JsonObject? = when (val e = this?.get(key)) {
    is JsonObject -> e
    is JsonArray -> e.firstOrNull() as? JsonObject
    else -> null
}

fun JsonObject?.arr(key: String): JsonArray = (this?.get(key) as? JsonArray) ?: JsonArray(emptyList())
fun JsonObject?.objs(key: String): List<JsonObject> = this?.get(key).objList()
fun JsonObject?.strings(key: String): List<String> = arr(key).mapNotNull { it.primitiveString() }
fun JsonObject?.numbers(key: String): List<Double> = arr(key).map { it.toNum() }

fun JsonObject?.has(key: String): Boolean = this?.get(key).let { it != null && it !is JsonNull }

// ---------------------------------------------------------------------------
// Building JSON from Kotlin values
// ---------------------------------------------------------------------------

/** Converts Kotlin values (String, Number, Boolean, Map, List, JsonElement, null) to JSON. */
fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Enum<*> -> JsonPrimitive(this.name)
    is Map<*, *> -> JsonObject(this.entries.associate { (k, v) -> k.toString() to v.toJson() })
    is Iterable<*> -> JsonArray(this.map { it.toJson() })
    is Array<*> -> JsonArray(this.map { it.toJson() })
    is IntArray -> JsonArray(this.map { JsonPrimitive(it) })
    is DoubleArray -> JsonArray(this.map { JsonPrimitive(it) })
    else -> JsonPrimitive(this.toString())
}

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to v.toJson() })

/** Like [jsonOf] but leaves out null values (for partial updates where null means "not changed"). */
fun jsonOfNotNull(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.filter { it.second != null }.associate { (k, v) -> k to v.toJson() })

/** Copy of this object with [key] replaced. */
fun JsonObject.with(key: String, value: Any?): JsonObject = JsonObject(this.toMutableMap().apply { put(key, value.toJson()) })
fun JsonObject.withAll(values: Map<String, Any?>): JsonObject =
    JsonObject(this.toMutableMap().apply { values.forEach { (k, v) -> put(k, v.toJson()) } })
fun JsonObject.without(vararg keys: String): JsonObject = JsonObject(this.filterKeys { it !in keys })

fun parseJson(text: String): JsonElement = if (text.isBlank()) JsonNull else JsonCodec.parseToJsonElement(text)
