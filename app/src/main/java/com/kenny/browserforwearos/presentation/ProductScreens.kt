package com.kenny.browserforwearos.presentation

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material3.*
import com.kenny.browserforwearos.BuildConfig
import com.kenny.browserforwearos.R
import com.kenny.browserforwearos.data.download.*
import com.kenny.browserforwearos.data.forms.FormValues
import com.kenny.browserforwearos.engine.EnginePrompt
import com.kenny.browserforwearos.engine.PageTools
import com.kenny.browserforwearos.engine.TabSessionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession

/** Keep the browsing screen mounted beneath a sparse, non-interactive ambient cover. */
@Composable
internal fun ProductAmbientScreen() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); now = System.currentTimeMillis() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
    ) {}, contentAlignment = Alignment.Center) {
        Text(android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)),
            color = Color.LightGray, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun ProductFindScreen(controller: TabSessionController, onBack: () -> Unit) {
    val tools = controller.pageTools
    val result by tools.findState.collectAsState()
    var query by remember(controller.tabId) { mutableStateOf(result.query) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun find(backward: Boolean) {
        if (query == result.query) tools.nextMatch(backward) else tools.find(query, backward)
        keyboard?.hide()
        onBack()
    }
    RoundList {
        item { PageTitle(stringResource(R.string.find_in_page)) }
        item { ProductTextField(query, { query = it.take(1024) }, stringResource(R.string.find_hint)) { find(false) } }
        if (result.query.isNotEmpty()) item {
            Text(if (result.total > 0) stringResource(R.string.find_results, result.current, result.total) else stringResource(R.string.find_no_matches))
        }
        item { Action(R.string.find_next, query.isNotBlank() && !result.busy) { find(false) } }
        item { Action(R.string.find_previous, query.isNotBlank() && !result.busy) { find(true) } }
        item { Action(R.string.find_clear) { tools.clearFind(); onBack() } }
        item { Action(R.string.back_to_page, onClick = onBack) }
    }
}

@Composable
internal fun ProductReaderScreen(controller: TabSessionController, onBack: () -> Unit) {
    val state by controller.pageTools.readerState.collectAsState()
    DisposableEffect(controller) { onDispose { controller.pageTools.closeReader() } }
    LaunchedEffect(controller) {
        if (state.status == PageTools.ReaderState.Status.IDLE) controller.pageTools.readArticle(controller.title.value)
    }
    val paragraphs = remember(state.article) {
        state.text.split(Regex("\\n\\s*\\n")).filter(String::isNotBlank).flatMap { paragraph ->
            // Never split a surrogate pair while keeping individual Compose text nodes bounded.
            buildList {
                var start = 0
                while (start < paragraph.length) {
                    var end = minOf(start + 1200, paragraph.length)
                    if (end < paragraph.length && paragraph[end - 1].isHighSurrogate()) end--
                    add(paragraph.substring(start, end)); start = end
                }
            }
        }
    }
    RoundList {
        item { PageTitle(state.title.ifBlank { stringResource(R.string.reader_mode) }) }
        when (state.status) {
            PageTools.ReaderState.Status.IDLE, PageTools.ReaderState.Status.LOADING -> item { Text(stringResource(R.string.reader_loading)) }
            PageTools.ReaderState.Status.FAILED -> item { Text(stringResource(if (state.failure == PageTools.ReaderFailure.NOT_ARTICLE) R.string.reader_unavailable else R.string.reader_failed)) }
            PageTools.ReaderState.Status.READY -> {
                item { Text(runCatching { Uri.parse(state.article?.url).host.orEmpty() }.getOrDefault(""), style = MaterialTheme.typography.bodySmall) }
                paragraphs.forEachIndexed { index, text -> item(key = "paragraph-$index") { Text(text, style = MaterialTheme.typography.bodyLarge) } }
            }
        }
        item { Action(R.string.back_to_page, onClick = onBack) }
    }
}

@Composable
internal fun ProductDownloadScreen(model: BrowserViewModel, onBack: () -> Unit) {
    val manager = model.downloadManager
    val downloads by manager.downloads.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportingId by rememberSaveable { mutableStateOf<String?>(null) }
    var savingPublic by remember { mutableStateOf(setOf<String>()) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = exportingId
        exportingId = null
        val target = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && id != null && target != null) scope.launch {
            val outcome = manager.export(id, target)
            if (outcome !in setOf(ExportResult.SUCCESS, ExportResult.CANCELLED)) model.showMessage(R.string.download_export_failed)
        }
    }
    RoundList {
        item { PageTitle(stringResource(R.string.downloads)) }
        item { Text(stringResource(R.string.download_limits), style = MaterialTheme.typography.bodySmall) }
        if (downloads.isEmpty()) item { Text(stringResource(R.string.download_empty)) }
        downloads.forEach { item -> item(key = item.id) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.fileName, style = MaterialTheme.typography.titleSmall)
                if (item.sourceHost.isNotEmpty()) Text(stringResource(R.string.download_source, item.sourceHost), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(when (item.status) {
                    DownloadStatus.WAITING -> R.string.download_status_waiting
                    DownloadStatus.DOWNLOADING -> R.string.download_status_downloading
                    DownloadStatus.COMPLETED -> R.string.download_status_completed
                    DownloadStatus.CANCELLED -> R.string.download_status_cancelled
                    DownloadStatus.FAILED -> R.string.download_status_failed
                }))
                Text(stringResource(R.string.download_progress, Formatter.formatShortFileSize(context, item.receivedBytes), item.totalBytes?.let { Formatter.formatShortFileSize(context, it) } ?: stringResource(R.string.download_unknown_size)), style = MaterialTheme.typography.bodySmall)
                item.error?.let { error -> Text(stringResource(downloadErrorString(error)), style = MaterialTheme.typography.bodySmall) }
                if (item.status == DownloadStatus.WAITING) Action(R.string.download_save) { manager.start(item.id) }
                if (item.status in setOf(DownloadStatus.WAITING, DownloadStatus.DOWNLOADING)) Action(R.string.download_cancel) { manager.cancel(item.id) }
                if (item.canRetry) Action(R.string.download_retry) { manager.retry(item.id) }
                if (item.status == DownloadStatus.COMPLETED) {
                    Action(R.string.download_public_export, item.id !in savingPublic) {
                        savingPublic = savingPublic + item.id
                        scope.launch {
                            try {
                                val outcome = manager.exportToDownloads(item.id)
                                if (outcome !in setOf(ExportResult.SUCCESS, ExportResult.CANCELLED)) model.showMessage(R.string.download_export_failed)
                            } finally { savingPublic = savingPublic - item.id }
                        }
                    }
                    val intent = manager.createExportIntent(item.id)
                    if (intent != null) Action(R.string.download_export) {
                        exportingId = item.id
                        runCatching { export.launch(intent) }.onFailure { exportingId = null; model.showMessage(R.string.download_export_failed) }
                    }
                }
                if (item.status != DownloadStatus.DOWNLOADING) Action(R.string.download_delete) { manager.delete(item.id) }
            }
        } }
        item { Text(stringResource(R.string.download_device_only), style = MaterialTheme.typography.bodySmall) }
        item { Text(stringResource(R.string.download_public_notice), style = MaterialTheme.typography.bodySmall) }
        item { Action(R.string.b1_home, onClick = onBack) }
    }
}

