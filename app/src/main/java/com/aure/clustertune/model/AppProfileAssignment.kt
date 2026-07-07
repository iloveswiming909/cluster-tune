package com.aure.clustertune.model

import android.graphics.drawable.Drawable

data class AppProfileAssignment(
    val packageName: String,
    val appLabel: String,
    val profileId: String,
)

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
)
