package com.tryagain2019.androidplot

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.tryagain2019.androidplot.data.BookStore
import com.tryagain2019.androidplot.model.BookMarket
import com.tryagain2019.androidplot.model.MINUTE
import com.tryagain2019.androidplot.net.Http
import com.tryagain2019.androidplot.net.OrderBooks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Records a snapshot of every enabled exchange's order book about every 15 minutes while the app
 * is closed, so the heatmap keeps filling in (no exchange has order book history to download later).
 */
class BookRecordJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (networkType(prefs) == null) {
            getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
            return false
        }
        val markets = BookMarket.enabled { prefs.getString(it, null) }
        val dir = File(filesDir, "book")
        scope.launch {
            try {
                val store = BookStore.forDir(dir)
                val books = OrderBooks.production(Http.shared)
                withTimeout(90_000) {
                    markets.map { market ->
                        async {
                            val last = store.lastTime(market)
                            // Skip when the app itself sampled a moment ago.
                            if (last != null && System.currentTimeMillis() - last < MIN_GAP) return@async
                            try {
                                store.append(market, books.snapshot(market) { System.currentTimeMillis() })
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.d(TAG, "${market.displayName} order book failed: $e")
                            }
                        }
                    }.awaitAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "order book recording failed: $e")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BTCPlot"
        private const val JOB_ID = 1507
        private const val MIN_GAP = 5 * MINUTE
        const val PREFS = "settings"

        /** The network the job needs, or null when background recording is off. */
        private fun networkType(prefs: SharedPreferences): Int? {
            if (prefs.getString("heat", null) == "off") return null
            return when (prefs.getString("heat.bg", null)) {
                "off" -> null
                "wifi" -> JobInfo.NETWORK_TYPE_UNMETERED
                else -> JobInfo.NETWORK_TYPE_ANY
            }
        }

        /** Schedules (or cancels) the job to match the settings; an unchanged schedule keeps its timer. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val network = networkType(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            if (network == null) {
                scheduler.cancel(JOB_ID)
                return
            }
            @Suppress("DEPRECATION") // getRequiredNetwork() needs API 28
            val pending = scheduler.getPendingJob(JOB_ID)
            @Suppress("DEPRECATION")
            if (pending != null && pending.isPeriodic && pending.networkType == network) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, BookRecordJob::class.java))
                .setPeriodic(15 * MINUTE, 5 * MINUTE)
                .setRequiredNetworkType(network)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(job) }.onFailure { Log.w(TAG, "could not schedule order book recording", it) }
        }
    }
}
