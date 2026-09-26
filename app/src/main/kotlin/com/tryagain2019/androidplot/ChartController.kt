package com.tryagain2019.androidplot

import com.tryagain2019.androidplot.data.BinanceFundingHistory
import com.tryagain2019.androidplot.data.BinanceOiHistory
import com.tryagain2019.androidplot.data.BybitFundingHistory
import com.tryagain2019.androidplot.data.BybitOiHistory
import com.tryagain2019.androidplot.data.FundingAggregator
import com.tryagain2019.androidplot.data.FundingRepository
import com.tryagain2019.androidplot.data.HyperliquidFundingHistory
import com.tryagain2019.androidplot.data.LiveOiRecorder
import com.tryagain2019.androidplot.data.OiAggregator
import com.tryagain2019.androidplot.data.OiRepository
import com.tryagain2019.androidplot.data.OkxFundingHistory
import com.tryagain2019.androidplot.data.OkxOiHistory
import com.tryagain2019.androidplot.data.Pacer
import com.tryagain2019.androidplot.model.Candle
import com.tryagain2019.androidplot.model.Exchange
import com.tryagain2019.androidplot.model.HOUR
import com.tryagain2019.androidplot.model.LiveFunding
import com.tryagain2019.androidplot.model.LiveSnapshot
import com.tryagain2019.androidplot.model.MINUTE
import com.tryagain2019.androidplot.model.Sample
import com.tryagain2019.androidplot.model.Timeframe
import com.tryagain2019.androidplot.net.BinanceApi
import com.tryagain2019.androidplot.net.BinanceArchive
import com.tryagain2019.androidplot.net.BybitApi
import com.tryagain2019.androidplot.net.HyperliquidApi
import com.tryagain2019.androidplot.net.OkxApi
import com.tryagain2019.androidplot.net.PriceFeed
import com.tryagain2019.androidplot.net.ApiException
import com.tryagain2019.androidplot.net.HttpException
import com.tryagain2019.androidplot.net.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.EnumMap
import java.util.EnumSet
import java.util.Locale

/** Endpoints used by the controller; tests point them at a local mock server. */
class Apis(
    val client: OkHttpClient,
    val binance: BinanceApi,
    val bybit: BybitApi,
    val okx: OkxApi,
    val hyperliquid: HyperliquidApi,
    val archive: BinanceArchive,
    val socketBases: List<String> = listOf("wss://fstream.binance.com/ws/", "wss://fstream.binance.com/market/ws/"),
) {
    companion object {
        fun production(client: OkHttpClient, archiveCache: File) = Apis(
            client = client,
            binance = BinanceApi(client),
            bybit = BybitApi(client),
            okx = OkxApi(client),
            hyperliquid = HyperliquidApi(client),
            archive = BinanceArchive(client, archiveCache),
        )
    }
}

interface SettingsStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/**
 * Loads and live-updates the three panes for the selected timeframe and pushes them to the chart
 * page. Everything runs on [scope]'s dispatcher (the main thread in the app); network calls suspend.
 */
