package com.kenny.browserforwearos

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import com.kenny.browserforwearos.presentation.BrowserViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView
import java.util.UUID

/**
 * Reconstructs an Activity and a fresh ViewModel from disk, allowing initial native
 * about:blank events to arrive before using only the real Home/Tabs UI to restore.
 * This verifies cold Activity/profile reconstruction, not whole-process death.
 * The inherited fixture and EmulatorOnlyTestRunner reject physical devices.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorColdRestoreUiTest : EmulatorProductFixture() {
    @get:Rule val compose = createEmptyComposeRule()
    private val device by lazy { UiDevice.getInstance(instrumentation) }

    @Before
    fun advanceComposeFramesDuringEngineAndProfileWaits() {
        awaitUiPump = { compose.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun homeContinueRetainsSavedLabelAfterInitialBlankAndLoadsTheRealPage() {
        val restored = saveCloseAndColdLaunch()
        scrollToAction(hasText(app.getString(R.string.continue_browsing)) and hasClickAction())
            .assertIsDisplayed().assertIsEnabled().performClick()
        verifyActualRestoredGetAndDom(restored)
    }

    @Test
    fun savedTabLabelSurvivesInitialBlankAndItsRowLoadsTheRealPage() {
        val restored = saveCloseAndColdLaunch()
        clickAction(R.string.tabs)
        scrollToAction(hasText(SAVED_TITLE, substring = true) and hasClickAction())
            .assertIsDisplayed().assertIsEnabled().performClick()
        verifyActualRestoredGetAndDom(restored)
    }

    private data class RestoredPage(val model: BrowserViewModel, val url: String, val tabId: String)

    private fun saveCloseAndColdLaunch(): RestoredPage {
        val url = "$base/regression/interaction?mode=counter&visit=cold-${UUID.randomUUID()}"
        val first = openModel(keepCompose = true)
        clickAction(R.string.b1_open_web)
        scrollToAction(hasSetTextAction()).performTextReplacement(url)
        compose.onNode(hasSetTextAction()).performImeAction()
        waitForActualPage(first, url, SAVED_TITLE)
        val tabId = first.controller.tabId
        val oldNative = onMain { first.controller.session }
        await("The normal tab URL and title must reach the atomic v2 profile") {
            val snapshot = readSnapshot()
            snapshot.tabs.singleOrNull()?.let { it.id == tabId && it.url == url && it.title == SAVED_TITLE } == true &&
                snapshot.selectedTabId == tabId
        }
        closeScenario()
        assertFalse("The first native session must close with its Activity-owned ViewModel", onMain { oldNative.isOpen })
        val disk = readSnapshot()
        assertEquals(url, disk.tabs.single().url)
        assertEquals(SAVED_TITLE, disk.tabs.single().title)
        assertEquals(2, profileJson().getInt("version"))

        val fresh = openModel(keepCompose = true)
        assertNotSame("ActivityScenario.recreate would retain the model; this launch must not", first, fresh)
        assertEquals(tabId, fresh.selectedTabId.value)
        assertNotSame(oldNative, onMain { fresh.controller.session })
        // Use real elapsed time and advance Compose frames. An immediate assertion
        // would miss the asynchronous initial about:blank regression entirely.
        val deadline = SystemClock.elapsedRealtime() + 1_200
        while (SystemClock.elapsedRealtime() < deadline) {
            awaitUiPump?.invoke()
            Thread.sleep(50)
        }
        assertEquals("Initial native blank must not erase the saved display URL", url, fresh.controller.url.value)
        assertEquals("Initial native blank must not erase the saved tab label", SAVED_TITLE, fresh.controller.title.value)
        assertEquals("Home must wait for a real user restore action", 0L, fresh.controller.committedRevision.value)
        return RestoredPage(fresh, url, tabId)
    }

    private fun verifyActualRestoredGetAndDom(restored: RestoredPage) {
        assertEquals(restored.tabId, restored.model.selectedTabId.value)
        waitForActualPage(restored.model, restored.url, SAVED_TITLE)
        assertTrue("A UI restore must commit a real document", restored.model.controller.committedRevision.value > 0)
        Thread.sleep(350)
        val point = onMain {
            val gecko = checkNotNull(findGecko(checkNotNull(scenarioActivity).window.decorView))
            assertSame(restored.model.controller.session, gecko.session)
            val xy = IntArray(2)
            gecko.getLocationOnScreen(xy)
            xy[0] + gecko.width / 2 to xy[1] + gecko.height / 2
        }
        assertTrue(device.click(point.first, point.second))
        await("The page restored through real app UI must receive a real DOM click") {
            restored.model.controller.title.value == "REGRESSION_CLICK_1"
        }
        assertNull(restored.model.controller.error.value)
    }

    private fun waitForActualPage(model: BrowserViewModel, url: String, title: String) {
        await("The real selected tab must commit $title") {
            model.controller.committedUrl.value == url && model.controller.title.value == title && !model.controller.isLoading.value
        }
        assertNull(model.controller.error.value)
        await("The actual GeckoView must display this selected native session") {
            onMain { findGecko(checkNotNull(scenarioActivity).window.decorView)?.session === model.controller.session }
        }
    }

    private fun clickAction(resource: Int) {
        scrollToAction(hasText(app.getString(resource)) and hasClickAction()).performClick()
    }

    private fun scrollToAction(matcher: SemanticsMatcher): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNode(hasScrollAction()).performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    private fun findGecko(node: View): GeckoView? {
        if (node is GeckoView && node.isAttachedToWindow && node.width > 0 && node.height > 0) return node
        if (node is ViewGroup) for (index in 0 until node.childCount) findGecko(node.getChildAt(index))?.let { return it }
        return null
    }

    private companion object { const val SAVED_TITLE = "REGRESSION_READY" }
}
