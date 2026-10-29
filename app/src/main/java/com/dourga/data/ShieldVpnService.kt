package com.dourga.data

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShieldVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    @Volatile private var running = false

    private fun log(message: String) = appendLog(this, message)

    override fun onCreate() {
        super.onCreate()
        log("Service cree")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("Commande recue : " + (intent?.action ?: "demarrage"))
        if (intent?.action == ACTION_STOP) {
            stopShield("arret demande")
            return START_NOT_STICKY
        }
        startShield()
        return START_STICKY
    }

    private fun startShield() {
        stopTunnel()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val blocked = prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()
        log("Applis a bloquer : " + blocked.size)
        if (blocked.isEmpty()) {
            stopShield("aucune application cochee")
            return
        }

        val builder = Builder()
            .setSession("Dourga Bouclier")
            .addAddress("10.8.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:1:fd00:1:fd00:1:fd00:2", 128)
            .addRoute("::", 0)

        var added = 0
        for (pkg in blocked) {
            try {
                builder.addAllowedApplication(pkg)
                added++
            } catch (e: Exception) {
                log("Application ignoree : " + pkg)
            }
        }
        log("Applis ajoutees au tunnel : " + added)
        if (added == 0) {
            stopShield("aucune application valide")
            return
        }

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            log("Erreur ouverture : " + e.javaClass.simpleName)
            null
        }
        if (fd == null) {
            stopShield("Android a refuse d'ouvrir le tunnel")
            return
        }

        tunnel = fd
        running = true
        active = true
        prefs.edit().putBoolean(KEY_ACTIVE, true).apply()
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
            log("Lecture du tunnel terminee")
        }
        reader?.start()
    }

    private fun stopTunnel() {
        running = false
        active = false
        try {
            tunnel?.close()
        } catch (e: Exception) {
        }
        tunnel = null
        reader = null
    }

    private fun stopShield(reason: String) {
        log("Arret : " + reason)
        stopTunnel()
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putBoolean(KEY_ACTIVE, false).apply()
        stopSelf()
    }

    override fun onRevoke() {
        stopShield("autre VPN ou autorisation retiree")
    }

    override fun onDestroy() {
        log("Service detruit")
        stopTunnel()
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

        @Volatile
        var active = false

        fun appendLog(ctx: Context, message: String) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val old = prefs.getString(KEY_LOG, "") ?: ""
            val lines = (old.split("\n").filter { it.isNotEmpty() } + (time + " " + message))
                .takeLast(15)
            prefs.edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
        }
    }
}
