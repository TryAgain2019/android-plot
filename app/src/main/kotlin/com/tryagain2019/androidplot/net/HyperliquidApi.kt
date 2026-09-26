package com.tryagain2019.androidplot.net

import com.tryagain2019.androidplot.model.FundingEvent
import com.tryagain2019.androidplot.model.HOUR
import com.tryagain2019.androidplot.model.LiveFunding
import com.tryagain2019.androidplot.model.LiveSnapshot
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** Hyperliquid info API (BTC perpetual). Hyperliquid funds hourly and publishes no open-interest history. */
class HyperliquidApi(
    private val client: OkHttpClient,
    private val base: String = "https://api.hyperliquid.xyz",
    val coin: String = "BTC",
) {
    /** Current open interest (BTC) and the hourly funding rate accruing now. */
    suspend fun assetContext(now: Long): LiveSnapshot =
        client.post("$base/info", """{"type":"metaAndAssetCtxs"}""") { HyperliquidParsers.assetContext(it, coin, now) }

    /** Hourly funding from [startTime], ascending, at most 500 per call. */
    suspend fun fundingHistory(startTime: Long, endTime: Long): List<FundingEvent> =
        client.post("$base/info", """{"type":"fundingHistory","coin":"$coin","startTime":$startTime,"endTime":$endTime}""", HyperliquidParsers::fundingHistory)
}

object HyperliquidParsers {
    /** Response is `[meta, assetCtxs]` where `assetCtxs[i]` belongs to `meta.universe[i]`. */
    fun assetContext(json: String, coin: String, now: Long): LiveSnapshot {
        val root = JSONArray(json)
        val universe = root.getJSONObject(0).getJSONArray("universe")
        val contexts = root.getJSONArray(1)
        var index = -1
        for (i in 0 until universe.length()) {
            if (universe.optJSONObject(i)?.optString("name") == coin) {
                index = i
                break
            }
        }
        if (index < 0) throw ApiException("Hyperliquid: $coin not listed")
        val ctx: JSONObject = contexts.getJSONObject(index)
        val oi = ctx.num("openInterest").takeIf { it.isFinite() }
        val rate = ctx.num("funding")
        val nextHour = now - Math.floorMod(now, HOUR) + HOUR
        return LiveSnapshot(now, oi, if (rate.isFinite()) LiveFunding(rate, nextHour) else null)
    }

    fun fundingHistory(json: String): List<FundingEvent> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[")) throw ApiException("Hyperliquid: ${errorDetail(trimmed).ifEmpty { "unexpected response" }}")
        val out = ArrayList<FundingEvent>()
        JSONArray(trimmed).forEachObject { o ->
            val t = o.long("time") ?: return@forEachObject
            val r = o.num("fundingRate")
            if (r.isFinite()) out += FundingEvent(t, r)
        }
        return out.sortedBy { it.time }
    }
}
