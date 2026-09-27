package com.tryagain2019.androidplot.net

import com.tryagain2019.androidplot.model.BookMarket
import com.tryagain2019.androidplot.model.BookSnapshot
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Binance's public order book snapshots (REST depth), the raw data behind order-book heatmaps such
 * as Material Indicators' FireCharts. Binance only serves the current book, so history is whatever
 * the app records.
 */
class BookApi(
    private val client: OkHttpClient,
    /** Spot hosts, tried in turn (data-api.binance.vision serves the same public market data). */
    private val spotBases: List<String> = listOf("https://api.binance.com", "https://data-api.binance.vision"),
    private val futuresBase: String = "https://fapi.binance.com",
    val symbol: String = "BTCUSDT",
) {
    @Volatile private var spotBase = 0

    /** The current book of [market] (spot: 5000 levels per side, weight 250; futures: 1000, weight 20). */
    suspend fun snapshot(market: BookMarket, time: () -> Long): BookSnapshot = when (market) {
        BookMarket.SPOT -> {
            var last: Exception? = null
            var result: BookSnapshot? = null
            for (attempt in spotBases.indices) {
                val i = (spotBase + attempt) % spotBases.size
                try {
                    result = client.get("${spotBases[i]}/api/v3/depth?symbol=$symbol&limit=${market.levels}") { BookParsers.depth(it, time()) }
                    spotBase = i
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                    // Only a host that refuses us (or cannot be reached) is worth trying the other one for.
                    if (e is HttpException && e.code != 451 && e.code != 403) throw e
                }
            }
            result ?: throw last!!
        }
        BookMarket.FUTURES -> client.get("$futuresBase/fapi/v1/depth?symbol=$symbol&limit=${market.levels}") { BookParsers.depth(it, time()) }
        else -> throw IllegalArgumentException("not a Binance book: $market")
    }
}

object BookParsers {
    /** Spot `/api/v3/depth` and futures `/fapi/v1/depth` share this shape: `{"bids":[["price","qty"],...],"asks":[...]}`. */
    fun depth(json: String, time: Long): BookSnapshot {
        val o = JSONObject(json)
        if (!o.has("bids") || !o.has("asks")) throw ApiException(errorDetail(json).ifEmpty { "no order book in response" })
        val (bidPrices, bidQuantities) = bookLevels(o.getJSONArray("bids"))
        val (askPrices, askQuantities) = bookLevels(o.getJSONArray("asks"))
        return BookSnapshot.fromLevels(time, bidPrices, bidQuantities, askPrices, askQuantities)
            ?: throw ApiException("empty order book")
    }
}

/** Price levels sent as `[[price, quantity, ...], ...]`; quantities are multiplied by [scale] (e.g. contracts to BTC). */
internal fun bookLevels(arr: JSONArray?, scale: Double = 1.0): Pair<DoubleArray, DoubleArray> {
    val count = arr?.length() ?: 0
    val prices = DoubleArray(count)
    val quantities = DoubleArray(count)
    var n = 0
    for (i in 0 until count) {
        val level = arr!!.optJSONArray(i) ?: continue
        val p = level.num(0)
        val q = level.num(1) * scale
        if (p.isFinite() && q.isFinite()) {
            prices[n] = p
            quantities[n] = q
            n++
        }
    }
    return prices.copyOf(n) to quantities.copyOf(n)
}

/** Current order books of every exchange the heatmap can include. */
class OrderBooks(
    private val binance: BookApi,
    private val bybit: BybitApi,
    private val okx: OkxApi,
    private val hyperliquid: HyperliquidApi,
) {
    suspend fun snapshot(market: BookMarket, time: () -> Long): BookSnapshot = when (market) {
        BookMarket.SPOT, BookMarket.FUTURES -> binance.snapshot(market, time)
        BookMarket.BYBIT -> bybit.orderBook(market.levels, time)
        BookMarket.OKX -> okx.orderBook(market.levels, time)
        BookMarket.HYPERLIQUID -> hyperliquid.orderBook(time)
    }

    companion object {
        fun production(client: OkHttpClient) = OrderBooks(BookApi(client), BybitApi(client), OkxApi(client), HyperliquidApi(client))
    }
}
