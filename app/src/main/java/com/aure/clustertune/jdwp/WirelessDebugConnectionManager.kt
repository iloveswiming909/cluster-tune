package com.aure.clustertune.jdwp

import android.content.Context
import android.util.Log
import com.wuyr.jdwp_injector.adb.AdbClient
import com.wuyr.jdwp_injector.adb.AdbWirelessPairing
import com.wuyr.jdwp_injector.debug.JdwpDebugLog
import com.wuyr.jdwp_injector.adb.AdbWirelessPortResolver
import com.wuyr.jdwp_injector.adb.AdbWirelessPortResolver.Companion.resolveAdbPairingPort
import com.wuyr.jdwp_injector.adb.AdbWirelessPortResolver.Companion.resolveAdbTcpConnectPort
import com.wuyr.jdwp_injector.adb.AdbWirelessPortResolver.Companion.resolveAdbWirelessConnectPort

/**
 * Holds the on-device wireless-debugging connection (host/port of the local
 * adbd) and drives mDNS discovery + SPAKE2 pairing using the vendored
 * jdwp-injector resolver/pairing classes (from
 * github.com/wuyr/jdwp-injector-for-android, Apache-2.0).
 *
 * Lifecycle: wireless debugging must be re-enabled each boot, so this holds
 * the connection in memory only. [JdwpInjectionExecutionMethod] reads
 * [connectionInfo] via a provider lambda.
 *
 * Typical flow (driven by the setup UI):
 *   1. startConnectDiscovery(...) — find the "connect" port (already paired).
 *   2. If that fails, startPairingDiscovery(...) — find the "pair" port, then
 *      pair(code, ...) with the 6-digit code shown under Wireless debugging.
 *   3. On success, [connectionInfo] is populated and injection can run.
 */
