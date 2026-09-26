package com.tryagain2019.androidplot.net

import com.tryagain2019.androidplot.model.DAY
import com.tryagain2019.androidplot.model.Sample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

/**
 * Binance's public data archive (data.binance.vision). Its daily "metrics" files hold 5-minute
 * open-interest snapshots for the whole history, which the REST API only serves for 30 days.
 * Parsed days are cached on disk because they never change.
 */
class BinanceArchive(
    private val client: OkHttpClient,
    private val cacheDir: File,
    private val base: String = "https://data.binance.vision",
    private val symbol: String = "BTCUSDT",
) {
    /** 5-minute OI snapshots for [date] (UTC), or an empty list if Binance has no file for that day. */
    suspend fun day(date: LocalDate, now: Long): List<Sample> {
        val cached = cacheFile(date)
        withContext(Dispatchers.IO) { readCache(cached) }?.let { return it }

        val url = "$base/data/futures/um/daily/metrics/$symbol/$symbol-metrics-$date.zip"
        val bytes = client.getBytesOrNull(url)
        val samples = if (bytes == null) emptyList() else withContext(Dispatchers.Default) { parseZip(bytes) }
        // A missing file for a recent day may still be published later; only remember it once it is old.
        val dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        if (samples.isNotEmpty() || now - dayEnd > 3 * DAY) {
            withContext(Dispatchers.IO) { writeCache(cached, samples) }
        }
        return samples
    }

    private fun cacheFile(date: LocalDate) = File(cacheDir, "$symbol-$date.csv")

    private fun readCache(file: File): List<Sample>? {
        if (!file.isFile) return null
        return runCatching {
            file.readLines().mapNotNull { line ->
                val comma = line.indexOf(',')
                if (comma <= 0) null else Sample(line.substring(0, comma).toLong(), line.substring(comma + 1).toDouble())
            }
        }.getOrNull()
    }

    private fun writeCache(file: File, samples: List<Sample>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(samples.joinToString("\n") { "${it.time},${it.value}" })
            tmp.renameTo(file)
        }
    }

    companion object {
        fun parseZip(bytes: ByteArray): List<Sample> {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.endsWith(".csv")) {
                        return parseCsv(zip.readBytes().toString(Charsets.UTF_8))
                    }
                }
            }
            return emptyList()
        }

        /**
         * Columns: create_time, symbol, sum_open_interest, sum_open_interest_value, ... The header row
         * is optional; create_time is "yyyy-MM-dd HH:mm:ss" (UTC) or epoch milliseconds.
         */
        fun parseCsv(text: String): List<Sample> {
            val out = ArrayList<Sample>(300)
            var timeCol = 0
            var oiCol = 2
            text.lineSequence().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachIndexed
                val cols = line.split(',')
                if (index == 0 && cols.any { it.trim().equals("sum_open_interest", ignoreCase = true) }) {
                    timeCol = cols.indexOfFirst { it.trim().equals("create_time", ignoreCase = true) }.takeIf { it >= 0 } ?: 0
                    oiCol = cols.indexOfFirst { it.trim().equals("sum_open_interest", ignoreCase = true) }
                    return@forEachIndexed
                }
                if (cols.size <= maxOf(timeCol, oiCol)) return@forEachIndexed
                val t = parseTime(cols[timeCol].trim()) ?: return@forEachIndexed
                val v = cols[oiCol].trim().toDoubleOrNull() ?: return@forEachIndexed
                if (v.isFinite() && v > 0) out += Sample(t, v)
            }
            return out.sortedBy { it.time }
        }

        private fun parseTime(s: String): Long? {
            if (s.isEmpty()) return null
            if (s[0].isDigit() && s.all { it.isDigit() }) {
                val n = s.toLongOrNull() ?: return null
                return if (n < 100_000_000_000L) n * 1000 else n // seconds or milliseconds
            }
            // "2024-01-01 00:05:00"
            if (s.length < 19 || s[4] != '-' || s[7] != '-' || s[13] != ':' || s[16] != ':') return null
            return runCatching {
                val date = LocalDate.of(s.substring(0, 4).toInt(), s.substring(5, 7).toInt(), s.substring(8, 10).toInt())
                val secs = s.substring(11, 13).toInt() * 3600 + s.substring(14, 16).toInt() * 60 + s.substring(17, 19).toInt()
                date.atStartOfDay(ZoneOffset.UTC).toEpochSecond() * 1000 + secs * 1000L
            }.getOrNull()
        }
    }
}
