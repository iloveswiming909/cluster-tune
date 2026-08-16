package com.aure.clustertune.daemon

import android.os.Environment
import java.io.File

/**
 * Shared contract between ClusterTune (unprivileged app) and the resident
 * system-uid daemon it bootstraps once per boot.
 *
 * WHY THIS EXISTS
 * ---------------
 * Wireless debugging is torn down by Android whenever Wi-Fi drops — measured
 * on an Odin 2 Mini: `persist.adb.tls_server.enable` flips 1 -> 0 and adbd
 * stops listening entirely (`ss -lnt` empty). So the JDWP path cannot be used
 * for per-app profile switching while offline, no matter which address is
 * targeted. Loopback does not help; there is nothing listening to connect to.
 *
 * The workaround: use the JDWP path ONCE, while Wi-Fi is up, to launch a
 * detached `sh` process owned by uid=system. That process outlives the adb
 * connection, needs no network, and can write the sysfs nodes directly. All
 * later applies talk to it through files.
 *
 * FOOTPRINT ON THE VENDOR APP
 * ---------------------------
 * None beyond the single Runtime.exec already used today. GameAssistant is a
 * launcher only — no dex staged into it, no thread injected, no service bound,
 * no data touched. The daemon is a child process, not code running inside GA.
 * Nothing survives a reboot.
 *
 * TRANSPORT
 * ---------
 * Files under Documents/ClusterScripts/daemon — the same public-storage
 * handoff dir ClusterTune already uses successfully, writable by the app with
 * no runtime permission and readable/writable by uid=system.
 *
 * Polling, not inotify: /sdcard is a FUSE mount and cross-process inotify
 * events on FUSE are unreliable on Android. Polling is boring and works.
 *
 * ATOMICITY
 * ---------
 * Both sides write to a tmp name then rename into place, so neither side can
 * ever observe a half-written file.
 *
 *   app    writes  tmp-req-<id>.sh  -> renames to  req-<id>.sh
 *   daemon writes  tmp-<id>.out     -> renames to  res-<id>.out
 *   daemon then writes res-<id>.rc  (written last; its presence means done)
 *
 * The app waits on res-<id>.rc, never on res-<id>.out.
 */
object SystemDaemonProtocol {

    /** Directory the daemon polls. Subdir of the existing handoff location. */
    @Suppress("DEPRECATION")
    fun daemonDir(): File = File(
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "ClusterScripts",
        ),
        "daemon",
    )

    const val HEARTBEAT_FILE = "heartbeat"
    const val PID_FILE = "daemon.pid"
    const val STOP_FILE = "stop"
    const val VERSION_FILE = "daemon.version"

    /**
     * Bumped whenever DAEMON_SCRIPT changes. A running daemon from an older app
     * version is asked to stop so the new one can take over, rather than
     * silently serving requests with stale logic.
     */
    const val DAEMON_VERSION = 1

    /** Daemon rewrites the heartbeat every loop; this is the loop period. */
    const val POLL_INTERVAL_MS = 750L

    /**
     * A heartbeat older than this means the daemon is gone. Generous relative
     * to POLL_INTERVAL_MS because the device can be deep-asleep between loops
     * and we must not declare a live daemon dead on a scheduling hiccup.
     */
    const val HEARTBEAT_STALE_MS = 15_000L

    const val BOOTSTRAP_SCRIPT_NAME = "ct_bootstrap.sh"
    const val DAEMON_SCRIPT_NAME = "ct_daemon.sh"

    /**
     * Launcher. This is what GameAssistant is asked to exec.
     *
     * It exists as a separate script for one specific reason: Runtime.exec(String)
     * tokenises its argument on whitespace with StringTokenizer and does NOT
     * honour quoting. So `sh -c "setsid sh /path/x.sh &"` would be shredded into
     * meaningless tokens. Keeping the injected command to the two bare tokens
     * `sh <path>` sidesteps that entirely, and all the redirection/detaching
     * lives here in file form where quoting is safe.
     *
     * setsid detaches the daemon into its own session so it is not torn down
     * with the transient shell that launched it. stdin/stdout/stderr are all
     * redirected away from the parent's pipes, otherwise the daemon would keep
     * GameAssistant's Process object's streams open forever.
     *
     * NOTE: scripts are executed via `sh <path>`, never `./<path>` — /sdcard is
     * mounted noexec, so the interpreter must read the file rather than the
     * kernel exec it. This mirrors what the existing JDWP path already does.
     */
    fun bootstrapScript(daemonScriptPath: String): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("setsid sh $daemonScriptPath </dev/null >/dev/null 2>&1 &")
    }

    /**
     * The resident daemon. Runs as uid=system (inherited from GameAssistant).
     *
     * Deliberately small and dependency-free: only toybox builtins, no awk, no
     * python, nothing that might be absent on a vendor ROM.
     *
     * Single-instance: if a live daemon already holds the pid file, this one
     * exits immediately rather than racing it for request files.
     */
    fun daemonScript(dir: String, version: Int = DAEMON_VERSION): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("DIR=$dir")
        appendLine("mkdir -p \"\$DIR\" 2>/dev/null")
        appendLine("")
        appendLine("# --- single instance guard -------------------------------------")
        appendLine("if [ -f \"\$DIR/${PID_FILE}\" ]; then")
        appendLine("  OLD=\$(cat \"\$DIR/${PID_FILE}\" 2>/dev/null)")
        appendLine("  if [ -n \"\$OLD\" ] && [ -d \"/proc/\$OLD\" ] && [ \"\$OLD\" != \"\$\$\" ]; then")
        appendLine("    exit 0")
        appendLine("  fi")
        appendLine("fi")
        appendLine("echo \$\$ > \"\$DIR/${PID_FILE}\"")
        appendLine("echo $version > \"\$DIR/${VERSION_FILE}\"")
        appendLine("rm -f \"\$DIR/${STOP_FILE}\" 2>/dev/null")
        appendLine("")
        appendLine("# --- main loop -------------------------------------------------")
        appendLine("while true; do")
        appendLine("  if [ -f \"\$DIR/${STOP_FILE}\" ]; then")
        appendLine("    rm -f \"\$DIR/${STOP_FILE}\" \"\$DIR/${PID_FILE}\" \"\$DIR/${HEARTBEAT_FILE}\" 2>/dev/null")
        appendLine("    exit 0")
        appendLine("  fi")
        appendLine("  date +%s > \"\$DIR/${HEARTBEAT_FILE}\" 2>/dev/null")
        appendLine("  for REQ in \"\$DIR\"/req-*.sh; do")
        appendLine("    [ -e \"\$REQ\" ] || continue")
        appendLine("    BASE=\$(basename \"\$REQ\")")
        appendLine("    ID=\${BASE#req-}")
        appendLine("    ID=\${ID%.sh}")
        appendLine("    sh \"\$REQ\" > \"\$DIR/tmp-\$ID.out\" 2>&1")
        appendLine("    RC=\$?")
        appendLine("    rm -f \"\$REQ\" 2>/dev/null")
        appendLine("    mv \"\$DIR/tmp-\$ID.out\" \"\$DIR/res-\$ID.out\" 2>/dev/null")
        appendLine("    echo \$RC > \"\$DIR/res-\$ID.rc\"")
        appendLine("  done")
        appendLine("  sleep 0.75")
        appendLine("done")
    }
}
