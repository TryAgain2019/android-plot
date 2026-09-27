package com.tryagain2019.androidplot.net

import com.tryagain2019.androidplot.model.BookSnapshot
import com.tryagain2019.androidplot.model.FundingEvent
import com.tryagain2019.androidplot.model.HOUR
import com.tryagain2019.androidplot.model.LiveFunding
import com.tryagain2019.androidplot.model.LiveSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

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

    /**
     * The order book. `l2Book` returns 20 levels a side, so it is asked for twice: grouped to 4
     * significant figures ($10 at BTC's price) for detail and to 3 ($100) for reach.
     */
    suspend fun orderBook(time: () -> Long): BookSnapshot = coroutineScope {
        val fine = async { client.post("$base/info", """{"type":"l2Book","coin":"$coin","nSigFigs":4}""", HyperliquidParsers::l2Book) }
        val coarse = async { client.post("$base/info", """{"type":"l2Book","coin":"$coin","nSigFigs":3}""", HyperliquidParsers::l2Book) }
        HyperliquidParsers.snapshot(time(), fine.await(), 4, coarse.await(), 3) ?: throw ApiException("Hyperliquid: empty order book")
    }
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

    class Level(val price: Double, val size: Double)

    /** Bids and asks of an `l2Book` response (`levels: [bids, asks]` of `{px, sz, n}`, best first, sizes in BTC). */
    class Book(val bids: List<Level>, val asks: List<Level>)

    fun l2Book(json: String): Book {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) throw ApiException("Hyperliquid: ${errorDetail(trimmed).ifEmpty { "unexpected response" }}")
        val levels = JSONObject(trimmed).optJSONArray("levels") ?: throw ApiException("Hyperliquid: no order book")
        fun side(i: Int) = ArrayList<Level>().also { out ->
            levels.optJSONArray(i)?.forEachObject { o ->
                val p = o.num("px")
                val sz = o.num("sz")
                if (p.isFinite() && sz.isFinite() && sz > 0) out += Level(p, sz)
            }
        }
        return Book(side(0), side(1))
    }

    /** Price step of a book grouped to [sigFigs] significant figures around [price]. */
    fun step(price: Double, sigFigs: Int): Double = 10.0.pow(floor(log10(price)) - (sigFigs - 1))

    /**
     * One snapshot from the book grouped two ways: [fine] near the price, and [coarse] reaching
     * further. Grouping rounds bids down and asks up, so a bid level at p holds the orders in
     * [p, p + step) and an ask level those in (p - step, p]. Of each coarse level, the part the fine
     * levels do not already hold is spread evenly over its $10 bins outside the fine levels' range.
     */
    fun snapshot(time: Long, fine: Book, fineSigFigs: Int, coarse: Book, coarseSigFigs: Int): BookSnapshot? {
        val reference = fine.bids.firstOrNull()?.price ?: fine.asks.firstOrNull()?.price ?: return null
        val fineStep = step(reference, fineSigFigs)
        val coarseStep = step(reference, coarseSigFigs)
        val bin = BookSnapshot.BIN
        val subBins = maxOf(1, Math.round(coarseStep / bin).toInt())
        val bidPrices = ArrayList<Double>()
        val bidSizes = ArrayList<Double>()
        val askPrices = ArrayList<Double>()
        val askSizes = ArrayList<Double>()
        for (l in fine.bids) {
            bidPrices += l.price + fineStep / 2
            bidSizes += l.size
        }
        for (l in fine.asks) {
            askPrices += l.price - fineStep / 2
            askSizes += l.size
        }
        val fineLow = fine.bids.minOfOrNull { it.price } ?: Double.POSITIVE_INFINITY
        val fineHigh = fine.asks.maxOfOrNull { it.price } ?: Double.NEGATIVE_INFINITY
        for (l in coarse.bids) {
            val rest = l.size - fine.bids.filter { it.price >= l.price && it.price < l.price + coarseStep }.sumOf { it.size }
            val outside = (0 until subBins).map { l.price + it * bin }.filter { it + bin <= fineLow }
            if (rest <= 0 || outside.isEmpty()) continue
            for (p in outside) {
                bidPrices += p + bin / 2
                bidSizes += rest / outside.size
            }
        }
        for (l in coarse.asks) {
            val rest = l.size - fine.asks.filter { it.price > l.price - coarseStep && it.price <= l.price }.sumOf { it.size }
            val outside = (1..subBins).map { l.price - it * bin }.filter { it >= fineHigh }
            if (rest <= 0 || outside.isEmpty()) continue
            for (p in outside) {
                askPrices += p + bin / 2
                askSizes += rest / outside.size
            }
        }
        return BookSnapshot.fromLevels(time, bidPrices.toDoubleArray(), bidSizes.toDoubleArray(), askPrices.toDoubleArray(), askSizes.toDoubleArray())
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
