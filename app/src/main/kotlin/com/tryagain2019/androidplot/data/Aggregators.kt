package com.tryagain2019.androidplot.data

import com.tryagain2019.androidplot.model.Candle
import com.tryagain2019.androidplot.model.DAY
import com.tryagain2019.androidplot.model.HOUR
import com.tryagain2019.androidplot.model.LiveFunding
import com.tryagain2019.androidplot.model.MINUTE
import com.tryagain2019.androidplot.model.Sample
import com.tryagain2019.androidplot.model.Timeframe
import java.util.NavigableMap
import java.util.NavigableSet

/** Builds aggregated open-interest candles from per-exchange snapshots. */
object OiAggregator {
    /** Snapshots further apart than this are not interpolated between. */
    private const val MAX_GAP = 35 * DAY

    /**
     * @param backfill true for exchanges without public history: before their first known snapshot
     *   that snapshot's value is used, instead of leaving the aggregate undefined.
     */
    class Input(val samples: NavigableMap<Long, Double>, val backfill: Boolean)

    /** Value of one exchange at [t]: exact, linearly interpolated between snapshots, or carried forward/back. */
    fun valueAt(samples: NavigableMap<Long, Double>, t: Long, backfill: Boolean): Double? {
        val floor = samples.floorEntry(t)
        if (floor != null && floor.key == t) return floor.value
        val ceil = samples.ceilingEntry(t)
        return when {
            floor != null && ceil != null -> {
                val span = ceil.key - floor.key
                if (span > MAX_GAP) null else floor.value + (ceil.value - floor.value) * (t - floor.key).toDouble() / span
            }
            floor != null -> floor.value
            ceil != null -> if (backfill) ceil.value else null
            else -> null
        }
    }

    /** Sum over all inputs at [t], or null when any exchange with real history has no data there. */
    fun aggregateAt(inputs: List<Input>, t: Long): Double? {
        var sum = 0.0
        for (input in inputs) sum += valueAt(input.samples, t, input.backfill) ?: return null
        return sum
    }

    /**
     * Candles for every bar from the one containing [from] to the one containing [now]. Each bar is
     * evaluated at its open, at every [gridMs] step inside it, at live polling times inside it, and
     * at its end (the next bar's open, or [now] for the bar in progress).
     */
    fun build(
        tf: Timeframe,
        gridMs: Long,
        from: Long,
        now: Long,
        inputs: List<Input>,
        liveTimes: NavigableSet<Long>,
    ): List<Candle> {
        if (inputs.isEmpty()) return emptyList()
        val out = ArrayList<Candle>()
        var bar = tf.barStart(from)
        val lastBar = tf.barStart(now)
        while (bar <= lastBar) {
            val next = tf.nextBarStart(bar)
            val end = minOf(next, now)
            val open = aggregateAt(inputs, bar)
            if (open != null) {
                var high: Double = open
                var low: Double = open
                var g = bar - Math.floorMod(bar, gridMs) + gridMs
                while (g < end) {
                    aggregateAt(inputs, g)?.let {
                        if (it > high) high = it
                        if (it < low) low = it
                    }
                    g += gridMs
                }
                for (t in liveTimes.subSet(bar, false, end, false)) {
                    aggregateAt(inputs, t)?.let {
                        if (it > high) high = it
                        if (it < low) low = it
                    }
                }
                val close = aggregateAt(inputs, end) ?: open
                if (close > high) high = close
                if (close < low) low = close
                out += Candle(bar, open, high, low, close)
            }
            bar = next
        }
        return out
    }
}

/** Turns settled funding events into per-bar values in percent per 8 hours. */
object FundingAggregator {
    private const val EIGHT_HOURS = 8 * HOUR

    private class Period(val start: Long, val end: Long, val percentPer8h: Double)

    /**
     * Settlement i covers the interval since settlement i-1; its rate is scaled to 8 hours (so
     * Hyperliquid's hourly rate is multiplied by 8). The interval after the last settlement uses
     * [live]. Each bar gets the time-weighted average over the part of it that has data.
     */
    fun build(
        tf: Timeframe,
        from: Long,
        now: Long,
        events: NavigableMap<Long, Double>,
        live: LiveFunding?,
        defaultIntervalMs: Long,
    ): List<Sample> {
        val periods = periods(events, live, now, defaultIntervalMs)
        if (periods.isEmpty()) return emptyList()
        val out = ArrayList<Sample>()
        var bar = tf.barStart(maxOf(from, periods.first().start))
        val lastBar = tf.barStart(now)
        var p = 0
        while (bar <= lastBar) {
            val end = minOf(tf.nextBarStart(bar), now)
            while (p < periods.size && periods[p].end <= bar) p++
            var weighted = 0.0
            var covered = 0L
            var i = p
            while (i < periods.size && periods[i].start < end) {
                val overlap = minOf(end, periods[i].end) - maxOf(bar, periods[i].start)
                if (overlap > 0) {
                    weighted += periods[i].percentPer8h * overlap
                    covered += overlap
                }
                i++
            }
            if (covered > 0) out += Sample(bar, weighted / covered)
            bar = tf.nextBarStart(bar)
        }
        return out
    }

    private fun periods(events: NavigableMap<Long, Double>, live: LiveFunding?, now: Long, defaultIntervalMs: Long): List<Period> {
        val out = ArrayList<Period>(events.size + 1)
        var prev: Long? = null
        var lastInterval = defaultIntervalMs
        for ((time, rate) in events) {
            var start = prev ?: (time - defaultIntervalMs)
            // A hole in the data (or an unusually long gap) is not one long funding period.
            if (time - start > 3 * maxOf(defaultIntervalMs, lastInterval)) start = time - lastInterval
            val interval = maxOf(time - start, MINUTE)
            out += Period(start, time, rate * EIGHT_HOURS / interval * 100.0)
            lastInterval = interval
            prev = time
        }
        if (live != null) {
            val last = prev
            val next = live.nextFundingTime?.takeIf { last == null || it > last }
            // If settlements since `last` have not been fetched yet, next - last spans several
            // intervals; the rate still has to be scaled by one interval.
            val interval = when {
                next != null && last != null && next - last <= lastInterval * 3 / 2 -> next - last
                else -> lastInterval
            }
            val start = last ?: ((next ?: now) - interval)
            val end = maxOf(next ?: (start + interval), now)
            if (end > start) out += Period(start, end, live.rate * EIGHT_HOURS / interval * 100.0)
        }
        return out
    }
}
