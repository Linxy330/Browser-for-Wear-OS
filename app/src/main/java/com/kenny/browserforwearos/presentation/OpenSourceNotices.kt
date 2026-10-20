package com.kenny.browserforwearos.presentation

import android.content.res.AssetManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.kenny.browserforwearos.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Offline assets only: opening a notice never opens a Gecko session, URL or history entry. */
@Composable
internal fun OpenSourceNoticesScreen(onBack: () -> Unit) {
    val assets = LocalContext.current.assets
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    val labels = if (chinese) ChineseNoticeLabels else EnglishNoticeLabels
    val state by produceState(NoticeLoad(), assets) {
        value = withContext(Dispatchers.IO) {
            runCatching { NoticeLoad(groups = readNotices(assets), loaded = true) }
                .getOrElse { NoticeLoad(loaded = true, failed = true) }
        }
    }
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    var documentIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val group = state.groups.firstOrNull { it.id == groupId }
    val document = documentIndex?.let { group?.documents?.getOrNull(it) }
    fun back() {
        when {
            documentIndex != null -> documentIndex = null
            groupId != null -> groupId = null
            else -> onBack()
        }
    }
    BackHandler(onBack = ::back)
    val paragraphs = remember(document) { document?.paragraphs.orEmpty().flatMap(::noticeChunks) }
    key(groupId, documentIndex) {
        RoundList {
            item { PageTitle(document?.title ?: group?.let { if (chinese) it.titleZh else it.titleEn } ?: stringResource(R.string.about_licenses)) }
            when {
                !state.loaded -> item { Text(labels.loading) }
                state.failed -> item { Text(labels.failed) }
                group == null -> {
                    item { Text(labels.offline, style = MaterialTheme.typography.bodySmall) }
                    state.groups.forEach { entry -> item(key = entry.id) {
                        NoticeButton(if (chinese) entry.titleZh else entry.titleEn) { groupId = entry.id; documentIndex = null }
                    } }
                }
                document == null -> {
                    item { Text(labels.originalLanguage, style = MaterialTheme.typography.bodySmall) }
                    item { PageTitle(labels.texts) }
                    group.documents.forEachIndexed { index, entry -> item(key = "document-$index") {
                        NoticeButton(entry.title) { documentIndex = index }
                    } }
                    item { PageTitle(labels.sources) }
                    group.sources.forEachIndexed { index, url -> item(key = "source-$index") { NoticeParagraph(url) } }
                    item { PageTitle(labels.components) }
                    group.components.forEachIndexed { index, component -> item(key = "component-$index") { NoticeParagraph(component) } }
                }
                else -> {
                    paragraphs.forEachIndexed { index, paragraph -> item(key = "paragraph-$index") { NoticeParagraph(paragraph) } }
                    if (document.sources.isNotEmpty()) {
                        item { PageTitle(labels.sources) }
                        document.sources.forEachIndexed { index, url -> item(key = "source-$index") { NoticeParagraph(url) } }
                    }
                }
            }
            item { NoticeButton(if (groupId == null) labels.about else labels.back, ::back) }
        }
    }
}

@Composable
private fun NoticeButton(title: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(title, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NoticeParagraph(text: String) {
    SelectionContainer { Text(text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium) }
}

private data class NoticeLoad(val groups: List<NoticeGroup> = emptyList(), val loaded: Boolean = false, val failed: Boolean = false)
private data class NoticeGroup(val id: String, val titleEn: String, val titleZh: String, val components: List<String>,
    val sources: List<String>, val documents: List<NoticeDocument>)
private data class NoticeDocument(val title: String, val paragraphs: List<String>, val sources: List<String>)

private fun readNotices(assets: AssetManager): List<NoticeGroup> {
    val catalog = JSONObject(assets.open("notices/catalog.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
    require(catalog.getInt("version") == 1)
    val groups = catalog.getJSONArray("groups")
    return (0 until groups.length()).map { position ->
        val metadata = groups.getJSONObject(position)
        val name = metadata.getString("asset")
        require(Regex("notices/[a-z0-9_-]+\\.json").matches(name))
        val data = JSONObject(assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() })
        val documents = data.getJSONArray("documents")
        NoticeGroup(metadata.getString("id"), metadata.getString("titleEn"), metadata.getString("titleZh"),
            data.getJSONArray("components").strings(), data.getJSONArray("sources").strings(),
            (0 until documents.length()).map { index ->
                val document = documents.getJSONObject(index)
                NoticeDocument(document.getString("title"), document.getJSONArray("paragraphs").strings(),
                    document.optJSONArray("sources")?.strings().orEmpty())
            })
    }
}

private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

/** Bound individual text layouts without splitting a UTF-16 surrogate pair. */
private fun noticeChunks(value: String): List<String> = buildList {
    var start = 0
    while (start < value.length) {
        var end = minOf(start + 1200, value.length)
        if (end < value.length && value[end - 1].isHighSurrogate()) end--
        add(value.substring(start, end))
        start = end
    }
}

private data class NoticeLabels(val loading: String, val failed: String, val offline: String, val originalLanguage: String,
    val texts: String, val sources: String, val components: String, val back: String, val about: String)

private val EnglishNoticeLabels = NoticeLabels("Loading notices…", "Could not open the offline notices.",
    "These license texts and notices are included in the app and can be read offline.",
    "License texts are reproduced in their original language. Text and source URLs can be selected and copied.",
    "License texts and notices", "Source code and provenance", "Resolved component versions", "Back", "Return to About")
private val ChineseNoticeLabels = NoticeLabels("正在读取许可…", "无法读取离线许可。",
    "许可与声明正文随应用提供，可在离线时阅读。", "许可保留原文。可长按选中并复制正文和来源网址。",
    "许可与声明正文", "源码与来源", "依赖组件版本", "返回", "返回关于浏览器")
