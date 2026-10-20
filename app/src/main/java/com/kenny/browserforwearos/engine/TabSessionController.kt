package com.kenny.browserforwearos.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.UiThread
import com.kenny.browserforwearos.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.PanZoomController
import org.mozilla.geckoview.ScreenLength
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.util.UUID
import com.kenny.browserforwearos.data.forms.FormValues
import com.kenny.browserforwearos.data.download.PreparedUpload
import com.kenny.browserforwearos.presentation.PageZoomController

fun newTabId(): String = "tab-" + UUID.randomUUID().toString()

/** One logical tab. Its GeckoSession can be closed for bounded background suspension. */
@UiThread
class TabSessionController(
    context: Context,
    val tabId: String = newTabId(),
    initiallyOpen: Boolean = true,
    val privateMode: Boolean = false,
    initialDesktop: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtime: GeckoRuntime by lazy { GeckoRuntimeHolder.runtime(appContext) }
    val pageTools = PageTools()
    val zoom = PageZoomController()
    private var initialZoomAllowed = true
    private val _desktopMode = MutableStateFlow(initialDesktop)
    val desktopMode: StateFlow<Boolean> = _desktopMode.asStateFlow()
    private fun createSession(): GeckoSession = GeckoSession(
        GeckoSessionSettings.Builder()
            .userAgentMode(if (_desktopMode.value) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (_desktopMode.value) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .usePrivateMode(privateMode)
            .suspendMediaWhenInactive(true)
            .build(),
    )

    private val _session = MutableStateFlow(createSession())
    val sessionState: StateFlow<GeckoSession> = _session.asStateFlow()
    val session: GeckoSession get() = _session.value
    private var pendingResume: Runnable? = null

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()
    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()
    private val _isSecure = MutableStateFlow(false)
    val isSecure: StateFlow<Boolean> = _isSecure.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _fullScreen = MutableStateFlow(false)
    val fullScreen: StateFlow<Boolean> = _fullScreen.asStateFlow()
    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()
    private val _pendingPrompt = MutableStateFlow<EnginePrompt?>(null)
    val pendingPrompt: StateFlow<EnginePrompt?> = _pendingPrompt.asStateFlow()

    /** Successful top-level HTTP(S) loads only; revision also changes for same-URL reloads. */
    private val _committedUrl = MutableStateFlow("")
    val committedUrl: StateFlow<String> = _committedUrl.asStateFlow()
    private val _committedRevision = MutableStateFlow(0L)
    val committedRevision: StateFlow<Long> = _committedRevision.asStateFlow()

    /** The factory must return an unopened session; Gecko opens and navigates it. */
    var onNewTabRequested: ((String) -> GeckoSession?)? = null
    var onCloseRequested: (() -> Unit)? = null
    /** The UI releases its GeckoView synchronously before native session closure. */
    var onBeforeSuspend: (() -> Unit)? = null
    var onDownload: ((WebResponse) -> Unit)? = null

    var closed: Boolean = false
        private set
    private val _suspended = MutableStateFlow(false)
    val suspendedState: StateFlow<Boolean> = _suspended.asStateFlow()
    val suspended: Boolean get() = _suspended.value
    private var generation = 0L
    private var active = true
    private var stoppedByUser = false
    private var handledExternalResponse = false
    private var lastSuccessfulTitle = ""
    private var showingRestoredMetadata = false
    private data class OwnedUpload(val lease: PreparedUpload, val document: Long)
    private var documentGeneration = 0L
    private val preparedUploads = mutableListOf<OwnedUpload>()
    private var authorizedWindow: Pair<String, Long>? = null
    var awaitingFilePicker: Boolean = false
        private set

    init {
        checkMainThread()
        installDelegates()
        if (initiallyOpen) session.open(runtime)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "TabSessionController requires the main thread" }
    }

    private fun installDelegates() {
        val epoch = generation
        session.navigationDelegate = NavigationHandler(epoch)
        session.progressDelegate = ProgressHandler(epoch)
        session.contentDelegate = ContentHandler(epoch)
        session.promptDelegate = PromptHandler(epoch)
        session.permissionDelegate = PermissionHandler()
        pageTools.sessionChanged(appContext, session)
        session.setCompositorScrollDelegate(object : GeckoSession.CompositorScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, position: GeckoSession.ScrollPositionUpdate) {
                if (current(epoch)) zoom.viewportChanged(session, position.zoom)
            }
        })
    }

    private fun current(epoch: Long): Boolean = !closed && !suspended && epoch == generation
    private fun canOperate(): Boolean = !closed && !suspended && session.isOpen

    private fun resetLoadState() {
        dismissPrompt()
        _error.value = null
        _isSecure.value = false
        _fullScreen.value = false
        stoppedByUser = false
        handledExternalResponse = false
        authorizedWindow = null
    }

    fun loadUri(uri: String) {
        checkMainThread()
        if (closed) return
        cancelPendingResume()
        reopen(loadLastPage = false)
        if (!canOperate()) return
        resetLoadState()
        initialZoomAllowed = true
        session.loadUri(uri)
    }

    fun reload() {
        checkMainThread()
        if (closed) return
        if (suspended || !session.isOpen) {
            val retry = _url.value.takeIf(::isWebUri) ?: _committedUrl.value.takeIf(::isWebUri)
            cancelPendingResume()
            // A renderer crash closes its session. Rebuild before honoring an explicit retry.
            if (!suspended) _suspended.value = true
            reopen(loadLastPage = false)
            resetLoadState()
            retry?.let { session.loadUri(it) }
            return
        }
        if (!canOperate()) return
        val retryUri = _url.value.takeIf { _error.value != null && isWebUri(it) }
        resetLoadState()
        if (retryUri != null) session.loadUri(retryUri) else session.reload()
    }

    fun setDesktopMode(enabled: Boolean) {
        checkMainThread()
        if (closed || _desktopMode.value == enabled) return
        _desktopMode.value = enabled
        zoom.forgetBaseline(_url.value)
        session.settings.userAgentMode = if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.settings.viewportMode = if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        if (_url.value.isNotEmpty()) reload()
    }

    fun stop() {
        checkMainThread()
        cancelPendingResume()
        if (!canOperate()) return
        stoppedByUser = true
        _isLoading.value = false
        session.stop()
    }

    fun goBack() {
        checkMainThread()
        if (!canOperate() || !_canGoBack.value) return
        resetLoadState()
        initialZoomAllowed = false
        session.goBack()
    }

    fun goForward() {
        checkMainThread()
        if (!canOperate() || !_canGoForward.value) return
        resetLoadState()
        initialZoomAllowed = false
        session.goForward()
    }

    fun scrollBy(pixels: Float) {
        checkMainThread()
        if (!canOperate() || !pixels.isFinite()) return
        session.panZoomController.scrollBy(
            ScreenLength.zero(), ScreenLength.fromPixels(pixels.toDouble()),
            PanZoomController.SCROLL_BEHAVIOR_AUTO,
        )
    }

    fun exitFullScreen() {
        checkMainThread()
        if (!canOperate()) return
        session.exitFullScreen()
        _fullScreen.value = false
    }

    fun setActive(active: Boolean) {
        checkMainThread()
        if (closed) return
        this.active = active
        if (!canOperate()) return
        if (active) {
            session.setActive(true)
            session.setFocused(true)
        } else {
            session.setFocused(false)
            session.setActive(false)
            if (!awaitingFilePicker) dismissPrompt()
        }
    }

    /** Call after a bounded inactive grace period, after detaching its GeckoView. */
    fun suspendPage() {
        checkMainThread()
        if (closed || suspended || !session.isOpen) return
        cancelPendingResume()
        setActive(false)
        dismissPrompt()
        onBeforeSuspend?.invoke()
        pageTools.onSessionSuspended()
        _suspended.value = true
        generation++
        authorizedWindow = null
        _isLoading.value = false
        _isSecure.value = false
        _fullScreen.value = false
        _canGoBack.value = false
        _canGoForward.value = false
        session.close()
        releasePreparedUploads()
    }

    /** Builds a new native identity; logical tab ID and metadata remain unchanged. */
    fun resumePage() {
        checkMainThread()
        reopen(loadLastPage = true)
    }

    private fun reopen(loadLastPage: Boolean) {
        if (closed || !suspended) return
        releasePreparedUploads()
        _suspended.value = false
        generation++
        _session.value = createSession()
        installDelegates()
        session.open(runtime)
        session.setActive(active)
        session.setFocused(active)
        val previous = _committedUrl.value
        if (loadLastPage && isWebUri(previous)) {
            resetLoadState()
            val resumedSession = session
            val epoch = generation
            // A tab selection may be followed by an explicit address in this UI turn.
            // Let that newer navigation cancel the automatic GET before Gecko queues both.
            pendingResume = Runnable {
                pendingResume = null
                if (current(epoch) && session === resumedSession && resumedSession.isOpen) {
                    resumedSession.loadUri(previous)
                }
            }.also { mainHandler.post(it) }
        }
        // SessionState restore can replay POST or restore private form values. Deliberately
        // use a fresh GET: hibernation does not promise pending forms or back-stack recovery.
    }

    private fun cancelPendingResume() {
        pendingResume?.let(mainHandler::removeCallbacks)
        pendingResume = null
    }

    private fun rendererUnavailable(message: Int) {
        cancelPendingResume()
        dismissPrompt()
        onBeforeSuspend?.invoke()
        pageTools.onSessionSuspended()
        _suspended.value = true
        generation++
        _isLoading.value = false
        _isSecure.value = false
        _fullScreen.value = false
        _canGoBack.value = false
        _canGoForward.value = false
        _error.value = appContext.getString(message)
        if (session.isOpen) session.close()
        releasePreparedUploads()
    }

    fun close() {
        checkMainThread()
        if (closed) return
        cancelPendingResume()
        dismissPrompt()
        onBeforeSuspend?.invoke()
        closed = true
        generation++
        onNewTabRequested = null
        onCloseRequested = null
        onBeforeSuspend = null
        onDownload = null
        pageTools.close()
        zoom.close()
        authorizedWindow = null
        if (session.isOpen) session.close()
        releasePreparedUploads()
    }

    private fun releasePreparedUploads(beforeDocument: Long? = null) {
        val iterator = preparedUploads.iterator()
        while (iterator.hasNext()) {
            val upload = iterator.next()
            if (beforeDocument == null || upload.document < beforeDocument) {
                iterator.remove()
                upload.lease.close()
            }
        }
    }

    private fun completePrompt(pending: EnginePrompt, response: () -> GeckoSession.PromptDelegate.PromptResponse): Boolean {
        pending.basePrompt.setDelegate(null)
        return runCatching { pending.geckoResult.complete(response()) }.fold(
            onSuccess = { true },
            onFailure = { failure ->
                runCatching { pending.geckoResult.completeExceptionally(failure) }
                Log.w(TAG, "Prompt completion failed")
                false
            },
        )
    }

    fun dismissPrompt() {
        checkMainThread()
        awaitingFilePicker = false
        val pending = _pendingPrompt.value ?: return
        _pendingPrompt.value = null
        completePrompt(pending) { pending.basePrompt.dismiss() }
    }

    fun confirmButton(positive: Boolean) {
        checkMainThread()
        val pending = _pendingPrompt.value as? EnginePrompt.Confirm ?: return
        _pendingPrompt.value = null
        val type = if (positive) GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE
        else GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE
        completePrompt(pending) { pending.prompt.confirm(type) }
    }

    fun confirmText(text: String) {
        checkMainThread()
        val pending = _pendingPrompt.value as? EnginePrompt.Text ?: return
        _pendingPrompt.value = null
        completePrompt(pending) { pending.prompt.confirm(text) }
    }

    fun confirmAuth(user: String, password: String) {
        checkMainThread()
        val pending = _pendingPrompt.value as? EnginePrompt.Auth ?: return
        _pendingPrompt.value = null
        completePrompt(pending) { pending.prompt.confirm(user, password) }
    }

    fun confirmChoice(choice: GeckoSession.PromptDelegate.ChoicePrompt.Choice) {
        checkMainThread()
        val pending = _pendingPrompt.value as? EnginePrompt.Choice ?: return
        _pendingPrompt.value = null
        completePrompt(pending) { pending.prompt.confirm(choice) }
    }

    fun confirmChoices(choices: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>) {
        checkMainThread()
        val pending = _pendingPrompt.value as? EnginePrompt.Choice ?: return
        if (pending.prompt.type != GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE) return
        _pendingPrompt.value = null
        completePrompt(pending) { pending.prompt.confirm(choices.distinctBy { it.id }.toTypedArray()) }
    }

    fun confirmLeave() {
        checkMainThread()
        val pending = _pendingPrompt.value as? EnginePrompt.BeforeUnload ?: return
        _pendingPrompt.value = null
        completePrompt(pending) { pending.prompt.confirm(AllowOrDeny.ALLOW) }
    }

    fun confirmFiles(uris: List<Uri>, prepared: PreparedUpload? = null): Boolean {
        checkMainThread()
        val pending = _pendingPrompt.value as? EnginePrompt.File
        if (pending == null || uris.isEmpty() || uris.size > 10 || uris.any { it.scheme != "file" } ||
            (pending.prompt.type != GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE && uris.size != 1) ||
            (prepared != null && prepared.uris != uris)) {
            prepared?.close()
            return false
        }
        _pendingPrompt.value = null
        awaitingFilePicker = false
        // Confirmation can trigger an onchange auto-submit. Capture the owning
        // document before native callbacks can advance the navigation generation.
        val ownerDocument = documentGeneration
        val accepted = completePrompt(pending) { pending.prompt.confirm(appContext, uris.toTypedArray()) }
        if (accepted && prepared != null) preparedUploads.add(OwnedUpload(prepared, ownerDocument)) else prepared?.close()
        return accepted
    }

    fun beginFilePicker(): Boolean {
        checkMainThread()
        if (_pendingPrompt.value !is EnginePrompt.File) return false
        awaitingFilePicker = true
        return true
    }

    fun endFilePicker() { checkMainThread(); awaitingFilePicker = false }

    fun confirmDateTime(value: String): Boolean {
        checkMainThread()
        val pending = _pendingPrompt.value as? EnginePrompt.DateTime ?: return false
        if (!FormValues.validDateTime(value, pending.prompt.type, pending.prompt.minValue.orEmpty(), pending.prompt.maxValue.orEmpty(), pending.prompt.stepValue.orEmpty())) return false
        _pendingPrompt.value = null
        completePrompt(pending) { pending.prompt.confirm(value) }
        return true
    }

    fun confirmColor(value: String): Boolean {
        checkMainThread()
        val pending = _pendingPrompt.value as? EnginePrompt.Color ?: return false
        if (!FormValues.validColor(value)) return false
        _pendingPrompt.value = null
        completePrompt(pending) { pending.prompt.confirm(value.lowercase()) }
        return true
    }

    fun consumeFeedback() {
        checkMainThread()
        _feedback.value = null
    }

    /** Initializes restored list labels without loading a page or recording a visit. */
    fun seedMetadata(url: String, title: String) {
        checkMainThread()
        if (closed || _committedRevision.value != 0L || _isLoading.value || _url.value.isNotEmpty()) return
        if (!isWebUri(url)) return
        _url.value = url
        _title.value = title
        showingRestoredMetadata = true
    }

    private fun notifyFeedback(resource: Int) {
        _feedback.value = appContext.getString(resource)
    }

    private fun promptOrigin(uri: String = _url.value): String =
        runCatching { Uri.parse(uri).host }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: appContext.getString(R.string.engine_unknown_origin)

    private fun isWebUri(uri: String): Boolean = runCatching {
        val parsed = Uri.parse(uri)
        parsed.scheme?.lowercase() in setOf("http", "https") && !parsed.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun displayUrl(uri: String): String =
        if (uri.equals("about:blank", ignoreCase = true) || uri.startsWith("about:blank#", ignoreCase = true)) "" else uri

    private inner class NavigationHandler(private val epoch: Long) : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession, url: String?,
            permissions: List<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            if (!current(epoch) || url == null) return
            if (showingRestoredMetadata && displayUrl(url).isEmpty()) return
            _url.value = displayUrl(url)
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            if (current(epoch)) _canGoBack.value = canGoBack
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            if (current(epoch)) _canGoForward.value = canGoForward
        }

        override fun onLoadRequest(
            session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny> {
            if (!current(epoch)) return GeckoResult.deny()
            val uri = request.uri
            val scheme = runCatching { Uri.parse(uri).scheme?.lowercase() }.getOrNull()
            if (scheme !in setOf("http", "https", "about", "blob", "data")) {
                notifyFeedback(R.string.engine_external_link_unsupported)
                return GeckoResult.deny()
            }
            if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                if (!request.hasUserGesture || onNewTabRequested == null) {
                    notifyFeedback(R.string.engine_popup_blocked)
                    return GeckoResult.deny()
                }
                authorizedWindow = uri to (SystemClock.elapsedRealtime() + WINDOW_REQUEST_TTL_MS)
            }
            return GeckoResult.allow()
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
            if (!current(epoch)) return GeckoResult.fromValue(null)
            val authorized = authorizedWindow
            authorizedWindow = null
            if (authorized == null || authorized.first != uri || authorized.second < SystemClock.elapsedRealtime()) {
                notifyFeedback(R.string.engine_popup_blocked)
                return GeckoResult.fromValue(null)
            }
            val created = onNewTabRequested?.invoke(uri)
            if (created?.isOpen == true) {
                Log.w(TAG, "New-window factory returned an already open session")
                return GeckoResult.fromValue(null)
            }
            return GeckoResult.fromValue(created)
        }

        override fun onLoadError(
            session: GeckoSession, uri: String?, error: WebRequestError,
        ): GeckoResult<String> {
            if (!current(epoch)) return GeckoResult.fromValue(null)
            if (handledExternalResponse) return GeckoResult.fromValue(null)
            _isLoading.value = false
            _isSecure.value = false
            _fullScreen.value = false
            dismissPrompt()
            val resource = when {
                error.category == WebRequestError.ERROR_CATEGORY_SECURITY -> R.string.engine_error_certificate
                error.code == WebRequestError.ERROR_UNKNOWN_HOST -> R.string.engine_error_dns
                error.code == WebRequestError.ERROR_OFFLINE -> R.string.engine_error_offline
                error.code == WebRequestError.ERROR_NET_TIMEOUT -> R.string.engine_error_timeout
                else -> R.string.engine_error_load
            }
            _error.value = appContext.getString(resource)
            // Neither URI, query, credentials, certificate details nor prompt contents enter logs.
            Log.w(TAG, "Load failed category=${error.category} code=${error.code}")
            // UI presents the error state. No certificate exception or bypass navigation exists.
            return GeckoResult.fromValue(null)
        }
    }

    private inner class ProgressHandler(private val epoch: Long) : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            if (!current(epoch)) return
            // The new native session's initial blank document must not erase
            // the cold-start label before the user chooses to restore it.
            if (showingRestoredMetadata && displayUrl(url).isEmpty()) return
            showingRestoredMetadata = false
            // Do not unlink old files here: a form POST can still be reading
            // them until the subsequent document has finished loading.
            documentGeneration++
            resetLoadState()
            _isLoading.value = displayUrl(url).isNotEmpty()
            _progress.value = 0
            _url.value = displayUrl(url)
            _title.value = ""
            pageTools.onDocumentStarted(url)
            zoom.documentStarted(url, session, initialZoomAllowed)
            initialZoomAllowed = true
            session.setActive(active)
            session.setFocused(active)
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (!current(epoch)) return
            if (showingRestoredMetadata) return
            _isLoading.value = false
            _progress.value = 100
            if (handledExternalResponse) return
            if (success && _error.value == null && isWebUri(_url.value)) {
                // A file selected while this document was still loading remains
                // live in its DOM. Only an older document's upload has finished.
                releasePreparedUploads(beforeDocument = documentGeneration)
                lastSuccessfulTitle = _title.value
                _committedUrl.value = _url.value
                _committedRevision.value++
                pageTools.onDocumentCommitted(_committedUrl.value, _title.value, _committedRevision.value)
            } else if (!success && !stoppedByUser && _error.value == null) {
                _isSecure.value = false
                _error.value = appContext.getString(R.string.engine_error_load)
            }
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            if (current(epoch)) _progress.value = progress.coerceIn(0, 100)
        }

        override fun onSecurityChange(
            session: GeckoSession, securityInfo: GeckoSession.ProgressDelegate.SecurityInformation,
        ) {
            if (current(epoch)) {
                _isSecure.value = _error.value == null && securityInfo.isSecure && !securityInfo.isException
            }
        }

    }

    private inner class ContentHandler(private val epoch: Long) : GeckoSession.ContentDelegate {
        override fun onFirstContentfulPaint(session: GeckoSession) {
            if (current(epoch)) zoom.contentPainted(session)
        }
        override fun onTitleChange(session: GeckoSession, title: String?) {
            if (showingRestoredMetadata) return
            if (current(epoch)) _title.value = title.orEmpty().takeUnless { it.equals("about:blank", ignoreCase = true) }.orEmpty()
            if (current(epoch) && !_isLoading.value && _url.value == _committedUrl.value) lastSuccessfulTitle = _title.value
        }

        override fun onCrash(session: GeckoSession) {
            if (!current(epoch)) return
            rendererUnavailable(R.string.engine_error_crash)
        }

        override fun onKill(session: GeckoSession) {
            if (!current(epoch)) return
            rendererUnavailable(R.string.engine_error_reclaimed)
        }

        override fun onFocusRequest(session: GeckoSession) {
            if (current(epoch) && active) session.setFocused(true)
        }

        override fun onCloseRequest(session: GeckoSession) {
            if (current(epoch)) onCloseRequested?.invoke()
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            if (current(epoch)) _fullScreen.value = fullScreen
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            if (!current(epoch) || onDownload == null) { runCatching { response.body?.close() }; return }
            _isLoading.value = false
            handledExternalResponse = true
            _error.value = null
            _url.value = _committedUrl.value
            _title.value = lastSuccessfulTitle
            pageTools.onDocumentCommitted(_url.value, _title.value, _committedRevision.value)
            zoom.documentStarted(_url.value, session, allowInitialBaseline = false)
            zoom.contentPainted(session)
            onDownload?.invoke(response)
        }

        override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) {
            if (current(epoch) && active) pageTools.showContextMenu(session, element)
        }
    }

    private inner class PermissionHandler : GeckoSession.PermissionDelegate {
        override fun onAndroidPermissionsRequest(
            session: GeckoSession, permissions: Array<out String>?, callback: GeckoSession.PermissionDelegate.Callback,
        ) = callback.reject()

        override fun onContentPermissionRequest(
            session: GeckoSession, permission: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int> = GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)

        override fun onMediaPermissionRequest(
            session: GeckoSession, uri: String,
            video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) = callback.reject()
    }

    private inner class PromptHandler(private val epoch: Long) : GeckoSession.PromptDelegate {
        private fun queue(
            create: (GeckoResult<GeckoSession.PromptDelegate.PromptResponse>) -> EnginePrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            val pending = create(result)
            if (!current(epoch) || !active) return GeckoResult.fromValue(pending.basePrompt.dismiss())
            dismissPrompt()
            pending.origin = promptOrigin((pending as? EnginePrompt.Auth)?.prompt?.authOptions?.uri ?: _url.value)
            pending.basePrompt.setDelegate(object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
                override fun onPromptDismiss(prompt: GeckoSession.PromptDelegate.BasePrompt) {
                    if (_pendingPrompt.value !== pending) return
                    _pendingPrompt.value = null
                    awaitingFilePicker = false
                    // A GeckoResult containing null is not a dismiss response: the
                    // native controller dispatches it and would dereference null.
                    completePrompt(pending) { prompt.dismiss() }
                }

                override fun onPromptUpdate(prompt: GeckoSession.PromptDelegate.BasePrompt) {
                    // Never keep a UI response bound to a superseded engine request.
                    if (_pendingPrompt.value === pending) dismissPrompt()
                }
            })
            result.setCancellationDelegate(object : GeckoResult.CancellationDelegate {
                override fun cancel(): GeckoResult<Boolean> {
                    val cancelled = GeckoResult<Boolean>(mainHandler)
                    val finishCancellation = Runnable {
                        if (_pendingPrompt.value === pending) {
                            _pendingPrompt.value = null
                            awaitingFilePicker = false
                            pending.basePrompt.setDelegate(null)
                            runCatching { pending.basePrompt.dismiss() }
                        }
                        // The caller owns cancellation of result; do not complete it recursively.
                        cancelled.complete(true)
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) finishCancellation.run()
                    else mainHandler.post(finishCancellation)
                    return cancelled
                }
            })
            _pendingPrompt.value = pending
            return result
        }

        override fun onAlertPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.AlertPrompt) =
            queue { EnginePrompt.Alert(prompt, it) }
        override fun onButtonPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ButtonPrompt) =
            queue { EnginePrompt.Confirm(prompt, it) }
        override fun onTextPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.TextPrompt) =
            queue { EnginePrompt.Text(prompt, it) }
        override fun onAuthPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.AuthPrompt) =
            queue { EnginePrompt.Auth(prompt, it) }
        override fun onChoicePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ChoicePrompt) =
            queue { EnginePrompt.Choice(prompt, it) }
        override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt) =
            queue { EnginePrompt.BeforeUnload(prompt, it) }

        override fun onFilePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER) {
                if (current(epoch)) notifyFeedback(R.string.folder_upload_unsupported)
                return GeckoResult.fromValue(prompt.dismiss())
            }
            return queue { EnginePrompt.File(prompt, it) }
        }

        override fun onPopupPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.PopupPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            if (current(epoch)) notifyFeedback(R.string.engine_popup_blocked)
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
        }

        override fun onDateTimePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.DateTimePrompt) = queue { EnginePrompt.DateTime(prompt, it) }
        override fun onColorPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ColorPrompt) = queue { EnginePrompt.Color(prompt, it) }
    }

    companion object {
        private const val TAG = "TabSession"
        private const val WINDOW_REQUEST_TTL_MS = 3_000L
    }
}

