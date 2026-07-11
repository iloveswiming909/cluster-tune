package com.aure.clustertune.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object SingleToast {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentToast: Toast? = null

    fun show(
        context: Context,
        message: String,
        duration: Int = Toast.LENGTH_SHORT,
    ) {
        val appContext = context.applicationContext
        mainHandler.post {
            currentToast?.cancel()
            currentToast = Toast.makeText(appContext, message, duration).also { toast ->
                toast.show()
            }
        }
    }

    fun cancel() {
        mainHandler.post {
            currentToast?.cancel()
            currentToast = null
        }
    }
}
