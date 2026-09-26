package com.tryagain2019.androidplot

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.tryagain2019.androidplot.net.Http
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import java.io.File

/** Hosts the chart page (assets/chart) in a WebView and connects it to [ChartController]. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var webView: WebView
    private lateinit var controller: ChartController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        webView = WebView(this).apply {
            setBackgroundColor(BACKGROUND)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            // Long-press is the chart's crosshair gesture; keep WebView's text selection out of it.
            isLongClickable = false
            isHapticFeedbackEnabled = false
            setOnLongClickListener { true }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.textZoom = 100
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.scheme == "file") return false
                    openExternally(url.toString())
                    return true
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    // The renderer died (e.g. killed for memory); start over with a fresh WebView.
                    recreate()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d(TAG, "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                    return true
                }
            }
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })

        controller = ChartController(
            scope = scope,
            apis = Apis.production(Http.newClient(), File(filesDir, "binance-archive")),
            settings = PrefsStore(getSharedPreferences("settings", MODE_PRIVATE)),
            dataDir = filesDir,
            versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "",
            send = { script -> if (!isDestroyed) webView.evaluateJavascript(script, null) },
        )
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/chart/index.html")
    }

    override fun onStart() {
        super.onStart()
        controller.onStart()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onStop() {
        controller.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        controller.destroy()
        scope.cancel()
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Still the simplest hook while targeting API 34 without predictive back.")
    override fun onBackPressed() {
        webView.evaluateJavascript("window.chartApp&&chartApp.back()") { handled ->
            @Suppress("DEPRECATION")
            if (handled != "true") super.onBackPressed()
        }
    }

    private fun openExternally(url: String) {
        if (!url.startsWith("https://")) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
        }
    }

    /** Called by the page's JavaScript on a WebView thread; everything is forwarded to the main thread. */
    private inner class Bridge {
        private val main = Handler(Looper.getMainLooper())

        private fun post(block: () -> Unit) {
            main.post { if (!isDestroyed) block() }
        }

        @JavascriptInterface
        fun ready() = post { controller.onPageReady() }

        @JavascriptInterface
        fun setTimeframe(code: String) = post { controller.setTimeframe(code) }

        @JavascriptInterface
        fun visibleRange(gen: Int, fromSec: Double, toSec: Double, logicalFrom: Double) =
            post { controller.onVisibleRange(gen, fromSec, toSec, logicalFrom) }

        @JavascriptInterface
        fun setSetting(key: String, value: String) = post { controller.setSetting(key, value) }

        @JavascriptInterface
        fun retry() = post { controller.retry() }

        @JavascriptInterface
        fun openUrl(url: String) = post { openExternally(url) }
    }

    private class PrefsStore(private val prefs: SharedPreferences) : SettingsStore {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }
    }

    private companion object {
        const val TAG = "BTCPlot"
        const val BACKGROUND = 0xFF212121.toInt()
    }
}
