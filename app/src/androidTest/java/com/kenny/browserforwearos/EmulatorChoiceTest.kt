package com.kenny.browserforwearos

import android.app.Application
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.kenny.browserforwearos.engine.EnginePrompt
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
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** Real optgroups and multi-select prompts, without enabling an accessibility service. */
@RunWith(AndroidJUnit4::class)
class EmulatorChoiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val device by lazy { UiDevice.getInstance(instrumentation) }
    private lateinit var model: BrowserViewModel

    private val emulatorOnly = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val qemu = ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu"),
                ).bufferedReader().use { it.readText().trim() }
                assertEquals("Choice tests refuse physical devices", "1", qemu)
                val fingerprint = Build.FINGERPRINT.lowercase()
                assertTrue("Expected an emulator SDK fingerprint", fingerprint.contains("generic") || fingerprint.contains("emulator") ||
                    (Build.PRODUCT.startsWith("sdk_") && Build.HARDWARE in setOf("ranchu", "goldfish")))
                val repository = BrowserRepository(app)
                val original = runBlocking { repository.read().snapshot }
                runBlocking { repository.save(BrowserSnapshot()) }
                try { base.evaluate() } finally { runBlocking { repository.save(original) } }
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
    fun groupedSingleChoiceShowsGroupsDisablesAncestorsAndReturnsToDom() {
        openFixture("single")
        tapSelect()
        val prompt = awaitChoice(GeckoSession.PromptDelegate.ChoicePrompt.Type.SINGLE)
        assertTrue("Fixture must provide real optgroup children", prompt.prompt.choices.any { it.items != null })
        scrollTo(hasText("Primary options"))
        compose.onNode(hasText("Primary options")).assertIsDisplayed()
        choice("Blocked option").assertIsNotEnabled()
        choice("Beta").performClick()
        compose.waitUntil(10_000) { model.controller.title.value == "CHOICE_RESULT_beta" }
        assertNull(model.controller.pendingPrompt.value)
    }

    @Test
    fun multipleChoicesStayPendingUntilOkAndCancelPreservesDomSelection() {
        openFixture("multiple")
        tapSelect()
        awaitChoice(GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE)
        scrollTo(hasText("✓ Alpha"))
        compose.onNode(hasText("✓ Alpha")).assertIsDisplayed()
        choice("Blocked option").assertIsNotEnabled()
        choice("Beta").performClick()
        scrollTo(hasText("✓ Beta"))
        compose.onNode(hasText("✓ Beta")).assertIsDisplayed()
        assertNotNull("Toggling a checkbox must leave the engine result pending", model.controller.pendingPrompt.value)
        assertEquals("CHOICE_READY_MULTIPLE", model.controller.title.value)
        dialogAction(R.string.b1_cancel)
        compose.waitUntil(5_000) { model.controller.pendingPrompt.value == null }
        assertEquals("Cancelled choices must not alter DOM selection", "CHOICE_READY_MULTIPLE", model.controller.title.value)

        tapSelect()
        awaitChoice(GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE)
        scrollTo(hasText("✓ Alpha"))
        compose.onNode(hasText("✓ Alpha")).assertIsDisplayed()
        scrollTo(hasText("○ Beta"))
        compose.onNode(hasText("○ Beta")).assertIsDisplayed()
        choice("Beta").performClick()
        dialogAction(R.string.b1_ok)
        compose.waitUntil(10_000) { model.controller.title.value == "CHOICE_RESULT_alpha,beta" }
        assertNull(model.controller.pendingPrompt.value)
    }

    private fun openFixture(mode: String) {
        val base = InstrumentationRegistry.getArguments().getString("base_url")?.trimEnd('/') ?: "http://127.0.0.1:8787"
        val url = "$base/choice-regression?mode=$mode"
        val open = app.getString(R.string.b1_open_web)
        scrollTo(hasText(open))
        compose.onNode(hasText(open) and hasClickAction()).performClick()
        scrollTo(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement(url)
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(25_000) { model.controller.committedUrl.value == url && !model.controller.isLoading.value }
        assertNull(model.controller.error.value)
    }

    private fun awaitChoice(type: Int): EnginePrompt.Choice {
        compose.waitUntil(8_000) { model.controller.pendingPrompt.value is EnginePrompt.Choice }
        val prompt = model.controller.pendingPrompt.value as EnginePrompt.Choice
        assertEquals(type, prompt.prompt.type)
        compose.waitForIdle()
        return prompt
    }

    private fun choice(label: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        scrollTo(hasText(label, substring = true))
        return compose.onNode(hasText(label, substring = true) and hasClickAction())
    }

    private fun dialogAction(resource: Int) {
        val label = app.getString(resource)
        // Wear AlertDialog includes its action buttons in the same scrolling content.
        scrollTo(hasText(label))
        compose.onNode(hasText(label) and hasClickAction()).performClick()
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        if (compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasScrollAction()).performScrollToNode(matcher)
        }
    }

    private fun tapSelect() {
        compose.waitForIdle()
        Thread.sleep(350)
        var bounds: Rect? = null
        compose.runOnUiThread {
            val view = checkNotNull(findGecko(compose.activity.window.decorView))
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            bounds = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        val rectangle = checkNotNull(bounds)
        assertTrue(device.click(rectangle.centerX(), rectangle.centerY()))
    }

    private fun findGecko(view: View): GeckoView? {
        if (view is GeckoView && view.isAttachedToWindow && view.session === model.controller.session && view.width > 0) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findGecko(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
