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

    fun hasAccess(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val m = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return m == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun todayJson(c: Context): String {
        if (!hasAccess(c)) return JSONObject().put("access", false).toString()
        val nsm = c.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val perUid = HashMap<Int, Long>()
        try {
            val st = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, null, cal.timeInMillis, System.currentTimeMillis())
            val b = NetworkStats.Bucket()
            while (st.hasNextBucket()) {
                st.getNextBucket(b)
                perUid[b.uid] = (perUid[b.uid] ?: 0L) + b.rxBytes + b.txBytes
            }
            st.close()
        } catch (e: Exception) {
            return JSONObject().put("access", true).put("error", e.javaClass.simpleName).toString()
        }
        val pm = c.packageManager
        val perApp = HashMap<String, Pair<String, Long>>()
        var total = 0L
        for ((uid, bytes) in perUid) {
            total += bytes
            var pkg = ""
            var name = "Système Android"
            val pkgs = if (uid >= 0) pm.getPackagesForUid(uid) else null
            if (uid != Process.SYSTEM_UID && pkgs != null && pkgs.isNotEmpty()) {
                pkg = pkgs[0]
                name = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
            }
            perApp[pkg] = Pair(name, (perApp[pkg]?.second ?: 0L) + bytes)
        }
        val arr = JSONArray()
        val top = perApp.entries.filter { it.value.second >= 100000L }.sortedByDescending { it.value.second }.take(15)
        for (e in top) {
            val o = JSONObject()
            o.put("pkg", e.key)
            o.put("name", e.value.first)
            o.put("mb", Math.round(e.value.second / 100000.0) / 10.0)
            arr.put(o)
        }
        return JSONObject().put("access", true).put("totalMb", Math.round(total / 100000.0) / 10.0).put("apps", arr).toString()
    }
}
