package com.kenny.browserforwearos.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kenny.browserforwearos.engine.TabSessionController
import kotlinx.coroutines.launch

/**
 * B1 引擎门禁期的浏览器 ViewModel。
 * B2/B3 将扩展为多标签与资料管理（BrowserController + Repository）。
 */
class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    val controller = TabSessionController(app)

    /** 地址/搜索提交；B2 起接入完整 URL/搜索判别规则与单元测试。 */
    fun submitAddress(input: String) {
        val target = normalizeInput(input) ?: return
        viewModelScope.launch {
            controller.loadUri(target)
        }
    }

    override fun onCleared() {
        // 进程内 ViewModel 销毁即退出浏览：关闭会话，释放引擎资源
        controller.close()
    }

    companion object {
        /** B1 临时规则：无 scheme 的输入按 https:// 打开；B2 用正式判别函数替换。 */
        fun normalizeInput(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }
    }
}
