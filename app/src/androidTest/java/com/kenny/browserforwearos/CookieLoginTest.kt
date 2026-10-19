package com.kenny.browserforwearos

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * B1 门禁：普通表单与 Cookie 登录（F06 前置）。
 * 使用测试账号 tester/test123（非真实凭据）；验证 登录→会话保持→刷新→退出。
 */
class CookieLoginTest : B1TestBase() {

    @Test
    fun loginKeepsSessionAcrossReloadAndLogout() {
        // 登录页：填写表单并提交（真实网页表单）
        navigateTo("$BASE/login")
        assertTrue(waitTitle("登录"))

        val found = retryStale {
            val fields = device.findObjects(By.clazz("android.widget.EditText"))
            fields.size >= 2
        } == true
        val filled = found && retryStale {
            val fields = device.findObjects(By.clazz("android.widget.EditText"))
            fields[0].text = "tester"
            fields[1].text = "test123"
            true
        } == true
        assertTrue("two form fields filled (user+password)", filled)

        // 提交按钮（网页内 button 登录）
        val submit = device.wait(
            Until.findObject(By.clazz("android.widget.Button").text("登录")),
            5_000,
        ) ?: device.findObject(By.clazz("android.widget.Button"))
        assertTrue("submit button", submit != null)
        retryStale { clickWeb(submit!!); true }

        assertTrue("login success page", waitTitle("登录成功"))
        shot("b1-login-success.png")

        // 会话页显示用户名（Cookie 生效）
        navigateTo("$BASE/me")
        assertTrue("session shows tester", waitTitle("会话"))
        val me = device.wait(Until.hasObject(By.textContains("tester")), 8_000)
        assertTrue("已登录：tester visible", me)

        // 退出登录（服务器清除 Cookie）
        navigateTo("$BASE/logout")
        assertTrue(waitTitle("退出"))
        navigateTo("$BASE/me")
        val notLoggedIn = device.wait(Until.hasObject(By.textContains("未登录")), 8_000)
        assertTrue("logged out confirmed", notLoggedIn)
        shot("b1-logout.png")
    }
}
