package com.aure.clustertune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.TunerState
import com.aure.clustertune.quicktuner.PerformanceQuickTunerApplyRepository
import com.aure.clustertune.quicktuner.QuickTunerApplyHandler
import com.aure.clustertune.tile.QuickSettingsTileRefresher
import com.aure.clustertune.ui.CompactProfilePickerScreen
import com.aure.clustertune.ui.CompactTunerScreen
import com.aure.clustertune.ui.SingleToast
import com.aure.clustertune.ui.TunerViewModel
import com.aure.clustertune.ui.theme.ClusterTuneTheme
import kotlinx.coroutines.launch

class TileControlActivity : ComponentActivity() {

    companion object {
        private const val ACTION_OPEN_DIALOG = "com.aure.clustertune.action.OPEN_TILE_DIALOG"
        private const val ACTION_OPEN_PROFILE_PICKER = "com.aure.clustertune.action.OPEN_PROFILE_PICKER"
        private const val ACTION_QS_TILE_PREFERENCES = "android.service.quicksettings.action.QS_TILE_PREFERENCES"

        fun createDialogIntent(context: Context): Intent {
            return Intent(context, TileControlActivity::class.java).apply {
                action = ACTION_OPEN_DIALOG
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
        }

        fun createProfilePickerIntent(context: Context): Intent {
            return Intent(context, TileControlActivity::class.java).apply {
                action = ACTION_OPEN_PROFILE_PICKER
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
        }
    }

    private val container by lazy { AppContainer(this) }
    private val viewModel by viewModels<TunerViewModel> {
        TunerViewModel.factory(
            repository = container.repository,
            settingsStorage = container.settingsStorage,
            privilegedExecutionResolver = container.privilegedExecutionResolver,
            installedAppRepository = container.installedAppRepository,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val launchedFromLongPress = intent?.action == ACTION_QS_TILE_PREFERENCES
        if (launchedFromLongPress) {
            openFullApp()
            return
        }

        if (intent?.action == ACTION_OPEN_PROFILE_PICKER) {
            setProfilePickerContent()
        } else {
            setEditorContent()
        }
    }

    private fun setEditorContent() {
        setContent {
            val settings = viewModel.settings.collectAsStateWithLifecycle().value
            ClusterTuneTheme(settings = settings) {
                Surface {
                    val state = viewModel.state.collectAsStateWithLifecycle().value
                    CompactTunerScreen(
                        state = state,
                        displayFrequenciesAsPercent = settings.displayFrequenciesAsPercent,
                        onPolicyValueChange = viewModel::setPolicyValue,
                        onApplyProfile = viewModel::applyProfile,
                        onClearSelection = viewModel::clearSelection,
                        onApplyCurrent = { tunerState ->
                            applyCurrentFromDialog(tunerState)
                        },
                        onDismissRequest = ::dismissTileDialog,
                        onRefreshLiveValues = viewModel::refreshLiveState,
                        onOpenFullApp = {
                            openFullApp()
                        },
                    )
                }
            }
        }
    }

    private fun applyCurrentFromDialog(state: TunerState) {
        lifecycleScope.launch {
            val handler = QuickTunerApplyHandler(
                repository = PerformanceQuickTunerApplyRepository(container.repository),
                showToast = { message, duration -> SingleToast.show(applicationContext, message, duration) },
                refreshTile = { QuickSettingsTileRefresher.requestUpdate(applicationContext) },
            )
            handler.applyCurrent(state).onSuccess {
                dismissTileDialog()
            }
        }
    }

    private fun setProfilePickerContent() {
        setContent {
            val settings = viewModel.settings.collectAsStateWithLifecycle().value
            ClusterTuneTheme(settings = settings) {
                Surface {
                    val state = viewModel.state.collectAsStateWithLifecycle().value
                    CompactProfilePickerScreen(
                        state = state,
                        onApplyProfile = { profile -> applyProfileFromPicker(state, profile) },
                        onDismissRequest = ::dismissTileDialog,
                    )
                }
            }
        }
    }

    private fun applyProfileFromPicker(state: TunerState, profile: PerformanceProfile) {
        lifecycleScope.launch {
            val handler = QuickTunerApplyHandler(
                repository = PerformanceQuickTunerApplyRepository(container.repository),
                showToast = { message, duration -> SingleToast.show(applicationContext, message, duration) },
                refreshTile = { QuickSettingsTileRefresher.requestUpdate(applicationContext) },
            )
            handler.applyProfile(state, profile).onSuccess {
                dismissTileDialog()
            }
        }
    }

    private fun openFullApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }

    private fun dismissTileDialog() {
        finishAndRemoveTask()
    }
}
