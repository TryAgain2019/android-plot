package com.tryagain2019.androidplot.data

import com.tryagain2019.androidplot.model.DAY
import com.tryagain2019.androidplot.model.FundingEvent
import com.tryagain2019.androidplot.model.HOUR
import com.tryagain2019.androidplot.model.OiResolution
import com.tryagain2019.androidplot.model.Sample
import com.tryagain2019.androidplot.net.ApiException
import com.tryagain2019.androidplot.net.BinanceApi
import com.tryagain2019.androidplot.net.BinanceArchive
import com.tryagain2019.androidplot.net.BybitApi
import com.tryagain2019.androidplot.net.HyperliquidApi
import com.tryagain2019.androidplot.net.OkxApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * Result of a history request. [coveredFrom] is how far back the request was actually served
 * (later than the requested start when a load was capped); nothing older exists if it equals the
 * requested start even when [items] ends later.
 */
class Fetched<T>(val items: List<T>, val coveredFrom: Long)

/** Historical open-interest snapshots (BTC) of one exchange. */
fun interface OiHistorySource {
    /** Snapshots between [from] and [to] (ms), about [res] apart. [progress] reports (done, total) for slow loads. */
    suspend fun history(from: Long, to: Long, res: OiResolution, archiveStepDays: Int, now: Long, progress: (Int, Int) -> Unit): Fetched<Sample>
}

/** Historical settled funding rates of one exchange. */
fun interface FundingHistorySource {
    suspend fun history(from: Long, to: Long, now: Long): Fetched<FundingEvent>
}

/** Keeps calls to a rate-limited endpoint at least [minIntervalMs] apart. */
class Pacer(private val minIntervalMs: Long) {
    private val mutex = Mutex()
    private var last = 0L

    suspend fun await() = mutex.withLock {
        val wait = last + minIntervalMs - System.nanoTime() / 1_000_000
        if (wait > 0) delay(wait)
        last = System.nanoTime() / 1_000_000
    }
}

/** Runs [block] for every item with at most [parallelism] calls in flight; results keep the items' order. */
internal suspend fun <T, R> inParallel(items: List<T>, parallelism: Int, block: suspend (T) -> R): List<R> = coroutineScope {
    val permits = Semaphore(parallelism)
    items.map { item -> async { permits.withPermit { block(item) } } }.awaitAll()
}

/**
 * Splits [from, to] into request windows of at most [span], newest first. At most [maxWindows] are
 * returned; the second value is how far back they reach (> [from] when capped).
 */
internal fun windowsBackward(from: Long, to: Long, span: Long, maxWindows: Int): Pair<List<LongRange>, Long> {
    val out = ArrayList<LongRange>()
    var end = to
    while (end >= from) {
        if (out.size == maxWindows) return out to end
        val start = maxOf(from, end - span)
        out += start..end
        end = start - 1
    }
    return out to from
}

/** Binance keeps 30 days of OI in its API; anything older comes from the daily archive files. */
class BinanceOiHistory(private val api: BinanceApi, private val archive: BinanceArchive) : OiHistorySource {
    /** First day known to have an archive file, once an older stretch came back empty. */
    private var archiveStart: LocalDate? = null

    override suspend fun history(from: Long, to: Long, res: OiResolution, archiveStepDays: Int, now: Long, progress: (Int, Int) -> Unit): Fetched<Sample> {
        val out = ArrayList<Sample>()
        val apiFloor = now - 30 * DAY + 2 * HOUR
        var coveredFrom = from
        if (to > apiFloor) {
            val (windows, reached) = windowsBackward(maxOf(from, apiFloor), to, 499 * res.ms, MAX_PAGES)
            inParallel(windows, 4) { w -> api.openInterestHist(res.binance, w.first, w.last, 500) }.forEach { out += it }
            if (reached > maxOf(from, apiFloor)) coveredFrom = reached
        }
        if (from < apiFloor && coveredFrom == from) {
            val first = archiveStart
            val dates = archiveDates(from, minOf(to, apiFloor), archiveStepDays).filter { first == null || !it.isBefore(first) }
            out += archiveDays(dates, now, progress)
        }
        return Fetched(out, coveredFrom)
    }

