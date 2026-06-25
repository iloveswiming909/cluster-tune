package com.aure.clustertune

import android.content.Context
import com.aure.clustertune.data.BundledProfileProvider
import com.aure.clustertune.data.CpuPolicyDetector
import com.aure.clustertune.data.PerformanceRepository
import com.aure.clustertune.data.ProfileStorage
import com.aure.clustertune.data.SettingsStorage
import com.aure.clustertune.root.PerformanceCommandBuilder
import com.aure.clustertune.root.PServerSysfsReader
import com.aure.clustertune.root.PrivilegedExecutionResolver
import com.aure.clustertune.root.RootCommandRunner

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val privilegedExecutionResolver: PrivilegedExecutionResolver by lazy {
        PrivilegedExecutionResolver.default(appContext)
    }

    val settingsStorage: SettingsStorage by lazy {
        SettingsStorage(appContext)
    }

    val repository: PerformanceRepository by lazy {
        PerformanceRepository(
            detector = CpuPolicyDetector(
                privilegedReader = PServerSysfsReader(
                    context = appContext,
                    executionResolver = privilegedExecutionResolver,
                ),
            ),
            bundledProfileProvider = BundledProfileProvider(appContext),
            profileStorage = ProfileStorage(appContext),
            commandBuilder = PerformanceCommandBuilder(),
            rootCommandRunner = RootCommandRunner(
                context = appContext,
                executionResolver = privilegedExecutionResolver,
            ),
        )
    }
}
