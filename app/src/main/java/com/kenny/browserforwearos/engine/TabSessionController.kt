package com.kenny.browserforwearos.engine

import android.content.Context
import android.util.Log
import androidx.annotation.UiThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** 新标签 ID 生成；B3 三标签管理复用。 */
fun newTabId(): String = "tab-" + UUID.randomUUID().toString()

/**
 * 单个标签的引擎会话控制器：封装 GeckoSession 与全部委托回调，
 * 以 StateFlow 暴露给 UI（规范 3.1：引擎回调是状态的唯一来源）。
 *
 * 线程约定：GeckoView 委托回调均在主线程；@UiThread 方法仅可主线程调用。
 * 关闭后（[close]）忽略一切迟到回调，防止过期异步结果覆盖当前页面（规范 3.2 规则 3）。
 */
@UiThread
class TabSessionController(
    context: Context,
    val tabId: String = newTabId(),
) {
    private val appContext: Context = context.applicationContext

    private val runtime: GeckoRuntime by lazy { GeckoRuntimeHolder.runtime(appContext) }

    val session: GeckoSession = GeckoSession()

    // ---- 引擎回调产生的真实状态 ----------------------------------------
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

    /** 由引擎 SecurityInformation 确认的安全状态，不仅凭 https:// 前缀。 */
    private val _isSecure = MutableStateFlow(false)
    val isSecure: StateFlow<Boolean> = _isSecure.asStateFlow()

    /** 一次性用户反馈（弹窗拦截、未支持类型说明等）；UI 消费后置空。 */
    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    /** 待用户应答的引擎提示（alert/confirm/prompt/auth/选择框/上传）。 */
    private val _pendingPrompt = MutableStateFlow<EnginePrompt?>(null)
    val pendingPrompt: StateFlow<EnginePrompt?> = _pendingPrompt.asStateFlow()

    /** 会话是否已关闭；关闭后所有委托回调短路。 */
    @Volatile
    var closed: Boolean = false
        private set

    init {
        session.open(runtime)
        session.navigationDelegate = NavigationHandler()
        session.progressDelegate = ProgressHandler()
        session.contentDelegate = ContentHandler()
        session.promptDelegate = PromptHandler()
        session.permissionDelegate = PermissionHandler()
    }

    // ---- 引擎操作 --------------------------------------------------------

    fun loadUri(uri: String) {
        if (closed) return
        session.loadUri(uri)
    }

    fun reload() {
        if (closed) return
        session.reload()
    }

    fun stop() {
        if (closed) return
        session.stop()
    }

    fun goBack() {
        if (closed) return
        session.goBack()
    }

    fun goForward() {
        if (closed) return
        session.goForward()
    }

    /**
     * 可见性驱动的引擎暂停（规范 3.2 规则 6 / F12）。
     * B1 真机验证 setActive(false) 的实际脚本/网络/媒体行为，不能当作默认暂停证明。
     */
    fun setActive(active: Boolean) {
        if (closed) return
        session.setActive(active)
    }

    /** 关闭标签：释放 Session；不关闭 Runtime（进程级单例，规范 3.2 规则 2）。 */
    fun close() {
        if (closed) return
        closed = true
        _pendingPrompt.value?.let { pending ->
            runCatching { pending.geckoResult.complete(pending.basePrompt.dismiss()) }
        }
        _pendingPrompt.value = null
        session.close()
    }

    // ---- Prompt 应答（UI 从 pendingPrompt 收集后调用） --------------------

    fun dismissPrompt() {
        val pending = _pendingPrompt.value ?: return
        _pendingPrompt.value = null
        runCatching { pending.geckoResult.complete(pending.basePrompt.dismiss()) }
            .onFailure { Log.w(TAG, "dismissPrompt failed", it) }
    }

    fun confirmButton(positive: Boolean) {
        val pending = _pendingPrompt.value as? EnginePrompt.Confirm ?: return
        _pendingPrompt.value = null
        val type = if (positive) {
            GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE
        } else {
            GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE
        }
        runCatching { pending.geckoResult.complete(pending.prompt.confirm(type)) }
            .onFailure { Log.w(TAG, "confirmButton failed", it) }
    }

    fun confirmText(text: String) {
        val pending = _pendingPrompt.value as? EnginePrompt.Text ?: return
        _pendingPrompt.value = null
        runCatching { pending.geckoResult.complete(pending.prompt.confirm(text)) }
            .onFailure { Log.w(TAG, "confirmText failed", it) }
    }

    fun confirmAuth(user: String, password: String) {
        val pending = _pendingPrompt.value as? EnginePrompt.Auth ?: return
        _pendingPrompt.value = null
        runCatching { pending.geckoResult.complete(pending.prompt.confirm(user, password)) }
            .onFailure { Log.w(TAG, "confirmAuth failed", it) }
    }

    fun confirmChoice(choice: GeckoSession.PromptDelegate.ChoicePrompt.Choice) {
        val pending = _pendingPrompt.value as? EnginePrompt.Choice ?: return
        _pendingPrompt.value = null
        runCatching { pending.geckoResult.complete(pending.prompt.confirm(choice)) }
            .onFailure { Log.w(TAG, "confirmChoice failed", it) }
    }

    /** 已确认的 BeforeUnload（离开页面）。 */
    fun confirmLeave() {
        val pending = _pendingPrompt.value as? EnginePrompt.BeforeUnload ?: return
        _pendingPrompt.value = null
        runCatching { pending.geckoResult.complete(pending.prompt.confirm(AllowOrDeny.ALLOW)) }
            .onFailure { Log.w(TAG, "confirmLeave failed", it) }
    }

    fun consumeFeedback() {
        _feedback.value = null
    }

    private fun notifyFeedback(message: String) {
        Log.i(TAG, "feedback: $message")
        _feedback.value = message
    }

    // ---- 委托实现 --------------------------------------------------------

    private inner class NavigationHandler : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            permissions: List<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            if (closed || url == null) return
            _url.value = url
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            if (closed) return
            _canGoBack.value = canGoBack
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            if (closed) return
            _canGoForward.value = canGoForward
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny> {
            if (closed) return GeckoResult.deny()
            val uri = request.uri
            val scheme = when {
                uri.contains("://") -> uri.substringBefore("://").lowercase()
                uri.startsWith("about:") -> "about"
                uri.startsWith("data:") -> "data"
                uri.startsWith("blob:") -> "blob"
                else -> ""
            }
            return when (scheme) {
                "http", "https", "about", "blob", "data" -> GeckoResult.allow()
                // P0 明确分支：特殊协议不执行，反馈用户（规范 4.3/4.4）
                "intent", "mailto", "tel", "sms", "geo", "market", "file", "javascript" -> {
                    notifyFeedback("此设备暂不支持在网页中打开 $scheme: 链接")
                    GeckoResult.deny()
                }
                else -> {
                    notifyFeedback("已阻止未支持的链接类型：$uri")
                    GeckoResult.deny()
                }
            }
        }

        override fun onNewSession(
            session: GeckoSession,
            uri: String,
        ): GeckoResult<GeckoSession> {
            if (closed) return GeckoResult.fromValue(null)
            // P0：站点自动弹窗默认拦截并反馈；新窗口转标签在 B3 标签体系实现（规范 4.4）
            notifyFeedback("已拦截站点弹窗")
            return GeckoResult.fromValue(null)
        }

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError,
        ): GeckoResult<String> {
            if (closed) return GeckoResult.fromValue(null)
            // 返回 null → 引擎显示内置错误页（含 TLS 失败页，规范 F11 基线）
            Log.w(TAG, "load error uri=$uri category=${error.category} code=${error.code}")
            return GeckoResult.fromValue(null)
        }
    }

    private inner class ProgressHandler : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            if (closed) return
            _isLoading.value = true
            _progress.value = 0
            _url.value = url
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (closed) return
            _isLoading.value = false
            _progress.value = 100
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            if (closed) return
            _progress.value = progress
        }

        override fun onSecurityChange(
            session: GeckoSession,
            securityInfo: GeckoSession.ProgressDelegate.SecurityInformation,
        ) {
            if (closed) return
            _isSecure.value = securityInfo.isSecure && !securityInfo.isException
        }
    }

    private inner class ContentHandler : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            if (closed) return
            _title.value = title.orEmpty()
        }

        override fun onCrash(session: GeckoSession) {
            if (closed) return
            _isLoading.value = false
            notifyFeedback("页面进程崩溃，已停止该页")
        }

        override fun onKill(session: GeckoSession) {
            if (closed) return
            _isLoading.value = false
            notifyFeedback("页面被系统回收，返回后重新加载")
        }

        override fun onFocusRequest(session: GeckoSession) {
            // 网页请求焦点：保持当前焦点所有者策略，B2 细化
        }

        override fun onCloseRequest(session: GeckoSession) {
            if (closed) return
            // window.close()：B3 标签体系处理关闭请求
            notifyFeedback("网页请求关闭此标签")
        }
    }

    private inner class PermissionHandler : GeckoSession.PermissionDelegate {
        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: GeckoSession.PermissionDelegate.Callback,
        ) {
            // P0：定位/麦克风/摄像头等一律拒绝（规范 4.4）
            callback.reject()
        }

        override fun onContentPermissionRequest(
            session: GeckoSession,
            permission: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int> {
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            callback.reject()
        }
    }

    private inner class PromptHandler : GeckoSession.PromptDelegate {
        private fun <T : EnginePrompt> queue(create: (GeckoResult<GeckoSession.PromptDelegate.PromptResponse>) -> T): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            if (closed) {
                _pendingPrompt.value = null
                return GeckoResult.fromValue(null)
            }
            _pendingPrompt.value = create(result)
            return result
        }

        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AlertPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = queue { EnginePrompt.Alert(prompt, it) }

        override fun onButtonPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ButtonPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = queue { EnginePrompt.Confirm(prompt, it) }

        override fun onTextPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.TextPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = queue { EnginePrompt.Text(prompt, it) }

        override fun onAuthPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AuthPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = queue { EnginePrompt.Auth(prompt, it) }

        override fun onChoicePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ChoicePrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = queue { EnginePrompt.Choice(prompt, it) }

        override fun onFilePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FilePrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            notifyFeedback("此版本暂不支持文件上传")
            return GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onPopupPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.PopupPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            notifyFeedback("已拦截站点弹窗")
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
        }

        override fun onDateTimePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.DateTimePrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            // P0 不做日期时间选择 UI：返回默认值，让引擎走系统行为
            return GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onColorPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ColorPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            return GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = queue { EnginePrompt.BeforeUnload(prompt, it) }
    }

    companion object {
        private const val TAG = "TabSession"
    }
}

/** UI 可应答的引擎提示集合；geckoResult 由控制器持有并完成。 */
sealed class EnginePrompt(
    val basePrompt: GeckoSession.PromptDelegate.BasePrompt,
    val geckoResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
) {
    class Alert(
        val prompt: GeckoSession.PromptDelegate.AlertPrompt,
        geckoResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
    ) : EnginePrompt(prompt, geckoResult)

    class Confirm(
        val prompt: GeckoSession.PromptDelegate.ButtonPrompt,
        geckoResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
    ) : EnginePrompt(prompt, geckoResult)

    class Text(
        val prompt: GeckoSession.PromptDelegate.TextPrompt,
        geckoResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
    ) : EnginePrompt(prompt, geckoResult)

    class Auth(
        val prompt: GeckoSession.PromptDelegate.AuthPrompt,
        geckoResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
    ) : EnginePrompt(prompt, geckoResult)

    class Choice(
        val prompt: GeckoSession.PromptDelegate.ChoicePrompt,
        geckoResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
    ) : EnginePrompt(prompt, geckoResult)

    class BeforeUnload(
        val prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
        geckoResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
    ) : EnginePrompt(prompt, geckoResult)
}