    /** Downloads the day files [ARCHIVE_PARALLELISM] at a time (they are small; latency dominates). */
    private suspend fun archiveDays(dates: List<LocalDate>, now: Long, progress: (Int, Int) -> Unit): List<Sample> {
        if (dates.isEmpty()) return emptyList()
        val done = AtomicInteger()
        progress(0, dates.size)
        val results = inParallel(dates, ARCHIVE_PARALLELISM) { date ->
            try {
                Result.success(archive.day(date, now))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                progress(done.incrementAndGet(), dates.size)
            }
        }
        // Days before the archive's first file come back empty (dates are oldest first); remember
        // where it starts so scrolling back does not ask for them again.
        val firstWithData = dates.indices.firstOrNull { results[it].getOrNull()?.isNotEmpty() == true }
        val emptyLead = firstWithData ?: dates.size
        if (emptyLead >= 8 && (0 until emptyLead).all { results[it].isSuccess }) {
            archiveStart = if (firstWithData != null) dates[firstWithData] else dates.last().plusDays(1)
        }
        // A few missing days are interpolated over; mostly failing means the archive is unreachable.
        val failures = results.mapNotNull { it.exceptionOrNull() }
        if (failures.size * 2 > dates.size) throw failures.last()
        return results.flatMap { it.getOrNull().orEmpty() }
    }

    companion object {
        private const val MAX_PAGES = 40
        private const val ARCHIVE_PARALLELISM = 24

        /** Days whose archive file is needed: every day, Mondays for weekly steps (7), or the 1st of each month (0). */
        fun archiveDates(from: Long, to: Long, stepDays: Int): List<LocalDate> {
            if (to < from) return emptyList()
            val first = Instant.ofEpochMilli(from).atZone(ZoneOffset.UTC).toLocalDate()
            val last = Instant.ofEpochMilli(to).atZone(ZoneOffset.UTC).toLocalDate()
            val out = ArrayList<LocalDate>()
            var d = first
            while (!d.isAfter(last)) {
                val wanted = when {
                    stepDays <= 0 -> d.dayOfMonth == 1
                    stepDays == 7 -> d.dayOfWeek == DayOfWeek.MONDAY
                    else -> true
                }
                if (wanted) out += d
                d = d.plusDays(1)
            }
            // Sparse steps still need a file near the requested end so the newest bars are anchored.
            if (stepDays != 1 && (out.isEmpty() || out.last() != last)) out += last
            return out
        }
    }
}

class BybitOiHistory(private val api: BybitApi) : OiHistorySource {
    override suspend fun history(from: Long, to: Long, res: OiResolution, archiveStepDays: Int, now: Long, progress: (Int, Int) -> Unit): Fetched<Sample> {
        val start = maxOf(from, LISTING)
        if (to < start) return Fetched(emptyList(), from)
        val (windows, reached) = windowsBackward(start, to, 199 * res.ms, MAX_PAGES)
        val pages = inParallel(windows, 4) { w -> api.openInterest(res.bybit, w.first, w.last, 200) }
        return Fetched(pages.flatten(), if (reached > start) reached else from)
    }

    private companion object {
        const val MAX_PAGES = 60

        /** BTCUSDT linear perpetual listing on Bybit (2020-03-25); nothing older exists. */
        const val LISTING = 1_585_094_400_000L
    }
}

/**
 * OKX keeps the latest 1440 snapshots per period, so older ranges fall back to coarser periods,
 * and the endpoint allows 5 calls per 2 seconds.
 */
class OkxOiHistory(private val api: OkxApi, private val pacer: Pacer) : OiHistorySource {
    private var dailyPeriod = OiResolution.D1.okx