private fun downloadErrorString(error: DownloadError) = when (error) {
    DownloadError.SIZE_LIMIT -> R.string.download_file_limit
    DownloadError.STORAGE_LIMIT -> R.string.download_storage_limit
    DownloadError.READ_TIMEOUT -> R.string.download_timeout
    DownloadError.INTERRUPTED -> R.string.download_interrupted
    DownloadError.EXPIRED -> R.string.download_expired
    DownloadError.BUSY -> R.string.download_busy
    DownloadError.MISSING_FILE -> R.string.download_missing
    DownloadError.HTTP_ERROR -> R.string.download_http_error
    else -> R.string.download_io_error
}

@Composable
internal fun ProductFilePrompt(controller: TabSessionController, current: EnginePrompt.File, manager: BrowserDownloadManager?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val empty = remember { MutableStateFlow<List<DownloadItem>>(emptyList()) }
    val files by (manager?.downloads ?: empty).collectAsState()
    val support = remember(context) { SafUploadSupport(context) }
    val stager = remember(context) { UploadStager(context) }
    val multiple = current.prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
    val systemIntent = remember(current) { support.createOpenIntent(current.prompt.mimeTypes, multiple) }
    var selected by remember(current) { mutableStateOf(setOf<String>()) }
    var invalid by remember(current) { mutableStateOf(false) }
    var submitting by remember(current) { mutableStateOf(false) }
    val launch = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controller.endFilePicker()
        if (controller.pendingPrompt.value !== current) return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val choice = support.selectedUris(result.data, multiple)
            if (choice.error == null && choice.uris.isNotEmpty()) {
                submitting = true
                scope.launch {
                    var unclaimed: PreparedUpload? = null
                    try {
                        val preparation = stager.prepare(choice.uris)
                        unclaimed = preparation.lease
                        withContext(Dispatchers.Main.immediate) {
                            submitting = false
                            val lease = preparation.lease
                            if (controller.pendingPrompt.value === current) {
                                if (lease != null && controller.confirmFiles(lease.uris, lease)) unclaimed = null
                                else invalid = true
                            }
                        }
                    } finally { unclaimed?.close() }
                }
            } else invalid = choice.error != null
        }
    }
    BackHandler { controller.dismissPrompt() }
    RoundList {
        item { PageTitle(stringResource(R.string.upload_file)) }
        item { Text(stringResource(R.string.upload_from_site, current.origin), style = MaterialTheme.typography.bodySmall) }
        if (multiple) item { Text(stringResource(R.string.upload_multiple)) }
        if (systemIntent != null) item { Action(R.string.upload_system, !submitting) {
            if (controller.beginFilePicker()) runCatching { launch.launch(systemIntent) }.onFailure { controller.endFilePicker(); invalid = true }
        } } else item { Text(stringResource(R.string.upload_unavailable), style = MaterialTheme.typography.bodySmall) }
        item { Text(stringResource(R.string.upload_downloads)) }
        val available = files.filter { it.status == DownloadStatus.COMPLETED && uploadMatches(it, current.prompt.mimeTypes) }
        if (available.isEmpty()) item { Text(stringResource(R.string.upload_no_files)) }
        available.forEach { file -> item(key = file.id) {
            Button(enabled = !submitting, onClick = {
                if (multiple) {
                    if (file.id in selected) selected = selected - file.id
                    else if (selected.size < SafUploadSupport.MAX_FILES) selected = selected + file.id
                } else {
                    submitting = true
                    scope.launch {
                        val uri = manager?.uploadUri(file.id)
                        withContext(Dispatchers.Main.immediate) {
                            submitting = false
                            if (controller.pendingPrompt.value === current) {
                                if (uri != null) controller.confirmFiles(listOf(uri)) else invalid = true
                            }
                        }
                    }
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text((if (file.id in selected) "✓ " else "") + file.fileName) }
        } }
        if (multiple) item { Action(R.string.confirm_selection, selected.isNotEmpty() && !submitting) {
            val ids = selected.toList()
            submitting = true
            scope.launch {
                val uris = ids.mapNotNull { manager?.uploadUri(it) }
                withContext(Dispatchers.Main.immediate) {
                    submitting = false
                    if (controller.pendingPrompt.value === current) {
                        if (uris.size == ids.size) controller.confirmFiles(uris) else invalid = true
                    }
                }
            }
        } }
        if (submitting) item { Text(stringResource(R.string.upload_preparing)) }
        if (invalid) item { Text(stringResource(R.string.upload_read_failed)) }
        item { Action(R.string.b1_cancel) { controller.dismissPrompt() } }
    }
}

private fun uploadMatches(file: DownloadItem, accepted: Array<String>?): Boolean {
    if (accepted.isNullOrEmpty()) return true
    return accepted.any { raw ->
        val type = raw.lowercase().trim()
        type == "*/*" || type == file.mimeType || (type.endsWith("/*") && file.mimeType.startsWith(type.substringBefore('/') + "/")) ||
            (type.startsWith('.') && file.fileName.lowercase().endsWith(type))
    }
}

@Composable
internal fun ProductValuePrompt(controller: TabSessionController, current: EnginePrompt) {
    val isColor = current is EnginePrompt.Color
    val initial = when (current) { is EnginePrompt.Color -> current.prompt.defaultValue; is EnginePrompt.DateTime -> current.prompt.defaultValue; else -> "" }.orEmpty()
    var value by remember(current) { mutableStateOf(initial) }
    var invalid by remember(current) { mutableStateOf(false) }
    fun confirm() { invalid = !(if (isColor) controller.confirmColor(value) else controller.confirmDateTime(value)) }
    // A separate native window preserves the HTML control's focus while typing.
    Dialog(onDismissRequest = controller::dismissPrompt, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
      Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
       RoundList {
        item { PageTitle(stringResource(if (isColor) R.string.color_value else R.string.date_value)) }
        item { Text(current.origin, style = MaterialTheme.typography.bodySmall) }
        item { ProductTextField(value, { value = it.take(64); invalid = false }, if (current is EnginePrompt.DateTime) FormValues.hint(current.prompt.type) else "#RRGGBB", ::confirm) }
        if (current is EnginePrompt.Color) {
            (current.prompt.predefinedValues.orEmpty().toList() + listOf("#000000", "#ffffff", "#ff0000", "#00ff00", "#0000ff")).filter(FormValues::validColor).distinct().take(20).forEach { color ->
                item { Button(onClick = { value = color }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(color) } }
            }
        }
        if (invalid) item { Text(stringResource(R.string.invalid_value)) }
        item { Action(R.string.b1_ok, onClick = ::confirm) }
        item { Action(R.string.b1_cancel) { controller.dismissPrompt() } }
       }
      }
    }
}

