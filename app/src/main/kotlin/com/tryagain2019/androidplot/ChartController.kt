package com.tryagain2019.androidplot

import com.tryagain2019.androidplot.data.BinanceFundingHistory
import com.tryagain2019.androidplot.data.BinanceOiHistory
import com.tryagain2019.androidplot.data.BookMerger
import com.tryagain2019.androidplot.data.BookStore
import com.tryagain2019.androidplot.data.BybitFundingHistory
import com.tryagain2019.androidplot.data.BybitOiHistory
import com.tryagain2019.androidplot.data.FundingAggregator
import com.tryagain2019.androidplot.data.FundingRepository
import com.tryagain2019.androidplot.data.HeatBuilder
import com.tryagain2019.androidplot.data.HistoryStore
import com.tryagain2019.androidplot.data.HyperliquidFundingHistory
import com.tryagain2019.androidplot.data.LiveOiRecorder
import com.tryagain2019.androidplot.data.OiAggregator
import com.tryagain2019.androidplot.data.OiRepository
import com.tryagain2019.androidplot.data.OkxFundingHistory
import com.tryagain2019.androidplot.data.OkxOiHistory
import com.tryagain2019.androidplot.data.Pacer
import com.tryagain2019.androidplot.data.StoredSeries
import com.tryagain2019.androidplot.model.BookMarket
import com.tryagain2019.androidplot.model.BookSnapshot
import com.tryagain2019.androidplot.model.Candle
import com.tryagain2019.androidplot.model.DAY
import com.tryagain2019.androidplot.model.Exchange
import com.tryagain2019.androidplot.model.HOUR
import com.tryagain2019.androidplot.model.LiveFunding
import com.tryagain2019.androidplot.model.LiveSnapshot
import com.tryagain2019.androidplot.model.MINUTE
import com.tryagain2019.androidplot.model.Sample
import com.tryagain2019.androidplot.model.Timeframe
import com.tryagain2019.androidplot.net.BinanceApi
import com.tryagain2019.androidplot.net.BinanceArchive
import com.tryagain2019.androidplot.net.BookApi
import com.tryagain2019.androidplot.net.BybitApi
import com.tryagain2019.androidplot.net.HyperliquidApi
import com.tryagain2019.androidplot.net.OkxApi
import com.tryagain2019.androidplot.net.OrderBooks
import com.tryagain2019.androidplot.net.PriceFeed
import com.tryagain2019.androidplot.net.ApiException
import com.tryagain2019.androidplot.net.HttpException
import com.tryagain2019.androidplot.net.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.EnumMap
import java.util.EnumSet
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.Executors

