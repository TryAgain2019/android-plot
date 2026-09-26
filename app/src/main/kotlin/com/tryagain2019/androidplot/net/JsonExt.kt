package com.tryagain2019.androidplot.net

import org.json.JSONArray
import org.json.JSONObject

// Exchanges send numbers both as JSON numbers and as strings; these accept either.

internal fun Any?.asDouble(): Double = when (this) {
    is Number -> toDouble()
    is String -> toDoubleOrNull() ?: Double.NaN
    else -> Double.NaN
}

internal fun Any?.asLong(): Long? = when (this) {
    is Number -> toLong()
    is String -> toLongOrNull() ?: toDoubleOrNull()?.toLong()
    else -> null
}

internal fun JSONObject.num(key: String): Double = if (has(key) && !isNull(key)) opt(key).asDouble() else Double.NaN

internal fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) opt(key).asLong() else null

internal fun JSONArray.num(index: Int): Double = opt(index).asDouble()

internal fun JSONArray.long(index: Int): Long? = opt(index).asLong()

internal inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(block)
}

internal inline fun JSONArray.forEachArray(block: (JSONArray) -> Unit) {
    for (i in 0 until length()) optJSONArray(i)?.let(block)
}
