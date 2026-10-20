package com.kenny.browserforwearos

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.uiautomator.UiDevice
import com.kenny.browserforwearos.engine.PageTools
import com.kenny.browserforwearos.engine.TabSessionController
import com.kenny.browserforwearos.presentation.BrowserViewModel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.util.UUID

/** Chinese product review through the production Compose UI and native Gecko engine. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37, maxSdkVersion = 37)
class EmulatorProductUiReviewTest : EmulatorProductFixture() {
    @get:Rule val compose = createEmptyComposeRule()
    private val device by lazy { UiDevice.getInstance(instrumentation) }
    private var previousLocales: LocaleList? = null
    private lateinit var model: BrowserViewModel
    private var paintBarrier: NativePaintBarrier? = null
    private var paintSession: GeckoSession? = null

    @Before
    fun openRealChineseProductUi() {
        assumeTrue("This visual review is restricted to the dedicated API 37 emulator", Build.VERSION.SDK_INT == 37)
        assertTrue("UI review refuses physical devices", Build.HARDWARE in setOf("ranchu", "goldfish"))
        awaitUiPump = { compose.mainClock.advanceTimeByFrame() }
        onMain {
            val locales = checkNotNull(app.getSystemService(LocaleManager::class.java))
            previousLocales = locales.applicationLocales
            locales.applicationLocales = LocaleList.forLanguageTags("zh-Hans")
        }
        instrumentation.waitForIdleSync()
        model = openModel(keepCompose = true)
        await("The actual Activity must use the requested Chinese app locale") {
            onMain { scenarioActivity?.resources?.configuration?.locales?.get(0)?.language == "zh" }
        }
        action(R.string.b1_open_web).assertIsDisplayed()
    }

    @After
    fun closeChineseActivityBeforeRestoringAppLocale() {
        try {
            closeScenario()
        } finally {
            onMain { paintBarrier?.close(); paintBarrier = null; paintSession = null }
            previousLocales?.let { saved ->
                onMain { checkNotNull(app.getSystemService(LocaleManager::class.java)).applicationLocales = saved }
            }
            previousLocales = null
            awaitUiPump = null
        }
        // The superclass then drains the writer and restores its original metadata profile.
    }

    @Test
    fun chineseHomeFindStaysAlivePastGraceThenReaderExtractsAndReturns() {
        screenshot("home")
        val target = "$base/page-tools?mode=article"
        clickAction(R.string.b1_open_web)
        enterAndSubmit(target)
        val controller = model.controller
        waitPage(controller, target, "PAGE_TOOLS_ARTICLE")
        screenshot("web")

        openMenu()
        clickAction(R.string.find_in_page)
        scrollTo(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement("ExactNeedle")
        val session = onMain { controller.session }
        val revision = controller.committedRevision.value
        // Handler hibernation uses real time. Advancing Compose's test clock alone
        // would not exercise the former 30-second Find-screen suspension bug.
        val deadline = SystemClock.elapsedRealtime() + 31_500
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.mainClock.advanceTimeByFrame()
            onMain {
                assertSame("Find must retain the original native session", session, controller.session)
                assertTrue("The native page must remain open throughout Find", session.isOpen)
                assertFalse("Find is a live page tool, including after the menu grace period", controller.suspended)
            }
            Thread.sleep(100)
        }
        assertEquals("Waiting in Find must not replace/reload its document", revision, controller.committedRevision.value)
        screenshot("find")
        clickAction(R.string.find_next)
        await("The actual Gecko finder must find all three fixture occurrences") {
            val result = controller.pageTools.findState.value
            result.query == "ExactNeedle" && !result.busy && result.found && !result.failed && result.total == 3
        }
        action(R.string.tools).assertIsDisplayed() // Submission returns to the real page.
        assertEquals(revision, controller.committedRevision.value)

        openMenu()
        recordReader(controller, "before-reader")
        clickAction(R.string.reader_mode)
        try {
            var nextDiagnostic = 0L
            await("Reader UI must obtain a real native article extraction") {
                val state = controller.pageTools.readerState.value
                if (SystemClock.elapsedRealtime() >= nextDiagnostic) {
                    recordReader(controller, "await-reader")
                    nextDiagnostic = SystemClock.elapsedRealtime() + 1_000
                }
                if (state.status == PageTools.ReaderState.Status.FAILED) {
                    fail("Native Reader failed: ${recordReader(controller, "reader-failed")}")
                }
                state.status == PageTools.ReaderState.Status.READY
            }
        } finally {
            recordReader(controller, "reader-await-finished")
            if (controller.pageTools.readerState.value.status != PageTools.ReaderState.Status.READY) screenshot("reader-failure")
        }
        val article = checkNotNull(controller.pageTools.readerState.value.article)
        assertEquals(target, article.url)
        assertTrue(article.text.contains("ExactNeedle"))
        assertTrue(article.text.contains("A small watch can provide a useful reading experience"))
        assertFalse("Navigation decoration must not become article prose", article.text.contains("Navigation decoration outside"))
        val prose = hasText("A small watch can provide a useful reading experience", substring = true)
        scrollTo(prose)
        compose.onAllNodes(prose)[0].assertIsDisplayed()
        screenshot("reader")
        clickAction(R.string.back_to_page)
        await("Leaving Reader must release its extracted in-memory article") {
            controller.pageTools.readerState.value.status == PageTools.ReaderState.Status.IDLE
        }
        waitPage(controller, target, "PAGE_TOOLS_ARTICLE")
        action(R.string.tools).assertIsDisplayed()
    }

    @Test
    fun chineseDesktopPrivateSettingsAndBundledNoticesUseRealProductRoutes() {
        val run = UUID.randomUUID().toString()
        val normalUrl = "$base/product/client?review=$run"
        clickAction(R.string.b1_open_web)
        enterAndSubmit(normalUrl)
        val normal = model.controller
        waitPage(normal, normalUrl, "PRODUCT_CLIENT_MOBILE")
        var revision = normal.committedRevision.value

        openMenu()
        clickAction(R.string.desktop_site)
        waitPage(normal, normalUrl, "PRODUCT_CLIENT_DESKTOP", revision)
        assertTrue(normal.desktopMode.value)
        screenshot("desktop")
        revision = normal.committedRevision.value
        openMenu()
        clickAction(R.string.mobile_site)
        waitPage(normal, normalUrl, "PRODUCT_CLIENT_MOBILE", revision)
        assertFalse(normal.desktopMode.value)
        await("The real normal-mode document must enter navigation history") { model.history.value.any { it.url == normalUrl } }

        openMenu()
        clickAction(R.string.tabs)
        clickAction(R.string.new_private_tab)
        val privateUrl = "$base/product/client?private_review=$run"
        enterAndSubmit(privateUrl)
        val privateController = model.controller
        assertNotSame(normal, privateController)
        assertTrue("The actual UI action must create a Gecko private profile", privateController.privateMode)
        waitPage(privateController, privateUrl, "PRODUCT_CLIENT_MOBILE")
        assertEquals(2, model.tabs.value.size)
        assertFalse(model.history.value.any { it.url == privateUrl })
        val saved = readSnapshot() // Waits for every already-registered profile write.
        assertFalse(saved.tabs.any { it.id == privateController.tabId || it.url == privateUrl })
        assertFalse(saved.history.any { it.url == privateUrl })
        assertFalse("Private addresses must not occur anywhere in the on-disk profile", profileJson().toString().contains(privateUrl))
        screenshot("private-web")

        val historyBeforeNotices = model.history.value.toList()
        val tabsBeforeNotices = model.tabs.value.map { it.id }
        val nativeBeforeNotices = onMain { privateController.session }
        val noticeRevision = privateController.committedRevision.value
        openMenu()
        clickAction(R.string.settings)
        action(R.string.about_browser).assertIsDisplayed()
        screenshot("settings")
        clickAction(R.string.about_browser)
        action(R.string.about_licenses).assertIsDisplayed()
        screenshot("about")
        clickAction(R.string.about_licenses)

        // Read labels/body from the shipped assets, then reach them through the UI.
        // No Gecko about: URL or synthetic replacement screen is used for licenses.
        val catalog = assetJson("notices/catalog.json").getJSONArray("groups")
        val gecko = (0 until catalog.length()).map(catalog::getJSONObject).single { it.getString("id") == "gecko" }
        val groupTitle = gecko.getString("titleZh")
        await("Bundled offline notice groups must finish loading") {
            // The first group can start below the tiny circular viewport. Search
            // by scrolling the real LazyList, including while assets are loading.
            val matcher = hasText(groupTitle) and hasClickAction()
            try {
                scrollTo(matcher)
                compose.onNode(matcher).assertIsDisplayed()
                true
            } catch (_: AssertionError) { false }
        }
        clickTextAction(groupTitle)
        val documents = assetJson(gecko.getString("asset")).getJSONArray("documents")
        val license = (0 until documents.length()).map(documents::getJSONObject)
            .single { it.getString("title") == "Mozilla Public License 2.0" }
        clickTextAction(license.getString("title"))
        val body = license.getJSONArray("paragraphs").getString(0).take(1200)
        assertTrue("A full offline license must contain substantial original text", license.getJSONArray("paragraphs").getString(0).length > 5_000)
        scrollTo(hasText(body))
        compose.onNode(hasText(body)).assertIsDisplayed()
        screenshot("offline-license")

        assertEquals("Opening native settings/notices must not create browser tabs", tabsBeforeNotices, model.tabs.value.map { it.id })
        assertSame("Offline licenses must not navigate a separate native web session", nativeBeforeNotices, onMain { privateController.session })
        assertEquals(noticeRevision, privateController.committedRevision.value)
        assertEquals(privateUrl, privateController.committedUrl.value)
        assertEquals("Settings and offline licenses must not write history", historyBeforeNotices, model.history.value)
        assertEquals(historyBeforeNotices, readSnapshot().history)
        assertFalse(profileJson().toString().contains(privateUrl))
    }

    private fun assetJson(path: String): JSONObject =
        JSONObject(app.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() })

    private fun recordReader(controller: TabSessionController, phase: String): String {
        val details = onMain {
            val activity = scenarioActivity
            val viewport = activity?.window?.decorView?.let(::findGecko)
            val state = controller.pageTools.readerState.value
            // Read this app-owned field for diagnostics without changing public
            // production API or substituting a lifecycle/engine getter result.
            val controllerActive = runCatching {
                TabSessionController::class.java.getDeclaredField("active").apply { isAccessible = true }.getBoolean(controller)
            }.getOrNull()
            JSONObject().put("phase", phase).put("status", state.status.name)
                .put("failure", state.failure?.name ?: JSONObject.NULL)
                .put("controllerActive", controllerActive ?: JSONObject.NULL)
                .put("nativeOpen", controller.session.isOpen).put("suspended", controller.suspended)
                .put("loading", controller.isLoading.value).put("revision", controller.committedRevision.value)
                .put("activityState", activity?.lifecycle?.currentState?.name ?: "none")
                .put("windowFocus", activity?.hasWindowFocus() ?: false)
                .put("interactive", app.getSystemService(PowerManager::class.java)?.isInteractive ?: false)
                .put("viewportAttached", viewport?.isAttachedToWindow ?: false)
                .put("viewportSameSession", viewport?.session === controller.session)
                .put("elapsedRealtimeMs", SystemClock.elapsedRealtime()).toString()
        }
        Log.i("EmulatorReaderUi", details)
        return details
    }

    private fun label(resource: Int): String = onMain { checkNotNull(scenarioActivity).getString(resource) }

    private fun action(resource: Int): SemanticsNodeInteraction {
        val matcher = hasText(label(resource)) and hasClickAction()
        scrollTo(matcher)
        return compose.onNode(matcher)
    }

    private fun clickAction(resource: Int) = action(resource).performClick()

    private fun clickTextAction(text: String) {
        val matcher = hasText(text) and hasClickAction()
        scrollTo(matcher)
        compose.onNode(matcher).performClick()
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        if (compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasScrollAction()).performScrollToNode(matcher)
        }
    }

    private fun enterAndSubmit(value: String) {
        scrollTo(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement(value)
        // Observe real paint in the same Main turn as the actual address action,
        // so a fast title/commit cannot produce a clear-color screenshot/menu tap.
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.OnImeAction) { action ->
            assertTrue("The actual address action must accept navigation", action())
            paintBarrier?.close()
            paintSession = model.controller.session
            paintBarrier = NativePaintBarrier(checkNotNull(paintSession), value)
        }
    }

    private fun openMenu() {
        clickAction(R.string.tools)
        action(R.string.back_to_page).assertIsDisplayed()
    }

    private fun waitPage(controller: TabSessionController, url: String, title: String, afterRevision: Long = 0L) {
        await("The real fixture must commit $title at the requested URL") {
            controller.committedUrl.value == url && controller.title.value == title &&
                controller.committedRevision.value > afterRevision && !controller.isLoading.value
        }
        assertNull(controller.error.value)
        await("The production Compose WebScreen must attach the correct native session") {
            onMain { findGecko(checkNotNull(scenarioActivity).window.decorView)?.session === controller.session }
        }
        if (onMain { paintSession === controller.session }) {
            await("The actual page must paint and complete native pixel readback before screenshot or menu input") {
                onMain { checkNotNull(paintBarrier).isReadyForInput(findGecko(checkNotNull(scenarioActivity).window.decorView)) }
            }
        }
    }

    private fun findGecko(view: View): GeckoView? {
        if (view is GeckoView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findGecko(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun screenshot(suffix: String) {
        compose.waitForIdle()
        Thread.sleep(350) // Native compositor painting can follow the final Compose frame.
        val folder = File(checkNotNull(app.getExternalFilesDir(null)), "product-screens")
        assertTrue("Create this suite's app-owned screenshot directory", folder.isDirectory || folder.mkdirs())
        val destination = File(folder, "review-$suffix.png")
        assertTrue("Capture the actual emulator screen: ${destination.name}", device.takeScreenshot(destination))
    }
}
