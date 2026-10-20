package com.kenny.browserforwearos

import android.app.Application
import android.graphics.Rect
import java.io.File
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.kenny.browserforwearos.data.BrowserRepository
import com.kenny.browserforwearos.data.BrowserSnapshot
import com.kenny.browserforwearos.presentation.BrowserViewModel
import com.kenny.browserforwearos.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.mozilla.geckoview.GeckoView
import kotlin.math.hypot
import kotlin.math.min

/** Real app UI: Compose actions navigate the app; UiDevice injects actual webpage touches. */
@RunWith(AndroidJUnit4::class)
class EmulatorBrowserUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val device by lazy { UiDevice.getInstance(instrumentation) }
    private val base get() = InstrumentationRegistry.getArguments().getString("base_url")?.trimEnd('/') ?: "http://127.0.0.1:8787"
    private lateinit var model: BrowserViewModel

    // This outer rule runs before the Compose rule launches MainActivity or writes app data.
    private val emulatorOnly = object : TestRule {
        override fun apply(baseStatement: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val qemu = ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu"),
                ).bufferedReader().use { it.readText().trim() }
                assertEquals("UI regression refuses physical devices", "1", qemu)
                val fingerprint = Build.FINGERPRINT.lowercase()
                assertTrue("Expected an emulator SDK fingerprint", fingerprint.contains("generic") || fingerprint.contains("emulator") ||
                    (Build.PRODUCT.startsWith("sdk_") && Build.HARDWARE in setOf("ranchu", "goldfish")))
                val repository = BrowserRepository(app)
                val original = runBlocking { repository.read().snapshot }
                runBlocking { repository.save(BrowserSnapshot()) }
                try {
                    baseStatement.evaluate()
                } finally {
                    // Inner activity rule has closed first, so no live ViewModel can overwrite this.
                    runBlocking { repository.save(original) }
                }
            }
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(emulatorOnly).around(compose)

    @Before
    fun waitForProfile() {
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.waitUntil(25_000) { model.ready.value }
    }

    @Test
    fun fourCornersRemainTouchableAfterZoomImmersiveAndFit() {
        openAddressFromHome("$base/corners")
        waitPage("$base/corners", "四角按钮")
        assertFitGeometry()
        clickAllFourCorners(0)
        screenshot("ui-fit-corners-before.png")

        openMenu()
        clickAction(R.string.zoom_in)
        Thread.sleep(650)
        openMenu()
        clickAction(R.string.zoom_out)
        Thread.sleep(650)
        openMenu()
        clickAction(R.string.immersive)
        compose.waitUntil(5_000) { onUi { geckoView().width >= compose.activity.window.decorView.width * .95f } }
        screenshot("ui-immersive.png")
        // The permanent menu must still be available to leave immersive rendering.
        openMenu()
        clickAction(R.string.fit_screen)
        Thread.sleep(650)
        compose.waitUntil(5_000) { onUi { geckoView().width < compose.activity.window.decorView.width * .8f } }
        assertFitGeometry()
        val count = model.controller.title.value.substringAfterLast(' ').toIntOrNull() ?: 0
        clickAllFourCorners(count)
        screenshot("ui-fit-corners-after.png")
    }

    @Test
    fun addressCancellationAndRealHistoryDriveNavigationButtons() {
        clickAction(R.string.b1_open_web)
        enterText("cancelled.example")
        clickAction(R.string.b1_cancel)
        scrollToAction(R.string.b1_open_web).assertIsDisplayed()

        openAddressFromHome("$base/corners")
        waitPage("$base/corners", "四角按钮")
        openMenu()
        scrollToAction(R.string.go_forward).assertIsNotEnabled()
        clickAction(R.string.edit_address)
        enterAndSubmit("$base/counter")
        waitPage("$base/counter", "计数器")

        openMenu()
        scrollToAction(R.string.go_back).assertIsEnabled().performClick()
        waitPage("$base/corners")
        openMenu()
        scrollToAction(R.string.go_forward).assertIsEnabled().performClick()
        waitPage("$base/counter")
        openMenu()
        clickAction(R.string.edit_address)
        enterText("another-cancelled.example")
        clickAction(R.string.b1_cancel)
        waitPage("$base/counter")
    }

    @Test
    fun homeBookmarksHistoryTabsAndSettingsStayReachableByScrolling() {
        openAddressFromHome("$base/counter")
        waitPage("$base/counter", "计数器")
        openMenu()
        clickAction(R.string.add_bookmark)
        compose.waitUntil(5_000) { model.bookmarks.value.any { it.url == "$base/counter" } }
        clickAction(R.string.b1_home)

        clickAction(R.string.bookmarks)
        clickTextAction("计数器")
        waitPage("$base/counter")
        openMenu()
        clickAction(R.string.b1_home)
        clickAction(R.string.history)
        clickTextAction("计数器")
        waitPage("$base/counter")
        openMenu()
        clickAction(R.string.b1_home)

        clickAction(R.string.tabs)
        clickAction(R.string.new_tab)
        enterText("not-submitted.example")
        clickAction(R.string.b1_cancel)
        compose.onNodeWithText(app.getString(R.string.tabs_count, 2)).assertIsDisplayed()
        assertEquals(2, model.tabs.value.size)
        clickAction(R.string.b1_home)

        clickAction(R.string.settings)
        clickAction(R.string.clear_site_data)
        scrollToAction(R.string.clear_confirm).assertIsEnabled()
        clickAction(R.string.b1_cancel)
        clickAction(R.string.b1_home)
        scrollToAction(R.string.b1_open_web).assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(500) // Wait for the native compositor after the final semantic update.
        val folder = app.getExternalFilesDir(null) ?: error("Missing app evidence folder")
        assertTrue(device.takeScreenshot(File(folder, name)))
    }

    private fun openAddressFromHome(url: String) {
        clickAction(R.string.b1_open_web)
        enterAndSubmit(url)
    }

    private fun enterText(value: String) {
        val edit = hasSetTextAction()
        scrollListTo(edit)
        compose.onNode(edit).performTextReplacement(value)
    }

    private fun enterAndSubmit(value: String) {
        enterText(value)
        compose.onNode(hasSetTextAction()).performImeAction()
    }

    private fun openMenu() {
        compose.onNode(hasText(app.getString(R.string.tools)) and hasClickAction()).performClick()
        // A menu-specific item confirms the overlay replaced page interaction.
        scrollToAction(R.string.back_to_page).assertIsDisplayed()
    }

    private fun clickAction(resource: Int) = scrollToAction(resource).performClick()

    private fun clickTextAction(text: String) {
        scrollListTo(hasText(text))
        compose.onNode(hasText(text) and hasClickAction()).performClick()
    }

    private fun scrollToAction(resource: Int): androidx.compose.ui.test.SemanticsNodeInteraction {
        val text = app.getString(resource)
        scrollListTo(hasText(text))
        return compose.onNode(hasText(text) and hasClickAction())
    }

    private fun scrollListTo(matcher: SemanticsMatcher) {
        if (compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasScrollAction()).performScrollToNode(matcher)
        }
    }

    private fun waitPage(url: String, title: String? = null) {
        compose.waitUntil(25_000) {
            model.controller.committedUrl.value == url && !model.controller.isLoading.value &&
                (title == null || model.controller.title.value == title)
        }
        assertNull(model.controller.error.value)
        compose.waitUntil(5_000) { onUi { findGecko(compose.activity.window.decorView)?.session === model.controller.session } }
    }

    private fun clickAllFourCorners(initial: Int) {
        // Fixture: .f width/height=64 CSS px with 8px inset, so each centre is 40 CSS px.
        // A fresh fit viewport uses Android density for Gecko CSS-to-screen conversion.
        Thread.sleep(350)
        val (bounds, density) = onUi { viewportBounds() to geckoView().resources.displayMetrics.density }
        val inset = 40f * density
        val points = listOf(
            bounds.left + inset to bounds.top + inset,
            bounds.right - inset to bounds.top + inset,
            bounds.left + inset to bounds.bottom - inset,
            bounds.right - inset to bounds.bottom - inset,
        )
        points.forEachIndexed { index, (x, y) ->
            assertTrue("Corner touch must be delivered", device.click(x.toInt(), y.toInt()))
            compose.waitUntil(8_000) { model.controller.title.value == "四角 ${initial + index + 1}" }
        }
    }

    private fun assertFitGeometry() {
        val bounds = onUi { viewportBounds() }
        val cx = device.displayWidth / 2f
        val cy = device.displayHeight / 2f
        val radius = min(device.displayWidth, device.displayHeight) / 2f
        listOf(bounds.left to bounds.top, bounds.right to bounds.top, bounds.left to bounds.bottom, bounds.right to bounds.bottom).forEach { (x, y) ->
            assertTrue("Fit viewport corner must lie inside the circular display", hypot(x - cx, y - cy) <= radius + 2f)
        }
    }

    private fun viewportBounds(): Rect {
        val gecko = geckoView()
        val position = IntArray(2)
        gecko.getLocationOnScreen(position)
        return Rect(position[0], position[1], position[0] + gecko.width, position[1] + gecko.height)
    }

    private fun geckoView(): GeckoView = checkNotNull(findGecko(compose.activity.window.decorView)) { "No attached GeckoView" }

    private fun findGecko(view: View): GeckoView? {
        if (view is GeckoView && view.isAttachedToWindow && view.width > 0) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findGecko(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun <T> onUi(action: () -> T): T = compose.runOnUiThread(action)
}
