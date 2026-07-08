package com.aure.clustertune.ui

import com.aure.clustertune.model.CpuPolicyInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class FrequencyFormattingTest {

    private val policy = CpuPolicyInfo(
        id = 0,
        policyPath = "/sys/devices/system/cpu/cpufreq/policy0",
        scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        currentMaxFreq = 1_500_000,
        selectableMaxFreq = 2_000_000,
        observedMaxFreq = 2_200_000,
        minFreq = 500_000,
        supportedFrequencies = listOf(500_000, 1_000_000, 1_500_000, 2_000_000, 2_200_000),
    )

    @Test
    fun formatsFrequencyAsGhzByDefault() {
        assertEquals("1.50 GHz", formatFrequency(1_500_000, policy = policy))
    }

    @Test
    fun formatsFrequencyAsPercentOfSelectableMaxWhenEnabled() {
        assertEquals(
            "75%",
            formatFrequency(
                valueKhz = 1_500_000,
                policy = policy,
                displayAsPercent = true,
            ),
        )
    }

    @Test
    fun keepsBoostMarkerInPercentMode() {
        assertEquals(
            "110%+",
            formatFrequency(
                valueKhz = 2_200_000,
                boosted = true,
                policy = policy,
                displayAsPercent = true,
            ),
        )
    }
}