/** Endpoints used by the controller; tests point them at a local mock server. */
class Apis(
    val client: OkHttpClient,
    val binance: BinanceApi,
    val bybit: BybitApi,
    val okx: OkxApi,
    val hyperliquid: HyperliquidApi,
    val archive: BinanceArchive,
    val book: BookApi,
    val socketBases: List<String> = listOf("wss://fstream.binance.com/ws/", "wss://fstream.binance.com/market/ws/"),
) {
    val books = OrderBooks(book, bybit, okx, hyperliquid)

    companion object {
        fun production(client: OkHttpClient, archiveCache: File) = Apis(
            client = client,
            binance = BinanceApi(client),
            bybit = BybitApi(client),
            okx = OkxApi(client),
            hyperliquid = HyperliquidApi(client),
            archive = BinanceArchive(client, archiveCache),
            book = BookApi(client),
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
    /** How often the order book is sampled for the heatmap while the app is open. */
    private val bookIntervalMs: Long = 60_000,
    /** Receives JavaScript statements for the WebView. */
    private val send: (String) -> Unit,
) {
    // OKX rate limits per endpoint: 5 calls / 2 s for OI history, 10 / 2 s for funding history.
    private val okxOiPacer = Pacer(420)
    private val okxFundingPacer = Pacer(220)
    private val store = HistoryStore(File(dataDir, "history"))
    private val bookStore = BookStore.forDir(File(dataDir, "book"))
    private val recorder = LiveOiRecorder(dataDir)
    private val oiRepo = OiRepository(
        mapOf(
            Exchange.BINANCE to BinanceOiHistory(apis.binance, apis.archive),
            Exchange.BYBIT to BybitOiHistory(apis.bybit),
            Exchange.OKX to OkxOiHistory(apis.okx, okxOiPacer),
        ),
        recorder,
    )
    private val fundingRepo = FundingRepository(
        mapOf(
            Exchange.BINANCE to BinanceFundingHistory(apis.binance),
            Exchange.BYBIT to BybitFundingHistory(apis.bybit),
            Exchange.OKX to OkxFundingHistory(apis.okx, okxFundingPacer),
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

    /** Start of the range open interest / funding bars are built for; null before the timeframe's load starts. */
    private var oiFrom: Long? = null
    private var fundingFrom: Long? = null

    /** The recent part of the history is loaded (live updates and further loads may run). */
    private var oiReady = false
    private var fundingReady = false

    /** History saved by earlier launches, read once in the background. */
    private val restored = scope.async(start = CoroutineStart.LAZY) { restoreHistory() }
    private var saveJob: Job? = null
    private var historyChanged = false

    /** Disk writes run here, not in [scope], so leaving the app cannot cancel a save half way. */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "history-writer").apply { isDaemon = true } }
    private var prefetchJob: Job? = null
    private val busyStates = LinkedHashMap<String, String?>()
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

    /** Order book heatmap of the current timeframe; null while it loads or when switched off. */
    private var heat: HeatBuilder? = null
    private var heatSince: Long? = null
    private var heatJob: Job? = null
    private var bookJob: Job? = null

    /** Combined books of this session's rounds, applied again after the heatmap is (re)built from disk. */
    private val recentBook = ArrayDeque<BookSnapshot>()
    private var liveMerger = BookMerger()

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
        // Loading normally started in onStart, while the page was still loading.
        if (loadJob == null) switchTo(tf) else resendAll()
        queueStatus()
    }

    fun onStart() {
        if (started) return
        started = true
        val pausedFor = stoppedAt?.let { clock() - it }
        stoppedAt = null
        startPolling()
        startBookSampler()
        when {
            loadJob == null -> switchTo(tf)
            priceLoaded -> {
                feed.start(tf)
                if (pausedFor != null && pausedFor > MINUTE) refreshAfterPause()
            }
        }
        // The background job kept recording while the app was away; rebuild from disk to include it.
        if (pausedFor != null && pausedFor > MINUTE && heat != null) reloadHeat()
    }

    fun onStop() {
        if (!started) return
        started = false
        stoppedAt = clock()
        feed.stop()
        pollJob?.cancel()
        pollJob = null
        bookJob?.cancel()
        bookJob = null
        saveJob?.cancel()
        saveNow()
    }

    fun destroy() {
        onStop()
        loadJob?.cancel()
        heatJob?.cancel()
        prefetchJob?.cancel()
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
            key == "heat" || key.startsWith("book.") -> {
                bookJob?.cancel()
                bookJob = null
                recentBook.clear()
                liveMerger = BookMerger()
                heat = null
                sourceStates.keys.removeAll { it.startsWith("book.") }
                queueStatus()
                reloadHeat()
                startBookSampler()
            }
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
        if (oiReady && oiStart != null && wanted < oiStart - tf.nominalMs && olderOiJob?.isActive != true) {
            loadOlderOi(minOf(tf.barStart(wanted), tf.shift(tf.barStart(oiStart), -tf.historyBars / 2)))
        }
        val fundingStart = fundingFrom
        if (fundingReady && fundingStart != null && wanted < fundingStart - tf.nominalMs && olderFundingJob?.isActive != true) {
            loadOlderFunding(minOf(tf.barStart(wanted), tf.shift(tf.barStart(fundingStart), -tf.historyBars / 2)))
        }
    }

    // ------------------------------------------------------------------ loading

    private fun switchTo(next: Timeframe) {
        if (priceLoaded) savePrice()
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
        oiReady = false
        fundingReady = false
        lastOi = emptyList()
        lastFunding.clear()
        busyStates.clear()
        heat = null
        sendReset()
        loadJob = scope.launch { initialLoad(g) }
        reloadHeat()
    }

    /**
     * Shows what earlier launches stored for this timeframe straight away, then downloads only what
     * is missing: the newest candles, then the last four weeks of open interest and funding, then
     * the rest of the history range.
     */
    private suspend fun initialLoad(g: Int) {
        val stored = withContext(Dispatchers.IO) { store.readCandles("price-${tf.code}") }.orEmpty()
        if (g != loadGen) return
        if (stored.isNotEmpty()) {
            price.addAll(stored)
            priceLoaded = true
            sendPrice("set", price)
            if (started) feed.start(tf)
        }
        coroutineScope {
            val fresh = async { loadPrice(g, stored) }
            restored.await()
            if (g != loadGen) return@coroutineScope
            val now = clock()
            val from = tf.shift(tf.barStart(now), -(tf.historyBars - 1))
            oiFrom = from
            fundingFrom = from
            publishOi(force = true)
            publishFunding(force = true)
            if (!fresh.await()) return@coroutineScope
            val recentFrom = maxOf(from, tf.barStart(now - RECENT))
            launch { loadOi(g, from, recentFrom, now) }
            launch { loadFunding(g, from, recentFrom, now) }
        }
        if (g == loadGen) prefetchOtherTimeframes()
    }

    /**
     * Once per session, after the first chart loaded: refresh the stored candles of the other
     * timeframes so switching to them shows candles immediately.
     */
    private fun prefetchOtherTimeframes() {
        if (prefetchJob != null) return
        prefetchJob = scope.launch {
            delay(1_500)
            for (other in Timeframe.entries) {
                if (other == tf) continue
                val stored = withContext(Dispatchers.IO) { store.readCandles("price-${other.code}") }.orEmpty()
                val last = stored.lastOrNull()
                val missing = if (last == null) Int.MAX_VALUE else ((clock() - last.time) / other.nominalMs + 2).toInt()
                val full = missing > PRICE_PAGE - 100
                val bars = try {
                    apis.binance.klines(other.binanceInterval, if (full) PRICE_PAGE else missing.coerceIn(2, PRICE_PAGE))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                if (bars.isEmpty() || other == tf) continue
                val merged = if (full) bars else stored.filter { it.time < bars.first().time } + bars
                withContext(Dispatchers.IO) { store.writeCandles("price-${other.code}", merged.takeLast(STORED_CANDLES)) }
            }
        }
    }

    /** Newest candles (all of them without stored ones). False if there is nothing to show at all. */
    private suspend fun loadPrice(g: Int, stored: List<Candle>): Boolean {
        val last = stored.lastOrNull()
        val missing = if (last == null) Int.MAX_VALUE else ((clock() - last.time) / tf.nominalMs + 2).toInt()
        val full = missing > PRICE_PAGE - 100
        busy("price", true)
        val bars = try {
            apis.binance.klines(tf.binanceInterval, if (full) PRICE_PAGE else missing.coerceIn(2, PRICE_PAGE))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (g != loadGen) return false
            busy("price", false)
            setSource("price", "Binance-Futures BTCUSDT", false, describeError(e))
            if (stored.isNotEmpty()) return true // keep the stored candles; the stream/poller keeps trying
            reloadWhenOnline = e is IOException && e !is HttpException && e !is ApiException
            message(json {
                str("type", "error"); num("gen", gen)
                str("text", "Couldn't load BTCUSDT candles from Binance: ${describeError(e)}")
            })
            return false
        }
        if (g != loadGen) return false
        busy("price", false)
        if (full) {
            price.clear()
            price.addAll(bars)
            priceExhausted = bars.size < PRICE_PAGE
        } else if (bars.isNotEmpty()) {
            price.removeAll { it.time >= bars.first().time }
            price.addAll(bars)
        }
        priceLoaded = true
        setSource("price", "Binance-Futures BTCUSDT", true, "loaded")
        sendPrice("set", price)
        if (started && !feed.running) feed.start(tf)
        return true
    }

    private suspend fun loadOi(g: Int, from: Long, recentFrom: Long, now: Long) {
        busy("oi", true)
        ensureOi(enabledOiExchanges(), recentFrom, now, g)
        if (g != loadGen) return
        oiReady = true
        publishOi(force = true)
        if (recentFrom > from) {
            ensureOi(enabledOiExchanges(), from, recentFrom, g)
            if (g != loadGen) return
            publishOi(force = true)
        }
        busy("oi", false)
        scheduleSave()
    }

    private suspend fun loadFunding(g: Int, from: Long, recentFrom: Long, now: Long) {
        busy("funding", true)
        ensureFunding(Exchange.entries, recentFrom, now, g)
        if (g != loadGen) return
        fundingReady = true
        publishFunding(force = true)
        if (recentFrom > from) {
            ensureFunding(Exchange.entries, from, recentFrom, g)
            if (g != loadGen) return
            publishFunding(force = true)
        }
        busy("funding", false)
        scheduleSave()
    }

    // ------------------------------------------------------------------ stored history

    private suspend fun restoreHistory() {
        class Loaded(val oi: Map<Exchange, StoredSeries?>, val funding: Map<Exchange, StoredSeries?>, val recorded: Map<Exchange, List<Sample>>)
        val loaded = withContext(Dispatchers.IO) {
            Loaded(
                oi = Exchange.entries.filter { it.hasOiHistory }.associateWith { store.readSeries("oi-${it.key}") },
                funding = Exchange.entries.associateWith { store.readSeries("funding-${it.key}") },
                recorded = recorder.load(),
            )
        }
        for ((ex, series) in loaded.oi) {
            if (series == null) continue
            oiRepo.restore(ex, series)
            if (series.points.isNotEmpty()) oiHistoryOk += ex
        }
        for ((ex, series) in loaded.funding) if (series != null) fundingRepo.restore(ex, series)
        for ((ex, list) in loaded.recorded) {
            oiRepo.restore(ex, StoredSeries(TreeMap<Long, Double>().apply { for (p in list) put(p.time, p.value) }, emptyMap()))
        }
    }

    private fun savePrice() {
        val name = "price-${tf.code}"
        val candles = ArrayList(price.takeLast(STORED_CANDLES))
        writer.execute { store.writeCandles(name, candles) }
    }

    /** Saves once loading settles; history is copied here, on the UI thread, and written in the background. */
    private fun scheduleSave() {
        historyChanged = true
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2_000)
            saveNow()
        }
    }

    private fun saveNow() {
        if (priceLoaded) savePrice()
        if (!historyChanged || !restored.isCompleted) return
        historyChanged = false
        val now = clock()
        val oi = Exchange.entries.filter { it.hasOiHistory }.associateWith { oiRepo.snapshot(it, now) }
        val funding = Exchange.entries.associateWith { fundingRepo.snapshot(it) }
        writer.execute {
            for ((ex, series) in oi) store.writeSeries("oi-${ex.key}", series)
            for ((ex, series) in funding) store.writeSeries("funding-${ex.key}", series)
        }
    }

    /** Waits for queued disk writes (tests). */
    internal fun awaitSaved() {
        writer.submit {}.get()
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
                scheduleSave()
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
                scheduleSave()
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
            scheduleSave()
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
        if (oiReady) publishOi(force = false)
        if (fundingReady) publishFunding(force = false)
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
        if (!oiReady) return
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
        if (!fundingReady) return
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

    // ------------------------------------------------------------------ order book heatmap

    private fun heatOn() = settings.get("heat") != "off"

    private fun enabledBooks() = BookMarket.enabled(settings::get)

    /** (Re)builds the current timeframe's heatmap from the recorded snapshots. */
    private fun reloadHeat() {
        heatJob?.cancel()
        val g = loadGen
        heatJob = scope.launch { loadHeat(g) }
    }

    private suspend fun loadHeat(g: Int) {
        if (!heatOn()) {
            publishHeat()
            return
        }
        val markets = enabledBooks()
        val frame = tf
        val now = clock()
        val (built, since) = withContext(Dispatchers.IO) {
            HeatBuilder.load(bookStore, markets, frame, now) { !isActive } to markets.mapNotNull { bookStore.firstTime(it) }.minOrNull()
        }
        if (g != loadGen || frame != tf || markets != enabledBooks() || !heatOn()) return
        // Snapshots taken while loading may not have reached the disk yet.
        for (s in recentBook) built.add(s)
        heat = built
        heatSince = since ?: recentBook.firstOrNull()?.time
        publishHeat()
    }

    private fun startBookSampler() {
        if (!started || !heatOn() || bookJob?.isActive == true) return
        bookJob = scope.launch {
            while (isActive) {
                val t0 = clock()
                sampleBooks(enabledBooks())
                delay(maxOf(1_000L, bookIntervalMs - (clock() - t0)))
            }
        }
    }

    /** One round: every enabled exchange's book at once; each is stored, and their sum goes into the heatmap. */
    private suspend fun sampleBooks(markets: List<BookMarket>) {
        if (markets.isEmpty()) return
        val results = coroutineScope {
            markets.map { m -> async { m to runCatching { withTimeout(30_000) { apis.books.snapshot(m, clock) } } } }.awaitAll()
        }
        if (markets != enabledBooks() || !heatOn()) return
        for ((market, result) in results) {
            val error = result.exceptionOrNull()
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            val snapshot = result.getOrNull()
            if (snapshot == null) {
                setSource("book.${market.key}", bookSourceName(market), false, if (error is TimeoutCancellationException) "timed out" else describeError(error!!))
                continue
            }
            writer.execute { bookStore.append(market, snapshot) }
            liveMerger.add(market, snapshot)
            setSource("book.${market.key}", bookSourceName(market), true, describeBook(snapshot))
        }
        liveMerger.flush()?.let(::onCombinedBook)
    }

    private fun onCombinedBook(s: BookSnapshot) {
        recentBook.addLast(s)
        while (recentBook.size > 64) recentBook.removeFirst()
        if (heatSince == null) heatSince = s.time
        val h = heat ?: return
        val changed = h.add(s)
        if (changed.isEmpty()) return
        val data = h.encodeBars(changed, clock())
        if (data.isNotEmpty()) {
            message(json {
                str("type", "heat"); num("gen", gen); str("mode", "live")
                raw("since", heatSince?.let { (it / 1000).toString() } ?: "null")
                raw("data", "\"" + Base64.getEncoder().encodeToString(data) + "\"")
            })
        }
    }

    /** Sends the whole heatmap (or that it is switched off). */
    private fun publishHeat() {
        if (!pageReady) return
        if (!heatOn()) {
            message(json { str("type", "heat"); num("gen", gen); str("mode", "off") })
            return
        }
        val h = heat ?: return
        val data = h.encodeAll(clock())
        message(json {
            str("type", "heat"); num("gen", gen); str("mode", "set")
            raw("books", enabledBooks().joinToString(",", "[", "]") { "\"${it.key}\"" })
            num("binSize", h.binSize)
            raw("since", heatSince?.let { (it / 1000).toString() } ?: "null")
            raw("data", "\"" + Base64.getEncoder().encodeToString(data) + "\"")
        })
    }

    private fun bookSourceName(market: BookMarket) = "Order book: ${market.displayName}"

    private fun describeBook(s: BookSnapshot): String {
        val reach = maxOf(s.mid - s.low, s.high - s.mid) / s.mid * 100
        return String.format(Locale.US, "live \u00b7 %,.0f\u2013%,.0f (\u00b1%.1f%%)", s.low, s.high, reach)
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
        // Only exchanges without public history (Hyperliquid) would not be an aggregate worth showing.
        if (inputs.none { !it.backfill }) return
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
        if (series.values.all { it.isEmpty() }) return
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
                key("heat").obj {
                    bool("on", heatOn())
                    key("books").obj { for (m in BookMarket.entries) bool(m.key, settings.get("book.${m.key}") != "false") }
                    str("bg", settings.get("heat.bg") ?: "any")
                    str("lo", settings.get("heat.lo"))
                    str("hi", settings.get("heat.hi"))
                    str("mode", settings.get("heat.mode"))
                    str("blo", settings.get("heat.blo"))
                    str("bhi", settings.get("heat.bhi"))
                }
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
        message(json { str("type", "price"); num("gen", gen); str("mode", mode); candles("bars", bars, 2, volumeDecimals = 3) })
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
        publishHeat()
        for ((what, detail) in busyStates.toList()) busy(what, true, detail)
    }

    private fun busy(what: String, busy: Boolean, detail: String? = null) {
        if (busy) busyStates[what] = detail else busyStates.remove(what)
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
                exchanges.any { it.ok == false } || sourceStates.any { (k, s) -> k.startsWith("book.") && s.ok == false } -> "warn"
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
        const val STORED_CANDLES = 1500

        /** Loaded first so the lower panes appear quickly; the rest of the range follows. */
        const val RECENT = 28 * DAY

        /** BTCUSDT perpetual listing on Binance (Sep 2019); nothing older exists anywhere we look. */
        const val EARLIEST = 1_567_296_000_000L
    }
}
