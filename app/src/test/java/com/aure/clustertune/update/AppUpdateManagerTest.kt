package com.aure.clustertune.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun `parses latest GitHub release apk asset`() {
        val release = GitHubReleaseParser.parseLatestRelease(
            """
            {
              "tag_name": "v0.4.0",
              "name": "v0.4.0",
              "body": "Release notes",
              "assets": [
                {
                  "name": "ClusterTune-v0.4.0.apk.sha256",
                  "browser_download_url": "https://example.invalid/hash",
                  "size": 100
                },
                {
                  "name": "ClusterTune-v0.4.0.apk",
                  "browser_download_url": "https://example.invalid/app.apk",
                  "size": 12345
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("0.4.0", release.versionName)
        assertEquals("v0.4.0", release.tagName)
        assertEquals("ClusterTune-v0.4.0.apk", release.asset.name)
        assertEquals("https://example.invalid/app.apk", release.asset.downloadUrl)
        assertEquals(12345, release.asset.sizeBytes)
    }

    @Test
    fun `semantic version compares newer releases`() {
        assertTrue(SemanticVersion.parse("0.4.0")!! > SemanticVersion.parse("0.3.1")!!)
        assertTrue(SemanticVersion.parse("v1.0.0")!! > SemanticVersion.parse("0.9.9")!!)
        assertTrue(SemanticVersion.parse("1.0.0")!! > SemanticVersion.parse("1.0.0-beta.1")!!)
    }

    @Test
    fun `parser rejects releases without apk asset`() {
        val result = runCatching {
            GitHubReleaseParser.parseLatestRelease(
                """
                {
                  "tag_name": "v0.4.0",
                  "assets": [
                    {
                      "name": "ClusterTune-v0.4.0.apk.sha256",
                      "browser_download_url": "https://example.invalid/hash"
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        assertTrue(result.isFailure)
    }
}
