package com.tryagain2019.androidplot

import com.tryagain2019.androidplot.net.ApiException
import com.tryagain2019.androidplot.net.BinanceArchive
import com.tryagain2019.androidplot.net.BinanceParsers
import com.tryagain2019.androidplot.net.BybitParsers
import com.tryagain2019.androidplot.net.HyperliquidParsers
import com.tryagain2019.androidplot.net.OkxParsers
import com.tryagain2019.androidplot.net.errorDetail
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Payloads follow the exchanges' documented response formats. */
class ParsersTest {
    @Test
    fun binanceKlines() {
        val json = """[[1758499200000,"84355.1","87012.0","83950.2","84705.4","151234.5",1758585599999,"12800000000.1",2400000,"75000.1","6300000000.2","0"],
            [1758412800000,"84000.0","84500.0","83800.0","84355.1","100.0",1758499199999,"1.0",10,"1","1","0"]]"""
        val bars = BinanceParsers.klines(json)
        assertEquals(2, bars.size)
        assertEquals(1758412800000, bars[0].time) // sorted ascending
        assertEquals(84705.4, bars[1].close)
        assertEquals(87012.0, bars[1].high)
        assertEquals(151234.5, bars[1].volume)
    }

    @Test
    fun binanceOpenInterestHistAcceptsNumericAndStringTimestamps() {
        val json = """[{"symbol":"BTCUSDT","sumOpenInterest":"20403.63700000","sumOpenInterestValue":"150570784.07809979","CMCCirculatingSupply":"165880.538","timestamp":1583127900000},
            {"symbol":"BTCUSDT","sumOpenInterest":"20401.36700000","sumOpenInterestValue":"149940752.14464448","timestamp":"1583128200000"}]"""
        val s = BinanceParsers.openInterestHist(json)
        assertEquals(listOf(1583127900000, 1583128200000), s.map { it.time })
        assertEquals(20403.637, s[0].value)
    }

    @Test
    fun binanceErrorObjectIsReported() {
        val e = assertFailsWith<ApiException> { BinanceParsers.openInterestHist("""{"code":-1130,"msg":"Data sent for parameter 'startTime' is not valid."}""") }
        assertEquals("Data sent for parameter 'startTime' is not valid.", e.message)
    }

    @Test
    fun binanceCurrentValues() {
        assertEquals(10659.509, BinanceParsers.openInterest("""{"openInterest":"10659.509","symbol":"BTCUSDT","time":1589437530011}""").value)
        val f = BinanceParsers.premiumIndex(
            """{"symbol":"BTCUSDT","markPrice":"11793.63104562","indexPrice":"11781.80495970","estimatedSettlePrice":"11781.16138815",
            "lastFundingRate":"0.00038246","interestRate":"0.00010000","nextFundingTime":1597392000000,"time":1597370495002}""",
        )
        assertEquals(0.00038246, f.rate)
        assertEquals(1597392000000, f.nextFundingTime)
        val events = BinanceParsers.fundingRates("""[{"symbol":"BTCUSDT","fundingRate":"-0.03750000","fundingTime":1570608000000,"markPrice":"34287.54619963"}]""")
        assertEquals(-0.0375, events.single().rate)
    }

    @Test
    fun binanceStreamKline() {
        val raw = """{"e":"kline","E":1638747660000,"s":"BTCUSDT","k":{"t":1638747660000,"T":1638747719999,"s":"BTCUSDT","i":"1m","f":100,"L":200,
            "o":"0.0010","c":"0.0020","h":"0.0025","l":"0.0015","v":"1000","n":100,"x":false,"q":"1.0000","V":"500","Q":"0.500","B":"123456"}}"""
        val c = BinanceParsers.wsKline(raw)!!
        assertEquals(1638747660000, c.time)
        assertEquals(0.0025, c.high)
        assertEquals(1000.0, c.volume)
        val combined = """{"stream":"btcusdt@kline_1m","data":$raw}"""
        assertEquals(c, BinanceParsers.wsKline(combined))
        assertNull(BinanceParsers.wsKline("""{"result":null,"id":1}"""))
    }

    @Test
    fun binanceArchiveCsv() {
        val csv = """create_time,symbol,sum_open_interest,sum_open_interest_value,count_toptrader_long_short_ratio,sum_toptrader_long_short_ratio,count_long_short_ratio,sum_taker_long_short_vol_ratio
            |2026-04-01 00:00:00,BTCUSDT,98765.43200000,6543210987.65,1.6,1.7,1.5,0.98
            |2026-04-01 00:05:00,BTCUSDT,98770.00000000,6543310987.65,1.6,1.7,1.5,0.98
            |""".trimMargin()
        val s = BinanceArchive.parseCsv(csv)
        assertEquals(2, s.size)
        assertEquals(1775001600000, s[0].time) // 2026-04-01T00:00:00Z
        assertEquals(98765.432, s[0].value)
        // Header-less file with epoch timestamps.
        val s2 = BinanceArchive.parseCsv("1775001900000,BTCUSDT,98770.0,1.0,1,1,1,1\n")
        assertEquals(1775001900000, s2.single().time)
        // Zipped like the real archive.
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("BTCUSDT-metrics-2026-04-01.csv"))
                zip.write(csv.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        assertEquals(s, BinanceArchive.parseZip(bytes))
    }

