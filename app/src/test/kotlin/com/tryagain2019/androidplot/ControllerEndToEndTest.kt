package com.tryagain2019.androidplot

import com.tryagain2019.androidplot.data.BookStore
import com.tryagain2019.androidplot.data.HeatBuilder
import com.tryagain2019.androidplot.model.BookMarket
import com.tryagain2019.androidplot.model.DAY
import com.tryagain2019.androidplot.model.HOUR
import com.tryagain2019.androidplot.model.Timeframe
import com.tryagain2019.androidplot.net.BinanceApi
import com.tryagain2019.androidplot.net.BinanceArchive
import com.tryagain2019.androidplot.net.BookApi
import com.tryagain2019.androidplot.net.BookParsers
import com.tryagain2019.androidplot.net.BybitApi
import com.tryagain2019.androidplot.net.HyperliquidApi
import com.tryagain2019.androidplot.net.OkxApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestName
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the real controller, API clients, history loaders and live feeds against [FakeExchanges]
 * and checks the messages that would reach the chart page.
 */
class ControllerEndToEndTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val testName = TestName()

    private val server = MockWebServer()
    private val fake = FakeExchanges()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ui").apply { isDaemon = true } }
    private val ui = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + ui)
    private val messages = CopyOnWriteArrayList<JSONObject>()
    private val settings = HashMap<String, String>()
    private lateinit var controller: ChartController
    private lateinit var dataDir: File

    private class MapSettings(private val map: MutableMap<String, String>) : SettingsStore {
        override fun get(key: String) = synchronized(map) { map[key] }
        override fun put(key: String, value: String) = synchronized(map) { map[key] = value }
    }

    @Before
    fun setUp() {
        server.dispatcher = fake
        server.start()
        val base = server.url("/").toString().removeSuffix("/")
        val client = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
        val apis = Apis(
            client = client,
            binance = BinanceApi(client, base),
            bybit = BybitApi(client, base),
            okx = OkxApi(client, base),
            hyperliquid = HyperliquidApi(client, base),
            archive = BinanceArchive(client, tmp.newFolder("archive"), base),
            book = BookApi(client, spotBases = listOf(base), futuresBase = base),
            socketBases = listOf(base.replaceFirst("http", "ws") + "/ws/"),
        )
        settings["tf"] = "1d"
        dataDir = tmp.newFolder("data")
        controller = ChartController(scope, apis, MapSettings(settings), dataDir, "test", pollIntervalMs = 400, bookIntervalMs = 300) { js ->
            val prefix = "window.chartApp&&chartApp.receive("
            assertTrue(js.startsWith(prefix) && js.endsWith(");"), js.take(80))
            messages += JSONObject(js.substring(prefix.length, js.length - 2))
        }
    }

    @After
    fun tearDown() {
        runBlocking(ui) { controller.destroy() }
        scope.cancel()
        fake.stopStreams()
        Thread.sleep(200)
        runCatching { server.shutdown() }
        executor.shutdownNow()
        // Keep the transcript for tools/web-test/replay.js.
        File("build/e2e").mkdirs()
        File("build/e2e/${testName.methodName}.json").writeText(JSONArray(messages.map { it }).toString())
    }

    private fun onUi(block: () -> Unit) = runBlocking(ui) { block() }

    private fun waitFor(what: String, timeoutMs: Long = 60_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        fail("timed out waiting for $what; got ${messages.map { it.optString("type") + "/" + it.optString("mode") }}")
    }

    private fun find(type: String, mode: String? = null, gen: Int? = null) = messages.filter {
        it.optString("type") == type && (mode == null || it.optString("mode") == mode) && (gen == null || it.optInt("gen") == gen)
    }

    /** True once the timeframe's history has finished loading (the page's "loading" marks are cleared). */
    private fun loaded(gen: Int, vararg what: String = arrayOf("price", "oi", "funding")) = what.all { w ->
        messages.lastOrNull { it.optString("type") == "busy" && it.optString("what") == w && it.optInt("gen") == gen }?.optBoolean("busy") == false
    }

    private fun expectedOi(tSec: Long) = (tSec * 1000).let { FakeExchanges.binanceOi(it) + FakeExchanges.bybitOi(it) + FakeExchanges.okxOi(it) + FakeExchanges.HL_OI }

    @Test
    fun dailyChartLoadsAllPanesAndGoesLive() {
        onUi {
            controller.onStart()
            controller.onPageReady()
        }
        waitFor("reset") { find("reset").isNotEmpty() }
        assertEquals("init", messages.first().getString("type"))
        val reset = find("reset").first()
        assertEquals("1d", reset.getString("tf"))
        val gen = reset.getInt("gen")
        waitFor("history loaded") { loaded(gen) }

        // Price: 1000 daily candles ending with today's.
        val price = find("price", "set", gen).last().getJSONArray("bars")
        assertEquals(1000, price.length())
        val today = Timeframe.D1.barStart(System.currentTimeMillis()) / 1000
        assertEquals(today, price.getJSONArray(price.length() - 1).getLong(0))

        // Wait until Hyperliquid's first live poll is part of the aggregate, then check the bars.
        waitFor("oi with hyperliquid") {
            val last = find("oi", gen = gen).last().getJSONArray("bars")
            last.length() > 0 && Math.abs(last.getJSONArray(last.length() - 1).getDouble(4) - expectedOi(System.currentTimeMillis() / 1000)) < 500
        }
        val oiSet = find("oi", "set", gen).last().getJSONArray("bars")
        assertEquals(Timeframe.D1.historyBars, oiSet.length())
        // A bar from ~100 days ago comes from Binance's archive + Bybit 4h + OKX daily snapshots.
        val old = oiSet.getJSONArray(oiSet.length() - 100)
        assertEquals(expectedOi(old.getLong(0)), old.getDouble(1), 0.01)
        for (i in 0 until oiSet.length() - 1) {
            val b = oiSet.getJSONArray(i)
            assertTrue(b.getDouble(2) >= maxOf(b.getDouble(1), b.getDouble(4)) - 1e-6 && b.getDouble(3) <= minOf(b.getDouble(1), b.getDouble(4)) + 1e-6)
            assertEquals(b.getDouble(4), oiSet.getJSONArray(i + 1).getDouble(1), 0.01) // close == next open
        }
        // One archive file per day older than the API's 30 days, and nothing twice.
        assertTrue(fake.archiveRequests.get() in Timeframe.D1.historyBars - 32..Timeframe.D1.historyBars - 27, "archive requests: ${fake.archiveRequests.get()}")

        // Funding: % per 8h; Hyperliquid's hourly rate x8; OKX only ~3 months back.
        val funding = find("funding", "set", gen).last().getJSONObject("series")
        fun values(key: String) = funding.getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONArray(it) } }
        assertTrue(values("binance").all { Math.abs(it.getDouble(1) - 0.01) < 1e-9 })
        assertTrue(values("bybit").all { Math.abs(it.getDouble(1) - 0.008) < 1e-9 })
        assertTrue(values("hyperliquid").all { Math.abs(it.getDouble(1) - 0.01) < 1e-9 })
        assertEquals(Timeframe.D1.historyBars, values("binance").size)
        val okx = values("okx")
        assertTrue(okx.first().getLong(0) >= (System.currentTimeMillis() - 93 * DAY) / 1000 && okx.size in 88..95, "okx ${okx.size}")

        // Live updates: stream candles and polled OI/funding.
        waitFor("live price") { find("price", "live", gen).size >= 3 }
        val live = find("price", "live", gen).last().getJSONArray("bars").getJSONArray(0)
        assertEquals(today, live.getLong(0))
        assertEquals(FakeExchanges.price(System.currentTimeMillis()), live.getDouble(4), 50.0)
        waitFor("live oi") { find("oi", "live", gen).isNotEmpty() }
        waitFor("status ok") { find("status").lastOrNull()?.optString("live") == "ok" }
        val sources = find("status").last().getJSONObject("sources")
        for (key in listOf("price", "binance", "bybit", "okx", "hyperliquid")) {
            assertEquals("ok", sources.getJSONObject(key).getString("state"), key)
        }
    }

    @Test
    fun orderBookHeatmapIsRecordedAndStreamed() {
        onUi {
            controller.onStart()
            controller.onPageReady()
        }
        waitFor("heatmap") { find("heat", "set").isNotEmpty() }
        val set = find("heat", "set").first()
        assertEquals("spot", set.getString("market"))
        assertEquals(100.0, set.getDouble("binSize"))
        waitFor("live heat") { find("heat", "live").size >= 3 }
        assertTrue(fake.requests.contains("/api/v3/depth"))
        assertTrue(fake.requests.none { it == "/fapi/v1/depth" })

        // Today's daily bar: $100 bins of the fake book, with walls at every $1000.
        val live = find("heat", "live").last()
        val today = Timeframe.D1.barStart(System.currentTimeMillis())
        val bar = decodeHeat(Base64.getDecoder().decode(live.getString("data"))).single { it.time == today }
        val mid = FakeExchanges.price(System.currentTimeMillis())
        assertTrue(Math.abs(Math.floor(mid / 100).toInt() - bar.bidTop) <= 1, "top bid bin ${bar.bidTop} for mid $mid")
        val inner = (2 until bar.bids.size - 2).map { j -> bar.bidTop - j to bar.bids[j] }
        val walls = inner.filter { (bin, _) -> bin % 10 == 0 }
        assertTrue(walls.isNotEmpty())
        assertTrue(walls.minOf { it.second } > inner.filter { (bin, _) -> bin % 5 != 0 }.maxOf { it.second }, "walls stand out")
        assertEquals(HeatBuilder.code(50.0), inner.first { (bin, _) -> bin % 5 != 0 }.second, "a \$100 bin holds 200 levels of 0.25 BTC")
        waitFor("book status") { find("status").lastOrNull()?.getJSONObject("sources")?.optJSONObject("book")?.optString("state") == "ok" }

        // What was recorded is on disk: a new chart (e.g. after a restart) shows it straight away.
        onUi { controller.onStop() }
        controller.awaitSaved()
        val stored = HeatBuilder.load(BookStore.forDir(File(dataDir, "book")), BookMarket.SPOT, Timeframe.M1, System.currentTimeMillis())
        assertTrue(decodeHeat(stored.encodeAll(System.currentTimeMillis())).isNotEmpty())

        // Switching to the futures book records that one instead.
        onUi {
            controller.onStart()
            controller.setSetting("heat.book", "futures")
        }
        waitFor("futures heatmap") { find("heat", "set").any { it.optString("market") == "futures" } }
        waitFor("futures depth") { fake.requests.contains("/fapi/v1/depth") }

        // Switched off: the page is told, and sampling stops.
        onUi { controller.setSetting("heat", "off") }
        waitFor("heat off") { find("heat", "off").isNotEmpty() }
        Thread.sleep(400)
        val requests = fake.requests.count { it.endsWith("/depth") }
        Thread.sleep(1_000)
        assertEquals(requests, fake.requests.count { it.endsWith("/depth") })
    }

    @Test
    fun recordedOrderBookShowsAsHeatOnTheHourlyChart() {
        // Three days recorded in the background (a snapshot every 15 minutes), before the app opens.
        val now = System.currentTimeMillis()
        val store = BookStore.forDir(File(dataDir, "book"))
        var t = now - 3 * DAY
        while (t < now - 20 * 60_000) {
            store.append(BookMarket.SPOT, BookParsers.depth(FakeExchanges.depthJson(t, 5000, 0.5, futures = false), t))
            t += 15 * 60_000
        }
        val gen = openOn("1h")
        waitFor("heatmap") { find("heat", "set", gen).isNotEmpty() }
        val set = find("heat", "set", gen).first()
        assertEquals(20.0, set.getDouble("binSize"))
        assertEquals((now - 3 * DAY) / 1000, set.getLong("since"))
        val bars = decodeHeat(Base64.getDecoder().decode(set.getString("data")))
        assertTrue(bars.size in 72..74, "bars ${bars.size}")
        for (bar in bars.dropLast(1)) {
            // Each hour: the fake book around that hour's price, 0.25 BTC per 50 cents -> 10 BTC per $20 bin.
            val mid = FakeExchanges.price(bar.time + 30 * 60_000)
            assertTrue(Math.abs(bar.bidTop * 20 - mid) < 700, "bar ${bar.time}: top bid ${bar.bidTop * 20}, price $mid")
            val usual = bar.bids.drop(40).dropLast(40).groupingBy { it }.eachCount().maxBy { it.value }.key
            assertEquals(HeatBuilder.code(10.0), usual)
        }
        waitFor("history loaded") { loaded(gen) }
        waitFor("live heat") { find("heat", "live", gen).isNotEmpty() }
    }

    @Test
    fun switchingTimeframeAndScrollingBackLoadsMore() {
        onUi {
            controller.onStart()
            controller.onPageReady()
        }
        waitFor("daily oi") { find("oi", "set").isNotEmpty() }
        onUi { controller.setTimeframe("1h") }
        waitFor("hourly reset") { find("reset").any { it.getString("tf") == "1h" } }
        val gen = find("reset").last().getInt("gen")
        waitFor("hourly history") { loaded(gen) }
        assertEquals("1h", settings["tf"])
        val oi = find("oi", "set", gen).last().getJSONArray("bars")
        assertEquals(Timeframe.H1.historyBars, oi.length())
        val bar = oi.getJSONArray(10)
        assertEquals(expectedOi(bar.getLong(0)), bar.getDouble(1), 0.5)

        // Near the left edge: older candles are prepended.
        val price = find("price", "set", gen).last().getJSONArray("bars")
        val firstSec = price.getJSONArray(0).getLong(0)
        onUi { controller.onVisibleRange(gen, firstSec.toDouble(), firstSec + 100 * 3600.0, 5.0) }
        waitFor("prepend") { find("price", "prepend", gen).isNotEmpty() }
        val older = find("price", "prepend", gen).single().getJSONArray("bars")
        assertEquals(1000, older.length())
        assertEquals(firstSec - 3600, older.getJSONArray(older.length() - 1).getLong(0))

        // Viewing hours before the loaded open interest extends it.
        val oiFirst = oi.getJSONArray(0).getLong(0)
        onUi { controller.onVisibleRange(gen, oiFirst - 200 * 3600.0, oiFirst.toDouble(), 500.0) }
        waitFor("older oi") { find("oi", "set", gen).last().getJSONArray("bars").length() > oi.length() + 100 }
        val extended = find("oi", "set", gen).last().getJSONArray("bars")
        assertTrue(extended.getJSONArray(0).getLong(0) <= oiFirst - 200 * 3600)
        // Older than OKX's hourly depth (60 days) it falls back to coarser snapshots; still continuous.
        for (i in 0 until extended.length() - 1) {
            assertEquals(extended.getJSONArray(i).getDouble(4), extended.getJSONArray(i + 1).getDouble(1), 0.01)
        }
    }

    /** Opens the page on [tf] (the controller starts on the daily chart). */
    private fun openOn(tf: String): Int {
        onUi {
            controller.onStart()
            controller.onPageReady()
            controller.setTimeframe(tf)
        }
        waitFor("$tf reset") { find("reset").any { it.getString("tf") == tf } }
        return find("reset").last().getInt("gen")
    }

    @Test
    fun weeklyAndMonthlyUseSparseArchiveDays() {
        val gen = openOn("1w")
        waitFor("weekly history") { loaded(gen) }
        val weekly = find("oi", "set", gen).last().getJSONArray("bars")
        assertEquals(Timeframe.W1.historyBars, weekly.length())
        val w = weekly.getJSONArray(20)
        assertEquals(expectedOi(w.getLong(0)), w.getDouble(1), 1.0)
        val weeklyArchive = fake.archiveRequests.get()
        assertTrue(weeklyArchive in 100..125, "weekly archive requests $weeklyArchive")

        onUi { controller.setTimeframe("1M") }
        waitFor("monthly history") { find("reset").last().getString("tf") == "1M" && loaded(find("reset").last().getInt("gen")) }
        val monthly = find("oi", "set").last().getJSONArray("bars")
        // OKX keeps ~1440 daily snapshots (~3.9 years), which limits how far back the aggregate goes.
        assertTrue(monthly.length() in 44..48, "monthly bars ${monthly.length()}")
        val m = monthly.getJSONArray(10)
        assertEquals(expectedOi(m.getLong(0)), m.getDouble(1), 1.0)
    }

    @Test
    fun historyFailureStaysVisibleAndExchangeIsLeftOut() {
        fake.failures["/api/v5/rubik/stat/contracts/open-interest-history"] = 500
        onUi {
            controller.onStart()
            controller.onPageReady()
        }
        waitFor("oi") { find("oi", "set").isNotEmpty() }
        // Live polls of OKX keep succeeding, but the failed history must stay visible.
        Thread.sleep(1_500)
        val okx = find("status").last().getJSONObject("sources").getJSONObject("okx")
        assertEquals("error", okx.getString("state"))
        assertTrue(okx.getString("text").contains("OI history failed"), okx.getString("text"))
        assertEquals("warn", find("status").last().getString("live"))
        // The aggregate is built from the other exchanges only (no jump when OKX's live value arrives).
        val bars = find("oi").last().getJSONArray("bars")
        val last = bars.getJSONArray(bars.length() - 1)
        val now = System.currentTimeMillis()
        assertEquals(FakeExchanges.binanceOi(now) + FakeExchanges.bybitOi(now) + FakeExchanges.HL_OI, last.getDouble(4), 500.0)

        // Once OKX answers again, the next retry adds it back.
        fake.failures.clear()
        waitFor("okx back", timeoutMs = 90_000) {
            val b = find("oi").last().getJSONArray("bars")
            Math.abs(b.getJSONArray(b.length() - 1).getDouble(4) - expectedOi(System.currentTimeMillis() / 1000)) < 500
        }
    }

    @Test
    fun blockedPriceSourceShowsAnError() {
        fake.failures["/fapi/v1/klines"] = 451
        onUi {
            controller.onStart()
            controller.onPageReady()
        }
        waitFor("error") { find("error").isNotEmpty() }
        assertEquals("Couldn't load BTCUSDT candles from Binance: blocked in your region (HTTP 451)", find("error").single().getString("text"))
        waitFor("status") { find("status").lastOrNull()?.optString("live") == "error" }
        fake.failures.clear()
        onUi { controller.retry() }
        waitFor("price after retry") { find("price", "set").isNotEmpty() }
    }

    @Test
    fun startingOfflineReloadsWhenTheNetworkIsBack() {
        // Klines time out (no network); the live polls of other exchanges still work.
        fake.failures["/fapi/v1/klines"] = -1
        onUi {
            controller.onStart()
            controller.onPageReady()
        }
        waitFor("error") { find("error").isNotEmpty() }
        fake.failures.clear()
        waitFor("automatic reload", timeoutMs = 30_000) { find("price", "set").isNotEmpty() }
    }

    @Test
    fun timeZoneChangeDuringLoadingKeepsLoading() {
        onUi {
            controller.onStart()
            controller.onPageReady()
            controller.setSetting("tz", "utc")
        }
        val gen = find("reset").last().getInt("gen")
        waitFor("oi + funding for the new generation") { find("oi", "set", gen).isNotEmpty() && find("funding", "set", gen).isNotEmpty() }
        assertEquals("utc", settings["tz"])
    }

    @Test
    fun hourlyFundingIsScaledToEightHours() {
        val gen = openOn("4h")
        waitFor("4h history") { loaded(gen) }
        val series = find("funding", "set", gen).last().getJSONObject("series")
        val hl = series.getJSONArray("hyperliquid")
        assertTrue(hl.length() >= Timeframe.H4.historyBars - 1)
        val last = hl.getJSONArray(hl.length() - 1)
        assertEquals(Timeframe.H4.barStart(System.currentTimeMillis()) / 1000, last.getLong(0))
        assertEquals(0.01, last.getDouble(1), 1e-9)
        assertTrue(series.getJSONArray("binance").length() >= Timeframe.H4.historyBars - 1)
        assertEquals(4 * HOUR / 1000, series.getJSONArray("binance").let { it.getJSONArray(1).getLong(0) - it.getJSONArray(0).getLong(0) })
    }
}
