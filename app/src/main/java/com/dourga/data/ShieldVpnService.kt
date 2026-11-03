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
import java.util.concurrent.atomic.AtomicInteger

class ShieldVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    @Volatile private var running = false
    private var screenReceiver: BroadcastReceiver? = null
    private var currentKey: String = ""
    @Volatile private var stoppedByApp = false

    private var shaperRunning = false
    private var shaperChoking = false
    private var shaperCycles = 0
    private var manualPkgs: Set<String> = emptySet()
    private var shapePkgs: Set<String> = emptySet()

    private val tcpCount = AtomicInteger(0)
    private val udpCount = AtomicInteger(0)
    private val udp443Count = AtomicInteger(0)
    private val otherCount = AtomicInteger(0)
    private val activeReaders = AtomicInteger(0)

    private val handler = Handler(Looper.getMainLooper())

    private val heartbeat = object : Runnable {
        override fun run() {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_ALIVE, System.currentTimeMillis()).apply()
            handler.postDelayed(this, 60000L)
        }
    }

    private val statsLogger = object : Runnable {
        override fun run() {
            val tcp = tcpCount.getAndSet(0)
            val udp = udpCount.getAndSet(0)
            val udp443 = udp443Count.getAndSet(0)
            val autre = otherCount.getAndSet(0)
            if (tcp + udp + autre > 0) {
                log(
                    "Paquets coupes : TCP " + tcp + ", UDP " + udp +
                        " (dont port 443 : " + udp443 + "), autres " + autre +
                        (if (shaperRunning) ", cycles " + shaperCycles else "") +
                        ", lecteurs " + activeReaders.get()
                )
            }
            handler.postDelayed(this, 10000L)
        }
    }

    private val shaperTick = object : Runnable {
        override fun run() {
            if (!shaperRunning) return
            if (shaperChoking) {
                shaperChoking = false
                shaperCycles++
                buildTunnel(manualPkgs, true)
                handler.postDelayed(this, OPEN_MS)
            } else {
                shaperChoking = true
                buildTunnel(manualPkgs + shapePkgs, true)
                handler.postDelayed(this, CHOKE_MS)
            }
        }
    }

    private fun log(message: String) = appendLog(this, message)

    override fun onCreate() {
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
        handler.removeCallbacks(statsLogger)
        handler.postDelayed(statsLogger, 10000L)
    }

    private fun stopHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(statsLogger)
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

    private fun isInstalled(pkg: String): Boolean {
        return try {
            packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
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
        val manual = HashSet<String>(prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet())
        manual.remove(packageName)
        val screenOn = isScreenOn()

        if (!screenOn) {
            stopShaper()
            val wanted = HashSet<String>(manual)
            wanted.addAll(AUTO_APPS)
            wanted.addAll(resolveByLabel())
            wanted.remove(packageName)
            val key = "off:" + wanted.sorted().joinToString(",")
            log("Ecran eteint, applis visees : " + wanted.size)
            if (key == currentKey && tunnel != null) return
            openTunnel(wanted, key)
        } else {
            val shape = HashSet<String>()
            for (pkg in SHAPE_APPS) {
                if (isInstalled(pkg)) shape.add(pkg)
            }
            shape.remove(packageName)
            val key = "on:" + manual.sorted().joinToString(",") + "|" +
                shape.sorted().joinToString(",")
            log("Ecran allume, applis limitees : " + shape.size + ", coupees : " + manual.size)
            if (key == currentKey && (shaperRunning || tunnel != null)) return
            if (shape.isEmpty()) {
                stopShaper()
                openTunnel(manual, key)
            } else {
                startShaper(manual, shape, key)
            }
        }
    }

    private fun startShaper(manual: Set<String>, shape: Set<String>, key: String) {
        stopShaper()
        closeTunnel()
        currentKey = key
        active = true
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putBoolean(KEY_ACTIVE, true).apply()
        manualPkgs = manual
        shapePkgs = shape
        shaperCycles = 0
        shaperChoking = false
        shaperRunning = true
        log("Shaper actif : ouvert " + OPEN_MS + " ms, coupe " + CHOKE_MS + " ms")
        buildTunnel(manualPkgs, true)
        handler.postDelayed(shaperTick, OPEN_MS)
    }

    private fun stopShaper() {
        if (shaperRunning) {
            log("Shaper arrete apres " + shaperCycles + " cycles")
        }
        shaperRunning = false
        handler.removeCallbacks(shaperTick)
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
        buildTunnel(wanted, false)
    }

    private fun buildTunnel(pkgs: Set<String>, quiet: Boolean): Boolean {
        closeTunnel()
        if (pkgs.isEmpty()) return false

        val builder = Builder()
            .setSession("Dourga Bouclier")
            .addAddress("10.8.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:1:fd00:1:fd00:1:fd00:2", 128)
            .addRoute("::", 0)

        var added = 0
        for (pkg in pkgs) {
            try {
                builder.addAllowedApplication(pkg)
                added++
            } catch (e: Exception) {
            }
        }
        if (!quiet) log("Applis dans le tunnel : " + added)
        if (added == 0) {
            if (!quiet) log("Aucune des applications visees n'est installee")
            return false
        }

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            if (!quiet) log("Erreur ouverture : " + e.javaClass.simpleName)
            null
        }
        if (fd == null) {
            if (!quiet) log("Android a refuse d'ouvrir le tunnel")
            return false
        }

        tunnel = fd
        running = true
        if (!quiet) log("Tunnel ouvert")

        val input = FileInputStream(fd.fileDescriptor)
        val thread = Thread {
            activeReaders.incrementAndGet()
            val buf = ByteArray(32767)
            try {
                while (running) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n < 1) continue
                    val version = (buf[0].toInt() shr 4) and 0x0F
                    val proto = when {
                        version == 4 && n >= 20 -> buf[9].toInt() and 0xFF
                        version == 6 && n >= 40 -> buf[6].toInt() and 0xFF
                        else -> -1
                    }
                    when (proto) {
                        6 -> tcpCount.incrementAndGet()
                        17 -> {
                            udpCount.incrementAndGet()
                            // Diagnostic : compte les UDP vers le port 443 (QUIC / HTTP3)
                            val ihl = if (version == 4) (buf[0].toInt() and 0x0F) * 4 else 40
                            if (n >= ihl + 4) {
                                val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or
                                    (buf[ihl + 3].toInt() and 0xFF)
                                if (dstPort == 443) udp443Count.incrementAndGet()
                            }
                        }
                        else -> otherCount.incrementAndGet()
                    }
                }
            } catch (e: Exception) {
            } finally {
                activeReaders.decrementAndGet()
            }
        }
        reader = thread
        thread.start()
        return true
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
        stopShaper()
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
        stopShaper()
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

        const val OPEN_MS = 150L
        const val CHOKE_MS = 250L

        val SHAPE_APPS = listOf(
            "com.facebook.katana"
        )

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
