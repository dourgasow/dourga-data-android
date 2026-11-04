package com.dourga.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * SONDE DNS : observe les noms de domaine demandes par Facebook.
 * Elle ne bloque RIEN. Seules les requetes DNS entrent dans le tunnel,
 * elles sont relayees telles quelles vers un vrai serveur DNS.
 * Tout le reste du trafic de Facebook passe directement par le reseau mobile.
 */
class DnsProbeService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var running = false
    @Volatile private var output: FileOutputStream? = null
    private val writeLock = Any()
    private var executor: ExecutorService? = null

    private val domains = ConcurrentHashMap<String, AtomicInteger>()
    private val totalQueries = AtomicInteger(0)
    private val failures = AtomicInteger(0)

    private val handler = Handler(Looper.getMainLooper())

    private val reportSaver = object : Runnable {
        override fun run() {
            saveReport()
            handler.postDelayed(this, 10000L)
        }
    }

    private val autoStop = Runnable { stopProbe("duree maximale atteinte") }

    private fun log(message: String) = ShieldVpnService.appendLog(this, message)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProbe("arret demande")
            return START_NOT_STICKY
        }
        goForeground()
        startProbe()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val builder: Notification.Builder
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Sonde Dourga",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
                builder = Notification.Builder(this, CHANNEL_ID)
            } else {
                builder = Notification.Builder(this)
            }
            val notification = builder
                .setContentTitle("Dourga Data")
                .setContentText("Sonde DNS en cours (test)")
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
        } catch (e: Exception) {
            log("Sonde : notification impossible " + e.javaClass.simpleName)
        }
    }

    private fun startProbe() {
        closeTunnel()
        domains.clear()
        totalQueries.set(0)
        failures.set(0)

        val builder = Builder()
            .setSession("Dourga Sonde DNS")
            .addAddress(CLIENT_IP, 32)
            .addDnsServer(DNS_IP)
            .addRoute(DNS_IP, 32)
            .setBlocking(true)

        try {
            builder.addAllowedApplication(FACEBOOK_PKG)
        } catch (e: Exception) {
            log("Sonde : Facebook introuvable")
            stopProbe("Facebook introuvable")
            return
        }

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            log("Sonde : erreur ouverture " + e.javaClass.simpleName)
            null
        }
        if (fd == null) {
            log("Sonde : Android a refuse d'ouvrir le tunnel")
            stopProbe("tunnel refuse")
            return
        }

        tunnel = fd
        val input = FileInputStream(fd.fileDescriptor)
        output = FileOutputStream(fd.fileDescriptor)
        executor = Executors.newFixedThreadPool(4)
        running = true
        active = true
        log("Sonde DNS demarree (Facebook seulement, rien n'est bloque)")

        val thread = Thread {
            val buf = ByteArray(32767)
            try {
                while (running) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n < 40) continue
                    handlePacket(buf, n)
                }
            } catch (e: Exception) {
            }
        }
        thread.start()

        handler.removeCallbacks(reportSaver)
        handler.postDelayed(reportSaver, 10000L)
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, MAX_DURATION_MS)
    }

    private fun handlePacket(buf: ByteArray, n: Int) {
        val version = (buf[0].toInt() shr 4) and 0x0F
        if (version != 4) return
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20 || n < ihl + 8 + 12) return
        val proto = buf[9].toInt() and 0xFF
        if (proto != 17) return
        val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return
        val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)

        val srcIp = buf.copyOfRange(12, 16)
        val dstIp = buf.copyOfRange(16, 20)
        val payload = buf.copyOfRange(ihl + 8, n)

        totalQueries.incrementAndGet()
        val name = parseName(payload)
        if (name != null) {
            domains.getOrPut(name) { AtomicInteger(0) }.incrementAndGet()
        }
        executor?.execute { forward(payload, srcIp, dstIp, srcPort) }
    }

    private fun parseName(p: ByteArray): String? {
        var i = 12
        val sb = StringBuilder()
        while (i < p.size) {
            val len = p[i].toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) != 0) return null
            if (i + 1 + len > p.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(p, i + 1, len, Charsets.US_ASCII))
            i += 1 + len
        }
        return if (sb.isEmpty()) null else sb.toString().lowercase()
    }

    private fun forward(query: ByteArray, srcIp: ByteArray, dstIp: ByteArray, srcPort: Int) {
        var answer: ByteArray? = null
        for (server in UPSTREAM_DNS) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                protect(socket)
                socket.soTimeout = 2500
                val addr = InetAddress.getByName(server)
                socket.send(DatagramPacket(query, query.size, addr, 53))
                val resp = ByteArray(4096)
                val packet = DatagramPacket(resp, resp.size)
                socket.receive(packet)
                answer = resp.copyOf(packet.length)
            } catch (e: Exception) {
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                }
            }
            if (answer != null) break
        }
        val data = answer
        if (data == null || data.size > 1400) {
            failures.incrementAndGet()
            return
        }
        writeResponse(data, srcIp, dstIp, srcPort)
    }

    private fun writeResponse(data: ByteArray, clientIp: ByteArray, dnsIp: ByteArray, clientPort: Int) {
        val total = 28 + data.size
        val p = ByteArray(total)
        p[0] = 0x45.toByte()
        p[2] = (total shr 8).toByte()
        p[3] = total.toByte()
        p[6] = 0x40.toByte()
        p[8] = 64
        p[9] = 17
        System.arraycopy(dnsIp, 0, p, 12, 4)
        System.arraycopy(clientIp, 0, p, 16, 4)
        val ck = checksum(p, 20)
        p[10] = (ck shr 8).toByte()
        p[11] = ck.toByte()
        p[20] = 0
        p[21] = 53
        p[22] = (clientPort shr 8).toByte()
        p[23] = clientPort.toByte()
        val udpLen = 8 + data.size
        p[24] = (udpLen shr 8).toByte()
        p[25] = udpLen.toByte()
        System.arraycopy(data, 0, p, 28, data.size)
        synchronized(writeLock) {
            try {
                output?.write(p)
            } catch (e: Exception) {
            }
        }
    }

    private fun checksum(b: ByteArray, len: Int): Int {
        var sum = 0
        var i = 0
        while (i < len - 1) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        while ((sum shr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun saveReport() {
        val sorted = domains.entries.sortedByDescending { it.value.get() }.take(150)
        val sb = StringBuilder()
        sb.append("SONDE DNS Facebook : ")
            .append(totalQueries.get()).append(" requetes, ")
            .append(domains.size).append(" noms, echecs ")
            .append(failures.get()).append("\n")
        if (totalQueries.get() == 0) {
            sb.append("Aucune requete DNS recue (DNS prive ou autre contournement possible)\n")
        }
        for (e in sorted) {
            sb.append(e.value.get()).append(" ").append(e.key).append("\n")
        }
        getSharedPreferences(ShieldVpnService.PREFS, MODE_PRIVATE)
            .edit().putString(KEY_REPORT, sb.toString()).apply()
    }

    private fun closeTunnel() {
        running = false
        try {
            tunnel?.close()
        } catch (e: Exception) {
        }
        tunnel = null
        output = null
        try {
            executor?.shutdownNow()
        } catch (e: Exception) {
        }
        executor = null
    }

    private fun stopProbe(reason: String) {
        if (active) {
            log("Sonde DNS arretee : " + reason)
            saveReport()
        }
        handler.removeCallbacks(reportSaver)
        handler.removeCallbacks(autoStop)
        closeTunnel()
        active = false
        try {
            stopForeground(true)
        } catch (e: Exception) {
        }
        stopSelf()
    }

    override fun onRevoke() {
        stopProbe("autre VPN ou autorisation retiree")
    }

    override fun onDestroy() {
        handler.removeCallbacks(reportSaver)
        handler.removeCallbacks(autoStop)
        closeTunnel()
        active = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.dourga.data.STOP_PROBE"
        const val KEY_REPORT = "probe_report"
        const val CHANNEL_ID = "dourga_probe"
        const val NOTIF_ID = 4202
        const val FACEBOOK_PKG = "com.facebook.katana"
        const val CLIENT_IP = "10.8.0.2"
        const val DNS_IP = "10.8.0.1"
        const val MAX_DURATION_MS = 600000L

        val UPSTREAM_DNS = listOf("8.8.8.8", "1.1.1.1")

        @Volatile
        var active = false
    }
}
