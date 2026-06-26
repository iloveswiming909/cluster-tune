package com.aure.clustertune.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.aure.clustertune.model.InstalledAppInfo

class InstalledAppRepository(context: Context) {
    private val packageManager = context.applicationContext.packageManager

    fun listLaunchableApps(): List<InstalledAppInfo> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName ?: return@mapNotNull null
                InstalledAppInfo(
                    packageName = packageName,
                    label = resolveInfo.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() }
                        ?: packageName,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
