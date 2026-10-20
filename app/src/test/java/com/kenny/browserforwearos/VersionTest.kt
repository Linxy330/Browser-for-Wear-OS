package com.kenny.browserforwearos

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same product identity is embedded in both ABI packages. */
class VersionTest {
    @Test
    fun versionNameIsPinnedToRelease() {
        assertEquals("1.0.0", BuildConfig.VERSION_NAME)
    }

    @Test
    fun versionCodeIsPinnedToRelease() {
        assertEquals(100, BuildConfig.VERSION_CODE)
    }
}
