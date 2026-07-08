package com.aure.clustertune.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.getSystemService

enum class OverlayType {
    COMPACT_TUNER_MODAL,
    COMPACT_PROFILE_PICKER,
}

class OverlayWindowController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService<WindowManager>()
        ?: error("WindowManager unavailable")
    private var activeView: View? = null
    private var activeType: OverlayType? = null

    val hasActiveOverlay: Boolean
        get() = activeView != null

    fun show(type: OverlayType, view: View) {
        dismiss()
        activeType = type
        activeView = view
        windowManager.addView(view, modalLayoutParams())
    }

    fun dismiss(type: OverlayType? = null) {
        if (type != null && activeType != type) return
        val view = activeView ?: return
        runCatching { windowManager.removeView(view) }
        activeView = null
        activeType = null
    }

    private fun modalLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            title = "ClusterTune overlay"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
