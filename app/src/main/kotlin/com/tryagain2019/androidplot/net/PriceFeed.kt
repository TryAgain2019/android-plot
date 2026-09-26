package com.tryagain2019.androidplot.net

import com.tryagain2019.androidplot.model.Candle
import com.tryagain2019.androidplot.model.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Live candles for the current timeframe: Binance's kline WebSocket stream, with REST polling
 * whenever the socket is down or silent. Callbacks run on [scope]'s dispatcher.
 */
class PriceFeed(
    private val client: OkHttpClient,
    private val api: BinanceApi,
    private val scope: CoroutineScope,
    /** Stream base URLs tried in turn; the stream name is appended. */
    private val socketBases: List<String> = listOf("wss://fstream.binance.com/ws/", "wss://fstream.binance.com/market/ws/"),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onCandle: (Candle) -> Unit,
    private val onState: (ok: Boolean, text: String) -> Unit,
) {
    private var job: Job? = null
    private var socket: WebSocket? = null
    private var session = 0

    @Volatile private var lastSocketMessage = 0L

    val running: Boolean get() = job?.isActive == true

    fun start(tf: Timeframe) {
        stop()
        val id = ++session
        lastSocketMessage = 0L
        val stream = "${api.symbol.lowercase()}@kline_${tf.binanceInterval}"
        job = scope.launch {
            launch { poller(tf, id) }
            var attempt = 0
            var base = 0
            while (isActive) {
                val openedAt = clock()
                val delivered = connect(socketBases[base] + stream, id, openedAt)
                if (delivered) attempt = 0 else base = (base + 1) % socketBases.size
                // Wait for the socket to die (or go silent), then reconnect with backoff.
                while (isActive && socket != null && clock() - maxOf(lastSocketMessage, openedAt) < SILENCE_MS) delay(1_000)
                closeSocket()
                attempt++
                delay(minOf(30_000L, 1_000L * (1 shl minOf(attempt, 5))))
            }
        }
    }

    fun stop() {
        session++
        job?.cancel()
        job = null
        closeSocket()
    }

    /** Opens the socket; returns whether a candle arrived within a few seconds. */
    private suspend fun connect(url: String, id: Int, openedAt: Long): Boolean {
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val candle = runCatching { BinanceParsers.wsKline(text) }.getOrNull() ?: return
                lastSocketMessage = clock()
                scope.launch {
                    if (id == session) {
                        onCandle(candle)
                        onState(true, "live (stream)")
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { if (id == session && socket === webSocket) socket = null }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { if (id == session && socket === webSocket) socket = null }
            }
        })
        socket = ws
        val firstMessageBy = openedAt + FIRST_MESSAGE_MS
        while (clock() < firstMessageBy) {
            delay(250)
            if (lastSocketMessage > openedAt) return true
            if (socket == null) return false
        }
        return lastSocketMessage > openedAt
    }

    private fun closeSocket() {
        socket?.let { runCatching { it.close(1000, null) } }
        socket = null
    }

    /** Polls the REST API while the stream is not delivering. */
    private suspend fun poller(tf: Timeframe, id: Int) {
        while (true) {
            delay(POLL_MS)
            if (id != session) return
            if (clock() - lastSocketMessage < SILENCE_MS / 2) continue
            try {
                val bars = api.klines(tf.binanceInterval, 2)
                if (id != session) return
                bars.forEach(onCandle)
                onState(true, "live (polling)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (id == session) onState(false, describeError(e))
                delay(POLL_MS)
            }
        }
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val SILENCE_MS = 10_000L
        const val FIRST_MESSAGE_MS = 6_000L
    }
}
