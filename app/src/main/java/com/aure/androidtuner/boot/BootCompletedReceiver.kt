package com.aure.androidtuner.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aure.androidtuner.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = AppContainer(context)
                val settings = container.settingsStorage.settings.first()
                if (!settings.applyLastPresetOnBoot) {
                    return@launch
                }
                container.repository.applyPersistedLastValuesOnBoot()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
