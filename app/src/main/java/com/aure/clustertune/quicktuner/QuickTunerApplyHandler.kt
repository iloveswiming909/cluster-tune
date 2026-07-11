package com.aure.clustertune.quicktuner

import android.widget.Toast
import com.aure.clustertune.data.PerformanceRepository
import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.TunerState

interface QuickTunerApplyRepository {
    suspend fun applyValues(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        isReset: Boolean,
        appliedDisplayProfileId: String?,
    ): Result<PerformanceRepository.ApplyOutcome>

    suspend fun selectProfile(profileId: String?)

    suspend fun logProfileSwitch(profileId: String?, profileName: String, trigger: String)
}

class PerformanceQuickTunerApplyRepository(
    private val repository: PerformanceRepository,
) : QuickTunerApplyRepository {
    override suspend fun applyValues(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        isReset: Boolean,
        appliedDisplayProfileId: String?,
    ): Result<PerformanceRepository.ApplyOutcome> {
        return repository.applyValues(
            policies = policies,
            selectedValues = selectedValues,
            isReset = isReset,
            appliedDisplayProfileId = appliedDisplayProfileId,
        )
    }

    override suspend fun selectProfile(profileId: String?) {
        repository.selectProfile(profileId)
    }

    override suspend fun logProfileSwitch(profileId: String?, profileName: String, trigger: String) {
        repository.logProfileSwitch(profileId, profileName, trigger)
    }
}

class QuickTunerApplyHandler(
    private val repository: QuickTunerApplyRepository,
    private val showToast: (String, Int) -> Unit,
    private val refreshTile: () -> Unit,
    private val trigger: String = "Quick Settings dialog",
) {
    suspend fun applyCurrent(state: TunerState): Result<Unit> {
        val appliedProfile = ProfileStateResolver.preferredProfileForCurrentValues(state)
        val profileId = appliedProfile?.id ?: ProfileStateResolver.MANUAL_PROFILE_ID
        val profileName = appliedProfile?.name ?: "Manual"
        val displayName = appliedProfile?.name ?: "Custom values"

        val result = repository.applyValues(
            policies = state.policies,
            selectedValues = state.currentValues,
            isReset = appliedProfile?.id == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = profileId,
        )

        return result.fold(
            onSuccess = {
                repository.selectProfile(
                    appliedProfile?.id?.takeUnless { id -> id == ProfileStateResolver.STOCK_PROFILE_ID },
                )
                repository.logProfileSwitch(
                    profileId = profileId,
                    profileName = profileName,
                    trigger = trigger,
                )
                showToast(displayName, Toast.LENGTH_SHORT)
                refreshTile()
                Result.success(Unit)
            },
            onFailure = { throwable ->
                showToast(throwable.message ?: "Failed to apply limits", Toast.LENGTH_LONG)
                Result.failure(throwable)
            },
        )
    }

    suspend fun applyProfile(state: TunerState, profile: PerformanceProfile): Result<Unit> {
        val result = repository.applyValues(
            policies = state.policies,
            selectedValues = profile.maxFrequencies,
            isReset = profile.id == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = profile.id,
        )

        return result.fold(
            onSuccess = {
                repository.selectProfile(profile.id.takeUnless { id -> id == ProfileStateResolver.STOCK_PROFILE_ID })
                repository.logProfileSwitch(
                    profileId = profile.id,
                    profileName = profile.name,
                    trigger = "Quick Settings picker",
                )
                showToast(profile.name, Toast.LENGTH_SHORT)
                refreshTile()
                Result.success(Unit)
            },
            onFailure = { throwable ->
                showToast(throwable.message ?: "Failed to apply profile", Toast.LENGTH_LONG)
                Result.failure(throwable)
            },
        )
    }
}
