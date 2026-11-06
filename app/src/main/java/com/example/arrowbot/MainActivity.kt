package com.example.arrowbot

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

/** Hosts index.html and bridges its buttons to AutoTapService. */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    inner class Bridge {
        @JavascriptInterface fun start() { AutoTapService.instance?.startBot() }
        @JavascriptInterface fun stop() { AutoTapService.instance?.stopBot() }
        @JavascriptInterface fun isAccessibilityEnabled(): Boolean = AutoTapService.instance != null
        @JavascriptInterface fun openAccessibilitySettings() {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            addJavascriptInterface(Bridge(), "ArrowBot")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)

        AutoTapService.statusListener = { s ->
            runOnUiThread { web.evaluateJavascript("window.onBotStatus('$s')", null) }
        }
        AutoTapService.logListener = { m ->
            val safe = m.replace("\\", "\\\\").replace("'", "\\'")
            runOnUiThread { web.evaluateJavascript("window.onBotLog('$safe')", null) }
        }
    }
}
