package com.kenny.browserforwearos

import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.kenny.browserforwearos.engine.DeviceActivityMonitor
import com.kenny.browserforwearos.engine.DeviceActivityState
import com.kenny.browserforwearos.engine.TabSessionController
import com.kenny.browserforwearos.presentation.BrowserViewModel
import com.kenny.browserforwearos.presentation.MainActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.util.UUID
import java.io.File
import android.graphics.Color

/**
 * Run separately with -e allow_sleep true through emulator_adb.sh or its API30
 * counterpart. This class never sets KEEP_SCREEN_ON and never calls setForeground.
 * It exercises the real Activity monitor, native renderer, installed IME and power
 * events. The Ambient case requires Always-on Display enabled in emulator Settings.
 *
 * Official Wear testing commands are KEYCODE_SLEEP / KEYCODE_WAKEUP:
 * https://developer.android.com/training/wearables/always-on#debugging-and-testing
 */
@RunWith(AndroidJUnit4::class)
class EmulatorDeviceActivityTest : EmulatorProductFixture() {
    @get:Rule val compose = createEmptyComposeRule()
    override val keepScreenOnForFixture = false
    private val device by lazy { UiDevice.getInstance(instrumentation) }
    private val settingsBefore = linkedMapOf<Pair<String, String>, String>()
    private val runId = UUID.randomUUID().toString()
    private var permitted = false

    @Before
    fun requireSeparatePowerRunAndPreserveTimeoutSettings() {
        assertEquals("Run this power suite separately with -e allow_sleep true", "true",
            InstrumentationRegistry.getArguments().getString("allow_sleep"))
        permitted = true
        awaitUiPump = { compose.mainClock.advanceTimeByFrame() }
        // Charging must not override explicit sleep. A long inactivity timeout keeps
        // the independent IME case focused on its >5s regression, without a wake lock.
        preserveAndSet("global", "stay_on_while_plugged_in", "0")
        preserveAndSet("system", "screen_off_timeout", "120000")
    }

    @After
    fun restoreOnlyTheSettingsChangedByThisCase() {
        if (!permitted) return
        try {
            guardedKey(KeyEvent.KEYCODE_WAKEUP)
            settingsBefore.entries.toList().asReversed().forEach { (key, value) ->
                val (namespace, name) = key
                if (value == "null") emulatorShell("settings delete $namespace $name")
                else emulatorShell("settings put $namespace $name $value")
                assertEquals("Restore the emulator setting $namespace/$name", value,
                    emulatorShell("settings get $namespace $name").trim())
            }
        } finally {
            settingsBefore.clear()
        }
    }

    @Test
    fun powerKeyThenSleepAndWakeRebuildsSelectedPageAndRealDomInput() {
        val model = openActualPage("$base/regression/interaction?mode=counter", "REGRESSION_READY")
        val controller = model.controller
        // POWER can return to the watch face on Wear. Test both that background
        // transition and explicit SLEEP; each must hibernate and restore a live page.
        for (key in listOf(KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP)) {
            await("The real monitor must be active before key $key") { monitorState().active }
            val oldSession = onMain { controller.session }
            val revision = controller.committedRevision.value
            assertTrue(onMain { oldSession.isOpen })
            guardedKey(key)
            await("Power event $key must make the real Activity monitor inactive") { !monitorState().active }
            await("Power event $key must close the page after the 5s background grace", 15_000) {
                controller.suspended && onMain { !oldSession.isOpen }
            }
            record("POWER_${key}_NATIVE_CLOSED", controller)
            wakeAndVerifyRestoredPage(model, controller, oldSession, revision)
        }
    }

    @Test
    fun actualAmbientCallbackSuspendsPageAndWakeRestoresRealDom() {
        val model = openActualPage("$base/regression/interaction?mode=counter", "REGRESSION_READY")
        val controller = model.controller
        val oldSession = onMain { controller.session }
        val revision = controller.committedRevision.value
        guardedKey(KeyEvent.KEYCODE_SLEEP)
        await("Enable Always-on Display in this emulator: KEYCODE_SLEEP must deliver an actual Ambient callback", 25_000) {
            monitorState().ambient
        }
        val ambient = monitorState()
        assertFalse("Ambient must stop page activity even if hardware remains interactive", ambient.active)
        if (Build.VERSION.SDK_INT >= 36) {
            assertTrue("Wear 6+ always-on app should remain resumed during the Ambient transition", ambient.resumed)
        }
        record("ACTUAL_AMBIENT", controller)
        await("Ambient must cover the bright webpage with a predominantly black clock display") { ambientBlackRatio() >= .85 }
        val screenshot = File(app.getExternalFilesDir(null), "product-screens/ambient.png").apply { parentFile!!.mkdirs() }
        assertTrue("Save the real ambient UI for visual review", device.takeScreenshot(screenshot))
        await("Ambient pages must hibernate after the background grace", 15_000) {
            controller.suspended && onMain { !oldSession.isOpen }
        }
        wakeAndVerifyRestoredPage(model, controller, oldSession, revision)
    }

