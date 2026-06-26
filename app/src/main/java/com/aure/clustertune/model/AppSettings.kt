package com.aure.clustertune.model

enum class AppColorSource {
    SYSTEM,
    CUSTOM_ACCENT,
}

enum class TileInteractionBehavior {
    SHOW_DIALOG,
    CYCLE_PROFILES,
    OPEN_APP,
}

data class AppSettings(
    val colorSource: AppColorSource = AppColorSource.SYSTEM,
    val accentColor: Int = 0xFF3F51B5.toInt(),
    val customAccentColor: Int = 0xFF3F51B5.toInt(),
    val tileTapBehavior: TileInteractionBehavior = TileInteractionBehavior.SHOW_DIALOG,
    val applyLastProfileOnBoot: Boolean = false,
    val sleepProfileEnabled: Boolean = false,
    val sleepProfileId: String? = null,
    val hasPromptedQuickSettingsTile: Boolean = false,
    val isQuickSettingsTileAdded: Boolean = false,
    val automaticUpdateChecksEnabled: Boolean = true,
    val updateCheckIntervalDays: Int = 7,
    val lastUpdateCheckMillis: Long = 0L,
    val privilegedExecutionMethodId: String? = null,
)
