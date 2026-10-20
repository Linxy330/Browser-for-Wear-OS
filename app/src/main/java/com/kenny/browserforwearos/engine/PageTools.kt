package com.kenny.browserforwearos.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.UiThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.PageExtractionController

/** Native Gecko tools. Contents stay in memory; no DOM bridge, extension, or page script. */
@UiThread
class PageTools {
    data class FindState(
        val query: String = "", val busy: Boolean = false,
        val found: Boolean = false, val current: Int = 0, val total: Int = 0,
        val wrapped: Boolean = false, val failed: Boolean = false,
    ) {
        val pending: Boolean get() = busy
        val error: String? get() = if (failed) "find_failed" else null
    }
    enum class ReaderFailure { NO_PAGE, NOT_ARTICLE, EXTRACTION_FAILED, EMPTY_CONTENT, TOO_LARGE, TIMEOUT }
    data class ReaderArticle(
        val url: String, val title: String, val text: String,
        val language: String?, val wordCount: Int, val gated: Boolean,
    )
    data class ReaderState(
        val busy: Boolean = false, val article: ReaderArticle? = null,
        val failure: ReaderFailure? = null,
    ) {
        enum class Status { IDLE, LOADING, READY, FAILED }
        val status: Status get() = when { busy -> Status.LOADING; article != null -> Status.READY; failure != null -> Status.FAILED; else -> Status.IDLE }
        val title: String get() = article?.title.orEmpty()
        val text: String get() = article?.text.orEmpty()
    }
    data class SelectionState(
        val preview: String, val editable: Boolean,
        val canCopy: Boolean, val canCut: Boolean, val canPaste: Boolean,
        val canSelectAll: Boolean,
    )
    enum class SelectionAction { COPY, CUT, PASTE, SELECT_ALL, DISMISS }
    data class ContextTarget(
        val linkUrl: String?, val linkText: String, val imageUrl: String?,
        val title: String, val altText: String,
    )

