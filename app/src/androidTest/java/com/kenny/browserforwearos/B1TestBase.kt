package com.kenny.browserforwearos

import android.content.Intent
import android.graphics.Point
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.kenny.browserforwearos.presentation.MainActivity
import org.junit.After
import org.junit.Assert
import org.junit.Before
import java.util.regex.Pattern

/**
 * B1 真机测试基类：启用无障碍桩（激活 GeckoView 网页 a11y 树）、
 * 保持设备唤醒、封装 地址输入→提交 与 网页元素交互。
 *
 * 已知引擎行为（Pixel Watch 5 / GeckoView 158 实测）：
 * 网页虚拟 a11y 节点的 bounds 是 GeckoView 视图本地坐标，屏幕坐标需加视图原点 Y 偏移。
 */
abstract class B1TestBase {

    protected val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    protected val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val testPkg = instrumentation.context.packageName

    @Before
    open fun baseSetUp() {
        // 激活测试无障碍桩 → GeckoView 输出网页 a11y 树（UiAutomator 可操作网页元素）。
        // 服务绑定异步生效：轮询确认已启用并留出绑定时间，避免应用启动时引擎读到旧状态
        val svc = "$testPkg/$testPkg.StubAccessibilityService"
        device.executeShellCommand("settings put secure enabled_accessibility_services $svc")
        device.executeShellCommand("settings put secure accessibility_enabled 1")
        var active = false
        for (attempt in 1..10) {
            active = device.executeShellCommand("settings get secure enabled_accessibility_services")
                .contains("StubAccessibilityService")
            if (active) break
            Thread.sleep(500)
        }
        check(active) { "stub accessibility service not enabled" }
        Thread.sleep(2_000) // 等系统完成绑定，GeckoView 侧可读到新状态
        // 测试期间保持唤醒（离腕 + 未充电会 ~6s 进入 Doze）
        device.executeShellCommand("dumpsys battery set ac 1")
        device.executeShellCommand("svc power stayon true")
        device.wakeUp()
        launchApp()
    }

    @After
    open fun baseTearDown() {
        // 不在每个测试后禁用 a11y 服务（绑定竞态会破坏后续用例）；整套跑完后统一清理
        device.executeShellCommand("dumpsys battery reset")
    }

    protected fun launchApp() {
        val context = instrumentation.targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        device.wait(
            Until.hasObject(By.text(Pattern.compile("Open web|打开网页|Home|主页"))),
            10_000,
        )
    }

    /** 回首页再进地址屏，输入 URL 并提交。 */
    protected fun navigateTo(url: String) {
        val homeBtn = device.findObject(By.text(Pattern.compile("Home|主页")))
        if (homeBtn != null) {
            homeBtn.click()
            device.wait(Until.hasObject(By.text(Pattern.compile("Open web|打开网页"))), 5_000)
        }
        val open = device.wait(Until.findObject(By.text(Pattern.compile("Open web|打开网页"))), 5_000)
        Assert.assertNotNull("Open web entry", open)
        open!!.click()
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        Assert.assertNotNull("address field", field)
        field!!.text = url
        val go = device.wait(Until.findObject(By.text(Pattern.compile("Go|前往"))), 5_000)
        Assert.assertNotNull("go button", go)
        go!!.click()
    }

    /** 等待状态条出现包含 text 的标题（引擎 onTitleChange 回流）。 */
    protected fun waitTitle(text: String, timeout: Long = 20_000): Boolean =
        device.wait(Until.hasObject(By.textContains(text)), timeout)

    /** 网页 a11y 节点本地坐标 → 屏幕坐标（加 GeckoView 视图原点 Y）。 */
    protected fun webPoint(node: UiObject2): Point {
        val homeBtn = device.findObject(By.text(Pattern.compile("Home|主页")))
        val originY = homeBtn?.getVisibleBounds()?.bottom ?: 0
        val b = node.getVisibleBounds()
        return Point(b.centerX(), b.centerY() + originY)
    }

    /** 真实触摸点击网页元素（a11y ACTION_CLICK 不触发页面 JS，须坐标注入）。 */
    protected fun clickWeb(node: UiObject2) {
        val p = webPoint(node)
        device.click(p.x, p.y)
    }

    /** Gecko 网页 a11y 树在加载期间频繁刷新；交互包裹重试。 */
    protected fun <T> retryStale(times: Int = 5, block: () -> T): T? {
        var last: StaleObjectException? = null
        repeat(times) {
            try {
                return block()
            } catch (e: StaleObjectException) {
                last = e
                Thread.sleep(500)
            }
        }
        return null
    }

    protected fun shot(name: String) {
        val dir = instrumentation.targetContext.getExternalFilesDir(null) ?: return
        device.takeScreenshot(java.io.File(dir, name))
    }

    companion object {
        const val BASE = "http://127.0.0.1:8787"
    }
}
