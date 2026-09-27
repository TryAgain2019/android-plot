package com.tryagain2019.androidplot.net

import com.tryagain2019.androidplot.model.BookSnapshot
import com.tryagain2019.androidplot.model.FundingEvent
import com.tryagain2019.androidplot.model.LiveFunding
import com.tryagain2019.androidplot.model.LiveSnapshot
import com.tryagain2019.androidplot.model.Sample
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Bybit v5 public REST API, linear BTCUSDT perpetual. */
class BybitApi(
    private val client: OkHttpClient,
    private val base: String = "https://api.bybit.com",
    val symbol: String = "BTCUSDT",
) {
    /** Open-interest snapshots (in BTC) between [startTime] and [endTime]; at most 200 per call. */
    suspend fun openInterest(intervalTime: String, startTime: Long, endTime: Long, limit: Int = 200): List<Sample> {
        val url = "$base/v5/market/open-interest".toHttpUrl().newBuilder()
            .addQueryParameter("category", "linear")
            .addQueryParameter("symbol", symbol)
            .addQueryParameter("intervalTime", intervalTime)
            .addQueryParameter("startTime", startTime.toString())
            .addQueryParameter("endTime", endTime.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        return client.get(url.toString(), BybitParsers::openInterest)
    }

    /** Up to 200 settled funding rates at or before [endTime]. */
    suspend fun fundingHistory(endTime: Long, limit: Int = 200): List<FundingEvent> {
        val url = "$base/v5/market/funding/history".toHttpUrl().newBuilder()
            .addQueryParameter("category", "linear")
            .addQueryParameter("symbol", symbol)
            .addQueryParameter("endTime", endTime.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        return client.get(url.toString(), BybitParsers::fundingHistory)
    }

    /** Current open interest and predicted funding. */
    suspend fun ticker(): LiveSnapshot =
        client.get("$base/v5/market/tickers?category=linear&symbol=$symbol", BybitParsers::ticker)

    /** The current order book, [limit] levels a side (at most 500 for linear contracts). */
    suspend fun orderBook(limit: Int, time: () -> Long): BookSnapshot =
        client.get("$base/v5/market/orderbook?category=linear&symbol=$symbol&limit=$limit") { BybitParsers.orderBook(it, time()) }
}

object BybitParsers {
    private fun result(json: String): JSONObject {
        val root = JSONObject(json)
        val code = root.optInt("retCode", 0)
        if (code != 0) throw ApiException("Bybit: ${root.optString("retMsg").ifEmpty { "error $code" }}")
        return root.optJSONObject("result") ?: throw ApiException("Bybit: empty result")
    }

    fun openInterest(json: String): List<Sample> {
        val out = ArrayList<Sample>()
        result(json).optJSONArray("list")?.forEachObject { o ->
            val t = o.long("timestamp") ?: return@forEachObject
            val v = o.num("openInterest")
            if (v.isFinite()) out += Sample(t, v)
        }
        return out.sortedBy { it.time }
    }

    fun fundingHistory(json: String): List<FundingEvent> {
        val out = ArrayList<FundingEvent>()
        result(json).optJSONArray("list")?.forEachObject { o ->
            val t = o.long("fundingRateTimestamp") ?: return@forEachObject
            val r = o.num("fundingRate")
            if (r.isFinite()) out += FundingEvent(t, r)
        }
        return out.sortedBy { it.time }
    }

    /** `result.b` (bids) and `result.a` (asks), best first, as `[price, size in BTC]`. */
    fun orderBook(json: String, time: Long): BookSnapshot {
        val r = result(json)
        val (bidPrices, bidSizes) = bookLevels(r.optJSONArray("b"))
        val (askPrices, askSizes) = bookLevels(r.optJSONArray("a"))
        return BookSnapshot.fromLevels(time, bidPrices, bidSizes, askPrices, askSizes) ?: throw ApiException("Bybit: empty order book")
    }

    fun ticker(json: String): LiveSnapshot {
        val root = JSONObject(json)
        val list = result(json).optJSONArray("list")
        val o = list?.optJSONObject(0) ?: throw ApiException("Bybit: no ticker")
        val oi = o.num("openInterest").takeIf { it.isFinite() }
        val rate = o.num("fundingRate")
        val funding = if (rate.isFinite()) LiveFunding(rate, o.long("nextFundingTime")?.takeIf { it > 0 }) else null
        return LiveSnapshot(root.long("time") ?: System.currentTimeMillis(), oi, funding)
    }
}