    @Test
    fun installedImeVisibleBeyondFiveSecondsKeepsNativeFormAndRealPostUsable() {
        val model = openActualPage("$base/forms-regression/ime", "FORMS_IME_READY")
        val controller = model.controller
        val originalSession = onMain { controller.session }
        val token = "Qw"
        val defaultIme = emulatorShell("settings get secure default_input_method").trim()
        val imePackage = defaultIme.substringBefore('/')
        assertTrue("An installed system IME is required for this UI regression", imePackage.contains('.'))
        assertEquals("This visually verified fixture uses the official image's Wear Gboard",
            "com.google.android.inputmethod.latin", imePackage)
        assertEquals("Recheck real keyboard key positions before using a different display", 454, device.displayWidth)
        assertEquals("Recheck real keyboard key positions before using a different display", 454, device.displayHeight)
        tapActualPageCentre(controller)
        await("A real DOM focus must display the installed IME") { imeVisible(imePackage) }
        device.waitForIdle(2_000)
        assertTrue("The real keyboard must remain displayed before tapping its keys", imeVisible(imePackage))
        // Wear Gboard's full-screen extract editor consumes injected hardware text locally.
        // Actual Q/w soft keys exercise its real composing InputConnection instead. These
        // positions were visually verified on this dedicated 454px official Wear image.
        assertTrue(device.click(45, 200))
        assertTrue(device.click(87, 200))
        await("Actual soft keyboard composition must reach the real DOM input") {
            controller.title.value in setOf("FORMS_COMPOSING_$token", "FORMS_COMMITTED_$token")
        }
        val visibleSince = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - visibleSince < 6_500) {
            awaitUiPump?.invoke()
            assertTrue("The installed IME must stay visibly open for the entire >5s interval", imeVisible(imePackage))
            assertTrue("Window-focus loss to the IME must not hibernate the form", onMain { originalSession.isOpen })
            assertSame("An IME must not replace the native form session", originalSession, onMain { controller.session })
            assertFalse(controller.suspended)
            Thread.sleep(200)
        }
        record("INSTALLED_IME_HELD_6500MS", controller)
        assertTrue(controller.title.value in setOf("FORMS_COMPOSING_$token", "FORMS_COMMITTED_$token"))
        val screenshot = File(app.getExternalFilesDir(null), "product-screens/ime-held6500.png")
            .apply { parentFile!!.mkdirs() }
        assertTrue("Save the real visible keyboard after its >5s hold", device.takeScreenshot(screenshot))
        // The real Go key commits composition and submits this HTML form. Back cancels
        // Wear's extract editor and is not a text-entry confirmation gesture.
        assertTrue(device.click(376, 130))
        await("The actual keyboard confirmation must commit and POST the original DOM value") {
            !imeVisible(imePackage) && monitorState().active &&
                controller.title.value == "FORMS_POSTED_$token" && !controller.isLoading.value
        }
        assertNull(controller.error.value)
        assertSame(originalSession, onMain { controller.session })
    }

    private fun openActualPage(url: String, title: String): BrowserViewModel {
        val model = openModel(keepCompose = true, intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        await("The real app must commit the controlled page before a device-state transition") {
            model.controller.committedUrl.value == url && model.controller.title.value == title && !model.controller.isLoading.value
        }
        await("The actual app GeckoView must display its selected session") { onMain { findGecko(model.controller) != null } }
        assertTrue("The power test must not use KEEP_SCREEN_ON", onMain {
            scenarioActivity!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0
        })
        await("The real production monitor must see the interactive foreground app") { monitorState().active }
        assertNull(model.controller.error.value)
        return model
    }

    private fun wakeAndVerifyRestoredPage(
        model: BrowserViewModel, controller: TabSessionController, oldSession: GeckoSession, revision: Long,
    ) {
        guardedKey(KeyEvent.KEYCODE_WAKEUP)
        bringOriginalActivityToFront()
        await("Wake must clear Ambient and make the real monitor active") { monitorState().active && !monitorState().ambient }
        await("Wake must GET the last successful page in a fresh native session", 25_000) {
            controller.committedRevision.value > revision && controller.title.value == "REGRESSION_READY" &&
                !controller.isLoading.value && !controller.suspended
        }
        assertSame("The selected logical tab must survive device sleep", controller, model.controller)
        assertNotSame("Hibernate/wake must replace the closed native session", oldSession, onMain { controller.session })
        assertNull(controller.error.value)
        await("The restored actual GeckoView must attach the replacement native session") { onMain { findGecko(controller) != null } }
        tapActualPageCentre(controller)
        await("A real touchscreen event must reach the restored DOM") { controller.title.value == "REGRESSION_CLICK_1" }
        record("WAKE_DOM_VERIFIED", controller)
    }

    private fun bringOriginalActivityToFront() {
        val original = checkNotNull(scenarioActivity)
        onMain {
            app.startActivity(Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        await("Wake must foreground the original Activity, without creating a replacement model") {
            onMain {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull() === original &&
                    original.lifecycle.currentState == Lifecycle.State.RESUMED
            }
        }
    }

    /** Read-only observation avoids registering a second Wear ambient controller. */
    private fun monitorState(): DeviceActivityState = onMain {
        val activityField = MainActivity::class.java.getDeclaredField("activityMonitor").apply { isAccessible = true }
        val monitor = activityField.get(checkNotNull(scenarioActivity)) as DeviceActivityMonitor
        val stateField = DeviceActivityMonitor::class.java.getDeclaredField("state").apply { isAccessible = true }
        stateField.get(monitor) as DeviceActivityState
    }

    private fun imeVisible(imePackage: String): Boolean = onMain {
        scenarioActivity?.window?.decorView?.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
    } || device.hasObject(By.pkg(imePackage))

    private fun ambientBlackRatio(): Double {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return 0.0
        try {
            var dark = 0
            var count = 0
            val cx = bitmap.width / 2.0
            val cy = bitmap.height / 2.0
            val radiusSquared = minOf(cx, cy).let { it * it * .81 }
            for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) > radiusSquared) continue
                val pixel = bitmap.getPixel(x, y)
                count++
                if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) < 24) dark++
            }
            return dark.toDouble() / count.coerceAtLeast(1)
        } finally { bitmap.recycle() }
    }

    private fun findGecko(controller: TabSessionController): GeckoView? {
        fun find(node: View): GeckoView? {
            if (node is GeckoView && node.isAttachedToWindow && node.width > 0 && node.height > 0 && node.session === controller.session) return node
            if (node is ViewGroup) for (index in 0 until node.childCount) find(node.getChildAt(index))?.let { return it }
            return null
        }
        return find(checkNotNull(scenarioActivity).window.decorView)
    }

    private fun tapActualPageCentre(controller: TabSessionController) {
        Thread.sleep(350)
        val point = onMain {
            val gecko = checkNotNull(findGecko(controller))
            val xy = IntArray(2)
            gecko.getLocationOnScreen(xy)
            xy[0] + gecko.width / 2 to xy[1] + gecko.height / 2
        }
        assertTrue("Actual touchscreen tap must be injected", device.click(point.first, point.second))
    }

    private fun preserveAndSet(namespace: String, name: String, value: String) {
        val key = namespace to name
        val old = emulatorShell("settings get $namespace $name").trim()
        require(old == "null" || old.matches(Regex("[0-9]+"))) { "Unexpected original numeric setting" }
        settingsBefore.putIfAbsent(key, old)
        emulatorShell("settings put $namespace $name $value")
        assertEquals(value, emulatorShell("settings get $namespace $name").trim())
    }

    private fun guardedKey(key: Int) { emulatorShell("input keyevent $key") }

    /** UiAutomation can affect only this instrumentation's transport; recheck QEMU each call. */
    private fun emulatorShell(command: String): String {
        val qemu = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu"))
            .bufferedReader().use { it.readText().trim() }
        check(qemu == "1" && Build.HARDWARE in setOf("ranchu", "goldfish")) { "Device-state tests refuse physical devices" }
        val allowed = command.matches(Regex("input keyevent (26|223|224)")) || command.matches(Regex("input text [a-z0-9]+")) ||
            command.matches(Regex("settings (get|put|delete) (system screen_off_timeout|global stay_on_while_plugged_in)( [0-9]+)?")) ||
            command == "settings get secure default_input_method"
        require(allowed) { "Unapproved device-state fixture command" }
        return ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    }

    private fun record(phase: String, controller: TabSessionController) {
        val state = monitorState()
        Log.i("EmulatorDeviceActivity", JSONObject().put("runId", runId).put("phase", phase)
            .put("resumed", state.resumed).put("ambient", state.ambient).put("interactive", state.interactive)
            .put("displayOn", state.displayOn).put("active", state.active).put("suspended", controller.suspended)
            .put("nativeOpen", onMain { controller.session.isOpen }).put("revision", controller.committedRevision.value)
            .put("wallClockMs", System.currentTimeMillis()).toString())
    }
}