    private var appContext: Context? = null
    private var nativeSession: GeckoSession? = null
    private var pageUrl = ""
    private var pageTitle = ""
    private var pageEpoch = 0L
    private var committedRevision = -1L
    private val handler = Handler(Looper.getMainLooper())
    private val _find = MutableStateFlow(FindState())
    val findState: StateFlow<FindState> = _find.asStateFlow()
    private val _reader = MutableStateFlow(ReaderState())
    val readerState: StateFlow<ReaderState> = _reader.asStateFlow()
    private val _selection = MutableStateFlow<SelectionState?>(null)
    val selectionState: StateFlow<SelectionState?> = _selection.asStateFlow()
    private val _context = MutableStateFlow<ContextTarget?>(null)
    val contextState: StateFlow<ContextTarget?> = _context.asStateFlow()
    private var findRequest = 0L
    private var lastMatchCase = false
    private var lastWholeWord = false
    private var readerRequest = 0L
    private var readerTimeout: Runnable? = null
    private var selected: GeckoSession.SelectionActionDelegate.Selection? = null
    private var selectionSession: GeckoSession? = null
    private var selectionRevision = -1L
    private var contextSession: GeckoSession? = null
    private var contextRevision = -1L

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper()) { "PageTools requires the main thread" }
    private fun currentSession(): GeckoSession? = nativeSession
    private fun currentUrl(): String = pageUrl
    private fun documentRevision(): Long = pageEpoch
    private fun session(): GeckoSession? = nativeSession?.takeIf { it.isOpen }
    private fun stillCurrent(session: GeckoSession, revision: Long): Boolean =
        currentSession() === session && session.isOpen && documentRevision() == revision

    fun sessionChanged(context: Context, session: GeckoSession) {
        mainThread()
        close()
        appContext = context.applicationContext
        nativeSession = session
        session.selectionActionDelegate = selectionDelegate
        pageEpoch++
    }

    fun onDocumentStarted(url: String) {
        mainThread()
        clearTransient()
        pageEpoch++
        pageUrl = url
        pageTitle = ""
    }

    fun onDocumentCommitted(url: String, title: String, revision: Long = committedRevision + 1) {
        mainThread()
        if (revision != committedRevision || url != pageUrl) {
            clearTransient()
            pageEpoch++
        }
        committedRevision = revision
        pageUrl = url
        pageTitle = title
    }

    fun close() {
        mainThread()
        clearTransient()
        nativeSession?.let { if (it.selectionActionDelegate === selectionDelegate) it.selectionActionDelegate = null }
        nativeSession = null
        appContext = null
        pageUrl = ""
        pageTitle = ""
        pageEpoch++
        committedRevision = -1
    }

    /** Call on page start, native session replacement, hibernation, or logical tab close. */
    fun clearTransient(preserveReader: Boolean = false) {
        mainThread()
        clearFind()
        if (!preserveReader) closeReader()
        else if (_reader.value.busy) {
            readerRequest++
            cancelReaderTimeout()
            _reader.value = ReaderState(failure = ReaderFailure.NO_PAGE)
        }
        dismissSelection()
        dismissContext()
    }

    /** A ready native text snapshot survives while its original webpage is hibernated. */
    fun onSessionSuspended() = clearTransient(preserveReader = true)

    fun find(query: String, backward: Boolean = false, matchCase: Boolean = false, wholeWord: Boolean = false) {
        lastMatchCase = matchCase
        lastWholeWord = wholeWord
        performFind(query, backward, matchCase, wholeWord, false)
    }

    fun nextMatch(backward: Boolean = false) {
        performFind(_find.value.query, backward, lastMatchCase, lastWholeWord, true)
    }

    private fun performFind(query: String, backwards: Boolean, matchCase: Boolean, wholeWord: Boolean, again: Boolean) {
        mainThread()
        if (query.isEmpty()) { clearFind(); return }
        val native = session()
        val request = ++findRequest
        if (native == null) { _find.value = FindState(query = query, failed = true); return }
        val revision = documentRevision()
        _find.value = _find.value.copy(query = query, busy = true, failed = false)
        native.finder.setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL or GeckoSession.FINDER_DISPLAY_DRAW_LINK_OUTLINE)
        var flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else GeckoSession.FINDER_FIND_FORWARD
        if (matchCase) flags = flags or GeckoSession.FINDER_FIND_MATCH_CASE
        if (wholeWord) flags = flags or GeckoSession.FINDER_FIND_WHOLE_WORD
        native.finder.find(if (again) null else query, flags).withHandler(handler).accept({ result ->
            if (request != findRequest || !stillCurrent(native, revision)) return@accept
            _find.value = if (result == null) FindState(query = query, failed = true)
                else FindState(query, false, result.found, result.current, result.total, result.wrapped)
        }, {
            if (request == findRequest && stillCurrent(native, revision)) _find.value = _find.value.copy(busy = false, failed = true)
        })
    }

    fun clearFind() {
        mainThread()
        findRequest++
        session()?.finder?.clear()
        _find.value = FindState()
    }

    /** ReaderMode extraction is supplied by Gecko 158 and returns text, never executable HTML. */
    // The engine version is pinned; bounded extraction has a visible failure state.
    @androidx.annotation.OptIn(markerClass = [org.mozilla.geckoview.ExperimentalGeckoViewApi::class])
    fun readArticle(title: String = pageTitle) {
        mainThread()
        cancelReaderTimeout()
        val native = session()
        val url = currentUrl()
        val request = ++readerRequest
        if (native == null || webUrl(url) == null) {
            _reader.value = ReaderState(failure = ReaderFailure.NO_PAGE)
            return
        }
        val revision = documentRevision()
        _reader.value = ReaderState(busy = true)
        readerTimeout = Runnable {
            if (request == readerRequest && _reader.value.busy) {
                readerRequest++
                readerTimeout = null
                _reader.value = ReaderState(failure = ReaderFailure.TIMEOUT)
            }
        }.also { handler.postDelayed(it, READER_TIMEOUT_MS) }
        native.sessionPageExtractor.getPageMetadata().withHandler(handler).accept(metadataResult@{ metadata ->
            if (request != readerRequest || !stillCurrent(native, revision)) return@metadataResult
            if (metadata == null || !metadata.isReaderable) {
                setReaderState(ReaderState(failure = ReaderFailure.NOT_ARTICLE))
                return@metadataResult
            }
            val parameters = PageExtractionController.ContentParams(true, true)
            native.sessionPageExtractor.getPageContent(parameters).withHandler(handler).accept(textResult@{ text ->
                if (request != readerRequest || !stillCurrent(native, revision)) return@textResult
                val body = text?.trim().orEmpty()
                setReaderState(when {
                    body.isEmpty() -> ReaderState(failure = ReaderFailure.EMPTY_CONTENT)
                    body.length > MAX_ARTICLE_CHARACTERS -> ReaderState(failure = ReaderFailure.TOO_LARGE)
                    else -> ReaderState(article = ReaderArticle(url, title, body, metadata.language, metadata.wordCount, metadata.isGated))
                })
            }, {
                if (request == readerRequest && stillCurrent(native, revision)) setReaderState(ReaderState(failure = ReaderFailure.EXTRACTION_FAILED))
            })
        }, {
            if (request == readerRequest && stillCurrent(native, revision)) setReaderState(ReaderState(failure = ReaderFailure.EXTRACTION_FAILED))
        })
    }

    fun closeReader() {
        mainThread()
        readerRequest++
        cancelReaderTimeout()
        _reader.value = ReaderState()
    }

    private fun cancelReaderTimeout() {
        readerTimeout?.let(handler::removeCallbacks)
        readerTimeout = null
    }

    private fun setReaderState(state: ReaderState) {
        cancelReaderTimeout()
        _reader.value = state
    }

    /** Install on each new native session; the app owns a round-screen action menu. */
    val selectionDelegate: GeckoSession.SelectionActionDelegate = object : GeckoSession.SelectionActionDelegate {
        override fun onShowActionRequest(session: GeckoSession, selection: GeckoSession.SelectionActionDelegate.Selection) {
            if (currentSession() !== session || !session.isOpen) return
            selected = selection
            selectionSession = session
            selectionRevision = documentRevision()
            val password = selection.flags and GeckoSession.SelectionActionDelegate.FLAG_IS_PASSWORD != 0
            _selection.value = SelectionState(
                preview = if (password) "" else selection.text.take(160),
                editable = selection.flags and GeckoSession.SelectionActionDelegate.FLAG_IS_EDITABLE != 0,
                canCopy = !password && selection.isActionAvailable(GeckoSession.SelectionActionDelegate.ACTION_COPY),
                canCut = !password && selection.isActionAvailable(GeckoSession.SelectionActionDelegate.ACTION_CUT),
                canPaste = selection.isActionAvailable(GeckoSession.SelectionActionDelegate.ACTION_PASTE),
                canSelectAll = selection.isActionAvailable(GeckoSession.SelectionActionDelegate.ACTION_SELECT_ALL),
            )
        }

        override fun onHideAction(session: GeckoSession, reason: Int) {
            if (selectionSession !== session) return
            // Gecko 158 explicitly keeps native actionId valid for invisible selection
            // and scrolling. Deactivate/pagehide/no-selection invalidates it immediately.
            if (reason != GeckoSession.SelectionActionDelegate.HIDE_REASON_INVISIBLE_SELECTION &&
                reason != GeckoSession.SelectionActionDelegate.HIDE_REASON_ACTIVE_SCROLL) clearSelectionReference()
        }

        override fun onShowClipboardPermissionRequest(
            session: GeckoSession,
            permission: GeckoSession.SelectionActionDelegate.ClipboardPermission,
        ): GeckoResult<AllowOrDeny> = GeckoResult.deny()
    }

    fun performSelection(action: SelectionAction): Boolean {
        mainThread()
        val native = selectionSession ?: return false
        val selection = selected ?: return false
        if (!stillCurrent(native, selectionRevision)) { clearSelectionReference(); return false }
        val state = _selection.value ?: return false
        when (action) {
            SelectionAction.COPY -> if (state.canCopy) selection.copy() else return false
            SelectionAction.CUT -> if (state.canCut) selection.cut() else return false
            SelectionAction.PASTE -> if (state.canPaste) selection.paste() else return false
            SelectionAction.SELECT_ALL -> if (state.canSelectAll) { selection.selectAll(); return true } else return false
            SelectionAction.DISMISS -> Unit
        }
        selection.hide()
        clearSelectionReference()
        return true
    }

    fun dismissSelection() {
        mainThread()
        val old = selected
        clearSelectionReference()
        runCatching { old?.hide() }
    }

    private fun clearSelectionReference() {
        selected = null
        selectionSession = null
        selectionRevision = -1
        _selection.value = null
    }

    /** Forward ContentDelegate.onContextMenu here. Raw content stays an inert model. */
    fun showContextMenu(session: GeckoSession, element: GeckoSession.ContentDelegate.ContextElement) {
        mainThread()
        if (currentSession() !== session || !session.isOpen) return
        val target = ContextTarget(
            linkUrl = webUrl(element.linkUri), linkText = element.linkText.orEmpty().take(240),
            imageUrl = if (element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE) webUrl(element.srcUri) else null,
            title = element.title.orEmpty().take(240), altText = element.altText.orEmpty().take(240),
        )
        contextSession = session
        contextRevision = documentRevision()
        _context.value = target.takeIf { it.linkUrl != null || it.imageUrl != null }
    }

    fun copyContextLink(label: String): Boolean = copyContextValue(_context.value?.linkUrl, label)
    fun copyContextImageUrl(label: String): Boolean = copyContextValue(_context.value?.imageUrl, label)

    private fun copyContextValue(value: String?, label: String): Boolean {
        mainThread()
        val native = contextSession ?: return false
        if (value == null || !stillCurrent(native, contextRevision)) { dismissContext(); return false }
        val clipboard = appContext?.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        dismissContext()
        return true
    }

    fun dismissContext() {
        mainThread()
        contextSession = null
        contextRevision = -1
        _context.value = null
    }

    private fun webUrl(value: String?): String? {
        if (value.isNullOrBlank() || value.length > 16_384 || value.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        return value.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() }
    }

    companion object {
        private const val MAX_ARTICLE_CHARACTERS = 300_000
        private const val READER_TIMEOUT_MS = 15_000L
    }
}
