package com.kenny.browserforwearos

import android.app.Application
import android.content.ClipboardManager
import android.graphics.Matrix
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kenny.browserforwearos.data.BrowserRepository
import com.kenny.browserforwearos.data.BrowserSnapshot
import com.kenny.browserforwearos.engine.PageTools
import com.kenny.browserforwearos.engine.TabSessionController
import com.kenny.browserforwearos.presentation.MainActivity
import com.kenny.browserforwearos.presentation.PageZoomController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.min

/** Native input and content tests. Requires /page-tools on the emulator HTTP reverse. */
@RunWith(AndroidJUnit4::class)
class EmulatorPageToolsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private lateinit var base: String
    private var verifiedEmulator = false
    private var scenario: ActivityScenario<MainActivity>? = null
    private var controller: TabSessionController? = null
    private var view: GeckoView? = null
    private var zoom: PageZoomController? = null
    private var profileBefore: BrowserSnapshot? = null

    @Before
    fun verifyEmulatorBeforeAnyAppMutation() {
        // Read-only device check is the first device operation in every test.
        val qemu = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu"),
        ).bufferedReader().use { it.readText().trim() }
        assertEquals("Page tools regression is restricted to emulators", "1", qemu)
        val fingerprint = Build.FINGERPRINT.lowercase()
        assertTrue(
            fingerprint.contains("generic") || fingerprint.contains("emulator") ||
                (Build.PRODUCT.startsWith("sdk_") && Build.HARDWARE in setOf("ranchu", "goldfish")),
        )
        verifiedEmulator = true
        base = InstrumentationRegistry.getArguments().getString("base_url")?.trimEnd('/') ?: "http://127.0.0.1:8787"
        profileBefore = runBlocking { BrowserRepository(app).read().snapshot }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity ->
            // This class verifies input/content, not power behavior. This window-only flag
            // prevents natural ambient mode from interrupting a long-press or native copy.
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val engine = TabSessionController(app)
            val gecko = GeckoView(activity)
            activity.setContentView(gecko)
            gecko.setSession(engine.session)
            gecko.requestFocus()
            engine.onBeforeSuspend = { if (gecko.session === engine.session) gecko.releaseSession() }
            engine.setActive(true)
            controller = engine
            view = gecko
        }
        await("Native viewport must attach") { onMain { view!!.isAttachedToWindow && view!!.width > 0 && view!!.height > 0 } }
    }

    @After
    fun releaseAppResources() {
        if (!verifiedEmulator) return
        onMain {
            zoom?.close()
            view?.setOnTouchListener(null)
            view?.releaseSession()
            controller?.close()
        }
        scenario?.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        scenario?.close()
        profileBefore?.let { runBlocking { BrowserRepository(app).save(it) } }
    }

    @Test
    fun finderUsesActualDocumentMatchesAndClearsNativeSearch() {
        val engine = controller!!
        val tools = engine.pageTools
        load("$base/page-tools?mode=article", "PAGE_TOOLS_ARTICLE")
        onMain { tools.find("ExactNeedle") }
        await("The native finder must report all three matches") { !tools.findState.value.pending && tools.findState.value.total == 3 }
        assertTrue(tools.findState.value.found)
        val first = tools.findState.value.current
        assertTrue(first in 1..3)
        onMain { tools.nextMatch() }
        await("Next must advance the real native match") { !tools.findState.value.pending && tools.findState.value.current != first }
        onMain { tools.nextMatch(backward = true) }
        await("Previous must return to the first match") { !tools.findState.value.pending && tools.findState.value.current == first }
        onMain { tools.find("ThereIsNoSuchTextInThisArticle") }
        await("Missing text must complete with no match") { !tools.findState.value.pending && !tools.findState.value.found && tools.findState.value.total == 0 }
        assertNull(tools.findState.value.error)
        onMain { tools.clearFind() }
        assertEquals(PageTools.FindState(), tools.findState.value)
        // The next lookup starts a new search, rather than advancing a retained old query.
        onMain { tools.find("ExactNeedle") }
        await("Finder must work after clear") { !tools.findState.value.pending && tools.findState.value.total == 3 && tools.findState.value.found }
    }

    @Test
    fun readableTextSurvivesHibernationAndNavigationInvalidatesIt() {
        val engine = controller!!
        val tools = engine.pageTools
        load("$base/page-tools?mode=article", "PAGE_TOOLS_ARTICLE")
        onMain { tools.readArticle() }
        await("Native Readability must finish with prose, failure=${tools.readerState.value.failure}", 20_000) { !tools.readerState.value.busy }
        val article = tools.readerState.value.article
        assertNotNull("Readability extraction failed: ${tools.readerState.value.failure}", article)
        assertTrue(article!!.text.contains("quiet coastal walk"))
        assertTrue(article.text.contains("ExactNeedle"))
        assertFalse("Reader must expose text rather than executable HTML", article.text.contains("<p>"))
        onMain { engine.suspendPage() }
        assertFalse(onMain { engine.session.isOpen })
        assertEquals("A native reader snapshot must remain readable while its webpage sleeps", article, tools.readerState.value.article)
        onMain { engine.loadUri("$base/counter") }
        await("A new document must commit") { engine.committedUrl.value == "$base/counter" && !engine.isLoading.value }
        assertNull("Reader content must never leak into a different document", tools.readerState.value.article)
    }

    @Test
    fun actualManualPinchResetsToMeasuredInitialScale() {
        val engine = controller!!
        val helper = onMain {
            engine.zoom.also {
                zoom = it
                it.attach(view!!, engine.session)
                view!!.setOnTouchListener { _, event -> it.observeTouch(event); false }
            }
        }
        load("$base/page-tools?mode=article", "PAGE_TOOLS_ARTICLE")
        await("An unpinched document needs a stable native baseline") { helper.state.value.baselineScale != null }
        val baseline = helper.state.value.baselineScale!!
        manualPinch()
        await("Real two-finger input must change native viewport zoom") { onMain { actualScale(engine.session) > baseline * 1.15f } }
        val result = AtomicReference<PageZoomController.Result>()
        onMain { helper.resetToFit().accept({ result.set(it) }, { fail("Reset result failed: ${it?.javaClass?.simpleName}") }) }
        await("Measured reset must complete", 20_000) { result.get() != null }
        assertEquals(result.get().toString(), PageZoomController.Outcome.APPLIED, result.get().outcome)
        val actual = onMain { actualScale(engine.session) }
        assertTrue("Expected scale=$baseline, actual=$actual", abs(actual / baseline - 1f) <= PageZoomController.RELATIVE_TOLERANCE)
    }

    @Test
    fun firstTouchBeforeStableNativeBaselineRemainsExplicitlyUnavailable() {
        val engine = controller!!
        val target = "$base/page-tools?mode=article&document=early-touch"
        val firstTouch = AtomicReference<Long>()
        val helper = onMain {
            engine.zoom.also { helper ->
                zoom = helper
                helper.attach(view!!, engine.session)
                view!!.setOnTouchListener { _, event -> helper.observeTouch(event); false }
                val delegate = checkNotNull(engine.session.contentDelegate)
                engine.session.contentDelegate = object : ForwardingContentDelegate(delegate) {
                    override fun onFirstContentfulPaint(session: GeckoSession) {
                        delegate.onFirstContentfulPaint(session)
                        if (engine.url.value != target || firstTouch.get() != null) return
                        assertNull("No stable callback can run inside this same main-thread turn", helper.state.value.baselineScale)
                        val downTime = SystemClock.uptimeMillis()
                        val side = min(view!!.width, view!!.height).toFloat()
                        touch(downTime, MotionEvent.ACTION_DOWN, 1, side * .10f)
                        assertNull("Early DOWN must never adopt Gecko's uninitialized identity matrix", helper.state.value.baselineScale)
                        assertEquals(PageZoomController.Outcome.NO_BASELINE, helper.state.value.lastOutcome)
                        firstTouch.set(downTime)
                    }
                }
            }
        }
        onMain { engine.loadUri(target) }
        await("First native content paint must receive the early real touch") { firstTouch.get() != null }
        // Wait for a real compositor measurement while the single finger remains
        // down; density/default-matrix changes cannot count as proof of a pinch.
        await("Compositor must expose a real scale after the early touch") { helper.state.value.actualScale != null }
        val beforePinch = onMain { actualScale(engine.session) }
        manualPinch(firstTouch.get())
        await("The early two-finger gesture must actually magnify the measured page") { onMain { actualScale(engine.session) > beforePinch * 1.15f } }
        Thread.sleep(1000)
        assertNull("Later native frames must never adopt the pinched scale as an initial baseline", helper.state.value.baselineScale)
        val result = AtomicReference<PageZoomController.Result>()
        onMain { helper.resetToFit().accept({ result.set(it) }, { fail("Early-touch reset failed: ${it?.javaClass?.simpleName}") }) }
        await("Unavailable early-touch reset must complete") { result.get() != null }
        assertEquals(result.get().toString(), PageZoomController.Outcome.NO_BASELINE, result.get().outcome)
        assertNull("An unknown original scale must have no invented reset target", result.get().targetScale)
        assertFalse(helper.state.value.busy)
    }

    @Test
    fun nativeContextAndSelectionActionsExpireAcrossNavigation() {
        val engine = controller!!
        val tools = engine.pageTools
        load("$base/page-tools?mode=link", "PAGE_TOOLS_LINK")
        longPressCentre()
        await("A native link long-press must produce a safe context target") { tools.contextState.value?.linkUrl == "$base/counter" }
        assertEquals("NativeContextLink", tools.contextState.value!!.linkText)
        onMain { assertTrue(tools.copyContextLink("Link")) }
        assertEquals("$base/counter", onMain { app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString() })
        longPressCentre()
        await("A second context target must become available") { tools.contextState.value != null }
        load("$base/page-tools?mode=selection", "PAGE_TOOLS_SELECTION")
        assertNull(tools.contextState.value)
        onMain { assertFalse("Old link actions must be invalid after navigation", tools.copyContextLink("Link")) }
        longPressCentre()
        await("Native text selection must expose available actions") { tools.selectionState.value?.canCopy == true }
        assertFalse(tools.selectionState.value!!.canCut)
        assertFalse(tools.selectionState.value!!.editable)
        onMain { assertFalse("Read-only text must not offer a cut action", tools.performSelection(PageTools.SelectionAction.CUT)) }
        onMain { assertTrue(tools.performSelection(PageTools.SelectionAction.COPY)) }
        await("Native copy must reach Android clipboard") {
            onMain { app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()?.contains("SelectableNativeText") == true }
        }
        // Gecko may keep the previous DOM range after hide/copy, so pressing the same
        // range need not emit another onShowActionRequest. Use a genuinely new document.
        load("$base/page-tools?mode=selection&document=fresh-selection", "PAGE_TOOLS_SELECTION")
        assertNull(tools.selectionState.value)
        longPressCentre()
        await("Selection must be available before testing its invalidation") { tools.selectionState.value?.canCopy == true }
        load("$base/counter", "计数器")
        assertNull(tools.selectionState.value)
        onMain { assertFalse("Old native selection token must not execute in a new document", tools.performSelection(PageTools.SelectionAction.COPY)) }
    }

    private fun load(url: String, title: String) {
        val engine = controller!!
        val revision = engine.committedRevision.value
        onMain { engine.loadUri(url) }
        await("Expected $title with successful native commit") {
            engine.committedRevision.value > revision && engine.committedUrl.value == url && engine.title.value == title && !engine.isLoading.value
        }
        assertNull(engine.error.value)
        Thread.sleep(250)
    }

    private fun manualPinch(existingDownTime: Long? = null) {
        val downTime = existingDownTime ?: SystemClock.uptimeMillis()
        val side = onMain { min(view!!.width, view!!.height).toFloat() }
        if (existingDownTime == null) onMain { touch(downTime, MotionEvent.ACTION_DOWN, 1, side * .10f) }
        Thread.sleep(20)
        onMain { touch(downTime, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, side * .10f) }
        repeat(20) { step ->
            Thread.sleep(20)
            onMain { touch(downTime, MotionEvent.ACTION_MOVE, 2, side * (.10f + .22f * (step + 1) / 20f)) }
        }
        onMain { touch(downTime, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, side * .32f) }
        Thread.sleep(20)
        onMain { touch(downTime, MotionEvent.ACTION_UP, 1, side * .32f) }
        Thread.sleep(350)
    }

    private fun longPressCentre() {
        Thread.sleep(250)
        val downTime = SystemClock.uptimeMillis()
        onMain { touch(downTime, MotionEvent.ACTION_DOWN, 1, 0f) }
        Thread.sleep(900)
        onMain { touch(downTime, MotionEvent.ACTION_UP, 1, 0f) }
    }

    private fun touch(downTime: Long, action: Int, count: Int, radius: Float) {
        val gecko = view!!
        val properties = Array(count) { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(count) { index -> MotionEvent.PointerCoords().apply {
            x = gecko.width / 2f + if (index == 0) -radius else radius
            y = gecko.height / 2f
            pressure = 1f
            size = .1f
        } }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { gecko.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun actualScale(session: GeckoSession): Float {
        val matrix = Matrix()
        session.getClientToSurfaceMatrix(matrix)
        val values = FloatArray(9)
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun await(message: String, timeout: Long = 25_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(40)
        }
        fail("$message; title=${controller?.title?.value}, error=${controller?.error?.value}, reader=${controller?.pageTools?.readerState?.value?.failure}")
    }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
