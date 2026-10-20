package com.kenny.browserforwearos.data.download

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

data class UploadLimits(
    val maxFiles: Int = 10,
    val maxFileBytes: Long = 16L * 1024 * 1024,
    val maxSelectionBytes: Long = 32L * 1024 * 1024,
    val maxCacheBytes: Long = 64L * 1024 * 1024,
    val minimumFreeBytes: Long = 32L * 1024 * 1024,
    val readTimeoutMs: Long = 15_000,
    val prepareTimeoutMs: Long = 60_000,
) {
    init {
        require(maxFiles in 1..10 && maxFileBytes > 0 && maxSelectionBytes >= maxFileBytes && maxCacheBytes >= maxSelectionBytes)
        require(minimumFreeBytes >= 0 && readTimeoutMs > 0 && prepareTimeoutMs > 0)
    }
}

enum class UploadPreparationError { INVALID_SELECTION, FILE_LIMIT, TOTAL_LIMIT, STORAGE_LIMIT, TIMEOUT, IO_ERROR, CANCELLED, BUSY }
data class UploadPreparation(val lease: PreparedUpload? = null, val error: UploadPreparationError? = null)

/** Hold until the upload finishes or its native session has closed. close() never performs main-thread I/O. */
class PreparedUpload internal constructor(val uris: List<Uri>, private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

/**
 * Gecko 158 accepts paths for normal file prompts, not arbitrary content providers.
 * Copy only explicitly selected content streams into private, leased cache files.
 * Provider calls run in a two-worker bounded pool; cancellation/timeout also closes
 * the stream and cancels the provider signal independently of the caller's scope.
 */
class UploadStager(context: Context, private val limits: UploadLimits = UploadLimits()) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val root = File(app.cacheDir, "uploads-v1")
    private val state = states.getOrPut(root.absolutePath) { CacheState(root) }

    /** Cold-start cleanup shares the bounded workers and never performs caller-thread file I/O. */
    fun cleanupOrphans() {
        try { workers.execute { runCatching { state.initialize() } } }
        catch (_: RejectedExecutionException) { /* The next prepare also initializes and cleans this cache. */ }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun prepare(uris: List<Uri>): UploadPreparation {
        if (uris.isEmpty() || uris.size > limits.maxFiles || uris.any { it.scheme != "content" || it.authority.isNullOrBlank() }) {
            return UploadPreparation(error = UploadPreparationError.INVALID_SELECTION)
        }
        val selected = uris.distinct()
        return suspendCancellableCoroutine { continuation ->
            val operation = PreparationOperation(continuation, limits.readTimeoutMs)
            operation.totalTimeout = deadlines.schedule({ operation.abort(UploadPreparationError.TIMEOUT) }, limits.prepareTimeoutMs, TimeUnit.MILLISECONDS)
            continuation.invokeOnCancellation { operation.abort(UploadPreparationError.CANCELLED) }
            if (operation.failure.get() != null) return@suspendCancellableCoroutine
            try {
                val worker = workers.submit { copySelection(selected, operation) }
                operation.worker.set(worker)
                if (operation.failure.get() != null) worker.cancel(true)
            } catch (_: RejectedExecutionException) {
                operation.finish(UploadPreparation(error = UploadPreparationError.BUSY))
            }
        }
    }

    private fun copySelection(uris: List<Uri>, operation: PreparationOperation) {
        var record: UploadRecord? = null
        var delivered = false
        try {
            operation.check()
            state.initialize()
            operation.check()
            record = state.acquire()
            val owned = record
            if (!owned.directory.mkdir()) throw UploadProblem(UploadPreparationError.IO_ERROR)
            owned.created = true
            state.reserve(owned, processToken.length.toLong(), limits)
            File(owned.directory, ".owner").writeText(processToken, Charsets.US_ASCII)
            val result = mutableListOf<Uri>()
            var total = 0L
            uris.forEachIndexed { index, uri ->
                operation.check()
                val metadata = metadata(uri, index, operation)
                if (metadata.size != null && metadata.size > limits.maxFileBytes) throw UploadProblem(UploadPreparationError.FILE_LIMIT)
                if (metadata.size != null && metadata.size > limits.maxSelectionBytes - total) throw UploadProblem(UploadPreparationError.TOTAL_LIMIT)
                val directory = File(owned.directory, index.toString())
                if (!directory.mkdir()) throw UploadProblem(UploadPreparationError.IO_ERROR)
                val target = File(directory, DownloadPolicy.safeFileName(metadata.name))
                val input = operation.provider {
                    val descriptor = resolver.openAssetFileDescriptor(uri, "r", it) ?: throw IOException("Selected content is unavailable")
                    try { descriptor.createInputStream() } catch (error: Exception) { descriptor.close(); throw error }
                }
                operation.input.set(input)
                input.use { source ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var fileBytes = 0L
                        var emptyReads = 0
                        while (true) {
                            operation.check()
                            val count = operation.provider { source.read(buffer) }
                            if (count < 0) break
                            if (count == 0) {
                                if (++emptyReads >= 8) throw IOException("Selected content returned empty reads")
                                continue
                            }
                            emptyReads = 0
                            if (count.toLong() > limits.maxFileBytes - fileBytes) throw UploadProblem(UploadPreparationError.FILE_LIMIT)
                            if (count.toLong() > limits.maxSelectionBytes - total) throw UploadProblem(UploadPreparationError.TOTAL_LIMIT)
                            state.reserve(owned, count.toLong(), limits)
                            operation.check()
                            output.write(buffer, 0, count)
                            fileBytes += count
                            total += count
                        }
                        if (metadata.size != null && fileBytes != metadata.size) throw IOException("Selected content was truncated or changed")
                        output.flush()
                    }
                }
                operation.input.compareAndSet(input, null)
                operation.check()
                result.add(Uri.fromFile(target))
            }
            operation.check()
            val lease = PreparedUpload(result.toList()) { owned.release() }
            delivered = true
            operation.finish(UploadPreparation(lease = lease))
        } catch (problem: UploadProblem) {
            operation.finish(UploadPreparation(error = problem.reason))
        } catch (_: Exception) {
            operation.finish(UploadPreparation(error = operation.failure.get() ?: UploadPreparationError.IO_ERROR))
        } finally {
            operation.input.set(null)
            if (!delivered) record?.release()
            operation.stopTimeouts()
        }
    }

    private data class Metadata(val name: String, val size: Long?)

    private fun metadata(uri: Uri, index: Int, operation: PreparationOperation): Metadata {
        val fallback = Metadata("upload-${index + 1}.bin", null)
        return try {
            operation.provider { signal ->
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, signal)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use fallback
                    val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                    Metadata(if (name >= 0) cursor.getString(name)?.takeIf { it.isNotBlank() } ?: fallback.name else fallback.name,
                        if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size).takeIf { it >= 0 } else null)
                } ?: fallback
            }
        } catch (problem: UploadProblem) {
            throw problem
        } catch (_: Exception) {
            operation.check()
            fallback // Providers need not implement metadata to provide readable file bytes.
        }
    }

    private class UploadProblem(val reason: UploadPreparationError) : IOException(reason.name)

    private class PreparationOperation(private val continuation: CancellableContinuation<UploadPreparation>, private val readTimeoutMs: Long) {
        val failure = AtomicReference<UploadPreparationError?>(null)
        val worker = AtomicReference<Future<*>?>(null)
        val input = AtomicReference<Closeable?>(null)
        private val signal = AtomicReference<CancellationSignal?>(null)
        private val completed = AtomicBoolean(false)
        private val abortQueued = AtomicBoolean(false)
        private val timeoutLock = Any()
        private var readTimeout: ScheduledFuture<*>? = null
        var totalTimeout: ScheduledFuture<*>? = null

        fun check() {
            failure.get()?.let { throw UploadProblem(it) }
            if (Thread.currentThread().isInterrupted) throw UploadProblem(UploadPreparationError.CANCELLED)
        }

        fun <T> provider(action: (CancellationSignal) -> T): T {
            check()
            val cancellation = CancellationSignal()
            signal.set(cancellation)
            synchronized(timeoutLock) {
                readTimeout = deadlines.schedule({ abort(UploadPreparationError.TIMEOUT) }, readTimeoutMs, TimeUnit.MILLISECONDS)
            }
            return try {
                check()
                val value = action(cancellation)
                try { check(); value } catch (error: Exception) {
                    if (value is Closeable) runCatching { value.close() }
                    throw error
                }
            } finally {
                signal.compareAndSet(cancellation, null)
                synchronized(timeoutLock) { readTimeout?.cancel(false); readTimeout = null }
            }
        }

        fun abort(reason: UploadPreparationError) {
            if (!failure.compareAndSet(null, reason)) return
            worker.get()?.cancel(true)
            finish(UploadPreparation(error = reason))
            if (abortQueued.compareAndSet(false, true)) {
                val closing = input.getAndSet(null)
                val cancelling = signal.getAndSet(null)
                try {
                    if (closing != null) aborts.execute { runCatching { closing.close() } }
                } catch (_: RejectedExecutionException) { /* Provider workers remain bounded and close on return. */ }
                try {
                    // A remote cancel() Binder call must not prevent a separate
                    // stream-close task from unblocking the actual provider read.
                    if (cancelling != null) signals.execute { runCatching { cancelling.cancel() } }
                } catch (_: RejectedExecutionException) { /* The independently queued stream close still runs. */ }
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        fun finish(result: UploadPreparation) {
            if (!completed.compareAndSet(false, true)) { result.lease?.close(); return }
            stopTimeouts()
            continuation.resume(result) { _, value, _ -> value.lease?.close() }
        }

        fun stopTimeouts() {
            totalTimeout?.cancel(false)
            synchronized(timeoutLock) { readTimeout?.cancel(false); readTimeout = null }
        }
    }

    private class UploadRecord(val state: CacheState, val directory: File) {
        val released = AtomicBoolean(false)
        var created = false
        var bytes = 0L
        fun release() {
            if (!released.compareAndSet(false, true)) return
            // At most MAX_LEASES records can exist process-wide, so the cleanup
            // queue has a strict upper bound and close() cannot wait on a provider.
            cleanup.execute {
                val deleted = !created || runCatching { deleteTree(directory); true }.getOrDefault(false)
                synchronized(state.lock) {
                    state.active.remove(directory.absolutePath)
                    if (deleted) state.bytes = (state.bytes - bytes).coerceAtLeast(0)
                }
                recordCount.decrementAndGet()
            }
        }
    }

    private class CacheState(private val root: File) {
        val lock = Any()
        val active = mutableSetOf<String>()
        var bytes = 0L
        private val initialization = ReentrantLock()
        private var initialized = false

        fun initialize() {
            initialization.lockInterruptibly()
            try {
                if (initialized) return
                if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create private upload cache")
                root.listFiles().orEmpty().forEach { directory ->
                    if (!leaseName.matches(directory.name) || !directory.isDirectory || directory.canonicalFile.parentFile != root.canonicalFile) return@forEach
                    if (synchronized(lock) { directory.absolutePath in active }) return@forEach
                    val marker = File(directory, ".owner")
                    if (!marker.isFile || marker.length() !in 1L..128L || marker.canonicalFile.parentFile != directory.canonicalFile) return@forEach
                    val owner = marker.readText(Charsets.US_ASCII)
                    // Other process tokens are orphaned sessions. Never expire an
                    // in-process live DOM lease, even if it is old or a new stager opens.
                    if (owner != processToken && DownloadPolicy.validId(owner)) runCatching { deleteTree(directory) }
                }
                val usage = treeBytes(root)
                synchronized(lock) { bytes = usage }
                initialized = true
            } finally {
                initialization.unlock()
            }
        }

        fun acquire(): UploadRecord {
            while (true) {
                val current = recordCount.get()
                if (current >= MAX_LEASES) throw UploadProblem(UploadPreparationError.BUSY)
                if (recordCount.compareAndSet(current, current + 1)) break
            }
            val record = UploadRecord(this, File(root, "lease-${UUID.randomUUID()}"))
            synchronized(lock) { active.add(record.directory.absolutePath) }
            return record
        }

        fun reserve(record: UploadRecord, count: Long, limits: UploadLimits) {
            if (root.usableSpace < limits.minimumFreeBytes + count) throw UploadProblem(UploadPreparationError.STORAGE_LIMIT)
            synchronized(lock) {
                if (count > limits.maxCacheBytes - bytes) throw UploadProblem(UploadPreparationError.STORAGE_LIMIT)
                bytes += count
                record.bytes += count
            }
        }
    }

    companion object {
        private const val MAX_LEASES = 16
        private val processToken = UUID.randomUUID().toString()
        private val recordCount = AtomicInteger(0)
        private val states = ConcurrentHashMap<String, CacheState>()
        private val leaseName = Regex("lease-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private fun pool(name: String, threads: Int, capacity: Int) = ThreadPoolExecutor(threads, threads, 15, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(capacity), { runnable -> Thread(runnable, name).apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
        private val workers = pool("BrowserUploadRead", 2, 2)
        private val aborts = pool("BrowserUploadAbort", 2, 8)
        private val signals = pool("BrowserUploadSignal", 2, 8)
        private val cleanup = pool("BrowserUploadCleanup", 1, MAX_LEASES * 2)
        private val deadlines = ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "BrowserUploadDeadline").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true; setKeepAliveTime(15, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }

        private fun deleteTree(directory: File) {
            if (!directory.exists()) return
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.deleteIfExists(file); return FileVisitResult.CONTINUE }
                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }

        private fun treeBytes(directory: File): Long {
            var bytes = 0L
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    bytes += attrs.size()
                    return FileVisitResult.CONTINUE
                }
            })
            return bytes
        }
    }
}
