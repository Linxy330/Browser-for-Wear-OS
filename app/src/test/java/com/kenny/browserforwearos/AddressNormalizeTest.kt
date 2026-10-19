package com.kenny.browserforwearos

import com.kenny.browserforwearos.presentation.BrowserViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B1 临时地址判别规则的钉住测试；B2 换正式规则时更新此表。 */
class AddressNormalizeTest {

    @Test
    fun `explicit https kept as-is`() {
        assertEquals("https://example.com", BrowserViewModel.normalizeInput("https://example.com"))
    }

    @Test
    fun `explicit http kept as-is`() {
        assertEquals("http://example.com", BrowserViewModel.normalizeInput("http://example.com"))
    }

    @Test
    fun `bare domain gets https scheme`() {
        assertEquals("https://example.com", BrowserViewModel.normalizeInput("example.com"))
        assertEquals("https://192.168.1.5:8787/counter", BrowserViewModel.normalizeInput("192.168.1.5:8787/counter"))
    }

    @Test
    fun `whitespace trimmed`() {
        assertEquals("https://example.com", BrowserViewModel.normalizeInput("  example.com  "))
    }

    @Test
    fun `blank input rejected`() {
        assertNull(BrowserViewModel.normalizeInput(""))
        assertNull(BrowserViewModel.normalizeInput("   "))
    }
}
