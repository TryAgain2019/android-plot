package com.tryagain2019.androidplot.data

import com.tryagain2019.androidplot.model.BookMarket
import com.tryagain2019.androidplot.model.BookSnapshot
import com.tryagain2019.androidplot.model.DAY
import com.tryagain2019.androidplot.model.MINUTE
import com.tryagain2019.androidplot.model.SECOND
import com.tryagain2019.androidplot.model.Timeframe
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import java.util.TreeSet
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The order book heatmap of one timeframe, built from recorded snapshots: for every bar and price
 * bin, the average quantity resting there while the bar was open. Each snapshot stands for the
 * book until the next one, but for at most [MAX_HOLD], so gaps in the recording stay empty.
 *
 * Finished bars are kept encoded ([encode] format); bars that can still receive snapshots keep
 * running sums. Not thread-safe.
 */
class HeatBuilder(val tf: Timeframe, val from: Long) {
    val binSize: Double = binSize(tf)
    private val factor = (binSize / BookSnapshot.BIN).roundToInt()
    private val maxDistance = maxDistance(tf)
    private val done = TreeMap<Long, ByteArray>()
    private val open = TreeMap<Long, Acc>()
    private var last: BookSnapshot? = null

    val lastSnapshot: BookSnapshot? get() = last

    /** Adds the next snapshot (older or repeated ones are ignored); returns the bars whose heat changed. */
    fun add(s: BookSnapshot): List<Long> {
        val prev = last
        if (prev != null && s.time <= prev.time) return emptyList()
        val changed = TreeSet<Long>()
        if (prev != null) {
            commit(prev, prev.time, minOf(s.time, prev.time + MAX_HOLD), changed)
            // Later snapshots start at s.time or after, so bars ending by then are final.
            val finished = open.headMap(s.time, true).keys.filter { tf.nextBarStart(it) <= s.time }
            for (start in finished) {
                open.remove(start)?.encode(start)?.let { done[start] = it }
            }
        }
        last = s
        val current = tf.barStart(s.time)
        if (current >= from) changed += current
        return changed.toList()
    }

    /** Heat of the bar opening at [start] as of [now], in the [encode] format; null without data. */
    fun encode(start: Long, now: Long): ByteArray? {
        done[start]?.let { return it }
        val end = tf.nextBarStart(start)
        val acc = open[start]
        val l = last
        val openFrom = maxOf(l?.time ?: Long.MAX_VALUE, start)
        val openTo = minOf(end, now, (l?.time ?: 0L) + MAX_HOLD)
        val extra = if (l != null && openTo > openFrom) openTo - openFrom else 0L
        if (extra == 0L) return acc?.encode(start)
        val sum = acc?.copy() ?: Acc()
        sum.add(l!!, extra, factor, maxDistance)
        return sum.encode(start)
    }

    /** All bars with heat, oldest first, concatenated. */
    fun encodeAll(now: Long): ByteArray {
        val starts = TreeSet<Long>(done.keys)
        starts += open.keys
        last?.let { l ->
            var t = tf.barStart(l.time)
            val until = minOf(now, l.time + MAX_HOLD)
            while (t < until) {
                if (t >= from) starts += t
                t = tf.nextBarStart(t)
            }
        }
        return concat(starts.mapNotNull { encode(it, now) })
    }

    /** The given bars as of [now], concatenated. */
    fun encodeBars(starts: Collection<Long>, now: Long): ByteArray = concat(starts.sorted().mapNotNull { encode(it, now) })

    private fun commit(s: BookSnapshot, start: Long, end: Long, changed: MutableSet<Long>) {
        var t = start
        while (t < end) {
            val barStart = tf.barStart(t)
            val segmentEnd = minOf(end, tf.nextBarStart(barStart))
            if (barStart >= from) {
                open.getOrPut(barStart) { Acc() }.add(s, segmentEnd - t, factor, maxDistance)
                changed += barStart
            }
            t = segmentEnd
        }
    }

    /** Running sums of quantity x milliseconds per bin of the timeframe (bin index = floor(price / binSize)). */
    private class Acc {
        var weight = 0L
        var bidTop = 0
        var bids = DoubleArray(0) // bids[j]: bin bidTop - j
        var askBottom = 0
        var asks = DoubleArray(0) // asks[j]: bin askBottom + j

        fun copy() = Acc().also {
            it.weight = weight
            it.bidTop = bidTop
            it.bids = bids.copyOf()
            it.askBottom = askBottom
            it.asks = asks.copyOf()
        }

