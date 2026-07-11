package com.aure.clustertune.model

data class ProfileSwitchHistoryEntry(
    val timestampMillis: Long,
    val profileId: String?,
    val profileName: String,
    val trigger: String,
)
