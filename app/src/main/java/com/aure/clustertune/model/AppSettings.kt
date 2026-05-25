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
    val tileTapBehavior: TileInteractionBehavior = TileInteractionBehavior.SHOW_DIALOG,
    val applyLastProfileOnBoot: Boolean = false,
    val hasPromptedQuickSettingsTile: Boolean = false,
    val isQuickSettingsTileAdded: Boolean = false,
    /**
     * Set the first time the Mini handoff dialog is shown, after which
     * the longer tutorial is replaced with the shorter dialog body on
     * subsequent uses.
     */
    val hasSeenOdinHandoffTutorial: Boolean = false,
)
