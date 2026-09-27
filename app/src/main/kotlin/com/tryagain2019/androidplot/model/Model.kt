package com.tryagain2019.androidplot.model

/** OHLC bar; [time] is the bar's open time in epoch milliseconds (UTC). [volume] is in BTC (0 when unknown). */
data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0,
)

/** A point-in-time value, e.g. an open-interest snapshot in BTC. */
data class Sample(val time: Long, val value: Double)

/** A settled funding payment: [rate] is the fraction paid for the interval ending at [time]. */
data class FundingEvent(val time: Long, val rate: Double)

/** The rate currently accruing (predicted for the next settlement at [nextFundingTime]). */
data class LiveFunding(val rate: Double, val nextFundingTime: Long?)

/** What one poll of an exchange's current state returned; null fields were unavailable. */
data class LiveSnapshot(val time: Long, val openInterest: Double?, val funding: LiveFunding?)

enum class Exchange(
    val key: String,
    val displayName: String,
    /** False when the exchange has no public open-interest history (it is then only recorded live). */
    val hasOiHistory: Boolean,
    val fundingIntervalHours: Int,
) {
    BINANCE("binance", "Binance", true, 8),
    BYBIT("bybit", "Bybit", true, 8),
    OKX("okx", "OKX", true, 8),
    HYPERLIQUID("hyperliquid", "Hyperliquid", false, 1),
    ;

    companion object {
        fun of(key: String): Exchange? = entries.firstOrNull { it.key == key }
    }
}
