package com.kenny.browserforwearos

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * B1 门禁：真实引擎的连续导航（≥10 次）、链接跳转、历史后退。
 * 页面标题回流状态条，用其断言每次导航的真实结果。
 */
class MultiNavigationTest : B1TestBase() {

    private fun goAndExpect(url: String, title: String) {
        navigateTo(url)
        assertTrue("navigate to $url → title '$title'", waitTitle(title, 20_000))
    }

    @Test
    fun tenConsecutiveNavigations() {
        goAndExpect("$BASE/counter", "计数器")
        goAndExpect("$BASE/form", "表单")
        goAndExpect("$BASE/table", "横向表格")
        goAndExpect("$BASE/long", "长页面")
        goAndExpect("$BASE/alerts", "原生提示")
        goAndExpect("$BASE/zh", "中文输入")
        goAndExpect("$BASE/newwin", "新窗口")
        goAndExpect("$BASE/echo", "回显")
        goAndExpect("$BASE/gone", "404")
        goAndExpect("$BASE/redirect", "计数器")
        goAndExpect("$BASE/corners", "四角")
        shot("b1-nav-corners.png")
    }

    @Test
    fun inPageLinkNavigationAndHistoryBack() {
        // 首页入口：测试站索引页的真实链接点击（引擎内链接跳转）
        navigateTo("$BASE/")
        assertTrue(waitTitle("受控测试站", 20_000))

        // 网页 a11y 树中的链接点击 → 引擎真实导航（树随页面构建有延迟，轮询）
        var link: androidx.test.uiautomator.UiObject2? = null
        var tries = 0
        while (link == null && tries < 15) {
            link = device.findObject(By.text("长页面"))
            if (link == null) {
                Thread.sleep(1_000)
                tries++
            }
        }
        if (link == null) {
            // 留证：索引页 a11y 树与截图
            device.executeShellCommand(
                "uiautomator dump /data/local/tmp/idx.xml && " +
                    "cp /data/local/tmp/idx.xml /storage/emulated/0/Android/data/" +
                    "com.kenny.browserforwearos/files/idx.xml",
            )
            shot("b1-index-nolink.png")
        }
        assertTrue("index link 长页面 clickable", link != null)
        clickWeb(link!!)
        assertTrue("link navigation → 长页面", waitTitle("长页面"))

        // 地址栏导航到 /counter 压入历史，再系统返回键经 BackHandler 触发引擎 goBack
        navigateTo("$BASE/counter")
        assertTrue(waitTitle("计数器"))
        device.pressBack()
        device.waitForIdle(2_000)
        assertTrue("back history reaches 长页面", waitTitle("长页面", 15_000))
    }

    @Test
    fun fixedCornerButtonsClickableInPage() {
        navigateTo("$BASE/corners")
        assertTrue(waitTitle("四角"))
        // 四个 fixed 定位按钮真实点击（F04 引擎侧基础：fixed 元素可命中），
        // 每次点击 document.title = "四角 N"，回流状态条后断言 N 递增
        var n = 1
        for (label in listOf("左上", "右上", "左下", "右下")) {
            val btn = device.wait(Until.findObject(By.text(label)), 10_000)
            assertTrue("corner button $label visible", btn != null)
            clickWeb(btn!!)
            assertTrue("click $label → 四角 $n", waitTitle("四角 $n", 8_000))
            n++
        }
        shot("b1-corners-clicked.png")
    }
}
