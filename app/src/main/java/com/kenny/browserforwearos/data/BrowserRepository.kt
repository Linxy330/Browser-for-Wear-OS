package com.kenny.browserforwearos.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

data class BrowserReadResult(val snapshot: BrowserSnapshot, val recoveredCorruption: Boolean = false)

/** One versioned private file, with crash-safe replace and bounded corruption recovery. */
class BrowserRepository(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "browser")
    private val file = AtomicFile(File(directory, "state.json"))
    private val fileLock = locks.getOrPut(directory.absolutePath) { Mutex() }

    /** Register synchronously when a UI snapshot is queued, before its IO writer is scheduled. */
    fun pendingWrite(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also {
        pendingWrites[directory.absolutePath] = it
    }

    suspend fun read(): BrowserReadResult = withContext(Dispatchers.IO) {
        // A replacement ViewModel must not read an old file while the previous one's
        // final snapshot is still queued on its IO dispatcher.
        while (true) {
            val pending = pendingWrites[directory.absolutePath] ?: break
            pending.await()
            if (pendingWrites.remove(directory.absolutePath, pending)) break
        }
        fileLock.withLock {
            try {
                val bytes = file.openRead().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_BYTES) throw IOException("Profile metadata exceeds size limit")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                BrowserReadResult(BrowserStateCodec.decode(bytes.toString(Charsets.UTF_8)))
            } catch (_: FileNotFoundException) {
                BrowserReadResult(BrowserSnapshot())
            } catch (_: Exception) {
                // Preserve unreadable data privately for recovery; never print its contents.
                directory.mkdirs()
                val quarantine = File(directory, "state-corrupt-${System.currentTimeMillis()}.json")
                if (!file.baseFile.renameTo(quarantine)) {
                    file.baseFile.copyTo(quarantine)
                    file.delete()
                }
                // Keep at most one prior corrupt copy rather than accumulating private browsing data.
                directory.listFiles { entry -> entry.name.startsWith("state-corrupt-") }
                    ?.filterNot { it == quarantine }?.forEach { it.delete() }
                BrowserReadResult(BrowserSnapshot(), recoveredCorruption = true)
            }
        }
    }

    suspend fun save(snapshot: BrowserSnapshot) = withContext(Dispatchers.IO) {
        fileLock.withLock {
            directory.mkdirs()
            val encoded = BrowserStateCodec.encode(snapshot).toByteArray(Charsets.UTF_8)
            if (encoded.size > MAX_BYTES) throw IOException("Profile metadata exceeds size limit")
            val stream = file.startWrite()
            try {
                stream.write(encoded)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val pendingWrites = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private const val MAX_BYTES = 2 * 1024 * 1024
    }
}

/** v0 (pre-versioned metadata) migrates to v1; unknown future schemas are isolated intact. */
internal object BrowserStateCodec {
    private const val VERSION = 1

    fun decode(json: String): BrowserSnapshot {
        val root = JSONObject(json)
        val version = root.optInt("version", 0)
        require(version in 0..VERSION) { "Unsupported profile version" }
        fun id(item: JSONObject): String = item.optString("id").ifEmpty {
            if (version == 0) UUID.randomUUID().toString() else ""
        }
        fun objects(name: String): List<JSONObject> {
            if (!root.has(name)) return emptyList()
            val array = root.getJSONArray(name)
            return (0 until array.length()).map { array.getJSONObject(it) }
        }
        return BrowserDataRules.sanitize(
            BrowserSnapshot(
                tabs = objects("tabs").map { SavedTab(id(it), it.optString("url").takeIf(String::isNotEmpty), it.optString("title")) },
                selectedTabId = root.optString("selectedTabId").takeIf(String::isNotEmpty),
                bookmarks = objects("bookmarks").map { Bookmark(id(it), it.optString("url"), it.optString("title")) },
                history = objects("history").map { HistoryEntry(id(it), it.optString("url"), it.optString("title"), it.optLong("visitedAt", 0)) },
                searchEngine = runCatching { SearchEngine.valueOf(root.optString("searchEngine", "BING")) }.getOrDefault(SearchEngine.BING),
            ),
        )
    }

    fun encode(snapshot: BrowserSnapshot): String {
        val safe = BrowserDataRules.sanitize(snapshot)
        val root = JSONObject().put("version", VERSION)
            .put("searchEngine", safe.searchEngine.name)
            .put("selectedTabId", safe.selectedTabId ?: JSONObject.NULL)
        root.put("tabs", JSONArray().apply {
            safe.tabs.forEach { tab ->
                put(JSONObject().put("id", tab.id).apply {
                    tab.url?.let { put("url", it); put("title", tab.title) }
                })
            }
        })
        root.put("bookmarks", JSONArray().apply {
            safe.bookmarks.forEach { put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title)) }
        })
        root.put("history", JSONArray().apply {
            safe.history.forEach { put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title).put("visitedAt", it.visitedAt)) }
        })
        return root.toString()
    }
}