class ChartController(
    private val scope: CoroutineScope,
    private val apis: Apis,
    private val settings: SettingsStore,
    dataDir: File,
    private val versionName: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = 5_000,
    /** Receives JavaScript statements for the WebView. */
    private val send: (String) -> Unit,
) {
    private val okxPacer = Pacer(450)
    private val oiRepo = OiRepository(
        mapOf(
            Exchange.BINANCE to BinanceOiHistory(apis.binance, apis.archive),
            Exchange.BYBIT to BybitOiHistory(apis.bybit),
            Exchange.OKX to OkxOiHistory(apis.okx, okxPacer),
        ),
        LiveOiRecorder(dataDir),
    )
    private val fundingRepo = FundingRepository(
        mapOf(
            Exchange.BINANCE to BinanceFundingHistory(apis.binance),
            Exchange.BYBIT to BybitFundingHistory(apis.bybit),
            Exchange.OKX to OkxFundingHistory(apis.okx, okxPacer),
            Exchange.HYPERLIQUID to HyperliquidFundingHistory(apis.hyperliquid),
        ),
    )
    private val feed = PriceFeed(
        client = apis.client,
        api = apis.binance,
        scope = scope,
        socketBases = apis.socketBases,
        clock = clock,
        onCandle = ::onLiveCandle,
        onState = { ok, text -> setSource("price", "Binance-Futures BTCUSDT", ok, text) },
    )

    private var tf: Timeframe = Timeframe.of(settings.get("tf")) ?: Timeframe.D1

    /** Generation of what the page shows; bumped on every reset message (page ignores stale messages). */
    private var gen = 0

    /** Generation of the loaded data; bumped only when the timeframe (re)loads, so re-sending keeps loads alive. */
    private var loadGen = 0
    private var pageReady = false
    private var started = false
    private var stoppedAt: Long? = null

    private val price = ArrayList<Candle>()
    private var priceLoaded = false
    private var priceExhausted = false

    /** Set when the first load failed for lack of network; a later successful poll then reloads. */
    private var reloadWhenOnline = false
    private var lastAutoReload = 0L

    /** Start of the range open interest / funding bars are built for; null until the first load finished. */
    private var oiFrom: Long? = null
    private var fundingFrom: Long? = null
    private val oiHistoryOk = EnumSet.noneOf(Exchange::class.java)
    private val oiHistoryFailedAt = EnumMap<Exchange, Long>(Exchange::class.java)
    private var lastOi: List<Candle> = emptyList()
    private val lastFunding = EnumMap<Exchange, List<Sample>>(Exchange::class.java)
    private val fundingRefreshAt = EnumMap<Exchange, Long>(Exchange::class.java)

    private var loadJob: Job? = null
    private var olderPriceJob: Job? = null
    private var olderOiJob: Job? = null
    private var olderFundingJob: Job? = null
    private var pollJob: Job? = null
    private val sideJobs = ArrayList<Job>()

    private class Source(val name: String, val ok: Boolean?, val text: String)

    private val sourceStates = LinkedHashMap<String, Source>()
    private val liveStates = EnumMap<Exchange, Source>(Exchange::class.java)
    private val historyErrors = EnumMap<Exchange, MutableMap<String, String>>(Exchange::class.java)
    private var statusQueued = false
    private var lastStatusJson = ""

    // ------------------------------------------------------------------ page and lifecycle events

    fun onPageReady() {
        pageReady = true
        lastStatusJson = ""
        sendInit()
        when {
            priceLoaded && loadJob?.isActive != true -> resendAll()
            loadJob?.isActive == true -> Unit
            else -> switchTo(tf)
        }
        queueStatus()
    }

    fun onStart() {
        if (started) return
        started = true
        val pausedFor = stoppedAt?.let { clock() - it }
        stoppedAt = null
        startPolling()
        if (priceLoaded) {
            feed.start(tf)
            if (pausedFor != null && pausedFor > MINUTE) refreshAfterPause()
        }
    }

    fun onStop() {
        if (!started) return
        started = false
        stoppedAt = clock()
        feed.stop()
        pollJob?.cancel()
        pollJob = null
    }

    fun destroy() {
        onStop()
        loadJob?.cancel()
    }

    fun setTimeframe(code: String) {
        val next = Timeframe.of(code) ?: return
        settings.put("tf", next.code)
        switchTo(next)
    }

    fun retry() = switchTo(tf)

    fun setSetting(key: String, value: String) {
        settings.put(key, value)
        when {
            key == "tz" -> resendAll()
            key.startsWith("oi.") -> {
                val ex = Exchange.of(key.removePrefix("oi.")) ?: return
                if (value == "true" && ex.hasOiHistory && oiFrom != null) {
                    val g = loadGen
                    sideJobs += scope.launch {
                        ensureOi(listOf(ex), oiFrom ?: return@launch, clock(), g)
                        if (g == loadGen) publishOi(force = true)
                    }
                } else {
                    publishOi(force = true)
                }
            }
        }
    }

    /** The chart reports its visible range (UTC seconds) so older history can be loaded when needed. */
    fun onVisibleRange(g: Int, fromSec: Double, toSec: Double, logicalFrom: Double) {
        if (g != gen || !priceLoaded) return
        val from = (fromSec * 1000).toLong()
        val to = (toSec * 1000).toLong()
        if (logicalFrom < 200 && !priceExhausted && olderPriceJob?.isActive != true) loadOlderPrice()

        val margin = maxOf(to - from, 20 * tf.nominalMs) / 2
        val wanted = maxOf(from - margin, EARLIEST)
        val oiStart = oiFrom
        if (oiStart != null && wanted < oiStart - tf.nominalMs && olderOiJob?.isActive != true) {
            loadOlderOi(minOf(tf.barStart(wanted), tf.shift(tf.barStart(oiStart), -tf.historyBars / 2)))
        }
        val fundingStart = fundingFrom
        if (fundingStart != null && wanted < fundingStart - tf.nominalMs && olderFundingJob?.isActive != true) {
            loadOlderFunding(minOf(tf.barStart(wanted), tf.shift(tf.barStart(fundingStart), -tf.historyBars / 2)))
        }
    }

    // ------------------------------------------------------------------ loading

    private fun switchTo(next: Timeframe) {
        tf = next
        gen++
        loadGen++
        val g = loadGen
        loadJob?.cancel()
        olderPriceJob?.cancel()
        olderOiJob?.cancel()
        olderFundingJob?.cancel()
        sideJobs.forEach { it.cancel() }
        sideJobs.clear()
        feed.stop()
        price.clear()
        priceLoaded = false
        priceExhausted = false
        oiFrom = null
        fundingFrom = null
        lastOi = emptyList()
        lastFunding.clear()
        sendReset()
        loadJob = scope.launch { initialLoad(g) }
    }

    private suspend fun initialLoad(g: Int) {
        busy("price", true)
        val bars = try {
            apis.binance.klines(tf.binanceInterval, PRICE_PAGE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (g != loadGen) return
            busy("price", false)
            reloadWhenOnline = e is IOException && e !is HttpException && e !is ApiException
            setSource("price", "Binance-Futures BTCUSDT", false, describeError(e))
            message(json {
                str("type", "error"); num("gen", g)
                str("text", "Couldn't load BTCUSDT candles from Binance: ${describeError(e)}")
            })
            return
        }
        if (g != loadGen) return
        busy("price", false)
        price.addAll(bars)
        priceLoaded = true
        priceExhausted = bars.size < PRICE_PAGE
        setSource("price", "Binance-Futures BTCUSDT", true, "loaded")
        sendPrice("set", price)
        if (started) feed.start(tf)

        val now = clock()
        val from = tf.shift(tf.barStart(now), -(tf.historyBars - 1))
        coroutineScope {
            launch {
                busy("oi", true)
                ensureOi(enabledOiExchanges(), from, now, g)
                if (g == loadGen) {
                    oiFrom = from
                    busy("oi", false)
                    publishOi(force = true)
                }
            }
            launch {
                busy("funding", true)
                ensureFunding(Exchange.entries, from, now, g)
                if (g == loadGen) {
                    fundingFrom = from
                    busy("funding", false)
                    publishFunding(force = true)
                }
            }
        }
    }

    /** Loads open-interest history for [exchanges] in parallel; failures only mark that exchange's status. */
    private suspend fun ensureOi(exchanges: List<Exchange>, from: Long, to: Long, g: Int) = coroutineScope {
        for (ex in exchanges.filter { it.hasOiHistory }) {
            launch {
                try {
                    oiRepo.ensure(ex, from, to, tf, clock()) { done, total ->
                        if (g == loadGen && total > 4) busy("oi", true, if (done < total) "history $done/$total" else null)
                    }
                    oiHistoryOk += ex
                    oiHistoryFailedAt.remove(ex)
                    setHistoryError(ex, "OI", null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    oiHistoryFailedAt[ex] = clock()
                    setHistoryError(ex, "OI", describeError(e))
                }
            }
        }
    }

    private suspend fun ensureFunding(exchanges: List<Exchange>, from: Long, to: Long, g: Int) = coroutineScope {
        for (ex in exchanges) {
            launch {
                try {
                    fundingRepo.ensure(ex, from, to, clock())
                    setHistoryError(ex, "funding", null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (g == loadGen) setHistoryError(ex, "funding", describeError(e))
                }
            }
        }
    }

    private fun loadOlderPrice() {
        val g = loadGen
        val first = price.firstOrNull() ?: return
        olderPriceJob = scope.launch {
            busy("price", true, "loading history…")
            try {
                val bars = apis.binance.klines(tf.binanceInterval, PRICE_PAGE, endTime = first.time - 1)
                if (g != loadGen) return@launch
                val older = bars.filter { it.time < first.time }
                if (bars.size < PRICE_PAGE) priceExhausted = true
                if (older.isNotEmpty()) {
                    price.addAll(0, older)
                    sendPrice("prepend", older)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (g == loadGen) setSource("price", "Binance-Futures BTCUSDT", false, "history: ${describeError(e)}")
            } finally {
                if (g == loadGen) busy("price", false)
            }
        }
    }

    private fun loadOlderOi(newFrom: Long) {
        val g = loadGen
        val currentFrom = oiFrom ?: return
        olderOiJob = scope.launch {
            busy("oi", true)
            try {
                ensureOi(enabledOiExchanges(), newFrom, currentFrom + tf.nominalMs, g)
                if (g != loadGen) return@launch
                oiFrom = minOf(currentFrom, newFrom)
                publishOi(force = true)
            } finally {
                if (g == loadGen) busy("oi", false)
            }
        }
    }

    private fun loadOlderFunding(newFrom: Long) {
        val g = loadGen
        val currentFrom = fundingFrom ?: return
        olderFundingJob = scope.launch {
            busy("funding", true)
            try {
                ensureFunding(Exchange.entries, newFrom, currentFrom + tf.nominalMs, g)
                if (g != loadGen) return@launch
                fundingFrom = minOf(currentFrom, newFrom)
                publishFunding(force = true)
            } finally {
                if (g == loadGen) busy("funding", false)
            }
        }
    }

    /** After the app was in the background: fill the gap without resetting the chart. */
    private fun refreshAfterPause() {
        val g = loadGen
        val last = price.lastOrNull() ?: return
        val now = clock()
        val missingBars = ((now - last.time) / tf.nominalMs + 3).toInt()
        if (missingBars > PRICE_PAGE - 100) {
            switchTo(tf)
            return
        }
        sideJobs += scope.launch {
            try {
                val bars = apis.binance.klines(tf.binanceInterval, missingBars.coerceIn(2, PRICE_PAGE))
                if (g != loadGen || bars.isEmpty()) return@launch
                price.removeAll { it.time >= bars.first().time }
                price.addAll(bars)
                sendPrice("set", price)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setSource("price", "Binance-Futures BTCUSDT", false, describeError(e))
            }
            val oiStart = oiFrom
            if (oiStart != null) {
                ensureOi(enabledOiExchanges(), oiStart, now, g)
                if (g == loadGen) publishOi(force = true)
            }
            val fundingStart = fundingFrom
            if (fundingStart != null) {
                ensureFunding(Exchange.entries, fundingStart, now, g)
                if (g == loadGen) publishFunding(force = true)
            }
        }
    }

    // ------------------------------------------------------------------ live data

    private fun onLiveCandle(candle: Candle) {
        if (!priceLoaded) return
        val last = price.lastOrNull()
        when {
            last == null || candle.time > last.time -> price += candle
            candle.time == last.time -> price[price.size - 1] = candle
            else -> {
                // Final state of the previous bar (REST polling returns the last two bars).
                val i = price.binarySearchBy(candle.time) { it.time }
                if (i >= 0) price[i] = candle else return
            }
        }
        if (pageReady) sendPrice("live", listOf(candle))
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var round = 0
            while (isActive) {
                val started = clock()
                pollRound(round++)
                delay(maxOf(1_000L, pollIntervalMs - (clock() - started)))
            }
        }
    }

    private suspend fun pollRound(round: Int) {
        val t = clock()
        // Hyperliquid's context call returns every market, so poll it less often.
        val exchanges = Exchange.entries.filter { it != Exchange.HYPERLIQUID || round % 3 == 0 }
        val results = coroutineScope {
            exchanges.map { ex ->
                async { ex to runCatching { withTimeout(9_000) { pollExchange(ex, t) } } }
            }.awaitAll()
        }
        val oi = EnumMap<Exchange, Double>(Exchange::class.java)
        for ((ex, result) in results) {
            val error = result.exceptionOrNull()
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            val snapshot = result.getOrNull()
            if (snapshot == null) {
                liveStates[ex] = Source(ex.displayName, false, describeError(error ?: Exception("no data")))
                updateExchangeStatus(ex)
                continue
            }
            snapshot.openInterest?.let { oi[ex] = it }
            snapshot.funding?.let { fundingRepo.setLive(ex, it) }
            liveStates[ex] = Source(ex.displayName, true, describeLive(snapshot))
            updateExchangeStatus(ex)
        }
        if (reloadWhenOnline && !priceLoaded && loadJob?.isActive != true && results.any { it.second.isSuccess } &&
            t - lastAutoReload > 30_000
        ) {
            // The network is back after a failed start: load again without waiting for "Retry".
            reloadWhenOnline = false
            lastAutoReload = t
            switchTo(tf)
        }
        oiRepo.recordLive(t, oi)
        publishOi(force = false)
        publishFunding(force = false)
        retryFailedHistory(t)
        refreshSettledFunding(t)
    }

    private suspend fun pollExchange(ex: Exchange, t: Long): LiveSnapshot = when (ex) {
        Exchange.BINANCE -> coroutineScope {
            val oi = async { runCatching { apis.binance.openInterest().value } }
            val funding = async { runCatching { apis.binance.premiumIndex() } }
            both(t, oi.await(), funding.await())
        }
        Exchange.BYBIT -> apis.bybit.ticker().copy(time = t)
        Exchange.OKX -> coroutineScope {
            val oi = async { runCatching { apis.okx.openInterest().value } }
            val funding = async { runCatching { apis.okx.fundingRate() } }
            both(t, oi.await(), funding.await())
        }
        Exchange.HYPERLIQUID -> apis.hyperliquid.assetContext(t)
    }

    private fun both(t: Long, oi: Result<Double>, funding: Result<LiveFunding>): LiveSnapshot {
        if (oi.isFailure && funding.isFailure) throw oi.exceptionOrNull()!!
        return LiveSnapshot(t, oi.getOrNull(), funding.getOrNull())
    }

    private fun describeLive(s: LiveSnapshot): String {
        val parts = ArrayList<String>()
        s.openInterest?.let { parts += String.format(Locale.US, "OI %,.0f BTC", it) }
        s.funding?.let { parts += String.format(Locale.US, "funding %.4f%%", it.rate * 100) }
        return if (parts.isEmpty()) "live" else parts.joinToString(" · ")
    }

    /** Exchanges whose OI history failed (e.g. a network hiccup) are retried every minute. */
    private fun retryFailedHistory(now: Long) {
        val from = oiFrom ?: return
        val due = enabledOiExchanges().filter { it.hasOiHistory && it !in oiHistoryOk && now - (oiHistoryFailedAt[it] ?: 0L) > MINUTE }
        sideJobs.removeAll { !it.isActive }
        if (due.isEmpty() || sideJobs.isNotEmpty()) return
        val g = loadGen
        for (ex in due) oiHistoryFailedAt[ex] = now
        sideJobs += scope.launch {
            ensureOi(due, from, now, g)
            if (g == loadGen) publishOi(force = true)
        }
    }

    /** Once a funding settlement has passed, fetch the settled rate so past bars use the real value. */
    private fun refreshSettledFunding(now: Long) {
        val from = fundingFrom ?: return
        sideJobs.removeAll { !it.isActive }
        for (ex in Exchange.entries) {
            val next = fundingRepo.live(ex)?.nextFundingTime ?: continue
            val fetchedTo = fundingRepo.fetchedTo(ex) ?: continue
            if (now < next + 90_000 || fetchedTo >= next) continue
            if (now - (fundingRefreshAt[ex] ?: 0L) < 2 * MINUTE) continue
            fundingRefreshAt[ex] = now
            val g = loadGen
            sideJobs += scope.launch {
                ensureFunding(listOf(ex), from, now, g)
                if (g == loadGen) publishFunding(force = true)
            }
        }
    }

    // ------------------------------------------------------------------ building and sending panes

    private fun enabledOiExchanges(): List<Exchange> = Exchange.entries.filter { settings.get("oi.${it.key}") != "false" }

    private fun publishOi(force: Boolean) {
        val from = oiFrom ?: return
        if (!pageReady) return
        val inputs = enabledOiExchanges().mapNotNull { ex ->
            val samples = oiRepo.samples(ex)
            when {
                samples.isEmpty() -> null
                ex.hasOiHistory && ex !in oiHistoryOk -> null
                else -> OiAggregator.Input(samples, backfill = !ex.hasOiHistory)
            }
        }
        val bars = OiAggregator.build(tf, tf.oiResolution.ms, from, clock(), inputs, oiRepo.liveTimes)
        val previous = lastOi
        lastOi = bars
        val tail = changedTail(previous, bars, ::sameCandle)
        if (force || tail == null) {
            message(json { str("type", "oi"); num("gen", gen); str("mode", "set"); candles("bars", bars, 3) })
        } else if (tail.isNotEmpty()) {
            message(json { str("type", "oi"); num("gen", gen); str("mode", "live"); candles("bars", tail, 3) })
        }
    }

    private fun publishFunding(force: Boolean) {
        val from = fundingFrom ?: return
        if (!pageReady) return
        val now = clock()
        val series = EnumMap<Exchange, List<Sample>>(Exchange::class.java)
        var needSet = force
        val tails = EnumMap<Exchange, List<Sample>>(Exchange::class.java)
        for (ex in Exchange.entries) {
            val points = FundingAggregator.build(tf, from, now, fundingRepo.events(ex), fundingRepo.live(ex), ex.fundingIntervalHours * HOUR)
            series[ex] = points
            val tail = changedTail(lastFunding[ex] ?: emptyList(), points) { a, b -> a.time == b.time && Math.abs(a.value - b.value) < 1e-9 }
            if (tail == null) needSet = true else if (tail.isNotEmpty()) tails[ex] = tail
        }
        lastFunding.clear()
        lastFunding.putAll(series)
        val chosen = if (needSet) series else tails
        if (!needSet && tails.isEmpty()) return
        message(json {
            str("type", "funding"); num("gen", gen); str("mode", if (needSet) "set" else "live")
            key("series").obj { for ((ex, pts) in chosen) points(ex.key, pts, 6) }
        })
    }

    /**
     * If [next] only differs from [previous] in its last bars (the live bar, or a new bar
     * appended), returns those bars; returns null when older bars changed too (send everything).
     */
    private fun <T> changedTail(previous: List<T>, next: List<T>, same: (T, T) -> Boolean): List<T>? {
        if (previous.isEmpty()) return if (next.isEmpty()) emptyList() else null
        if (next.size < previous.size || next.size > previous.size + 2) return null
        val stable = previous.size - 1 // the previous live bar may have changed
        for (i in 0 until stable) if (!same(previous[i], next[i])) return null
        val first = if (same(previous[stable], next[stable])) stable + 1 else stable
        return next.subList(first, next.size).toList()
    }

    private fun sameCandle(a: Candle, b: Candle) =
        a.time == b.time && close(a.open, b.open) && close(a.high, b.high) && close(a.low, b.low) && close(a.close, b.close)

    private fun close(a: Double, b: Double) = Math.abs(a - b) < 1e-6

    private fun sendInit() {
        message(json {
            str("type", "init")
            str("version", versionName)
            str("tf", tf.code)
            key("settings").obj {
                str("tz", settings.get("tz") ?: "local")
                key("oi").obj { for (ex in Exchange.entries) bool(ex.key, settings.get("oi.${ex.key}") != "false") }
                key("funding").obj { for (ex in Exchange.entries) bool(ex.key, settings.get("funding.${ex.key}") != "false") }
            }
        })
    }

    private fun sendReset() {
        message(json {
            str("type", "reset"); num("gen", gen); str("tf", tf.code)
            str("symbol", apis.binance.symbol); str("venue", "Binance-Futures")
        })
    }

    private fun sendPrice(mode: String, bars: List<Candle>) {
        message(json { str("type", "price"); num("gen", gen); str("mode", mode); candles("bars", bars, 2) })
    }

    /** Re-sends everything held in memory under a new generation (page reload, time zone change). */
    private fun resendAll() {
        gen++
        sendReset()
        if (price.isNotEmpty()) sendPrice("set", price)
        lastOi = emptyList()
        lastFunding.clear()
        publishOi(force = true)
        publishFunding(force = true)
    }

    private fun busy(what: String, busy: Boolean, detail: String? = null) {
        message(json { str("type", "busy"); num("gen", gen); str("what", what); bool("busy", busy); str("detail", detail) })
    }

    /** Records (or clears, with a null [error]) a failed history load; [kind] is "OI" or "funding". */
    private fun setHistoryError(ex: Exchange, kind: String, error: String?) {
        val errors = historyErrors.getOrPut(ex) { LinkedHashMap() }
        if (error == null) errors.remove(kind) else errors[kind] = error
        updateExchangeStatus(ex)
    }

    /** One status line per exchange: its live poll and any failed history load. */
    private fun updateExchangeStatus(ex: Exchange) {
        val live = liveStates[ex]
        val errors = historyErrors[ex].orEmpty()
        val parts = listOfNotNull(live?.text) + errors.map { (kind, error) -> "$kind history failed: $error" }
        setSource(ex.key, ex.displayName, live?.ok != false && errors.isEmpty(), parts.joinToString(" \u00b7 ").ifEmpty { "loading\u2026" })
    }

    private fun setSource(key: String, name: String, ok: Boolean, text: String) {
        val previous = sourceStates[key]
        if (previous != null && previous.ok == ok && previous.text == text) return
        sourceStates[key] = Source(name, ok, text)
        queueStatus()
    }

    private fun queueStatus() {
        if (statusQueued) return
        statusQueued = true
        scope.launch {
            delay(300)
            statusQueued = false
            val price = sourceStates["price"]
            val exchanges = Exchange.entries.mapNotNull { sourceStates[it.key] }
            val live = when {
                price?.ok == false -> "error"
                price == null -> "pending"
                exchanges.any { it.ok == false } -> "warn"
                else -> "ok"
            }
            val body = json {
                str("type", "status"); str("live", live)
                key("sources").obj {
                    for ((k, s) in sourceStates) key(k).obj {
                        str("name", s.name)
                        str("state", if (s.ok == true) "ok" else if (s.ok == false) "error" else "pending")
                        str("text", s.text)
                    }
                }
            }
            if (body != lastStatusJson) {
                lastStatusJson = body
                message(body)
            }
        }
    }

    private fun message(jsonText: String) {
        if (pageReady) send("window.chartApp&&chartApp.receive($jsonText);")
    }

    private companion object {
        const val PRICE_PAGE = 1000

        /** BTCUSDT perpetual listing on Binance (Sep 2019); nothing older exists anywhere we look. */
        const val EARLIEST = 1_567_296_000_000L
    }
}
