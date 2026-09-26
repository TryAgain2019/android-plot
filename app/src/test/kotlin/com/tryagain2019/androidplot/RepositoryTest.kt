package com.tryagain2019.androidplot

import com.tryagain2019.androidplot.data.Fetched
import com.tryagain2019.androidplot.data.FundingRepository
import com.tryagain2019.androidplot.data.LiveOiRecorder
import com.tryagain2019.androidplot.data.OiRepository
import com.tryagain2019.androidplot.model.Exchange
import com.tryagain2019.androidplot.model.FundingEvent
import com.tryagain2019.androidplot.model.MINUTE
import com.tryagain2019.androidplot.model.Sample
import com.tryagain2019.androidplot.model.Timeframe
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals

class RepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun liveRoundsWithoutAnExchangeKeepItsLatestValue() {
        val repo = OiRepository(emptyMap())
        repo.recordLive(1_000_000, mapOf(Exchange.BINANCE to 1.0, Exchange.HYPERLIQUID to 5.0))
        repo.recordLive(1_005_000, mapOf(Exchange.BINANCE to 2.0)) // Hyperliquid not polled this round
        repo.recordLive(1_008_000, mapOf(Exchange.BINANCE to 3.0))
        assertEquals(mapOf(1_000_000L to 5.0), repo.samples(Exchange.HYPERLIQUID))
        // Binance points closer than 10 s replace each other.
        assertEquals(mapOf(1_008_000L to 3.0), repo.samples(Exchange.BINANCE))
        repo.recordLive(1_020_000, mapOf(Exchange.BINANCE to 4.0, Exchange.HYPERLIQUID to 6.0))
        assertEquals(listOf(1_008_000L, 1_020_000L), repo.samples(Exchange.BINANCE).keys.toList())
        assertEquals(listOf(1_000_000L, 1_020_000L), repo.samples(Exchange.HYPERLIQUID).keys.toList())
        assertEquals(listOf(1_008_000L, 1_020_000L), repo.liveTimes.toList())
    }

    @Test
    fun ensureOnlyFetchesMissingRangesAndRespectsCappedLoads() = runBlocking {
        val calls = ArrayList<Pair<Long, Long>>()
        val repo = OiRepository(mapOf(Exchange.BYBIT to com.tryagain2019.androidplot.data.OiHistorySource { from, to, _, _, _, _ ->
            calls += from to to
            // Pretend the source stops after one day per call.
            val start = maxOf(from, to - 24 * 60 * MINUTE)
            Fetched(listOf(Sample(start, 1.0), Sample(to, 2.0)), start)
        }))
        val day = 24 * 60 * MINUTE
        repo.ensure(Exchange.BYBIT, 10 * day, 13 * day, Timeframe.H1, 13 * day)
        repo.ensure(Exchange.BYBIT, 10 * day, 13 * day, Timeframe.H1, 13 * day)
        // Second call: only the part the capped first call did not cover.
        assertEquals(listOf(10 * day to 13 * day, 10 * day to 12 * day), calls)
        repo.ensure(Exchange.BYBIT, 11 * day, 14 * day, Timeframe.H1, 14 * day)
        assertEquals(13 * day to 14 * day, calls.last())
    }

    @Test
    fun fundingEventsAreRoundedToTheMinute() {
        val repo = FundingRepository(emptyMap())
        repo.add(Exchange.HYPERLIQUID, listOf(FundingEvent(3_600_076, 0.1), FundingEvent(7_199_990, 0.2)))
        assertEquals(listOf(3_600_000L, 7_200_000L), repo.events(Exchange.HYPERLIQUID).keys.toList())
    }

    @Test
    fun hyperliquidOpenInterestIsRecordedAcrossSessions() {
        val dir = tmp.newFolder()
        val first = OiRepository(emptyMap(), LiveOiRecorder(dir))
        first.recordLive(10 * MINUTE, mapOf(Exchange.HYPERLIQUID to 30_000.0, Exchange.BINANCE to 1.0))
        first.recordLive(12 * MINUTE, mapOf(Exchange.HYPERLIQUID to 30_100.0)) // < 5 min later: not persisted
        first.recordLive(16 * MINUTE, mapOf(Exchange.HYPERLIQUID to 30_200.0))
        val second = OiRepository(emptyMap(), LiveOiRecorder(dir))
        assertEquals(mapOf(10 * MINUTE to 30_000.0, 16 * MINUTE to 30_200.0), second.samples(Exchange.HYPERLIQUID))
        assertEquals(emptyMap(), second.samples(Exchange.BINANCE))
    }
}
