package com.dourga.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShieldVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    @Volatile private var running = false
    private var screenReceiver: BroadcastReceiver? = null
    private var currentKey: String = ""
    @Volatile private var stoppedByApp = false

    private val handler = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_ALIVE, System.currentTimeMillis()).apply()
            handler.postDelayed(this, 60000L)
        }
    }

    private fun log(message: String) = appendLog(this, message)

        super.onCreate()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val wasActive = prefs.getBoolean(KEY_ACTIVE, false)
        val lastAlive = prefs.getLong(KEY_ALIVE, 0L)
        log("Service cree")
        if (wasActive) {
            val t = if (lastAlive > 0L) {
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(lastAlive))
            } else {
                "inconnu"
            }
            log("ALERTE : service precedent arrete sans prevenir (dernier signe de vie : " + t + ")")
        }
        log("Batterie : " + batteryInfo())
        installCrashLogger()
    }

    override fun onBind(intent: Intent?): IBinder? {
        log("Liaison demandee : " + (intent?.action ?: "aucune"))
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log(
            "Commande recue : " +
                (if (intent == null) "REDEMARRAGE par Android" else (intent.action ?: "demarrage"))
        )
        if (intent?.action == ACTION_STOP) {
            stopShield("arret demande")
            return START_NOT_STICKY
        }
        goForeground()
        startHeartbeat()
        registerScreenReceiver()
        applyState()
        return START_STICKY
    }

    private fun startHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
    }

    private fun stopHeartbeat() {
        handler.removeCallbacks(heartbeat)
    }

    private fun batteryInfo(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(packageName)) {
                    "non optimisee (bon)"
                } else {
                    "OPTIMISEE (Android peut couper)"
                }
            } else {
                "non applicable"
            }
        } catch (e: Exception) {
            "inconnu"
        }
    }

    private fun installCrashLogger() {
        if (crashLoggerInstalled) return
        crashLoggerInstalled = true
        val appCtx = applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                appendLog(
                    appCtx,
                    "PLANTAGE : " + error.javaClass.simpleName + " " + (error.message ?: ""),
                    true
                )
            } catch (e: Exception) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun goForeground() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val builder: Notification.Builder
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Protection Dourga",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
                builder = Notification.Builder(this, CHANNEL_ID)
            } else {
                builder = Notification.Builder(this)
            }
            val notification = builder
                .setContentTitle("Dourga Data")
                .setContentText("Protection de votre forfait active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            log("Notification permanente activee")
        } catch (e: Exception) {
            log("Notification impossible : " + e.javaClass.simpleName)
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        log("Ecran eteint")
                        applyState()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        log("Ecran allume")
                        applyState()
                    }
                }
            }
        }
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        registerReceiver(receiver, filter)
        screenReceiver = receiver
    }

    private fun unregisterScreenReceiver() {
        val receiver = screenReceiver ?: return
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
        }
        screenReceiver = null
    }

    private fun isScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    private fun resolveByLabel(): Set<String> {
        val result = HashSet<String>()
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (info in packageManager.queryIntentActivities(intent, 0)) {
                val label = info.loadLabel(packageManager).toString()
                if (AUTO_LABELS.any { it.equals(label, ignoreCase = true) }) {
                    result.add(info.activityInfo.packageName)
                }
            }
        } catch (e: Exception) {
            log("Recherche par nom impossible")
        }
        return result
    }

    private fun applyState() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val manual = prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()
        val screenOn = isScreenOn()
        val wanted = HashSet<String>(manual)
        if (!screenOn) {
            wanted.addAll(AUTO_APPS)
            wanted.addAll(resolveByLabel())
        }
        wanted.remove(packageName)
        val key = wanted.sorted().joinToString(",")
        log("Ecran " + (if (screenOn) "allume" else "eteint") + ", applis visees : " + wanted.size)
        if (key == currentKey && tunnel != null) return
        openTunnel(wanted, key)
    }

    private fun openTunnel(wanted: Set<String>, key: String) {
        closeTunnel()
        currentKey = key
        active = true
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putBoolean(KEY_ACTIVE, true).apply()

        if (wanted.isEmpty()) {
            log("Aucune application a couper pour le moment")
            return
        }

        val builder = Builder()
            .setSession("Dourga Bouclier")
            .addAddress("10.8.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:1:fd00:1:fd00:1:fd00:2", 128)
            .addRoute("::", 0)

        var added = 0
        for (pkg in wanted) {
            try {
                builder.addAllowedApplication(pkg)
                added++
            } catch (e: Exception) {
            }
        }
        log("Applis dans le tunnel : " + added)
        if (added == 0) {
            log("Aucune des applications visees n'est installee")
            return
        }

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            log("Erreur ouverture : " + e.javaClass.simpleName)
            null
        }
        if (fd == null) {
            log("Android a refuse d'ouvrir le tunnel")
            return
        }

        tunnel = fd
        running = true
        log("Tunnel ouvert")

        val input = FileInputStream(fd.fileDescriptor)
        reader = Thread {
            val buf = ByteArray(32767)
            try {
                while (running) {
                    if (input.read(buf) < 0) break
                }
            } catch (e: Exception) {
            }
        }
        reader?.start()
    }

    private fun closeTunnel() {
        running = false
        try {
            tunnel?.close()
        } catch (e: Exception) {
        }
        tunnel = null
        reader = null
    }

    private fun stopShield(reason: String) {
        stoppedByApp = true
        log("Arret : " + reason)
        stopHeartbeat()
        unregisterScreenReceiver()
        closeTunnel()
        active = false
        currentKey = ""
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putBoolean(KEY_ACTIVE, false).apply()
        try {
            stopForeground(true)
        } catch (e: Exception) {
        }
        stopSelf()
    }

    override fun onRevoke() {
        stopShield("autre VPN ou autorisation retiree")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        log("Appli retiree des applis recentes")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        if (level != 20) {
            log("Memoire faible, niveau " + level)
        }
        super.onTrimMemory(level)
    }

    override fun onDestroy() {
        log("Service detruit" + (if (stoppedByApp) "" else " SANS demande de l'appli"))
        stopHeartbeat()
        unregisterScreenReceiver()
        closeTunnel()
        active = false
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putBoolean(KEY_ACTIVE, false).apply()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.dourga.data.STOP_SHIELD"
        const val PREFS = "dourga_shield"
        const val KEY_BLOCKED = "blocked_apps"
        const val KEY_ACTIVE = "shield_active"
        const val KEY_LOG = "shield_log"
        const val KEY_ALIVE = "shield_last_alive"
        const val CHANNEL_ID = "dourga_protection"
        const val NOTIF_ID = 4201

        val AUTO_APPS = listOf(
            "com.facebook.katana",
            "com.facebook.lite",
            "com.instagram.android",
            "com.google.android.youtube",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go",
            "com.snapchat.android",
            "com.netflix.mediaclient",
            "com.twitter.android"
        )

        val AUTO_LABELS = listOf(
            "CapCut",
            "FRANCE 24"
        )

        @Volatile
        var active = false

        @Volatile
        private var crashLoggerInstalled = false

        fun appendLog(ctx: Context, message: String, sync: Boolean = false) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
            val old = prefs.getString(KEY_LOG, "") ?: ""
            val lines = (old.split("\n").filter { it.isNotEmpty() } + (time + " " + message))
                .takeLast(120)
            val editor = prefs.edit().putString(KEY_LOG, lines.joinToString("\n"))
            if (sync) {
                editor.commit()
            } else {
                editor.apply()
            }
        }
    }
}
