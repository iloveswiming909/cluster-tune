package com.aure.clustertune.model

data class AppProfileAssignment(
    val packageName: String,
    val appLabel: String,
    val profileId: String,
)

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
)
