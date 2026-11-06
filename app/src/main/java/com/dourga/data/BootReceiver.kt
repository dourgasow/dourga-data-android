package com.dourga.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

// Relance le bouclier apres un redemarrage du telephone,
// seulement si le client l'avait active et ne l'avait pas arrete lui-meme.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(
            ShieldVpnService.PREFS,
            Context.MODE_PRIVATE
        )
        if (!prefs.getBoolean(ShieldVpnService.KEY_WANTED, false)) return
        ShieldVpnService.appendLog(context, "Redemarrage du telephone : relance du bouclier")
        val service = Intent(context, ShieldVpnService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        } catch (e: Exception) {
            ShieldVpnService.appendLog(
                context,
                "Relance impossible : " + e.javaClass.simpleName
            )
        }
    }
}
