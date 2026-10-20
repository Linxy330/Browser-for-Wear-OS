package com.kenny.browserforwearos

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kenny.browserforwearos.data.download.PreparedUpload
import com.kenny.browserforwearos.data.download.UploadLimits
import com.kenny.browserforwearos.data.download.UploadPreparation
import com.kenny.browserforwearos.data.download.UploadPreparationError
import com.kenny.browserforwearos.data.download.UploadStager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual ContentResolver streams from FileProvider, with no legacy `_data` column. */
@RunWith(AndroidJUnit4::class)
class EmulatorUploadStagerTest {
    private lateinit var target: Context
    private lateinit var context: Context
    private lateinit var cacheRoot: File
    private lateinit var unrelated: File
    private val sourceDirectories = mutableListOf<File>()
    private val leases = mutableListOf<PreparedUpload>()
    private val preparedDirectories = mutableSetOf<File>()
    private val limits = UploadLimits(
        maxFiles = 10,
        maxFileBytes = 1024L * 1024,
        maxSelectionBytes = 2L * 1024 * 1024,
        maxCacheBytes = 4L * 1024 * 1024,
        minimumFreeBytes = 0,
        readTimeoutMs = 2_000,
        prepareTimeoutMs = 10_000,
    )

    @Before
    fun requireEmulatorBeforeCreatingAnyFixture() {
        assertTrue("Upload staging tests refuse physical devices", Build.HARDWARE in setOf("ranchu", "goldfish"))
        target = InstrumentationRegistry.getInstrumentation().targetContext
        cacheRoot = File(target.cacheDir, "upload-stager-test-${UUID.randomUUID()}")
        assertTrue(cacheRoot.mkdirs())
        unrelated = File(cacheRoot, "unrelated-test-owned.txt").apply { writeText("keep unrelated cache") }
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = cacheRoot
        }
    }

    @After
    fun removeOnlyThisCasesSourcesAndCache() {
        if (!::cacheRoot.isInitialized) return
        try {
            leases.forEach { it.close() }
            await { preparedDirectories.none { it.exists() } }
        } finally {
            sourceDirectories.forEach { it.deleteRecursively() }
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun realContentStreamBecomesPrivateFileUriWithDisplayNameAndExactBytes() {
        val source = source("证明-actual.bin", bytes(96 * 1024 + 13, 41))
        val lease = success(UploadStager(context, limits), source.uri)
        assertEquals(1, lease.uris.size)
        val staged = stagedFile(lease.uris.single())
        val directory = leaseDirectory(lease.uris.single())
        assertEquals(source.file.name, staged.name)
        assertNotEquals(source.file.canonicalPath, staged.canonicalPath)
        assertArrayEquals(source.bytes, target.contentResolver.openInputStream(lease.uris.single())!!.use { it.readBytes() })
        assertArrayEquals(source.bytes, staged.readBytes())
        assertArrayEquals(source.bytes, source.file.readBytes())

        lease.close()
        lease.close()
        await { !directory.exists() }
        assertArrayEquals(source.bytes, source.file.readBytes())
        assertEquals("keep unrelated cache", unrelated.readText())
    }

    @Test
    fun differentContentUrisWithSameDisplayNameKeepTwoIndependentFiles() {
        val first = source("same-name.bin", bytes(4_097, 19))
        val second = source("same-name.bin", bytes(7_113, 61))
        assertNotEquals(first.uri, second.uri)
        val lease = success(UploadStager(context, limits), first.uri, second.uri)
        assertEquals(2, lease.uris.size)
        assertEquals(2, lease.uris.distinct().size)
        val files = lease.uris.map(::stagedFile)
        assertEquals(listOf("same-name.bin", "same-name.bin"), files.map { it.name })
        assertNotEquals(files[0].parentFile, files[1].parentFile)
        assertEquals(leaseDirectory(lease.uris[0]), leaseDirectory(lease.uris[1]))
        assertArrayEquals(first.bytes, files[0].readBytes())
        assertArrayEquals(second.bytes, files[1].readBytes())
        assertArrayEquals(first.bytes, first.file.readBytes())
        assertArrayEquals(second.bytes, second.file.readBytes())
    }

    @Test
    fun perFileLimitRejectsRealProviderMetadataAndRemovesAttemptDirectory() {
        val source = source("too-large.bin", bytes(2_049, 17))
        val result = prepare(UploadStager(context, limits.copy(maxFileBytes = 2_048)), listOf(source.uri))
        assertNull(result.lease)
        assertEquals(UploadPreparationError.FILE_LIMIT, result.error)
        await { liveLeaseDirectories().isEmpty() }
        assertArrayEquals(source.bytes, source.file.readBytes())
        assertEquals("keep unrelated cache", unrelated.readText())
    }

    @Test
    fun combinedSelectionLimitRejectsWithoutRetainingEarlierCopiedFile() {
        val first = source("first.bin", bytes(1_501, 23))
        val second = source("second.bin", bytes(1_503, 29))
        val result = prepare(UploadStager(context, limits.copy(maxFileBytes = 2_048, maxSelectionBytes = 2_500)),
            listOf(first.uri, second.uri))
        assertNull(result.lease)
        assertEquals(UploadPreparationError.TOTAL_LIMIT, result.error)
        await { liveLeaseDirectories().isEmpty() }
        assertArrayEquals(first.bytes, first.file.readBytes())
        assertArrayEquals(second.bytes, second.file.readBytes())
        assertEquals("keep unrelated cache", unrelated.readText())
    }

    @Test
    fun replacementStagerCannotPruneFilesStillHeldByEarlierLease() {
        val first = source("still-in-dom.bin", bytes(6_113, 31))
        val original = success(UploadStager(context, limits), first.uri)
        val originalFile = stagedFile(original.uris.single())
        val originalDirectory = leaseDirectory(original.uris.single())

        val replacement = UploadStager(context, limits)
        assertArrayEquals(first.bytes, originalFile.readBytes())
        val second = source("next-selection.bin", bytes(3_117, 37))
        val newer = success(replacement, second.uri)
        val newerDirectory = leaseDirectory(newer.uris.single())
        assertNotEquals(originalDirectory, newerDirectory)
        assertArrayEquals(first.bytes, originalFile.readBytes())
        assertArrayEquals(second.bytes, stagedFile(newer.uris.single()).readBytes())

        newer.close()
        await { !newerDirectory.exists() }
        assertTrue(originalDirectory.isDirectory)
        assertArrayEquals(first.bytes, originalFile.readBytes())
        assertEquals(setOf(originalDirectory), liveLeaseDirectories())
        original.close()
        await { !originalDirectory.exists() }
    }

    @Test
    fun cacheQuotaIncludesLiveLeaseFromEarlierStagerAndPreservesItOnRejection() {
        val restricted = limits.copy(maxFileBytes = 4_096, maxSelectionBytes = 4_096, maxCacheBytes = 4_096)
        val first = source("protected.bin", bytes(3_072, 43))
        val original = success(UploadStager(context, restricted), first.uri)
        val originalFile = stagedFile(original.uris.single())
        val originalDirectory = leaseDirectory(original.uris.single())
        val second = source("over-cache-quota.bin", bytes(2_048, 47))
        val result = prepare(UploadStager(context, restricted), listOf(second.uri))
        assertNull(result.lease)
        assertEquals(UploadPreparationError.STORAGE_LIMIT, result.error)
        await { liveLeaseDirectories() == setOf(originalDirectory) }
        assertArrayEquals(first.bytes, originalFile.readBytes())
        assertArrayEquals(first.bytes, first.file.readBytes())
        assertArrayEquals(second.bytes, second.file.readBytes())
    }

    @Test
    fun startupDeletesRecognizedOrphanButPreservesUnmarkedAndUnknownDirectories() {
        val uploadRoot = File(cacheRoot, "uploads-v1")
        assertTrue(uploadRoot.mkdir())
        val orphan = File(uploadRoot, "lease-${UUID.randomUUID()}").apply { assertTrue(mkdir()) }
        File(orphan, ".owner").writeText(UUID.randomUUID().toString(), Charsets.US_ASCII)
        File(orphan, "old-payload.bin").writeBytes(bytes(1_113, 59))
        val unmarked = File(uploadRoot, "lease-${UUID.randomUUID()}").apply { assertTrue(mkdir()) }
        val unmarkedPayload = File(unmarked, "keep-without-owner.bin").apply { writeBytes(bytes(127, 67)) }
        val unknown = File(uploadRoot, "unknown-test-owned-${UUID.randomUUID()}").apply { assertTrue(mkdir()) }
        val unknownPayload = File(unknown, "keep-unknown.bin").apply { writeBytes(bytes(131, 71)) }
        val source = source("new-process-selection.bin", bytes(2_113, 73))

        val lease = success(UploadStager(context, limits), source.uri)
        await { !orphan.exists() }
        assertArrayEquals(source.bytes, stagedFile(lease.uris.single()).readBytes())
        assertTrue(unmarked.isDirectory)
        assertArrayEquals(bytes(127, 67), unmarkedPayload.readBytes())
        assertTrue(unknown.isDirectory)
        assertArrayEquals(bytes(131, 71), unknownPayload.readBytes())
        assertEquals("keep unrelated cache", unrelated.readText())

        val directory = leaseDirectory(lease.uris.single())
        lease.close()
        await { !directory.exists() }
        assertArrayEquals(bytes(127, 67), unmarkedPayload.readBytes())
        assertArrayEquals(bytes(131, 71), unknownPayload.readBytes())
    }

    @Test
    fun callerCancelledBeforePrepareCannotCreateALeaseOrLeaveStagedFiles() = runBlocking {
        val source = source("never-started.bin", bytes(8_193, 53))
        val stager = UploadStager(context, limits)
        var outcome: UploadPreparation? = null
        var entered = false
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            entered = true
            // Cancellation precedes prepare itself; this case does not claim to
            // simulate interruption inside a remote or blocked provider read.
            outcome = stager.prepare(listOf(source.uri))
            outcome?.lease?.let { leases += it; preparedDirectories += leaseDirectory(it.uris.first()) }
        }
        caller.join()
        assertTrue(entered)
        assertTrue(caller.isCancelled)
        assertNull(outcome?.lease)
        outcome?.let { assertEquals(UploadPreparationError.CANCELLED, it.error) }
        withTimeout(5_000) { while (liveLeaseDirectories().isNotEmpty()) delay(10) }
        assertArrayEquals(source.bytes, source.file.readBytes())
        assertEquals("keep unrelated cache", unrelated.readText())
    }

    private data class Source(val file: File, val uri: Uri, val bytes: ByteArray)

    private fun source(name: String, bytes: ByteArray): Source {
        // Only this UUID directory is ever removed from the real FileProvider root.
        val directory = File(target.filesDir, "downloads/files/${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        sourceDirectories += directory
        val file = File(directory, name).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(target, target.packageName + ".downloads", file)
        assertEquals("content", uri.scheme)
        target.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("The fixture must reproduce SAF without a filesystem-path column", -1, cursor.getColumnIndex("_data"))
            assertEquals(name, cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(bytes.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        assertArrayEquals(bytes, target.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        return Source(file, uri, bytes)
    }

    private fun prepare(stager: UploadStager, uris: List<Uri>): UploadPreparation = runBlocking {
        withTimeout(15_000) { stager.prepare(uris) }
    }

    private fun success(stager: UploadStager, vararg uris: Uri): PreparedUpload {
        val result = prepare(stager, uris.toList())
        assertNull("Staging failed: ${result.error}", result.error)
        val lease = requireNotNull(result.lease) { "Successful staging must return a lease" }
        leases += lease
        assertEquals(uris.size, lease.uris.size)
        lease.uris.forEach { preparedDirectories += leaseDirectory(it) }
        return lease
    }

    private fun stagedFile(uri: Uri): File {
        assertEquals("file", uri.scheme)
        assertTrue(uri.authority.isNullOrEmpty())
        val file = File(requireNotNull(uri.path)).canonicalFile
        assertTrue("Staged upload must remain in this case's private cache", file.path.startsWith(cacheRoot.canonicalPath + File.separator))
        assertTrue(file.isFile)
        return file
    }

    private fun leaseDirectory(uri: Uri): File = stagedFile(uri).parentFile!!.parentFile!!.also {
        assertTrue(it.name.startsWith("lease-"))
        assertEquals(File(cacheRoot, "uploads-v1").canonicalFile, it.parentFile)
    }

    private fun liveLeaseDirectories(): Set<File> = File(cacheRoot, "uploads-v1").listFiles().orEmpty()
        .filter { it.name.startsWith("lease-") }.map { it.canonicalFile }.toSet()

    private fun bytes(size: Int, factor: Int): ByteArray = ByteArray(size) { ((it * factor + 7) % 256).toByte() }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }
}
