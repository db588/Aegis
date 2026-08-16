package uk.co.logicscience.aegis.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import uk.co.logicscience.aegis.MainActivity
import uk.co.logicscience.aegis.R
import uk.co.logicscience.aegis.config.Config
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class DnsVpnService : VpnService() {
    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var dnsResolver: DnsResolver? = null

    /**
     * Worker pool for DNS handling. The read loop must never block:
     * forwardQuery can wait up to Config.DNS_TIMEOUT_MS on upstream, and
     * doing that inline would stall every other DNS query on the device.
     */
    private var executor: ExecutorService? = null

    /** Guards writes to the TUN descriptor, which workers share. */
    private val writeLock = Any()

    @Volatile
    private var vpnOutput: FileOutputStream? = null

    /**
     * Reload requests come in on the main thread via LocalBroadcastManager.
     * reloadBlocklists() does a blocking DB read, so each request is handed
     * off to its own short-lived thread rather than run inline — that keeps
     * both the caller and the VPN read loop unblocked. DnsResolver publishes
     * the new sets through @Volatile fields, so workers pick them up safely
     * without any lock.
     */
    private val reloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val resolver = dnsResolver ?: return
            Thread({ resolver.reloadBlocklists() }, "AegisBlocklistReload").start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(reloadReceiver, IntentFilter(ACTION_RELOAD_BLOCKLISTS))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (vpnThread != null) return

        startForeground(NOTIFICATION_ID, buildNotification())

        vpnThread = Thread({
            try {
                dnsResolver = DnsResolver(applicationContext)
                runVpn()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, "AegisVpnThread").apply { start() }

        isRunning = true
    }

    private fun stopVpn() {
        vpnThread?.interrupt()
        vpnThread = null

        executor?.shutdownNow()
        try {
            executor?.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        executor = null

        vpnOutput = null
        vpnInterface?.close()
        vpnInterface = null
        dnsResolver = null
        isRunning = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun runVpn() {
        // Route ONLY the fake DNS address through the VPN. All other traffic
        // bypasses the tunnel entirely, so browsing speed is unaffected and
        // we never have to forward TCP traffic ourselves.
        val builder = Builder()
            .setSession("Aegis")
            .addAddress("10.111.222.1", 24)
            .addDnsServer(FAKE_DNS)
            .addRoute(FAKE_DNS, 32)
            .setMtu(1500)
            .setBlocking(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Don't filter our own DNS lookups (avoids loops)
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
        }

        vpnInterface = builder.establish() ?: return

        val input = FileInputStream(vpnInterface!!.fileDescriptor)
        val output = FileOutputStream(vpnInterface!!.fileDescriptor)
        vpnOutput = output

        executor = Executors.newFixedThreadPool(Config.DNS_THREAD_POOL_SIZE)

        val packet = ByteArray(32767)

        try {
            while (!Thread.currentThread().isInterrupted) {
                val length = input.read(packet)
                if (length <= 0) continue

                val version = (packet[0].toInt() shr 4) and 0x0F
                if (version != 4) continue

                val ihl = (packet[0].toInt() and 0x0F) * 4
                if (ihl < 20 || length < ihl + 8) continue

                val protocol = packet[9].toInt() and 0xFF
                if (protocol != 17) continue // UDP only

                val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or
                        (packet[ihl + 3].toInt() and 0xFF)
                if (dstPort != 53) continue

                // The read buffer is reused on the next iteration, so the
                // worker must get its own copy.
                val copy = packet.copyOf(length)
                try {
                    executor?.execute { handleQuery(copy, length, ihl) }
                } catch (_: Exception) {
                    // Pool shutting down; drop the query.
                }
            }
        } catch (e: InterruptedException) {
            // Expected on stop
        } catch (e: IOException) {
            // Expected when the user revokes the VPN permission (or another
            // VPN app takes over) — the TUN fd is torn out from under the
            // read loop. Not an error, so exit quietly.
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { input.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
            vpnOutput = null
            vpnInterface?.close()
            vpnInterface = null
        }
    }

    /** Runs on a worker thread. May block on upstream DNS. */
    private fun handleQuery(packet: ByteArray, length: Int, ihl: Int) {
        val result = dnsResolver?.handlePacket(packet, length, ihl) ?: return

        if (result.blocked) {
            blockedCount.incrementAndGet()
        }

        val response = result.responsePacket ?: return
        val out = vpnOutput ?: return

        synchronized(writeLock) {
            try {
                out.write(response)
            } catch (_: Exception) {
                // Interface closed underneath us; nothing useful to do.
            }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "aegis_vpn"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Aegis Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Aegis DNS filtering is active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Aegis is protecting you")
            .setContentText("DNS filtering active")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(reloadReceiver)
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        stopSelf()
    }

    companion object {
        const val ACTION_START = "uk.co.logicscience.aegis.START_VPN"
        const val ACTION_STOP = "uk.co.logicscience.aegis.STOP_VPN"
        const val ACTION_RELOAD_BLOCKLISTS = "uk.co.logicscience.aegis.RELOAD_BLOCKLISTS"
        const val FAKE_DNS = "10.111.222.53"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set

        val blockedCount = AtomicLong(0)
    }
}
