package com.tryagain2019.androidplot

import com.tryagain2019.androidplot.data.BookStore
import com.tryagain2019.androidplot.data.HeatBuilder
import com.tryagain2019.androidplot.model.BookMarket
import com.tryagain2019.androidplot.model.BookSnapshot
import com.tryagain2019.androidplot.model.DAY
import com.tryagain2019.androidplot.model.MINUTE
import com.tryagain2019.androidplot.model.Timeframe
import com.tryagain2019.androidplot.net.ApiException
import com.tryagain2019.androidplot.net.BookParsers
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeatmapTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun t(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun near(expected: Double, actual: Double, relative: Double = 0.001) =
        assertTrue(Math.abs(expected - actual) <= relative * Math.abs(expected), "expected $expected, got $actual")

    /** A book with [bid] BTC in every $10 bin below [mid] and [ask] in every bin above, [bins] deep. */
    private fun book(time: Long, mid: Double, bid: Float, ask: Float, bins: Int = 40): BookSnapshot {
        val bidTop = BookSnapshot.bin(mid - 0.01)
        val askBottom = BookSnapshot.bin(mid + 0.01)
        return BookSnapshot(time, mid, bidTop, FloatArray(bins) { bid }, askBottom, FloatArray(bins) { ask })
    }

    private fun decode(data: ByteArray) = decodeHeat(data)

    @Test
    fun snapshotSumsLevelsIntoTenDollarBinsAndDropsFarOnes() {
        val s = BookSnapshot.fromLevels(
            time = 1,
            bidPrices = doubleArrayOf(84498.00, 84495.50, 84489.99, 70000.0),
            bidQuantities = doubleArrayOf(1.0, 2.0, 3.0, 500.0),
            askPrices = doubleArrayOf(84498.01, 84500.00, 84512.00, 99000.0),
            askQuantities = doubleArrayOf(0.5, 4.0, 1.0, 500.0),
        )!!
        near(84498.005, s.mid)
        assertEquals(8449, s.bidTop)
        assertContentEquals(floatArrayOf(3f, 3f), s.bids) // 84490-84500, 84480-84490; 70000 is >10 % away
        assertEquals(8449, s.askBottom)
        assertContentEquals(floatArrayOf(0.5f, 4f, 1f), s.asks)
        near(84480.0, s.low)
        near(84520.0, s.high)
        assertNull(BookSnapshot.fromLevels(1, doubleArrayOf(), doubleArrayOf(), doubleArrayOf(1.0), doubleArrayOf(1.0)))
    }

    @Test
    fun parsesSpotAndFuturesDepth() {
        val spot = BookParsers.depth(
            """{"lastUpdateId":1027024,"bids":[["84498.00000000","1.20000000"],["84480.10000000","0.00012000"]],
            "asks":[["84498.01000000","0.50000000"],["84530.00000000","12.00000000"]]}""",
            time = 42,
        )
        assertEquals(42, spot.time)
        assertEquals(8449, spot.bidTop)
        near(1.2, spot.bids[0].toDouble())
        near(0.00012, spot.bids[1].toDouble())
        near(12.0, spot.asks[4].toDouble()) // 84530 is four $10 bins above the best ask
        val futures = BookParsers.depth(
            """{"lastUpdateId":1027024,"E":1589436922972,"T":1589436922959,"bids":[["84498.1","31.5"]],"asks":[["84498.2","4.1"]]}""",
            time = 7,
        )
        near(31.5, futures.bids.single().toDouble())
        val e = assertFailsWith<ApiException> { BookParsers.depth("""{"code":-1121,"msg":"Invalid symbol."}""", 0) }
        assertEquals("Invalid symbol.", e.message)
    }

    @Test
    fun storeRoundTripsSnapshotsAndCutsOffAHalfWrittenRecord() {
        val dir = tmp.newFolder("book")
        val day = t("2026-09-20T00:00:00Z")
        val a = book(day + 10 * MINUTE, 84_500.0, 1.5f, 0.75f)
        val b = book(day + 11 * MINUTE, 84_600.0, 2.0f, 3.0f)
        BookStore(dir).apply {
            append(BookMarket.SPOT, a)
            append(BookMarket.SPOT, b)
        }
        val read = ArrayList<BookSnapshot>()
        BookStore(dir).read(BookMarket.SPOT, day, day + DAY) { read += it }
        assertEquals(listOf(a.time, b.time), read.map { it.time })
        assertEquals(b.bidTop, read[1].bidTop)
        assertEquals(b.askBottom, read[1].askBottom)
        near(2.0, read[1].bids[5].toDouble())
        near(3.0, read[1].asks[39].toDouble())

        // The app was killed half way through writing b.
        val file = File(dir, "spot/2026-09-20.bin")
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 7) }
        val store = BookStore(dir)
        val c = book(day + 12 * MINUTE, 84_700.0, 1.0f, 1.0f)
        store.append(BookMarket.SPOT, c)
        read.clear()
        store.read(BookMarket.SPOT, day, day + DAY) { read += it }
        assertEquals(listOf(a.time, c.time), read.map { it.time })
        assertEquals(a.time, store.firstTime(BookMarket.SPOT))
        assertEquals(c.time, store.lastTime(BookMarket.SPOT))
        assertNull(store.firstTime(BookMarket.FUTURES))
    }

    @Test
    fun storeKeepsAboutAMonth() {
        val dir = tmp.newFolder("book")
        val now = t("2026-09-27T12:00:00Z")
        val store = BookStore(dir)
        store.append(BookMarket.SPOT, book(now - 40 * DAY, 60_000.0, 1f, 1f))
        store.append(BookMarket.SPOT, book(now - 20 * DAY, 70_000.0, 1f, 1f))
        store.append(BookMarket.SPOT, book(now, 80_000.0, 1f, 1f))
        assertEquals(listOf("2026-09-07.bin", "2026-09-27.bin"), File(dir, "spot").list()!!.sorted())
        assertEquals(now - 20 * DAY, store.firstTime(BookMarket.SPOT))
    }

    @Test
    fun barsAverageTheBookOverTheTimeItWasRecorded() {
        val h = HeatBuilder(Timeframe.H1, t("2026-09-20T00:00:00Z"))
        // 10:00 -> 10:30 two BTC per $10 bin, 10:30 -> 11:00 four BTC.
        h.add(book(t("2026-09-20T10:00:00Z"), 84_500.0, 2f, 1f))
        h.add(book(t("2026-09-20T10:30:00Z"), 84_500.0, 4f, 1f))
        h.add(book(t("2026-09-20T11:00:00Z"), 84_500.0, 4f, 1f))
        val bar = decode(h.encodeAll(t("2026-09-20T11:00:00Z"))).first { it.time == t("2026-09-20T10:00:00Z") }
        // A 1h bin is $20 wide, so it holds two $10 bins: (2 * 30 + 4 * 30) / 60 * 2 = 6 BTC.
        near(6.0, HeatBuilder.quantity(bar.bids[1]), 0.05)
        assertEquals(HeatBuilder.code(6.0), bar.bids[1])
        assertEquals(84_480 / 20, bar.bidTop) // best bid 84499.99 is in the $20 bin starting at 84480
        assertEquals(HeatBuilder.code(2.0), bar.asks[1]) // 1 BTC per $10 bin
    }

    @Test
    fun recordingGapsStayEmpty() {
        val h = HeatBuilder(Timeframe.M5, t("2026-09-20T00:00:00Z"))
        h.add(book(t("2026-09-20T10:00:00Z"), 84_500.0, 1f, 1f))
        h.add(book(t("2026-09-20T11:00:00Z"), 84_500.0, 1f, 1f)) // the phone was off for an hour
        val times = decode(h.encodeAll(t("2026-09-20T11:02:00Z"))).map { it.time }
        val expected = (0 until 6).map { t("2026-09-20T10:00:00Z") + it * 5 * MINUTE } + t("2026-09-20T11:00:00Z")
        assertEquals(expected, times) // each snapshot counts for 30 minutes at most
    }

    @Test
    fun liveUpdatesGiveTheSameBarsAsARebuild() {
        val start = t("2026-09-20T09:58:00Z")
        val snapshots = (0 until 40).map { i ->
            val jitter = (i * 7919L) % 20_000
            book(start + i * MINUTE + jitter, 84_000.0 + 10 * i, (1 + i % 5).toFloat(), (6 - i % 5).toFloat())
        }
        val live = HeatBuilder(Timeframe.M5, t("2026-09-20T00:00:00Z"))
        val seen = HashMap<Long, List<Int>>()
        for (s in snapshots) {
            val changed = live.add(s)
            for (bar in decode(live.encodeBars(changed, s.time))) seen[bar.time] = bar.bids.toList() + bar.asks.toList()
        }
        val now = snapshots.last().time
        val rebuilt = HeatBuilder(Timeframe.M5, t("2026-09-20T00:00:00Z"))
        snapshots.forEach { rebuilt.add(it) }
        val all = decode(rebuilt.encodeAll(now))
        assertEquals(all.map { it.time }, seen.keys.sorted())
        for (bar in all) assertEquals(bar.bids.toList() + bar.asks.toList(), seen[bar.time], "bar ${bar.time}")
    }

    @Test
    fun dailyBinsSumTenDollarBins() {
        val h = HeatBuilder(Timeframe.D1, t("2026-09-01T00:00:00Z"))
        h.add(book(t("2026-09-20T10:00:00Z"), 84_550.0, 1f, 1f, bins = 300))
        val bar = decode(h.encodeAll(t("2026-09-20T10:20:00Z"))).single()
        assertEquals(t("2026-09-20T00:00:00Z"), bar.time)
        // $100 bins: the top one (84500-84600) holds five $10 bins below the mid, the next ones ten.
        assertEquals(845, bar.bidTop)
        assertEquals(HeatBuilder.code(5.0), bar.bids[0])
        assertEquals(HeatBuilder.code(10.0), bar.bids[1])
        assertEquals(845, bar.askBottom)
    }

    @Test
    fun currentBookIsTheLatestSnapshotInTheTimeframesBins() {
        val h = HeatBuilder(Timeframe.H1, t("2026-09-20T00:00:00Z"))
        assertNull(h.encodeCurrent())
        h.add(book(t("2026-09-20T10:00:00Z"), 84_500.0, 2f, 1f))
        h.add(book(t("2026-09-20T10:40:00Z"), 84_500.0, 3f, 1.5f))
        val current = decode(h.encodeCurrent()!!).single()
        assertEquals(t("2026-09-20T10:40:00Z"), current.time) // the snapshot's own time, not a bar's
        assertEquals(84_480 / 20, current.bidTop)
        assertEquals(HeatBuilder.code(6.0), current.bids[1]) // two $10 bins of 3 BTC, not averaged with the older snapshot
        assertEquals(HeatBuilder.code(3.0), current.asks[1])
    }

    @Test
    fun loadsFromTheStoreAndSkipsWhatIsOutsideTheWindow() {
        val dir = tmp.newFolder("book")
        val store = BookStore(dir)
        val now = t("2026-09-20T12:00:00Z")
        store.append(BookMarket.SPOT, book(now - 2 * DAY, 84_000.0, 1f, 1f)) // before the 1m window (1 day)
        store.append(BookMarket.SPOT, book(now - 10 * MINUTE, 84_000.0, 1f, 1f))
        store.append(BookMarket.SPOT, book(now - 5 * MINUTE, 84_000.0, 2f, 1f))
        val h = HeatBuilder.load(store, BookMarket.SPOT, Timeframe.M1, now)
        val bars = decode(h.encodeAll(now))
        assertEquals((0 until 10).map { now - 10 * MINUTE + it * MINUTE }, bars.map { it.time })
        assertEquals(HeatBuilder.code(2.0), bars.last().bids[1])
    }
}
