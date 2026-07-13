package com.aure.clustertune.jdwp

import android.content.Context
import com.wuyr.jdwp_injector.adb.AdbWirelessPairing
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
        val handle: (String, Int) -> Unit = { host, port ->
            val info = AdbConnectionInfo(host, port)
            connectionInfo = info
            onConnected(info)
        }
        connectResolver = with(appContext) {
            resolveAdbTcpConnectPort { host, port -> handle(host, port) }
        }
        wirelessConnectResolver = with(appContext) {
            resolveAdbWirelessConnectPort(onLost = { onUnavailable() }) { host, port ->
                handle(host, port)
            }
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
        pairingResolver = with(appContext) {
            resolveAdbPairingPort(onLost = { onLost() }) { host, port ->
                pairingHost = host
                pairingPort = port
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
        runCatching {
            AdbWirelessPairing(host, port, code).use { it.start() }
        }.onSuccess {
            onPaired()
        }.onFailure { onError(it) }
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
