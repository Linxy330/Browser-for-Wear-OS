package com.kenny.browserforwearos.presentation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material3.*
import com.kenny.browserforwearos.R
import com.kenny.browserforwearos.data.SearchEngine
import com.kenny.browserforwearos.data.PageColorScheme
import com.kenny.browserforwearos.engine.EnginePrompt
import com.kenny.browserforwearos.engine.TabSessionController
import com.kenny.browserforwearos.engine.DeviceActivityMonitor
import com.kenny.browserforwearos.engine.ImeGeckoView
import com.kenny.browserforwearos.presentation.theme.BrowserForWearOSTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoView
import java.net.URI
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private val viewModel: BrowserViewModel by viewModels()
    private lateinit var activityMonitor: DeviceActivityMonitor
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        activityMonitor = DeviceActivityMonitor(this) { viewModel.setForeground(it) }
        if (savedInstanceState == null) receiveIntent(intent)
        setContent {
            val ambient by activityMonitor.ambient.collectAsState()
            BrowserForWearOSTheme {
                Box(Modifier.fillMaxSize()) {
                    BrowserNavHost(viewModel)
                    if (ambient) ProductAmbientScreen()
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveIntent(intent) }
    private fun receiveIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { viewModel.receiveIncoming(it, true) }
            Intent.ACTION_SEND -> if (intent.type == "text/plain") intent.getStringExtra(Intent.EXTRA_TEXT)?.let { viewModel.receiveIncoming(it, false) }
        }
    }
    override fun onPause() { activityMonitor.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); activityMonitor.onResume() }
    override fun onDestroy() { activityMonitor.close(); super.onDestroy() }
}

private enum class Screen { Home, Address, Web, Menu, Tabs, Bookmarks, History, Settings, ClearData, EditBookmark, Find, Reader, Downloads, About }

