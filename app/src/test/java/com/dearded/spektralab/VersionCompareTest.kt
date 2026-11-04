package com.dearded.spektralab

import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {
    @Test fun patchBumps() {
        assertTrue(compareVersions("0.1.19-alpha", "0.1.18-alpha") > 0)
        assertTrue(compareVersions("0.1.10-alpha", "0.1.9-alpha") > 0)
        assertTrue(compareVersions("v0.2.0-alpha", "0.1.99-alpha") > 0)
    }

    @Test fun suffixOrder() {
        assertTrue(compareVersions("0.2.0-beta", "0.2.0-alpha") > 0)
        assertTrue(compareVersions("0.2.0-rc1", "0.2.0-beta") > 0)
        assertTrue(compareVersions("0.2.0", "0.2.0-rc1") > 0)
    }

    @Test fun equal() {
        assertTrue(compareVersions("v0.1.18-alpha", "0.1.18-alpha") == 0)
    }
}
