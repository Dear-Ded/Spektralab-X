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

    @Test fun engineMigrationAlpha() {
        assertTrue(compareVersions("0.2.00-alpha-1", "0.1.19-beta") > 0)
        assertTrue(compareVersions("0.2.00-alpha-1", "0.1.19-beta2") > 0)
        assertTrue(compareVersions("0.2.00-alpha-1", "0.2.0-alpha1") == 0)
        assertTrue(compareVersions("0.2.00-alpha-2", "0.2.00-alpha-1") > 0)
        assertTrue(compareVersions("0.2.00-alpha-1", "0.2.00-beta") < 0)
    }

    @Test fun numberedPrereleases() {
        assertTrue(compareVersions("0.1.19-beta2", "0.1.19-beta") > 0)
        assertTrue(compareVersions("0.1.19-beta10", "0.1.19-beta2") > 0)
        assertTrue(compareVersions("0.1.19-alpha2", "0.1.19-alpha1") > 0)
        assertTrue(compareVersions("0.1.19-rc2", "0.1.19-rc1") > 0)
        assertTrue(compareVersions("0.1.19-beta2", "0.1.19-rc1") < 0)
        assertTrue(compareVersions("0.1.19", "0.1.19-beta10") > 0)
    }

    @Test fun dottedPrereleasesAndBuildMetadata() {
        assertTrue(compareVersions("v0.1.19-beta.2", "0.1.19-beta2") == 0)
        assertTrue(compareVersions("0.1.19-beta.2.10", "0.1.19-beta.2.9") > 0)
        assertTrue(compareVersions("0.1.19-beta2+build7", "0.1.19-beta2") == 0)
        assertTrue(compareVersions("0.1.19+build7", "0.1.19") == 0)
        assertTrue(compareVersions("0.1.19-beta1", "0.1.19-beta2") < 0)
    }
}