@Composable
fun BrowserNavHost(viewModel: BrowserViewModel) {
    val ready by viewModel.ready.collectAsState()
    val tabs by viewModel.tabs.collectAsState()
    val selected by viewModel.selectedTabId.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val history by viewModel.history.collectAsState()
    val search by viewModel.searchEngine.collectAsState()
    val reading by viewModel.reading.collectAsState()
    val incoming by viewModel.incoming.collectAsState()
    val downloadOffer by viewModel.downloadOffer.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var returnTo by rememberSaveable { mutableStateOf(Screen.Home) }
    var editingBookmark by rememberSaveable { mutableStateOf<String?>(null) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    var viewport by remember { mutableStateOf<GeckoView?>(null) }
    var incomingText by remember { mutableStateOf<String?>(null) }
    val controller = tabs.firstOrNull { it.id == selected }?.controller
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    fun requestZoom(factor: Float?) {
        val target = controller ?: return
        screen = Screen.Web
        scope.launch {
            delay(250) // Let the menu leave and the fit viewport complete its native layout.
            if (viewModel.controller !== target || screen != Screen.Web) return@launch
            val result = if (factor == null) target.zoom.resetToFit() else target.zoom.zoomBy(factor)
            result.accept { outcome ->
                if (outcome?.outcome !in setOf(PageZoomController.Outcome.APPLIED, PageZoomController.Outcome.CANCELLED)) viewModel.showMessage(R.string.zoom_limited)
            }
        }
    }
    LaunchedEffect(incoming, ready) {
        val request = incoming ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        if (request.openImmediately) {
            val needsTab = controller?.url?.value?.isNotEmpty() == true || controller?.privateMode == true
            if (needsTab && !viewModel.newTab()) {
                incomingText = request.value; returnTo = if (screen == Screen.Address) returnTo else screen; screen = Screen.Address
            } else if (viewModel.submitAddress(request.value)) screen = Screen.Web
        } else {
            incomingText = request.value; returnTo = if (screen == Screen.Address) returnTo else screen; screen = Screen.Address
        }
        viewModel.consumeIncoming(request.serial)
    }
    LaunchedEffect(downloadOffer) { if (downloadOffer > 0) screen = Screen.Downloads }
    LaunchedEffect(screen, selected, ready) { viewModel.setWebVisible(screen == Screen.Web || screen == Screen.Find) }
    BackHandler(enabled = screen != Screen.Home) {
        when (screen) {
            Screen.Web -> {
                if (controller?.fullScreen?.value == true) controller.exitFullScreen()
                else if (controller?.canGoBack?.value == true) controller.goBack()
                else screen = Screen.Home
            }
            Screen.Address -> { incomingText = null; keyboard?.hide(); screen = returnTo }
            Screen.Menu -> screen = Screen.Web
            Screen.EditBookmark -> screen = Screen.Bookmarks
            Screen.ClearData -> screen = Screen.Settings
            Screen.Find, Screen.Reader -> screen = Screen.Web
            else -> screen = Screen.Home
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (!ready || controller == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.restoring)) }
            return@Box
        }
        if (screen == Screen.Web || screen == Screen.Menu || screen == Screen.Find) {
            key(controller.tabId) {
                WebScreen(controller, immersive, screen == Screen.Web, { screen = Screen.Menu }, {
                    returnTo = Screen.Web; screen = Screen.Address
                }, { viewport = it })
            }
        }
        val currentUrl by controller.url.collectAsState()
        when (screen) {
            Screen.Home -> RoundList {
                item { PageTitle(stringResource(R.string.app_name)) }
                item { Action(R.string.b1_open_web) { returnTo = Screen.Home; screen = Screen.Address } }
                if (currentUrl.isNotEmpty()) item { Action(R.string.continue_browsing) { screen = Screen.Web } }
                item { Action(R.string.tabs) { screen = Screen.Tabs } }
                item { Action(R.string.bookmarks) { screen = Screen.Bookmarks } }
                item { Action(R.string.history) { screen = Screen.History } }
                item { Action(R.string.downloads) { screen = Screen.Downloads } }
                item { Action(R.string.settings) { screen = Screen.Settings } }
            }
            Screen.Address -> AddressScreen(incomingText ?: currentUrl,
                onSubmit = { if (viewModel.submitAddress(it)) { incomingText = null; keyboard?.hide(); screen = Screen.Web } },
                onCancel = { incomingText = null; keyboard?.hide(); screen = returnTo })
            Screen.Menu -> {
                val back by controller.canGoBack.collectAsState()
                val forward by controller.canGoForward.collectAsState()
                val loading by controller.isLoading.collectAsState()
                val url by controller.url.collectAsState()
                val secure by controller.isSecure.collectAsState()
                val desktop by controller.desktopMode.collectAsState()
                RoundList {
                    item { PageTitle(stringResource(R.string.tools)) }
                    if (controller.privateMode) item { Text(stringResource(R.string.private_notice), style = MaterialTheme.typography.bodySmall) }
                    item { Text(url, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall) }
                    item { Text(stringResource(if (secure) R.string.secure_connection else R.string.unconfirmed_connection), style = MaterialTheme.typography.bodySmall) }
                    item { Action(R.string.back_to_page) { screen = Screen.Web } }
                    item { Action(R.string.edit_address) { returnTo = Screen.Web; screen = Screen.Address } }
                    item { Action(R.string.go_back, back) { controller.goBack(); screen = Screen.Web } }
                    item { Action(R.string.go_forward, forward) { controller.goForward(); screen = Screen.Web } }
                    item { Action(if (loading) R.string.stop else R.string.reload) { if (loading) controller.stop() else controller.reload(); screen = Screen.Web } }
                    item { Action(R.string.tabs) { screen = Screen.Tabs } }
                    item { Action(R.string.find_in_page) { screen = Screen.Find } }
                    item { Action(R.string.reader_mode) { screen = Screen.Reader } }
                    item { Action(R.string.downloads) { screen = Screen.Downloads } }
                    item { Action(if (desktop) R.string.mobile_site else R.string.desktop_site) { controller.setDesktopMode(!desktop); screen = Screen.Web } }
                    item { Action(R.string.add_bookmark, url.startsWith("http")) { viewModel.addBookmark() } }
                    item { Action(R.string.zoom_in) { requestZoom(1.35f) } }
                    item { Action(R.string.zoom_out) { requestZoom(1 / 1.35f) } }
                    item { Action(R.string.fit_screen) { immersive = false; requestZoom(null) } }
                    item { Action(R.string.immersive) { immersive = true; screen = Screen.Web } }
                    item { Action(R.string.b1_home) { screen = Screen.Home } }
                    item { Action(R.string.settings) { screen = Screen.Settings } }
                }
            }
            Screen.Tabs -> RoundList {
                item { PageTitle(stringResource(R.string.tabs_count, tabs.size)) }
                item { Action(R.string.new_tab) { if (viewModel.newTab()) { returnTo = Screen.Tabs; screen = Screen.Address } } }
                item { Action(R.string.new_private_tab) { if (viewModel.newTab(privateMode = true)) { returnTo = Screen.Tabs; screen = Screen.Address } } }
                tabs.forEach { tab ->
                    item(key = tab.id) {
                        val title by tab.controller.title.collectAsState()
                        val url by tab.controller.url.collectAsState()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val label = title.ifEmpty { url }.ifEmpty { stringResource(R.string.empty_tab) }
                            Button(onClick = { viewModel.selectTab(tab.id); screen = if (url.isEmpty()) Screen.Home else Screen.Web }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text((if (tab.id == selected) "• " else "") + (if (tab.controller.privateMode) stringResource(R.string.private_prefix) else "") + label, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            Action(R.string.close_tab) { viewModel.closeTab(tab.id) }
                        }
                    }
                }
                item { Action(R.string.b1_home) { screen = Screen.Home } }
            }
            Screen.Bookmarks -> RoundList {
                item { PageTitle(stringResource(R.string.bookmarks)) }
                if (bookmarks.isEmpty()) item { Text(stringResource(R.string.no_bookmarks)) }
                bookmarks.forEach { entry -> item(key = entry.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { if (viewModel.submitAddress(entry.url)) screen = Screen.Web }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(entry.title.ifEmpty { entry.url }, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        Action(R.string.edit_title) { editingBookmark = entry.id; screen = Screen.EditBookmark }
                        Action(R.string.delete) { viewModel.removeBookmark(entry.id) }
                    }
                } }
                item { Action(R.string.b1_home) { screen = Screen.Home } }
            }
            Screen.EditBookmark -> {
                val bookmark = bookmarks.firstOrNull { it.id == editingBookmark }
                EditorScreen(bookmark?.title.orEmpty(), stringResource(R.string.edit_title), false,
                    onSubmit = { title -> bookmark?.let { viewModel.editBookmark(it.id, title) }; screen = Screen.Bookmarks },
                    onCancel = { screen = Screen.Bookmarks })
            }
            Screen.History -> RoundList {
                item { PageTitle(stringResource(R.string.history)) }
                if (history.isEmpty()) item { Text(stringResource(R.string.no_history)) }
                history.forEach { entry -> item(key = entry.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { if (viewModel.submitAddress(entry.url)) screen = Screen.Web }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(entry.title.ifEmpty { entry.url }, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        Text(entry.url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Action(R.string.delete) { viewModel.removeHistory(entry.id) }
                    }
                } }
                if (history.isNotEmpty()) item { Action(R.string.clear_history) { viewModel.clearHistory() } }
                item { Action(R.string.b1_home) { screen = Screen.Home } }
            }
            Screen.Settings -> RoundList {
                item { PageTitle(stringResource(R.string.settings)) }
                item { Text(stringResource(R.string.search_engine)) }
                SearchEngine.entries.forEach { engine -> item {
                    Button(onClick = { viewModel.setSearchEngine(engine) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text((if (engine == search) "• " else "") + when(engine) { SearchEngine.BING -> "Bing"; SearchEngine.GOOGLE -> "Google"; SearchEngine.DUCKDUCKGO -> "DuckDuckGo" }) }
                } }
                item { Action(R.string.clear_site_data) { screen = Screen.ClearData } }
                item { Text(stringResource(R.string.web_text_size, reading.textPercent)) }
                listOf(75, 100, 125, 150, 175, 200).forEach { percent -> item {
                    Button(onClick = { viewModel.setReading(reading.copy(textPercent = percent)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text((if (percent == reading.textPercent) "• " else "") + "$percent%")
                    }
                } }
                item { Text(stringResource(R.string.page_appearance)) }
                PageColorScheme.entries.forEach { scheme -> item {
                    val name = when (scheme) { PageColorScheme.SYSTEM -> R.string.follow_system; PageColorScheme.LIGHT -> R.string.light_pages; PageColorScheme.DARK -> R.string.dark_pages }
                    Button(onClick = { viewModel.setReading(reading.copy(colorScheme = scheme)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text((if (scheme == reading.colorScheme) "• " else "") + stringResource(name))
                    }
                } }
                item { Action(R.string.about_browser) { screen = Screen.About } }
                item { Action(R.string.b1_home) { screen = Screen.Home } }
            }
            Screen.ClearData -> RoundList {
                item { PageTitle(stringResource(R.string.clear_site_data)) }
                item { Text(stringResource(R.string.clear_site_warning)) }
                item { Action(R.string.clear_confirm) { viewModel.clearSiteData(); screen = Screen.Settings } }
                item { Action(R.string.b1_cancel) { screen = Screen.Settings } }
            }
            Screen.Web -> Unit
            Screen.Find -> ProductFindScreen(controller) { screen = Screen.Web }
            Screen.Reader -> ProductReaderScreen(controller) { screen = Screen.Web }
            Screen.Downloads -> ProductDownloadScreen(viewModel) { screen = Screen.Home }
            Screen.About -> ProductAboutScreen { screen = Screen.Settings }
        }
        val appFeedback by viewModel.feedback.collectAsState()
        val engineFeedback by controller.feedback.collectAsState()
        (appFeedback ?: engineFeedback)?.let { message ->
            LaunchedEffect(message) { delay(3500); viewModel.consumeFeedback(); controller.consumeFeedback() }
            Box(Modifier.fillMaxSize().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                Text(message, textAlign = TextAlign.Center, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small).padding(12.dp))
            }
        }
        EnginePromptDialogs(controller, viewModel.downloadManager)
        ProductContextActions(controller, onOpen = { url, newTab ->
            val privateMode = controller.privateMode
            if (!newTab || viewModel.newTab(privateMode)) {
                if (viewModel.submitAddress(url)) screen = Screen.Web
            }
        })
    }
}

/** End padding lets every item move into the readable centre of a round display. */
@Composable
internal fun RoundList(content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(state = state,
            modifier = Modifier.fillMaxSize().onRotaryScrollEvent { event -> scope.launch { state.scrollBy(event.verticalScrollPixels) }; true }.focusRequester(focus).focusable(),
            contentPadding = PaddingValues(horizontal = maxWidth * .13f, vertical = maxHeight * .25f),
            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
internal fun PageTitle(text: String) = Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)

@Composable
internal fun Action(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(label), textAlign = TextAlign.Center) }
}

@Composable
fun AddressScreen(initial: String = "", onSubmit: (String) -> Unit, onCancel: () -> Unit) =
    EditorScreen(initial, stringResource(R.string.b1_address_title), true, onSubmit, onCancel)

@Composable
private fun EditorScreen(initial: String, title: String, voice: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    val context = LocalContext.current
    val speech = remember { Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM) }
    val canSpeak = remember { speech.resolveActivity(context.packageManager) != null }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = it }
    }
    RoundList {
        item { PageTitle(title) }
        item { BasicTextField(value = text, onValueChange = { text = it }, textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { if (text.isNotBlank()) onSubmit(text) }),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small).padding(12.dp),
            decorationBox = { inner -> Box { if (text.isEmpty()) Text(stringResource(R.string.b1_address_hint), color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } }) }
        item { Action(R.string.b1_go, text.isNotBlank()) { onSubmit(text) } }
        if (voice && canSpeak) item { Action(R.string.voice_input) { launcher.launch(speech) } }
        item { Action(R.string.b1_cancel, onClick = onCancel) }
    }
}