@Composable
internal fun ProductContextActions(controller: TabSessionController, onOpen: (String, Boolean) -> Unit) {
    val tools = controller.pageTools
    val selection by tools.selectionState.collectAsState()
    val target by tools.contextState.collectAsState()
    if (selection == null && target == null) return
    BackHandler { tools.dismissSelection(); tools.dismissContext() }
    RoundList {
        item { PageTitle(stringResource(R.string.context_menu)) }
        selection?.let { current ->
            if (current.preview.isNotEmpty()) item { Text(current.preview, style = MaterialTheme.typography.bodySmall) }
            if (current.canCopy) item { Action(R.string.copy_text) { tools.performSelection(PageTools.SelectionAction.COPY) } }
            if (current.canCut) item { Action(R.string.cut_text) { tools.performSelection(PageTools.SelectionAction.CUT) } }
            if (current.canPaste) item { Action(R.string.paste_text) { tools.performSelection(PageTools.SelectionAction.PASTE) } }
            if (current.canSelectAll) item { Action(R.string.select_all_text) { tools.performSelection(PageTools.SelectionAction.SELECT_ALL) } }
        }
        target?.let { current ->
            item { Text(current.linkText.ifEmpty { current.title }, style = MaterialTheme.typography.bodySmall) }
            current.linkUrl?.let { link ->
                item { Text(link, style = MaterialTheme.typography.bodySmall) }
                item { Action(R.string.open_link) { tools.dismissContext(); onOpen(link, false) } }
                item { Action(R.string.open_link_tab) { tools.dismissContext(); onOpen(link, true) } }
                item { Action(R.string.copy_link) { tools.copyContextLink("URL") } }
            }
            if (current.imageUrl != null) item { Action(R.string.copy_image_address) { tools.copyContextImageUrl("URL") } }
        }
        item { Action(R.string.b1_cancel) { tools.dismissSelection(); tools.dismissContext() } }
    }
}

@Composable
internal fun ProductAboutScreen(onBack: () -> Unit) {
    var showingNotices by rememberSaveable { mutableStateOf(false) }
    if (showingNotices) {
        OpenSourceNoticesScreen { showingNotices = false }
        return
    }
    RoundList {
        item { PageTitle(stringResource(R.string.app_name)) }
        item { Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME)) }
        item { Text(stringResource(R.string.about_local)) }
        item { Text(stringResource(R.string.about_private)) }
        item { Text(stringResource(R.string.about_limits)) }
        item { PageTitle(stringResource(R.string.about_licenses)) }
        item { Text(stringResource(R.string.about_gecko)) }
        item { Text(stringResource(R.string.about_androidx)) }
        item { Action(R.string.about_licenses) { showingNotices = true } }
        item { Action(R.string.settings, onClick = onBack) }
    }
}

@Composable
private fun ProductTextField(value: String, onChange: (String) -> Unit, hint: String, onDone: () -> Unit) {
    BasicTextField(value = value, onValueChange = onChange, textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { onDone() }),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small).padding(12.dp),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
}
