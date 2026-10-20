package com.kenny.browserforwearos

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.lifecycle.Lifecycle
import com.kenny.browserforwearos.data.BrowserRepository
import com.kenny.browserforwearos.data.BrowserSnapshot
import com.kenny.browserforwearos.data.PageColorScheme
import com.kenny.browserforwearos.data.ReadingPreferences
import com.kenny.browserforwearos.engine.GeckoRuntimeHolder
import com.kenny.browserforwearos.engine.TabSessionController
import com.kenny.browserforwearos.presentation.BrowserViewModel
import com.kenny.browserforwearos.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.StorageController
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val AUTHENTICATED = "FORMS_SESSION_AUTHENTICATED"
private const val ANONYMOUS = "FORMS_SESSION_ANONYMOUS"

/**
 * Emulator-only product regressions. Requires the existing loopback fixture on 8787.
 * Does not change system settings, accessibility, power state, or installed packages.
 * Each case starts with an empty metadata profile and restores the previous snapshot
 * only after its Activity/ViewModel writer has drained. The Cookie case also clears
 * this emulated app's website data at both boundaries; all credentials are fixtures.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorProductLifecycleTest : EmulatorProductFixture() {
    @Test
    fun externallyClosedNativeSessionReloadCreatesANewUsableSession() {
        openModel()
        val controller = createController()
        val target = "$base/regression/interaction?mode=counter"
        loadAndWait(controller, target, "REGRESSION_READY")
        val oldSession = onMain { controller.session }
        val revision = controller.committedRevision.value

        // This is a real native-session close, not a simulated ContentDelegate callback.
        // This case proves closed-session recovery. The separate about:crashcontent
        // case below covers an intentionally induced real renderer crash.
        onMain {
            view!!.releaseSession()
            oldSession.close()
            assertFalse(oldSession.isOpen)
            controller.reload()
            assertNotSame("Reload must replace the unusable native session", oldSession, controller.session)
            assertTrue(controller.session.isOpen)
        }
        attach(controller)
        await("Reload must commit the prior GET in the new native session") {
            controller.committedRevision.value > revision && controller.committedUrl.value == target &&
                controller.title.value == "REGRESSION_READY" && !controller.isLoading.value
        }
        assertNull(controller.error.value)
        assertFalse(controller.suspended)
        tapCentre()
        await("The replacement session must paint and deliver a real DOM touch") {
            controller.title.value == "REGRESSION_CLICK_1"
        }
    }

    @Test
    fun intentionalRendererCrashRestoresLastSuccessfulGetAndRealDomInput() {
        openModel()
        val settings = onMain { GeckoRuntimeHolder.runtime(app).settings }
        // Match Mozilla's ContentDelegateTest.crashContent guard exactly in scope.
        // https://bugzilla.mozilla.org/show_bug.cgi?id=1710940
        // Runtime defaults for the fixed Gecko 158 artifact are false for both flags.
        assumeFalse("Mozilla bug 1710940: isolated content processes do not reliably deliver onCrash", settings.isolatedProcessEnabled)
        assumeFalse("Mozilla bug 1710940: App Zygote content processes do not reliably deliver onCrash", settings.appZygoteProcessEnabled)

        val controller = createController()
        val target = "$base/regression/interaction?mode=counter"
        loadAndWait(controller, target, "REGRESSION_READY")
        // PageStop can precede the compositor/frame-loader attachment. Establish a
        // real input round trip before inducing a frame-loader/process failure.
        tapCentre()
        await("The renderer must be painted and accepting input before the intentional crash") {
            controller.title.value == "REGRESSION_CLICK_1"
        }
        val oldSession = onMain { controller.session }
        val oldRevision = controller.committedRevision.value
        val nativeCrashCallbacks = AtomicInteger(0)
        val nativeKillCallbacks = AtomicInteger(0)
        val marker = UUID.randomUUID().toString()
        fun record(phase: String) {
            Log.i(
                "EmulatorProductCrash",
                JSONObject().put("test", "intentionalRendererCrashRestoresLastSuccessfulGetAndRealDomInput")
                    .put("runId", marker).put("phase", phase)
                    .put("expectedRendererCrash", true).put("wallClockMs", System.currentTimeMillis())
                    .put("elapsedRealtimeMs", SystemClock.elapsedRealtime()).toString(),
            )
        }

        onMain {
            val productDelegate = checkNotNull(oldSession.contentDelegate)
            oldSession.contentDelegate = object : ForwardingContentDelegate(productDelegate) {
                override fun onCrash(session: GeckoSession) {
                    nativeCrashCallbacks.incrementAndGet()
                    record("NATIVE_ON_CRASH")
                    productDelegate.onCrash(session)
                }

                override fun onKill(session: GeckoSession) {
                    nativeKillCallbacks.incrementAndGet()
                    record("NATIVE_ON_KILL")
                    productDelegate.onKill(session)
                }
            }
        }
        fun actualTerminationError(): Int? = when {
            nativeCrashCallbacks.get() == 1 && nativeKillCallbacks.get() == 0 -> R.string.engine_error_crash
            nativeKillCallbacks.get() == 1 && nativeCrashCallbacks.get() == 0 -> R.string.engine_error_reclaimed
            else -> null
        }
        fun recordNativeState(phase: String) {
            val state = onMain {
                JSONObject().put("runId", marker).put("phase", phase).put("expectedRendererCrash", true)
                    .put("nativeCrashCallback", nativeCrashCallbacks.get() > 0).put("nativeKillCallback", nativeKillCallbacks.get() > 0)
                    .put("nativeCrashCallbackCount", nativeCrashCallbacks.get()).put("nativeKillCallbackCount", nativeKillCallbacks.get())
                    .put("actualTerminationCallback", when (actualTerminationError()) {
                        R.string.engine_error_crash -> "onCrash"
                        R.string.engine_error_reclaimed -> "onKill"
                        else -> "none_or_duplicate"
                    })
                    .put("oldNativeOpen", oldSession.isOpen).put("sameNative", oldSession === controller.session)
                    .put("suspended", controller.suspended).put("logicalClosed", controller.closed)
                    .put("error", controller.error.value ?: JSONObject.NULL)
                    .put("urlKind", when (controller.url.value) {
                        "about:crashcontent" -> "crash_uri"
                        target -> "last_http"
                        "" -> "blank"
                        else -> "other"
                    }).put("revision", controller.committedRevision.value)
                    .put("wallClockMs", System.currentTimeMillis())
            }
            Log.i("EmulatorProductCrash", state.toString())
        }

        record("INTENTIONAL_TRIGGER")
        try {
            // Mozilla's official ContentDelegateTest.crashContent uses this URI.
            // Call the controller directly; this is never entered through address UI.
            onMain { controller.loadUri("about:crashcontent") }
            var nextDiagnostic = 0L
            // Fixed Gecko 158's GeckoViewContent.sys.mjs observe() dispatches
            // ContentCrash only when its 250ms classification window sees a dumpID;
            // otherwise the same real frame-loader failure becomes ContentKill.
            // Accept the actual native callback and require its matching UI error.
            await("The intentional SIGSEGV must deliver exactly one real Gecko termination callback, close the old native session, and show its matching recovery state", 60_000) {
                if (SystemClock.elapsedRealtime() >= nextDiagnostic) {
                    recordNativeState("WAIT_CRASH")
                    nextDiagnostic = SystemClock.elapsedRealtime() + 5_000
                }
                onMain {
                    val expectedError = actualTerminationError()
                    expectedError != null && !oldSession.isOpen && controller.suspended &&
                        controller.error.value == app.getString(expectedError)
                }
            }
            recordNativeState("NATIVE_TERMINATION_OBSERVED")
            assertEquals("One native session termination must produce exactly one real callback", 1,
                nativeCrashCallbacks.get() + nativeKillCallbacks.get())
            assertEquals(app.getString(checkNotNull(actualTerminationError())), controller.error.value)
            assertSame(oldSession, onMain { controller.session })
            assertEquals(target, controller.committedUrl.value)
            assertEquals("The crash URI must not become a successful HTTP visit", oldRevision, controller.committedRevision.value)
            assertFalse(controller.closed)

            onMain {
                controller.reload()
                assertNotSame("Crash recovery must create a new native identity", oldSession, controller.session)
                assertTrue(controller.session.isOpen)
            }
            attach(controller)
            await("Reload after a real renderer crash must restore the last successful HTTP GET", 60_000) {
                controller.committedUrl.value == target && controller.committedRevision.value > oldRevision &&
                    controller.title.value == "REGRESSION_READY" && !controller.isLoading.value
            }
            assertNull(controller.error.value)
            assertFalse(controller.suspended)
            tapCentre()
            await("The new renderer must paint and deliver real DOM input after crash recovery") {
                controller.title.value == "REGRESSION_CLICK_1"
            }
            assertEquals("Late duplicate termination callbacks must not be hidden", 1,
                nativeCrashCallbacks.get() + nativeKillCallbacks.get())
            record("RECOVERED")
        } finally {
            // This time-bounded marker identifies an expected crash during this case.
            // It must be kept separate from unexpected crash/ANR checks in soak runs.
            recordNativeState("FINAL_NATIVE_STATE")
            record("CASE_FINISHED")
        }
    }

    @Test
    fun privateAndNormalSessionsDoNotShareServerVerifiedCookies() {
        isolateCookies = true
        clearWebsiteData()
        val model = openModel()
        onMain { model.setWebVisible(true) }
        val normal = model.controller
        attach(normal)
        loadAndWait(normal, "$base/forms-regression/session", ANONYMOUS)
        loginThroughRealPost(normal)
        loadAndWait(normal, "$base/forms-regression/session", AUTHENTICATED)

        onMain { assertTrue(model.newTab(privateMode = true)) }
        val privateController = model.controller
        attach(privateController)
        assertTrue(onMain { privateController.session.settings.usePrivateMode })
        loadAndWait(privateController, "$base/forms-regression/session", ANONYMOUS)

        selectTab(model, normal)
        loadAndWait(normal, "$base/forms-regression/logout", "FORMS_LOGOUT_OK")
        loadAndWait(normal, "$base/forms-regression/session", ANONYMOUS)
        selectTab(model, privateController)
        loginThroughRealPost(privateController)
        loadAndWait(privateController, "$base/forms-regression/session", AUTHENTICATED)

        selectTab(model, normal)
        loadAndWait(normal, "$base/forms-regression/session", ANONYMOUS)
        selectTab(model, privateController)
        loadAndWait(privateController, "$base/forms-regression/logout", "FORMS_LOGOUT_OK")
        loadAndWait(privateController, "$base/forms-regression/session", ANONYMOUS)
    }

    @Test
    fun privateTabNeverEntersHistoryOrVersionTwoRestoration() {
        val model = openModel()
        val token = UUID.randomUUID().toString()
        val normalUrl = "$base/counter?public_product=$token"
        val privateUrl = "$base/long?private_product=$token"
        val normal = model.controller
        onMain { model.setWebVisible(true) }
        attach(normal)
        loadAndWait(normal, normalUrl, "计数器")
        await("The normal visit must become history before testing private exclusion") {
            model.history.value.map { it.url } == listOf(normalUrl)
        }
        onMain { assertTrue(model.newTab(privateMode = true)) }
        val privateController = model.controller
        assertTrue(privateController.privateMode)
        attach(privateController)
        loadAndWait(privateController, privateUrl, "长页面")
        onMain { model.setReading(model.reading.value) } // Queue a final profile write.

        closeScenario()
        val saved = readSnapshot() // Waits for the old ViewModel's final writer.
        assertEquals(listOf(normal.tabId), saved.tabs.map { it.id })
        assertEquals(normalUrl, saved.tabs.single().url)
        assertEquals(normal.tabId, saved.selectedTabId)
        assertEquals(listOf(normalUrl), saved.history.map { it.url })
        assertTrue(saved.bookmarks.isEmpty())
        val encoded = profileJson()
        assertEquals(2, encoded.getInt("version"))
        assertFalse("Private native identity must not reach disk", encoded.toString().contains(privateController.tabId))
        assertFalse("Private URL must not reach disk", encoded.toString().contains("private_product"))

        val restored = openModel()
        assertNotSame(model, restored)
        assertEquals(listOf(normal.tabId), restored.tabs.value.map { it.id })
        assertEquals(normal.tabId, restored.selectedTabId.value)
        // A restored hidden tab is pending until shown. Opening an un-navigated
        // native session can emit about:blank after seedMetadata; verify the disk
        // metadata above, then demand the actual restored GET before its display URL.
        onMain { restored.setWebVisible(true) }
        attach(restored.controller)
        await("Showing the restored normal tab must perform its saved HTTP GET") {
            restored.controller.committedUrl.value == normalUrl &&
                restored.controller.title.value == "计数器" && !restored.controller.isLoading.value
        }
        assertEquals(normalUrl, restored.controller.url.value)
        assertNull(restored.controller.error.value)
        assertTrue(restored.tabs.value.none { it.controller.privateMode })
        assertEquals(listOf(normalUrl), restored.history.value.map { it.url })
    }

    @Test
    fun versionTwoReadingPreferencesRestoreIntoFreshModelAndNativeRuntime() {
        val model = openModel()
        val preferences = ReadingPreferences(textPercent = 135, colorScheme = PageColorScheme.DARK)
        onMain { model.setReading(preferences) }
        await("The v2 profile must persist reading preferences") { readSnapshot().reading == preferences }
        assertReadingRuntime(preferences)
        closeScenario()
        val encoded = profileJson()
        assertEquals(2, encoded.getInt("version"))
        assertEquals(135, encoded.getJSONObject("reading").getInt("textPercent"))
        assertEquals("DARK", encoded.getJSONObject("reading").getString("colorScheme"))

        // Remove the first model's native setting so the second model must apply it.
        onMain { applyReadingRuntime(ReadingPreferences()) }
        val restored = openModel()
        assertNotSame(model, restored)
        assertEquals(preferences, restored.reading.value)
        assertReadingRuntime(preferences)
    }
}

/** Exercises the real exported Activity intent path and its Compose preview surface. */
@RunWith(AndroidJUnit4::class)
class EmulatorProductIntentTest : EmulatorProductFixture() {
    // This rule launches no Activity; @Before verifies QEMU before any app mutation.
    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun advanceComposeFramesDuringNativeWaits() {
        awaitUiPump = { compose.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun actionViewOpensHttpUrlAndSingleTopDeliveryKeepsTheExistingTab() {
        val firstUrl = "$base/counter?external_product=${UUID.randomUUID()}"
        val secondUrl = "$base/long?external_product=${UUID.randomUUID()}"
        val model = openModel(keepCompose = true, intent = Intent(Intent.ACTION_VIEW, Uri.parse(firstUrl)))
        await("ACTION_VIEW must open the first real HTTP document") {
            model.controller.committedUrl.value == firstUrl && model.controller.title.value == "计数器" &&
                !model.controller.isLoading.value
        }
        val first = model.controller
        val firstId = model.selectedTabId.value
        scenario!!.onActivity { activity ->
            activity.startActivity(
                Intent(activity, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse(secondUrl)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        await("A second singleTop VIEW must open another real document") {
            val selected = model.tabs.value.firstOrNull { it.id == model.selectedTabId.value }?.controller
            model.tabs.value.size == 2 && model.selectedTabId.value != firstId &&
                selected != null && selected.committedUrl.value == secondUrl &&
                selected.title.value == "长页面" && !selected.isLoading.value
        }
        onMain {
            val activity = checkNotNull(scenarioActivity)
            assertSame("singleTop delivery must use the original Activity's model", model, ViewModelProvider(activity)[BrowserViewModel::class.java])
        }
        assertSame(first, model.tabs.value.single { it.id == firstId }.controller)
        assertNotEquals(first.tabId, model.selectedTabId.value)
        assertEquals(firstUrl, first.committedUrl.value)
        assertNull(model.controller.error.value)
    }

    @Test
    fun actionSendPreviewsSharedTextAndNavigatesOnlyAfterExplicitSubmit() {
        val shared = "$base/long?shared_product=${UUID.randomUUID()}"
        val model = openModel(
            keepCompose = true,
            intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shared),
        )
        compose.waitUntil(25_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 && model.incoming.value == null
        }
        compose.onNode(hasSetTextAction()).assertTextEquals(shared)
        assertEquals("Shared text must wait for submission", "", model.controller.url.value)
        assertEquals("Shared text must not create a document", 0L, model.controller.committedRevision.value)
        assertTrue(model.history.value.isEmpty())
        val previewSnapshot = readSnapshot()
        assertTrue(previewSnapshot.tabs.all { it.url == null })
        assertTrue(previewSnapshot.history.isEmpty())
        assertFalse("Unsubmitted shared text must not be persisted", profileJson().toString().contains("shared_product"))

        compose.onNode(hasSetTextAction()).performImeAction()
        await("Submitting the visible shared-text preview must open its HTTP target") {
            model.controller.committedUrl.value == shared && model.controller.title.value == "长页面" &&
                !model.controller.isLoading.value
        }
        assertNull(model.controller.error.value)
    }
}

/** App-owned resources only. A custom runner and this independent guard reject real devices. */
abstract class EmulatorProductFixture {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val app get() = instrumentation.targetContext.applicationContext as Application
    protected lateinit var base: String
    protected var scenario: ActivityScenario<MainActivity>? = null
    protected var view: GeckoView? = null
    protected var isolateCookies = false
    protected var awaitUiPump: (() -> Unit)? = null
    protected open val keepScreenOnForFixture: Boolean = true
    protected var scenarioActivity: MainActivity? = null
        private set
    private var scenarioLaunchIntent: Intent? = null
    private val controllers = mutableListOf<TabSessionController>()
    private var profileBefore: BrowserSnapshot? = null
    private var emulatorVerified = false

    @Before
    fun requireEmulatorAndIsolateMetadata() {
        val qemu = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu"),
        ).bufferedReader().use { it.readText().trim() }
        assertEquals("Product regression refuses physical devices", "1", qemu)
        assertTrue("Expected an emulator SDK build", Build.HARDWARE in setOf("ranchu", "goldfish"))
        emulatorVerified = true
        wakeForFixture()
        base = InstrumentationRegistry.getArguments().getString("base_url")?.trimEnd('/') ?: "http://127.0.0.1:8787"
        val uri = URI(base)
        require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2")) {
            "Product tests require the controlled emulator loopback fixture"
        }
        assertTrue("Start tools/testsite/server.py and emulator-only reverse 8787", rawHttp("$base/regression/metrics?token=product-setup").contains("ticks"))
        profileBefore = readSnapshot()
        runBlocking { BrowserRepository(app).save(BrowserSnapshot()) }
    }

    @After
    fun closeActivitiesDrainWritersAndRestoreMetadata() {
        if (!emulatorVerified) return
        try {
            closeScenario()
        } finally {
            try {
                onMain {
                    controllers.forEach { it.close() }
                    controllers.clear()
                }
                if (isolateCookies) clearWebsiteData()
            } finally {
                profileBefore?.let { original ->
                    readSnapshot() // Wait for any final queued write before restoring the fixture.
                    runBlocking { BrowserRepository(app).save(original) }
                    onMain { applyReadingRuntime(original.reading) }
                }
            }
        }
    }

    protected fun openModel(keepCompose: Boolean = false, intent: Intent? = null): BrowserViewModel {
        check(scenario == null)
        wakeForFixture()
        val launch = (intent ?: Intent(Intent.ACTION_MAIN)).setClass(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        scenario = ActivityScenario.launch(launch)
        var model: BrowserViewModel? = null
        scenario!!.onActivity { activity ->
            scenarioActivity = activity
            scenarioLaunchIntent = Intent(activity.intent)
            if (keepScreenOnForFixture) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model = ViewModelProvider(activity)[BrowserViewModel::class.java]
            if (!keepCompose) view = GeckoView(activity).also { activity.setContentView(it) }
        }
        val result = checkNotNull(model)
        await("A fresh ViewModel must finish reading its isolated profile") { result.ready.value }
        if (!keepCompose) {
            onMain { result.setForeground(true) }
            await("The native viewport must be attached and measured") {
                onMain { view!!.isAttachedToWindow && view!!.width > 0 && view!!.height > 0 }
            }
        }
        return result
    }

    protected fun closeScenario() {
        val closing = scenario ?: return
        wakeForFixture()
        onMain {
            view?.releaseSession()
            scenarioActivity?.takeUnless { it.isDestroyed }?.let { activity ->
                // ActivityScenario matches lifecycle events to its original Intent.
                // Production singleTop correctly replaces activity.intent, so restore
                // only that test bookkeeping after all incoming-intent assertions.
                scenarioLaunchIntent?.let { activity.intent = Intent(it) }
                activity.finishAndRemoveTask()
            }
        }
        // Wear finishes the task asynchronously. ActivityScenario cannot close from its
        // transient STARTED stage (which has no Lifecycle.State mapping), particularly
        // after a real singleTop wake intent changed the lifecycle matcher temporarily.
        // Wait for the actual Activity destruction, then retain the strict Scenario check.
        await("The test-owned Activity must really finish before disposing its Scenario", 15_000) {
            onMain { scenarioActivity?.isDestroyed == true }
        }
        closing.close()
        assertEquals("Every test-owned Activity must be destroyed", Lifecycle.State.DESTROYED, closing.state)
        scenario = null
        scenarioActivity = null
        scenarioLaunchIntent = null
        view = null
        instrumentation.waitForIdleSync()
    }

    protected fun wakeForFixture() {
        check(emulatorVerified) { "Verify QEMU before changing screen state" }
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        device.pressKeyCode(KeyEvent.KEYCODE_WAKEUP)
        val power = checkNotNull(app.getSystemService(PowerManager::class.java))
        await("The dedicated emulator must wake before app UI work") { power.isInteractive }
    }

    protected fun createController(privateMode: Boolean = false): TabSessionController {
        val controller = onMain { TabSessionController(app, privateMode = privateMode).also { controllers.add(it) } }
        attach(controller)
        return controller
    }

    protected fun attach(controller: TabSessionController) = onMain {
        val gecko = checkNotNull(view)
        if (gecko.session !== controller.session) {
            gecko.releaseSession()
            gecko.setSession(controller.session)
        }
        controllers.forEach { it.setActive(it === controller) }
        controller.setActive(true)
        controller.onBeforeSuspend = { if (gecko.session === controller.session) gecko.releaseSession() }
        gecko.requestFocus()
    }

    protected fun selectTab(model: BrowserViewModel, controller: TabSessionController) {
        onMain {
            view!!.releaseSession()
            model.selectTab(controller.tabId)
        }
        attach(controller)
    }

    protected fun loadAndWait(controller: TabSessionController, target: String, expectedTitle: String) {
        val revision = controller.committedRevision.value
        onMain { controller.loadUri(target) }
        // loadUri may rebuild a background-suspended session.
        attach(controller)
        await("Expected a committed fixture document: $expectedTitle") {
            controller.committedUrl.value == target && controller.committedRevision.value > revision &&
                controller.title.value == expectedTitle && !controller.isLoading.value
        }
        assertNull(controller.error.value)
    }

    protected fun loginThroughRealPost(controller: TabSessionController) {
        loadAndWait(controller, "$base/forms-regression/login", "FORMS_LOGIN_READY")
        val revision = controller.committedRevision.value
        tapCentre()
        await("The fixture server must accept an actual HTML form POST") {
            controller.title.value == "FORMS_LOGIN_OK" && controller.committedRevision.value > revision &&
                !controller.isLoading.value
        }
        assertNull(controller.error.value)
    }

    protected fun tapCentre() {
        Thread.sleep(350) // The compositor may paint just after title/onPageStop.
        onMain {
            val gecko = checkNotNull(view)
            val time = SystemClock.uptimeMillis()
            val x = gecko.width / 2f
            val y = gecko.height / 2f
            fun event(action: Int, timestamp: Long) = MotionEvent.obtain(
                time, timestamp, action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
                arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = .1f }),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            event(MotionEvent.ACTION_DOWN, time).also { gecko.dispatchTouchEvent(it); it.recycle() }
            gecko.postDelayed({
                event(MotionEvent.ACTION_UP, time + 80).also { gecko.dispatchTouchEvent(it); it.recycle() }
            }, 80)
        }
    }

    protected fun clearWebsiteData() {
        val completed = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        onMain {
            GeckoRuntimeHolder.runtime(app).storageController.clearData(StorageController.ClearFlags.ALL)
                .accept({ completed.set(true) }, { failure.set(it); completed.set(true) })
        }
        await("This emulator app's Cookie cleanup must finish", 35_000) { completed.get() }
        assertNull("Website-data cleanup failed", failure.get())
    }

    protected fun readSnapshot(): BrowserSnapshot = runBlocking { BrowserRepository(app).read().snapshot }
    protected fun profileJson(): JSONObject = JSONObject(File(app.filesDir, "browser/state.json").readText())

    protected fun assertReadingRuntime(preferences: ReadingPreferences) = onMain {
        val settings = GeckoRuntimeHolder.runtime(app).settings
        assertEquals(preferences.textPercent / 100f, settings.fontSizeFactor, .001f)
        assertEquals(colorScheme(preferences), settings.preferredColorScheme)
    }

    protected fun applyReadingRuntime(preferences: ReadingPreferences) {
        val settings = GeckoRuntimeHolder.runtime(app).settings
        settings.fontSizeFactor = preferences.textPercent / 100f
        settings.preferredColorScheme = colorScheme(preferences)
    }

    private fun colorScheme(preferences: ReadingPreferences): Int = when (preferences.colorScheme) {
        PageColorScheme.SYSTEM -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        PageColorScheme.LIGHT -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        PageColorScheme.DARK -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
    }

    private fun rawHttp(target: String): String {
        val uri = URI(target)
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, if (uri.port < 0) 80 else uri.port), 3_000)
            socket.soTimeout = 3_000
            val path = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: ${uri.host}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            val response = socket.getInputStream().bufferedReader().readText()
            check(response.startsWith("HTTP/1.1 200") || response.startsWith("HTTP/1.0 200")) { "Fixture HTTP request failed" }
            response.substringAfter("\r\n\r\n")
        }
    }

    protected fun await(message: String, timeout: Long = 25_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            awaitUiPump?.invoke()
            if (condition()) return
            Thread.sleep(50)
        }
        fail(message)
    }

    protected fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

}
