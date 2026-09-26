package com.tryagain2019.androidplot

import com.tryagain2019.androidplot.model.DAY
import com.tryagain2019.androidplot.model.HOUR
import com.tryagain2019.androidplot.model.MINUTE
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Collections
import java.util.Timer
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.fixedRateTimer
import kotlin.math.cos
import kotlin.math.sin

/**
 * A stand-in for the Binance, Bybit, OKX and Hyperliquid endpoints the app uses, following their
 * documented response formats and limits (page sizes, Binance's 30-day OI window, OKX's 1440
 * snapshots per period and 3 months of funding, newest-first ordering, ...). Values are smooth
 * functions of time so tests can check the aggregation.
 */
class FakeExchanges(
    private val clock: () -> Long = System::currentTimeMillis,
    /** Added to every response, like a phone's round trip to the exchange. */
    private val latencyMs: Long = 0,
) : Dispatcher() {
    val requests: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** Paths answered with this status code instead of data (to simulate outages or geo-blocks). */
    val failures: MutableMap<String, Int> = Collections.synchronizedMap(HashMap())
    val archiveRequests = AtomicInteger()
    private val timers = Collections.synchronizedList(ArrayList<Timer>())
    private val sockets = Collections.synchronizedList(ArrayList<WebSocket>())

    companion object {
        fun price(t: Long) = 80_000.0 + 6_000 * sin(t / (20.0 * DAY)) + 300 * sin(t / (3.0 * HOUR))
        fun binanceOi(t: Long) = 100_000.0 + 3_000 * sin(t / (3.0 * DAY))
        fun bybitOi(t: Long) = 60_000.0 + 1_500 * cos(t / (2.0 * DAY))
        fun okxOi(t: Long) = 30_000.0 + 700 * sin(t / (1.0 * DAY))
        const val HL_OI = 35_000.0
        const val BINANCE_FUNDING = 0.0001
        const val BYBIT_FUNDING = 0.00008
        const val OKX_FUNDING = 0.00009
        const val HL_FUNDING = 0.0000125
        val ARCHIVE_START: LocalDate = LocalDate.of(2021, 12, 1)

        private val intervals = mapOf(
            "1m" to MINUTE, "3m" to 3 * MINUTE, "5m" to 5 * MINUTE, "15m" to 15 * MINUTE, "30m" to 30 * MINUTE,
            "1h" to HOUR, "2h" to 2 * HOUR, "4h" to 4 * HOUR, "6h" to 6 * HOUR, "12h" to 12 * HOUR, "1d" to DAY, "1w" to 7 * DAY,
        )
        private val bybitIntervals = mapOf("5min" to 5 * MINUTE, "15min" to 15 * MINUTE, "30min" to 30 * MINUTE, "1h" to HOUR, "4h" to 4 * HOUR, "1d" to DAY)
        private val okxPeriods = mapOf("5m" to 5 * MINUTE, "15m" to 15 * MINUTE, "30m" to 30 * MINUTE, "1H" to HOUR, "2H" to 2 * HOUR, "4H" to 4 * HOUR, "1Dutc" to DAY)
    }

    fun stopStreams() {
        synchronized(timers) { timers.forEach { it.cancel() } }
        synchronized(sockets) { sockets.forEach { runCatching { it.close(1001, null) } } }
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val response = respond(request)
        if (latencyMs > 0 && !request.path.orEmpty().startsWith("/ws/")) response.setHeadersDelay(latencyMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return response
    }

    private fun respond(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        val path = url.encodedPath
        requests += path
        fun q(name: String) = url.queryParameter(name)
        fun ql(name: String) = q(name)?.toLong()
        val now = clock()
        failures[path]?.let { code ->
            // -1: drop the connection, like a network outage.
            if (code == -1) return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
            return MockResponse().setResponseCode(code).setBody("""{"code":0,"msg":"Service unavailable from a restricted location"}""")
        }
        return try {
            when {
                path == "/fapi/v1/klines" -> json(binanceKlines(q("interval")!!, q("limit")!!.toInt(), ql("endTime"), now))
                path == "/futures/data/openInterestHist" -> binanceOiHist(q("period")!!, ql("startTime")!!, ql("endTime")!!, q("limit")!!.toInt(), now)
                path == "/fapi/v1/openInterest" -> json("""{"openInterest":"${fmt(binanceOi(now))}","symbol":"BTCUSDT","time":$now}""")
                path == "/fapi/v1/premiumIndex" -> json(
                    """{"symbol":"BTCUSDT","markPrice":"${fmt(price(now))}","lastFundingRate":"$BINANCE_FUNDING","interestRate":"0.00010000",
                    "nextFundingTime":${ceilTo(now, 8 * HOUR)},"time":$now}""",
                )
                path == "/fapi/v1/fundingRate" -> json(binanceFunding(ql("startTime")!!, ql("endTime")!!, q("limit")!!.toInt(), now))
                path.startsWith("/data/futures/um/daily/metrics/BTCUSDT/") -> archive(path, now)
                path == "/v5/market/open-interest" -> json(bybitOi(q("intervalTime")!!, ql("startTime")!!, ql("endTime")!!, q("limit")!!.toInt(), now))
                path == "/v5/market/funding/history" -> json(bybitFunding(ql("endTime")!!, q("limit")!!.toInt(), now))
                path == "/v5/market/tickers" -> json(
                    """{"retCode":0,"retMsg":"OK","result":{"category":"linear","list":[{"symbol":"BTCUSDT","lastPrice":"${fmt(price(now))}",
                    "openInterest":"${fmt(bybitOi(now))}","fundingRate":"$BYBIT_FUNDING","nextFundingTime":"${ceilTo(now, 8 * HOUR)}"}]},"retExtInfo":{},"time":$now}""",
                )
                path == "/api/v5/rubik/stat/contracts/open-interest-history" -> okxOiHistory(q("period")!!, ql("begin")!!, ql("end")!!, q("limit")!!.toInt(), now)
                path == "/api/v5/public/open-interest" -> json(
                    """{"code":"0","data":[{"instType":"SWAP","instId":"BTC-USDT-SWAP","oi":"${fmt(okxOi(now) * 100)}","oiCcy":"${fmt(okxOi(now))}","ts":"$now"}],"msg":""}""",
                )
                path == "/api/v5/public/funding-rate" -> json(
                    """{"code":"0","data":[{"fundingRate":"$OKX_FUNDING","fundingTime":"${ceilTo(now, 8 * HOUR)}","instId":"BTC-USDT-SWAP","ts":"$now"}],"msg":""}""",
                )
                path == "/api/v5/public/funding-rate-history" -> json(okxFunding(ql("after"), q("limit")!!.toInt(), now))
                path == "/info" -> hyperliquid(request.body.clone().readUtf8(), now)
                path.startsWith("/ws/") -> stream(path.removePrefix("/ws/"))
                else -> MockResponse().setResponseCode(404)
            }
        } catch (e: Exception) {
            MockResponse().setResponseCode(500).setBody("fake exchange error: $e")
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.8f", v)

    private fun ceilTo(t: Long, step: Long) = t - Math.floorMod(t, step) + step

    private fun barsFor(interval: String, step: Long, limit: Int, endTime: Long?, now: Long): List<Long> {
        val last = minOf(endTime ?: now, now)
        val lastOpen = if (interval == "1M") monthStart(last) else last - Math.floorMod(last - if (interval == "1w") 4 * DAY else 0, step)
        val out = ArrayList<Long>()
        var t = lastOpen
        val listing = Instant.parse("2019-09-08T00:00:00Z").toEpochMilli()
        while (out.size < limit && t >= listing) {
            out += t
            t = if (interval == "1M") monthStart(t - 1) else t - step
        }
        return out.reversed()
    }

    private fun monthStart(t: Long): Long {
        val d = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1)
        return d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    private fun binanceKlines(interval: String, limit: Int, endTime: Long?, now: Long): String {
        require(limit <= 1500)
        val step = intervals[interval] ?: 30 * DAY
        return barsFor(interval, step, limit, endTime, now).joinToString(",", "[", "]") { t ->
            val o = price(t)
            val c = price(minOf(t + step, now))
            "[$t,\"${fmt(o)}\",\"${fmt(maxOf(o, c) + 50)}\",\"${fmt(minOf(o, c) - 50)}\",\"${fmt(c)}\",\"1\",${t + step - 1},\"1\",1,\"1\",\"1\",\"0\"]"
        }
    }

    private fun gridTimes(start: Long, end: Long, step: Long): List<Long> {
        val out = ArrayList<Long>()
        var t = start - Math.floorMod(start, step)
        if (t < start) t += step
        while (t <= end) {
            out += t
            t += step
        }
        return out
    }

    private fun binanceOiHist(period: String, start: Long, end: Long, limit: Int, now: Long): MockResponse {
        require(limit <= 500)
        if (start < now - 30 * DAY) {
            return MockResponse().setResponseCode(400).setBody("""{"code":-1130,"msg":"Data sent for parameter 'startTime' is not valid."}""")
        }
        val step = intervals.getValue(period)
        val times = gridTimes(start, minOf(end, now), step).takeLast(limit)
        return json(times.joinToString(",", "[", "]") { t ->
            """{"symbol":"BTCUSDT","sumOpenInterest":"${fmt(binanceOi(t))}","sumOpenInterestValue":"1","timestamp":$t}"""
        })
    }

    private fun binanceFunding(start: Long, end: Long, limit: Int, now: Long): String {
        require(limit <= 1000)
        val times = gridTimes(start, minOf(end, now), 8 * HOUR).take(limit)
        return times.joinToString(",", "[", "]") { """{"symbol":"BTCUSDT","fundingRate":"$BINANCE_FUNDING","fundingTime":$it,"markPrice":"1"}""" }
    }

    private fun archive(path: String, now: Long): MockResponse {
        archiveRequests.incrementAndGet()
        val date = LocalDate.parse(path.substringAfterLast("BTCUSDT-metrics-").removeSuffix(".zip"))
        val today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        if (date.isBefore(ARCHIVE_START) || !date.isBefore(today)) return MockResponse().setResponseCode(404)
        val dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val csv = StringBuilder("create_time,symbol,sum_open_interest,sum_open_interest_value,count_toptrader_long_short_ratio,sum_toptrader_long_short_ratio,count_long_short_ratio,sum_taker_long_short_vol_ratio\n")
        for (i in 0 until 288) {
            val t = dayStart + i * 5 * MINUTE
            val time = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC)
            csv.append(String.format(java.util.Locale.US, "%tF %<tT", time)).append(",BTCUSDT,").append(fmt(binanceOi(t))).append(",1,1,1,1,1\n")
        }
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use {
            it.putNextEntry(ZipEntry("BTCUSDT-metrics-$date.csv"))
            it.write(csv.toString().toByteArray())
            it.closeEntry()
        }
        return MockResponse().setHeader("Content-Type", "application/zip").setBody(Buffer().write(zip.toByteArray()))
    }

    private fun bybitOi(interval: String, start: Long, end: Long, limit: Int, now: Long): String {
        require(limit <= 200)
        val step = bybitIntervals.getValue(interval)
        val listing = Instant.parse("2020-03-25T00:00:00Z").toEpochMilli()
        val times = gridTimes(maxOf(start, listing), minOf(end, now), step).takeLast(limit).reversed()
        val list = times.joinToString(",", "[", "]") { """{"openInterest":"${fmt(bybitOi(it))}","timestamp":"$it"}""" }
        return """{"retCode":0,"retMsg":"OK","result":{"symbol":"BTCUSDT","category":"linear","list":$list,"nextPageCursor":""},"retExtInfo":{},"time":$now}"""
    }

    private fun bybitFunding(end: Long, limit: Int, now: Long): String {
        require(limit <= 200)
        val listing = Instant.parse("2020-03-25T00:00:00Z").toEpochMilli()
        val times = gridTimes(listing, minOf(end, now), 8 * HOUR).takeLast(limit).reversed()
        val list = times.joinToString(",", "[", "]") { """{"symbol":"BTCUSDT","fundingRate":"$BYBIT_FUNDING","fundingRateTimestamp":"$it"}""" }
        return """{"retCode":0,"retMsg":"OK","result":{"category":"linear","list":$list},"retExtInfo":{},"time":$now}"""
    }

    private fun okxOiHistory(period: String, begin: Long, end: Long, limit: Int, now: Long): MockResponse {
        require(limit <= 100)
        val step = okxPeriods[period] ?: return json("""{"code":"51000","msg":"Parameter period error","data":[]}""")
        val depth = now - 1440 * step
        val rows = gridTimes(maxOf(begin, depth), minOf(end, now), step).takeLast(limit).reversed()
            .joinToString(",", "[", "]") { """["$it","${fmt(okxOi(it) * 100)}","${fmt(okxOi(it))}","1"]""" }
        return json("""{"code":"0","msg":"","data":$rows}""")
    }

    private fun okxFunding(after: Long?, limit: Int, now: Long): String {
        require(limit <= 100)
        val times = gridTimes(now - 92 * DAY, minOf((after ?: (now + 1)) - 1, now), 8 * HOUR).takeLast(limit).reversed()
        val data = times.joinToString(",", "[", "]") {
            """{"fundingRate":"$OKX_FUNDING","fundingTime":"$it","instId":"BTC-USDT-SWAP","instType":"SWAP","method":"current_period","realizedRate":"$OKX_FUNDING"}"""
        }
        return """{"code":"0","msg":"","data":$data}"""
    }

    private fun hyperliquid(body: String, now: Long): MockResponse {
        val req = JSONObject(body)
        return when (req.getString("type")) {
            "metaAndAssetCtxs" -> json(
                """[{"universe":[{"name":"ETH","szDecimals":4,"maxLeverage":25},{"name":"BTC","szDecimals":5,"maxLeverage":40}]},
                [{"funding":"0.00001","openInterest":"1000","markPx":"2500"},{"funding":"$HL_FUNDING","openInterest":"${fmt(HL_OI)}","markPx":"${fmt(price(now))}"}]]""",
            )
            "fundingHistory" -> {
                val start = req.getLong("startTime")
                val end = if (req.has("endTime")) req.getLong("endTime") else now
                val launch = Instant.parse("2023-05-12T00:00:00Z").toEpochMilli()
                val times = gridTimes(maxOf(start, launch), minOf(end, now), HOUR).take(500)
                json(times.joinToString(",", "[", "]") { """{"coin":"BTC","fundingRate":"$HL_FUNDING","premium":"0.0001","time":${it + 76}}""" })
            }
            else -> MockResponse().setResponseCode(422).setBody("Failed to deserialize the JSON body")
        }
    }

    /** Kline stream: pushes an update of the current bar every 200 ms. */
    private fun stream(name: String): MockResponse {
        val interval = name.substringAfter("@kline_")
        val step = intervals[interval] ?: 30 * DAY
        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sockets += webSocket
                timers += fixedRateTimer(daemon = true, period = 200) {
                    val now = clock()
                    val t = if (interval == "1M") monthStart(now) else now - Math.floorMod(now - if (interval == "1w") 4 * DAY else 0, step)
                    val o = price(t)
                    val c = price(now)
                    val msg = """{"e":"kline","E":$now,"s":"BTCUSDT","k":{"t":$t,"T":${t + step - 1},"s":"BTCUSDT","i":"$interval","o":"${fmt(o)}",
                        "c":"${fmt(c)}","h":"${fmt(maxOf(o, c) + 50)}","l":"${fmt(minOf(o, c) - 50)}","v":"1","n":1,"x":false,"q":"1","V":"1","Q":"1","B":"0"}}"""
                    if (!webSocket.send(msg)) cancel()
                }
            }
        })
    }
}
