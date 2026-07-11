package com.aure.clustertune.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object OverlayPermission {
    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun createSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            packageUri(context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    internal fun packageUri(packageName: String): Uri {
        return Uri.parse(packageUriString(packageName))
    }

    internal fun packageUriString(packageName: String): String {
        return "package:$packageName"
    }
}
