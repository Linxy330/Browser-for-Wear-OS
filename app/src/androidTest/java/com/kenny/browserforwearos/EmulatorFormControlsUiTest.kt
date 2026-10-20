package com.kenny.browserforwearos

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import com.kenny.browserforwearos.engine.EnginePrompt
import com.kenny.browserforwearos.presentation.BrowserViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView

/** Native control UI values are verified by the real document's change event. */
@RunWith(AndroidJUnit4::class)
class EmulatorFormControlsUiTest : EmulatorProductFixture() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var model: BrowserViewModel
    private lateinit var root: View
    private val device by lazy { UiDevice.getInstance(instrumentation) }

    @Before fun openBrowser() {
        awaitUiPump = { compose.mainClock.advanceTimeByFrame() }
        model = openModel(keepCompose = true)
        scenario!!.onActivity { root = it.window.decorView }
        click(R.string.b1_open_web)
        compose.onNode(hasScrollAction()).performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement("$base/product/control?type=date")
        compose.onNode(hasSetTextAction()).performImeAction()
        await("Actual date document must commit") { model.controller.title.value == "PRODUCT_CONTROL_date_READY" && !model.controller.isLoading.value }
    }

    @Test fun dateRejectsStepMismatchThenChangesTheActualDomValue() {
        openPrompt("date")
        assertTrue(model.controller.pendingPrompt.value is EnginePrompt.DateTime)
        replaceValue("2026-10-20")
        click(R.string.b1_ok)
        assertTrue("A step mismatch must leave the original prompt open", model.controller.pendingPrompt.value is EnginePrompt.DateTime)
        show(R.string.invalid_value)
        replaceValue("2026-10-21")
        click(R.string.b1_ok)
        awaitValue("date", "2026-10-21")
    }

    @Test fun monthWeekTimeAndLocalDateTimeReachRealHtmlInputs() {
        val values = listOf("month" to "2026-12", "week" to "2026-W42", "time" to "10:30", "datetime-local" to "2026-10-20T10:30")
        for ((kind, value) in values) {
            navigate(kind)
            openPrompt(kind)
            assertTrue("Expected a native $kind prompt", model.controller.pendingPrompt.value is EnginePrompt.DateTime)
            replaceValue(value)
            click(R.string.b1_ok)
            awaitValue(kind, value)
        }
    }

    @Test fun valueAttributeStepBaseWithoutMinimumAllowsValidDates() {
        navigate("date-base")
        openPrompt("date-base")
        replaceValue("2024-01-03")
        click(R.string.b1_ok)
        awaitValue("date-base", "2024-01-03")
    }

    @Test fun colorCancelKeepsValueAndExplicitConfirmChangesActualDom() {
        navigate("color")
        openPrompt("color")
        assertTrue(model.controller.pendingPrompt.value is EnginePrompt.Color)
        replaceValue("#34cceb")
        click(R.string.b1_cancel)
        await("Cancel must resolve the native color prompt") { model.controller.pendingPrompt.value == null }
        assertEquals("PRODUCT_CONTROL_color_READY", model.controller.title.value)
        openPrompt("color")
        val current = model.controller.pendingPrompt.value as EnginePrompt.Color
        assertEquals("#123456", current.prompt.defaultValue)
        replaceValue("#34cceb")
        click(R.string.b1_ok)
        awaitValue("color", "#34cceb")
    }

    private fun navigate(kind: String) {
        onMain { assertTrue(model.submitAddress("$base/product/control?type=$kind")) }
        await("Expected real $kind document") { model.controller.title.value == "PRODUCT_CONTROL_${kind}_READY" && !model.controller.isLoading.value }
    }
    private fun openPrompt(kind: String) {
        await("A real GeckoView must attach") { onMain { findGecko(root)?.session === model.controller.session } }
        Thread.sleep(250)
        val point = onMain {
            val view = checkNotNull(findGecko(root))
            val position = IntArray(2); view.getLocationOnScreen(position)
            position[0] + view.width / 2 to position[1] + view.height / 2
        }
        assertTrue(device.click(point.first, point.second))
        await("Actual $kind input must request native UI") { model.controller.pendingPrompt.value != null }
    }
    private fun replaceValue(value: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement(value)
    }
    private fun awaitValue(kind: String, value: String) {
        await("A real DOM change must contain the confirmed value") {
            model.controller.pendingPrompt.value == null && model.controller.title.value == "PRODUCT_CONTROL_${kind}_VALUE_$value"
        }
        assertNull(model.controller.error.value)
    }
    private fun click(resource: Int) {
        val matcher = hasText(app.getString(resource)) and hasClickAction()
        compose.onNode(hasScrollAction()).performScrollToNode(matcher)
        compose.onNode(matcher).performClick()
    }
    private fun show(resource: Int) {
        val matcher = hasText(app.getString(resource))
        compose.onNode(hasScrollAction()).performScrollToNode(matcher)
        compose.onNode(matcher).assertIsDisplayed()
    }
    private fun findGecko(view: View): GeckoView? {
        if (view is GeckoView && view.isAttachedToWindow && view.width > 0) return view
        if (view is ViewGroup) repeat(view.childCount) { findGecko(view.getChildAt(it))?.let { found -> return found } }
        return null
    }
}
