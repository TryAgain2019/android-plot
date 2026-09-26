package com.tryagain2019.androidplot.data

import com.tryagain2019.androidplot.model.Exchange
import com.tryagain2019.androidplot.model.FundingEvent
import com.tryagain2019.androidplot.model.LiveFunding
import com.tryagain2019.androidplot.model.MINUTE
import com.tryagain2019.androidplot.model.Sample
import com.tryagain2019.androidplot.model.SECOND
import com.tryagain2019.androidplot.model.Timeframe
import java.io.File
import java.util.EnumMap
import java.util.NavigableMap
import java.util.NavigableSet
import java.util.TreeMap
import java.util.TreeSet

/** Contiguous time range already fetched for some series. */
private data class Span(val from: Long, val to: Long) {
    fun missing(a: Long, b: Long): List<Pair<Long, Long>> = buildList {
        if (a < from) add(a to minOf(b, from))
        if (b > to) add(maxOf(a, to) to b)
    }

    fun union(a: Long, b: Long) = Span(minOf(from, a), maxOf(to, b))
}

/**
 * Open-interest snapshots per exchange (history + live polls), in BTC. Not thread safe: use it from
 * one thread (the UI thread in the app).
 */
class OiRepository(
    private val sources: Map<Exchange, OiHistorySource>,
    private val recorder: LiveOiRecorder? = null,
) {
    private val samples = EnumMap<Exchange, TreeMap<Long, Double>>(Exchange::class.java).apply {
        for (ex in Exchange.entries) put(ex, TreeMap())
    }
    private val spans = HashMap<String, Span>()

    /** Latest live point per exchange that is not also a historical snapshot (so it may be replaced). */
    private val replaceableLive = EnumMap<Exchange, Long>(Exchange::class.java)
    private val rounds = TreeSet<Long>()

    init {
        recorder?.load()?.forEach { (ex, list) -> for (s in list) samples.getValue(ex)[s.time] = s.value }
    }

    fun samples(ex: Exchange): NavigableMap<Long, Double> = samples.getValue(ex)

    /** Times of live polling rounds; bars use them as extra points so live bars get real highs and lows. */
    val liveTimes: NavigableSet<Long> get() = rounds

    /** Fetches whatever part of [from, to] this exchange has not been fetched for at this timeframe's resolution. */
    suspend fun ensure(ex: Exchange, from: Long, to: Long, tf: Timeframe, now: Long, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val source = sources[ex] ?: return
        val res = if (ex == Exchange.OKX) tf.okxOiResolution else tf.oiResolution
        val key = "${ex.key}/${res.name}/${tf.archiveStepDays}"
        val span = spans[key]
        val gaps = span?.missing(from, to) ?: listOf(from to to)
        for ((a, b) in gaps) {
            if (b - a < MINUTE && span != null) continue
            val got = source.history(a, b, res, tf.archiveStepDays, now, progress)
            val map = samples.getValue(ex)
            for (s in got.items) {
                map[s.time] = s.value
                if (replaceableLive[ex] == s.time) replaceableLive.remove(ex)
            }
            val covered = maxOf(a, minOf(got.coveredFrom, b))
            spans[key] = spans[key]?.union(covered, b) ?: Span(covered, b)
        }
    }

    /** Earliest time already fetched for [ex] at this timeframe's resolution, if any. */
    fun fetchedFrom(ex: Exchange, tf: Timeframe): Long? {
        val res = if (ex == Exchange.OKX) tf.okxOiResolution else tf.oiResolution
        return spans["${ex.key}/${res.name}/${tf.archiveStepDays}"]?.from
    }

    /**
     * Records one polling round (not every exchange is polled every round). A live point less than
     * 10 s after the exchange's previous one replaces it, so a long session keeps at most one live
     * point per 10 s per exchange.
     */
    fun recordLive(time: Long, values: Map<Exchange, Double>) {
        if (values.isEmpty()) return
        for ((ex, v) in values) {
            val map = samples.getValue(ex)
            val previous = replaceableLive[ex]
            if (previous != null && time - previous in 0 until 10 * SECOND) map.remove(previous)
            if (map.containsKey(time)) replaceableLive.remove(ex) else replaceableLive[ex] = time
            map[time] = v
        }
        val lastRound = if (rounds.isEmpty()) null else rounds.last()
        if (lastRound != null && time - lastRound in 0 until 10 * SECOND) rounds.remove(lastRound)
        rounds += time
        recorder?.record(time, values)
    }
}

