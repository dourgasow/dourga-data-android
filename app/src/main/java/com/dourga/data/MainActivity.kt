package com.dourga.data

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val onlineUrl = "https://dourgasow.github.io/dourga-data/"
    private val offlineUrl = "file:///android_asset/index.html"

    private val shieldButtonScript = """
        (function(){
          if (document.getElementById('dourga-shield-btn')) return;
          var a = document.createElement('a');
          a.id = 'dourga-shield-btn';
          a.href = 'https://dourgasow.github.io/dourga-data/bouclier.html';
          a.textContent = '🛡️';
          a.style.cssText = 'position:fixed;right:14px;bottom:90px;width:54px;height:54px;border-radius:27px;background:#4ade80;color:#0b1f2a;font-size:26px;display:flex;align-items:center;justify-content:center;text-decoration:none;box-shadow:0 2px 8px rgba(0,0,0,.4);z-index:99999';
          document.body.appendChild(a);
        })();
    """

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            launchShield()
        } else {
            notifyShield()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.addJavascriptInterface(DourgaBridge(), "DourgaNative")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame && request.url.toString() != offlineUrl) {
                    view.loadUrl(offlineUrl)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null && url.startsWith(onlineUrl) && !url.contains("bouclier")) {
                    view.evaluateJavascript(shieldButtonScript, null)
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        webView.loadUrl(onlineUrl)
    }

    private fun prefs() = getSharedPreferences(ShieldVpnService.PREFS, MODE_PRIVATE)

    private fun launchShield() {
        startService(Intent(this, ShieldVpnService::class.java))
        webView.postDelayed({ notifyShield() }, 800)
    }

    private fun notifyShield() {
        val active = prefs().getBoolean(ShieldVpnService.KEY_ACTIVE, false)
        webView.evaluateJavascript(
            "if(window.onShieldChanged){window.onShieldChanged($active)}",
            null
        )
    }

    inner class DourgaBridge {

        @JavascriptInterface
        fun isNative(): Boolean = true

        @JavascriptInterface
        fun appVersion(): String = "1.2"

        @JavascriptInterface
        fun isShieldActive(): Boolean =
            prefs().getBoolean(ShieldVpnService.KEY_ACTIVE, false)

        @JavascriptInterface
        fun getInstalledApps(): String {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val seen = HashSet<String>()
            val items = ArrayList<Pair<String, String>>()
            for (info in packageManager.queryIntentActivities(intent, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val label = info.loadLabel(packageManager).toString()
                items.add(Pair(pkg, label))
            }
            items.sortBy { it.second.lowercase() }
            val array = JSONArray()
            for (item in items) {
                val obj = JSONObject()
                obj.put("pkg", item.first)
                obj.put("name", item.second)
                array.put(obj)
            }
            return array.toString()
        }

        @JavascriptInterface
        fun getBlockedApps(): String {
            val set = prefs().getStringSet(ShieldVpnService.KEY_BLOCKED, emptySet()) ?: emptySet()
            return JSONArray(set.toList()).toString()
        }

        @JavascriptInterface
        fun setBlockedApps(json: String) {
            val array = JSONArray(json)
            val set = HashSet<String>()
            for (i in 0 until array.length()) {
                set.add(array.getString(i))
            }
            prefs().edit().putStringSet(ShieldVpnService.KEY_BLOCKED, set).apply()
            if (prefs().getBoolean(ShieldVpnService.KEY_ACTIVE, false)) {
                runOnUiThread { launchShield() }
            }
        }

        @JavascriptInterface
        fun startShield() {
            runOnUiThread {
                val request = VpnService.prepare(this@MainActivity)
                if (request != null) {
                    vpnPermission.launch(request)
                } else {
                    launchShield()
                }
            }
        }

        @JavascriptInterface
        fun stopShield() {
            runOnUiThread {
                val stop = Intent(this@MainActivity, ShieldVpnService::class.java)
                stop.action = ShieldVpnService.ACTION_STOP
                startService(stop)
                webView.postDelayed({ notifyShield() }, 600)
            }
        }
    }
}
