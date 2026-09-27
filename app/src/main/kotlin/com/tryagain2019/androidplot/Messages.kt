package com.tryagain2019.androidplot

import com.tryagain2019.androidplot.model.Candle
import com.tryagain2019.androidplot.model.Sample
import java.util.Locale

/** Builds the JSON messages understood by chartApp.receive() in assets/chart/app.js. */
internal class Json {
    private val sb = StringBuilder(256)
    private var needComma = false

    fun obj(block: Json.() -> Unit): Json {
        comma()
        sb.append('{')
        needComma = false
        block()
        sb.append('}')
        needComma = true
        return this
    }

    fun key(name: String): Json {
        comma()
        string(name)
        sb.append(':')
        needComma = false
        return this
    }

    fun str(name: String, value: String?) = key(name).also { if (value == null) sb.append("null") else string(value); needComma = true }

    fun num(name: String, value: Number) = key(name).also { appendNumber(value.toDouble(), 10); needComma = true }

    fun bool(name: String, value: Boolean) = key(name).also { sb.append(value); needComma = true }

    fun raw(name: String, json: String) = key(name).also { sb.append(json); needComma = true }

    /** `[[t,o,h,l,c],...]`, or `[[t,o,h,l,c,v],...]` with [volumeDecimals]. */
    fun candles(name: String, bars: List<Candle>, decimals: Int, volumeDecimals: Int? = null) = key(name).also {
        sb.append('[')
        bars.forEachIndexed { i, b ->
            if (i > 0) sb.append(',')
            sb.append('[').append(b.time / 1000).append(',')
            appendNumber(b.open, decimals); sb.append(',')
            appendNumber(b.high, decimals); sb.append(',')
            appendNumber(b.low, decimals); sb.append(',')
            appendNumber(b.close, decimals)
            if (volumeDecimals != null) {
                sb.append(',')
                appendNumber(b.volume, volumeDecimals)
            }
            sb.append(']')
        }
        sb.append(']')
        needComma = true
    }

    fun points(name: String, points: List<Sample>, decimals: Int) = key(name).also {
        sb.append('[')
        points.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append('[').append(p.time / 1000).append(',')
            appendNumber(p.value, decimals)
            sb.append(']')
        }
        sb.append(']')
        needComma = true
    }

    private fun comma() {
        if (needComma) sb.append(',')
    }

    private fun string(s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> sb.append(String.format(Locale.US, "\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Plain decimal notation, at most [decimals] places, never NaN/Infinity (invalid JSON). */
    private fun appendNumber(v: Double, decimals: Int) {
        if (!v.isFinite()) {
            sb.append('0')
            return
        }
        val rounded = java.math.BigDecimal(v).setScale(decimals, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
        sb.append(if (rounded.signum() == 0) "0" else rounded.toPlainString())
    }

    override fun toString() = sb.toString()
}

internal fun json(block: Json.() -> Unit): String = Json().obj(block).toString()