    @Test
    fun bybit() {
        val oi = BybitParsers.openInterest(
            """{"retCode":0,"retMsg":"OK","result":{"symbol":"BTCUSDT","category":"linear","list":[
            {"openInterest":"52000.123","timestamp":"1669571700000"},{"openInterest":"51999.5","timestamp":"1669571400000"}],
            "nextPageCursor":"lastid%3D123"},"retExtInfo":{},"time":1672053548579}""",
        )
        assertEquals(listOf(1669571400000, 1669571700000), oi.map { it.time })
        val ticker = BybitParsers.ticker(
            """{"retCode":0,"retMsg":"OK","result":{"category":"linear","list":[{"symbol":"BTCUSDT","lastPrice":"84705.40","indexPrice":"84690.1",
            "markPrice":"84701.00","openInterest":"58123.456","openInterestValue":"4923000000.12","fundingRate":"-0.00000800","nextFundingTime":"1758528000000"}]},
            "retExtInfo":{},"time":1758520000000}""",
        )
        assertEquals(58123.456, ticker.openInterest)
        assertEquals(-0.000008, ticker.funding?.rate)
        assertEquals(1758528000000, ticker.funding?.nextFundingTime)
        val funding = BybitParsers.fundingHistory(
            """{"retCode":0,"retMsg":"OK","result":{"category":"linear","list":[{"symbol":"BTCUSDT","fundingRate":"0.0001","fundingRateTimestamp":"1672041600000"},
            {"symbol":"BTCUSDT","fundingRate":"0.00005","fundingRateTimestamp":"1672012800000"}]},"retExtInfo":{},"time":1672051897447}""",
        )
        assertEquals(listOf(1672012800000, 1672041600000), funding.map { it.time })
        assertFailsWith<ApiException> { BybitParsers.ticker("""{"retCode":10001,"retMsg":"params error","result":{}}""") }
    }

    @Test
    fun okx() {
        val oi = OkxParsers.openInterestHistory("""{"code":"0","msg":"","data":[["1701417600000","3123456.5","31234.565","2645000000"],["1701403200000","3100000","31000","2600000000"]]}""")
        assertEquals(listOf(1701403200000, 1701417600000), oi.map { it.time })
        assertEquals(31234.565, oi[1].value)
        val current = OkxParsers.openInterest("""{"code":"0","data":[{"instType":"SWAP","instId":"BTC-USDT-SWAP","oi":"3123456.5","oiCcy":"31234.565","oiUsd":"2645000000","ts":"1597026383085"}],"msg":""}""")
        assertEquals(31234.565, current.value)
        val rate = OkxParsers.fundingRate(
            """{"code":"0","data":[{"fundingRate":"0.0000380000000000","fundingTime":"1758528000000","instId":"BTC-USDT-SWAP","instType":"SWAP",
            "method":"current_period","maxFundingRate":"0.00375","minFundingRate":"-0.00375","nextFundingRate":"","nextFundingTime":"1758556800000",
            "premium":"0.0001","settFundingRate":"0.0000412","settState":"settled","ts":"1758520000000"}],"msg":""}""",
        )
        assertEquals(0.000038, rate.rate)
        assertEquals(1758528000000, rate.nextFundingTime)
        val history = OkxParsers.fundingRateHistory(
            """{"code":"0","msg":"","data":[{"fundingRate":"0.0000746572464779","fundingTime":"1703059200000","instId":"BTC-USDT-SWAP","instType":"SWAP",
            "method":"current_period","realizedRate":"0.0000746572464779"}]}""",
        )
        assertEquals(0.0000746572464779, history.single().rate)
        val e = assertFailsWith<ApiException> { OkxParsers.openInterestHistory("""{"code":"51000","msg":"Parameter period error","data":[]}""") }
        assertEquals("OKX: Parameter period error (51000)", e.message)
    }

