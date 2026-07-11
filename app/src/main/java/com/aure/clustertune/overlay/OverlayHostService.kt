package com.aure.clustertune.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.aure.clustertune.AppContainer
import com.aure.clustertune.MainActivity
import com.aure.clustertune.R
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

class OverlayHostService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    override val viewModelStore = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private val container by lazy { AppContainer(this) }
    private val windowController by lazy { OverlayWindowController(this) }
    private val viewModel by lazy {
        ViewModelProvider(
            this,
            TunerViewModel.factory(
                repository = container.repository,
                settingsStorage = container.settingsStorage,
                privilegedExecutionResolver = container.privilegedExecutionResolver,
                installedAppRepository = container.installedAppRepository,
            ),
        )[TunerViewModel::class.java]
    }
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                dismissOverlay(OverlayType.COMPACT_TUNER_MODAL)
            }
        }
    }

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SHOW_COMPACT_TUNER -> showCompactTunerOverlay()
            ACTION_SHOW_PROFILE_PICKER -> showCompactProfilePickerOverlay()
            ACTION_DISMISS -> dismissOverlay(intent.overlayTypeExtra())
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        windowController.dismiss()
        viewModelStore.clear()
        super.onDestroy()
    }

    private fun showCompactTunerOverlay() {
        if (!OverlayPermission.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing; cannot show compact tuner overlay")
            stopIfIdle()
            return
        }
        val view = buildCompactTunerView()
        runCatching {
            windowController.show(OverlayType.COMPACT_TUNER_MODAL, view)
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to show compact tuner overlay", throwable)
            stopIfIdle()
        }
    }

    private fun showCompactProfilePickerOverlay() {
        if (!OverlayPermission.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing; cannot show compact profile picker overlay")
            stopIfIdle()
            return
        }
        val view = buildCompactProfilePickerView()
        runCatching {
            windowController.show(OverlayType.COMPACT_PROFILE_PICKER, view)
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to show compact profile picker overlay", throwable)
            stopIfIdle()
        }
    }

    private fun buildCompactTunerView(): ComposeView {
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayHostService)
            setViewTreeViewModelStoreOwner(this@OverlayHostService)
            setViewTreeSavedStateRegistryOwner(this@OverlayHostService)
            setContent {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val state by viewModel.state.collectAsStateWithLifecycle()
                ClusterTuneTheme(settings = settings) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.46f))
                            .clickable { dismissOverlay(OverlayType.COMPACT_TUNER_MODAL) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .widthIn(max = 720.dp)
                                .fillMaxHeight(0.92f)
                                .padding(vertical = 12.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {},
                        ) {
                            CompactTunerScreen(
                                state = state,
                                displayFrequenciesAsPercent = settings.displayFrequenciesAsPercent,
                                onPolicyValueChange = viewModel::setPolicyValue,
                                onApplyProfile = viewModel::applyProfile,
                                onClearSelection = viewModel::clearSelection,
                                onApplyCurrent = { tunerState -> applyCurrentFromOverlay(tunerState) },
                                onDismissRequest = { dismissOverlay(OverlayType.COMPACT_TUNER_MODAL) },
                                onRefreshLiveValues = viewModel::refreshLiveState,
                                onOpenFullApp = ::openFullApp,
                                showCompactScrim = false,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun buildCompactProfilePickerView(): ComposeView {
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayHostService)
            setViewTreeViewModelStoreOwner(this@OverlayHostService)
            setViewTreeSavedStateRegistryOwner(this@OverlayHostService)
            setContent {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val state by viewModel.state.collectAsStateWithLifecycle()
                ClusterTuneTheme(settings = settings) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.46f))
                            .clickable { dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 380.dp)
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {},
                        ) {
                            CompactProfilePickerScreen(
                                state = state,
                                onApplyProfile = { profile -> applyProfileFromOverlay(state, profile) },
                                onDismissRequest = { dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER) },
                                showCompactScrim = false,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun applyCurrentFromOverlay(state: TunerState) {
        lifecycleScope.launch {
            val handler = QuickTunerApplyHandler(
                repository = PerformanceQuickTunerApplyRepository(container.repository),
                showToast = { message, duration -> SingleToast.show(applicationContext, message, duration) },
                refreshTile = { QuickSettingsTileRefresher.requestUpdate(applicationContext) },
            )
            handler.applyCurrent(state).onSuccess {
                dismissOverlay(OverlayType.COMPACT_TUNER_MODAL)
            }
        }
    }

    private fun applyProfileFromOverlay(state: TunerState, profile: PerformanceProfile) {
        lifecycleScope.launch {
            val handler = QuickTunerApplyHandler(
                repository = PerformanceQuickTunerApplyRepository(container.repository),
                showToast = { message, duration -> SingleToast.show(applicationContext, message, duration) },
                refreshTile = { QuickSettingsTileRefresher.requestUpdate(applicationContext) },
            )
            handler.applyProfile(state, profile).onSuccess {
                dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
            }
        }
    }

    private fun dismissOverlay(type: OverlayType? = null) {
        windowController.dismiss(type)
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (!windowController.hasActiveOverlay) {
            stopSelf()
        }
    }

    private fun openFullApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        dismissOverlay()
    }

    private fun registerScreenReceiver() {
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenReceiverRegistered = true
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ClusterTune overlays",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            description = "Hosts temporary ClusterTune overlay controls."
        }
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_underclock)
            .setContentTitle("ClusterTune controls")
            .setContentText("Showing quick tuning controls over the current app.")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    private fun Intent.overlayTypeExtra(): OverlayType? {
        val rawType = getStringExtra(EXTRA_OVERLAY_TYPE) ?: return null
        return runCatching { OverlayType.valueOf(rawType) }.getOrNull()
    }

    companion object {
        private const val TAG = "OverlayHostService"
        private const val CHANNEL_ID = "clustertune_overlays"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_SHOW_COMPACT_TUNER = "com.aure.clustertune.overlay.SHOW_COMPACT_TUNER"
        private const val ACTION_SHOW_PROFILE_PICKER = "com.aure.clustertune.overlay.SHOW_PROFILE_PICKER"
        private const val ACTION_DISMISS = "com.aure.clustertune.overlay.DISMISS"
        private const val EXTRA_OVERLAY_TYPE = "overlay_type"

        fun showCompactTuner(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayHostService::class.java).apply {
                    action = ACTION_SHOW_COMPACT_TUNER
                },
            )
        }

        fun showProfilePicker(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayHostService::class.java).apply {
                    action = ACTION_SHOW_PROFILE_PICKER
                },
            )
        }

        fun dismiss(context: Context, overlayType: OverlayType? = null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayHostService::class.java).apply {
                    action = ACTION_DISMISS
                    overlayType?.let { putExtra(EXTRA_OVERLAY_TYPE, it.name) }
                },
            )
        }
    }
}
