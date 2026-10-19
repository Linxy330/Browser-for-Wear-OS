package com.kenny.browserforwearos

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 门禁：前后台 / 熄屏唤醒恢复（F12 基线）。各 5 次，无崩溃且页面状态保持。
 */
class LifecycleRecoveryTest : B1TestBase() {

    @Test
    fun fiveBackgroundForegroundCycles() {
        navigateTo("$BASE/counter")
        assertTrue(waitTitle("计数器"))
        repeat(5) { i ->
            device.pressHome()
            device.waitForIdle(2_000)
            Thread.sleep(500)
            launchApp()
            assertTrue(
                "cycle $i: page state preserved after background",
                waitTitle("计数器", 15_000),
            )
        }
        shot("b1-lifecycle-bg.png")
    }

    @Test
    fun fiveScreenOffOnCycles() {
        navigateTo("$BASE/long")
        assertTrue(waitTitle("长页面"))
        repeat(5) { i ->
            device.sleep()
            Thread.sleep(1_500)
            device.wakeUp()
            device.executeShellCommand("input keyevent 82") // 解锁（无锁码时）
            launchApp()
            assertTrue(
                "cycle $i: page state preserved after screen off",
                waitTitle("长页面", 15_000),
            )
        }
        shot("b1-lifecycle-screen.png")
    }
}
