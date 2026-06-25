package com.aure.clustertune.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AppUpdateManager(
    private val context: Context,
    currentVersionName: String? = null,
    private val releasesUrl: String = DEFAULT_LATEST_RELEASE_URL,
) {
    private val currentVersionName: String = currentVersionName ?: context.currentVersionName()

    suspend fun checkForUpdates(): Result<UpdateCheckResult> = withContext(Dispatchers.IO) {
        runCatching {
            val json = httpGetText(releasesUrl)
            val release = GitHubReleaseParser.parseLatestRelease(json)
            val current = SemanticVersion.parse(currentVersionName)
            val latest = SemanticVersion.parse(release.versionName)
            if (current == null || latest == null || latest <= current) {
                UpdateCheckResult.UpToDate(currentVersionName)
            } else {
                UpdateCheckResult.UpdateAvailable(release)
            }
        }
    }

    suspend fun downloadApk(release: AppRelease): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val apkFile = File(context.cacheDir, "updates/${release.asset.name}")
            apkFile.parentFile?.mkdirs()
            val connection = openConnection(release.asset.downloadUrl)
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    error("Failed to download update: HTTP $code")
                }
                connection.inputStream.use { input ->
                    apkFile.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
            apkFile
        }
    }

    fun installApk(apkFile: File): InstallLaunchResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return InstallLaunchResult.PermissionRequired
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
        return InstallLaunchResult.Started
    }

    private fun httpGetText(url: String): String {
        val connection = openConnection(url)
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                error("Failed to check updates: HTTP $code")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ClusterTune/$currentVersionName")
        }
    }

    private fun Context.currentVersionName(): String {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        return packageInfo.versionName ?: "0.0.0"
    }

    companion object {
        private const val NETWORK_TIMEOUT_MS = 15_000
        const val DEFAULT_LATEST_RELEASE_URL =
            "https://api.github.com/repos/AurelioB/ClusterTune/releases/latest"
    }
}

sealed interface UpdateCheckResult {
    data class UpToDate(val currentVersionName: String) : UpdateCheckResult
    data class UpdateAvailable(val release: AppRelease) : UpdateCheckResult
}

data class AppRelease(
    val versionName: String,
    val tagName: String,
    val name: String?,
    val body: String?,
    val asset: ReleaseAsset,
)

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Int?,
)

enum class InstallLaunchResult {
    Started,
    PermissionRequired,
}

object UpdateCheckPolicy {
    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L

    fun shouldCheck(
        enabled: Boolean,
        intervalDays: Int,
        lastCheckMillis: Long,
        nowMillis: Long,
    ): Boolean {
        if (!enabled) return false
        if (lastCheckMillis <= 0L) return true
        val intervalMillis = intervalDays.coerceAtLeast(1) * MILLIS_PER_DAY
        return nowMillis - lastCheckMillis >= intervalMillis
    }
}

object GitHubReleaseParser {
    fun parseLatestRelease(rawJson: String): AppRelease {
        val root = Json.parseToJsonElement(rawJson).jsonObject
        val tagName = root.string("tag_name") ?: error("Release is missing tag_name")
        val versionName = tagName.removePrefix("v")
        val assets = root["assets"]?.jsonArray ?: JsonArray(emptyList())
        val asset = assets
            .map { it.jsonObject }
            .firstNotNullOfOrNull(::parseApkAsset)
            ?: error("Latest release $tagName does not include a ClusterTune APK asset")
        return AppRelease(
            versionName = versionName,
            tagName = tagName,
            name = root.string("name"),
            body = root.string("body"),
            asset = asset,
        )
    }

    private fun parseApkAsset(asset: JsonObject): ReleaseAsset? {
        val name = asset.string("name") ?: return null
        val downloadUrl = asset.string("browser_download_url") ?: return null
        if (!name.endsWith(".apk", ignoreCase = true)) return null
        if (!name.startsWith("ClusterTune-", ignoreCase = true)) return null
        return ReleaseAsset(
            name = name,
            downloadUrl = downloadUrl,
            sizeBytes = asset["size"]?.jsonPrimitive?.intOrNull,
        )
    }

    private fun JsonObject.string(name: String): String? {
        return this[name]?.jsonPrimitive?.contentOrNull
    }
}

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val suffix: String? = null,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)
            .takeIf { it != 0 }
            ?.let { return it }
        return when {
            suffix == null && other.suffix != null -> 1
            suffix != null && other.suffix == null -> -1
            else -> suffix.orEmpty().compareTo(other.suffix.orEmpty())
        }
    }

    companion object {
        private val pattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+]([0-9A-Za-z.-]+))?$")

        fun parse(raw: String): SemanticVersion? {
            val match = pattern.matchEntire(raw.trim()) ?: return null
            return SemanticVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
                suffix = match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() },
            )
        }
    }
}
