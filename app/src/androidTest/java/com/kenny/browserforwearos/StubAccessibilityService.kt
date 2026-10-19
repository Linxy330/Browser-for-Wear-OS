package com.kenny.browserforwearos

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 测试用无障碍桩服务：启用后 GeckoView 激活网页内容的可访问性树，
 * UiAutomator 才能看到/操作网页内部元素（Fenix UI 测试同款机制）。
 * 仅存在于 androidTest APK，不进入交付包。
 */
class StubAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
