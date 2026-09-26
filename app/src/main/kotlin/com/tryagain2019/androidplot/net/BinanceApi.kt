package com.tryagain2019.androidplot.net

import com.tryagain2019.androidplot.model.Candle
import com.tryagain2019.androidplot.model.FundingEvent
import com.tryagain2019.androidplot.model.LiveFunding
import com.tryagain2019.androidplot.model.Sample
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** Binance USDⓈ-M futures public REST API (BTCUSDT perpetual). */
class BinanceApi(
    private val client: OkHttpClient,
    private val base: String = "https://fapi.binance.com",
    val symbol: String = "BTCUSDT",
) {
    /** Candles in ascending order. Binance returns at most 1500 per call. */
    suspend fun klines(interval: String, limit: Int, endTime: Long? = null): List<Candle> {
        val url = "$base/fapi/v1/klines".toHttpUrl().newBuilder()
            .addQueryParameter("symbol", symbol)
            .addQueryParameter("interval", interval)
            .addQueryParameter("limit", limit.toString())
            .apply { if (endTime != null) addQueryParameter("endTime", endTime.toString()) }
            .build()
        return client.get(url.toString(), BinanceParsers::klines)
    }

    /** Open-interest snapshots; Binance only keeps the last 30 days here. At most 500 per call. */
    suspend fun openInterestHist(period: String, startTime: Long, endTime: Long, limit: Int = 500): List<Sample> {
        val url = "$base/futures/data/openInterestHist".toHttpUrl().newBuilder()
            .addQueryParameter("symbol", symbol)
            .addQueryParameter("period", period)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("startTime", startTime.toString())
            .addQueryParameter("endTime", endTime.toString())
            .build()
        return client.get(url.toString(), BinanceParsers::openInterestHist)
    }

    suspend fun openInterest(): Sample = client.get("$base/fapi/v1/openInterest?symbol=$symbol", BinanceParsers::openInterest)

    /** Settled funding in ascending order, at most 1000 per call. */
    suspend fun fundingRates(startTime: Long, endTime: Long, limit: Int = 1000): List<FundingEvent> {
        val url = "$base/fapi/v1/fundingRate".toHttpUrl().newBuilder()
            .addQueryParameter("symbol", symbol)
            .addQueryParameter("startTime", startTime.toString())
            .addQueryParameter("endTime", endTime.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        return client.get(url.toString(), BinanceParsers::fundingRates)
    }

    /** Mark price info including the funding rate that will be settled next. */
    suspend fun premiumIndex(): LiveFunding = client.get("$base/fapi/v1/premiumIndex?symbol=$symbol", BinanceParsers::premiumIndex)
}

object BinanceParsers {
    fun klines(json: String): List<Candle> {
        val arr = JSONArray(json)
        val out = ArrayList<Candle>(arr.length())
        arr.forEachArray { k ->
            val t = k.long(0) ?: return@forEachArray
            val c = Candle(t, k.num(1), k.num(2), k.num(3), k.num(4))
            if (c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite()) out += c
        }
        return out.sortedBy { it.time }
    }

    fun openInterestHist(json: String): List<Sample> {
        val trimmed = json.trim()
        if (trimmed.startsWith("{")) throw ApiException(errorDetail(trimmed).ifEmpty { "unexpected response" })
        val out = ArrayList<Sample>()
        JSONArray(trimmed).forEachObject { o ->
            val t = o.long("timestamp") ?: return@forEachObject
            val v = o.num("sumOpenInterest")
            if (v.isFinite()) out += Sample(t, v)
        }
        return out.sortedBy { it.time }
    }

    fun openInterest(json: String): Sample {
        val o = JSONObject(json)
        val v = o.num("openInterest")
        if (!v.isFinite()) throw ApiException("no openInterest in response")
        return Sample(o.long("time") ?: System.currentTimeMillis(), v)
    }

    fun fundingRates(json: String): List<FundingEvent> {
        val out = ArrayList<FundingEvent>()
        JSONArray(json).forEachObject { o ->
            val t = o.long("fundingTime") ?: return@forEachObject
            val r = o.num("fundingRate")
            if (r.isFinite()) out += FundingEvent(t, r)
        }
        return out.sortedBy { it.time }
    }

    fun premiumIndex(json: String): LiveFunding {
        val t = json.trim()
        // Without a symbol the endpoint returns an array; accept either shape.
        val o = if (t.startsWith("[")) JSONArray(t).getJSONObject(0) else JSONObject(t)
        val r = o.num("lastFundingRate")
        if (!r.isFinite()) throw ApiException("no lastFundingRate in response")
        return LiveFunding(r, o.long("nextFundingTime")?.takeIf { it > 0 })
    }

    /** One message of the `<symbol>@kline_<interval>` stream (raw or combined-stream envelope). Returns null for other messages. */
    fun wsKline(json: String): Candle? {
        val root = JSONObject(json)
        val data = root.optJSONObject("data") ?: root
        val k = data.optJSONObject("k") ?: return null
        val t = k.long("t") ?: return null
        val c = Candle(t, k.num("o"), k.num("h"), k.num("l"), k.num("c"))
        return if (c.open.isFinite() && c.close.isFinite() && c.high.isFinite() && c.low.isFinite()) c else null
    }
}
