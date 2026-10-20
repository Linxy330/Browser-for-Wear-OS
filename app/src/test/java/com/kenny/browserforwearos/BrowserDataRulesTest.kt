package com.kenny.browserforwearos

import com.kenny.browserforwearos.data.AddressResolver
import com.kenny.browserforwearos.data.Bookmark
import com.kenny.browserforwearos.data.BrowserDataRules
import com.kenny.browserforwearos.data.BrowserSnapshot
import com.kenny.browserforwearos.data.HistoryEntry
import com.kenny.browserforwearos.data.SavedTab
import com.kenny.browserforwearos.data.SearchEngine
import com.kenny.browserforwearos.data.TabMetadataState
import org.junit.Assert.*
import org.junit.Test

class BrowserDataRulesTest {
    @Test fun `search encodes Chinese and punctuation and respects selected engine`() {
        assertEquals("https://www.bing.com/search?q=%E6%89%8B%E8%A1%A8+a%26b%3F", AddressResolver.resolve("手表 a&b?"))
        assertEquals("https://www.google.com/search?q=two+words", AddressResolver.resolve("two words", SearchEngine.GOOGLE))
        assertEquals("https://duckduckgo.com/?q=weather", AddressResolver.resolve("weather", SearchEngine.DUCKDUCKGO))
    }

    @Test fun `address validation rejects schemes credentials and malformed hosts`() {
        listOf("javascript:alert(1)", "intent://target", "file:///etc/passwd", "data:text/html,hi", "https://user:pass@example.com", "user:pass@example.com", "https://user%40example.com", "https://example.com:abc", "https://example.com:70000", "https://example..com", "example..com", "999.1.1.1", "http:///example.com", "https://example.com/a b", "https://example.com\\evil", "a\nb").forEach {
            assertNull("must reject $it", AddressResolver.resolve(it))
        }
    }

    @Test fun `hosts ports IPv6 unicode and explicit http are accepted`() {
        assertEquals("http://localhost:8787/path?a=1&b=2", AddressResolver.resolve("http://localhost:8787/path?a=1&b=2"))
        assertEquals("https://[::1]:8787/path", AddressResolver.resolve("[::1]:8787/path"))
        assertEquals("https://xn--fsqu00a.xn--0zwm56d/%E4%B8%AD%E6%96%87", AddressResolver.resolve("https://例子.测试/中文"))
        assertEquals("http://http/page", AddressResolver.resolve("http://http/page"))
        assertEquals("https://example.com/a?b=c#d", AddressResolver.resolve("example.com/a?b=c#d"))
    }

    @Test fun `credential parameters never enter profile metadata`() {
        listOf("https://example.com/?password=private", "https://example.com/?access_token=private", "https://example.com/#token=private", "https://example.com/?%70assword=private", "https://example.com/?sessionid=private").forEach {
            assertNotNull(AddressResolver.httpUrl(it))
            assertNull(AddressResolver.persistableUrl(it))
        }
        assertEquals("https://example.com/?q=watch", AddressResolver.persistableUrl("https://example.com/?q=watch"))
    }

    @Test fun `bookmark duplicate keeps identity and edited title`() {
        val added = BrowserDataRules.addBookmark(emptyList(), "https://example.com", "Example", "one")
        assertEquals(added, BrowserDataRules.addBookmark(added, "https://example.com", "Other title", "two"))
        assertEquals(Bookmark("one", "https://example.com", "Renamed"), BrowserDataRules.editBookmark(added, "one", "Renamed").single())
        assertEquals(emptyList<Bookmark>(), BrowserDataRules.addBookmark(emptyList(), "file:///private", "Bad"))
    }

    @Test fun `history deduplicates moves latest first keeps identity and caps at 200`() {
        var history = emptyList<HistoryEntry>()
        repeat(205) { history = BrowserDataRules.recordHistory(history, "https://example.com/$it", "Page $it", it.toLong(), "id$it") }
        assertEquals(200, history.size)
        assertEquals("https://example.com/204", history.first().url)
        assertFalse(history.any { it.url == "https://example.com/4" })
        val revisited = BrowserDataRules.recordHistory(history, "https://example.com/10", "New title", 999, "ignored")
        assertEquals(200, revisited.size)
        assertEquals(HistoryEntry("id10", "https://example.com/10", "New title", 999), revisited.first())
        assertEquals(1, revisited.count { it.url == "https://example.com/10" })
    }

    @Test fun `sanitization preserves blank tab ordering and isolates unsafe URLs`() {
        val source = BrowserSnapshot(
            tabs = listOf(SavedTab("a", "https://a.com", "A"), SavedTab("b", "about:blank", "Unsafe"), SavedTab("c"), SavedTab("d", "https://d.com")),
            selectedTabId = "missing",
            bookmarks = listOf(Bookmark("bad", "https://user:pass@a.com", "Bad"), Bookmark("good", "https://a.com", "  A\n  ")),
            history = listOf(HistoryEntry("old", "https://a.com", "Old", 1), HistoryEntry("new", "https://a.com", "New", 2)),
        )
        val safe = BrowserDataRules.sanitize(source)
        assertEquals(listOf("a", "b", "c"), safe.tabs.map { it.id })
        assertNull(safe.tabs[1].url)
        assertEquals("", safe.tabs[1].title)
        assertEquals("a", safe.selectedTabId)
        assertEquals(listOf(Bookmark("good", "https://a.com", "A")), safe.bookmarks)
        assertEquals("new", safe.history.single().id)
    }

    @Test fun `tab state limits at three and invalid selections never lose existing tabs`() {
        val state = TabMetadataState(listOf(SavedTab("one")), "one").add("two")!!.add("three")!!
        assertNull(state.add("four"))
        assertNull(state.add("two"))
        assertEquals(state, state.select("missing"))
        assertEquals("one", state.select("one").selectedTabId)
        assertEquals(listOf("one", "two", "three"), state.tabs.map { it.id })
    }

    @Test fun `closing a tab picks neighbor preserves other identities and last closes to blank`() {
        val state = TabMetadataState(listOf(SavedTab("one"), SavedTab("two"), SavedTab("three")), "two")
        val closed = state.close("two", "unused")
        assertEquals(listOf("one", "three"), closed.tabs.map { it.id })
        assertEquals("three", closed.selectedTabId)
        assertEquals("two", state.close("one", "unused").selectedTabId)
        assertEquals(state, state.close("missing", "unused"))
        val blank = TabMetadataState(listOf(SavedTab("last", "https://a.com")), "last").close("last", "replacement")
        assertEquals(listOf(SavedTab("replacement")), blank.tabs)
        assertEquals("replacement", blank.selectedTabId)
    }
}
