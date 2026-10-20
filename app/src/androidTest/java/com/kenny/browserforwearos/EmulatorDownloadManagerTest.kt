package com.kenny.browserforwearos

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.kenny.browserforwearos.data.download.BrowserDownloadManager
import com.kenny.browserforwearos.data.download.DownloadError
import com.kenny.browserforwearos.data.download.DownloadLimits
import com.kenny.browserforwearos.data.download.DownloadResponseFetcher
import com.kenny.browserforwearos.data.download.DownloadStatus
import com.kenny.browserforwearos.data.download.DownloadStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mozilla.geckoview.WebResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Storage/stream ownership tests. Actual HTTP/TLS download integration is tested separately. */
class EmulatorDownloadManagerTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var owner: CoroutineScope
    private var manager: BrowserDownloadManager? = null
    private var limits = DownloadLimits(maxFileBytes = 1024, maxStoredBytes = 4096, minimumFreeBytes = 0,
        maxConcurrent = 1, confirmationTimeoutMs = 5000, readTimeoutMs = 1000, fetchTimeoutMs = 1000)

    @Before fun requireEmulatorAndIsolateStorage() {
        assertTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, "download-manager-test-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @After fun closeOnlyIsolatedResources() {
        if (!::root.isInitialized) return
        manager?.close()
        owner.cancel()
        runBlocking { withTimeout(5000) { DownloadStorage(context, limits).load() } }
        root.deleteRecursively()
    }

    @Test fun responseIsNotReadBeforeConfirmationAndCompletedBytesAreRealThenDeleted() {
        val downloads = newManager()
        val body = CountingStream("actual file bytes".toByteArray())
        val id = downloads.offer(response(body, "../../sample.txt", length = 17))!!
        assertEquals(DownloadStatus.WAITING, downloads.downloads.value.single().status)
        assertEquals(0, body.reads.get())
        assertTrue(downloads.start(id))
        await { downloads.downloads.value.single().status == DownloadStatus.COMPLETED }
        val item = downloads.downloads.value.single()
        val file = File(root, "downloads/files/$id/${item.fileName}")
        assertArrayEquals("actual file bytes".toByteArray(), file.readBytes())
        assertEquals(17L, item.receivedBytes)
        assertFalse(item.fileName.contains('/'))
        assertTrue(body.closed.get())
        downloads.delete(id)
        await { !file.parentFile!!.exists() && downloads.downloads.value.isEmpty() }
    }

    @Test fun privateOfferNeverPersistsRetryUrlAndCancellingClosesUnreadBody() {
        val downloads = newManager()
        val body = CountingStream(byteArrayOf(1, 2, 3))
        val source = "https://private.example/document"
        val id = downloads.offer(WebResponse.Builder(source).statusCode(200).body(body).build(), privateMode = true)!!
        assertNull(downloads.downloads.value.single().sourceUrl)
        assertTrue(downloads.cancel(id))
        await { body.closed.get() }
        assertEquals(0, body.reads.get())
        assertFalse(downloads.downloads.value.single().canRetry)
        downloads.close()
        runBlocking { withTimeout(5000) { DownloadStorage(context, limits).load() } }
        assertFalse(File(root, "downloads/index.json").readText().contains(source))
    }

    @Test fun oversizedKnownLengthAndHttpFailureCloseStreamsWithoutFakeSuccess() {
        limits = limits.copy(maxFileBytes = 8, maxStoredBytes = 32)
        val downloads = newManager()
        val rejected = CountingStream(byteArrayOf(1))
        assertNull(downloads.offer(WebResponse.Builder("https://example.com/denied").statusCode(403).body(rejected).build()))
        await { rejected.closed.get() }
        val tooLarge = CountingStream(ByteArray(10))
        val id = downloads.offer(response(tooLarge, "large.bin", 10))!!
        assertTrue(downloads.start(id))
        await { downloads.downloads.value.single().status == DownloadStatus.FAILED && tooLarge.closed.get() }
        assertEquals(DownloadError.SIZE_LIMIT, downloads.downloads.value.single().error)
        assertEquals(0, tooLarge.reads.get())
        assertFalse(File(root, "downloads/files/$id/large.bin").exists())
    }

    @Test fun cancellingThenRetryingWaitsForOldCleanupAndCannotDeleteNewPayload() {
        val downloads = newManager(DownloadResponseFetcher { response(CountingStream("retry".toByteArray()), "cancel.bin", 5) })
        val old = BlockingStream()
        val id = downloads.offer(response(old, "cancel.bin"))!!
        assertTrue(downloads.start(id))
        await { old.waiting.get() }
        assertTrue(downloads.cancel(id))
        await { old.closed.get() }
        await { downloads.retry(id) }
        await { downloads.downloads.value.single().status == DownloadStatus.COMPLETED }
        val file = File(root, "downloads/files/$id/cancel.bin")
        assertEquals("retry", file.readText())
        Thread.sleep(150)
        assertEquals("retry", file.readText())
        assertFalse(File(root, "downloads/files/$id/.part").exists())
    }

    @Test fun replacementManagerReadsFinalIndexAfterOldOwnerClosesAndKeepsCompletedFile() {
        val first = newManager()
        val completedId = first.offer(response(CountingStream("saved".toByteArray()), "persist.txt", 5))!!
        assertTrue(first.start(completedId))
        await { first.downloads.value.single().status == DownloadStatus.COMPLETED }
        val waitingBody = CountingStream(byteArrayOf(9))
        val waitingId = first.offer(response(waitingBody, "unread.bin", 1))!!
        first.close()
        val replacement = newManager()
        assertEquals(DownloadStatus.COMPLETED, replacement.downloads.value.first { it.id == completedId }.status)
        assertEquals(DownloadStatus.CANCELLED, replacement.downloads.value.first { it.id == waitingId }.status)
        assertEquals("saved", File(root, "downloads/files/$completedId/persist.txt").readText())
        await { waitingBody.closed.get() }
        assertEquals(0, waitingBody.reads.get())
    }

    private fun newManager(fetcher: DownloadResponseFetcher? = null): BrowserDownloadManager =
        BrowserDownloadManager(context, owner, fetcher, limits).also { manager = it; await { it.ready.value } }

    private fun response(body: InputStream, name: String, length: Long? = null): WebResponse =
        WebResponse.Builder("https://example.com/download").statusCode(200).body(body)
            .header("Content-Disposition", "attachment; filename=\"$name\"")
            .header("Content-Type", "application/octet-stream")
            .apply { if (length != null) header("Content-Length", length.toString()) }.build()

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(5000) { while (!condition()) delay(10) }
    }

    private class CountingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        val reads = AtomicInteger()
        val closed = AtomicBoolean(false)
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            reads.incrementAndGet()
            return super.read(bytes, offset, length)
        }
        override fun close() { closed.set(true); super.close() }
    }

    private class BlockingStream : InputStream() {
        val waiting = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        private var first = true
        private val release = CountDownLatch(1)
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (first) { first = false; repeat(4) { bytes[offset + it] = 1 }; return 4 }
            waiting.set(true)
            release.await(5, TimeUnit.SECONDS)
            if (closed.get()) throw IOException("Fixture stream closed")
            return -1
        }
        override fun close() { closed.set(true); release.countDown() }
    }
}
