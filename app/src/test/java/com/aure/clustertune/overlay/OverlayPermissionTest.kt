package com.aure.clustertune.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPermissionTest {
    @Test
    fun packageUriStringTargetsCurrentApp() {
        assertEquals(
            "package:com.aure.clustertune",
            OverlayPermission.packageUriString("com.aure.clustertune"),
        )
    }
}
