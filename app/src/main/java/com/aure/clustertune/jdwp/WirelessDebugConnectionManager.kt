package com.aure.clustertune.jdwp

import android.content.Context
import android.util.Log
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
class WirelessDebugConnectionManager(
    context: Context,
) {

    private val appContext = context.applicationContext

    @Volatile
    var connectionInfo: AdbConnectionInfo? = null
        private set

    private var connectResolver: AdbWirelessPortResolver? = null
    private var wirelessConnectResolver: AdbWirelessPortResolver? = null
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
    fun startConnectDiscovery(
        onConnected: (AdbConnectionInfo) -> Unit,
        onUnavailable: () -> Unit = {},
    ) {
        stopConnectDiscovery()
        JdwpDebugLog.d("startConnectDiscovery: looking for adb connect port (mDNS)...")
        val handle: (String, Int) -> Unit = { host, port ->
            val info = AdbConnectionInfo(host, port)
            connectionInfo = info
            JdwpDebugLog.d("startConnectDiscovery: CONNECTED $host:$port")
            onConnected(info)
        }
        connectResolver = with(appContext) {
            resolveAdbWirelessConnectPort(onLost = {
                JdwpDebugLog.d("startConnectDiscovery: connect port lost/unavailable")
                onUnavailable()
            }) { host, port -> handle(host, port) }
        }
        // Note: we intentionally start ONLY the _adb-tls-connect._tcp discovery.
        // On Android < 14, NsdManager resolves one service at a time; running a
        // second discovery (_adb._tcp) concurrently caused resolves to fail with
        // FAILURE_ALREADY_ACTIVE. The TLS-connect service is the correct one for
        // a paired device on Android 11+.
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

    private companion object {
        const val TAG = "ClusterTuneJdwpConn"
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
}
