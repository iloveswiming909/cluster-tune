package com.aure.clustertune

import android.content.Context
import com.aure.clustertune.data.BundledProfileProvider
import com.aure.clustertune.data.CpuPolicyDetector
import com.aure.clustertune.data.GpuPolicyDetector
import com.aure.clustertune.data.SharedPreferencesGpuCeilingStore
import com.aure.clustertune.data.InstalledAppRepository
import com.aure.clustertune.data.PerformanceRepository
import com.aure.clustertune.data.ProfileStorage
import com.aure.clustertune.data.SettingsStorage
import com.aure.clustertune.jdwp.WirelessDebugConnectionManager
import com.aure.clustertune.root.PrivilegedExecutionResolver
import com.aure.clustertune.root.host.ClusterTuneHostClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.util.Log

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Process-wide singleton. MainActivity, the overlay service and the boot
     * receiver each build their own AppContainer, so anything holding adb
     * connection state has to be shared or the instances silently disagree
     * about whether a connection exists.
     */
    val wirelessDebugConnectionManager: WirelessDebugConnectionManager
        get() = WirelessDebugConnectionManager.getInstance(appContext)

    val privilegedExecutionResolver: PrivilegedExecutionResolver by lazy {
        PrivilegedExecutionResolver.default(
            context = appContext,
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

    val profileStorage: ProfileStorage by lazy {
        ProfileStorage(appContext)
    }

    // Keep the repository delegate ahead of init so startup work can never observe a partially
    // initialized dependency graph when a container is created by a background component.
    /**
     * Hoisted out of [repository] so the UI can ask whether the privileged host
     * is up. The host and the adb connection are independent: once the host is
     * running it serves over Binder and needs no network, so connection state
     * alone would misreport a working setup as soon as Wi-Fi is turned off.
     */
    val hostClient: ClusterTuneHostClient by lazy {
        ClusterTuneHostClient(appContext, privilegedExecutionResolver)
    }

    /** Cheap, non-blocking: does not attempt to start the host. */
    val isPrivilegedHostRunning: Boolean
        get() = hostClient.isRunning

    /**
     * Starts the privileged host if it is not already up.
     *
     * Blocking — call from a background dispatcher. On the jdwp path this
     * performs the JDWP attach and injection, then waits for the host's Binder
     * handoff broadcast, so it can take a couple of seconds.
     *
     * Safe to call repeatedly: [ClusterTuneHostClient.ensureStarted] returns
     * immediately when a live binder is already attached.
     */
    fun startPrivilegedHost(): Result<Unit> {
        val result = hostClient.ensureStarted()
        result.fold(
            onSuccess = { Log.i(TAG, "privileged host started via ${hostClient.selectedMethodId}") },
            onFailure = { Log.w(TAG, "privileged host failed to start", it) },
        )
        return result
    }

    val repository: PerformanceRepository by lazy {
        PerformanceRepository(
            detector = CpuPolicyDetector(
            ),
            gpuDetector = GpuPolicyDetector(
                ceilingStore = SharedPreferencesGpuCeilingStore(appContext),
            ),
            bundledProfileProvider = BundledProfileProvider(appContext),
            profileStorage = profileStorage,
            settingsStorage = settingsStorage,
            hostClient = hostClient,
        )
    }

    init {
        appScope.launch {
            settingsStorage.settings.collect { settings ->
                privilegedExecutionResolver.setConfiguredMethodId(settings.privilegedExecutionMethodId)
            }
        }
    }

    private companion object {
        const val TAG = "AppContainer"
    }

}