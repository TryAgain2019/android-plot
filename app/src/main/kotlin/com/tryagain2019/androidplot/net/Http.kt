package com.tryagain2019.androidplot.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Non-2xx response. [detail] is the exchange's error message when it sent one. */
class HttpException(val code: Int, val detail: String, val url: String) : IOException("HTTP $code${if (detail.isNotEmpty()) ": $detail" else ""}")

object Http {
    /** One client (connection pool, threads) for the whole process: the chart and the background job. */
    val shared: OkHttpClient by lazy { newClient() }

    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dispatcher(Dispatcher().apply { maxRequests = 96; maxRequestsPerHost = 32 })
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "BTCPlot/1.0 (Android)").build())
        }
        .build()
}

private val JSON_TYPE = "application/json".toMediaType()

/** GET [url] and parse the body with [parse] off the caller's thread. */
suspend fun <T> OkHttpClient.get(url: String, parse: (String) -> T): T = fetch(Request.Builder().url(url).get().build(), parse)

/** POST a JSON body to [url] and parse the response with [parse] off the caller's thread. */
suspend fun <T> OkHttpClient.post(url: String, json: String, parse: (String) -> T): T =
    fetch(Request.Builder().url(url).post(json.toRequestBody(JSON_TYPE)).build(), parse)

/** Downloads [url]; returns null on 404 (e.g. an archive file that does not exist). */
suspend fun OkHttpClient.getBytesOrNull(url: String): ByteArray? = suspendCancellableCoroutine { cont ->
    val call = newCall(Request.Builder().url(url).get().build())
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWith(Result.failure(e))
        override fun onResponse(call: Call, response: Response) {
            cont.resumeWith(runCatching {
                response.use { r ->
                    when {
                        r.code == 404 || r.code == 403 && url.contains("data.binance.vision") -> null
                        !r.isSuccessful -> throw HttpException(r.code, errorDetail(r.body?.string().orEmpty()), url)
                        else -> r.body?.bytes() ?: ByteArray(0)
                    }
                }
            })
        }
    })
}

/** Runs [request] and hands the body to [parse] on OkHttp's thread, so neither I/O nor parsing touches the caller's thread. */
private suspend fun <T> OkHttpClient.fetch(request: Request, parse: (String) -> T): T = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWith(Result.failure(e))
        override fun onResponse(call: Call, response: Response) {
            cont.resumeWith(runCatching {
                response.use { r ->
                    val body = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw HttpException(r.code, errorDetail(body), request.url.toString())
                    parse(body)
                }
            })
        }
    })
}

/** Pulls the human readable message out of the error bodies the exchanges send. */
internal fun errorDetail(body: String): String {
    val trimmed = body.trim()
    if (trimmed.startsWith("{")) {
        runCatching {
            val o = JSONObject(trimmed)
            for (key in listOf("msg", "retMsg", "message", "error")) {
                val v = o.optString(key, "")
                if (v.isNotEmpty()) return v.take(160)
            }
        }
    }
    return if (trimmed.startsWith("<")) "" else trimmed.take(160)
}

/** Short text for the status panel. */
fun describeError(e: Throwable): String = when (e) {
    is HttpException -> when (e.code) {
        451 -> "blocked in your region (HTTP 451)"
        403 -> "access denied (HTTP 403)${if (e.detail.isNotEmpty()) ": ${e.detail}" else ""}"
        418, 429 -> "rate limited (HTTP ${e.code})"
        else -> e.message ?: "HTTP ${e.code}"
    }
    is java.net.UnknownHostException -> "no internet connection"
    is java.net.SocketTimeoutException -> "timed out"
    is java.io.InterruptedIOException -> "timed out"
    is ApiException -> e.message ?: "API error"
    is IOException -> e.message ?: "network error"
    else -> e.message ?: e.javaClass.simpleName
}

/** The exchange answered, but with an error code or a payload we could not use. */
class ApiException(message: String) : IOException(message)
