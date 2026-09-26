package com.tryagain2019.androidplot

import com.tryagain2019.androidplot.data.FundingAggregator
import com.tryagain2019.androidplot.data.OiAggregator
import com.tryagain2019.androidplot.model.DAY
import com.tryagain2019.androidplot.model.HOUR
import com.tryagain2019.androidplot.model.LiveFunding
import com.tryagain2019.androidplot.model.MINUTE
import com.tryagain2019.androidplot.model.Timeframe
import java.time.Instant
import java.util.TreeMap
import java.util.TreeSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AggregatorsTest {
    private fun t(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun series(vararg points: Pair<String, Double>) = TreeMap<Long, Double>().apply { for ((k, v) in points) put(t(k), v) }
    private fun near(expected: Double, actual: Double, eps: Double = 1e-9) = assertTrue(Math.abs(expected - actual) < eps, "expected $expected, got $actual")

    @Test
    fun valueAtInterpolatesAndCarries() {
        val s = series("2026-09-01T00:00:00Z" to 100.0, "2026-09-01T04:00:00Z" to 200.0)
        near(150.0, OiAggregator.valueAt(s, t("2026-09-01T02:00:00Z"), false)!!)
        near(200.0, OiAggregator.valueAt(s, t("2026-09-02T00:00:00Z"), false)!!) // carried forward
        assertNull(OiAggregator.valueAt(s, t("2026-08-31T00:00:00Z"), false)) // no history there
        near(100.0, OiAggregator.valueAt(s, t("2026-08-31T00:00:00Z"), true)!!) // backfilled
    }

    @Test
    fun dailyCandlesFromFourHourSnapshots() {
        val a = TreeMap<Long, Double>()
        val b = TreeMap<Long, Double>()
        // Two exchanges, 4h snapshots over 3 days; exchange a spikes mid-day on day 2.
        var time = t("2026-09-01T00:00:00Z")
        var i = 0
        while (time <= t("2026-09-04T00:00:00Z")) {
            a[time] = 100.0 + i + if (time == t("2026-09-02T12:00:00Z")) 50.0 else 0.0
            b[time] = 60.0
            time += 4 * HOUR
            i++
        }
        val inputs = listOf(OiAggregator.Input(a, false), OiAggregator.Input(b, false))
        val bars = OiAggregator.build(Timeframe.D1, 4 * HOUR, t("2026-09-01T00:00:00Z"), t("2026-09-03T12:00:00Z"), inputs, TreeSet())
        assertEquals(3, bars.size)
        val day2 = bars[1]
        assertEquals(t("2026-09-02T00:00:00Z"), day2.time)
        near(160.0 + 6, day2.open)
        near(160.0 + 12, day2.close) // value at the next day's open
        near(160.0 + 9 + 50, day2.high) // the spike inside the bar
        near(166.0, day2.low)
        near(bars[0].close, day2.open) // continuous
        // The bar in progress ends at "now": 12:00 on day 3.
        near(160.0 + 15, bars[2].close)
    }

    @Test
    fun missingHistoryDropsBarsButBackfillDoesNot() {
        val withHistory = series("2026-09-02T00:00:00Z" to 100.0, "2026-09-03T00:00:00Z" to 110.0)
        val liveOnly = series("2026-09-03T00:00:00Z" to 30.0)
        val bars = OiAggregator.build(
            Timeframe.D1, 4 * HOUR, t("2026-09-01T00:00:00Z"), t("2026-09-03T06:00:00Z"),
            listOf(OiAggregator.Input(withHistory, false), OiAggregator.Input(liveOnly, true)), TreeSet(),
        )
        // 09-01 has no data for the exchange with history -> no bar; later bars include the backfilled 30.
        assertEquals(listOf(t("2026-09-02T00:00:00Z"), t("2026-09-03T00:00:00Z")), bars.map { it.time })
        near(130.0, bars[0].open)
        near(140.0, bars[0].close)
    }

    @Test
    fun liveRoundsGiveTheCurrentBarItsRange() {
        val s = series("2026-09-03T00:00:00Z" to 100.0, "2026-09-03T00:01:30Z" to 120.0, "2026-09-03T00:02:40Z" to 90.0, "2026-09-03T00:04:10Z" to 101.0)
        val live = TreeSet(listOf(t("2026-09-03T00:01:30Z"), t("2026-09-03T00:02:40Z"), t("2026-09-03T00:04:10Z")))
        val bars = OiAggregator.build(Timeframe.M5, 5 * MINUTE, t("2026-09-03T00:00:00Z"), t("2026-09-03T00:04:10Z"), listOf(OiAggregator.Input(s, false)), live)
        val bar = bars.single()
        near(100.0, bar.open)
        near(120.0, bar.high)
        near(90.0, bar.low)
        near(101.0, bar.close)
    }

    @Test
    fun monthlyAndWeeklyBars() {
        val s = TreeMap<Long, Double>()
        var d = t("2026-01-01T00:00:00Z")
        while (d <= t("2026-04-15T00:00:00Z")) {
            s[d] = 1000.0 + (d - t("2026-01-01T00:00:00Z")) / DAY
            d += DAY
        }
        val input = listOf(OiAggregator.Input(s, false))
        val months = OiAggregator.build(Timeframe.MN1, DAY, t("2026-01-01T00:00:00Z"), t("2026-04-15T00:00:00Z"), input, TreeSet())
        assertEquals(4, months.size)
        near(1000.0 + 31, months[0].close) // Feb 1st
        near(1000.0 + 31, months[1].open)
        val weeks = OiAggregator.build(Timeframe.W1, DAY, t("2026-03-01T00:00:00Z"), t("2026-03-20T00:00:00Z"), input, TreeSet())
        assertEquals(t("2026-02-23T00:00:00Z"), weeks.first().time) // the Monday before
        assertEquals(t("2026-03-16T00:00:00Z"), weeks.last().time)
    }

    @Test
    fun fundingBarsShowTheRateAtTheirClose() {
        val events = series(
            "2026-09-01T08:00:00Z" to 0.0001,
            "2026-09-01T16:00:00Z" to 0.0002,
            "2026-09-02T00:00:00Z" to 0.0003,
            "2026-09-02T08:00:00Z" to -0.0001,
        )
        val points = FundingAggregator.build(Timeframe.D1, t("2026-09-01T00:00:00Z"), t("2026-09-02T08:00:00Z"), events, null, 8 * HOUR)
        assertEquals(2, points.size)
        near(0.03, points[0].value) // the period accruing at the end of 09-01 (16:00-24:00)
        near(-0.01, points[1].value)
    }

    @Test
    fun latestFundingIsTheSameOnEveryTimeframe() {
        // Settled rates vary through the day; the live predicted rate is 0.00004 (0.004 %/8h).
        val events = series(
            "2026-09-01T00:00:00Z" to 0.0002,
            "2026-09-01T08:00:00Z" to 0.0001,
        )
        val live = LiveFunding(0.00004, t("2026-09-01T16:00:00Z"))
        val now = t("2026-09-01T15:53:00Z")
        for (tf in Timeframe.entries) {
            val points = FundingAggregator.build(tf, t("2026-08-01T00:00:00Z"), now, events, live, 8 * HOUR)
            near(0.004, points.last().value)
            assertEquals(tf.barStart(now), points.last().time, tf.name)
        }
    }

    @Test
    fun beforeTheFirstLivePollTheLastSettledRateCarriesOn() {
        val events = series("2026-09-01T00:00:00Z" to 0.0001, "2026-09-01T08:00:00Z" to 0.0002)
        val points = FundingAggregator.build(Timeframe.H1, t("2026-09-01T06:00:00Z"), t("2026-09-01T09:30:00Z"), events, null, 8 * HOUR)
        near(0.02, points.last().value)
        assertEquals(t("2026-09-01T09:00:00Z"), points.last().time)
    }

    @Test
    fun hourlyFundingIsScaledToEightHours() {
        val events = TreeMap<Long, Double>()
        var h = t("2026-09-01T01:00:00Z")
        while (h <= t("2026-09-02T00:00:00Z")) {
            events[h + 76] = 0.0000125 // Hyperliquid stamps settlements a few ms late
            h += HOUR
        }
        val rounded = TreeMap<Long, Double>().apply { for ((k, v) in events) put(Math.round(k.toDouble() / MINUTE) * MINUTE, v) }
        val daily = FundingAggregator.build(Timeframe.D1, t("2026-09-01T00:00:00Z"), t("2026-09-02T00:00:00Z"), rounded, null, HOUR)
        near(0.01, daily.first().value) // 0.00125 %/h * 8
    }

    @Test
    fun intradayBarsTakeTheRateOfTheirPeriodAndLiveRateForTheCurrentOne() {
        val events = series("2026-09-01T00:00:00Z" to 0.0001, "2026-09-01T08:00:00Z" to 0.0002)
        val live = LiveFunding(0.00005, t("2026-09-01T16:00:00Z"))
        val points = FundingAggregator.build(Timeframe.H4, t("2026-09-01T00:00:00Z"), t("2026-09-01T10:00:00Z"), events, live, 8 * HOUR)
        assertEquals(listOf(t("2026-09-01T00:00:00Z"), t("2026-09-01T04:00:00Z"), t("2026-09-01T08:00:00Z")), points.map { it.time })
        near(0.02, points[0].value) // accrued 00:00-08:00, settled at 08:00
        near(0.02, points[1].value)
        near(0.005, points[2].value) // live
    }

    @Test
    fun staleHistoryDoesNotStretchTheLiveInterval() {
        // Last fetched settlement 00:00, but it is already 10:00 and the next one is 16:00.
        val events = series("2026-08-31T16:00:00Z" to 0.0001, "2026-09-01T00:00:00Z" to 0.0001)
        val live = LiveFunding(0.0001, t("2026-09-01T16:00:00Z"))
        val points = FundingAggregator.build(Timeframe.H1, t("2026-09-01T09:00:00Z"), t("2026-09-01T10:00:00Z"), events, live, 8 * HOUR)
        near(0.01, points.last().value)
    }
}