        fun add(s: BookSnapshot, ms: Long, factor: Int, maxDistance: Double) {
            weight += ms
            val w = ms.toDouble()
            val lowest = BookSnapshot.bin(s.mid * (1 - maxDistance))
            val highest = BookSnapshot.bin(s.mid * (1 + maxDistance))

            val bidFrom = s.bidTop
            val bidTo = maxOf(s.bidTop - s.bids.size + 1, lowest) // base bins, descending
            if (bidFrom >= bidTo) {
                growBids(Math.floorDiv(bidFrom, factor), Math.floorDiv(bidTo, factor))
                for (k in bidFrom downTo bidTo) {
                    val q = s.bids[s.bidTop - k]
                    if (q > 0f) bids[bidTop - Math.floorDiv(k, factor)] += q * w
                }
            }
            val askFrom = s.askBottom
            val askTo = minOf(s.askBottom + s.asks.size - 1, highest)
            if (askTo >= askFrom) {
                growAsks(Math.floorDiv(askFrom, factor), Math.floorDiv(askTo, factor))
                for (k in askFrom..askTo) {
                    val q = s.asks[k - s.askBottom]
                    if (q > 0f) asks[Math.floorDiv(k, factor) - askBottom] += q * w
                }
            }
        }

        /** Makes bids cover tf bins [bottom, top]. */
        private fun growBids(top: Int, bottom: Int) {
            if (bids.isEmpty()) {
                bidTop = top
                bids = DoubleArray(top - bottom + 1)
                return
            }
            val newTop = maxOf(bidTop, top)
            val newBottom = minOf(bidTop - bids.size + 1, bottom)
            if (newTop == bidTop && newBottom == bidTop - bids.size + 1) return
            val grown = DoubleArray(newTop - newBottom + 1)
            System.arraycopy(bids, 0, grown, newTop - bidTop, bids.size)
            bids = grown
            bidTop = newTop
        }

        /** Makes asks cover tf bins [bottom, top]. */
        private fun growAsks(bottom: Int, top: Int) {
            if (asks.isEmpty()) {
                askBottom = bottom
                asks = DoubleArray(top - bottom + 1)
                return
            }
            val newBottom = minOf(askBottom, bottom)
            val newTop = maxOf(askBottom + asks.size - 1, top)
            if (newBottom == askBottom && newTop == askBottom + asks.size - 1) return
            val grown = DoubleArray(newTop - newBottom + 1)
            System.arraycopy(asks, 0, grown, askBottom - newBottom, asks.size)
            asks = grown
            askBottom = newBottom
        }

        fun encode(start: Long): ByteArray? {
            if (weight <= 0) return null
            val bidCodes = codes(bids)
            val askCodes = codes(asks)
            val (b0, b1) = nonZeroRange(bidCodes)
            val (a0, a1) = nonZeroRange(askCodes)
            val bidCount = if (b0 < 0) 0 else b1 - b0 + 1
            val askCount = if (a0 < 0) 0 else a1 - a0 + 1
            if (bidCount == 0 && askCount == 0) return null
            val buf = ByteBuffer.allocate(RECORD_HEADER + bidCount + askCount).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt((start / 1000).toInt())
            buf.putInt(if (bidCount > 0) bidTop - b0 else 0)
            buf.putShort(bidCount.toShort())
            buf.putInt(if (askCount > 0) askBottom + a0 else 0)
            buf.putShort(askCount.toShort())
            if (bidCount > 0) buf.put(bidCodes, b0, bidCount)
            if (askCount > 0) buf.put(askCodes, a0, askCount)
            return buf.array()
        }

        private fun codes(sums: DoubleArray) = ByteArray(sums.size) { code(sums[it] / weight).toByte() }

        private fun nonZeroRange(codes: ByteArray): Pair<Int, Int> {
            val first = codes.indexOfFirst { it.toInt() != 0 }
            return if (first < 0) -1 to -1 else first to codes.indexOfLast { it.toInt() != 0 }
        }
    }

