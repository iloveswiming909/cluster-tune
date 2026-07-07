package com.aure.clustertune.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import com.aure.clustertune.model.InstalledAppInfo

class InstalledAppRepository(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    @Suppress("DEPRECATION")
    fun listLaunchableApps(): List<InstalledAppInfo> {
        return launchableAppsByPackage()
            .values
            .asSequence()
            .map { appInfo -> appInfo.toInstalledAppInfo() }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun launchableAppsByPackage(): Map<String, android.content.pm.ApplicationInfo> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .map { resolveInfo -> resolveInfo.activityInfo.applicationInfo }
            .filter { appInfo -> appInfo.enabled }
            .distinctBy { appInfo -> appInfo.packageName }
            .associateBy { appInfo -> appInfo.packageName }
    }

    @Suppress("DEPRECATION")
    fun listRecentActiveApps(limit: Int = RECENT_APPS_LIMIT): List<InstalledAppInfo> {
        if (!hasUsageStatsPermission()) return emptyList()

        val usageStatsManager = appContext.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - RECENT_LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        val lastActiveByPackage = linkedMapOf<String, Long>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                lastActiveByPackage[event.packageName] = event.timeStamp
            }
        }

        val launchableAppsByPackage = launchableAppsByPackage()
        return lastActiveByPackage.entries
            .sortedByDescending { it.value }
            .mapNotNull { (packageName, _) -> launchableAppsByPackage[packageName]?.toInstalledAppInfo() }
            .distinctBy { it.packageName }
            .take(limit)
    }

    private fun android.content.pm.ApplicationInfo.toInstalledAppInfo(): InstalledAppInfo {
        return InstalledAppInfo(
            packageName = packageName,
            label = loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: packageName,
            icon = loadIcon(packageManager),
        )
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = appContext.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            appContext.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private companion object {
        private const val RECENT_APPS_LIMIT = 5
        private const val RECENT_LOOKBACK_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}
