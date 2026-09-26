package com.tryagain2019.androidplot

import com.tryagain2019.androidplot.model.DAY
import com.tryagain2019.androidplot.model.HOUR
import com.tryagain2019.androidplot.model.Timeframe
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeframeTest {
    private fun t(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun intradayAndDailyBarsAlignToUtc() {
        assertEquals(t("2026-09-22T13:00:00Z"), Timeframe.H1.barStart(t("2026-09-22T13:59:59Z")))
        assertEquals(t("2026-09-22T12:00:00Z"), Timeframe.H4.barStart(t("2026-09-22T15:10:00Z")))
        assertEquals(t("2026-09-22T00:00:00Z"), Timeframe.D1.barStart(t("2026-09-22T23:10:00Z")))
        assertEquals(t("2026-09-22T00:15:00Z"), Timeframe.M15.barStart(t("2026-09-22T00:29:00Z")))
    }

    @Test
    fun weeksStartOnMonday() {
        // 2026-09-21 is a Monday.
        assertEquals(t("2026-09-21T00:00:00Z"), Timeframe.W1.barStart(t("2026-09-27T23:59:00Z")))
        assertEquals(t("2026-09-21T00:00:00Z"), Timeframe.W1.barStart(t("2026-09-21T00:00:00Z")))
        assertEquals(t("2026-09-14T00:00:00Z"), Timeframe.W1.barStart(t("2026-09-20T12:00:00Z")))
        // Before the epoch's first Monday as well.
        assertEquals(t("1969-12-29T00:00:00Z"), Timeframe.W1.barStart(t("1970-01-01T00:00:00Z")))
    }

    @Test
    fun monthsFollowTheCalendar() {
        assertEquals(t("2026-02-01T00:00:00Z"), Timeframe.MN1.barStart(t("2026-02-28T22:00:00Z")))
        assertEquals(t("2026-03-01T00:00:00Z"), Timeframe.MN1.nextBarStart(t("2026-02-01T00:00:00Z")))
        assertEquals(t("2025-11-01T00:00:00Z"), Timeframe.MN1.shift(t("2026-03-01T00:00:00Z"), -4))
        assertEquals(t("2026-09-24T00:00:00Z"), Timeframe.D1.shift(t("2026-09-25T00:00:00Z"), -1))
    }

    @Test
    fun resolutionsDivideTheirTimeframes() {
        for (tf in Timeframe.entries) {
            if (tf == Timeframe.MN1) continue
            assertEquals(0L, tf.nominalMs % minOf(tf.nominalMs, tf.oiResolution.ms), tf.name)
        }
        assertEquals(DAY, Timeframe.D1.nominalMs)
        assertEquals(4 * HOUR, Timeframe.D1.oiResolution.ms)
    }
}
