package com.kenny.browserforwearos.engine

import android.content.Context
import android.os.Looper
import androidx.annotation.UiThread
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * 每进程一次的 GeckoRuntime 持有者（规范 3.2 规则 1）。
 * Compose 重组、Activity 重建不得重复 create；任何调用方都经此获取单例。
 */
object GeckoRuntimeHolder {

    @Volatile
    private var runtime: GeckoRuntime? = null

    @UiThread
    fun runtime(context: Context): GeckoRuntime {
        check(Looper.myLooper() == Looper.getMainLooper()) { "GeckoRuntime requires the main thread" }
        return runtime ?: synchronized(this) {
            runtime ?: GeckoRuntime.create(
                context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .aboutConfigEnabled(false)
                    .remoteDebuggingEnabled(false)
                    .forceUserScalableEnabled(true)
                    // Engine and page console output can include URLs or form data.
                    .debugLogging(false)
                    .consoleOutput(false)
                    .build(),
            ).also { runtime = it }
        }
    }
}
