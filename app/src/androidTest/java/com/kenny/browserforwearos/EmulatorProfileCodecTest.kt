package com.kenny.browserforwearos

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.kenny.browserforwearos.data.Bookmark
import com.kenny.browserforwearos.data.BrowserRepository
import com.kenny.browserforwearos.data.BrowserSnapshot
import com.kenny.browserforwearos.data.HistoryEntry
import com.kenny.browserforwearos.data.SavedTab
import com.kenny.browserforwearos.data.SearchEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real Android JSON/AtomicFile coverage, restricted to isolated files on an emulator. */
class EmulatorProfileCodecTest {
    private lateinit var isolatedFiles: File
    private lateinit var repository: BrowserRepository
    private val stateFile get() = File(isolatedFiles, "browser/state.json")

    @Before
    fun requireEmulatorAndCreateIsolatedProfile() {
        // This assertion precedes any write, in addition to the runner's QEMU guard.
        assertTrue(
            "Profile codec tests must never run on a physical device",
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"),
        )
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        isolatedFiles = File(target.cacheDir, "profile-codec-test-${UUID.randomUUID()}")
        assertTrue(isolatedFiles.mkdirs())
        val isolatedContext = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolatedFiles
        }
        repository = BrowserRepository(isolatedContext)
    }

    @After
    fun removeOnlyIsolatedTestFiles() {
        if (::isolatedFiles.isInitialized) isolatedFiles.deleteRecursively()
    }

    @Test
    fun legacyV0MigratesGeneratedIdsAndPreservesTabOrderAndSelection() = runBlocking {
        writeFixture(
            """
            {
              "tabs": [
                {"id":"first","url":"https://first.example/","title":"First"},
                {"id":"blank"},
                {"id":"third","url":"https://third.example/","title":"第三页"}
              ],
              "selectedTabId":"third",
              "bookmarks":[{"url":"https://legacy.example/","title":"旧书签"}],
              "history":[{"url":"https://legacy.example/","title":"旧历史","visitedAt":42}]
            }
            """.trimIndent(),
        )
        val result = repository.read()
        assertFalse(result.recoveredCorruption)
        val migrated = result.snapshot
        assertEquals(listOf("first", "blank", "third"), migrated.tabs.map { it.id })
        assertEquals("third", migrated.selectedTabId)
        assertNull(migrated.tabs[1].url)
        assertEquals("第三页", migrated.tabs[2].title)
        assertTrue(migrated.bookmarks.single().id.isNotBlank())
        assertTrue(migrated.history.single().id.isNotBlank())
        assertEquals("旧书签", migrated.bookmarks.single().title)
        assertEquals(42L, migrated.history.single().visitedAt)
        assertEquals(SearchEngine.BING, migrated.searchEngine)

        repository.save(migrated)
        assertEquals(1, JSONObject(stateFile.readText()).getInt("version"))
        val reread = repository.read()
        assertFalse(reread.recoveredCorruption)
        // Migration-generated IDs are stable once the migrated profile is written.
        assertEquals(migrated, reread.snapshot)
    }

    @Test
    fun v1RoundTripRetainsOrderedTabsBlankCurrentTabAndChineseMetadata() = runBlocking {
        val expected = BrowserSnapshot(
            tabs = listOf(
                SavedTab("stable-a", "https://a.example/path?item=1", "第一页"),
                SavedTab("stable-blank"),
                SavedTab("stable-c", "http://c.example:8787/", "Quoted \"title\""),
            ),
            selectedTabId = "stable-blank",
            bookmarks = listOf(Bookmark("bookmark-id", "https://a.example/path?item=1", "我的书签")),
            history = listOf(
                HistoryEntry("history-new", "https://a.example/path?item=1", "最近浏览", 200),
                HistoryEntry("history-old", "http://c.example:8787/", "Earlier", 100),
            ),
            searchEngine = SearchEngine.DUCKDUCKGO,
        )
        repository.save(expected)
        val encoded = JSONObject(stateFile.readText())
        assertEquals(1, encoded.getInt("version"))
        assertEquals("stable-blank", encoded.getString("selectedTabId"))
        assertFalse("A blank tab must not store an empty or about URL", encoded.getJSONArray("tabs").getJSONObject(1).has("url"))
        val restored = repository.read()
        assertFalse(restored.recoveredCorruption)
        assertEquals(expected, restored.snapshot)
        assertTrue(quarantinedFiles().isEmpty())
    }

    @Test
    fun pendingReadWaitsForQueuedSnapshot() = runBlocking {
        val older = BrowserSnapshot(
            tabs = listOf(SavedTab("before", "https://before.example/", "Older")),
            selectedTabId = "before",
        )
        val newer = BrowserSnapshot(
            tabs = listOf(SavedTab("after", "https://after.example/", "Newest")),
            selectedTabId = "after",
            bookmarks = listOf(Bookmark("latest-bookmark", "https://after.example/", "Queued change")),
        )
        repository.save(older)
        val barrier = repository.pendingWrite()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reader = async(Dispatchers.IO) {
            started.complete(Unit)
            repository.read()
        }
        try {
            withTimeout(2_000) { started.await() }
            delay(150)
            assertFalse("A read must wait for the UI-queued snapshot, not return the older file", reader.isCompleted)
            // This must succeed while read is waiting: read must not hold the file Mutex.
            withTimeout(2_000) { repository.save(newer) }
            assertFalse("Even a finished file write waits for its explicit queue barrier", reader.isCompleted)
            barrier.complete(Unit)
            val result = withTimeout(2_000) { reader.await() }
            assertFalse(result.recoveredCorruption)
            assertEquals(newer, result.snapshot)
        } finally {
            barrier.complete(Unit)
            reader.cancel()
            withTimeout(2_000) { reader.join() }
        }
    }

    @Test
    fun unsupportedFutureVersionIsQuarantinedByteForByteBeforeEmptyRecovery() = runBlocking {
        val original = """{"version":999,"futureField":"保留原文","tabs":[{"id":"future","url":"https://future.example/"}]}"""
        writeFixture(original)
        val result = repository.read()
        assertTrue(result.recoveredCorruption)
        assertEquals(BrowserSnapshot(), result.snapshot)
        assertFalse(stateFile.exists())
        val preserved = quarantinedFiles().single()
        assertArrayEquals(original.toByteArray(Charsets.UTF_8), preserved.readBytes())

        repository.save(result.snapshot)
        assertEquals(BrowserSnapshot(), repository.read().snapshot)
        assertArrayEquals(original.toByteArray(Charsets.UTF_8), preserved.readBytes())
    }

    @Test
    fun oversizedValidJsonRecoversWithoutAcceptingUnboundedProfileAndPreservesOriginal() = runBlocking {
        stateFile.parentFile!!.mkdirs()
        stateFile.outputStream().use { output ->
            output.write("{\"version\":1,\"padding\":\"".toByteArray())
            val chunk = ByteArray(8192) { 'a'.code.toByte() }
            // Well-formed v1 JSON would otherwise decode successfully: only the size limit rejects it.
            repeat(257) { output.write(chunk) }
            output.write("\",\"tabs\":[]}".toByteArray())
        }
        val originalLength = stateFile.length()
        assertTrue(originalLength > 2L * 1024 * 1024)
        val originalDigest = digest(stateFile)
        val result = repository.read()
        assertTrue(result.recoveredCorruption)
        assertEquals(BrowserSnapshot(), result.snapshot)
        assertFalse(stateFile.exists())
        val preserved = quarantinedFiles().single()
        assertEquals(originalLength, preserved.length())
        assertArrayEquals(originalDigest, digest(preserved))
    }

    private fun writeFixture(json: String) {
        stateFile.parentFile!!.mkdirs()
        stateFile.writeText(json, Charsets.UTF_8)
    }

    private fun quarantinedFiles(): List<File> = stateFile.parentFile!!
        .listFiles { file -> file.name.startsWith("state-corrupt-") }.orEmpty().toList()

    private fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
}
