package com.aure.clustertune

import android.content.Context
import com.aure.clustertune.data.BundledProfileProvider
import com.aure.clustertune.data.CpuPolicyDetector
import com.aure.clustertune.data.InstalledAppRepository
import com.aure.clustertune.data.PerformanceRepository
import com.aure.clustertune.data.ProfileStorage
import com.aure.clustertune.data.SettingsStorage
import com.aure.clustertune.jdwp.WirelessDebugConnectionManager
import com.aure.clustertune.root.ExecutionMethodSysfsLister
import com.aure.clustertune.root.PerformanceCommandBuilder
import com.aure.clustertune.root.PServerSysfsReader
import com.aure.clustertune.root.PrivilegedExecutionResolver
import com.aure.clustertune.root.RootCommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Holds the on-device wireless-debugging connection used by the no-root
     * JDWP injection execution method. Populated by the wireless-debugging
     * setup UI (pairing + port discovery).
     */
    val wirelessDebugConnectionManager: WirelessDebugConnectionManager by lazy {
        WirelessDebugConnectionManager()
    }

    val privilegedExecutionResolver: PrivilegedExecutionResolver by lazy {
        PrivilegedExecutionResolver.default(
            appContext,
            jdwpConnectionProvider = wirelessDebugConnectionManager.provider(),
        )
    }

    val settingsStorage: SettingsStorage by lazy {
        SettingsStorage(appContext)
    }

    val installedAppRepository: InstalledAppRepository by lazy {
        InstalledAppRepository(appContext)
    }

    init {
        appScope.launch {
            settingsStorage.settings.collect { settings ->
                privilegedExecutionResolver.setConfiguredMethodId(settings.privilegedExecutionMethodId)
            }
        }
    }

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
}
