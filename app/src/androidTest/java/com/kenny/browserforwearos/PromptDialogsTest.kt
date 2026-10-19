package com.kenny.browserforwearos

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * B1 门禁：JS 原生提示 alert/confirm/prompt 的原生弹窗与应答（F06 前置）。
 */
class PromptDialogsTest : B1TestBase() {

    private fun pageButton(label: String) =
        device.wait(Until.findObject(By.text(label)), 10_000)

    private fun okButton() =
        device.wait(Until.findObject(By.text(Pattern.compile("好|OK"))), 5_000)

    private fun cancelButton() =
        device.wait(Until.findObject(By.text(Pattern.compile("取消|Cancel"))), 5_000)

    @Test
    fun alertDialogShowsAndDismisses() {
        navigateTo("$BASE/alerts")
        assertTrue(waitTitle("原生提示"))
        retryStale { clickWeb(pageButton("alert")!!); true }
        // 原生弹窗显示真实消息
        val dialog = device.wait(Until.hasObject(By.textContains("测试警告框")), 8_000)
        assertTrue("alert dialog shows message", dialog)
        shot("b1-alert-dialog.png")
        okButton()!!.click()
        // 弹窗关闭（消息文本消失）
        val closed = device.wait(Until.gone(By.textContains("测试警告框")), 8_000)
        assertTrue("alert dialog dismissed", closed)
    }

    @Test
    fun confirmDialogReturnsResultToPage() {
        navigateTo("$BASE/alerts")
        assertTrue(waitTitle("原生提示"))
        retryStale { clickWeb(pageButton("confirm")!!); true }
        assertTrue(device.wait(Until.hasObject(By.textContains("确认继续？")), 8_000))
        okButton()!!.click()
        // 页面收到 true（JS confirm 返回值真实回流）
        val result = device.wait(Until.hasObject(By.textContains("confirm=true")), 8_000)
        assertTrue("page received confirm=true", result)
        shot("b1-confirm-true.png")
    }

    @Test
    fun promptDialogWritesTextBackToPage() {
        navigateTo("$BASE/alerts")
        assertTrue(waitTitle("原生提示"))
        retryStale { clickWeb(pageButton("prompt")!!); true }
        assertTrue(device.wait(Until.hasObject(By.textContains("输入点什么？")), 8_000))
        // 原生弹窗内的输入框（Compose BasicTextField）
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        assertTrue("prompt dialog input", field != null)
        field!!.text = "手表输入"
        okButton()!!.click()
        val result = device.wait(Until.hasObject(By.textContains("手表输入")), 8_000)
        assertTrue("page received prompt text", result)
        shot("b1-prompt-back.png")
    }
}
