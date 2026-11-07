package com.dourga.data

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Process
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

object DataUsage {

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun todayJson(context: Context): String {
        if (!hasAccess(context)) {
            return JSONObject().put("access", false).toString()
        }
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        val end = System.currentTimeMillis()

        val perUid = HashMap<Int, Long>()
        try {
            val stats = manager.querySummary(ConnectivityManager.TYPE_MOBILE, null, start, end)
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val bytes = bucket.rxBytes + bucket.txBytes
                perUid[bucket.uid] = (perUid[bucket.uid] ?: 0L) + bytes
            }
            stats.close()
        } catch (e: Exception) {
            return JSONObject()
                .put("access", true)
                .put("error", e.javaClass.simpleName)
                .toString()
        }

        val pm = context.packageManager
        val perApp = HashMap<String, Pair<String, Long>>()
        var total = 0L
        for ((uid, bytes) in perUid) {
            total += bytes
            var pkg = ""
            var name = "Système Android"
            if (uid != Process.SYSTEM_UID && uid >= 0) {
                val pkgs = pm.getPackagesForUid(uid)
                if (pkgs != null && pkgs.isNotEmpty()) {
                    pkg = pkgs[0]
                    name = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (e: Exception) {
                        pkg
                    }
                }
            }
            val old = perApp[pkg]
            perApp[pkg] = Pair(name, (old?.second ?: 0L) + bytes)
        }

        val sorted = perApp.entries
            .filter { it.value.second >= 100_000L }
            .sortedByDescending { it.value.second }
            .take(15)

        val array = JSONArray()
        for (e in sorted) {
            val obj = JSONObject()
            obj.put("pkg", e.key)
            obj.put("name", e.value.first)
            obj.put("mb", Math.round(e.value.second / 100_00