class WirelessDebugConnectionManager private constructor(
    context: Context,
) {

    private val appContext = context.applicationContext

    companion object {
        @Volatile
        private var INSTANCE: WirelessDebugConnectionManager? = null

        /** Process-wide singleton, shared across all AppContainer instances. */
        fun getInstance(context: Context): WirelessDebugConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WirelessDebugConnectionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    @Volatile
    var connectionInfo: AdbConnectionInfo? = null
        private set

    private var connectResolver: AdbWirelessPortResolver? = null
    private var wirelessConnectResolver: AdbWirelessPortResolver? = null

    private var connectOnConnected: ((AdbConnectionInfo) -> Unit)? = null
    private var connectOnUnavailable: (() -> Unit)? = null
    private var pairingResolver: AdbWirelessPortResolver? = null

    private var pairingHost: String? = null
    private var pairingPort: Int = 0

    // True while a pair() handshake is running or has just succeeded. When set,
    // the mDNS "pairing service lost" callback is expected (Android closes its
    // dialog on success) and must NOT be surfaced as a failure.
    @Volatile
    private var pairingInProgressOrDone: Boolean = false

    /** Provider to hand to [JdwpInjectionExecutionMethod]. */
    fun provider(): () -> AdbConnectionInfo? = { connectionInfo }

    /**
     * Begin discovering the wireless-debugging CONNECT port (device already
     * paired). Calls [onConnected] when a host/port is found.
     */
    /**
     * Start connect discovery the way wuyr does: BOTH resolvers, running
     * continuously, so whenever the _adb-tls-connect._tcp service appears
     * (which only happens after wireless debugging is fully active/paired) it
     * gets caught. Callbacks are idempotent — the first successful resolve wins.
     *
     * Safe to call repeatedly (e.g. when the screen opens and on each Connect
     * tap); it won't tear down a discovery that's mid-flight if already running.
     */
    fun startConnectDiscovery(
        onConnected: (AdbConnectionInfo) -> Unit,
        onUnavailable: () -> Unit = {},
    ) {
        // If already discovering, just update the callbacks; don't restart
        // (restarting is what made us miss the service-appearance window).
        connectOnConnected = onConnected
        connectOnUnavailable = onUnavailable
        if (connectResolver != null || wirelessConnectResolver != null) {
            JdwpDebugLog.d("startConnectDiscovery: already running; keeping discovery alive")
            connectionInfo?.let { onConnected(it) }
            return
        }
        JdwpDebugLog.d("startConnectDiscovery: starting continuous discovery (tcp + tls-connect)")
        val handle: (String, Int) -> Unit = { host, port ->
            if (connectionInfo == null) {
                val info = AdbConnectionInfo(host, port)
                connectionInfo = info
                JdwpDebugLog.d("startConnectDiscovery: CONNECTED $host:$port")
                connectOnConnected?.invoke(info)
            }
        }
        connectResolver = with(appContext) {
            resolveAdbTcpConnectPort { host, port -> handle(host, port) }
        }
        wirelessConnectResolver = with(appContext) {
            resolveAdbWirelessConnectPort(onLost = {
                JdwpDebugLog.d("startConnectDiscovery: connect service lost")
                connectOnUnavailable?.invoke()
            }) { host, port -> handle(host, port) }
        }
    }

    /**
     * Begin discovering the PAIRING port. Calls [onPairingPortFound] with the
     * host/port to which [pair] should then send the code.
     */
    fun startPairingDiscovery(
        onPairingPortFound: (String, Int) -> Unit,
        onLost: () -> Unit = {},
    ) {
        stopPairingDiscovery()
        pairingInProgressOrDone = false
        pairingResolver = with(appContext) {
            resolveAdbPairingPort(onLost = {
                // Android stops advertising the pairing service the moment
                // pairing succeeds (it closes its own dialog). Only treat this
                // as "dialog closed" if the user hasn't started pairing yet.
                if (!pairingInProgressOrDone) {
                    JdwpDebugLog.d("startPairingDiscovery: pairing port lost (before pairing)")
                    onLost()
                } else {
                    JdwpDebugLog.d("startPairingDiscovery: pairing port lost (expected after pairing) — ignoring")
                }
            }) { host, port ->
                pairingHost = host
                pairingPort = port
                JdwpDebugLog.d("startPairingDiscovery: pairing port found $host:$port")
                onPairingPortFound(host, port)
            }
        }
    }

    /**
     * Perform SPAKE2 pairing with the 6-digit [code]. Blocking; call off the
     * main thread. On success, connect discovery is (re)started so
     * [connectionInfo] gets populated.
     */
    fun pair(
        code: String,
        onPaired: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val host = pairingHost
        val port = pairingPort
        if (host == null || port == 0) {
            onError(IllegalStateException("Pairing port not found yet"))
            return
        }
        pairingInProgressOrDone = true
        runCatching {
            JdwpDebugLog.d("pair: pairing with $host:$port ...")
            AdbWirelessPairing(host, port, code).use { it.start() }
        }.onSuccess {
            JdwpDebugLog.d("pair: SUCCESS")
            stopPairingDiscovery()
            onPaired()
        }.onFailure {
            JdwpDebugLog.w("pair: FAILED", it)
            // allow the user to retry pairing
            pairingInProgressOrDone = false
            onError(it)
        }
    }

    fun stopConnectDiscovery() {
        connectResolver?.stop(); connectResolver = null
        wirelessConnectResolver?.stop(); wirelessConnectResolver = null
    }

    fun stopPairingDiscovery() {
        pairingResolver?.stop(); pairingResolver = null
    }

    fun stopAll() {
        stopConnectDiscovery()
        stopPairingDiscovery()
    }

    // ---------------------------------------------------------------------
    //  Fallback: find the adb connect port by scanning the device's own
    //  Wi-Fi IP. Used when mDNS discovery doesn't surface the connect
    //  service. No typing, no mDNS. `adb connect <wifiIp>:<port>` is known
    //  to work, so the port is open on the Wi-Fi interface.
    // ---------------------------------------------------------------------

    @Volatile
    private var scanning = false

    /**
     * Scan the device's Wi-Fi IP for the adb connect port and, if found,
     * populate [connectionInfo]. Runs off the main thread. [onResult] is
     * invoked with the connection info on success, or null if not found.
     */
    fun scanForConnectPort(onResult: (AdbConnectionInfo?) -> Unit) {
        if (scanning) return
        scanning = true
        Thread {
            val result = runCatching { doScan() }.getOrNull()
            scanning = false
            if (result != null) {
                connectionInfo = result
                connectOnConnected?.invoke(result)
            }
            onResult(result)
        }.also { it.isDaemon = true }.start()
    }

    private fun doScan(): AdbConnectionInfo? {
        val ip = wifiIpAddress()
        if (ip == null) {
            JdwpDebugLog.w("port-scan: could not determine Wi-Fi IP")
            return null
        }
        JdwpDebugLog.d("port-scan: scanning $ip for adb connect port…")

        // 1) Fast pass: find OPEN TCP ports in the adb ephemeral range.
        //    Android's wireless-adb connect port is a dynamic port; scan a
        //    broad-but-bounded range in parallel with short timeouts.
        val start = 30000
        val end = 49999
        val openPorts = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(64)
        val futures = ArrayList<java.util.concurrent.Future<*>>()
        try {
            for (port in start..end) {
                val task = Runnable {
                    try {
                        java.net.Socket().use { s ->
                            s.connect(java.net.InetSocketAddress(ip, port), 120)
                            openPorts.add(port)
                        }
                    } catch (_: Throwable) { /* closed */ }
                }
                futures.add(pool.submit(task))
            }
            futures.forEach { runCatching { it.get() } }
        } finally {
            pool.shutdownNow()
        }
        JdwpDebugLog.d("port-scan: ${openPorts.size} open port(s): ${openPorts.sorted().joinToString().take(200)}")

        // 2) For each open port, try the actual adb handshake. The one that
        //    completes the adb protocol is the connect port.
        for (port in openPorts.sorted()) {
            try {
                AdbClient.openShell(ip, port, connectTimeout = 3000L, maxRetryCount = 1).use { _ ->
                    JdwpDebugLog.d("port-scan: adb handshake OK on $ip:$port")
                }
                return AdbConnectionInfo(ip, port)
            } catch (_: Throwable) {
                // not an adb port; keep looking
            }
        }
        JdwpDebugLog.w("port-scan: no adb connect port found in $start-$end")
        return null
    }

    private fun wifiIpAddress(): String? {
        // Prefer a real (non-loopback) site-local IPv4 address (Wi-Fi).
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
