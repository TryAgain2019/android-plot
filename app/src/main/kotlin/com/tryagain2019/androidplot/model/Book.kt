package com.tryagain2019.androidplot.model

import kotlin.math.floor

/** An order book the heatmap can record; the heatmap sums the ones switched on. */
enum class BookMarket(val key: String, val displayName: String, val levels: Int) {
    /** The book Material Indicators' FireCharts shows (and the one people mean by "Binance sell walls"). */
    SPOT("spot", "Binance spot BTCUSDT", 5000),
    FUTURES("futures", "Binance-Futures BTCUSDT", 1000),
    BYBIT("bybit", "Bybit BTCUSDT", 500),
    OKX("okx", "OKX BTC-USDT-SWAP", 5000),

    /** Hyperliquid serves 20 levels a side, so the book is fetched grouped by $10 and by $100. */
    HYPERLIQUID("hyperliquid", "Hyperliquid BTC", 20),
    ;

    companion object {
        fun of(key: String?): BookMarket? = entries.firstOrNull { it.key == key }

        /** The books switched on (setting `book.<key>`, on unless "false"). */
        fun enabled(setting: (String) -> String?): List<BookMarket> = entries.filter { setting("book.${it.key}") != "false" }
    }
}

/**
 * One order book snapshot, summed into price bins of [BIN] dollars: bin k holds the BTC resting in
 * [k * BIN, (k + 1) * BIN). Levels further than [MAX_DISTANCE] from the mid price are left out.
 */
class BookSnapshot(
    val time: Long,
    val mid: Double,
    /** Bin of the best bid; bids[j] is the quantity in bin bidTop - j. */
    val bidTop: Int,
    val bids: FloatArray,
    /** Bin of the best ask; asks[j] is the quantity in bin askBottom + j. */
    val askBottom: Int,
    val asks: FloatArray,
) {
    /** Lowest price the snapshot covers. */
    val low: Double get() = (bidTop - bids.size + 1) * BIN

    /** Highest price the snapshot covers. */
    val high: Double get() = (askBottom + asks.size) * BIN

    companion object {
        const val BIN = 10.0
        const val MAX_DISTANCE = 0.10

        fun bin(price: Double): Int = floor(price / BIN).toInt()

        /** The sum of several books (every exchange's at one moment); the mid is the average of theirs. */
        fun combine(time: Long, parts: Collection<BookSnapshot>): BookSnapshot? {
            if (parts.isEmpty()) return null
            val bidTop = parts.maxOf { it.bidTop }
            val bids = FloatArray(bidTop - parts.minOf { it.bidTop - it.bids.size + 1 } + 1)
            for (p in parts) for (j in p.bids.indices) bids[bidTop - p.bidTop + j] += p.bids[j]
            val askBottom = parts.minOf { it.askBottom }
            val asks = FloatArray(parts.maxOf { it.askBottom + it.asks.size - 1 } - askBottom + 1)
            for (p in parts) for (j in p.asks.indices) asks[p.askBottom - askBottom + j] += p.asks[j]
            return BookSnapshot(time, parts.sumOf { it.mid } / parts.size, bidTop, bids, askBottom, asks)
        }

        /**
         * Bins raw levels as the exchange sends them ([prices] and [quantities] side by side, best
         * price first on both sides). Returns null when either side is empty or crossed.
         */
        fun fromLevels(
            time: Long,
            bidPrices: DoubleArray,
            bidQuantities: DoubleArray,
            askPrices: DoubleArray,
            askQuantities: DoubleArray,
        ): BookSnapshot? {
            val bestBid = bidPrices.maxOrNull() ?: return null
            val bestAsk = askPrices.minOrNull() ?: return null
            if (!(bestBid > 0 && bestAsk >= bestBid)) return null
            val mid = (bestBid + bestAsk) / 2
            val minPrice = mid * (1 - MAX_DISTANCE)
            val maxPrice = mid * (1 + MAX_DISTANCE)

            val bidTop = bin(bestBid)
            var bidBottom = bidTop
            for (i in bidPrices.indices) {
                val p = bidPrices[i]
                if (p >= minPrice && bidQuantities[i] > 0) bidBottom = minOf(bidBottom, bin(p))
            }
            val bids = FloatArray(bidTop - bidBottom + 1)
            for (i in bidPrices.indices) {
                val p = bidPrices[i]
                val q = bidQuantities[i]
                if (p < minPrice || p > bestBid || !(q > 0)) continue
                bids[bidTop - bin(p)] += q.toFloat()
            }

            val askBottom = bin(bestAsk)
            var askTop = askBottom
            for (i in askPrices.indices) {
                val p = askPrices[i]
                if (p <= maxPrice && askQuantities[i] > 0) askTop = maxOf(askTop, bin(p))
            }
            val asks = FloatArray(askTop - askBottom + 1)
            for (i in askPrices.indices) {
                val p = askPrices[i]
                val q = askQuantities[i]
                if (p > maxPrice || p < bestAsk || !(q > 0)) continue
                asks[bin(p) - askBottom] += q.toFloat()
            }
            return BookSnapshot(time, mid, bidTop, bids, askBottom, asks)
        }
    }
}
