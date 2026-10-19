package com.kenny.browserforwearos

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Test

/** 一次性诊断探针：枚举索引页 a11y 树内全部节点，定位链接的暴露方式。 */
class DebugProbeTest : B1TestBase() {

    @Test
    fun probeIndexTree() {
        navigateTo("$BASE/")
        check(waitTitle("受控测试站")) { "index page title" }
        Thread.sleep(5_000)
        val root = device.findObject(By.pkg("com.kenny.browserforwearos"))
        val sb = StringBuilder()
        fun walk(node: androidx.test.uiautomator.UiObject2?, depth: Int) {
            if (node == null || depth > 14) return
            sb.appendLine(
                "  ".repeat(depth) +
                    "[${node.className}] text=${node.text} desc=${node.contentDescription} " +
                    "clickable=${node.isClickable} bounds=${node.getVisibleBounds()}",
            )
            for (child in node.children.orEmpty()) walk(child, depth + 1)
        }
        walk(root, 0)
        val dir = instrumentation.targetContext.getExternalFilesDir(null)
        val f = java.io.File(dir, "probe-index-tree.txt")
        f.writeText(sb.toString())
        android.util.Log.i("Probe", "path=${f.absolutePath} exists=${f.exists()} len=${f.length()} nodes=${sb.length}")
    }
}
