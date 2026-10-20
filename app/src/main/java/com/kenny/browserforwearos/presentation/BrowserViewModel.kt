package com.kenny.browserforwearos.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kenny.browserforwearos.R
import com.kenny.browserforwearos.data.AddressResolver
import com.kenny.browserforwearos.data.Bookmark
import com.kenny.browserforwearos.data.BrowserDataRules
import com.kenny.browserforwearos.data.BrowserRepository
import com.kenny.browserforwearos.data.BrowserSnapshot
import com.kenny.browserforwearos.data.HistoryEntry
import com.kenny.browserforwearos.data.SavedTab
import com.kenny.browserforwearos.data.SearchEngine
import com.kenny.browserforwearos.data.TabMetadataState
import com.kenny.browserforwearos.data.ReadingPreferences
import com.kenny.browserforwearos.data.PageColorScheme
import com.kenny.browserforwearos.engine.GeckoRuntimeHolder
import com.kenny.browserforwearos.engine.TabSessionController
import com.kenny.browserforwearos.engine.newTabId
import com.kenny.browserforwearos.data.download.BrowserDownloadManager
import com.kenny.browserforwearos.data.download.UploadStager
import com.kenny.browserforwearos.data.download.GeckoDownloadFetcher
import com.kenny.browserforwearos.data.download.DownloadEventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.GeckoRuntimeSettings

data class BrowserTab(val id: String, val controller: TabSessionController)
data class IncomingBrowserRequest(val value: String, val openImmediately: Boolean, val serial: Long)

