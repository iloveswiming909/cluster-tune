package com.aure.clustertune.apps

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.aure.clustertune.AppContainer
import com.aure.clustertune.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

class AppProfileMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private lateinit var container: AppContainer
    private var activeAssignedPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        monitorJob = scope.launch { monitorForegroundApps() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun monitorForegroundApps() {
        while (currentCoroutineContext().isActive) {
            val assignments = container.repository.observeState().first().appProfileAssignments
            if (assignments.isEmpty()) {
                if (activeAssignedPackage != null) {
                    activeAssignedPackage = null
                    container.repository.restoreNormalProfileTemporarily()
                }
                delay(POLL_INTERVAL_MS)
                continue
            }
            if (!hasUsageStatsPermission(this@AppProfileMonitorService)) {
                delay(POLL_INTERVAL_MS)
                continue
            }
            val foregroundPackage = currentForegroundPackage()
            val assignment = assignments.firstOrNull { it.packageName == foregroundPackage }
            when {
                assignment != null && assignment.packageName != activeAssignedPackage -> {
                    activeAssignedPackage = assignment.packageName
                    container.repository.applyProfileTemporarily(assignment.profileId)
                }
                assignment == null && activeAssignedPackage != null -> {
                    activeAssignedPackage = null
                    container.repository.restoreNormalProfileTemporarily()
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun currentForegroundPackage(): String? {
        val usageStatsManager = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var foregroundPackage: String? = null
        var lastTimestamp = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForeground = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            if (isForeground && event.timeStamp >= lastTimestamp) {
                foregroundPackage = event.packageName
                lastTimestamp = event.timeStamp
            }
        }
        return foregroundPackage
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "App profile automation",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_underclock)
            .setContentTitle("ClusterTune app profiles")
            .setContentText("Watching focused apps to apply assigned profiles")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "app_profile_monitor"
        private const val NOTIFICATION_ID = 2002
        private const val POLL_INTERVAL_MS = 2_000L
        private const val LOOKBACK_MS = 10_000L

        fun start(context: Context) {
            if (!hasUsageStatsPermission(context)) return
            val intent = Intent(context, AppProfileMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AppProfileMonitorService::class.java))
        }

        fun hasUsageStatsPermission(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
