package com.aegis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.aegis.app.MainActivity
import com.aegis.app.R
import com.aegis.app.engine.AegisEngine
import com.aegis.core.model.RouteContext
import com.aegis.core.net.DnsMessage
import com.aegis.core.net.Ipv4Udp
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor

/**
 * The app-wide network filter (§3.5, build order step 3).
 *
 * ## What this actually does, and what it deliberately does not
 *
 * It is a **DNS filter**, not a traffic interceptor. The tunnel is configured to route
 * one address — the DNS server Aegis advertises to the system — so name lookups come here
 * and nothing else does. Every other packet on the device takes its normal path and is
 * never seen by this process.
 *
 * That is a smaller thing than "inspect all traffic", and it is the right thing for three
 * reasons. Practically, §5 is correct that TLS means a general interceptor would see
 * hostnames and little else — exactly what DNS already gives us, at a fraction of the
 * cost. Ethically, a blocker that positions itself as a man in the middle of everything a
 * person does is asking for trust it does not need. And in engineering terms, routing all
 * traffic through a userspace process makes the whole device's networking as reliable as
 * this file, which is not a trade worth making.
 *
 * ## Known limits, stated rather than hidden
 *
 * - DNS-over-HTTPS and DNS-over-TLS bypass this entirely. Apps that hard-code their own
 *   encrypted resolver will not be filtered here — the browser and the accessibility
 *   guard are what cover that case.
 * - Direct connections to a literal IP address involve no lookup and so are not seen.
 * - Only one VPN can be active at a time on Android, so this cannot coexist with another
 *   VPN app. The settings screen says so before it is switched on.
 */
class AegisVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    private lateinit var engine: AegisEngine
    private val resolvers = mutableListOf<InetAddress>()
    private val writeLock = Any()

    private val forwarders: ThreadPoolExecutor =
        Executors.newFixedThreadPool(FORWARDER_THREADS) as ThreadPoolExecutor

    override fun onCreate() {
        super.onCreate()
        engine = AegisEngine.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFiltering()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotice()
        startFiltering()
        // START_STICKY: if the system kills us for memory, the filter should come back
        // without the user having to notice it went away.
        return START_STICKY
    }

    override fun onDestroy() {
        stopFiltering()
        forwarders.shutdownNow()
        super.onDestroy()
    }

    /** The system telling us the user revoked the VPN in Settings. */
    override fun onRevoke() {
        stopFiltering()
        stopSelf()
        super.onRevoke()
    }

    private fun startFiltering() {
        if (running) return

        // Read the real resolvers *before* establishing, while the active network is
        // still the physical one rather than our own tunnel.
        resolvers.clear()
        resolvers += discoverUpstreamResolvers()

        val descriptor = try {
            Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(MTU)
                .addAddress(TUNNEL_ADDRESS, TUNNEL_PREFIX)
                .addDnsServer(VIRTUAL_DNS)
                .addRoute(VIRTUAL_DNS, HOST_PREFIX)
                .setBlocking(true)
                .also { builder ->
                    val configure = Intent(this, MainActivity::class.java)
                    builder.setConfigureIntent(
                        PendingIntent.getActivity(
                            this, 0, configure,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
                .establish()
        } catch (error: Exception) {
            Log.e(TAG, "could not establish the tunnel", error)
            null
        }

        if (descriptor == null) {
            isRunning = false
            stopSelf()
            return
        }

        tunnel = descriptor
        running = true
        isRunning = true

        worker = Thread({ pump(descriptor) }, "aegis-dns").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopFiltering() {
        running = false
        isRunning = false
        worker?.interrupt()
        worker = null
        try {
            tunnel?.close()
        } catch (error: Exception) {
            Log.w(TAG, "closing the tunnel", error)
        }
        tunnel = null
        stopForegroundCompat()
    }

    /**
     * Read packets off the tunnel until told to stop.
     *
     * Blocking reads on a dedicated thread, with each approved query forwarded on a small
     * pool so that one slow upstream lookup cannot stall every other name on the device.
     */
    private fun pump(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(MTU)

        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val length = input.read(buffer)
                if (length <= 0) continue

                val packet = buffer.copyOf(length)
                val datagram = Ipv4Udp.parseUdp(packet, length) ?: continue
                if (datagram.destinationPort != DNS_PORT) continue

                val payload = packet.copyOfRange(
                    datagram.payloadOffset,
                    datagram.payloadOffset + datagram.payloadLength,
                )
                val question = DnsMessage.parseQuestion(payload)

                if (question == null) {
                    // Not something we understand. Forward it rather than drop it.
                    forwarders.execute { forward(datagram, payload, output) }
                    continue
                }

                val verdict = engine.evaluateHost(question.name, RouteContext.DNS)
                if (verdict.decision.isBlocked) {
                    val response = DnsMessage.buildBlockedResponse(payload, question)
                    write(output, Ipv4Udp.buildUdpReply(datagram, response))
                } else {
                    forwarders.execute { forward(datagram, payload, output) }
                }
            }
        } catch (error: Exception) {
            if (running) Log.w(TAG, "pump stopped", error)
        }
    }

    /**
     * Send an approved query to a real resolver and hand the answer back.
     *
     * The socket is [protect]ed, which keeps it off the tunnel — without that, the query
     * would be routed straight back into this service.
     */
    private fun forward(
        request: Ipv4Udp.UdpDatagram,
        payload: ByteArray,
        output: FileOutputStream,
    ) {
        val upstream = resolvers.firstOrNull() ?: return
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = UPSTREAM_TIMEOUT_MILLIS

            socket.send(DatagramPacket(payload, payload.size, upstream, DNS_PORT))

            val answer = ByteArray(MAX_DNS_RESPONSE)
            val received = DatagramPacket(answer, answer.size)
            socket.receive(received)

            write(output, Ipv4Udp.buildUdpReply(request, answer.copyOf(received.length)))
        } catch (error: Exception) {
            // A dropped lookup surfaces to the app as a normal DNS timeout, which is the
            // correct failure mode: it retries, rather than seeing a forged answer.
            Log.d(TAG, "upstream lookup failed", error)
        } finally {
            socket?.close()
        }
    }

    private fun write(output: FileOutputStream, packet: ByteArray) {
        synchronized(writeLock) {
            try {
                output.write(packet)
            } catch (error: Exception) {
                Log.w(TAG, "could not write to the tunnel", error)
            }
        }
    }

    /**
     * Ask the system which resolvers the real network is using, so Aegis forwards to the
     * same place the device would have anyway. Falling back to a fixed public resolver
     * only when the system will not say.
     */
    private fun discoverUpstreamResolvers(): List<InetAddress> {
        val discovered = mutableListOf<InetAddress>()
        try {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (network in manager.allNetworks) {
                val capabilities = manager.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue

                val properties = manager.getLinkProperties(network) ?: continue
                for (server in properties.dnsServers) {
                    // IPv4 only: the tunnel advertises an IPv4 resolver, so an IPv6
                    // upstream would be reachable but pointlessly asymmetric here.
                    if (server.address.size == 4 && server !in discovered) discovered += server
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "could not read the system resolvers", error)
        }

        if (discovered.isEmpty()) {
            for (fallback in FALLBACK_RESOLVERS) {
                try {
                    discovered += InetAddress.getByName(fallback)
                } catch (error: Exception) {
                    Log.w(TAG, "bad fallback resolver $fallback", error)
                }
            }
        }
        return discovered
    }

    private fun startForegroundNotice() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.vpn_channel_description)
                setShowBadge(false)
            },
        )

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val TAG = "AegisVpn"

        const val ACTION_START = "com.aegis.app.action.START_FILTER"
        const val ACTION_STOP = "com.aegis.app.action.STOP_FILTER"

        /** Observed by the UI to show whether the filter is up. */
        @Volatile
        var isRunning: Boolean = false

        private const val CHANNEL_ID = "aegis-filter"
        private const val NOTIFICATION_ID = 4711

        private const val MTU = 1500
        private const val DNS_PORT = 53
        private const val MAX_DNS_RESPONSE = 4096
        private const val UPSTREAM_TIMEOUT_MILLIS = 5_000
        private const val FORWARDER_THREADS = 4

        /**
         * Addresses inside the tunnel. Chosen from a rarely-used private range so the
         * tunnel does not collide with a home or corporate network on 10.0.0.x.
         */
        private const val TUNNEL_ADDRESS = "10.111.222.1"
        private const val TUNNEL_PREFIX = 32
        private const val VIRTUAL_DNS = "10.111.222.2"
        private const val HOST_PREFIX = 32

        /** Quad9, which publishes a no-logging policy. Only used if the system will not say. */
        private val FALLBACK_RESOLVERS = listOf("9.9.9.9", "149.112.112.112")

        fun start(context: Context) {
            val intent = Intent(context, AegisVpnService::class.java).setAction(ACTION_START)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AegisVpnService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
