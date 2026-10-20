package com.kenny.browserforwearos

import android.app.Application
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.kenny.browserforwearos.data.BrowserRepository
import com.kenny.browserforwearos.data.BrowserSnapshot
import com.kenny.browserforwearos.presentation.BrowserViewModel
import com.kenny.browserforwearos.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Exclusive instrumentation run: do not run alongside other tests or manual app control.
 * Requires emulator-scoped port 8787 reverse to the controlled HTTP server.
 * Default archive run is 1800 seconds. SOAK_SECONDS is only for shorter development runs.
 * Covers Activity pause/stop/resume, not display power, Doze, physical heat, or Ambient.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorSoakTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val device by lazy { UiDevice.getInstance(instrumentation) }
    private lateinit var model: BrowserViewModel
    private val runId = UUID.randomUUID().toString()
    private var startedAt = 0L
    private var requestedSeconds = 1800L
    private var cycles = 0
    private var navigationRequests = 0
    private var tabSwitches = 0
    private var backgrounds = 0
    private var resumedGets = 0
    private var domTouches = 0
    private var renderChecks = 0
    private var phase = "setup"
    private var status = "RUNNING"
    private var failureClass: String? = null

    private val emulatorOnly = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val qemu = ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu"),
                ).bufferedReader().use { it.readText().trim() }
                assertEquals("Soak test refuses physical devices", "1", qemu)
                val fingerprint = Build.FINGERPRINT.lowercase()
                assertTrue("Expected an Android emulator SDK fingerprint", fingerprint.contains("generic") || fingerprint.contains("emulator") ||
                    (Build.PRODUCT.startsWith("sdk_") && Build.HARDWARE in setOf("ranchu", "goldfish")))
                val repository = BrowserRepository(app)
                val original = runBlocking { repository.read().snapshot }
                runBlocking { repository.save(BrowserSnapshot()) }
                try {
                    base.evaluate()
                } finally {
                    // The inner ActivityScenario rule has closed the real app before restore.
                    runBlocking { repository.save(original) }
                }
            }
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(emulatorOnly).around(compose)

    @Before
    fun waitForRestoredProfile() {
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.waitUntil(25_000) { model.ready.value }
    }

    @Test
    fun thirtyMinuteNavigationTabsAndActivityRecovery() {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString("base_url")?.trimEnd('/') ?: "http://127.0.0.1:8787"
        requestedSeconds = (arguments.getString("SOAK_SECONDS") ?: arguments.getString("soak_seconds"))
            ?.toLongOrNull()?.coerceIn(30L, 10_800L) ?: 1800L
        val fullRun = requestedSeconds >= 1800L
        val plannedCycles = if (fullRun) 36 else min(36, max(3, (requestedSeconds / 20).toInt()))
        val plannedBackgrounds = if (fullRun) 10 else min(2, plannedCycles)
        val pages = listOf(
            "$baseUrl/counter" to "计数器",
            "$baseUrl/long" to "长页面",
            "$baseUrl/corners" to "四角按钮",
            "$baseUrl/regression/interaction?mode=counter" to "REGRESSION_READY",
        )
        try {
            // Enter Web through the actual Compose address UI, then retain that real screen.
            val open = app.getString(R.string.b1_open_web)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(open))
            compose.onNode(hasText(open) and hasClickAction()).performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasSetTextAction())
            compose.onNode(hasSetTextAction()).performTextReplacement(pages.first().first)
            compose.onNode(hasSetTextAction()).performImeAction()
            navigationRequests++
            awaitPage(pages.first().first, pages.first().second)
            compose.runOnUiThread { assertTrue(model.newTab()); assertTrue(model.newTab()) }
            val tabIds = model.tabs.value.map { it.id }
            assertEquals(3, tabIds.size)
            startedAt = SystemClock.elapsedRealtime()
            saveSummary()

            repeat(plannedCycles) { index ->
                phase = "navigation"
                val id = tabIds[index % tabIds.size]
                val page = pages[index % pages.size]
                val previousId = model.selectedTabId.value
                var revision = 0L
                compose.runOnUiThread {
                    model.selectTab(id)
                    if (id != previousId) tabSwitches++
                    revision = model.controller.committedRevision.value
                    assertTrue("Controlled URL submission should be accepted", model.submitAddress(page.first))
                }
                navigationRequests++
                awaitPage(page.first, page.second, revision)
                if (page.second == "REGRESSION_READY") {
                    touchWebCentre()
                    await("Real DOM counter should receive the injected touch") { model.controller.title.value == "REGRESSION_CLICK_1" }
                    domTouches++
                }

                // Ten actual stop/resume cycles, distributed across the full observation.
                val shouldBackground = ((index + 1) * plannedBackgrounds / plannedCycles) > backgrounds
                if (shouldBackground) {
                    phase = "activity_stopped"
                    saveSummary()
                    val beforeResumeRevision = model.controller.committedRevision.value
                    compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                    Thread.sleep(6_200)
                    await("All tabs must close after the bounded background grace period", 5_000) {
                        model.tabs.value.all { it.controller.suspendedState.value }
                    }
                    assertTrue("Background native sessions must all be closed", onUi { model.tabs.value.all { !it.controller.session.isOpen } })
                    backgrounds++
                    phase = "activity_resumed"
                    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                    awaitPage(page.first, page.second, beforeResumeRevision)
                    resumedGets++
                }
                cycles++
                phase = "foreground_idle"
                saveSummary()
                val nextDeadline = startedAt + requestedSeconds * 1000L * (index + 1) / plannedCycles
                while (SystemClock.elapsedRealtime() < nextDeadline) {
                    compose.waitForIdle()
                    assertNull("Foreground controlled page unexpectedly failed", model.controller.error.value)
                    assertFalse("Selected logical tab must remain open", model.controller.closed)
                    Thread.sleep(min(500L, max(1L, nextDeadline - SystemClock.elapsedRealtime())))
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            assertTrue("Observation must last the requested duration", elapsed >= requestedSeconds * 1000L)
            assertEquals(plannedCycles, cycles)
            assertEquals(plannedBackgrounds, backgrounds)
            assertEquals(backgrounds, resumedGets)
            if (fullRun) {
                assertTrue("Archive run needs at least 20 tab switches", tabSwitches >= 20)
                assertTrue("Archive run needs at least 10 background cycles", backgrounds >= 10)
                assertTrue("Archive run needs at least 30 foreground navigations", navigationRequests >= 30)
            }
            status = "PASS"
            phase = "complete"
        } catch (error: Throwable) {
            status = "FAIL"
            failureClass = error.javaClass.simpleName
            throw error
        } finally {
            // Restore the activity state before the Compose rule tears its host down.
            runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
            saveSummary()
        }
    }

    private fun awaitPage(url: String, title: String, afterRevision: Long = -1L) {
        compose.waitForIdle()
        await("Controlled page must commit without an engine failure") {
            model.controller.error.value == null && model.controller.committedUrl.value == url &&
                !model.controller.isLoading.value && model.controller.title.value == title &&
                model.controller.committedRevision.value > afterRevision
        }
        compose.waitForIdle()
        await("Visible native viewport must bind the selected product session", 8_000) {
            onUi { findGecko(compose.activity.window.decorView, model.controller.session) != null }
        }
        renderChecks++
    }

    private fun touchWebCentre() {
        Thread.sleep(350)
        val bounds = onUi {
            val view = checkNotNull(findGecko(compose.activity.window.decorView, model.controller.session))
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
        }
        assertTrue(device.click(bounds.centerX(), bounds.centerY()))
    }

    private fun findGecko(view: View, session: GeckoSession): GeckoView? {
        if (view is GeckoView && view.isAttachedToWindow && view.width > 0 && view.session === session) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findGecko(view.getChildAt(index), session)?.let { return it }
        }
        return null
    }

    private fun await(message: String, timeout: Long = 25_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            // Immediate errors should fail at the relevant operation instead of silently timing out.
            if (phase != "activity_stopped" && model.controller.error.value != null) fail("Controlled page exposed an engine error during $phase")
            Thread.sleep(100)
        }
        fail(message)
    }

    private fun <T> onUi(action: () -> T): T = compose.runOnUiThread(action)

    private fun saveSummary() {
        val elapsed = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt
        val archiveEligible = status == "PASS" && requestedSeconds >= 1800 && elapsed >= 1_800_000 &&
            navigationRequests >= 30 && tabSwitches >= 20 && backgrounds >= 10 && resumedGets == backgrounds
        val summary = JSONObject()
            .put("runId", runId)
            .put("status", status)
            .put("phase", phase)
            .put("requestedSeconds", requestedSeconds)
            .put("elapsedMilliseconds", elapsed)
            .put("cycles", cycles)
            .put("foregroundNavigationRequests", navigationRequests)
            .put("tabSwitches", tabSwitches)
            .put("backgroundCycles", backgrounds)
            .put("resumedGetLoads", resumedGets)
            .put("domTouches", domTouches)
            .put("selectedSessionRenderChecks", renderChecks)
            .put("archiveEligible", archiveEligible)
            .put("deviceScope", "Android emulator")
            .put("lifecycleScope", "Activity pause/stop/resume")
            .put("displayPowerAndAmbientCovered", false)
            .put("systemSettingsChanged", false)
            .put("failureClass", failureClass ?: JSONObject.NULL)
        Log.i("EmulatorSoak", summary.toString())
        val directory = instrumentation.targetContext.getExternalFilesDir(null) ?: return
        val text = summary.toString(2)
        File(directory, "emulator-soak-$runId.json").writeText(text)
        File(directory, "emulator-soak-latest.json").writeText(text)
    }
}
