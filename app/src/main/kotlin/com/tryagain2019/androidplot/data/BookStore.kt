package com.tryagain2019.androidplot.data

import com.tryagain2019.androidplot.model.BookMarket
import com.tryagain2019.androidplot.model.BookSnapshot
import com.tryagain2019.androidplot.model.DAY
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.time.LocalDate
import kotlin.math.log10
import kotlin.math.pow

/**
 * The order book snapshots the app recorded, one file per UTC day and market. Quantities are
 * stored as 16-bit logarithms (0.06 % steps), so a snapshot takes about 2 bytes per $10 of book.
 * One instance per directory is shared by the chart and the background job; all methods block, so
 * call them from a background thread.
 */
class BookStore internal constructor(private val dir: File) {
    private val checked = HashSet<String>()
    private val prunedDay = HashMap<BookMarket, Long>()
    private val first = HashMap<BookMarket, Long?>()
    private val last = HashMap<BookMarket, Long>()

    private fun marketDir(market: BookMarket) = File(dir, market.key)

    private fun file(market: BookMarket, day: Long) = File(marketDir(market), "${LocalDate.ofEpochDay(day)}.bin")

    @Synchronized
    fun append(market: BookMarket, s: BookSnapshot) {
        val day = Math.floorDiv(s.time, DAY)
        val f = file(market, day)
        runCatching {
            f.parentFile?.mkdirs()
            if (checked.add(f.path)) repair(f)
            FileOutputStream(f, true).use { out ->
                if (f.length() == 0L) out.write(header())
                out.write(encode(s))
            }
            if (first[market] == null) first.remove(market)
            last[market] = maxOf(last[market] ?: 0L, s.time)
        }
        if (prunedDay[market] != day) {
            prunedDay[market] = day
            prune(market, day)
        }
    }

    /** Calls [visit] for the snapshots taken in [from, to), oldest first. */
    @Synchronized
    fun read(market: BookMarket, from: Long, to: Long, visit: (BookSnapshot) -> Unit) {
        var previous = Long.MIN_VALUE
        for (day in Math.floorDiv(from, DAY)..Math.floorDiv(to - 1, DAY)) {
            val f = file(market, day)
            if (!f.isFile) continue
            scan(f, decode = { t -> t >= from && t < to && t > previous }) { _, s ->
                if (s != null) {
                    previous = s.time
                    visit(s)
                }
            }
        }
    }

    fun readList(market: BookMarket, from: Long, to: Long): List<BookSnapshot> = ArrayList<BookSnapshot>().also { list -> read(market, from, to) { list += it } }

    /** Time of the oldest recorded snapshot, or null before the first one. */
    @Synchronized
    fun firstTime(market: BookMarket): Long? {
        if (first.containsKey(market)) return first[market]
        var t: Long? = null
        for (day in days(market)) {
            scan(file(market, day), decode = { false }) { time, _ -> if (t == null) t = time }
            if (t != null) break
        }
        first[market] = t
        return t
    }

    /** Time of the newest recorded snapshot, or null. */
    @Synchronized
    fun lastTime(market: BookMarket): Long? {
        last[market]?.let { return it }
        for (day in days(market).reversed()) {
            var t: Long? = null
            scan(file(market, day), decode = { false }) { time, _ -> t = time }
            if (t != null) return t.also { last[market] = it }
        }
        return null
    }

    /** Bytes stored for [market]. */
    @Synchronized
    fun size(market: BookMarket): Long = marketDir(market).listFiles()?.sumOf { it.length() } ?: 0L

