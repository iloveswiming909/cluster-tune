package com.aure.clustertune.data

import com.aure.clustertune.model.ProfileSwitchHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSwitchHistoryLimitTest {

    @Test
    fun `adding history entry keeps newest entries up to configured limit`() {
        val current = listOf(
            historyEntry(timestamp = 300, profileName = "third"),
            historyEntry(timestamp = 200, profileName = "second"),
            historyEntry(timestamp = 100, profileName = "oldest"),
        )

        val updated = boundedProfileSwitchHistory(
            entry = historyEntry(timestamp = 400, profileName = "newest"),
            current = current,
            limit = 3,
        )

        assertEquals(listOf("newest", "third", "second"), updated.map { it.profileName })
    }

    private fun historyEntry(timestamp: Long, profileName: String): ProfileSwitchHistoryEntry {
        return ProfileSwitchHistoryEntry(
            timestampMillis = timestamp,
            profileId = profileName,
            profileName = profileName,
            trigger = "test",
        )
    }
}