    companion object {
        /** A snapshot stands for the book until the next one, but no longer than this. */
        const val MAX_HOLD = 30 * MINUTE

        /**
         * Bytes before each bar's values: bar open time (UTC seconds, u32), top bid bin (i32), bid
         * count (u16), bottom ask bin (i32), ask count (u16); then one code per bin, bids from the
         * top down and asks from the bottom up. Little endian.
         */
        const val RECORD_HEADER = 16

        /** Price bin of the heatmap: finer on short bars, where the chart spans less price. */
        fun binSize(tf: Timeframe): Double = when (tf) {
            Timeframe.M1, Timeframe.M5 -> 10.0
            Timeframe.M15, Timeframe.M30, Timeframe.H1 -> 20.0
            Timeframe.H4 -> 50.0
            Timeframe.D1 -> 100.0
            Timeframe.W1 -> 250.0
            Timeframe.MN1 -> 500.0
        }

        /** How far back the heatmap is built (recordings are kept for [BookStore.RETENTION_DAYS]). */
        fun window(tf: Timeframe): Long = when (tf) {
            Timeframe.M1 -> DAY
            Timeframe.M5 -> 3 * DAY
            Timeframe.M15 -> 8 * DAY
            Timeframe.M30 -> 16 * DAY
            else -> BookStore.RETENTION_DAYS * DAY
        }

        /** Levels further than this from the mid price are left out (they would be off screen anyway). */
        fun maxDistance(tf: Timeframe): Double = when (tf) {
            Timeframe.M1 -> 0.015
            Timeframe.M5 -> 0.025
            Timeframe.M15 -> 0.035
            Timeframe.M30 -> 0.05
            Timeframe.H1 -> 0.06
            Timeframe.H4 -> 0.08
            else -> BookSnapshot.MAX_DISTANCE
        }

        /** u8 code of an average quantity in BTC: 24 steps per decade from 0.0001 BTC, 0 for nothing. */
        fun code(quantity: Double): Int =
            if (!(quantity >= 1e-7)) 0 else ((log10(quantity) + 4) * 24).roundToLong().toInt().plus(1).coerceIn(1, 255)

        /** The quantity a [code] stands for (the page decodes the same way). */
        fun quantity(code: Int): Double = if (code <= 0) 0.0 else Math.pow(10.0, (code - 1) / 24.0 - 4)

        /**
         * Builds the heatmap of [tf] from what [store] recorded of [markets] up to [now], summing the
         * books of each sampling round. Blocking; stops early once [cancelled].
         */
        fun load(store: BookStore, markets: Collection<BookMarket>, tf: Timeframe, now: Long, cancelled: () -> Boolean = { false }): HeatBuilder {
            val builder = HeatBuilder(tf, tf.barStart(now - window(tf)))
            val merger = BookMerger()
            val start = builder.from - MAX_HOLD
            // A day at a time, so a month of five exchanges' snapshots is never in memory at once.
            for (day in Math.floorDiv(start, DAY)..Math.floorDiv(now, DAY)) {
                val from = maxOf(start, day * DAY)
                val to = minOf(now + 1, (day + 1) * DAY)
                val snapshots = markets.flatMap { m -> store.readList(m, from, to).map { m to it } }.sortedBy { it.second.time }
                for ((market, s) in snapshots) {
                    if (cancelled()) throw CancellationException("heatmap no longer needed")
                    merger.add(market, s)?.let(builder::add)
                }
            }
            merger.flush()?.let(builder::add)
            return builder
        }

        private fun concat(parts: List<ByteArray>): ByteArray {
            val out = ByteArrayOutputStream(parts.sumOf { it.size })
            for (p in parts) out.write(p)
            return out.toByteArray()
        }
    }
}

/**
 * Turns the exchanges' snapshots into one combined book per sampling round: snapshots taken within
 * [ROUND_GAP] of each other are one round, and the combined book sums every exchange's latest
 * snapshot, as long as it is under [HeatBuilder.MAX_HOLD] old (so one exchange missing a round does
 * not make its liquidity vanish).
 */
class BookMerger {
    private val latest = HashMap<BookMarket, BookSnapshot>()
    private var roundEnd = 0L
    private var pending = false

    /** Adds a snapshot (in time order); returns the combined book of the round it ends, if it ends one. */
    fun add(market: BookMarket, s: BookSnapshot): BookSnapshot? {
        val finished = if (pending && s.time - roundEnd > ROUND_GAP) combined() else null
        latest[market] = s
        roundEnd = maxOf(roundEnd, s.time)
        pending = true
        return finished
    }

    /** The combined book of the round in progress, if there is one. */
    fun flush(): BookSnapshot? = if (pending) combined().also { pending = false } else null

    private fun combined(): BookSnapshot? =
        BookSnapshot.combine(roundEnd, latest.values.filter { it.time > roundEnd - HeatBuilder.MAX_HOLD })

    companion object {
        /** The app asks every exchange at once, so a round's snapshots are seconds apart. */
        const val ROUND_GAP = 20 * SECOND
    }
}
