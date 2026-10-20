package com.kenny.browserforwearos.data.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads stay in app-private storage until explicit export. This manager never
 * creates a service, schedules an automatic retry, or reads a response before confirmation.
 * Call setForeground(false) on app backgrounding, and close() when its owner is cleared.
 */
class BrowserDownloadManager(
    context: Context,
    ownerScope: CoroutineScope,
    private val fetcher: DownloadResponseFetcher? = null,
    private val limits: DownloadLimits = DownloadLimits(),
) : AutoCloseable {
    private val app = context.applicationContext
    private val storage = DownloadStorage(app, limits)
    private val lock = Any()
    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()
    private class Transfer(var response: WebResponse? = null, var job: Job? = null, var expiry: Job? = null)
    private val transfers = mutableMapOf<String, Transfer>()
    private val retiringJobs = mutableMapOf<String, Job>()
    private val reservedBytes = mutableMapOf<String, Long>()
    private var storedBytes = 0L
    private var foreground = true
    private var closed = false
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closeExecutor = ThreadPoolExecutor(1, 2, 15, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { task -> Thread(task, "BrowserDownloadClose").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
    private data class PendingIndex(val items: List<DownloadItem>, val finished: CompletableDeferred<Unit>)
    private val writes = Channel<PendingIndex>(Channel.CONFLATED, onUndeliveredElement = { it.finished.complete(Unit) })
    private val ownerHandle = ownerScope.coroutineContext[Job]?.invokeOnCompletion { close() }

    init {
        storageScope.launch {
            try {
                for (write in writes) {
                    try {
                        storage.save(write.items)
                    } catch (_: Exception) {
                        emit(DownloadEventType.FAILED, error = DownloadError.IO_ERROR)
                    } finally {
                        write.finished.complete(Unit)
                    }
                }
            } finally {
                storageScope.cancel()
            }
        }
        workScope.launch {
            try {
                val loaded = storage.load()
                storage.cleanupParts()
                val restored = loaded.items.map { item ->
                    when {
                        item.status in setOf(DownloadStatus.WAITING, DownloadStatus.DOWNLOADING) -> item.copy(status = DownloadStatus.CANCELLED, receivedBytes = 0, error = DownloadError.INTERRUPTED)
                        item.status == DownloadStatus.COMPLETED && (!storage.payload(item).isFile || storage.payload(item).length() != item.receivedBytes) -> item.copy(status = DownloadStatus.FAILED, receivedBytes = 0, error = DownloadError.MISSING_FILE)
                        else -> item
                    }
                }
                val diskUsage = storage.storedBytes()
                synchronized(lock) {
                    if (closed) return@launch
                    storedBytes = diskUsage
                    _downloads.value = restored
                    _ready.value = true
                    persistLocked()
                }
                if (loaded.recovered) emit(DownloadEventType.INDEX_RECOVERED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) { if (!closed) _ready.value = true }
                emit(DownloadEventType.FAILED, error = DownloadError.IO_ERROR)
            }
        }
    }

    /** Ownership of response.body transfers here even on rejection. No body bytes are read. */
    fun offer(response: WebResponse, privateMode: Boolean = false): String? {
        if (response.body == null || response.statusCode !in 200..299) {
            closeBody(response)
            emit(DownloadEventType.FAILED, error = if (response.body == null) DownloadError.NO_BODY else DownloadError.HTTP_ERROR)
            return null
        }
        val id = UUID.randomUUID().toString()
        synchronized(lock) {
            if (closed || !foreground || !_ready.value || _downloads.value.size >= limits.maxRecords || transfers.values.count { it.job == null } >= 3) {
                closeBody(response)
                emit(DownloadEventType.CAPACITY_REACHED, error = DownloadError.BUSY)
                return null
            }
            val item = DownloadItem(id, DownloadPolicy.fileName(response.headers, response.uri),
                DownloadPolicy.mimeType(DownloadPolicy.header(response.headers, "content-type")), DownloadPolicy.sourceHost(response.uri),
                DownloadPolicy.totalBytes(response.headers), 0, DownloadStatus.WAITING, System.currentTimeMillis(),
                sourceUrl = if (privateMode) null else DownloadPolicy.sourceUrl(response.uri))
            val transfer = Transfer(response)
            transfers[id] = transfer
            _downloads.value = listOf(item) + _downloads.value
            transfer.expiry = workScope.launch {
                delay(limits.confirmationTimeoutMs)
                cancelInternal(id, DownloadError.EXPIRED, expired = true)
            }
            persistLocked()
        }
        emit(DownloadEventType.OFFERED, id)
        return id
    }

    fun start(id: String): Boolean = begin(id, retry = false)
    fun retry(id: String): Boolean = begin(id, retry = true)
    fun cancel(id: String): Boolean = cancelInternal(id, null)

    private fun begin(id: String, retry: Boolean): Boolean {
        val task: Job
        synchronized(lock) {
            val item = _downloads.value.firstOrNull { it.id == id } ?: return false
            if (closed || !foreground || !_ready.value || retiringJobs[id]?.isCompleted == false ||
                transfers.values.count { it.job != null } + retiringJobs.values.count { !it.isCompleted } >= limits.maxConcurrent) {
                emit(DownloadEventType.CAPACITY_REACHED, id, DownloadError.BUSY)
                return false
            }
            if (retry && (!item.canRetry || fetcher == null)) return false
            if (!retry && item.status != DownloadStatus.WAITING) return false
            val transfer = if (retry) Transfer() else transfers[id] ?: return false
            transfer.expiry?.cancel()
            transfer.expiry = null
            reservedBytes[id] = 0
            updateLocked(id) { it.copy(status = DownloadStatus.DOWNLOADING, receivedBytes = 0, error = null) }
            task = workScope.launch(start = CoroutineStart.LAZY) { transfer(id, transfer, retry) }
            transfer.job = task
            transfers[id] = transfer
            persistLocked()
        }
        emit(DownloadEventType.STARTED, id)
        task.start()
        return true
    }

    private suspend fun transfer(id: String, control: Transfer, retry: Boolean) {
        var response: WebResponse? = control.response
        var part: File? = null
        var target: File? = null
        var completed = false
        var moved = false
        try {
            val original = synchronized(lock) { _downloads.value.firstOrNull { it.id == id } } ?: return
            if (retry) response = withTimeout(limits.fetchTimeoutMs) { checkNotNull(fetcher).fetch(checkNotNull(original.sourceUrl)) }
            val actual = checkNotNull(response)
            synchronized(lock) { control.response = actual }
            coroutineContext.ensureActive()
            if (actual.statusCode !in 200..299) throw DownloadFailure(DownloadError.HTTP_ERROR)
            val input = actual.body ?: throw DownloadFailure(DownloadError.NO_BODY)
            actual.setReadTimeoutMillis(limits.readTimeoutMs)
            val expected = DownloadPolicy.totalBytes(actual.headers)
            if (expected != null && expected > limits.maxFileBytes) throw DownloadFailure(DownloadError.SIZE_LIMIT)
            if (storage.freeBytes() < limits.minimumFreeBytes + (expected ?: 32 * 1024L)) throw DownloadFailure(DownloadError.STORAGE_LIMIT)
            synchronized(lock) {
                if (transfers[id] !== control) throw CancellationException()
                updateLocked(id) { it.copy(totalBytes = expected) }
                persistLocked()
            }
            val directory = storage.directory(id)
            if (!directory.isDirectory && !directory.mkdirs()) throw DownloadFailure(DownloadError.IO_ERROR)
            part = storage.part(id)
            target = storage.payload(original)
            val context = coroutineContext
            var lastProgress = System.nanoTime()
            var lastCheckpoint = lastProgress
            val bytes = input.use { body ->
                FileOutputStream(part).use { output ->
                    val count = DownloadCopier.copy(body, output, limits.maxFileBytes, expected, limits.transferTimeoutMs,
                        checkCancellation = { context.ensureActive() },
                        reserveBytes = { count -> synchronized(lock) {
                            if (closed || transfers[id] !== control) throw CancellationException()
                            val reserved = reservedBytes.values.sum()
                            if (count > limits.maxStoredBytes - storedBytes - reserved) throw DownloadFailure(DownloadError.STORAGE_LIMIT)
                            reservedBytes[id] = (reservedBytes[id] ?: 0) + count
                        } },
                        progress = { count ->
                            val now = System.nanoTime()
                            if ((now - lastProgress) / 1_000_000 >= 200) {
                                synchronized(lock) {
                                    if (transfers[id] === control) {
                                        updateLocked(id) { it.copy(receivedBytes = count) }
                                        if ((now - lastCheckpoint) / 1_000_000 >= 2_000) { persistLocked(); lastCheckpoint = now }
                                    }
                                }
                                lastProgress = now
                            }
                        })
                    output.flush()
                    output.fd.sync()
                    count
                }
            }
            coroutineContext.ensureActive()
            if (!part.renameTo(target)) throw DownloadFailure(DownloadError.IO_ERROR)
            moved = true
            synchronized(lock) {
                if (closed || transfers[id] !== control) throw CancellationException()
                reservedBytes.remove(id)
                storedBytes += bytes
                updateLocked(id) { it.copy(status = DownloadStatus.COMPLETED, receivedBytes = bytes, totalBytes = bytes, error = null) }
                transfers.remove(id)
                completed = true
                persistLocked()
            }
            emit(DownloadEventType.COMPLETED, id)
        } catch (_: TimeoutCancellationException) {
            failTransfer(id, control, DownloadError.READ_TIMEOUT)
        } catch (_: CancellationException) {
            failTransfer(id, control, DownloadError.INTERRUPTED, cancelled = true)
        } catch (failure: DownloadFailure) {
            failTransfer(id, control, failure.reason)
        } catch (_: SocketTimeoutException) {
            failTransfer(id, control, DownloadError.READ_TIMEOUT)
        } catch (_: Exception) {
            failTransfer(id, control, DownloadError.IO_ERROR)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { response?.body?.close() }
                if (!completed) {
                    runCatching { part?.delete() }
                    if (moved) runCatching { target?.delete() }
                }
            }
        }
    }

    private fun failTransfer(id: String, control: Transfer, error: DownloadError, cancelled: Boolean = false) {
        synchronized(lock) {
            if (transfers[id] !== control) return
            transfers.remove(id)
            retireLocked(id, control.job)
            updateLocked(id) { it.copy(status = if (cancelled) DownloadStatus.CANCELLED else DownloadStatus.FAILED, receivedBytes = 0, error = error) }
            persistLocked()
        }
        emit(if (cancelled) DownloadEventType.CANCELLED else DownloadEventType.FAILED, id, error)
    }

    private fun cancelInternal(id: String, error: DownloadError?, expired: Boolean = false): Boolean {
        val transfer = synchronized(lock) {
            val current = transfers.remove(id) ?: return false
            retireLocked(id, current.job)
            updateLocked(id) { it.copy(status = if (expired) DownloadStatus.FAILED else DownloadStatus.CANCELLED, receivedBytes = 0, error = error) }
            persistLocked()
            current
        }
        transfer.expiry?.cancel()
        transfer.job?.cancel()
        closeBody(transfer.response)
        emit(if (expired) DownloadEventType.FAILED else DownloadEventType.CANCELLED, id, error)
        return true
    }

    fun delete(id: String) {
        cancel(id)
        synchronized(lock) {
            if (closed) return
            val removed = _downloads.value.firstOrNull { it.id == id } ?: return
            try {
                closeExecutor.execute {
                    runCatching {
                        val file = storage.payload(removed)
                        val bytes = if (file.isFile) file.length() else 0
                        val deleted = storage.directory(id).deleteRecursively()
                        if (!deleted) throw DownloadFailure(DownloadError.IO_ERROR)
                        synchronized(lock) { storedBytes = (storedBytes - bytes).coerceAtLeast(0) }
                        emit(DownloadEventType.DELETED, id)
                    }.onFailure { emit(DownloadEventType.FAILED, id, DownloadError.IO_ERROR) }
                }
            } catch (_: RejectedExecutionException) {
                emit(DownloadEventType.FAILED, id, DownloadError.BUSY)
                return
            }
            _downloads.value = _downloads.value.filterNot { it.id == id }
            persistLocked()
        }
    }

    /** A retry cannot reuse the same file until the previous worker's finally has run. */
    private fun retireLocked(id: String, job: Job?) {
        if (job == null) { reservedBytes.remove(id); return }
        retiringJobs[id] = job
        job.invokeOnCompletion {
            synchronized(lock) {
                if (retiringJobs[id] === job) {
                    retiringJobs.remove(id)
                    reservedBytes.remove(id)
                }
            }
        }
    }

    fun setForeground(active: Boolean) {
        val ids = synchronized(lock) { foreground = active; if (!active) transfers.keys.toList() else emptyList() }
        ids.forEach { cancelInternal(it, DownloadError.INTERRUPTED) }
    }

    /** Gecko 158 needs a local path; this URI stays inside the app, never in an external Intent. */
    suspend fun uploadUri(id: String): Uri? = withContext(Dispatchers.IO) {
        val item = synchronized(lock) { _downloads.value.firstOrNull { it.id == id && it.status == DownloadStatus.COMPLETED } } ?: return@withContext null
        val file = storage.payload(item)
        if (!file.isFile || !file.canRead() || file.length() != item.receivedBytes) return@withContext null
        Uri.fromFile(file)
    }

    fun createExportIntent(id: String): Intent? {
        val item = synchronized(lock) { _downloads.value.firstOrNull { it.id == id && it.status == DownloadStatus.COMPLETED } } ?: return null
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(item.mimeType).putExtra(Intent.EXTRA_TITLE, item.fileName)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return intent.takeIf { it.resolveActivity(app.packageManager) != null }
    }

    /** Creates a fresh public Download/Wear Browser item; no picker or storage permission is needed. */
    suspend fun exportToDownloads(id: String): ExportResult {
        val item = synchronized(lock) {
            if (closed) null else _downloads.value.firstOrNull { it.id == id && it.status == DownloadStatus.COMPLETED }
        } ?: return ExportResult.NOT_FOUND
        val file = try {
            withContext(Dispatchers.IO) {
                storage.payload(item).takeIf { it.isFile && it.length() == item.receivedBytes }
            }
        } catch (_: CancellationException) {
            return ExportResult.CANCELLED
        } catch (_: Exception) {
            emit(DownloadEventType.EXPORT_FAILED, id)
            return ExportResult.FAILED
        } ?: return ExportResult.NOT_FOUND
        val outcome = MediaStoreDownloadDestination(app.contentResolver).writeNew(item.fileName, item.mimeType) { output ->
            val context = coroutineContext
            file.inputStream().use { input ->
                if (input.channel.size() != item.receivedBytes) throw DownloadFailure(DownloadError.MISSING_FILE)
                DownloadCopier.copy(input, output, limits.maxFileBytes, item.receivedBytes, limits.transferTimeoutMs,
                    checkCancellation = { context.ensureActive() })
            }
        }
        when (outcome) {
            ExportResult.SUCCESS -> emit(DownloadEventType.EXPORTED, id)
            ExportResult.FAILED -> emit(DownloadEventType.EXPORT_FAILED, id)
            else -> Unit
        }
        return outcome
    }

    suspend fun export(id: String, target: Uri): ExportResult {
        if (target.scheme != "content" || target.authority == "${app.packageName}.downloads") return ExportResult.INVALID_TARGET
        return try {
            withContext(Dispatchers.IO) {
                val item = synchronized(lock) { _downloads.value.firstOrNull { it.id == id && it.status == DownloadStatus.COMPLETED } } ?: return@withContext ExportResult.NOT_FOUND
                val file = storage.payload(item)
                if (!file.isFile) return@withContext ExportResult.NOT_FOUND
                val context = coroutineContext
                file.inputStream().use { input ->
                    val output = app.contentResolver.openOutputStream(target, "w") ?: return@withContext ExportResult.FAILED
                    output.use {
                        DownloadCopier.copy(input, it, limits.maxFileBytes, file.length(), limits.transferTimeoutMs,
                            checkCancellation = { context.ensureActive() })
                        it.flush()
                    }
                }
                emit(DownloadEventType.EXPORTED, id)
                ExportResult.SUCCESS
            }
        } catch (_: CancellationException) {
            ExportResult.CANCELLED
        } catch (_: Exception) {
            emit(DownloadEventType.EXPORT_FAILED, id)
            ExportResult.FAILED
        }
    }

    private fun updateLocked(id: String, transform: (DownloadItem) -> DownloadItem) {
        _downloads.value = _downloads.value.map { if (it.id == id) transform(it) else it }
    }

    private fun persistLocked() {
        val write = PendingIndex(_downloads.value, storage.pendingWrite())
        if (writes.trySend(write).isFailure) write.finished.complete(Unit)
    }

    private fun emit(type: DownloadEventType, id: String? = null, error: DownloadError? = null) {
        _events.tryEmit(DownloadEvent(type, id, error))
    }

    private fun closeBody(response: WebResponse?) {
        val body = response?.body ?: return
        try { closeExecutor.execute { runCatching { body.close() } } }
        catch (_: RejectedExecutionException) { runCatching { body.close() } }
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            val copy = transfers.values.toList()
            transfers.clear()
            reservedBytes.clear()
            if (_ready.value) {
                _downloads.value = _downloads.value.map { item ->
                    if (item.status in setOf(DownloadStatus.WAITING, DownloadStatus.DOWNLOADING)) item.copy(status = DownloadStatus.CANCELLED, receivedBytes = 0, error = DownloadError.INTERRUPTED) else item
                }
                persistLocked()
            }
            copy
        }
        active.forEach { it.expiry?.cancel(); it.job?.cancel(); closeBody(it.response) }
        workScope.cancel()
        writes.close()
        closeExecutor.shutdown()
        ownerHandle?.dispose()
    }
}
