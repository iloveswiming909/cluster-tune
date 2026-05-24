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
    }

    fun executeAsRoot(cmd: String): Result<String?> {
        if (binder == null) {
            Log.d(WRITE_TAG, "executeAsRoot SKIPPED: binder == null")
            return Result.failure(IllegalStateException("PServer not available"))
        }
        val preview = if (cmd.length > 200) cmd.take(200) + "...(${cmd.length})" else cmd
        Log.d(WRITE_TAG, "executeAsRoot ENTER: cmd=[$preview]")

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(cmd, "1"))
            val ok = try {
                binder.transact(0, data, reply, 0)
            } catch (t: Throwable) {
                Log.d(WRITE_TAG, "executeAsRoot transact THREW ${t.javaClass.simpleName}: ${t.message}")
                return Result.failure(t)
            }
            val replySize = reply.dataSize()
            val decoded = decodeReply(reply)
            Log.d(
                WRITE_TAG,
                "executeAsRoot RETURN: transact=$ok, reply.dataSize=$replySize, decoded=${decoded?.let { "'${it.take(120)}'(${it.length})" } ?: "null"}",
            )
            Result.success(decoded)
        } catch (throwable: Throwable) {
            Log.d(WRITE_TAG, "executeAsRoot OUTER THREW ${throwable.javaClass.simpleName}: ${throwable.message}")
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

    private companion object {
        const val WRITE_TAG = "ClusterTuneWrite"
    }
}
