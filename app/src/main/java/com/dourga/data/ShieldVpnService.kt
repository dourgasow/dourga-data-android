package com.dourga.data

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

class ShieldVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopShield()
            return START_NOT_STICKY
        }
        startShield()
        return START_STICKY
    }

    private fun startShield() {
        stopTunnel()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val blocked = prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()
        if (blocked.isEmpty()) {
            stopShield()
            return
        }

        val builder = Builder()
            .setSession("Dourga Bouclier")
            .addAddress("10.8.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:1:fd00:1:fd00:1:fd00:2", 128)
            .addRoute("::", 0)

        for (pkg in blocked) {
            try {
                builder.addAllowedApplication(pkg)
            } catch (e: Exception) {
            }
        }

        val fd = builder.establish()
        if (fd == null) {
            stopShield()
            return
        }
        tunnel = fd
        running = true
        prefs.edit().putBoolean(KEY_ACTIVE, true).apply()

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

    private fun stopTunnel() {
        running = false
        try {
            tunnel?.close()
        } catch (e: Exception) {
        }
        tunnel = null
        reader = null
    }

    private fun stopShield() {
        stopTunnel()
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putBoolean(KEY_ACTIVE, false).apply()
        stopSelf()
    }

    override fun onRevoke() {
        stopShield()
    }

    override fun onDestroy() {
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
    }
}
