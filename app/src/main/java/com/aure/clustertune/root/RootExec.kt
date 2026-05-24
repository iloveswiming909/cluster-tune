package com.aure.clustertune.root

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.nio.charset.Charset

@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class RootExec {

    private val binder: IBinder?
    var pServerAvailable: Boolean = false
        private set

    init {
        binder = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            val rawBinder = getService.invoke(serviceManager, "PServerBinder") as IBinder
            pServerAvailable = true
            rawBinder
        }.getOrDefault(null)

        // One-shot diagnostic: probe every plausible PServer protocol
        // shape against a canary echo command and log the outcomes.
        // Runs only when the binder was obtained successfully.
        binder?.let { b ->
            try {
                PServerProbe.run(b)
            } catch (t: Throwable) {
                Log.w("ClusterTuneProbe", "Probe threw at top level", t)
            }
        }
    }

    fun executeAsRoot(cmd: String): Result<String?> {
        if (binder == null) return Result.failure(IllegalStateException("PServer not available"))

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(cmd, "1"))
            val ok = binder.transact(0, data, reply, 0)
            // Log the transact return + reply size for every real call too —
            // this was the missing piece in the previous round of
            // diagnostics. Tag matches the rest of the detection logging.
            Log.d(
                "ClusterTune",
                "executeAsRoot('${cmd.take(60)}${if (cmd.length > 60) "..." else ""}'): transact=$ok, reply.dataSize=${reply.dataSize()}",
            )
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