@Composable
private fun WebScreen(controller: TabSessionController, immersive: Boolean, active: Boolean, onMenu: () -> Unit, onAddress: () -> Unit, onView: (GeckoView) -> Unit) {
    val url by controller.url.collectAsState()
    val title by controller.title.collectAsState()
    val loading by controller.isLoading.collectAsState()
    val progress by controller.progress.collectAsState()
    val error by controller.error.collectAsState()
    val suspended by controller.suspendedState.collectAsState()
    val currentSession by controller.sessionState.collectAsState()
    val selection by controller.pageTools.selectionState.collectAsState()
    val contextTarget by controller.pageTools.contextState.collectAsState()
    val prompt by controller.pendingPrompt.collectAsState()
    var nativeView by remember { mutableStateOf<GeckoView?>(null) }
    val releaseHooks = remember(controller) { mutableMapOf<GeckoView, () -> Unit>() }
    LaunchedEffect(active, suspended, currentSession, nativeView, selection == null, contextTarget == null, prompt == null) {
        if (active && !suspended && selection == null && contextTarget == null && prompt == null) nativeView?.requestFocus()
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val side = min(maxWidth.value, maxHeight.value).dp * .68f
        val webModifier = if (immersive) Modifier.fillMaxSize() else Modifier.size(side)
        AndroidView(factory = { context -> ImeGeckoView(context).apply {
            setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
            if (!suspended && currentSession.isOpen) { setSession(currentSession); controller.zoom.attach(this, currentSession) }
            val hook: () -> Unit = { controller.zoom.detach(this); releaseSession() }
            releaseHooks[this] = hook
            controller.onBeforeSuspend = hook
            setOnGenericMotionListener { _, event ->
                if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                    controller.scrollBy(-event.getAxisValue(MotionEvent.AXIS_SCROLL) * 48 * resources.displayMetrics.density); true
                } else false
            }
            setOnTouchListener { _, event -> controller.zoom.observeTouch(event); false }
            nativeView = this
            onView(this)
        } }, update = { view ->
            if (!suspended && currentSession.isOpen && view.session !== currentSession) {
                controller.zoom.detach(view)
                view.releaseSession()
                view.setSession(currentSession)
                controller.zoom.attach(view, currentSession)
            }
            controller.onBeforeSuspend = releaseHooks[view]
            onView(view)
        },
            onRelease = { view ->
                controller.zoom.detach(view)
                view.releaseSession()
                val ownedHook = releaseHooks.remove(view)
                if (controller.onBeforeSuspend === ownedHook) controller.onBeforeSuspend = null
            }, modifier = webModifier.align(Alignment.Center))
        Text(text = (if (controller.privateMode) stringResource(R.string.private_prefix) else "") + if (loading) stringResource(R.string.b1_loading, progress) else title.ifEmpty { runCatching { URI(url).host }.getOrNull() ?: url },
            color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp).fillMaxWidth(.50f).background(Color.Black).clickable(onClick = onAddress))
        error?.let { message ->
            Box(Modifier.size(side).align(Alignment.Center).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    Action(R.string.reload) { controller.reload() }
                }
            }
        }
        Button(onClick = onMenu, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.tools)) }
    }
}

