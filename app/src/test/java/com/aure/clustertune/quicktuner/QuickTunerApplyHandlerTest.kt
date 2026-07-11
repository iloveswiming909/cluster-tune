package com.aure.clustertune.quicktuner

import com.aure.clustertune.data.PerformanceRepository
import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.TunerState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickTunerApplyHandlerTest {

    private val policy = CpuPolicyInfo(
        id = 0,
        policyPath = "/sys/devices/system/cpu/cpufreq/policy0",
        scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        currentMaxFreq = 1_000_000,
        selectableMaxFreq = 2_000_000,
        observedMaxFreq = 2_000_000,
        minFreq = 500_000,
        supportedFrequencies = listOf(500_000, 1_000_000, 2_000_000),
    )

    @Test
    fun appliesAndLogsResolvedProfile() = runTest {
        val profile = PerformanceProfile(
            id = "balanced",
            name = "Balanced",
            maxFrequencies = mapOf(0 to 1_000_000),
            source = ProfileSource.USER,
        )
        val repository = FakeQuickTunerRepository()
        val toasts = mutableListOf<String>()
        var tileRefreshCount = 0
        val handler = QuickTunerApplyHandler(
            repository = repository,
            showToast = { message, _ -> toasts += message },
            refreshTile = { tileRefreshCount++ },
        )

        val result = handler.applyCurrent(
            TunerState(
                policies = listOf(policy),
                currentValues = profile.maxFrequencies,
                displayProfiles = listOf(profile),
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals("balanced", repository.appliedDisplayProfileId)
        assertEquals("balanced", repository.selectedProfileId)
        assertEquals("balanced", repository.loggedProfileId)
        assertEquals("Balanced", repository.loggedProfileName)
        assertEquals("Quick Settings dialog", repository.loggedTrigger)
        assertEquals(listOf("Balanced"), toasts)
        assertEquals(1, tileRefreshCount)
    }

    @Test
    fun logsManualProfileWhenValuesDoNotMatchDisplayProfile() = runTest {
        val repository = FakeQuickTunerRepository()
        val handler = QuickTunerApplyHandler(
            repository = repository,
            showToast = { _, _ -> },
            refreshTile = {},
        )

        handler.applyCurrent(
            TunerState(
                policies = listOf(policy),
                currentValues = mapOf(0 to 500_000),
                displayProfiles = emptyList(),
            ),
        )

        assertEquals(ProfileStateResolver.MANUAL_PROFILE_ID, repository.appliedDisplayProfileId)
        assertEquals(null, repository.selectedProfileId)
        assertEquals(ProfileStateResolver.MANUAL_PROFILE_ID, repository.loggedProfileId)
        assertEquals("Manual", repository.loggedProfileName)
    }

    @Test
    fun appliesPickedProfileAndLogsPickerTrigger() = runTest {
        val profile = PerformanceProfile(
            id = "gaming",
            name = "Gaming",
            maxFrequencies = mapOf(0 to 2_000_000),
            source = ProfileSource.USER,
        )
        val repository = FakeQuickTunerRepository()
        val toasts = mutableListOf<String>()
        var tileRefreshCount = 0
        val handler = QuickTunerApplyHandler(
            repository = repository,
            showToast = { message, _ -> toasts += message },
            refreshTile = { tileRefreshCount++ },
        )

        val result = handler.applyProfile(
            state = TunerState(policies = listOf(policy)),
            profile = profile,
        )

        assertTrue(result.isSuccess)
        assertEquals("gaming", repository.appliedDisplayProfileId)
        assertEquals("gaming", repository.selectedProfileId)
        assertEquals("gaming", repository.loggedProfileId)
        assertEquals("Gaming", repository.loggedProfileName)
        assertEquals("Quick Settings picker", repository.loggedTrigger)
        assertEquals(listOf("Gaming"), toasts)
        assertEquals(1, tileRefreshCount)
    }

    private class FakeQuickTunerRepository : QuickTunerApplyRepository {
        var appliedDisplayProfileId: String? = null
        var selectedProfileId: String? = null
        var loggedProfileId: String? = null
        var loggedProfileName: String? = null
        var loggedTrigger: String? = null

        override suspend fun applyValues(
            policies: List<CpuPolicyInfo>,
            selectedValues: Map<Int, Int>,
            isReset: Boolean,
            appliedDisplayProfileId: String?,
        ): Result<PerformanceRepository.ApplyOutcome> {
            this.appliedDisplayProfileId = appliedDisplayProfileId
            return Result.success(
                PerformanceRepository.ApplyOutcome(
                    actualValues = selectedValues,
                    verificationPassed = true,
                    commandOutput = null,
                ),
            )
        }

        override suspend fun selectProfile(profileId: String?) {
            selectedProfileId = profileId
        }

        override suspend fun logProfileSwitch(profileId: String?, profileName: String, trigger: String) {
            loggedProfileId = profileId
            loggedProfileName = profileName
            loggedTrigger = trigger
        }
    }
}
