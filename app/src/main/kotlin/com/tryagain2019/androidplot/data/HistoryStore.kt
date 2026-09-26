package com.tryagain2019.androidplot.data

import com.tryagain2019.androidplot.model.Candle
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.NavigableMap
import java.util.TreeMap

/** A time series together with the ranges that were fully fetched for it (keyed by resolution). */
class StoredSeries(val points: NavigableMap<Long, Double>, val spans: Map<String, Span>)

/**
 * Fetched history kept on disk between launches, so a launch can draw the chart immediately and
 * then only download what is new. Binary files, one per series, replaced atomically. Blocking:
 * call from a background thread.
 */
class HistoryStore(private val dir: File) {
    fun readSeries(name: String): StoredSeries? = read(name) { input ->
        val spans = HashMap<String, Span>()
        repeat(input.readInt()) { spans[input.readUTF()] = Span(input.readLong(), input.readLong()) }
        val points = TreeMap<Long, Double>()
        repeat(input.readInt()) { points[input.readLong()] = input.readDouble() }
        StoredSeries(points, spans)
    }

    fun writeSeries(name: String, series: StoredSeries) = write(name) { out ->
        out.writeInt(series.spans.size)
        for ((key, span) in series.spans) {
            out.writeUTF(key)
            out.writeLong(span.from)
            out.writeLong(span.to)
        }
        out.writeInt(series.points.size)
        for ((t, v) in series.points) {
            out.writeLong(t)
            out.writeDouble(v)
        }
    }

    fun readCandles(name: String): List<Candle>? = read(name) { input ->
        List(input.readInt()) { Candle(input.readLong(), input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble()) }
    }

    fun writeCandles(name: String, candles: List<Candle>) = write(name) { out ->
        out.writeInt(candles.size)
        for (c in candles) {
            out.writeLong(c.time)
            out.writeDouble(c.open)
            out.writeDouble(c.high)
            out.writeDouble(c.low)
            out.writeDouble(c.close)
        }
    }

    private fun file(name: String) = File(dir, "$name.bin")

    private fun <T> read(name: String, body: (DataInputStream) -> T): T? {
        val f = file(name)
        if (!f.isFile) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered(64 * 1024)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                body(input)
            }
        }.getOrNull()
    }

    private fun write(name: String, body: (DataOutputStream) -> Unit) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            DataOutputStream(tmp.outputStream().buffered(64 * 1024)).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                body(out)
            }
            if (!tmp.renameTo(file(name))) tmp.delete()
        }
    }

    private companion object {
        const val MAGIC = 0x42504c54 // "BPLT"
        const val VERSION = 1
    }
}