/**
 * Settled funding rates per exchange plus the rate accruing now. Event times are rounded to the
 * minute (Hyperliquid stamps settlements a few ms after the hour).
 */
class FundingRepository(private val sources: Map<Exchange, FundingHistorySource>) {
    private val events = EnumMap<Exchange, TreeMap<Long, Double>>(Exchange::class.java).apply {
        for (ex in Exchange.entries) put(ex, TreeMap())
    }
    private val spans = EnumMap<Exchange, Span>(Exchange::class.java)
    private val live = EnumMap<Exchange, LiveFunding>(Exchange::class.java)

    fun events(ex: Exchange): NavigableMap<Long, Double> = events.getValue(ex)

    fun live(ex: Exchange): LiveFunding? = live[ex]

    fun setLive(ex: Exchange, value: LiveFunding) {
        live[ex] = value
    }

    fun fetchedFrom(ex: Exchange): Long? = spans[ex]?.from

    fun fetchedTo(ex: Exchange): Long? = spans[ex]?.to

    suspend fun ensure(ex: Exchange, from: Long, to: Long, now: Long) {
        val source = sources[ex] ?: return
        val span = spans[ex]
        val gaps = span?.missing(from, to) ?: listOf(from to to)
        for ((a, b) in gaps) {
            if (b - a < MINUTE && span != null) continue
            val got = source.history(a, b, now)
            add(ex, got.items)
            val covered = maxOf(a, minOf(got.coveredFrom, b))
            spans[ex] = spans[ex]?.union(covered, b) ?: Span(covered, b)
        }
    }

    fun add(ex: Exchange, list: List<FundingEvent>) {
        val map = events.getValue(ex)
        for (e in list) map[Math.round(e.time.toDouble() / MINUTE) * MINUTE] = e.rate
    }
}

/**
 * Persists live open interest of exchanges without public history (Hyperliquid), so their history
 * grows while the app is used. One point per 5 minutes, kept for about a year.
 */
class LiveOiRecorder(private val dir: File, private val exchanges: Set<Exchange> = setOf(Exchange.HYPERLIQUID)) {
    private val lastWritten = EnumMap<Exchange, Long>(Exchange::class.java)

    private fun file(ex: Exchange) = File(dir, "oi-${ex.key}.csv")

    fun load(): Map<Exchange, List<Sample>> = exchanges.associateWith { ex ->
        val f = file(ex)
        if (!f.isFile) return@associateWith emptyList()
        val list = runCatching {
            f.readLines().mapNotNull { line ->
                val comma = line.indexOf(',')
                if (comma <= 0) null else Sample(line.substring(0, comma).toLong(), line.substring(comma + 1).toDouble())
            }
        }.getOrDefault(emptyList())
        if (list.size > MAX_LINES) {
            val kept = list.takeLast(MAX_LINES / 2)
            runCatching { f.writeText(kept.joinToString("") { "${it.time},${it.value}\n" }) }
            kept
        } else {
            list
        }.also { lastWritten[ex] = it.lastOrNull()?.time ?: 0L }
    }

    fun record(time: Long, values: Map<Exchange, Double>) {
        for (ex in exchanges) {
            val v = values[ex] ?: continue
            if (time - (lastWritten[ex] ?: 0L) < 5 * MINUTE) continue
            lastWritten[ex] = time
            runCatching {
                dir.mkdirs()
                file(ex).appendText("$time,$v\n")
            }
        }
    }

    private companion object {
        const val MAX_LINES = 110_000
    }
}
