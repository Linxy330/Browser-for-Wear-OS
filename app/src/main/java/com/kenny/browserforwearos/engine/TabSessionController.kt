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

fun newTabId(): String = "tab-" + UUID.randomUUID().toString()

/** One logical tab. Its GeckoSession can be closed for bounded background suspension. */
@UiThread
class TabSessionController(
    context: Context,
    val tabId: String = newTabId(),
    initiallyOpen: Boolean = true,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtime: GeckoRuntime by lazy { GeckoRuntimeHolder.runtime(appContext) }
    private fun createSession(): GeckoSession = GeckoSession(
        GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
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

    var closed: Boolean = false
        private set
    private val _suspended = MutableStateFlow(false)
    val suspendedState: StateFlow<Boolean> = _suspended.asStateFlow()
    val suspended: Boolean get() = _suspended.value
    private var generation = 0L
    private var active = true
    private var stoppedByUser = false
    private var authorizedWindow: Pair<String, Long>? = null
    // Kept only in memory. Never serialize form/POST state or replay it during resume.
    private var lastSessionState: GeckoSession.SessionState? = null

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
    }

    private fun current(epoch: Long): Boolean = !closed && !suspended && epoch == generation
    private fun canOperate(): Boolean = !closed && !suspended && session.isOpen

    private fun resetLoadState() {
        dismissPrompt()
        _error.value = null
        _isSecure.value = false
        _fullScreen.value = false
        stoppedByUser = false
        authorizedWindow = null
    }

    fun loadUri(uri: String) {
        checkMainThread()
        if (closed) return
        cancelPendingResume()
        reopen(loadLastPage = false)
        if (!canOperate()) return
        resetLoadState()
        session.loadUri(uri)
    }

    fun reload() {
        checkMainThread()
        if (closed) return
        val wasSuspended = suspended
        resumePage()
        if (!canOperate() || wasSuspended) return
        val retryUri = _url.value.takeIf { _error.value != null && isWebUri(it) }
        resetLoadState()
        if (retryUri != null) session.loadUri(retryUri) else session.reload()
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
        session.goBack()
    }

    fun goForward() {
        checkMainThread()
        if (!canOperate() || !_canGoForward.value) return
        resetLoadState()
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
            session.flushSessionState()
            dismissPrompt()
        }
    }

    /** Call after a bounded inactive grace period, after detaching its GeckoView. */
    fun suspendPage() {
        checkMainThread()
        if (closed || suspended || !session.isOpen) return
        cancelPendingResume()
        setActive(false)
        onBeforeSuspend?.invoke()
        _suspended.value = true
        generation++
        authorizedWindow = null
        _isLoading.value = false
        _isSecure.value = false
        _fullScreen.value = false
        _canGoBack.value = false
        _canGoForward.value = false
        session.close()
    }

    /** Builds a new native identity; logical tab ID and metadata remain unchanged. */
    fun resumePage() {
        checkMainThread()
        reopen(loadLastPage = true)
    }

    private fun reopen(loadLastPage: Boolean) {
        if (closed || !suspended) return
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
        lastSessionState = null
    }

    private fun cancelPendingResume() {
        pendingResume?.let(mainHandler::removeCallbacks)
        pendingResume = null
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
        authorizedWindow = null
        lastSessionState = null
        if (session.isOpen) session.close()
    }

    private fun completePrompt(pending: EnginePrompt, response: () -> GeckoSession.PromptDelegate.PromptResponse) {
        pending.basePrompt.setDelegate(null)
        runCatching { pending.geckoResult.complete(response()) }
            .onFailure { Log.w(TAG, "Prompt completion failed") }
    }

    fun dismissPrompt() {
        checkMainThread()
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
            resetLoadState()
            _isLoading.value = displayUrl(url).isNotEmpty()
            _progress.value = 0
            _url.value = displayUrl(url)
            _title.value = ""
            session.setActive(active)
            session.setFocused(active)
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (!current(epoch)) return
            _isLoading.value = false
            _progress.value = 100
            if (success && _error.value == null && isWebUri(_url.value)) {
                _committedUrl.value = _url.value
                _committedRevision.value++
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

        override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
            if (current(epoch)) lastSessionState = GeckoSession.SessionState(sessionState)
        }
    }

    private inner class ContentHandler(private val epoch: Long) : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            if (current(epoch)) _title.value = title.orEmpty().takeUnless { it.equals("about:blank", ignoreCase = true) }.orEmpty()
        }

        override fun onCrash(session: GeckoSession) {
            if (!current(epoch)) return
            dismissPrompt()
            _isLoading.value = false
            _isSecure.value = false
            _fullScreen.value = false
            _error.value = appContext.getString(R.string.engine_error_crash)
        }

        override fun onKill(session: GeckoSession) {
            if (!current(epoch)) return
            dismissPrompt()
            _isLoading.value = false
            _isSecure.value = false
            _fullScreen.value = false
            _error.value = appContext.getString(R.string.engine_error_reclaimed)
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
            runCatching { response.body?.close() }
            if (!current(epoch)) return
            _isLoading.value = false
            notifyFeedback(R.string.engine_download_unsupported)
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
                    prompt.setDelegate(null)
                    runCatching { result.complete(null) }
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
            if (current(epoch)) notifyFeedback(R.string.engine_upload_unsupported)
            return GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onPopupPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.PopupPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            if (current(epoch)) notifyFeedback(R.string.engine_popup_blocked)
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
        }

        override fun onDateTimePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.DateTimePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            GeckoResult.fromValue(prompt.dismiss())
        override fun onColorPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ColorPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            GeckoResult.fromValue(prompt.dismiss())
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
}
