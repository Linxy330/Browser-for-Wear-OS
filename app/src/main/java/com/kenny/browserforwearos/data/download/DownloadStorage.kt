package com.kenny.browserforwearos.data.download

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal data class DownloadIndexLoad(val items: List<DownloadItem>, val recovered: Boolean = false)

/** Metadata is excluded from the FileProvider root; only UUID payload folders are shared. */
internal class DownloadStorage(context: Context, private val limits: DownloadLimits) {
    private val root = File(context.applicationContext.filesDir, "downloads")
    private val payloadRoot = File(root, "files")
    private val index = AtomicFile(File(root, "index.json"))
    private val key = root.absolutePath
    private val mutex = locks.getOrPut(key) { Mutex() }

    fun pendingWrite(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { pending[key] = it }

    suspend fun load(): DownloadIndexLoad = withContext(Dispatchers.IO) {
        while (true) {
            val latest = pending[key] ?: break
            latest.await()
            if (pending.remove(key, latest)) break
        }
        mutex.withLock {
            try {
                val text = index.openRead().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (output.size() + read > MAX_INDEX_BYTES) throw IOException("Download index exceeds limit")
                        output.write(buffer, 0, read)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                val objectRoot = JSONObject(text)
                require(objectRoot.getInt("version") == 1)
                val entries = objectRoot.getJSONArray("items")
                require(entries.length() <= limits.maxRecords)
                val items = (0 until entries.length()).map { position ->
                    val item = entries.getJSONObject(position)
                    val id = item.getString("id")
                    require(DownloadPolicy.validId(id))
                    val status = DownloadStatus.valueOf(item.getString("status"))
                    val total = if (item.isNull("total")) null else item.getLong("total")
                    val received = item.getLong("received")
                    require(total == null || total in 0..limits.maxFileBytes)
                    require(received in 0..limits.maxFileBytes)
                    DownloadItem(
                        id = id,
                        fileName = DownloadPolicy.safeFileName(item.getString("name")),
                        mimeType = DownloadPolicy.mimeType(item.optString("mime")),
                        sourceHost = item.optString("host").filterNot(Char::isISOControl).take(253),
                        totalBytes = total,
                        receivedBytes = received,
                        status = status,
                        createdAt = item.optLong("created", 0).coerceAtLeast(0),
                        error = item.optString("error").takeIf { it.isNotEmpty() }?.let(DownloadError::valueOf),
                        sourceUrl = item.optString("url").let(DownloadPolicy::sourceUrl),
                    )
                }.distinctBy { it.id }.sortedByDescending { it.createdAt }
                DownloadIndexLoad(items)
            } catch (_: FileNotFoundException) {
                DownloadIndexLoad(emptyList())
            } catch (_: Exception) {
                root.mkdirs()
                val preserved = File(root, "index-corrupt.json")
                if (preserved.exists()) preserved.delete()
                if (index.baseFile.exists() && !index.baseFile.renameTo(preserved)) {
                    index.baseFile.copyTo(preserved, overwrite = true)
                    index.delete()
                }
                DownloadIndexLoad(emptyList(), recovered = true)
            }
        }
    }

    suspend fun save(items: List<DownloadItem>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(items.size <= limits.maxRecords)
            val array = JSONArray()
            items.forEach { item ->
                require(DownloadPolicy.validId(item.id))
                array.put(JSONObject()
                    .put("id", item.id).put("name", DownloadPolicy.safeFileName(item.fileName))
                    .put("mime", DownloadPolicy.mimeType(item.mimeType)).put("host", item.sourceHost.take(253))
                    .put("total", item.totalBytes ?: JSONObject.NULL).put("received", item.receivedBytes)
                    .put("status", item.status.name).put("created", item.createdAt)
                    .put("error", item.error?.name ?: "")
                    .put("url", item.sourceUrl?.let(DownloadPolicy::sourceUrl) ?: JSONObject.NULL))
            }
            val bytes = JSONObject().put("version", 1).put("items", array).toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_INDEX_BYTES) throw IOException("Download index exceeds limit")
            root.mkdirs()
            val stream = index.startWrite()
            try {
                stream.write(bytes)
                index.finishWrite(stream)
            } catch (error: Exception) {
                index.failWrite(stream)
                throw error
            }
        }
    }

    fun directory(id: String): File {
        require(DownloadPolicy.validId(id))
        val directory = File(payloadRoot, id)
        require(directory.canonicalFile.parentFile == payloadRoot.canonicalFile)
        return directory
    }

    fun payload(item: DownloadItem): File {
        val directory = directory(item.id)
        val file = File(directory, DownloadPolicy.safeFileName(item.fileName))
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        return file
    }

    fun part(id: String): File = File(directory(id), ".part")
    fun freeBytes(): Long = root.apply { mkdirs() }.usableSpace

    fun storedBytes(): Long {
        if (!payloadRoot.exists()) return 0
        return payloadRoot.listFiles().orEmpty().filter { DownloadPolicy.validId(it.name) }.sumOf { directory ->
            if (directory.canonicalFile.parentFile != payloadRoot.canonicalFile) 0L
            else directory.listFiles().orEmpty().filter { it.isFile && it.canonicalFile.parentFile == directory.canonicalFile }.sumOf { it.length() }
        }
    }

    fun cleanupParts() {
        payloadRoot.listFiles().orEmpty().filter { DownloadPolicy.validId(it.name) }.forEach { directory -> part(directory.name).delete() }
    }

    companion object {
        private const val MAX_INDEX_BYTES = 2 * 1024 * 1024
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    }
}
