package com.kenny.browserforwearos

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 门禁：错误与安全分支。正常 404 展示服务器页面；DNS 失败有明确失败页；
 * 公网 HTTPS 走手表独立 Wi-Fi（F01/F11/F12 网络证据）。
 */
class ErrorHandlingTest : B1TestBase() {

    @Test
    fun server404PageShownAsIs() {
        navigateTo("$BASE/gone")
        assertTrue(waitTitle("404"))
        // 服务器自渲染的 404 文案出现在内容里（而非浏览器错误页顶替）
        val body = device.wait(
            Until.hasObject(By.textContains("测试站服务器返回的正常")),
            10_000,
        )
        assertTrue("server 404 body rendered", body)
        shot("b1-404-server-page.png")
    }

    @Test
    fun dnsFailureShowsErrorFeedback() {
        navigateTo("https://nonexistent.invalid/")
        // 引擎错误页出现（含失败主机名），不会假成功
        val failed = device.wait(
            Until.hasObject(By.textContains("nonexistent.invalid")),
            20_000,
        )
        assertTrue("engine error page mentions failing host", failed)
        shot("b1-dns-error.png")
    }

    @Test
    fun publicHttpsOverWatchWifi() {
        // 手表独立 Wi-Fi（不经手机转发）访问公网 HTTPS：有效证书链 + 真实渲染
        navigateTo("https://example.com/")
        assertTrue("example.com loads over watch Wi-Fi", waitTitle("Example Domain", 25_000))
        shot("b1-example-com.png")

        navigateTo("https://www.wikipedia.org/")
        assertTrue("wikipedia.org loads", waitTitle("Wikipedia", 25_000))
        shot("b1-wikipedia.png")
    }
}
