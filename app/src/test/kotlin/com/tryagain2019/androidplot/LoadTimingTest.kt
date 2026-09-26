package com.tryagain2019.androidplot

import com.tryagain2019.androidplot.model.Timeframe
import com.tryagain2019.androidplot.net.BinanceApi
import com.tryagain2019.androidplot.net.BinanceArchive
import com.tryagain2019.androidplot.net.BybitApi
import com.tryagain2019.androidplot.net.HyperliquidApi
import com.tryagain2019.androidplot.net.OkxApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * How long the panes take to appear with a phone-like 150 ms round trip per request, on a first
 * launch and on a later launch that can use what the first one stored.
 */
class LoadTimingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val fake = FakeExchanges(latencyMs = 150)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ui").apply { isDaemon = true } }
    private val ui = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + ui)
    private lateinit var apis: Apis
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
        // Every fake exchange shares one host here; allow as many connections as separate hosts would.
        val client = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 64 })
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        dataDir = tmp.newFolder("data")
        apis = Apis(
            client = client,
            binance = BinanceApi(client, base),
            bybit = BybitApi(client, base),
            okx = OkxApi(client, base),
            hyperliquid = HyperliquidApi(client, base),
            archive = BinanceArchive(client, File(dataDir, "binance-archive"), base),
            socketBases = listOf(base.replaceFirst("http", "ws") + "/ws/"),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        fake.stopStreams()
        Thread.sleep(200)
        runCatching { server.shutdown() }
        executor.shutdownNow()
    }

    private class Run(val messages: List<Pair<Long, JSONObject>>, val requests: Int)

    /** Opens the app on [tf] and records when each message arrived, until [done] holds. */
    private fun launch(tf: String, done: (List<JSONObject>) -> Boolean): Run {
        val settings = HashMap(mapOf("tf" to tf))
        val start = System.nanoTime()
        val messages = CopyOnWriteArrayList<Pair<Long, JSONObject>>()
        val prefix = "window.chartApp&&chartApp.receive("
        val requestsBefore = fake.requests.size
        val controller = ChartController(scope, apis, MapSettings(settings), dataDir, "test", pollIntervalMs = 5_000) { js ->
            messages += (System.nanoTime() - start) / 1_000_000 to JSONObject(js.substring(prefix.length, js.length - 2))
        }
        runBlocking(ui) {
            controller.onStart()
            controller.onPageReady()
        }
        val deadline = System.currentTimeMillis() + 90_000
        while (!done(messages.map { it.second })) {
            if (System.currentTimeMillis() > deadline) fail("timed out: ${messages.map { it.second.optString("type") }}")
            Thread.sleep(20)
        }
        runBlocking(ui) { controller.destroy() }
        controller.awaitSaved()
        return Run(messages.toList(), fake.requests.size - requestsBefore)
    }

    private fun firstAt(run: Run, type: String, minBars: Int = 1): Long? = run.messages.firstOrNull { (_, m) ->
        m.optString("type") == type && m.optString("mode") == "set" && when (type) {
            "funding" -> m.getJSONObject("series").getJSONArray("binance").length() >= minBars
            else -> m.getJSONArray("bars").length() >= minBars
        }
    }?.first

    private fun report(label: String, run: Run, bars: Int) {
        println(
            "$label: price ${firstAt(run, "price")} ms, first OI ${firstAt(run, "oi")} ms, full OI ${firstAt(run, "oi", bars)} ms, " +
                "first funding ${firstAt(run, "funding")} ms, full funding ${firstAt(run, "funding", bars)} ms, requests ${run.requests}",
        )
    }

    private fun complete(bars: Int) = { msgs: List<JSONObject> ->
        msgs.any { it.optString("type") == "oi" && it.optString("mode") == "set" && it.getJSONArray("bars").length() >= bars } &&
            msgs.any { it.optString("type") == "funding" && it.optString("mode") == "set" && it.getJSONObject("series").getJSONArray("binance").length() >= bars }
    }

    @Test
    fun switchingAfterPrefetchShowsCandlesAtOnce() {
        val settings = HashMap(mapOf("tf" to "1d"))
        val messages = CopyOnWriteArrayList<Pair<Long, JSONObject>>()
        val prefix = "window.chartApp&&chartApp.receive("
        val controller = ChartController(scope, apis, MapSettings(settings), dataDir, "test", pollIntervalMs = 5_000) { js ->
            messages += System.nanoTime() / 1_000_000 to JSONObject(js.substring(prefix.length, js.length - 2))
        }
        runBlocking(ui) {
            controller.onStart()
            controller.onPageReady()
        }
        // Wait for the first chart and the background refresh of the other timeframes' candles.
        val deadline = System.currentTimeMillis() + 60_000
        while (fake.requests.count { it == "/fapi/v1/klines" } < 9) {
            if (System.currentTimeMillis() > deadline) fail("no prefetch")
            Thread.sleep(20)
        }
        Thread.sleep(300)
        val switchedAt = System.nanoTime() / 1_000_000
        runBlocking(ui) { controller.setTimeframe("15m") }
        while (messages.none { (_, m) -> m.optString("type") == "price" && m.optString("mode") == "set" && m.getJSONArray("bars").length() > 0 && m.getJSONArray("bars").getJSONArray(1).getLong(0) - m.getJSONArray("bars").getJSONArray(0).getLong(0) == 900L }) {
            if (System.currentTimeMillis() > deadline) fail("no 15m candles")
            Thread.sleep(5)
        }
        val shownAt = messages.first { (_, m) -> m.optString("type") == "price" && m.optString("mode") == "set" && m.getJSONArray("bars").length() > 1 && m.getJSONArray("bars").getJSONArray(1).getLong(0) - m.getJSONArray("bars").getJSONArray(0).getLong(0) == 900L }.first
        println("15m after prefetch: candles ${shownAt - switchedAt} ms after switching")
        assertTrue(shownAt - switchedAt < 100, "took ${shownAt - switchedAt} ms")
        runBlocking(ui) { controller.destroy() }
    }

    @Test
    fun dailyChartColdAndWarm() {
        val bars = Timeframe.D1.historyBars
        val cold = launch("1d", complete(bars))
        report("1D first launch", cold, bars)
        val warm = launch("1d", complete(bars))
        report("1D next launch", warm, bars)
        File("build/e2e").mkdirs()
        File("build/e2e/warmStart1d.json").writeText(org.json.JSONArray(warm.messages.map { it.second }).toString())
        val hourly = launch("1h", complete(Timeframe.H1.historyBars))
        report("1h first time", hourly, Timeframe.H1.historyBars)
    }
}
