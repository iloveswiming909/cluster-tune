package com.aure.clustertune.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import com.aure.clustertune.AppContainer
import com.aure.clustertune.R
import com.aure.clustertune.TileControlActivity
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.TileInteractionBehavior
import com.aure.clustertune.model.TunerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PerformanceTileService : TileService() {

    companion object {
        private const val TAG = "PerformanceTile"
    }

    override fun onTileAdded() {
        super.onTileAdded()
        persistTileAddedState(isAdded = true)
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        persistTileAddedState(isAdded = false)
    }

    override fun onStartListening() {
        super.onStartListening()
        persistTileAddedState(isAdded = true)
        runCatching {
            val container = AppContainer(applicationContext)
            val state = runBlocking { container.repository.observeState().first() }
            qsTile?.apply {
                label = getString(R.string.tile_title)
                subtitle = buildTileSubtitle(state)
                this.state = buildTileVisualState(state)
                updateTile()
            }
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to refresh tile state", throwable)
            qsTile?.apply {
                label = getString(R.string.tile_title)
                subtitle = getString(R.string.tile_state_unavailable)
                state = Tile.STATE_INACTIVE
                updateTile()
            }
        }
    }

    private fun buildTileSubtitle(state: TunerState): String {
        return when {
            !state.isPServerAvailable -> getString(R.string.tile_state_unavailable)
            else -> state.activeDisplayProfileName ?: getString(R.string.tile_state_manual)
        }
    }

    private fun buildTileVisualState(state: TunerState): Int {
        if (!state.isPServerAvailable) return Tile.STATE_INACTIVE
        val activeName = state.activeDisplayProfileName
        val stockIsActive = state.activeDisplayProfileId == ProfileStateResolver.STOCK_PROFILE_ID ||
            activeName == "Stock"
        return if (stockIsActive) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
    }

    private fun persistTileAddedState(isAdded: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            AppContainer(applicationContext).settingsStorage.persistQuickSettingsTileAdded(isAdded)
        }
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun(::handleTap)
        } else {
            handleTap()
        }
    }

    private fun handleTap() {
        runCatching {
            val container = AppContainer(applicationContext)
            val settings = runBlocking { container.settingsStorage.settings.first() }
            when (settings.tileTapBehavior) {
                TileInteractionBehavior.SHOW_DIALOG -> {
                    launchDialogAndCollapse()
                }
                TileInteractionBehavior.OPEN_APP -> {
                    launchAppAndCollapse()
                }
                TileInteractionBehavior.CYCLE_PROFILES -> {
                    runBlocking {
                        container.repository.cycleTileProfile()
                            .onSuccess { profile ->
                                Toast.makeText(
                                    applicationContext,
                                    "Applied ${profile.name}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            .onFailure { throwable ->
                                Toast.makeText(
                                    applicationContext,
                                    throwable.message ?: "Failed to cycle profile",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                    }
                    onStartListening()
                }
            }
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to handle tile tap", throwable)
            Toast.makeText(
                applicationContext,
                throwable.message ?: "Failed to handle tile tap",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun launchDialogAndCollapse() {
        val intent = TileControlActivity.createDialogIntent(applicationContext)
        launchIntentAndCollapse(intent)
    }

    @Suppress("DEPRECATION")
    private fun launchAppAndCollapse() {
        val intent = Intent(applicationContext, com.aure.clustertune.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        launchIntentAndCollapse(intent)
    }

    @Suppress("DEPRECATION")
    private fun launchIntentAndCollapse(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
