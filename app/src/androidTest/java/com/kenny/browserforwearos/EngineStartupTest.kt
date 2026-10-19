package com.kenny.browserforwearos

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kenny.browserforwearos.engine.GeckoRuntimeHolder
import com.kenny.browserforwearos.presentation.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoRuntime

/**
 * B1 真机冒烟：进程内 Runtime 单一性 + 主界面可启动。
 * 完整输入/渲染用例由 docs/B1_DEVICE_TEST_CHECKLIST.md 人工执行记录。
 */
@RunWith(AndroidJUnit4::class)
class EngineStartupTest {

    @Test
    fun runtimeIsProcessSingleton() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first: GeckoRuntime = GeckoRuntimeHolder.runtime(context)
        val second: GeckoRuntime = GeckoRuntimeHolder.runtime(context)
        assertSame(first, second)
    }

    @Test
    fun mainActivityLaunches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Activity 正常创建即通过；引擎状态由人工清单覆盖
                assertEquals(
                    "com.kenny.browserforwearos",
                    activity.packageName,
                )
            }
        }
    }
}
