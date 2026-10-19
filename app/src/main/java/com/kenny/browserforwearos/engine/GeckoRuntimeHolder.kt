package com.kenny.browserforwearos.engine

import android.content.Context
import com.kenny.browserforwearos.BuildConfig
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * 每进程一次的 GeckoRuntime 持有者（规范 3.2 规则 1）。
 * Compose 重组、Activity 重建不得重复 create；任何调用方都经此获取单例。
 */
object GeckoRuntimeHolder {

    @Volatile
    private var runtime: GeckoRuntime? = null

    fun runtime(context: Context): GeckoRuntime =
        runtime ?: synchronized(this) {
            runtime ?: GeckoRuntime.create(
                context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .aboutConfigEnabled(false)
                    .remoteDebuggingEnabled(false)
                    // 控制台/调试日志仅 debug 构建，release 无额外输出（规范 5：历史与 Cookie 不进日志）
                    .debugLogging(BuildConfig.DEBUG)
                    .consoleOutput(BuildConfig.DEBUG)
                    .build(),
            ).also { runtime = it }
        }
}