    private fun days(market: BookMarket): List<Long> = marketDir(market).listFiles()
        ?.mapNotNull { f -> f.name.removeSuffix(".bin").takeIf { f.name.endsWith(".bin") }?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() } }
        ?.sorted()
        .orEmpty()

    /** Drops days older than [RETENTION_DAYS], then the oldest days while over [MAX_BYTES]. */
    private fun prune(market: BookMarket, today: Long) {
        val days = days(market).toMutableList()
        var removed = false
        while (days.isNotEmpty() && (days.first() < today - RETENTION_DAYS || days.size > 1 && days.sumOf { file(market, it).length() } > MAX_BYTES)) {
            file(market, days.removeAt(0)).delete()
            removed = true
        }
        if (removed) first.remove(market)
    }

    /** Cuts off a record left half-written (the app was killed while appending), or drops an unreadable file. */
    private fun repair(f: File) {
        if (!f.isFile || f.length() == 0L) return
        val valid = scan(f, decode = { false }) { _, _ -> }
        when {
            valid < 0 -> f.delete()
            valid < f.length() -> RandomAccessFile(f, "rw").use { it.setLength(valid) }
        }
    }

    /**
     * Reads [f]'s records in order and returns the length of its valid part (-1 if the header is
     * not ours). [visit] gets each record's time, and the decoded snapshot where [decode] says so.
     */
    private fun scan(f: File, decode: (Long) -> Boolean, visit: (Long, BookSnapshot?) -> Unit): Long {
        if (!f.isFile) return 0
        // Only read errors end the scan quietly; whatever visit() throws goes to the caller.
        return try {
            DataInputStream(f.inputStream().buffered(64 * 1024)).use { input ->
                if (input.readInt() != FILE_MAGIC || input.readInt() != VERSION || input.readDouble() != BookSnapshot.BIN) return -1
                var offset = HEADER_BYTES.toLong()
                var buffer = ByteArray(0)
                while (true) {
                    val magic = try {
                        input.readUnsignedShort()
                    } catch (_: EOFException) {
                        break
                    }
                    if (magic != RECORD_MAGIC) break
                    val length = try {
                        input.readInt()
                    } catch (_: EOFException) {
                        break
                    }
                    if (length < 32 || length > MAX_RECORD) break
                    if (buffer.size < length) buffer = ByteArray(length)
                    try {
                        input.readFully(buffer, 0, length)
                    } catch (_: EOFException) {
                        break
                    }
                    val time = readLong(buffer, 0)
                    if (decode(time)) visit(time, decode(buffer, length) ?: break) else visit(time, null)
                    offset += 6 + length
                }
                offset
            }
        } catch (_: IOException) {
            0L
        }
    }

    companion object {
        const val RETENTION_DAYS = 31
        /** Per book; a month of snapshots is normally a few MB. */
        const val MAX_BYTES = 64L * 1024 * 1024
        private const val FILE_MAGIC = 0x424f4f4b // "BOOK"
        private const val VERSION = 1
        private const val HEADER_BYTES = 16
        private const val RECORD_MAGIC = 0xB0B0
        private const val MAX_RECORD = 1 shl 20

        private val instances = HashMap<String, BookStore>()

        /** The store for [dir]; every caller in the process shares one instance (and its lock). */
        fun forDir(dir: File): BookStore = synchronized(instances) { instances.getOrPut(dir.absolutePath) { BookStore(dir) } }

        private fun header(): ByteArray = ByteArrayOutputStream(HEADER_BYTES).also {
            DataOutputStream(it).apply {
                writeInt(FILE_MAGIC)
                writeInt(VERSION)
                writeDouble(BookSnapshot.BIN)
            }
        }.toByteArray()

        internal fun encode(s: BookSnapshot): ByteArray {
            val body = ByteArrayOutputStream(40 + 2 * (s.bids.size + s.asks.size))
            DataOutputStream(body).apply {
                writeLong(s.time)
                writeDouble(s.mid)
                writeInt(s.bidTop)
                writeInt(s.bids.size)
                for (q in s.bids) writeShort(Quantity.encode(q))
                writeInt(s.askBottom)
                writeInt(s.asks.size)
                for (q in s.asks) writeShort(Quantity.encode(q))
            }
            val record = ByteArrayOutputStream(body.size() + 6)
            DataOutputStream(record).apply {
                writeShort(RECORD_MAGIC)
                writeInt(body.size())
                write(body.toByteArray())
            }
            return record.toByteArray()
        }

        private fun readInt(b: ByteArray, p: Int): Int =
            ((b[p].toInt() and 0xff) shl 24) or ((b[p + 1].toInt() and 0xff) shl 16) or ((b[p + 2].toInt() and 0xff) shl 8) or (b[p + 3].toInt() and 0xff)

        private fun readLong(b: ByteArray, p: Int): Long = (readInt(b, p).toLong() shl 32) or (readInt(b, p + 4).toLong() and 0xffffffffL)

        private fun decode(b: ByteArray, length: Int): BookSnapshot? {
            var p = 0
            fun int(): Int = readInt(b, p).also { p += 4 }
            fun values(n: Int): FloatArray? {
                if (n < 0 || p + 2 * n > length) return null
                val table = Quantity.table
                val start = p
                p += 2 * n
                return FloatArray(n) { i -> table[((b[start + 2 * i].toInt() and 0xff) shl 8) or (b[start + 2 * i + 1].toInt() and 0xff)] }
            }
            val time = readLong(b, 0)
            val mid = Double.fromBits(readLong(b, 8))
            p = 16
            val bidTop = int()
            val bids = values(int()) ?: return null
            if (p + 8 > length) return null
            val askBottom = int()
            val asks = values(int()) ?: return null
            return BookSnapshot(time, mid, bidTop, bids, askBottom, asks)
        }
    }
}

/** 16-bit logarithmic quantities: code = log10(q) * 4000 + 24001, 0 for nothing. */
internal object Quantity {
    private const val SCALE = 4000.0
    private const val OFFSET = 6.0

    fun encode(q: Float): Int = if (!(q > 0)) 0 else (Math.round((log10(q.toDouble()) + OFFSET) * SCALE) + 1).toInt().coerceIn(1, 65535)

    val table: FloatArray by lazy { FloatArray(65536) { if (it == 0) 0f else 10.0.pow((it - 1) / SCALE - OFFSET).toFloat() } }
}

