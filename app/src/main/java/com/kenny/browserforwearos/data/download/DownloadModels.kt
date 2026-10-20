package com.kenny.browserforwearos.data.download

import com.kenny.browserforwearos.data.AddressResolver
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID

enum class DownloadStatus { WAITING, DOWNLOADING, COMPLETED, CANCELLED, FAILED }
enum class DownloadError { HTTP_ERROR, NO_BODY, SIZE_LIMIT, STORAGE_LIMIT, READ_TIMEOUT, INTERRUPTED, IO_ERROR, EXPIRED, BUSY, MISSING_FILE }
enum class DownloadEventType { OFFERED, STARTED, COMPLETED, CANCELLED, FAILED, DELETED, EXPORTED, EXPORT_FAILED, INDEX_RECOVERED, CAPACITY_REACHED }
data class DownloadEvent(val type: DownloadEventType, val id: String? = null, val error: DownloadError? = null)
enum class ExportResult { SUCCESS, CANCELLED, NOT_FOUND, INVALID_TARGET, FAILED }

data class DownloadItem(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val sourceHost: String,
    val totalBytes: Long?,
    val receivedBytes: Long,
    val status: DownloadStatus,
    val createdAt: Long,
    val error: DownloadError? = null,
    /** Only credential-filtered HTTP(S) URLs are retained. Retrying always issues a fresh GET. */
    val sourceUrl: String? = null,
) {
    val canRetry: Boolean get() = sourceUrl != null && status in setOf(DownloadStatus.CANCELLED, DownloadStatus.FAILED)
    val progress: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (receivedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}

data class DownloadLimits(
    val maxFileBytes: Long = 64L * 1024 * 1024,
    val maxStoredBytes: Long = 256L * 1024 * 1024,
    val minimumFreeBytes: Long = 32L * 1024 * 1024,
    val maxRecords: Int = 100,
    val maxConcurrent: Int = 2,
    val confirmationTimeoutMs: Long = 30_000,
    val readTimeoutMs: Long = 15_000,
    val fetchTimeoutMs: Long = 30_000,
    val transferTimeoutMs: Long = 5 * 60_000,
) {
    init {
        require(maxFileBytes > 0 && maxStoredBytes >= maxFileBytes && minimumFreeBytes >= 0)
        require(maxRecords in 1..500 && maxConcurrent in 1..4)
        require(confirmationTimeoutMs > 0 && readTimeoutMs > 0 && fetchTimeoutMs > 0 && transferTimeoutMs > 0)
    }
}

/** Pure trust boundary: website names are display text, never arbitrary local paths. */
object DownloadPolicy {
    private const val MAX_NAME_BYTES = 180
    private const val MAX_NAME_CHARS = 120
    private val mime = Regex("^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$")
    private val extension = Regex("\\.[A-Za-z0-9][A-Za-z0-9_+-]{0,15}")
    private val extendedName = Regex("(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
    private val ordinaryName = Regex("(?:^|;)\\s*filename\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^;]*))", RegexOption.IGNORE_CASE)

    fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    fun safeFileName(candidate: String?): String {
        val raw = candidate.orEmpty()
        val cleaned = buildString {
            var position = 0
            while (position < raw.length && position < 1024) {
                val codePoint = Character.codePointAt(raw, position)
                position += Character.charCount(codePoint)
                when {
                    codePoint in 0xd800..0xdfff || Character.isISOControl(codePoint) -> append('_')
                    codePoint <= 0xffff && codePoint.toChar() in "/\\:*?\"<>|" -> append('_')
                    codePoint in 0x202a..0x202e || codePoint in 0x2066..0x2069 || codePoint == 0x200b -> append('_')
                    else -> appendCodePoint(codePoint)
                }
            }
        }.trim().trimStart('.').trimEnd('.', ' ')
        if (cleaned.isBlank()) return "download.bin"
        if (cleaned.length <= MAX_NAME_CHARS && cleaned.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return cleaned

        // Linux NAME_MAX counts encoded bytes. Preserve an ordinary extension so
        // long CJK/emoji names remain usable by upload MIME and system exporters.
        val suffix = cleaned.substringAfterLast('.', "").let { ".$it" }.takeIf(extension::matches).orEmpty()
        val stem = cleaned.dropLast(suffix.length)
        var bytes = 0
        val shortened = buildString {
            var position = 0
            while (position < stem.length) {
                val codePoint = Character.codePointAt(stem, position)
                val chars = Character.charCount(codePoint)
                val encodedBytes = when {
                    codePoint <= 0x7f -> 1
                    codePoint <= 0x7ff -> 2
                    codePoint <= 0xffff -> 3
                    else -> 4
                }
                if (length + chars > MAX_NAME_CHARS - suffix.length || bytes + encodedBytes > MAX_NAME_BYTES - suffix.length) break
                appendCodePoint(codePoint)
                bytes += encodedBytes
                position += chars
            }
        }.trimEnd('.', ' ')
        return shortened.ifBlank { "download" } + suffix
    }

    fun fileName(headers: Map<String, String>, url: String): String {
        val disposition = header(headers, "content-disposition").orEmpty().take(8192)
        val extended = extendedName.find(disposition)?.groupValues?.get(1)?.trim()?.trim('"')
        val decoded = extended?.split('\'', limit = 3)?.takeIf { it.size == 3 && it[0].equals("utf-8", true) }?.let {
            runCatching { URLDecoder.decode(it[2].replace("+", "%2B"), Charsets.UTF_8.name()) }.getOrNull()
        }
        val ordinary = ordinaryName.find(disposition)?.let { match ->
            match.groupValues[1].ifEmpty { match.groupValues[2].trim() }.replace(Regex("\\\\(.)"), "$1")
        }
        val pathName = runCatching {
            val raw = URI(url.takeIf { it.length <= AddressResolver.MAX_URL_LENGTH }.orEmpty()).rawPath.orEmpty().substringAfterLast('/')
            URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrNull()
        return safeFileName(decoded?.takeIf { it.isNotBlank() } ?: ordinary?.takeIf { it.isNotBlank() } ?: pathName)
    }

    fun mimeType(value: String?): String = value.orEmpty().take(256).substringBefore(';').trim().lowercase(Locale.ROOT)
        .takeIf { mime.matches(it) } ?: "application/octet-stream"

    fun sourceUrl(url: String): String? = AddressResolver.persistableUrl(url)

    fun sourceHost(url: String): String = runCatching {
        if (url.length > AddressResolver.MAX_URL_LENGTH) return ""
        val candidate = if (url.startsWith("blob:")) url.removePrefix("blob:") else url
        URI(candidate).host.orEmpty().filterNot(Char::isISOControl).take(253)
    }.getOrDefault("")

    fun totalBytes(headers: Map<String, String>): Long? {
        if (header(headers, "content-encoding")?.let { !it.equals("identity", true) } == true) return null
        return header(headers, "content-length")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
    }

    fun header(headers: Map<String, String>, name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
}
