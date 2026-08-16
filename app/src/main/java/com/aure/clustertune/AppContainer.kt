package com.aure.clustertune

import android.content.Context
import android.util.Log
import com.aure.clustertune.data.BundledProfileProvider
import com.aure.clustertune.data.CpuPolicyDetector
import com.aure.clustertune.data.InstalledAppRepository
import com.aure.clustertune.data.PerformanceRepository
import com.aure.clustertune.data.ProfileStorage
import com.aure.clustertune.data.SettingsStorage
import com.aure.clustertune.daemon.SystemDaemonBootstrapper
import com.aure.clustertune.daemon.SystemDaemonExecutionMethod
import com.aure.clustertune.jdwp.WirelessDebugConnectionManager
import com.aure.clustertune.root.ExecutionMethodSysfsLister
import com.aure.clustertune.root.PerformanceCommandBuilder
import com.aure.clustertune.root.PServerSysfsReader
import com.aure.clustertune.root.PrivilegedExecutionResolver
import com.aure.clustertune.root.RootCommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class AppContainer(context: Context) {
    private companion object {
        const val TAG = "AppContainer"
        const val SYSFS_REPAIR_ATTEMPTS = 5
        const val SYSFS_REPAIR_INITIAL_DELAY_MS = 250L
    }

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Process-wide singleton: AppContainer is constructed by MainActivity,
     * services AND receivers. The setup screen writes the connection into one
     * instance while the resolver reads it from another, so they must share.
     */
    val wirelessDebugConnectionManager: WirelessDebugConnectionManager
        get() = WirelessDebugConnectionManager.getInstance(appContext)

    /**
     * Bootstraps and monitors the resident system-uid daemon.
     *
     * Background: Android tears wireless debugging down whenever Wi-Fi drops —
     * measured on the Odin 2 Mini, persist.adb.tls_server.enable flips 1 -> 0
     * and adbd stops listening entirely. So the JDWP path cannot serve per-app
     * profile switching while offline, regardless of which address it targets.
     * Instead we use JDWP ONCE per boot (while Wi-Fi is up) to launch a detached
     * `sh` daemon owned by uid=system, and every later apply talks to that
     * daemon over files. GameAssistant is only the launcher; nothing is injected
     * into it beyond the single Runtime.exec already used for a normal apply.
     */
    private val systemDaemonMethod = SystemDaemonExecutionMethod()

    private val systemDaemonBootstrapper by lazy {
        SystemDaemonBootstrapper(daemonMethod = systemDaemonMethod)
    }

    /** True when an offline-capable daemon is currently serving requests. */
    val isSystemDaemonAlive: Boolean
        get() = systemDaemonMethod.isDaemonAlive()

    /**
     * Start the daemon if it is not already running and a JDWP connection is
     * available to launch it with. Safe to call repeatedly; returns quickly when
     * there is nothing to do. Never blocks the caller.
     */
    fun bootstrapSystemDaemonIfPossible() {
        if (systemDaemonMethod.isDaemonAlive() &&
            systemDaemonMethod.runningDaemonVersion() ==
            com.aure.clustertune.daemon.SystemDaemonProtocol.DAEMON_VERSION
        ) {
            return
        }
        if (wirelessDebugConnectionManager.connectionInfo == null) return
        if (!daemonBootstrapInFlight.compareAndSet(false, true)) return
        appScope.launch {
            try {
                val jdwp = privilegedExecutionResolver.methodById("jdwp-inject")
                if (jdwp == null) {
                    Log.i(TAG, "Daemon bootstrap skipped: no JDWP method registered")
                    return@launch
                }
                val result = systemDaemonBootstrapper.ensureRunning(jdwp)
                Log.i(TAG, "Daemon bootstrap: $result")
                // Let the resolver re-select now that the daemon may be live, so
                // subsequent applies take the offline-capable path.
                privilegedExecutionResolver.autoDetectBestMethod()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.w(TAG, "Daemon bootstrap failed", error)
            } finally {
                daemonBootstrapInFlight.set(false)
            }
        }
    }

    private val daemonBootstrapInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    val privilegedExecutionResolver: PrivilegedExecutionResolver by lazy {
        PrivilegedExecutionResolver.default(
            appContext,
            jdwpConnectionProvider = wirelessDebugConnectionManager.provider(),
            jdwpSharedShellProvider = { wirelessDebugConnectionManager.sharedShell() },
            jdwpShellInvalidator = { wirelessDebugConnectionManager.invalidateShell() },
            jdwpPersistentInjector = { pkg, command, pid, trigger ->
                wirelessDebugConnectionManager.injectExecPersistent(pkg, command, pid, trigger)
            },
            jdwpShellUseLock = wirelessDebugConnectionManager.shellUseLock,
        )
    }

    val settingsStorage: SettingsStorage by lazy {
        SettingsStorage(appContext)
    }

    val installedAppRepository: InstalledAppRepository by lazy {
        InstalledAppRepository(appContext)
    }

    // Keep the repository delegate ahead of init so startup work can never observe a partially
    // initialized dependency graph when a container is created by a background component.
    val repository: PerformanceRepository by lazy {
        PerformanceRepository(
            detector = CpuPolicyDetector(
                privilegedReader = PServerSysfsReader(
                    context = appContext,
                    executionResolver = privilegedExecutionResolver,
                ),
                privilegedLister = ExecutionMethodSysfsLister(privilegedExecutionResolver),
            ),
            bundledProfileProvider = BundledProfileProvider(appContext),
            profileStorage = ProfileStorage(appContext),
            settingsStorage = settingsStorage,
            commandBuilder = PerformanceCommandBuilder(),
            rootCommandRunner = RootCommandRunner(
                context = appContext,
                executionResolver = privilegedExecutionResolver,
            ),
        )
    }

    init {
        appScope.launch {
            settingsStorage.settings.collect { settings ->
                privilegedExecutionResolver.setConfiguredMethodId(settings.privilegedExecutionMethodId)
            }
        }
        appScope.launch {
            repairSysfsMinimumsWithRetry()
        }
    }

    /**
     * Repairs stale minimum nodes independently from apply-on-boot. The first attempt waits
     * until the persisted execution method has been loaded, otherwise a freshly-created
     * container can race the settings collector and incorrectly conclude that no executor is
     * available. Failures are retried briefly because the privileged service may be starting
     * at the same time as an overlay, tile, or boot receiver.
     */
    private suspend fun repairSysfsMinimumsWithRetry() {
        // Ensure all repository dependencies are initialized only on the worker dispatcher and
        // that the resolver sees the configured method before probing it.
        var delayMs = SYSFS_REPAIR_INITIAL_DELAY_MS
        var settingsLoaded = false
        repeat(SYSFS_REPAIR_ATTEMPTS) { attempt ->
            // Do NOT attempt the repair unless a privileged method is actually
            // usable right now.
            //
            // Upstream wrote this for root/PServer, where executeScript is cheap
            // and synchronous. On the no-root JDWP path it is not: each attempt
            // opens adb connections (making Android flash "Wireless debugging
            // connected" repeatedly), performs a JDWP injection, and does so
            // while holding processApplyMutex — which blocks every profile apply
            // — and while contending for AdbClient's global @Synchronized lock,
            // which produced ANRs. At startup there is never a connection yet, so
            // all 5 attempts were guaranteed to fail expensively.
            val methodId = privilegedExecutionResolver.selectedMethodId
            if (methodId == null) {
                Log.i(TAG, "Sysfs minimum repair skipped: no privileged method available yet")
                return
            }
            // Never run this migration on the fire-and-forget JDWP path.
            //
            // It is a 1.0.2 addition written for root/PServer, where executeScript
            // blocks until the command has run. It takes processApplyMutex — the
            // SAME mutex every profile apply needs — and retries 5x with backoff.
            // On the no-root path the write cannot be verified in that window, so
            // it always failed, held the mutex across all 5 attempts, and blocked
            // every apply (logs showed the JDWP session never even attaching).
            // The Odin does not need this migration; skipping it is correct.
            if (methodId == "jdwp-inject") {
                Log.i(TAG, "Sysfs minimum repair skipped: not applicable to the JDWP path")
                return
            }
            // The system daemon DOES block until the script has run and DOES
            // return stdout, so unlike the JDWP path the repair can complete and
            // be verified here. It is still allowed to run only once: a repeated
            // failure would hold processApplyMutex across all retries and block
            // profile applies, which is the same trap the JDWP path fell into.
            if (methodId == com.aure.clustertune.daemon.SystemDaemonExecutionMethod.METHOD_ID &&
                attempt > 0
            ) {
                Log.i(TAG, "Sysfs minimum repair: not retrying on the daemon path")
                return
            }
            val result = try {
                if (!settingsLoaded) {
                    val settings = settingsStorage.settings.first()
                    privilegedExecutionResolver.setConfiguredMethodId(settings.privilegedExecutionMethodId)
                    settingsLoaded = true
                }
                repository.repairSysfsMinimumsIfNeeded()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            if (result.isSuccess) {
                Log.i(TAG, "Sysfs minimum repair completed")
                return
            }

            val error = result.exceptionOrNull()
            Log.w(
                TAG,
                "Sysfs minimum repair attempt ${attempt + 1}/$SYSFS_REPAIR_ATTEMPTS failed: " +
                    (error?.message ?: "unknown error"),
                error,
            )
            if (attempt < SYSFS_REPAIR_ATTEMPTS - 1) {
                delay(delayMs)
                delayMs *= 2
            }
        }
        Log.e(TAG, "Sysfs minimum repair was not completed in this app process")
    }
}
