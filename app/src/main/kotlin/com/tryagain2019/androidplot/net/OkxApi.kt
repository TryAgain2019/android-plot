package com.tryagain2019.androidplot.net

import com.tryagain2019.androidplot.model.BookSnapshot
import com.tryagain2019.androidplot.model.FundingEvent
import com.tryagain2019.androidplot.model.LiveFunding
import com.tryagain2019.androidplot.model.Sample
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** OKX v5 public REST API, BTC-USDT-SWAP perpetual. */
class OkxApi(
    private val client: OkHttpClient,
    private val base: String = "https://www.okx.com",
    val instId: String = "BTC-USDT-SWAP",
) {
    /**
     * Open-interest snapshots in BTC (`oiCcy`) between [begin] and [end]; at most 100 per call.
     * OKX keeps a limited number of snapshots per period and rate limits this endpoint to 5 calls per 2 s.
     */
    suspend fun openInterestHistory(period: String, begin: Long, end: Long, limit: Int = 100): List<Sample> {
        val url = "$base/api/v5/rubik/stat/contracts/open-interest-history".toHttpUrl().newBuilder()
            .addQueryParameter("instId", instId)
            .addQueryParameter("period", period)
            .addQueryParameter("begin", begin.toString())
            .addQueryParameter("end", end.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        return client.get(url.toString(), OkxParsers::openInterestHistory)
    }

    /** Current open interest in BTC. */
    suspend fun openInterest(): Sample =
        client.get("$base/api/v5/public/open-interest?instType=SWAP&instId=$instId", OkxParsers::openInterest)

    /** The funding rate accruing now, settled at the returned next funding time. */
    suspend fun fundingRate(): LiveFunding =
        client.get("$base/api/v5/public/funding-rate?instId=$instId", OkxParsers::fundingRate)

    /** The current order book, [size] levels a side (at most 5000); rate limited to 5 calls per 2 s. */
    suspend fun orderBook(size: Int, time: () -> Long): BookSnapshot =
        client.get("$base/api/v5/market/books-full?instId=$instId&sz=$size") { OkxParsers.orderBook(it, time(), OkxParsers.CONTRACT_BTC) }

    /** Up to 100 settled funding rates older than [after] (newest first when [after] is null). OKX keeps ~3 months. */
    suspend fun fundingRateHistory(after: Long?, limit: Int = 100): List<FundingEvent> {
        val url = "$base/api/v5/public/funding-rate-history".toHttpUrl().newBuilder()
            .addQueryParameter("instId", instId)
            .addQueryParameter("limit", limit.toString())
            .apply { if (after != null) addQueryParameter("after", after.toString()) }
            .build()
        return client.get(url.toString(), OkxParsers::fundingRateHistory)
    }
}

object OkxParsers {
    private fun data(json: String): JSONArray {
        val root = JSONObject(json)
        val code = root.optString("code", "0")
        if (code != "0") throw ApiException("OKX: ${root.optString("msg").ifEmpty { "error $code" }} ($code)")
        return root.optJSONArray("data") ?: JSONArray()
    }

    /** Rows are `[ts, oi (contracts), oiCcy (coin), oiUsd]`. */
    fun openInterestHistory(json: String): List<Sample> {
        val out = ArrayList<Sample>()
        data(json).forEachArray { row ->
            val t = row.long(0) ?: return@forEachArray
            var v = row.num(2)
            if (!v.isFinite()) v = row.num(1) * CONTRACT_BTC
            if (v.isFinite()) out += Sample(t, v)
        }
        return out.sortedBy { it.time }
    }

    fun openInterest(json: String): Sample {
        val o = data(json).optJSONObject(0) ?: throw ApiException("OKX: no open interest")
        var v = o.num("oiCcy")
        if (!v.isFinite()) v = o.num("oi") * CONTRACT_BTC
        if (!v.isFinite()) throw ApiException("OKX: no open interest")
        return Sample(o.long("ts") ?: System.currentTimeMillis(), v)
    }

    /** `data[0].bids/asks`, best first, as `[price, size, orders]`; swap sizes are contracts of [contractBtc]. */
    fun orderBook(json: String, time: Long, contractBtc: Double): BookSnapshot {
        val o = data(json).optJSONObject(0) ?: throw ApiException("OKX: no order book")
        val (bidPrices, bidSizes) = bookLevels(o.optJSONArray("bids"), contractBtc)
        val (askPrices, askSizes) = bookLevels(o.optJSONArray("asks"), contractBtc)
        return BookSnapshot.fromLevels(time, bidPrices, bidSizes, askPrices, askSizes) ?: throw ApiException("OKX: empty order book")
    }

    fun fundingRate(json: String): LiveFunding {
        val o = data(json).optJSONObject(0) ?: throw ApiException("OKX: no funding rate")
        val r = o.num("fundingRate")
        if (!r.isFinite()) throw ApiException("OKX: no funding rate")
        return LiveFunding(r, o.long("fundingTime")?.takeIf { it > 0 })
    }

    fun fundingRateHistory(json: String): List<FundingEvent> {
        val out = ArrayList<FundingEvent>()
        data(json).forEachObject { o ->
            val t = o.long("fundingTime") ?: return@forEachObject
            var r = o.num("realizedRate")
            if (!r.isFinite()) r = o.num("fundingRate")
            if (r.isFinite()) out += FundingEvent(t, r)
        }
        return out.sortedBy { it.time }
    }

    /** BTC-USDT-SWAP contract value (order book sizes, and OI when a response lacks the coin field). */
    const val CONTRACT_BTC = 0.01
}
