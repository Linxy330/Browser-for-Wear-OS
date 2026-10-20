package com.kenny.browserforwearos

import com.kenny.browserforwearos.data.download.DownloadCopier
import com.kenny.browserforwearos.data.download.DownloadError
import com.kenny.browserforwearos.data.download.DownloadFailure
import com.kenny.browserforwearos.data.download.DownloadPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException

class DownloadPolicyTest {
    @Test fun contentDispositionUsesUtf8AndKeepsLiteralPlus() {
        val headers = mapOf("Content-Disposition" to "attachment; filename=old.txt; filename*=UTF-8''%E4%B8%AD%E6%96%87+a.txt")
        assertEquals("中文+a.txt", DownloadPolicy.fileName(headers, "https://example.com/fallback"))
    }

    @Test fun untrustedNamesCannotEscapeDirectoryOrHideBidiExtension() {
        listOf("../../secret.txt", "/sdcard/payload", "..\\..\\secret", "\n../../evil\u202Etxt.exe").forEach { input ->
            val name = DownloadPolicy.safeFileName(input)
            assertFalse(name.contains('/'))
            assertFalse(name.contains('\\'))
            assertFalse(name.startsWith('.'))
            assertFalse(name.any { it.isISOControl() || it == '\u202e' })
        }
        assertEquals("download.bin", DownloadPolicy.safeFileName("..."))
        assertTrue(DownloadPolicy.safeFileName("a".repeat(1000)).length <= 120)
    }

    @Test fun filenameFallbackDecodesPathButNeverQuery() {
        assertEquals("report+1.pdf", DownloadPolicy.fileName(emptyMap(), "https://example.com/report%2B1.pdf?password=not-a-name"))
        assertEquals("download.bin", DownloadPolicy.fileName(emptyMap(), "https://example.com/"))
        assertEquals("a;b.txt", DownloadPolicy.fileName(mapOf("content-disposition" to "attachment; filename=\"a;b.txt\""), "https://example.com/other"))
    }

    @Test fun longChineseAndEmojiNamesFitEncodedByteLimitAndKeepExtension() {
        listOf("中文".repeat(100) + ".bin", "📦".repeat(100) + "-结果.bin", "a".repeat(119) + "📦.bin").forEach { input ->
            val name = DownloadPolicy.safeFileName(input)
            assertTrue("Filesystem limits count UTF-8 bytes", name.toByteArray(Charsets.UTF_8).size <= 180)
            assertTrue(name.length <= 120)
            assertTrue(name.endsWith(".bin"))
            assertEquals("Truncation must not split a surrogate pair", name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
        assertEquals("_name.bin", DownloadPolicy.safeFileName("\ud800name.bin"))
    }

    @Test fun knownCredentialsAndSpecialSchemesNeverBecomeRetryUrls() {
        listOf("https://u:p@example.com/a", "https://example.com/?token=x", "https://example.com/?password=x", "blob:https://example.com/123", "file:///private/file").forEach {
            assertNull(DownloadPolicy.sourceUrl(it))
        }
        assertEquals("https://example.com/a?document=2", DownloadPolicy.sourceUrl("https://example.com/a?document=2"))
    }

    @Test fun lengthAndMimeHeadersHaveConservativeFallbacks() {
        assertEquals(42L, DownloadPolicy.totalBytes(mapOf("Content-Length" to "42")))
        assertNull(DownloadPolicy.totalBytes(mapOf("content-length" to "-1")))
        assertNull(DownloadPolicy.totalBytes(mapOf("content-length" to "42", "content-encoding" to "gzip")))
        assertEquals("application/pdf", DownloadPolicy.mimeType("Application/PDF; charset=utf-8"))
        assertEquals("application/octet-stream", DownloadPolicy.mimeType("bad\r\nHeader: x"))
    }

    @Test fun streamingCopyPreservesExactBytesAndReportsProgress() {
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val count = DownloadCopier.copy(ByteArrayInputStream(bytes), output, 100_000, 100_000, 1000, {}, progress = { progress += it })
        assertEquals(100_000L, count)
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(100_000L, progress.last())
    }

    @Test fun unknownLengthBodyStillEnforcesFileLimitBeforeWritingOversizedChunk() {
        val output = ByteArrayOutputStream()
        val error = runCatching { DownloadCopier.copy(ByteArrayInputStream(ByteArray(20)), output, 10, null, 1000, {}) }.exceptionOrNull()
        assertEquals(DownloadError.SIZE_LIMIT, (error as DownloadFailure).reason)
        assertTrue(output.size() <= 10)
    }

    @Test fun truncationAndRepeatedEmptyReadsFailInsteadOfFakeCompletionOrSpin() {
        val truncated = runCatching { DownloadCopier.copy(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 10, 5, 1000, {}) }.exceptionOrNull()
        assertEquals(DownloadError.IO_ERROR, (truncated as DownloadFailure).reason)
        val emptyStream = object : InputStream() {
            override fun read(): Int = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
        }
        val empty = runCatching { DownloadCopier.copy(emptyStream, ByteArrayOutputStream(), 10, null, 1000, {}) }.exceptionOrNull()
        assertEquals(DownloadError.IO_ERROR, (empty as DownloadFailure).reason)
    }

    @Test fun deadlineAndCancellationAreCheckedBetweenReads() {
        var now = 0L
        val deadline = runCatching {
            DownloadCopier.copy(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), 10, null, 8, {}, clockNanos = { now += 5_000_000; now })
        }.exceptionOrNull()
        assertEquals(DownloadError.READ_TIMEOUT, (deadline as DownloadFailure).reason)
        val cancelled = runCatching {
            DownloadCopier.copy(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), 10, null, 1000, { throw InterruptedIOException() })
        }.exceptionOrNull()
        assertTrue(cancelled is InterruptedIOException)
    }

    @Test fun storageReservationCanRejectBeforeAWrite() {
        val output = ByteArrayOutputStream()
        val error = runCatching {
            DownloadCopier.copy(ByteArrayInputStream(ByteArray(10)), output, 10, null, 1000, {}, reserveBytes = { throw DownloadFailure(DownloadError.STORAGE_LIMIT) })
        }.exceptionOrNull()
        assertEquals(DownloadError.STORAGE_LIMIT, (error as DownloadFailure).reason)
        assertEquals(0, output.size())
    }
}
