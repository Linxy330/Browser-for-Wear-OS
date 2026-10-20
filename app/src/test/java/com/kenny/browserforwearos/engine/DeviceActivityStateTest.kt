package com.kenny.browserforwearos.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActivityStateTest {
    private val interactive = DeviceActivityState(resumed = true, interactive = true, displayOn = true)

    @Test fun resumedInteractiveDisplayAllowsPage() {
        assertTrue(interactive.active)
    }

    @Test fun ambientStopsPageEvenWhenResumedAndDisplayOn() {
        assertFalse(interactive.copy(ambient = true).active)
    }

    @Test fun screenOffAndNonInteractiveAreIndependentStopConditions() {
        assertFalse(interactive.copy(displayOn = false).active)
        assertFalse(interactive.copy(interactive = false).active)
    }

    @Test fun ambientExitCannotResumeBackgroundActivity() {
        val paused = interactive.copy(resumed = false, ambient = true)
        assertFalse(paused.copy(ambient = false).active)
    }

    @Test fun wakeCannotResumePageUntilActivityAndAmbientAllowIt() {
        val sleeping = interactive.copy(ambient = true, interactive = false, displayOn = false)
        val displayAwake = sleeping.copy(interactive = true, displayOn = true)
        assertFalse(displayAwake.active)
        assertTrue(displayAwake.copy(ambient = false).active)
    }
}
