package com.kenny.browserforwearos

import org.junit.Assert.assertEquals
import org.junit.Test

/** F14 版本钉住：雏形阶段版本号固定，防止无意漂移。 */
class VersionTest {
    @Test
    fun versionNameIsPinnedToPrototype() {
        assertEquals("0.1.0-dev", BuildConfig.VERSION_NAME)
    }

    @Test
    fun versionCodeIsPinnedToFirstPrototype() {
        assertEquals(1, BuildConfig.VERSION_CODE)
    }
}
