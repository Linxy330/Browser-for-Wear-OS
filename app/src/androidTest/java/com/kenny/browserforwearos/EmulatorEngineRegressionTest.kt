package com.kenny.browserforwearos

import android.app.Application
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kenny.browserforwearos.data.BrowserRepository
import com.kenny.browserforwearos.data.BrowserSnapshot
import com.kenny.browserforwearos.engine.EnginePrompt
import com.kenny.browserforwearos.engine.TabSessionController
import com.kenny.browserforwearos.presentation.BrowserViewModel
import com.kenny.browserforwearos.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID

/**
 * Runs only on a verified Android emulator. Does not enable accessibility services,
 * write system settings, change power state, or install any device-wide test fixture.
 *
 * Host prerequisites: testsite/server.py on 8787 and its self-signed TLS instance on 8743,
 * with emulator-scoped reverse ports for 8787 and 8743 prepared by the test runner.
 * Optional instrumentation args: base_url / tls_url. Defaults use that loopback tunnel.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorEngineRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private lateinit var base: String
    private lateinit var tls: String
    private var emulatorVerified = false
    private var scenario: ActivityScenario<MainActivity>? = null
    private var view: GeckoView? = null
    private val controllers = mutableListOf<TabSessionController>()
    private var profileBefore: BrowserSnapshot? = null

    @Before
    fun requireEmulatorBeforeAnyMutation() {
        // The first operation touching device state is a read-only qemu property query.
        val qemu = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu"),
        ).bufferedReader().use { it.readText().trim() }
        assertEquals("Refusing to run regression tests on a physical device", "1", qemu)
        val fingerprint = Build.FINGERPRINT.lowercase()
        assertTrue(
            "Expected an emulator SDK fingerprint, got ${Build.FINGERPRINT}",
            fingerprint.contains("generic") || fingerprint.contains("emulator") ||
                (Build.PRODUCT.startsWith("sdk_") && Build.HARDWARE in setOf("ranchu", "goldfish")),
        )
        emulatorVerified = true
        val arguments = InstrumentationRegistry.getArguments()
        base = arguments.getString("base_url")?.trimEnd('/') ?: "http://127.0.0.1:8787"
        tls = arguments.getString("tls_url") ?: "https://127.0.0.1:8743/counter"
        profileBefore = runBlocking { BrowserRepository(app).read().snapshot }
        // Give an explicit setup failure instead of a misleading browser timeout.
        assertTrue("Start the HTTP regression server before running tests", rawHttp("$base/regression/metrics?token=setup").contains("ticks"))
    }

    @After
    fun releaseOnlyAppOwnedResources() {
        if (!emulatorVerified) return
        onMain {
            view?.releaseSession()
            controllers.forEach { it.close() }
            controllers.clear()
        }
        scenario?.close()
        scenario = null
        view = null
        profileBefore?.let { runBlocking { BrowserRepository(app).save(it) } }
    }

    @Test
    fun realDomTapAndRepeatedNavigationUpdateCommittedState() {
        val controller = openEngine()
        val page = "$base/regression/interaction?mode=counter"
        loadAndWait(controller, page, "REGRESSION_READY")
        tapCentre()
        await("Real page JavaScript should observe the touch") { controller.title.value == "REGRESSION_CLICK_1" }
        var revision = controller.committedRevision.value
        repeat(11) { index ->
            val target = if (index % 2 == 0) "$base/counter" else "$base/long"
            onMain { controller.loadUri(target) }
            await("Navigation ${index + 1} should commit") {
                controller.committedUrl.value == target && controller.committedRevision.value > revision
            }
            assertNull(controller.error.value)
            revision = controller.committedRevision.value
        }
        assertTrue(controller.canGoBack.value)
        onMain { controller.goBack() }
        await("Back navigation should use real engine history") {
            controller.committedUrl.value == "$base/long" && controller.committedRevision.value > revision
        }
    }

    @Test
    fun normal404CommitsButConnectionFailureDoesNotBecomeHistory() {
        val controller = openEngine()
        loadAndWait(controller, "$base/gone", "404")
        assertNull(controller.error.value)
        val committed = controller.committedUrl.value
        val revision = controller.committedRevision.value
        val host = URI(base).host
        onMain { controller.loadUri("http://$host:1/unreachable") }
        await("Connection refusal should expose a native error") { controller.error.value != null }
        assertEquals(committed, controller.committedUrl.value)
        assertEquals(revision, controller.committedRevision.value)
        assertFalse(controller.isSecure.value)
        loadAndWait(controller, "$base/counter", "计数器")
        assertNull(controller.error.value)
    }

    @Test
    fun selfSignedTlsFailsWithoutCertificateBypass() {
        assertPortAvailable(tls)
        val controller = openEngine()
        loadAndWait(controller, "$base/counter", "计数器")
        val committed = controller.committedUrl.value
        val revision = controller.committedRevision.value
        onMain { controller.loadUri(tls) }
        await("Self-signed certificate must be rejected") { controller.error.value != null }
        assertEquals(app.getString(R.string.engine_error_certificate), controller.error.value)
        assertFalse(controller.isSecure.value)
        assertEquals(committed, controller.committedUrl.value)
        assertEquals(revision, controller.committedRevision.value)
    }

    @Test
    fun promptResponsesReturnToRealDomAndNavigationDismissesPendingRequest() {
        val controller = openEngine()
        loadAndWait(controller, "$base/regression/interaction?mode=prompt", "PROMPT_READY")
        tapCentre()
        await("Page should request its native text prompt") { controller.pendingPrompt.value is EnginePrompt.Text }
        assertEquals(URI(base).host, controller.pendingPrompt.value!!.origin)
        onMain { controller.confirmText("中文确认") }
        await("Confirmed text must reach JavaScript") { controller.title.value == "PROMPT_RESULT_中文确认" }
        assertNull(controller.pendingPrompt.value)

        loadAndWait(controller, "$base/regression/interaction?mode=confirm", "CONFIRM_READY")
        tapCentre()
        await("Page should request its native confirmation") { controller.pendingPrompt.value is EnginePrompt.Confirm }
        onMain { controller.confirmButton(false) }
        await("Cancellation must return false to JavaScript") { controller.title.value == "CONFIRM_RESULT_false" }

        loadAndWait(controller, "$base/regression/interaction?mode=prompt", "PROMPT_READY")
        tapCentre()
        await("Second text request should become pending") { controller.pendingPrompt.value is EnginePrompt.Text }
        val abandoned = controller.pendingPrompt.value!!
        loadAndWait(controller, "$base/counter", "计数器")
        assertNull(controller.pendingPrompt.value)
        assertTrue("Superseded request must be completed, not leaked", abandoned.basePrompt.isComplete)
    }

    @Test
    fun gestureNewWindowCreatesSessionAndAutomaticPopupIsBlocked() {
        val controller = openEngine()
        val newWindows = mutableListOf<TabSessionController>()
        onMain {
            controller.onNewTabRequested = {
                TabSessionController(app, initiallyOpen = false).also {
                    controllers.add(it)
                    newWindows.add(it)
                }.session
            }
        }
        loadAndWait(controller, "$base/regression/interaction?mode=window", "WINDOW_READY")
        tapCentre()
        await("A user gesture should create exactly one real new session") { onMain { newWindows.size == 1 } }
        val opened = onMain { newWindows.single() }
        await("Gecko should open and load the returned session") { opened.committedUrl.value == "$base/counter" }
        assertTrue(onMain { opened.session.isOpen })
        loadAndWait(controller, "$base/regression/interaction?mode=automatic", "AUTO_POPUP_ATTEMPTED")
        Thread.sleep(500)
        assertEquals(1, onMain { newWindows.size })
        assertNotNull("Blocked automatic popup should give feedback", controller.feedback.value)
    }

    @Test
    fun hibernationClosesAttachedSessionStopsNetworkAndResumesSafeGet() {
        val controller = openEngine()
        val token = UUID.randomUUID().toString()
        val page = "$base/regression/interaction?mode=ticks&token=$token"
        loadAndWait(controller, page, "TICKS_READY")
        await("Visible JavaScript must produce real network requests") { ticks(token) >= 3 }
        onMain { controller.suspendPage() }
        assertTrue(controller.suspendedState.value)
        assertFalse(onMain { controller.session.isOpen })
        assertNull("Suspension must detach the GeckoView before native close", onMain { view!!.session })
        Thread.sleep(500) // Let requests already in flight settle before measuring.
        val stoppedAt = ticks(token)
        Thread.sleep(1_300)
        assertEquals("A closed background document must stop issuing requests", stoppedAt, ticks(token))
        onMain {
            controller.resumePage()
            controller.setActive(true)
            view!!.setSession(controller.session)
        }
        await("Safe GET recovery must restart the actual document") { ticks(token) > stoppedAt + 1 }
        assertFalse(controller.suspendedState.value)
        assertEquals(page, controller.committedUrl.value)
        assertNull(controller.error.value)
    }

    @Test
    fun threeTabLimitStableControllersAndProfileSurviveActivityRecreation() {
        val vm = openViewModel()
        onMain {
            while (vm.tabs.value.size > 1) vm.closeTab(vm.tabs.value.last().id)
            vm.closeTab(vm.tabs.value.single().id)
            vm.setForeground(true)
            vm.setWebVisible(true)
        }
        val first = onMain { vm.controller }
        loadAndWait(first, "$base/counter", "计数器")
        await("Successful navigation must enter local history") { vm.history.value.any { it.url == "$base/counter" } }
        onMain { vm.addBookmark(); assertTrue(vm.newTab()) }
        val second = onMain { vm.controller }
        loadAndWait(second, "$base/long", "长页面")
        onMain { assertTrue(vm.newTab()) }
        val third = onMain { vm.controller }
        loadAndWait(third, "$base/gone", "404")
        val ids = onMain { vm.tabs.value.map { it.id } }
        onMain {
            assertFalse("Fourth tab must leave the existing tabs intact", vm.newTab())
            assertEquals(ids, vm.tabs.value.map { it.id })
            vm.selectTab(first.tabId)
            assertSame(first, vm.controller)
            assertSame(second, vm.tabs.value[1].controller)
            assertSame(third, vm.tabs.value[2].controller)
        }
        await("Atomic profile should contain all tabs, selection, bookmark and real history") {
            val saved = runBlocking { BrowserRepository(app).read().snapshot }
            saved.tabs.map { it.id } == ids && saved.selectedTabId == first.tabId &&
                saved.tabs.mapNotNull { it.url }.containsAll(listOf("$base/counter", "$base/long", "$base/gone")) &&
                saved.bookmarks.any { it.url == "$base/counter" } && saved.history.size >= 3
        }
        scenario!!.close()
        scenario = null
        val restored = openViewModel()
        assertEquals(ids, restored.tabs.value.map { it.id })
        assertEquals(first.tabId, restored.selectedTabId.value)
        assertTrue(restored.bookmarks.value.any { it.url == "$base/counter" })
        onMain { restored.setForeground(true); restored.setWebVisible(true) }
        await("Restored selected tab should perform a real GET") { restored.controller.committedUrl.value == "$base/counter" }
    }

    private fun openEngine(): TabSessionController {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val controller = onMain { TabSessionController(app).also { controllers.add(it) } }
        scenario!!.onActivity { activity ->
            val gecko = GeckoView(activity)
            activity.setContentView(gecko)
            gecko.setSession(controller.session)
            gecko.requestFocus()
            view = gecko
            controller.onBeforeSuspend = { if (gecko.session === controller.session) gecko.releaseSession() }
            controller.setActive(true)
        }
        await("Gecko viewport should be attached and measured") { onMain { view!!.isAttachedToWindow && view!!.width > 0 && view!!.height > 0 } }
        return controller
    }

    private fun openViewModel(): BrowserViewModel {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        var selected: BrowserViewModel? = null
        scenario!!.onActivity { activity ->
            selected = ViewModelProvider(activity)[BrowserViewModel::class.java]
            activity.setContentView(FrameLayout(activity))
        }
        return selected!!.also { model -> await("Browser profile should restore") { model.ready.value } }
    }

    private fun loadAndWait(controller: TabSessionController, url: String, title: String) {
        val revision = controller.committedRevision.value
        onMain { controller.loadUri(url) }
        await("Expected loaded document title: $title") {
            controller.title.value == title && controller.committedUrl.value == url &&
                controller.committedRevision.value > revision && !controller.isLoading.value
        }
        assertNull(controller.error.value)
    }

    private fun tapCentre() {
        Thread.sleep(350) // Allow the compositor to paint after title/onPageStop delivery.
        onMain {
            val gecko = view!!
            val time = SystemClock.uptimeMillis()
            val x = gecko.width / 2f
            val y = gecko.height / 2f
            fun event(action: Int, timestamp: Long): MotionEvent = MotionEvent.obtain(
                time, timestamp, action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
                arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = .1f }),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            val down = event(MotionEvent.ACTION_DOWN, time)
            gecko.dispatchTouchEvent(down)
            down.recycle()
            gecko.postDelayed({
                val up = event(MotionEvent.ACTION_UP, time + 80)
                gecko.dispatchTouchEvent(up)
                up.recycle()
            }, 80)
        }
    }

    private fun ticks(token: String): Int = JSONObject(rawHttp("$base/regression/metrics?token=$token")).getInt("ticks")

    /** Raw HTTP is used only by the test fixture; product TLS policy remains unchanged. */
    private fun rawHttp(url: String): String {
        val uri = URI(url)
        require(uri.scheme == "http")
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, if (uri.port < 0) 80 else uri.port), 3_000)
            socket.soTimeout = 3_000
            val target = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            socket.getOutputStream().write(
                "GET $target HTTP/1.1\r\nHost: ${uri.host}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII),
            )
            val response = socket.getInputStream().bufferedReader().readText()
            check(response.startsWith("HTTP/1.1 200") || response.startsWith("HTTP/1.0 200")) { "Test fixture returned an unsuccessful response" }
            response.substringAfter("\r\n\r\n")
        }
    }

    private fun assertPortAvailable(url: String) {
        val uri = URI(url)
        Socket().use { it.connect(InetSocketAddress(uri.host, if (uri.port < 0) 443 else uri.port), 3_000) }
    }

    private fun await(message: String, timeout: Long = 25_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail(message)
    }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
