package com.tryagain2019.androidplot.model

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

const val SECOND = 1_000L
const val MINUTE = 60 * SECOND
const val HOUR = 60 * MINUTE
const val DAY = 24 * HOUR
const val WEEK = 7 * DAY

/** Spacing of the open-interest snapshots requested from the exchanges, with each API's name for it. */
enum class OiResolution(val ms: Long, val binance: String, val bybit: String, val okx: String) {
    M5(5 * MINUTE, "5m", "5min", "5m"),
    M15(15 * MINUTE, "15m", "15min", "15m"),
    M30(30 * MINUTE, "30m", "30min", "30m"),
    H1(HOUR, "1h", "1h", "1H"),
    H4(4 * HOUR, "4h", "4h", "4H"),
    D1(DAY, "1d", "1d", "1Dutc"),
    ;

    /** Next coarser resolution, used when an exchange's history at this one does not reach back far enough. */
    fun coarser(): OiResolution? = when (this) {
        M5 -> M15
        M15 -> H1
        M30 -> H1
        H1 -> H4
        H4 -> D1
        D1 -> null
    }
}

enum class Timeframe(
    /** Code shared with the chart page. */
    val code: String,
    val label: String,
    val binanceInterval: String,
    /** Bar length; for 1M the real length follows the calendar. */
    val nominalMs: Long,
    /** Snapshot spacing used to build open-interest bars (Binance, Bybit). */
    val oiResolution: OiResolution,
    /** OKX's history endpoint is rate limited harder, so it is asked for coarser snapshots. */
    val okxOiResolution: OiResolution,
    /** Bars of open interest / funding loaded up front (more are loaded when scrolling back). */
    val historyBars: Int,
    /** Binance's OI archive has one file per day; long bars only need one file every few days (0 = monthly). */
    val archiveStepDays: Int,
    /** Bars shown when the timeframe is opened. */
    val visibleBars: Int,
) {
    M1("1m", "1m", "1m", MINUTE, OiResolution.M5, OiResolution.M5, 360, 1, 120),
    M5("5m", "5m", "5m", 5 * MINUTE, OiResolution.M5, OiResolution.M5, 300, 1, 120),
    M15("15m", "15m", "15m", 15 * MINUTE, OiResolution.M5, OiResolution.M15, 300, 1, 120),
    M30("30m", "30m", "30m", 30 * MINUTE, OiResolution.M15, OiResolution.M30, 300, 1, 120),
    H1("1h", "1h", "1h", HOUR, OiResolution.M15, OiResolution.H1, 300, 1, 120),
    H4("4h", "4h", "4h", 4 * HOUR, OiResolution.H1, OiResolution.H4, 300, 1, 120),
    D1("1d", "1D", "1d", DAY, OiResolution.H4, OiResolution.D1, 250, 1, 180),
    W1("1w", "1W", "1w", WEEK, OiResolution.D1, OiResolution.D1, 110, 7, 104),
    MN1("1M", "1M", "1M", 30 * DAY, OiResolution.D1, OiResolution.D1, 48, 0, 48),
    ;

    val intraday: Boolean get() = nominalMs < DAY

    /** Open time of the bar containing [t]. Weeks start on Monday 00:00 UTC and months on the 1st, like Binance. */
    fun barStart(t: Long): Long = when (this) {
        W1 -> t - Math.floorMod(t - MONDAY_EPOCH, WEEK)
        MN1 -> utc(t).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        else -> t - Math.floorMod(t, nominalMs)
    }

    /** Open time of the bar after the one opening at [start]. */
    fun nextBarStart(start: Long): Long = shift(start, 1)

    /** Open time [bars] bars after (or before, if negative) the bar opening at [start]. */
    fun shift(start: Long, bars: Int): Long = when (this) {
        MN1 -> utc(start).plusMonths(bars.toLong()).toInstant().toEpochMilli()
        else -> start + bars * nominalMs
    }

    companion object {
        /** 1970-01-05, the first Monday after the epoch. */
        private const val MONDAY_EPOCH = 4 * DAY

        fun of(code: String?): Timeframe? = entries.firstOrNull { it.code == code }

        private fun utc(t: Long): ZonedDateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneOffset.UTC)
    }
}
