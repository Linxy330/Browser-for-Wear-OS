package com.kenny.browserforwearos.data

import java.util.UUID

data class Bookmark(val id: String, val url: String, val title: String)

data class HistoryEntry(val id: String, val url: String, val title: String, val visitedAt: Long)

/** A blank tab has no URL; an attempted or failed navigation is never a restore target. */
data class SavedTab(val id: String, val url: String? = null, val title: String = "")

data class BrowserSnapshot(
    val tabs: List<SavedTab> = emptyList(),
    val selectedTabId: String? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val searchEngine: SearchEngine = SearchEngine.BING,
)

/** Shared invariants apply both at the file boundary and on every user mutation. */
object BrowserDataRules {
    const val MAX_TABS = 3
    const val MAX_HISTORY = 200
    private const val MAX_TITLE_LENGTH = 512

    fun title(value: String, fallback: String = ""): String = value
        .filterNot { it.isISOControl() }.trim().take(MAX_TITLE_LENGTH).ifEmpty { fallback }

    fun sanitize(snapshot: BrowserSnapshot): BrowserSnapshot {
        val tabs = snapshot.tabs.filter { validId(it.id) }.distinctBy { it.id }.take(MAX_TABS).map { tab ->
            val url = tab.url?.let(AddressResolver::persistableUrl)
            tab.copy(url = url, title = if (url == null) "" else title(tab.title, url))
        }
        val bookmarks = snapshot.bookmarks.mapNotNull { bookmark ->
            if (!validId(bookmark.id)) return@mapNotNull null
            val url = AddressResolver.persistableUrl(bookmark.url) ?: return@mapNotNull null
            bookmark.copy(url = url, title = title(bookmark.title, url))
        }.distinctBy { it.id }.distinctBy { it.url }
        val history = snapshot.history.mapNotNull { entry ->
            if (!validId(entry.id) || entry.visitedAt < 0) return@mapNotNull null
            val url = AddressResolver.persistableUrl(entry.url) ?: return@mapNotNull null
            entry.copy(url = url, title = title(entry.title, url))
        }.sortedByDescending { it.visitedAt }.distinctBy { it.id }.distinctBy { it.url }.take(MAX_HISTORY)
        return snapshot.copy(
            tabs = tabs,
            selectedTabId = snapshot.selectedTabId?.takeIf { selected -> tabs.any { it.id == selected } } ?: tabs.firstOrNull()?.id,
            bookmarks = bookmarks,
            history = history,
        )
    }

    fun addBookmark(items: List<Bookmark>, url: String, pageTitle: String, id: String = UUID.randomUUID().toString()): List<Bookmark> {
        val safe = AddressResolver.persistableUrl(url) ?: return items
        if (items.any { it.url == safe }) return items
        return listOf(Bookmark(id, safe, title(pageTitle, safe))) + items
    }

    fun editBookmark(items: List<Bookmark>, id: String, pageTitle: String): List<Bookmark> = items.map {
        if (it.id == id) it.copy(title = title(pageTitle, it.url)) else it
    }

    fun recordHistory(items: List<HistoryEntry>, url: String, pageTitle: String, now: Long, id: String = UUID.randomUUID().toString()): List<HistoryEntry> {
        val safe = AddressResolver.persistableUrl(url) ?: return items
        val existingId = items.firstOrNull { it.url == safe }?.id ?: id
        return (listOf(HistoryEntry(existingId, safe, title(pageTitle, safe), now.coerceAtLeast(0))) + items.filterNot { it.url == safe }).take(MAX_HISTORY)
    }

    fun validId(id: String): Boolean = id.isNotBlank() && id.length <= 128 && id.none { it.isISOControl() }
}

/** Pure tab selection policy used by the UI model and unit tests. */
data class TabMetadataState(val tabs: List<SavedTab>, val selectedTabId: String) {
    fun add(id: String): TabMetadataState? {
        if (tabs.size >= BrowserDataRules.MAX_TABS || !BrowserDataRules.validId(id) || tabs.any { it.id == id }) return null
        return TabMetadataState(tabs + SavedTab(id), id)
    }

    fun select(id: String): TabMetadataState = if (tabs.any { it.id == id }) copy(selectedTabId = id) else this

    fun close(id: String, replacementId: String): TabMetadataState {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return this
        val remaining = tabs.filterNot { it.id == id }
        if (remaining.isEmpty()) return TabMetadataState(listOf(SavedTab(replacementId)), replacementId)
        val selected = if (selectedTabId == id) remaining[index.coerceAtMost(remaining.lastIndex)].id else selectedTabId
        return TabMetadataState(remaining, selected)
    }
}