/** 引擎原生提示：alert/confirm/prompt/HTTP 认证/选择框/离开确认。 */
@Composable
fun EnginePromptDialogs(controller: TabSessionController, downloads: com.kenny.browserforwearos.data.download.BrowserDownloadManager? = null) {
    val prompt by controller.pendingPrompt.collectAsState()
    val current = prompt ?: return

    when (current) {
        is EnginePrompt.Alert -> {
            // GeckoView 中 alert 的"确认"即 dismiss 语义
            AlertDialog(
                visible = true,
                onDismissRequest = { controller.dismissPrompt() },
                confirmButton = {
                    Button(onClick = { controller.dismissPrompt() }) {
                        Text(stringResource(R.string.b1_ok))
                    }
                },
                title = { Text(current.prompt.title.orEmpty().ifEmpty { current.origin }) },
                text = { Text(current.origin + "\n" + current.prompt.message.orEmpty()) },
            )
        }
        is EnginePrompt.Confirm -> {
            AlertDialog(
                visible = true,
                onDismissRequest = { controller.confirmButton(false) },
                confirmButton = {
                    Button(onClick = { controller.confirmButton(true) }) {
                        Text(stringResource(R.string.b1_ok))
                    }
                },
                title = { Text(current.prompt.title.orEmpty().ifEmpty { current.origin }) },
                text = { Text(current.origin + "\n" + current.prompt.message.orEmpty()) },
                dismissButton = {
                    OutlinedButton(onClick = { controller.confirmButton(false) }) {
                        Text(stringResource(R.string.b1_cancel))
                    }
                },
            )
        }
        is EnginePrompt.Text -> {
            var value by remember(current) { mutableStateOf(current.prompt.defaultValue.orEmpty()) }
            AlertDialog(
                visible = true,
                onDismissRequest = { controller.dismissPrompt() },
                confirmButton = {
                    Button(onClick = { controller.confirmText(value) }) {
                        Text(stringResource(R.string.b1_ok))
                    }
                },
                title = { Text(current.prompt.title.orEmpty().ifEmpty { current.origin }) },
                text = { Text(current.origin + "\n" + current.prompt.message.orEmpty()) },
                dismissButton = {
                    OutlinedButton(onClick = { controller.dismissPrompt() }) {
                        Text(stringResource(R.string.b1_cancel))
                    }
                },
                content = {
                    item {
                        BasicTextField(
                            value = value,
                            onValueChange = { value = it },
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                                .padding(8.dp),
                        )
                    }
                },
            )
        }
        is EnginePrompt.Auth -> {
            var user by remember(current) { mutableStateOf("") }
            var pass by remember(current) { mutableStateOf("") }
            AlertDialog(
                visible = true,
                onDismissRequest = { controller.dismissPrompt() },
                confirmButton = {
                    Button(onClick = { controller.confirmAuth(user, pass) }) {
                        Text(stringResource(R.string.b1_ok))
                    }
                },
                title = { Text(current.prompt.title.orEmpty().ifEmpty { current.origin }) },
                text = { Text(current.origin + "\n" + current.prompt.message.orEmpty()) },
                dismissButton = {
                    OutlinedButton(onClick = { controller.dismissPrompt() }) {
                        Text(stringResource(R.string.b1_cancel))
                    }
                },
                content = {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BasicTextField(
                                value = user,
                                onValueChange = { user = it },
                                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                                    .padding(8.dp),
                                decorationBox = { inner ->
                                    Box {
                                        if (user.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.b1_auth_user),
                                                style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                            BasicTextField(
                                value = pass,
                                onValueChange = { pass = it },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                                    .padding(8.dp),
                                decorationBox = { inner ->
                                    Box {
                                        if (pass.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.b1_auth_password),
                                                style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }
        is EnginePrompt.Choice -> {
            data class ChoiceRow(
                val key: String,
                val choice: org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt.Choice,
                val depth: Int,
                val disabled: Boolean,
                val group: Boolean,
            )
            val rows = remember(current) {
                buildList<ChoiceRow> {
                    fun append(
                        choices: Array<org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt.Choice>,
                        depth: Int,
                        ancestorDisabled: Boolean,
                        path: String,
                    ) {
                        choices.forEachIndexed { index, choice ->
                            val key = "$path/$index"
                            val children = choice.items
                            val disabled = ancestorDisabled || choice.disabled
                            add(ChoiceRow(key, choice, depth, disabled, children != null))
                            if (children != null) append(children, depth + 1, disabled, key)
                        }
                    }
                    append(current.prompt.choices, 0, false, "choice")
                }
            }
            val multiple = current.prompt.type == org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE
            var selectedIds by remember(current) {
                mutableStateOf(rows.filter { !it.group && !it.choice.separator && it.choice.selected }.map { it.choice.id }.toSet())
            }
            AlertDialog(
                visible = true,
                onDismissRequest = { controller.dismissPrompt() },
                confirmButton = {
                    if (multiple) {
                        Button(onClick = {
                            controller.confirmChoices(rows.filter { !it.group && !it.choice.separator && it.choice.id in selectedIds }.map { it.choice }.toTypedArray())
                        }) { Text(stringResource(R.string.b1_ok)) }
                    } else {
                        OutlinedButton(onClick = { controller.dismissPrompt() }) { Text(stringResource(R.string.b1_cancel)) }
                    }
                },
                dismissButton = {
                    if (multiple) OutlinedButton(onClick = { controller.dismissPrompt() }) { Text(stringResource(R.string.b1_cancel)) }
                },
                title = { Text(current.prompt.title.orEmpty().ifEmpty { current.origin }) },
                text = { Text(current.origin + "\n" + current.prompt.message.orEmpty()) },
                content = {
                    rows.forEach { row ->
                        item(key = row.key) {
                            val choice = row.choice
                            when {
                                choice.separator -> Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurfaceVariant))
                                row.group -> Text(
                                    text = choice.label,
                                    color = if (row.disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth().padding(start = (row.depth * 8).dp, top = 8.dp),
                                )
                                else -> OutlinedButton(
                                    enabled = !row.disabled,
                                    onClick = {
                                        if (multiple) selectedIds = if (choice.id in selectedIds) selectedIds - choice.id else selectedIds + choice.id
                                        else controller.confirmChoice(choice)
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(start = (row.depth * 8).dp).heightIn(min = 48.dp),
                                ) {
                                    Text(
                                        text = (if (choice.id in selectedIds) "✓ " else if (multiple) "○ " else "") + choice.label,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
        is EnginePrompt.BeforeUnload -> {
            AlertDialog(
                visible = true,
                onDismissRequest = { controller.dismissPrompt() },
                confirmButton = {
                    Button(onClick = { controller.confirmLeave() }) {
                        Text(stringResource(R.string.b1_leave_ok))
                    }
                },
                title = { Text(stringResource(R.string.b1_leave_title)) },
                text = { Text(stringResource(R.string.b1_leave_message)) },
                dismissButton = {
                    OutlinedButton(onClick = { controller.dismissPrompt() }) {
                        Text(stringResource(R.string.b1_stay))
                    }
                },
            )
        }
        is EnginePrompt.File -> ProductFilePrompt(controller, current, downloads)
        is EnginePrompt.DateTime -> ProductValuePrompt(controller, current)
        is EnginePrompt.Color -> ProductValuePrompt(controller, current)
    }
}
