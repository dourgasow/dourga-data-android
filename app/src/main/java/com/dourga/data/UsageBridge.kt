package com.dourga.data

import android.app.Activity
import android.webkit.JavascriptInterface

class UsageBridge(private val activity: Activity) {

    @JavascriptInterface
    fun hasAccess(): Boolean = DataUsage.hasAccess(activity)

    @JavascriptInterface
    fun today(): String = DataUsage.todayJson(activity)

    @JavascriptInterface
    fun openAccessSettings() {
        activity.runOnUiThread {
            activity.startActivity(DataUsage.settingsIntent())
        }
    }
}
