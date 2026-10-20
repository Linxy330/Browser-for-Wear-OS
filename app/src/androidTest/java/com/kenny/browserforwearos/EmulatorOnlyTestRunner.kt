package com.kenny.browserforwearos

import android.app.Activity
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner

/** Refuse every instrumentation suite before test setup can alter a physical device. */
class EmulatorOnlyTestRunner : AndroidJUnitRunner() {
    override fun callActivityOnResume(activity: Activity) {
        super.callActivityOnResume(activity)
        // Interactive feature suites must not accidentally become display-power tests.
        // Dedicated power tests opt out and exercise real sleep/ambient transitions.
        if (InstrumentationRegistry.getArguments().getString("allow_sleep") != "true") {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    override fun onStart() {
        val qemu = ParcelFileDescriptor.AutoCloseInputStream(
            uiAutomation.executeShellCommand("getprop ro.kernel.qemu"),
        ).bufferedReader().use { it.readText().trim() }
        check(qemu == "1" && (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))) {
            "Browser tests are restricted to Android emulators. Physical-device execution is disabled."
        }
        super.onStart()
    }
}