    override suspend fun history(from: Long, to: Long, res: OiResolution, archiveStepDays: Int, now: Long, progress: (Int, Int) -> Unit): Fetched<Sample> {
        val out = ArrayList<Sample>()
        var r = res
        var end = to
        var pages = 0
        while (end >= from) {
            val depthStart = now - DEPTH * r.ms
            if (end <= depthStart) {
                r = r.coarser() ?: break
                continue
            }
            if (pages++ == MAX_PAGES) return Fetched(out, end)
            val start = maxOf(from, end - 99 * r.ms, depthStart)
            pacer.await()
            val page = try {
                api.openInterestHistory(period(r), start, end, 100)
            } catch (e: ApiException) {
                // "1Dutc" is the UTC-aligned daily period; fall back to the Hong Kong aligned "1D" if refused.
                if (r == OiResolution.D1 && dailyPeriod != "1D") {
                    dailyPeriod = "1D"
                    continue
                }
                throw e
            }
            if (page.isEmpty()) {
                // History at this period ends earlier than expected: retry the window with a coarser one.
                r = r.coarser() ?: break
                continue
            }
            out += page
            end = start - 1
        }
        return Fetched(out, from)
    }

    private fun period(r: OiResolution) = if (r == OiResolution.D1) dailyPeriod else r.okx

    private companion object {
        const val DEPTH = 1440
        const val MAX_PAGES = 40
    }
}

class BinanceFundingHistory(private val api: BinanceApi) : FundingHistorySource {
    override suspend fun history(from: Long, to: Long, now: Long): Fetched<FundingEvent> {
        val out = ArrayList<FundingEvent>()
        var start = from
        var pages = 0
        while (start <= to && pages++ < 20) {
            val page = api.fundingRates(start, to, 1000)
            out += page
            if (page.size < 1000) break
            start = page.last().time + 1
        }
        return Fetched(out, from)
    }
}

class BybitFundingHistory(private val api: BybitApi) : FundingHistorySource {
    override suspend fun history(from: Long, to: Long, now: Long): Fetched<FundingEvent> {
        val out = ArrayList<FundingEvent>()
        var end = to
        var pages = 0
        while (end >= from && pages++ < 40) {
            val page = api.fundingHistory(end, 200)
            out += page.filter { it.time >= from }
            if (page.size < 200 || page.first().time <= from) break
            end = page.first().time - 1
        }
        return Fetched(out, from)
    }
}

/** OKX only serves about three months of funding history. */
class OkxFundingHistory(private val api: OkxApi, private val pacer: Pacer) : FundingHistorySource {
    override suspend fun history(from: Long, to: Long, now: Long): Fetched<FundingEvent> {
        if (to < now - 100 * DAY) return Fetched(emptyList(), from)
        val out = ArrayList<FundingEvent>()
        var after: Long? = if (to < now - HOUR) to + 1 else null
        var pages = 0
        while (pages++ < 12) {
            pacer.await()
            val page = api.fundingRateHistory(after, 100)
            out += page.filter { it.time in from..to }
            if (page.isEmpty() || page.first().time <= from) break
            after = page.first().time
        }
        return Fetched(out, from)
    }
}

/**
 * Hyperliquid funds hourly, so long ranges take many calls: windows of 500 hours are fetched in
 * parallel, newest first, and a load stops after [maxPages]; scrolling further back loads more.
 */
class HyperliquidFundingHistory(private val api: HyperliquidApi, private val maxPages: Int = 12) : FundingHistorySource {
    override suspend fun history(from: Long, to: Long, now: Long): Fetched<FundingEvent> {
        val start = maxOf(from, LAUNCH)
        if (to < start) return Fetched(emptyList(), from)
        val (windows, reached) = windowsBackward(start, to, 499 * HOUR, maxPages)
        val pages = inParallel(windows, 4) { w -> api.fundingHistory(w.first, w.last) }
        return Fetched(pages.flatten(), if (reached > start) reached else from)
    }

    private companion object {
        /** 2023-01-01; Hyperliquid's mainnet perps started in 2023. */
        const val LAUNCH = 1_672_531_200_000L
    }
}
