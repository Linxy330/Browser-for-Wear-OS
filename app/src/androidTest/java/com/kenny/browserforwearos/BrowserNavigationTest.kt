package com.kenny.browserforwearos

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.kenny.browserforwearos.presentation.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * B1 引擎门禁：驱动 首页 → 地址输入 → 真实 GeckoView 加载测试站 的完整路径。
 * 文本注入用 ACTION_SET_TEXT（手表键盘吞硬件键事件）；加载结果经状态条标题断言。
 * 测试站地址用环境变量 WBS_TEST_URL 覆盖，默认本机 LAN IP。
 */
@RunWith(AndroidJUnit4::class)
class BrowserNavigationTest {

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun testUrl(): String =
        // 优先 instrumentation 参数（-e WBS_TEST_URL …），否则默认本机 LAN IP
        InstrumentationRegistry.getArguments().getString("WBS_TEST_URL")
            ?: System.getenv("WBS_TEST_URL")
            ?: "http://10.18.23.175:8787/counter"

    private fun shot(name: String) {
        // 应用 uid 无法写 /data/local/tmp；存自己的 external files 再由 adb pull
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null) ?: return
        val ok = device.takeScreenshot(java.io.File(dir, name))
        check(ok) { "screenshot $name failed" }
    }

    @Test
    fun loadCounterPageThroughAddressFlow() {
        device.wakeUp()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )

        // 首页 → 打开网页（英/中双语文案兼容）
        val openWeb = device.wait(
            Until.findObject(By.text(Pattern.compile("Open web|打开网页"))),
            10_000,
        )
        assertTrue("home screen with Open web button", openWeb != null)
        openWeb!!.click()

        // 地址输入：ACTION_SET_TEXT 绕过 IME
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
        assertTrue("address field visible", field != null)
        field!!.text = testUrl()

        // 提交（Go/前往）
        val go = device.wait(Until.findObject(By.text(Pattern.compile("Go|前往"))), 5_000)
        assertTrue("go button visible", go != null)
        go!!.click()

        // 等待真实引擎渲染：状态条出现页面标题（测试站计数器页）
        val loaded = device.wait(
            Until.hasObject(By.text(Pattern.compile(".*计数.*|.*Counter.*"))),
            20_000,
        )
        assertTrue("engine loaded counter page (status bar title)", loaded)

        // 留证：加载完成态截图（存设备侧再拉取，避免熄屏后误拍）
        shot("b1-counter-loaded.png")

        // JS 执行验证：先滚动页面（按钮在首屏折叠线下方），再点击；
        // 计数变化反映到 document.title → 状态条文本
        device.swipe(240, 400, 240, 120, 12)
        var clicked = false
        for (y in intArrayOf(240, 260, 220, 280)) {
            device.click(240, y)
            clicked = device.wait(
                Until.hasObject(By.textContains("计数器 1")),
                3_000,
            )
            if (clicked) break
        }
        assertTrue("page JS click handler updated title", clicked)
        shot("b1-counter-clicked.png")
    }
}