    @Test
    fun hyperliquid() {
        val json = """[{"universe":[{"name":"ETH","szDecimals":4,"maxLeverage":25},{"name":"BTC","szDecimals":5,"maxLeverage":40}],"marginTables":[]},
            [{"dayNtlVlm":"1.0","funding":"0.0000125","impactPxs":["1","2"],"markPx":"2500.1","midPx":"2500.0","openInterest":"500000.1","oraclePx":"2500","premium":"0.0001","prevDayPx":"2400"},
             {"dayNtlVlm":"2.0","funding":"0.00000575","impactPxs":["84700","84701"],"markPx":"84705.0","midPx":"84705.5","openInterest":"33456.789","oraclePx":"84690","premium":"0.00002","prevDayPx":"84000"}]]"""
        val now = 1758520000000
        val s = HyperliquidParsers.assetContext(json, "BTC", now)
        assertEquals(33456.789, s.openInterest)
        assertEquals(0.00000575, s.funding?.rate)
        assertEquals(1758520800000, s.funding?.nextFundingTime) // next full hour
        val history = HyperliquidParsers.fundingHistory("""[{"coin":"BTC","fundingRate":"0.0000125","premium":"0.0003","time":1683849600076}]""")
        assertEquals(1683849600076, history.single().time)
    }

    @Test
    fun errorDetails() {
        assertEquals("Service unavailable from a restricted location", errorDetail("""{"code":0,"msg":"Service unavailable from a restricted location"}"""))
        assertEquals("", errorDetail("<html>403 Forbidden</html>"))
    }

    @Test
    fun bybitOrderBook() {
        val json = """{"retCode":0,"retMsg":"OK","result":{"s":"BTCUSDT","a":[["84500.1","16.606"],["84512.0","2.5"]],"b":[["84499.9","47.08"],["84489.5","1.2"]],
            "ts":1716863719031,"u":230704,"seq":1432604333,"cts":1716863718905},"retExtInfo":{},"time":1716863719382}"""
        val s = BybitParsers.orderBook(json, 5)
        assertEquals(8449, s.bidTop)
        assertContentEquals(floatArrayOf(47.08f, 1.2f), s.bids)
        assertEquals(8450, s.askBottom)
        assertEquals(16.606f, s.asks[0])
        assertEquals(2.5f, s.asks[1])
        assertFailsWith<ApiException> { BybitParsers.orderBook("""{"retCode":10001,"retMsg":"params error","result":{}}""", 0) }
    }

    @Test
    fun okxOrderBookInContracts() {
        val json = """{"code":"0","msg":"","data":[{"asks":[["84500.1","1200","3"]],"bids":[["84499.9","250","5"],["84480.0","100","1"]],"ts":"1629966436396"}]}"""
        val s = OkxParsers.orderBook(json, 5, 0.01)
        assertEquals(8449, s.bidTop)
        assertEquals(2.5f, s.bids[0]) // 250 contracts of 0.01 BTC
        assertEquals(1.0f, s.bids[1])
        assertEquals(12.0f, s.asks[0])
        assertFailsWith<ApiException> { OkxParsers.orderBook("""{"code":"51001","msg":"Instrument ID does not exist","data":[]}""", 0, 0.01) }
    }

    @Test
    fun hyperliquidBookFromTwoGroupings() {
        // $10 levels near the price, $100 levels further out (bids rounded down, asks up).
        val fine = HyperliquidParsers.l2Book(
            """{"coin":"BTC","time":1754450974231,"levels":[
            [{"px":"84490.0","sz":"2.0","n":3},{"px":"84480.0","sz":"1.0","n":1}],
            [{"px":"84500.0","sz":"3.0","n":2},{"px":"84510.0","sz":"1.5","n":1}]]}""",
        )
        val coarse = HyperliquidParsers.l2Book(
            """{"coin":"BTC","time":1754450974231,"levels":[
            [{"px":"84400.0","sz":"23.0","n":9},{"px":"84300.0","sz":"10.0","n":4}],
            [{"px":"84500.0","sz":"3.0","n":2},{"px":"84600.0","sz":"11.5","n":5}]]}""",
        )
        assertEquals(10.0, HyperliquidParsers.step(84_490.0, 4))
        assertEquals(100.0, HyperliquidParsers.step(84_490.0, 3))
        val s = HyperliquidParsers.snapshot(9, fine, 4, coarse, 3)!!
        // Bids: 84490 and 84480 as sent; the 84400 level (23 BTC, of which the fine levels hold 3)
        // spreads the other 20 over 84400-84480 (8 bins of 2.5); 84300 spreads 10 over 10 bins.
        assertEquals(8449, s.bidTop)
        assertContentEquals(floatArrayOf(2f, 1f), s.bids.copyOfRange(0, 2))
        for (j in 2 until 10) assertEquals(2.5f, s.bids[j], "bin ${s.bidTop - j}")
        for (j in 10 until 20) assertEquals(1f, s.bids[j], 1e-6f)
        // Asks: level 84500 holds (84490, 84500], i.e. the 84490 bin; 84510 the 84500 bin. The
        // 84600 level, (84500, 84600], is 11.5 minus the fine 1.5, spread over its 9 bins above 84510.
        assertEquals(8449, s.askBottom)
        assertEquals(3f, s.asks[0])
        assertEquals(1.5f, s.asks[1])
        assertEquals(11, s.asks.size)
        for (j in 2 until 11) assertEquals(10f / 9, s.asks[j], 1e-5f)
    }
}
