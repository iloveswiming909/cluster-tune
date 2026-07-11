package com.aure.clustertune.root

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import java.nio.charset.Charset

interface PServerRootExecutor {
    val pServerAvailable: Boolean
    fun executeAsRoot(cmd: String): Result<String?>

    /**
     * Execute [cmd] as root through PServer, choosing whether PServer
     * should capture and return the command's stdout.
     *
     * On some firmware (notably the Odin 2 Mini) PServer's stdout-capture
     * path is broken: requesting stdout ([captureStdout] = true) yields an
     * empty reply AND the command may not run at all. Fire-and-forget
     * execution ([captureStdout] = false) takes a different, working code
     * path — this is exactly what Odin's own Settings app uses for every
     * write it performs (chmod, echo > sysfs, etc). Reads that need output
     * back should be done via direct File I/O rather than stdout capture on
     * such devices.
     *
     * Default is stdout capture, matching historical behaviour.
     */
    fun executeAsRoot(cmd: String, captureStdout: Boolean): Result<String?> = executeAsRoot(cmd)
}

@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class RootExec : PServerRootExecutor {

    private val binder: IBinder?
    override var pServerAvailable: Boolean = false
        private set

    init {
        binder = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            val rawBinder = getService.invoke(serviceManager, "PServerBinder") as IBinder
            pServerAvailable = true
            rawBinder
        }.getOrDefault(null)
    }

    override fun executeAsRoot(cmd: String): Result<String?> = executeAsRoot(cmd, captureStdout = true)

    override fun executeAsRoot(cmd: String, captureStdout: Boolean): Result<String?> {
        if (binder == null) return Result.failure(IllegalStateException("PServer not available"))

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            // Matches com.odin2.common.PServiceBridgeV2.call(SU_CMD=0, [cmd, flag]).
            // flag "1" = capture stdout, flag "0" = fire-and-forget.
            val flag = if (captureStdout) "1" else "0"
            data.writeStringArray(arrayOf(cmd, flag))
            binder.transact(0, data, reply, 0)
            Result.success(decodeReply(reply))
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun decodeReply(reply: Parcel): String? {
        return reply.createByteArray()
            ?.toString(Charset.defaultCharset())
            ?.trim()
            ?.let { value -> if (value == "null") null else value }
    }
}
