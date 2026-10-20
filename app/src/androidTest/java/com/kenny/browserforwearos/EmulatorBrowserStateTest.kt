package com.kenny.browserforwearos

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.kenny.browserforwearos.data.BrowserRepository
import com.kenny.browserforwearos.data.BrowserSnapshot
import com.kenny.browserforwearos.presentation.BrowserViewModel
import com.kenny.browserforwearos.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Exercises persisted state against real engine sessions; the runner enforces QEMU first. */
class EmulatorBrowserStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val repository get() = BrowserRepository(app)

    @Before
    fun cleanMetadata() {
        runBlocking { repository.save(BrowserSnapshot()) }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(label: String, timeout: Long = 25_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var result = false
            main { result = condition() }
            if (result) return
            Thread.sleep(100)
        }
        fail("Timed out: $label")
    }

    @Test
    fun tabsBookmarksAndHistorySurviveActivityAndViewModelRecreation() {
        var savedIds = emptyList<String>()
        var savedSelected: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: BrowserViewModel
            scenario.onActivity { model = ViewModelProvider(it)[BrowserViewModel::class.java] }
            await("metadata ready") { model.ready.value }
            main { model.setWebVisible(true); model.submitAddress("$BASE/counter") }
            await("counter committed") { model.controller.committedUrl.value.endsWith("/counter") }
            main {
                model.addBookmark()
                assertEquals(1, model.bookmarks.value.size)
                model.editBookmark(model.bookmarks.value.first().id, "我的书签")
                assertTrue(model.newTab())
                model.submitAddress("$BASE/table")
            }
            await("table committed") { model.controller.committedUrl.value.endsWith("/table") }
            main { assertTrue(model.newTab()); model.submitAddress("$BASE/long") }
            await("long page committed") { model.controller.committedUrl.value.endsWith("/long") }
            main {
                assertFalse("fourth tab must be refused", model.newTab())
                assertEquals(3, model.tabs.value.size)
                savedIds = model.tabs.value.map { it.id }
                assertEquals(3, savedIds.toSet().size)
                model.selectTab(savedIds[0])
                savedSelected = model.selectedTabId.value
                assertEquals("我的书签", model.bookmarks.value.single().title)
                assertTrue(model.history.value.any { it.url.endsWith("/counter") })
            }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val snapshot = runBlocking { repository.read().snapshot }
                if (snapshot.tabs.map { it.id } == savedIds && snapshot.selectedTabId == savedSelected && snapshot.bookmarks.singleOrNull()?.title == "我的书签") break
                Thread.sleep(100)
            }
            val saved = runBlocking { repository.read().snapshot }
            assertEquals(savedIds, saved.tabs.map { it.id })
            assertEquals(savedSelected, saved.selectedTabId)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var restored: BrowserViewModel
            scenario.onActivity { restored = ViewModelProvider(it)[BrowserViewModel::class.java] }
            await("restored metadata ready") { restored.ready.value }
            main {
                assertEquals(savedIds, restored.tabs.value.map { it.id })
                assertEquals(savedSelected, restored.selectedTabId.value)
                assertEquals("我的书签", restored.bookmarks.value.single().title)
                assertEquals(3, restored.history.value.map { it.url }.toSet().size)
                assertTrue("restored hidden tabs must not preload", restored.tabs.value.all { it.controller.committedRevision.value == 0L })
                restored.removeBookmark(restored.bookmarks.value.single().id)
                restored.removeHistory(restored.history.value.first().id)
                assertEquals(2, restored.history.value.size)
                restored.clearHistory()
                assertTrue(restored.history.value.isEmpty())
                val current = restored.selectedTabId.value!!
                restored.closeTab(current)
                assertEquals(2, restored.tabs.value.size)
                assertFalse(restored.tabs.value.any { it.id == current })
                while (restored.tabs.value.size > 1) restored.closeTab(restored.tabs.value.last().id)
                restored.closeTab(restored.tabs.value.single().id)
                assertEquals(1, restored.tabs.value.size)
                assertEquals("", restored.controller.url.value)
            }
        }
    }

    @Test
    fun selectingSuspendedTabAndSubmittingInSameUiTurnKeepsExplicitNavigation() {
        assertTrue(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: BrowserViewModel
            scenario.onActivity {
                model = ViewModelProvider(it)[BrowserViewModel::class.java]
                // Keep visibility deterministic rather than letting the Home composition
                // asynchronously change it while this test drives the ViewModel directly.
                it.setContentView(android.widget.FrameLayout(it))
            }
            await("metadata ready for same-turn navigation") { model.ready.value }
            main { model.setForeground(true); model.setWebVisible(true) }
            val target = model.controller
            val targetId = target.tabId
            main { assertTrue(model.newTab()) }
            val otherId = model.selectedTabId.value!!
            repeat(3) { attempt ->
                val oldUrl = "$BASE/counter?old=$attempt"
                main { model.selectTab(targetId); assertTrue(model.submitAddress(oldUrl)) }
                await("old document committed before suspension $attempt") {
                    target.committedUrl.value == oldUrl && target.title.value == "计数器" && !target.isLoading.value
                }
                val previousSession = target.sessionState.value
                val previousRevision = target.committedRevision.value
                val newUrl = if (attempt % 2 == 0) "$BASE/long?new=$attempt" else "$BASE/gone?new=$attempt"
                val expectedTitle = if (attempt % 2 == 0) "长页面" else "404"
                var resumedSession: org.mozilla.geckoview.GeckoSession? = null
                main {
                    model.selectTab(otherId)
                    target.suspendPage()
                    assertTrue(target.suspendedState.value)
                    assertFalse(previousSession.isOpen)
                    // Both actions deliberately share one main-thread Runnable. A queued
                    // automatic GET of oldUrl must be cancelled by this explicit address.
                    model.selectTab(targetId)
                    assertSame(target, model.controller)
                    resumedSession = target.sessionState.value
                    assertNotSame(previousSession, resumedSession)
                    assertSame(resumedSession, target.session)
                    assertTrue(target.session.isOpen)
                    assertTrue(model.submitAddress(newUrl))
                }
                await("explicit address must win over the queued restore GET $attempt") {
                    target.committedUrl.value == newUrl && target.title.value == expectedTitle &&
                        target.committedRevision.value > previousRevision && !target.isLoading.value
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(600) // Drain late native callbacks from the cancelled restore.
                main {
                    assertEquals(targetId, model.selectedTabId.value)
                    assertEquals(newUrl, target.url.value)
                    assertEquals(newUrl, target.committedUrl.value)
                    assertEquals(expectedTitle, target.title.value)
                    assertEquals("Only the explicit GET should commit", previousRevision + 1, target.committedRevision.value)
                    assertSame(resumedSession, target.sessionState.value)
                    assertFalse(target.suspendedState.value)
                    assertNull(target.error.value)
                }
            }
        }
    }

    @Test
    fun corruptedProfileIsPreservedAndRecoversToUsableBlankTab() {
        val directory = File(app.filesDir, "browser").apply { mkdirs() }
        File(directory, "state.json").writeText("{incomplete-json")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: BrowserViewModel
            scenario.onActivity { model = ViewModelProvider(it)[BrowserViewModel::class.java] }
            await("corruption recovery") { model.ready.value }
            main {
                assertEquals(1, model.tabs.value.size)
                assertTrue(model.bookmarks.value.isEmpty())
                assertTrue(model.history.value.isEmpty())
                assertTrue(model.newTab())
            }
            val corrupt = directory.listFiles { file -> file.name.startsWith("state-corrupt-") }.orEmpty()
            assertEquals(1, corrupt.size)
            assertEquals("{incomplete-json", corrupt.single().readText())
        }
    }

    companion object { private const val BASE = "http://127.0.0.1:8787" }
}
