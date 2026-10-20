package com.kenny.browserforwearos

import android.os.Build
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.core.content.FileProvider
import com.kenny.browserforwearos.data.download.DownloadItem
import com.kenny.browserforwearos.data.download.DownloadStatus
import com.kenny.browserforwearos.data.download.UploadStager
import kotlinx.coroutines.runBlocking
import com.kenny.browserforwearos.engine.EnginePrompt
import com.kenny.browserforwearos.presentation.BrowserViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/**
 * Full app UI + real Gecko HTTP streams. No WebResponse.Builder or direct prompt
 * confirmation: native Compose buttons select/save files, and the fixture hashes
 * the actual multipart POST body. Requires product_fixture.py enabled in testsite.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorFileTransferUiTest : EmulatorProductFixture() {
    @get:Rule val compose = createEmptyComposeRule()
    private val device by lazy { UiDevice.getInstance(instrumentation) }
    private val runId = UUID.randomUUID().toString().replace("-", "")
    private lateinit var model: BrowserViewModel
    private lateinit var activityRoot: View
    private val payloadDirectories = mutableSetOf<File>()

    @Before
    fun openActualBrowserUi() {
        awaitUiPump = { compose.mainClock.advanceTimeByFrame() }
        // The superclass and runner guard before mutation; keep this local guard
        // as well so the test's device boundary is explicit when run by itself.
        assertTrue("File transfer UI tests refuse physical devices", Build.HARDWARE in setOf("ranchu", "goldfish"))
        model = openModel(keepCompose = true)
        scenario!!.onActivity { activityRoot = it.window.decorView }
        await("The real app download index must finish opening") { model.downloadManager.ready.value }
    }

    @After
    fun deleteOnlyThisCasesDownloads() {
        if (!::model.isInitialized || scenario == null) return
        val own = model.downloadManager.downloads.value.filter { it.fileName.startsWith("product-$runId-") }
        own.forEach { payloadDirectories.add(payload(it).parentFile!!) }
        own.forEach { model.downloadManager.delete(it.id) }
        await("Only this test's private payloads must be removed") {
            model.downloadManager.downloads.value.none { it.fileName.startsWith("product-$runId-") } &&
                payloadDirectories.none(File::exists)
        }
    }

    @Test
    fun realDownloadLinkWaitsForSaveAndRestoresExactBytesIntoFreshModel() {
        val completed = downloadFromActualLink("a")
        val firstModel = model
        val firstFile = payload(completed)
        assertArrayEquals(expectedBytes("a"), firstFile.readBytes())

        // ActivityScenario.recreate() keeps its ViewModel. Fully close/open so a
        // fresh manager must await the old index writer and read persisted data.
        closeScenario()
        model = openModel(keepCompose = true)
        scenario!!.onActivity { activityRoot = it.window.decorView }
        await("A fresh download manager must finish restoring its index") { model.downloadManager.ready.value }
        assertNotSame(firstModel, model)
        val restored = model.downloadManager.downloads.value.single { it.id == completed.id }
        assertEquals(completed.fileName, restored.fileName)
        assertEquals(DownloadStatus.COMPLETED, restored.status)
        assertEquals(expectedBytes("a").size.toLong(), restored.receivedBytes)
        assertEquals(sha256(expectedBytes("a")), sha256(payload(restored).readBytes()))
        clickAction(R.string.downloads)
        showText(restored.fileName)
        showText(app.getString(R.string.download_status_completed))
    }

    @Test
    fun realMultiFilePromptCanCancelThenPostsTheOriginalSavedBinaryBytes() {
        val first = downloadFromActualLink("a")
        val second = downloadFromActualLink("b")
        val url = "$base/product/upload?multiple=1"
        clickAction(R.string.b1_home)
        openAddressFromHome(url)
        waitPage(url, "PRODUCT_UPLOAD_READY_MULTIPLE")

        tapActualPageCentre()
        awaitFilePrompt()
        clickTextAction(first.fileName)
        showText("✓ ${first.fileName}")
        assertTrue("A multi-selection stays pending until the user confirms", model.controller.pendingPrompt.value is EnginePrompt.File)
        clickAction(R.string.b1_cancel)
        await("Cancel must finish the native file prompt") { model.controller.pendingPrompt.value == null }
        assertEquals("Cancelling must not fire file input change or POST", "PRODUCT_UPLOAD_READY_MULTIPLE", model.controller.title.value)

        tapActualPageCentre()
        awaitFilePrompt()
        // A cancelled partial selection must not be retained by the next prompt.
        showText(first.fileName)
        clickTextAction(first.fileName)
        clickTextAction(second.fileName)
        showText("✓ ${second.fileName}")
        assertEquals("PRODUCT_UPLOAD_READY_MULTIPLE", model.controller.title.value)
        val revision = model.controller.committedRevision.value
        clickAction(R.string.confirm_selection)
        val bytesA = expectedBytes("a")
        val bytesB = expectedBytes("b")
        val hashes = listOf(sha256(bytesA), sha256(bytesB)).sorted().joinToString(",")
        val actualPostTitle = "PRODUCT_UPLOADED_2_${bytesA.size + bytesB.size}_$hashes"
        await("The server must hash both real multipart payloads; actual title=${model.controller.title.value}", 35_000) {
            model.controller.title.value == actualPostTitle && model.controller.committedRevision.value > revision &&
                !model.controller.isLoading.value && model.controller.pendingPrompt.value == null
        }
        assertNull(model.controller.error.value)
        assertEquals("$base/product/upload", model.controller.committedUrl.value)
        assertEquals("Upload must leave the original private files intact", sha256(bytesA), sha256(payload(first).readBytes()))
        assertEquals(sha256(bytesB), sha256(payload(second).readBytes()))
    }

    @Test
    fun contentUriStagingFeedsNativeMultipartAndCleansAfterCompletedNavigation() {
        val first = downloadFromActualLink("a")
        val second = downloadFromActualLink("b")
        val content = listOf(first, second).map {
            FileProvider.getUriForFile(app, "${app.packageName}.downloads", payload(it))
        }
        val prepared = runBlocking { UploadStager(app).prepare(content) }
        assertNull(prepared.error)
        val lease = checkNotNull(prepared.lease)
        val staged = lease.uris.map { File(checkNotNull(it.path)) }
        try {
            assertTrue(staged.all(File::isFile))
            clickAction(R.string.b1_home)
            val url = "$base/product/upload?multiple=1"
            openAddressFromHome(url)
            waitPage(url, "PRODUCT_UPLOAD_READY_MULTIPLE")
            tapActualPageCentre()
            awaitFilePrompt()
            onMain { assertTrue(model.controller.confirmFiles(lease.uris, lease)) }
            val hashes = listOf(sha256(expectedBytes("a")), sha256(expectedBytes("b"))).sorted().joinToString(",")
            await("Staged content streams must reach a real multipart POST with exact original hashes", 35_000) {
                model.controller.title.value == "PRODUCT_UPLOADED_2_81920_$hashes" && !model.controller.isLoading.value
            }
            assertNull(model.controller.error.value)
            await("Completed navigation must release only the leased upload copies") { staged.none(File::exists) }
            assertArrayEquals(expectedBytes("a"), payload(first).readBytes())
            assertArrayEquals(expectedBytes("b"), payload(second).readBytes())
        } finally { lease.close() }
    }

    private fun downloadFromActualLink(slot: String): DownloadItem {
        // Every prior successful save leaves Downloads visible. The next address
        // still travels through Home -> address editor -> real webpage.
        if (model.downloadManager.downloads.value.any { it.fileName.startsWith("product-$runId-") }) clickAction(R.string.b1_home)
        val url = "$base/product/download?run=$runId&slot=$slot"
        openAddressFromHome(url)
        waitPage(url, "PRODUCT_DOWNLOAD_READY_${slot.uppercase()}")
        val fileName = "product-$runId-$slot.bin"
        val pageRevision = model.controller.committedRevision.value
        tapActualPageCentre()
        await("A real download link must reach onExternalResponse and the app's WAITING list") {
            model.downloadManager.downloads.value.any { it.fileName == fileName && it.status == DownloadStatus.WAITING }
        }
        val offered = model.downloadManager.downloads.value.single { it.fileName == fileName }
        payloadDirectories.add(payload(offered).parentFile!!)
        assertEquals(0L, offered.receivedBytes)
        assertEquals(expectedBytes(slot).size.toLong(), offered.totalBytes!!)
        assertEquals(URI(base).host, offered.sourceHost)
        assertFalse("A website response must wait for a user save before creating a payload", payload(offered).exists())
        assertEquals("An external response must keep the original page committed", url, model.controller.committedUrl.value)
        assertEquals(pageRevision, model.controller.committedRevision.value)
        showText(fileName)
        showText(app.getString(R.string.download_source, offered.sourceHost))
        showText(app.getString(R.string.download_status_waiting))
        val size = Formatter.formatShortFileSize(app, offered.totalBytes!!)
        showText(app.getString(R.string.download_progress, Formatter.formatShortFileSize(app, 0), size))
        clickAction(R.string.download_save)
        await("Saving the real response must finish with exact bytes") {
            model.downloadManager.downloads.value.any { it.id == offered.id && it.status == DownloadStatus.COMPLETED }
        }
        val completed = model.downloadManager.downloads.value.single { it.id == offered.id }
        assertEquals(expectedBytes(slot).size.toLong(), completed.receivedBytes)
        assertEquals(sha256(expectedBytes(slot)), sha256(payload(completed).readBytes()))
        assertFalse(File(payload(completed).parentFile, ".part").exists())
        return completed
    }

    private fun awaitFilePrompt() {
        await("A real file input touch must show ProductFilePrompt") { model.controller.pendingPrompt.value is EnginePrompt.File }
        val prompt = model.controller.pendingPrompt.value as EnginePrompt.File
        assertEquals(GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE, prompt.prompt.type)
        assertTrue(prompt.prompt.mimeTypes.orEmpty().any { it == ".bin" || it == "application/octet-stream" })
        showText(app.getString(R.string.upload_from_site, prompt.origin))
        showText(app.getString(R.string.upload_multiple))
    }

    private fun openAddressFromHome(url: String) {
        clickAction(R.string.b1_open_web)
        scrollTo(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement(url)
        compose.onNode(hasSetTextAction()).performImeAction()
    }

    private fun waitPage(url: String, title: String) {
        await("Expected a real committed fixture document: $title") {
            model.controller.committedUrl.value == url && model.controller.title.value == title && !model.controller.isLoading.value
        }
        assertNull(model.controller.error.value)
        await("The actual app GeckoView must display the selected native session") {
            onMain { findGecko(activityRoot)?.session === model.controller.session }
        }
    }

    private fun tapActualPageCentre() {
        Thread.sleep(350) // Allow the native compositor to paint after onPageStop.
        val point = onMain {
            val gecko = checkNotNull(findGecko(activityRoot))
            val position = IntArray(2)
            gecko.getLocationOnScreen(position)
            (position[0] + gecko.width / 2) to (position[1] + gecko.height / 2)
        }
        assertTrue("An actual touchscreen tap must reach the DOM", device.click(point.first, point.second))
    }

    private fun findGecko(view: View): GeckoView? {
        if (view is GeckoView && view.isAttachedToWindow && view.width > 0) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findGecko(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun showText(text: String) { scrollTo(hasText(text)); compose.onAllNodes(hasText(text))[0].assertIsDisplayed() }
    private fun clickAction(resource: Int) = clickTextAction(app.getString(resource))
    private fun clickTextAction(text: String) {
        val match = hasText(text) and hasClickAction()
        scrollTo(match)
        compose.onNode(match).performClick()
    }
    private fun scrollTo(matcher: SemanticsMatcher) {
        if (compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasScrollAction()).performScrollToNode(matcher)
        }
    }
    private fun payload(item: DownloadItem): File = File(app.filesDir, "downloads/files/${item.id}/${item.fileName}")
    private fun expectedBytes(slot: String): ByteArray = when (slot) {
        "a" -> ByteArray(32 * 1024) { ((it * 73 + 19) % 256).toByte() }
        "b" -> ByteArray(48 * 1024) { ((it * 29 + 101) % 256).toByte() }
        else -> error("Unknown fixture slot")
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