/** The origin is a parsed hostname, never a page-controlled title or credential-bearing URL. */
sealed class EnginePrompt(
    val basePrompt: GeckoSession.PromptDelegate.BasePrompt,
    val geckoResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
) {
    var origin: String = ""
        internal set

    class Alert(val prompt: GeckoSession.PromptDelegate.AlertPrompt, result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>) : EnginePrompt(prompt, result)
    class Confirm(val prompt: GeckoSession.PromptDelegate.ButtonPrompt, result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>) : EnginePrompt(prompt, result)
    class Text(val prompt: GeckoSession.PromptDelegate.TextPrompt, result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>) : EnginePrompt(prompt, result)
    class Auth(val prompt: GeckoSession.PromptDelegate.AuthPrompt, result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>) : EnginePrompt(prompt, result)
    class Choice(val prompt: GeckoSession.PromptDelegate.ChoicePrompt, result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>) : EnginePrompt(prompt, result)
    class BeforeUnload(val prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt, result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>) : EnginePrompt(prompt, result)
    class File(val prompt: GeckoSession.PromptDelegate.FilePrompt, result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>) : EnginePrompt(prompt, result)
    class DateTime(val prompt: GeckoSession.PromptDelegate.DateTimePrompt, result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>) : EnginePrompt(prompt, result)
    class Color(val prompt: GeckoSession.PromptDelegate.ColorPrompt, result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>) : EnginePrompt(prompt, result)
}
