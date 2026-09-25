package com.landosol.toolbox

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVersionTest {
    @Test fun `the shipped version is the one this branch declares`() {
        // Diagnostic bundles are read against this constant, so a build.gradle bump that forgets
        // the rest of the release is worth a failing test rather than a silent mismatch.
        assertEquals("1.0.14", AppVersion.name)
        assertEquals(10014, AppVersion.code)
    }

    @Test fun `every surface prints one label`() {
        // A version this build will never have, so it cannot quietly pass by matching the real one.
        assertEquals("9.9.9 (99909)", appVersionLabel("9.9.9", 99909))
        assertEquals("版本 ${AppVersion.label}", AppVersion.display)
    }
}
