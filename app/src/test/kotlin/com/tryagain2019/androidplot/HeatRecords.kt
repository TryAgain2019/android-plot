package com.tryagain2019.androidplot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One bar of the heatmap as the chart page receives it (see HeatBuilder.RECORD_HEADER). */
internal class HeatBar(val time: Long, val bidTop: Int, val bids: IntArray, val askBottom: Int, val asks: IntArray)

internal fun decodeHeat(data: ByteArray): List<HeatBar> {
    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val out = ArrayList<HeatBar>()
    while (buf.hasRemaining()) {
        val time = (buf.int.toLong() and 0xffffffffL) * 1000
        val bidTop = buf.int
        val bidCount = buf.short.toInt() and 0xffff
        val askBottom = buf.int
        val askCount = buf.short.toInt() and 0xffff
        val bids = IntArray(bidCount) { buf.get().toInt() and 0xff }
        val asks = IntArray(askCount) { buf.get().toInt() and 0xff }
        out += HeatBar(time, bidTop, bids, askBottom, asks)
    }
    return out
}
