package com.kenny.browserforwearos

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kenny.browserforwearos.engine.GeckoRuntimeHolder
import com.kenny.browserforwearos.engine.TabSessionController
import com.kenny.browserforwearos.presentation.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.StorageController
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real Gecko form/IME/cookie coverage. Uses app-owned sessions and emulator-only fixtures.
 * Chinese input uses Android's InputConnection protocol; tapping a system keyboard UI is
 * a separate manual/device check. No JavaScript evaluation or DOM editing bridge is used.
 *
 * Start the updated tools/testsite/server.py on 8787 and configure emulator-only reverse.
 * Optional instrumentation argument: base_url (default http://127.0.0.1:8787).
 */
@RunWith(AndroidJUnit4::class)
class EmulatorFormsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private var scenario: ActivityScenario<MainActivity>? = null
    private var view: GeckoView? = null
    private val controllers = mutableListOf<TabSessionController>()
    private var emulatorVerified = false
    private lateinit var base: String

    @Before
    fun requireEmulatorBeforeCreatingAnySessions() {
        assertTrue(
            "Forms tests must never run on a physical device",
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"),
        )
        emulatorVerified = true
        base = InstrumentationRegistry.getArguments().getString("base_url")?.trimEnd('/')
            ?: "http://127.0.0.1:8787"
        require(URI(base).scheme == "http") { "Controlled fixture uses plain HTTP loopback; public TLS is tested separately" }
    }

    @After
    fun closeOnlyAppOwnedViewsAndSessions() {
        if (!emulatorVerified) return
        onMain {
            view?.releaseSession()
            controllers.forEach { it.session.textInput.setDelegate(null); it.close() }
            controllers.clear()
        }
        scenario?.close()
        scenario = null
        view = null
    }

    @Test
    fun postLoginCookieSurvivesReloadAndSharedProfileTabThenLogoutAndClearRevokeIt() {
        clearWebsiteData()
        val first = openEngine()
        loginThroughRealPost(first)
        loadAndWait(first, "$base/forms-regression/session", AUTHENTICATED)
        val beforeReload = first.committedRevision.value
        onMain { first.reload() }
        await("Reload must preserve the server-verified Cookie session") {
            first.committedRevision.value > beforeReload && first.title.value == AUTHENTICATED && !first.isLoading.value
        }

        val second = createAndAttachController()
        loadAndWait(second, "$base/forms-regression/session", AUTHENTICATED)
        loadAndWait(second, "$base/forms-regression/logout", "FORMS_LOGOUT_OK")
        attach(first)
        loadAndWait(first, "$base/forms-regression/session", ANONYMOUS)

        loginThroughRealPost(first)
        loadAndWait(first, "$base/forms-regression/session", AUTHENTICATED)
        attach(second)
        loadAndWait(second, "$base/forms-regression/session", AUTHENTICATED)
        clearWebsiteData()
        // loadUri reopens a suspended session directly at a GET target, without replaying POST.
        onMain { second.loadUri("$base/forms-regression/session") }
        attach(second)
        await("StorageController.clearData must make the actual server session anonymous") {
            second.title.value == ANONYMOUS && second.committedUrl.value == "$base/forms-regression/session" && !second.isLoading.value
        }
        assertNull(second.error.value)
        record("cookie_post_reload_shared_tab_logout_clear", "PASS_SERVER_VERIFIED")
    }

    @Test
    fun androidInputConnectionComposesChineseAndRealPostPreservesUrlPunctuation() {
        val controller = openEngine()
        val focused = AtomicBoolean(false)
        onMain {
            // The test itself acts as the IME client. Do not also ask the installed Wear
            // keyboard to create/restart a competing connection to the same DOM editor.
            controller.session.textInput.setDelegate(object : GeckoSession.TextInputDelegate {
                override fun restartInput(session: GeckoSession, reason: Int) {
                    if (reason == GeckoSession.TextInputDelegate.RESTART_REASON_FOCUS) focused.set(true)
                    if (reason == GeckoSession.TextInputDelegate.RESTART_REASON_BLUR) focused.set(false)
                    record("ime_restart", "reason=$reason; title=${controller.title.value}")
                }
                override fun showSoftInput(session: GeckoSession) {
                    record("ime_show_requested", "test owns InputConnection; installed keyboard not invoked")
                }
                override fun hideSoftInput(session: GeckoSession) {
                    record("ime_hide_requested", "title=${controller.title.value}")
                }
            })
        }
        loadAndWait(controller, "$base/forms-regression/ime", "FORMS_IME_READY")
        tapCentre()
        await("A real DOM tap must produce native TextInputDelegate focus") { focused.get() }
        var connection: InputConnection? = null
        var editorInfo: EditorInfo? = null
        await("A focused real DOM editor must expose Android InputConnection") {
            onMain {
                val info = EditorInfo()
                val candidate = view!!.onCreateInputConnection(info)
                if (candidate != null && info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
                    connection = candidate
                    editorInfo = info
                    true
                } else false
            }
        }
        val editor = checkNotNull(connection)
        // GeckoInputConnection.getHandler() registers its editable thread. Calling edit
        // methods on UI unconditionally can return true while getEditable() is null.
        val inputHandler = onMain { editor.handler ?: Handler(Looper.getMainLooper()) }
        val info = checkNotNull(editorInfo)
        record("ime_connection", "class=${editor.javaClass.name}; inputType=${info.inputType}; " +
            "imeOptions=${info.imeOptions}; initialSelection=${info.initialSelStart}:${info.initialSelEnd}; " +
            "handlerThread=${inputHandler.looper.thread.name}; uiThread=${Looper.getMainLooper().thread.name}; " +
            "keyboard=${app.resources.configuration.keyboard}; title=${controller.title.value}")
        await("The registered InputConnection thread must expose the focused native editable") {
            onHandler(inputHandler) { editor.getTextBeforeCursor(64, 0) != null }
        }
        val firstComposing = imeEdit(editor, inputHandler) { editor.setComposingText("中", 1) }
        record("ime_set_composing_first", "returned=$firstComposing; ${imeSnapshot(editor, inputHandler)}; title=${controller.title.value}")
        assertTrue(firstComposing)
        awaitImeTitle("DOM input event must observe a real composing span", controller, editor, inputHandler, "FORMS_COMPOSING_中")
        val updatedComposing = imeEdit(editor, inputHandler) { editor.setComposingText("中文测", 1) }
        record("ime_set_composing_update", "returned=$updatedComposing; ${imeSnapshot(editor, inputHandler)}; title=${controller.title.value}")
        assertTrue(updatedComposing)
        awaitImeTitle("Updating composition must replace the previous composing text", controller, editor, inputHandler, "FORMS_COMPOSING_中文测")
        val submitted = "中文测试./?&"
        val committed = imeEdit(editor, inputHandler) { editor.commitText(submitted, 1) }
        record("ime_commit", "returned=$committed; ${imeSnapshot(editor, inputHandler)}; title=${controller.title.value}")
        assertTrue(committed)
        awaitImeTitle("Committing through Android IME must reach the live DOM input", controller, editor, inputHandler, "FORMS_COMMITTED_$submitted")
        onHandler(inputHandler) { editor.performEditorAction(EditorInfo.IME_ACTION_GO) }
        if (!waitFor(3_000) { controller.title.value == "FORMS_POSTED_$submitted" }) {
            // Some Wear IMEs expose DONE rather than GO; the real HTML submit button is
            // full-viewport after commit, so a touch submits without a DOM scripting bridge.
            onMain {
                val gecko = view!!
                (gecko.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(gecko.windowToken, 0)
            }
            tapCentre()
        }
        await("The HTTP server must echo the exact POSTed Chinese and ./?& punctuation") {
            controller.title.value == "FORMS_POSTED_$submitted" &&
                controller.committedUrl.value == "$base/forms-regression/ime" && !controller.isLoading.value
        }
        assertNull(controller.error.value)
        record("chinese_composition_post", "PASS_INPUT_CONNECTION_AND_SERVER_ECHO; keyboard UI not exercised")
    }

    @Test
    fun publicHttpsAttemptsReportVerifiedTlsOrBoundedExternalFailureForThreeSites() {
        val first = openEngine()
        var successes = 0
        val targets = listOf("https://example.com/", "https://www.wikipedia.org/", "https://www.bing.com/")
        targets.forEachIndexed { index, target ->
            val controller = if (index == 0) first else createAndAttachController()
            val revision = controller.committedRevision.value
            onMain { controller.loadUri(target) }
            val finished = waitFor(35_000) {
                controller.committedRevision.value > revision || controller.error.value != null
            }
            val host = URI(target).host
            when {
                !finished -> {
                    onMain { controller.stop() }
                    assertFalse("An external timeout must be cancellable", controller.isLoading.value)
                    record("public_https_$host", "EXTERNAL_TIMEOUT_STOPPED; network reachability unverified")
                }
                controller.error.value != null -> {
                    assertFalse(controller.isLoading.value)
                    assertFalse(controller.isSecure.value)
                    assertTrue("Failure must expose a readable engine error", !controller.error.value.isNullOrBlank())
                    record("public_https_$host", "ENGINE_ERROR; tls=false; ${controller.error.value}")
                }
                else -> {
                    await("Successful HTTPS must have engine-verified TLS", 5_000) { controller.isSecure.value }
                    assertTrue(controller.committedUrl.value.startsWith("https://"))
                    assertTrue("A real public document must supply a title", controller.title.value.isNotBlank())
                    successes++
                    record("public_https_$host", "PASS_VERIFIED_TLS; final_host=${URI(controller.committedUrl.value).host}")
                }
            }
            // New sessions isolate late callbacks after stopping a slow external target.
            onMain { controller.close() }
        }
        record("public_https_total", "$successes/3 verified TLS; remaining outcomes recorded individually")
        // An unavailable external network is reported as a skipped reachability check, not a
        // fabricated product pass or a product regression attributed to the browser.
        assumeTrue("No public HTTPS page was reachable; inspect recorded external outcomes", successes > 0)
    }

    private fun loginThroughRealPost(controller: TabSessionController) {
        loadAndWait(controller, "$base/forms-regression/login", "FORMS_LOGIN_READY")
        val revision = controller.committedRevision.value
        tapCentre()
        await("Server must accept the actual HTML form POST") {
            controller.title.value == "FORMS_LOGIN_OK" && controller.committedRevision.value > revision && !controller.isLoading.value
        }
        assertNull(controller.error.value)
    }

    private fun clearWebsiteData() {
        val completed = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>(null)
        onMain {
            controllers.forEach { it.suspendPage() }
            GeckoRuntimeHolder.runtime(app).storageController.clearData(StorageController.ClearFlags.ALL)
                .accept({ completed.set(true) }, { failure -> error.set(failure); completed.set(true) })
        }
        await("Gecko website-data clearing must finish", 35_000) { completed.get() }
        assertNull("Gecko website-data clearing failed", error.get())
    }

    private fun openEngine(): TabSessionController {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity ->
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            view = GeckoView(activity).also { activity.setContentView(it) }
        }
        return createAndAttachController().also {
            await("Gecko viewport must be attached and measured") {
                onMain { view!!.isAttachedToWindow && view!!.width > 0 && view!!.height > 0 }
            }
        }
    }

    private fun createAndAttachController(): TabSessionController {
        val controller = onMain { TabSessionController(app).also { controllers.add(it) } }
        attach(controller)
        return controller
    }

    private fun attach(controller: TabSessionController) = onMain {
        val gecko = checkNotNull(view)
        if (gecko.session !== controller.session) {
            gecko.releaseSession()
            gecko.setSession(controller.session)
        }
        controllers.forEach { it.setActive(it === controller) }
        controller.onBeforeSuspend = { if (gecko.session === controller.session) gecko.releaseSession() }
        gecko.requestFocus()
    }

    private fun loadAndWait(controller: TabSessionController, url: String, expectedTitle: String) {
        val revision = controller.committedRevision.value
        onMain { controller.loadUri(url) }
        await("Expected server fixture response: $expectedTitle; restart updated fixture if missing") {
            controller.committedUrl.value == url && controller.committedRevision.value > revision &&
                controller.title.value == expectedTitle && !controller.isLoading.value
        }
        assertNull(controller.error.value)
    }

    private fun tapCentre() {
        Thread.sleep(350)
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

    private fun record(check: String, result: String) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "[forms-regression] $check: $result\n")
            putString("forms_check", check)
            putString("forms_result", result)
        })
    }

    /** Logs only the fixed non-sensitive text from the dedicated IME fixture. */
    private fun imeSnapshot(editor: InputConnection, handler: Handler): String = onHandler(handler) {
        "before=${editor.getTextBeforeCursor(64, 0)}; selected=${editor.getSelectedText(0)}; after=${editor.getTextAfterCursor(64, 0)}"
    }

    private fun imeEdit(editor: InputConnection, handler: Handler, action: () -> Boolean): Boolean = onHandler(handler) {
        editor.beginBatchEdit()
        try {
            action()
        } finally {
            editor.endBatchEdit()
        }
    }

    private fun awaitImeTitle(message: String, controller: TabSessionController, editor: InputConnection, handler: Handler, expected: String) {
        var lastTitle: String? = null
        val found = waitFor(25_000) {
            val title = controller.title.value
            if (title != lastTitle) {
                lastTitle = title
                record("ime_dom_title", title)
            }
            title == expected
        }
        if (!found) {
            val snapshot = imeSnapshot(editor, handler)
            record("ime_failure", "expected=$expected; actual=${controller.title.value}; $snapshot; error=${controller.error.value}")
            fail("$message; actual title=${controller.title.value}; $snapshot")
        }
    }

    private fun <T> onHandler(handler: Handler, action: () -> T): T {
        if (Looper.myLooper() == handler.looper) return action()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<T>?>(null)
        check(handler.post {
            result.set(runCatching(action))
            latch.countDown()
        }) { "InputConnection handler rejected work" }
        check(latch.await(5, TimeUnit.SECONDS)) { "InputConnection handler did not complete within 5 seconds" }
        return checkNotNull(result.get()).getOrThrow()
    }

    private fun await(message: String, timeout: Long = 25_000, condition: () -> Boolean) {
        if (!waitFor(timeout, condition)) fail(message)
    }

    private fun waitFor(timeout: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    companion object {
        private const val AUTHENTICATED = "FORMS_SESSION_AUTHENTICATED"
        private const val ANONYMOUS = "FORMS_SESSION_ANONYMOUS"
    }
}
