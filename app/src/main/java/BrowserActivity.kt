package com.dourga.data

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream

class BrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var ecoButton: Button
    private var eco = true
    private val startUrl = "https://m.facebook.com/"

    private fun log(message: String) = ShieldVpnService.appendLog(this, message)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        ecoButton = Button(this)
        ecoButton.setOnClickListener {
            eco = !eco
            applyEco()
            webView.reload()
        }
        root.addView(
            ecoButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        webView = WebView(this)
        root.addView(
            webView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        applyEco()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val scheme = request.url.scheme ?: ""
                return !(scheme == "http" || scheme == "https")
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (eco && isVideo(request)) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        404,
                        "Blocked",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
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

        log("Navigateur economique ouvert")
        webView.loadUrl(startUrl)
    }

    private fun isVideo(request: WebResourceRequest): Boolean {
        val host = (request.url.host ?: "").lowercase()
        val path = (request.url.path ?: "").lowercase()
        return host.startsWith("video") ||
            path.endsWith(".mp4") ||
            path.endsWith(".m3u8") ||
            path.endsWith(".mpd") ||
            path.endsWith(".webm")
    }

    private fun applyEco() {
        webView.settings.blockNetworkImage = eco
        ecoButton.text = if (eco) {
            "Mode économique : ACTIVÉ (appuyer pour désactiver)"
        } else {
            "Mode économique : DÉSACTIVÉ (appuyer pour activer)"
        }
        log("Mode economique : " + (if (eco) "active" else "desactive"))
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