/** Owns three stable logical tabs. All Gecko operations stay on the main thread. */
class BrowserViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = BrowserRepository(app)
    private val firstController = TabSessionController(app)
    val downloadManager = BrowserDownloadManager(app, viewModelScope, GeckoDownloadFetcher(GeckoRuntimeHolder.runtime(app)))
    private val _downloadOffer = MutableStateFlow(0L)
    val downloadOffer: StateFlow<Long> = _downloadOffer.asStateFlow()
    private val _tabs = MutableStateFlow(listOf(BrowserTab(firstController.tabId, firstController)))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()
    private val _selectedTabId = MutableStateFlow<String?>(firstController.tabId)
    val selectedTabId: StateFlow<String?> = _selectedTabId.asStateFlow()
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()
    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()
    private val _searchEngine = MutableStateFlow(SearchEngine.BING)
    val searchEngine: StateFlow<SearchEngine> = _searchEngine.asStateFlow()
    private val _reading = MutableStateFlow(ReadingPreferences())
    val reading: StateFlow<ReadingPreferences> = _reading.asStateFlow()
    private val _incoming = MutableStateFlow<IncomingBrowserRequest?>(null)
    val incoming: StateFlow<IncomingBrowserRequest?> = _incoming.asStateFlow()
    private var incomingSerial = 0L
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    /** Compatibility for existing callers; Compose observes tabs and selectedTabId directly. */
    val controller: TabSessionController
        get() = _tabs.value.firstOrNull { it.id == _selectedTabId.value }?.controller ?: _tabs.value.first().controller

    private var metadata = listOf(SavedTab(firstController.tabId))
    private val observers = mutableMapOf<String, Job>()
    private data class PendingWrite(val snapshot: BrowserSnapshot, val finished: CompletableDeferred<Unit>)
    private val writes = Channel<PendingWrite>(Channel.CONFLATED, onUndeliveredElement = { it.finished.complete(Unit) })
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var foreground = false
    private var webVisible = false
    private val inactiveJobs = mutableMapOf<String, Job>()
    private val inactiveDelays = mutableMapOf<String, Long>()
    private val pendingRestores = mutableMapOf<String, String>()
    private var clearingSiteData = false

    init {
        UploadStager(app).cleanupOrphans()
        firstController.setActive(false)
        viewModelScope.launch {
            downloadManager.events.collect { event ->
                when (event.type) {
                    DownloadEventType.OFFERED -> _downloadOffer.value++
                    DownloadEventType.EXPORTED -> _feedback.value = message(R.string.download_exported)
                    DownloadEventType.EXPORT_FAILED -> _feedback.value = message(R.string.download_export_failed)
                    DownloadEventType.CAPACITY_REACHED -> _feedback.value = message(if (event.id == null) R.string.download_capacity else R.string.download_busy)
                    DownloadEventType.FAILED -> if (event.id == null) _feedback.value = message(R.string.download_io_error)
                    else -> Unit // Each transfer has its own visible status and error.
                }
            }
        }
        storageScope.launch {
            // A single writer preserves order; rapidly changing engine titles coalesce safely.
            // Closing the ViewModel closes the channel, allowing the last atomic write to drain.
            try {
                for (write in writes) {
                    try {
                        repository.save(write.snapshot)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _feedback.value = message(R.string.data_save_failed)
                    } finally {
                        write.finished.complete(Unit)
                    }
                }
            } finally {
                storageScope.cancel()
            }
        }
        viewModelScope.launch {
            val snapshot = try {
                val result = repository.read()
                if (result.recoveredCorruption) _feedback.value = message(R.string.data_corrupt_recovered)
                result.snapshot
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _feedback.value = message(R.string.data_read_failed)
                BrowserSnapshot()
            }
            restore(snapshot)
            _ready.value = true
            persist()
            applyActivity()
        }
    }

    fun submitAddress(input: String): Boolean {
        if (!_ready.value) return false
        val target = AddressResolver.resolve(input, _searchEngine.value)
        if (target == null) {
            _feedback.value = message(R.string.data_invalid_address)
            return false
        }
        pendingRestores.remove(_selectedTabId.value)
        controller.loadUri(target)
        return true
    }

    fun newTab(privateMode: Boolean = false): Boolean {
        if (!_ready.value) return false
        if (_tabs.value.size >= BrowserDataRules.MAX_TABS) {
            _feedback.value = message(R.string.data_tab_limit)
            return false
        }
        val added = createTab(newTabId(), privateMode = privateMode)
        metadata = tabState().add(added.id)!!.tabs
        _tabs.value = _tabs.value + added
        _selectedTabId.value = added.id
        applyActivity()
        persist()
        return true
    }

    fun selectTab(id: String) {
        if (!_ready.value || _tabs.value.none { it.id == id }) return
        _selectedTabId.value = id
        applyActivity()
        persist()
    }

    fun closeTab(id: String) {
        if (!_ready.value) return
        val removed = _tabs.value.firstOrNull { it.id == id } ?: return
        val next = tabState().close(id, newTabId())
        observers.remove(id)?.cancel()
        inactiveJobs.remove(id)?.cancel()
        inactiveDelays.remove(id)
        pendingRestores.remove(id)
        removed.controller.close()
        metadata = next.tabs
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = if (remaining.isEmpty()) listOf(createTab(next.selectedTabId)) else remaining
        _selectedTabId.value = next.selectedTabId
        applyActivity()
        persist()
    }

    fun setWebVisible(visible: Boolean) {
        webVisible = visible
        applyActivity()
    }

    fun setForeground(active: Boolean) {
        foreground = active
        downloadManager.setForeground(active)
        applyActivity()
    }

    fun setSearchEngine(engine: SearchEngine) {
        if (!_ready.value) return
        _searchEngine.value = engine
        persist()
    }

    fun setReading(preferences: ReadingPreferences) {
        if (!_ready.value) return
        _reading.value = preferences.normalized()
        applyReading()
        persist()
    }

    private fun applyReading() {
        val settings = GeckoRuntimeHolder.runtime(getApplication<Application>()).settings
        settings.fontSizeFactor = _reading.value.textPercent / 100f
        settings.preferredColorScheme = when (_reading.value.colorScheme) {
            PageColorScheme.SYSTEM -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
            PageColorScheme.LIGHT -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
            PageColorScheme.DARK -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
        }
    }

    fun receiveIncoming(value: String, openImmediately: Boolean) {
        val bounded = value.trim().take(8_192)
        if (bounded.isBlank()) return
        if (openImmediately && AddressResolver.httpUrl(bounded) == null) return
        _incoming.value = IncomingBrowserRequest(bounded, openImmediately, ++incomingSerial)
    }

    fun consumeIncoming(serial: Long) {
        if (_incoming.value?.serial == serial) _incoming.value = null
    }

    fun addBookmark() {
        if (!_ready.value) return
        val url = AddressResolver.persistableUrl(controller.url.value)?.takeIf { it == controller.committedUrl.value }
        if (url == null) {
            _feedback.value = message(R.string.data_bookmark_unavailable)
            return
        }
        val updated = BrowserDataRules.addBookmark(_bookmarks.value, url, controller.title.value)
        _feedback.value = if (updated == _bookmarks.value) message(R.string.data_bookmark_exists) else message(R.string.data_bookmark_added)
        _bookmarks.value = updated
        persist()
    }

    fun editBookmark(id: String, title: String) {
        if (!_ready.value) return
        _bookmarks.value = BrowserDataRules.editBookmark(_bookmarks.value, id, title)
        persist()
    }

    fun removeBookmark(id: String) {
        if (!_ready.value) return
        _bookmarks.value = _bookmarks.value.filterNot { it.id == id }
        persist()
    }

    fun removeHistory(id: String) {
        if (!_ready.value) return
        _history.value = _history.value.filterNot { it.id == id }
        persist()
    }

    fun clearHistory() {
        if (!_ready.value) return
        _history.value = emptyList()
        persist()
        _feedback.value = message(R.string.data_history_cleared)
    }

    fun clearSiteData() {
        if (!_ready.value || clearingSiteData) return
        clearingSiteData = true
        // Stop page activity so a running document cannot immediately recreate a cleared cookie.
        _tabs.value.forEach { it.controller.suspendPage() }
        try {
            GeckoRuntimeHolder.runtime(getApplication<Application>()).storageController
                .clearData(StorageController.ClearFlags.ALL)
                .accept({
                    clearingSiteData = false
                    _feedback.value = message(R.string.data_site_data_cleared)
                    applyActivity()
                }, {
                    clearingSiteData = false
                    _feedback.value = message(R.string.data_site_data_failed)
                    applyActivity()
                })
        } catch (_: Exception) {
            clearingSiteData = false
            _feedback.value = message(R.string.data_site_data_failed)
            applyActivity()
        }
    }

    private fun message(id: Int): String = getApplication<Application>().getString(id)

    fun consumeFeedback() {
        _feedback.value = null
    }
    fun showMessage(resource: Int) { _feedback.value = message(resource) }

    private fun restore(snapshot: BrowserSnapshot) {
        val restored = BrowserDataRules.sanitize(snapshot)
        observers.values.forEach { it.cancel() }
        observers.clear()
        _tabs.value.forEach { it.controller.close() }
        metadata = restored.tabs.ifEmpty { listOf(SavedTab(newTabId())) }
        _bookmarks.value = restored.bookmarks
        _history.value = restored.history
        _searchEngine.value = restored.searchEngine
        _reading.value = restored.reading
        applyReading()
        _tabs.value = metadata.map { saved ->
            createTab(saved.id, desktop = saved.desktop).also { tab -> saved.url?.let { tab.controller.seedMetadata(it, saved.title) } }
        }
        _selectedTabId.value = restored.selectedTabId?.takeIf { id -> metadata.any { it.id == id } } ?: metadata.first().id
        // Restored background tabs stay blank until actually selected and shown.
        pendingRestores.clear()
        metadata.forEach { saved -> saved.url?.let { pendingRestores[saved.id] = it } }
    }

    private fun createTab(id: String, initiallyOpen: Boolean = true, privateMode: Boolean = false, desktop: Boolean = false): BrowserTab {
        val tab = BrowserTab(id, TabSessionController(getApplication<Application>(), id, initiallyOpen = initiallyOpen, privateMode = privateMode, initialDesktop = desktop))
        tab.controller.setActive(false)
        tab.controller.onDownload = { response -> downloadManager.offer(response, privateMode = privateMode) }
        tab.controller.onNewTabRequested = { url ->
            if (foreground && webVisible && !clearingSiteData && _selectedTabId.value == id) openRequestedTab(url, privateMode) else null
        }
        tab.controller.onCloseRequested = { closeTab(id) }
        observe(tab)
        return tab
    }

    private fun openRequestedTab(url: String, privateMode: Boolean): GeckoSession? {
        if (!_ready.value || AddressResolver.httpUrl(url) == null) return null
        if (_tabs.value.size >= BrowserDataRules.MAX_TABS) {
            _feedback.value = message(R.string.data_popup_tab_limit)
            return null
        }
        // Gecko owns opening the returned session and loading the popup target.
        val added = createTab(newTabId(), initiallyOpen = false, privateMode = privateMode)
        metadata = tabState().add(added.id)!!.tabs
        _tabs.value = _tabs.value + added
        _selectedTabId.value = added.id
        applyActivity()
        persist()
        return added.controller.session
    }

    private fun observe(tab: BrowserTab) {
        observers[tab.id] = viewModelScope.launch {
            launch {
                tab.controller.desktopMode.collect { desktop ->
                    if (_tabs.value.none { it.id == tab.id }) return@collect
                    metadata = metadata.map { if (it.id == tab.id) it.copy(desktop = desktop) else it }
                    persist()
                }
            }
            launch {
                tab.controller.committedRevision.collect { revision ->
                    if (_tabs.value.none { it.id == tab.id } || revision == 0L) return@collect
                    val safe = AddressResolver.persistableUrl(tab.controller.committedUrl.value)
                    if (safe == null) {
                        metadata = metadata.map { if (it.id == tab.id) SavedTab(it.id) else it }
                    } else {
                        val pageTitle = BrowserDataRules.title(tab.controller.title.value, safe)
                        metadata = metadata.map { if (it.id == tab.id) it.copy(url = safe, title = pageTitle) else it }
                        if (!tab.controller.privateMode) _history.value = BrowserDataRules.recordHistory(_history.value, safe, pageTitle, System.currentTimeMillis())
                    }
                    persist()
                }
            }
            launch {
                tab.controller.title.collect { title ->
                    if (_tabs.value.none { it.id == tab.id } || tab.controller.committedRevision.value == 0L || tab.controller.isLoading.value) return@collect
                    val safe = AddressResolver.persistableUrl(tab.controller.committedUrl.value) ?: return@collect
                    if (tab.controller.url.value != safe) return@collect
                    val pageTitle = BrowserDataRules.title(title, safe)
                    metadata = metadata.map { if (it.id == tab.id) it.copy(url = safe, title = pageTitle) else it }
                    // A late title update never recreates deleted history or changes visit order.
                    if (!tab.controller.privateMode) _history.value = _history.value.map { if (it.url == safe) it.copy(title = pageTitle) else it }
                    persist()
                }
            }
        }
    }

    private fun tabState() = TabMetadataState(metadata, _selectedTabId.value ?: metadata.first().id)

    private fun applyActivity() {
        _tabs.value.forEach { tab ->
            val active = foreground && webVisible && !clearingSiteData && tab.id == _selectedTabId.value
            if (active) {
                inactiveJobs.remove(tab.id)?.cancel()
                inactiveDelays.remove(tab.id)
                val pending = pendingRestores.remove(tab.id)
                if (pending != null) tab.controller.loadUri(pending) else tab.controller.resumePage()
            }
            tab.controller.setActive(active)
            // Hidden foreground pages get a bounded grace period for menus/address editing.
            val canSleep = !active
            val gracePeriod = when {
                tab.controller.awaitingFilePicker -> FILE_PICKER_SLEEP_DELAY_MS
                foreground && tab.id == _selectedTabId.value -> HIDDEN_PAGE_SLEEP_DELAY_MS
                else -> BACKGROUND_SLEEP_DELAY_MS
            }
            if (canSleep && (inactiveJobs[tab.id]?.isActive != true || inactiveDelays[tab.id] != gracePeriod)) {
                inactiveJobs.remove(tab.id)?.cancel()
                inactiveDelays[tab.id] = gracePeriod
                inactiveJobs[tab.id] = viewModelScope.launch {
                    delay(gracePeriod)
                    if (!foreground || !webVisible || tab.id != _selectedTabId.value) {
                        if (tab.controller.committedUrl.value.isEmpty()) {
                            metadata.firstOrNull { it.id == tab.id }?.url?.let { pendingRestores[tab.id] = it }
                        }
                        tab.controller.suspendPage()
                    }
                }
            } else if (!canSleep) {
                inactiveJobs.remove(tab.id)?.cancel()
                inactiveDelays.remove(tab.id)
            }
        }
    }

    private fun persist() {
        if (!_ready.value) return
        val pending = PendingWrite(
            BrowserSnapshot(
                metadata.filter { saved -> _tabs.value.any { it.id == saved.id && !it.controller.privateMode } },
                _selectedTabId.value.takeIf { id -> _tabs.value.any { it.id == id && !it.controller.privateMode } },
                _bookmarks.value, _history.value, _searchEngine.value, _reading.value,
            ),
            repository.pendingWrite(),
        )
        if (writes.trySend(pending).isFailure) pending.finished.complete(Unit)
    }

    override fun onCleared() {
        inactiveJobs.values.forEach { it.cancel() }
        observers.values.forEach { it.cancel() }
        _tabs.value.forEach { it.controller.close() }
        downloadManager.close()
        writes.close()
        super.onCleared()
    }

    companion object {
        private const val BACKGROUND_SLEEP_DELAY_MS = 5_000L
        private const val HIDDEN_PAGE_SLEEP_DELAY_MS = 30_000L
        private const val FILE_PICKER_SLEEP_DELAY_MS = 120_000L

        fun normalizeInput(input: String): String? = AddressResolver.resolve(input)
    }
}
