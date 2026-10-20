package com.kenny.browserforwearos

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kenny.browserforwearos.data.download.BrowserDownloadManager
import com.kenny.browserforwearos.data.download.DownloadItem
import com.kenny.browserforwearos.data.download.DownloadLimits
import com.kenny.browserforwearos.data.download.DownloadStatus
import com.kenny.browserforwearos.data.download.DownloadStorage
import com.kenny.browserforwearos.data.download.ExportResult
import com.kenny.browserforwearos.data.download.MediaStoreDownloadDestination
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.WebResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real MediaStore writes; cleanup addresses only this case's newly inserted rows. */
@RunWith(AndroidJUnit4::class)
class EmulatorDownloadExportTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var owner: CoroutineScope
    private lateinit var resolver: ContentResolver
    private lateinit var manager: BrowserDownloadManager
    private val runId = UUID.randomUUID().toString().replace("-", "")
    private val namePrefix = "export-$runId"
    private val rowsBefore = mutableSetOf<Uri>()
    private val limits = DownloadLimits(maxFileBytes = 2 * 1024 * 1024, maxStoredBytes = 4 * 1024 * 1024,
        minimumFreeBytes = 0, confirmationTimeoutMs = 10_000)
    private val collection get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Before
    fun requireEmulatorAndIsolatePrivateStorage() {
        assertTrue("MediaStore tests refuse physical devices", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        resolver = target.contentResolver
        rowsBefore.addAll(rows().map { it.uri })
        root = File(target.cacheDir, "download-export-test-$runId")
        assertTrue(root.mkdirs())
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        manager = BrowserDownloadManager(context, owner, limits = limits)
        await { manager.ready.value }
    }

    @After
    fun deleteOnlyNewRowsOwnedByThisCase() {
        if (!::root.isInitialized) return
        try {
            if (::manager.isInitialized) manager.close()
            if (::owner.isInitialized) owner.cancel()
            if (::context.isInitialized) runBlocking { withTimeout(5_000) { DownloadStorage(context, limits).load() } }
        } finally {
            // A UUID prefix is used for discovery, but deletion is still limited
            // to URI identities which did not exist when this case began.
            rows().map { it.uri }.filterNot { it in rowsBefore }.forEach { resolver.delete(it, null, null) }
            root.deleteRecursively()
        }
    }

    @Test
    fun realMediaStoreExportPublishesExactBytesAndKeepsPrivateOriginal() {
        val bytes = ByteArray(96 * 1024) { ((it * 41 + 7) % 256).toByte() }
        val item = completed("$namePrefix.bin", bytes)
        assertEquals(ExportResult.SUCCESS, runBlocking { withTimeout(10_000) { manager.exportToDownloads(item.id) } })
        await { rows().singleOrNull { it.uri !in rowsBefore }?.size == bytes.size.toLong() }
        val row = rows().single { it.uri !in rowsBefore }
        assertEquals(item.fileName, row.name)
        assertEquals("application/octet-stream", row.mime)
        assertEquals(MediaStoreDownloadDestination.RELATIVE_PATH, row.relativePath)
        assertEquals(0, row.pending)
        assertEquals(bytes.size.toLong(), row.size)
        assertArrayEquals(bytes, resolver.openInputStream(row.uri)!!.use { it.readBytes() })
        assertArrayEquals(bytes, payload(item).readBytes())
        assertEquals(DownloadStatus.COMPLETED, manager.downloads.value.single { it.id == item.id }.status)
    }

    @Test
    fun duplicateDisplayNameCreatesNewRowsWithoutOverwritingEarlierBytes() {
        val firstBytes = ByteArray(4_097) { ((it * 19 + 3) % 256).toByte() }
        val secondBytes = ByteArray(7_113) { ((it * 61 + 13) % 256).toByte() }
        val first = completed("$namePrefix.bin", firstBytes)
        assertEquals(ExportResult.SUCCESS, runBlocking { manager.exportToDownloads(first.id) })
        val firstUri = rows().single { it.uri !in rowsBefore }.uri
        val second = completed(first.fileName, secondBytes)
        assertEquals(ExportResult.SUCCESS, runBlocking { manager.exportToDownloads(second.id) })
        val exported = rows().filterNot { it.uri in rowsBefore }
        assertEquals(2, exported.size)
        val secondUri = exported.single { it.uri != firstUri }.uri
        assertNotEquals(firstUri, secondUri)
        assertArrayEquals(firstBytes, resolver.openInputStream(firstUri)!!.use { it.readBytes() })
        assertArrayEquals(secondBytes, resolver.openInputStream(secondUri)!!.use { it.readBytes() })
        assertArrayEquals(firstBytes, payload(first).readBytes())
        assertArrayEquals(secondBytes, payload(second).readBytes())
        assertTrue(exported.all { it.pending == 0 })
    }

    @Test
    fun failedRealOutputWriteDeletesOnlyItsNewPendingRow() {
        val bytes = ByteArray(1_024) { it.toByte() }
        val item = completed("$namePrefix-source.bin", bytes)
        val protected = publishProtectedFile(item)
        val beforeFailure = rows().map { it.uri }.toSet()
        var observedPending: Uri? = null
        val result = runBlocking {
            MediaStoreDownloadDestination(resolver).writeNew("$namePrefix-failed.bin", item.mimeType) { output ->
                output.write(bytes, 0, 128)
                output.flush()
                val row = rows().single { it.uri !in beforeFailure }
                observedPending = row.uri
                assertEquals(1, row.pending)
                throw IOException("Deterministic fixture write failure after real MediaStore insertion")
            }
        }
        assertEquals(ExportResult.FAILED, result)
        assertNotNull("The test must fail after insertion rather than before it", observedPending)
        assertEquals(beforeFailure, rows().map { it.uri }.toSet())
        assertArrayEquals(bytes, resolver.openInputStream(protected)!!.use { it.readBytes() })
        assertArrayEquals(bytes, payload(item).readBytes())
    }

    @Test
    fun cancelledRealOutputWriteDeletesOnlyItsNewPendingRow() = runBlocking {
        val bytes = ByteArray(1_024) { ((it * 31 + 9) % 256).toByte() }
        val item = completed("$namePrefix-source.bin", bytes)
        val protected = publishProtectedFile(item)
        val beforeCancellation = rows().map { it.uri }.toSet()
        val inserted = CompletableDeferred<Uri>()
        val outcome = AtomicReference<ExportResult?>(null)
        val copying = launch(Dispatchers.IO) {
            outcome.set(MediaStoreDownloadDestination(resolver).writeNew("$namePrefix-cancelled.bin", item.mimeType) { output ->
                output.write(bytes, 0, 128)
                output.flush()
                val row = rows().single { it.uri !in beforeCancellation }
                assertEquals(1, row.pending)
                inserted.complete(row.uri)
                awaitCancellation()
            })
        }
        try {
            withTimeout(10_000) { inserted.await(); copying.cancelAndJoin() }
            assertEquals(ExportResult.CANCELLED, outcome.get())
            assertEquals(beforeCancellation, rows().map { it.uri }.toSet())
            assertArrayEquals(bytes, resolver.openInputStream(protected)!!.use { it.readBytes() })
            assertArrayEquals(bytes, payload(item).readBytes())
        } finally {
            copying.cancelAndJoin()
        }
    }

    private fun publishProtectedFile(item: DownloadItem): Uri {
        assertEquals(ExportResult.SUCCESS, runBlocking { manager.exportToDownloads(item.id) })
        return rows().single { it.uri !in rowsBefore }.uri
    }

    private fun completed(fileName: String, bytes: ByteArray): DownloadItem {
        val response = WebResponse.Builder("https://example.com/export-fixture").statusCode(200)
            .header("Content-Type", "application/octet-stream")
            .header("Content-Disposition", "attachment; filename=\"$fileName\"")
            .header("Content-Length", bytes.size.toString()).body(ByteArrayInputStream(bytes)).build()
        val id = manager.offer(response)!!
        assertTrue(manager.start(id))
        await { manager.downloads.value.any { it.id == id && it.status == DownloadStatus.COMPLETED } }
        return manager.downloads.value.single { it.id == id }
    }

    private data class Row(val uri: Uri, val name: String, val mime: String, val relativePath: String, val pending: Int, val size: Long)

    private fun rows(): List<Row> {
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE)
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(MediaStoreDownloadDestination.RELATIVE_PATH, "$namePrefix%"))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return resolver.query(collection, columns, query, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Row(ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getInt(4), cursor.getLong(5)))
            }
        } ?: error("The real MediaStore query must return a cursor")
    }

    private fun payload(item: DownloadItem): File = File(root, "downloads/files/${item.id}/${item.fileName}")
    private fun await(condition: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!condition()) delay(10) } }
}
