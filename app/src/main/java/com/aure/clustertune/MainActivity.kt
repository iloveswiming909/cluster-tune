package com.aure.clustertune

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aure.clustertune.apps.AppProfileMonitorService
import com.aure.clustertune.sleep.SleepProfileMonitorService
import com.aure.clustertune.tile.QuickSettingsTileAddResult
import com.aure.clustertune.tile.QuickSettingsTilePrompt
import com.aure.clustertune.tile.QuickSettingsTileRefresher
import com.aure.clustertune.root.ShizukuCommandRunner
import com.aure.clustertune.ui.MainTunerScreen
import com.aure.clustertune.ui.SettingsScreen
import com.aure.clustertune.ui.TunerViewModel
import com.aure.clustertune.ui.theme.ClusterTuneTheme
import com.aure.clustertune.update.AppRelease
import com.aure.clustertune.update.AppUpdateManager
import com.aure.clustertune.update.InstallLaunchResult
import com.aure.clustertune.update.UpdateCheckPolicy
import com.aure.clustertune.update.UpdateCheckResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container by lazy { AppContainer(this) }
    private val appUpdateManager by lazy { AppUpdateManager(this) }
    private val shizukuCommandRunner by lazy { ShizukuCommandRunner() }
    private val pendingUpdateRelease = mutableStateOf<AppRelease?>(null)
    private val viewModel by viewModels<TunerViewModel> {
        TunerViewModel.factory(
            repository = container.repository,
            settingsStorage = container.settingsStorage,
            privilegedExecutionResolver = container.privilegedExecutionResolver,
            installedAppRepository = container.installedAppRepository,
        )
    }
    private val exportProfilesLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let(::exportProfilesToUri)
    }
    private val importProfilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(::importProfilesFromUri)
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        lifecycleScope.launch {
            val settings = container.settingsStorage.settings.first()
            if (settings.sleepProfileEnabled) {
                SleepProfileMonitorService.start(this@MainActivity)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestQuickSettingsTileOnFirstRun()
        maybeAutoDetectPrivilegedExecutionOnFirstRun()
        maybeCheckForUpdatesOnLaunch()
        maybeStartAppProfileMonitor()

        setContent {
            val settings = viewModel.settings.collectAsStateWithLifecycle().value
            ClusterTuneTheme(settings = settings) {
                Surface {
                    val state = viewModel.state.collectAsStateWithLifecycle().value
                    val launchableApps = viewModel.launchableApps.collectAsStateWithLifecycle().value
                    var showSettings by rememberSaveable { mutableStateOf(false) }

                    if (showSettings) {
                        SettingsScreen(
                            settings = settings,
                            onBack = { showSettings = false },
                            onColorSourceChange = viewModel::setColorSource,
                            onAccentColorChange = viewModel::setAccentColor,
                            onCustomAccentColorChange = viewModel::setCustomAccentColor,
                            onTileTapBehaviorChange = { behavior ->
                                viewModel.setTileTapBehavior(behavior) {
                                    QuickSettingsTileRefresher.requestUpdate(this@MainActivity)
                                }
                            },
                            onApplyLastProfileOnBootChange = viewModel::setApplyLastProfileOnBoot,
                            sleepProfileOptions = state.displayProfiles,
                            onSleepProfileEnabledChange = { enabled ->
                                val profileId = settings.sleepProfileId
                                    ?: state.displayProfiles.firstOrNull()?.id
                                viewModel.configureSleepProfile(enabled, profileId) {
                                    if (enabled) {
                                        startSleepProfileMonitor()
                                    } else {
                                        SleepProfileMonitorService.stop(this@MainActivity)
                                    }
                                }
                            },
                            onSleepProfileChange = viewModel::setSleepProfile,
                            onResetProfiles = viewModel::resetProfilesToDefault,
                            onExportProfiles = {
                                exportProfilesLauncher.launch("clustertune-profiles.json")
                            },
                            onImportProfiles = {
                                importProfilesLauncher.launch(arrayOf("application/json", "text/*"))
                            },
                            onRequestAddQuickSettingsTile = {
                                requestQuickSettingsTile(showResultToast = true)
                            },
                            canRequestAddQuickSettingsTile = QuickSettingsTilePrompt.isSupported,
                            isQuickSettingsTileAdded = settings.isQuickSettingsTileAdded,
                            onCheckForUpdates = { checkForUpdates(showUpToDateToast = true) },
                            onAutomaticUpdateChecksEnabledChange = viewModel::setAutomaticUpdateChecksEnabled,
                            onUpdateCheckIntervalDaysChange = viewModel::setUpdateCheckIntervalDays,
                            onPrivilegedExecutionMethodChange = viewModel::setPrivilegedExecutionMethod,
                            onAutoDetectPrivilegedExecutionMethod = viewModel::autoDetectPrivilegedExecutionMethod,
                            isShizukuPermissionGranted = shizukuCommandRunner.hasPermission(),
                            onRequestShizukuPermission = ::requestShizukuPermission,
                        )
                    } else {
                        MainTunerScreen(
                            state = state,
                            sleepProfileId = settings.sleepProfileId.takeIf { settings.sleepProfileEnabled },
                            onApplyProfile = viewModel::applyProfile,
                            onApplyCurrent = { tunerState ->
                                viewModel.applyCurrent(tunerState) {
                                    QuickSettingsTileRefresher.requestUpdate(this@MainActivity)
                                }
                            },
                            onCreateProfile = viewModel::createUserProfile,
                            onUpdateProfile = viewModel::updateProfile,
                            onDeleteProfile = viewModel::deleteProfile,
                            onMoveProfile = viewModel::moveProfile,
                            launchableApps = launchableApps,
                            onSaveAppProfileAssignment = { packageName, appLabel, profileId ->
                                viewModel.saveAppProfileAssignment(packageName, appLabel, profileId)
                                startAppProfileMonitor()
                            },
                            onDeleteAppProfileAssignment = viewModel::deleteAppProfileAssignment,
                            onRefreshInstalledApps = viewModel::refreshInstalledApps,
                            onOpenSettings = { showSettings = true },
                            onRefreshLiveValues = viewModel::refreshLiveState,
                            onStatusMessageShown = viewModel::consumeStatusMessage,
                            onErrorMessageShown = viewModel::consumeErrorMessage,
                        )
                    }
                    pendingUpdateRelease.value?.let { release ->
                        UpdateAvailableDialog(
                            release = release,
                            onDismiss = { pendingUpdateRelease.value = null },
                            onInstall = {
                                pendingUpdateRelease.value = null
                                downloadAndInstallUpdate(release)
                            },
                        )
                    }
                }
            }
        }
    }

    private fun startSleepProfileMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        SleepProfileMonitorService.start(this)
    }

    private fun startAppProfileMonitor() {
        if (!AppProfileMonitorService.hasUsageStatsPermission(this)) {
            Toast.makeText(
                this,
                "Grant Usage Access to enable per-app profiles",
                Toast.LENGTH_LONG,
            ).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        AppProfileMonitorService.start(this)
    }

    private fun maybeRequestQuickSettingsTileOnFirstRun() {
        lifecycleScope.launch {
            val settings = container.settingsStorage.settings.first()
            if (settings.hasPromptedQuickSettingsTile) return@launch

            container.settingsStorage.persistQuickSettingsTilePromptShown()
            if (QuickSettingsTilePrompt.isSupported) {
                requestQuickSettingsTile(showResultToast = false)
            }
        }
    }

    private fun maybeAutoDetectPrivilegedExecutionOnFirstRun() {
        lifecycleScope.launch {
            val settings = container.settingsStorage.settings.first()
            if (settings.privilegedExecutionMethodId != null) return@launch

            val methodId = container.privilegedExecutionResolver.autoDetectBestMethod(forceReprobe = true)
            container.settingsStorage.persistPrivilegedExecutionMethodId(methodId)
        }
    }

    private fun maybeStartAppProfileMonitor() {
        lifecycleScope.launch {
            if (container.repository.observeState().first().appProfileAssignments.isNotEmpty() &&
                AppProfileMonitorService.hasUsageStatsPermission(this@MainActivity)
            ) {
                startAppProfileMonitor()
            }
        }
    }

    private fun requestQuickSettingsTile(showResultToast: Boolean) {
        QuickSettingsTilePrompt.request(this) { result ->
            if (result == QuickSettingsTileAddResult.ADDED || result == QuickSettingsTileAddResult.ALREADY_ADDED) {
                lifecycleScope.launch {
                    container.settingsStorage.persistQuickSettingsTileAdded(true)
                }
            }
            if (!showResultToast) return@request
            Toast.makeText(
                applicationContext,
                result.toToastMessage(),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun requestShizukuPermission() {
        when {
            !shizukuCommandRunner.isBinderAlive() -> {
                Toast.makeText(applicationContext, "Shizuku is not running", Toast.LENGTH_LONG).show()
            }

            shizukuCommandRunner.hasPermission() -> {
                Toast.makeText(applicationContext, "Shizuku permission is already granted", Toast.LENGTH_SHORT).show()
            }

            else -> {
                shizukuCommandRunner.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                    .onSuccess {
                        Toast.makeText(applicationContext, "Shizuku permission requested", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { throwable ->
                        Toast.makeText(
                            applicationContext,
                            throwable.message ?: "Failed to request Shizuku permission",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        }
    }

    private fun maybeCheckForUpdatesOnLaunch() {
        lifecycleScope.launch {
            val settings = container.settingsStorage.settings.first()
            val nowMillis = System.currentTimeMillis()
            if (!UpdateCheckPolicy.shouldCheck(
                    enabled = settings.automaticUpdateChecksEnabled,
                    intervalDays = settings.updateCheckIntervalDays,
                    lastCheckMillis = settings.lastUpdateCheckMillis,
                    nowMillis = nowMillis,
                )
            ) {
                return@launch
            }
            container.settingsStorage.persistLastUpdateCheckMillis(nowMillis)
            checkForUpdates(showUpToDateToast = false)
        }
    }

    private fun checkForUpdates(showUpToDateToast: Boolean) {
        lifecycleScope.launch {
            if (showUpToDateToast) {
                Toast.makeText(applicationContext, "Checking for updates…", Toast.LENGTH_SHORT).show()
            }
            container.settingsStorage.persistLastUpdateCheckMillis(System.currentTimeMillis())
            appUpdateManager.checkForUpdates()
                .onSuccess { result ->
                    when (result) {
                        is UpdateCheckResult.UpToDate -> {
                            if (showUpToDateToast) {
                                Toast.makeText(
                                    applicationContext,
                                    "ClusterTune is up to date (${result.currentVersionName})",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }

                        is UpdateCheckResult.UpdateAvailable -> {
                            pendingUpdateRelease.value = result.release
                        }
                    }
                }
                .onFailure { throwable ->
                    Toast.makeText(
                        applicationContext,
                        throwable.message ?: "Failed to check for updates",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun downloadAndInstallUpdate(release: AppRelease) {
        lifecycleScope.launch {
            Toast.makeText(
                applicationContext,
                "Downloading ${release.tagName}…",
                Toast.LENGTH_SHORT,
            ).show()
            appUpdateManager.downloadApk(release)
                .onSuccess { apkFile ->
                    when (appUpdateManager.installApk(apkFile)) {
                        InstallLaunchResult.Started -> Toast.makeText(
                            applicationContext,
                            "Opening installer for ${release.tagName}",
                            Toast.LENGTH_LONG,
                        ).show()

                        InstallLaunchResult.PermissionRequired -> Toast.makeText(
                            applicationContext,
                            "Allow ClusterTune to install unknown apps, then check again.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                .onFailure { throwable ->
                    Toast.makeText(
                        applicationContext,
                        throwable.message ?: "Failed to download update",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun exportProfilesToUri(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val json = viewModel.exportProfilesJson()
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(json.toByteArray())
                } ?: error("Unable to open export file")
            }.onSuccess {
                Toast.makeText(applicationContext, "Exported profiles", Toast.LENGTH_SHORT).show()
            }.onFailure { throwable ->
                Toast.makeText(
                    applicationContext,
                    throwable.message ?: "Failed to export profiles",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun importProfilesFromUri(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Unable to open import file")
                viewModel.importProfilesJson(json)
            }.onSuccess { importedCount ->
                Toast.makeText(
                    applicationContext,
                    "Imported $importedCount profiles",
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { throwable ->
                Toast.makeText(
                    applicationContext,
                    throwable.message ?: "Failed to import profiles",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun QuickSettingsTileAddResult.toToastMessage(): String {
        return when (this) {
            QuickSettingsTileAddResult.ADDED -> "Quick Settings tile added"
            QuickSettingsTileAddResult.ALREADY_ADDED -> "Quick Settings tile is already added"
            QuickSettingsTileAddResult.NOT_ADDED -> "Quick Settings tile was not added"
            QuickSettingsTileAddResult.UNAVAILABLE -> "Quick Settings tile prompt is unavailable on this device"
            QuickSettingsTileAddResult.ERROR -> "Failed to request Quick Settings tile"
        }
    }

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 4100
    }
}

@Composable
private fun UpdateAvailableDialog(
    release: AppRelease,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update ${release.tagName} available") },
        text = {
            Text(
                text = release.body
                    ?.takeIf { it.isNotBlank() }
                    ?.take(2_000)
                    ?: "No changelog was provided for this release.",
            )
        },
        confirmButton = {
            TextButton(onClick = onInstall) {
                Text("Install")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now")
            }
        },
    )
}

