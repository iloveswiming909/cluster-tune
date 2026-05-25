package com.aure.clustertune.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Workaround for the Odin 2 Mini's broken pservice.
 *
 * On the Mini, pservice (the binder service ClusterTune uses to run
 * commands as root on the rest of the Odin family) is broken: binder
 * transactions complete without throwing, but the reply payload is
 * always empty. Writes look like they succeed at the API layer but
 * nothing reaches the kernel. AYN provides a workaround in their
 * customised Settings app: a "Run script as root" page that executes
 * a `.sh` from internal storage with full privileges. We can't call
 * that page directly (the activity is not exported), but we can write
 * the script to a known location, launch Odin Settings, and let the
 * user navigate the remaining few taps themselves.
 *
 * On non-Mini devices this class is a no-op: [isAvailable] returns
 * false unless the Odin Settings package is installed, and even then
 * the caller should only invoke [writeScriptAndLaunch] after the
 * normal PServer apply path has failed verification.
 */
class OdinScriptHandoff(private val context: Context) {

    /**
     * True if the device exposes the AYN-customised Settings app with
     * a "Run script as root" feature. Currently this is the Odin 2/3
     * family and any future AYN device that ships the same package.
     */
    val isAvailable: Boolean
        get() = isPackageInstalled(ODIN_SETTINGS_PACKAGE)

    /**
     * Writes the apply script to a stable location under the public
     * Downloads directory and launches Odin Settings's main screen so
     * the user can finish the apply via "Run script as root". The user
     * will see the script under [scriptDirectory] / [SCRIPT_FILENAME]
     * in Odin Settings's file picker.
     *
     * Returns the absolute path of the script that was written, so the
     * UI can show it in the handoff dialog and tutorial. Returns null
     * if writing the file failed.
     */
    fun writeScriptAndLaunch(scriptContents: String): String? {
        val scriptPath = writeScript(scriptContents) ?: return null
        launchOdinSettings()
        return scriptPath
    }

    /**
     * Writes the script without launching anything. Used for tests and
     * for callers that want to display the path before launching.
     */
    fun writeScript(scriptContents: String): String? {
        return runCatching {
            val dir = scriptDirectory()
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, SCRIPT_FILENAME)
            file.writeText(scriptContents)
            file.setReadable(true, false)
            Log.d(TAG, "Wrote handoff script to ${file.absolutePath} (${scriptContents.length} bytes)")
            file.absolutePath
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to write handoff script", throwable)
        }.getOrNull()
    }

    fun launchOdinSettings() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(ODIN_SETTINGS_PACKAGE, ODIN_SETTINGS_MAIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (throwable: Throwable) {
            Log.w(TAG, "Failed to launch Odin Settings, falling back to system Settings", throwable)
            try {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (inner: Throwable) {
                Log.w(TAG, "Failed to launch system Settings too", inner)
            }
        }
    }

    private fun scriptDirectory(): File {
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        return File(downloads, SCRIPT_DIR_NAME)
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        private const val TAG = "ClusterTuneHandoff"
        const val ODIN_SETTINGS_PACKAGE = "com.odin2.odinsettings"
        const val ODIN_SETTINGS_MAIN_ACTIVITY =
            "com.odin2.odinsettings.activity.MainSettingsActivity"
        const val SCRIPT_DIR_NAME = "ClusterScripts"
        const val SCRIPT_FILENAME = "clustertune-apply.sh"

        /**
         * The path users will see in Odin Settings's file picker.
         * Exposed as a constant so the UI can show it without
         * instantiating the handoff.
         */
        const val USER_VISIBLE_PATH = "Download/$SCRIPT_DIR_NAME/$SCRIPT_FILENAME"
    }
}
