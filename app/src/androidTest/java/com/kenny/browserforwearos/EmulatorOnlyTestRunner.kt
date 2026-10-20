package com.kenny.browserforwearos

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.runner.AndroidJUnitRunner

/** Refuse every instrumentation suite before test setup can alter a physical device. */
class EmulatorOnlyTestRunner : AndroidJUnitRunner() {
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
