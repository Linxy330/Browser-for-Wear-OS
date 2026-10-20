package com.kenny.browserforwearos.data.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface DownloadResponseFetcher {
    /** Must return a new GET response; never reuse or serialize a POST request/body. */
    suspend fun fetch(url: String): WebResponse
}

/** Uses the same Gecko runtime/profile as browser sessions, retaining normal Cookie policy. */
class GeckoDownloadFetcher(runtime: GeckoRuntime) : DownloadResponseFetcher {
    private val executor = GeckoWebExecutor(runtime)

    override suspend fun fetch(url: String): WebResponse {
        val safe = DownloadPolicy.sourceUrl(url) ?: throw DownloadFailure(DownloadError.IO_ERROR)
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val delivered = AtomicReference<WebResponse?>(null)
                val request = WebRequest.Builder(safe).method("GET").cacheMode(WebRequest.CACHE_MODE_NO_STORE).build()
                val result = executor.fetch(request, GeckoWebExecutor.FETCH_FLAGS_NONE)
                result.accept({ response ->
                    if (response == null) {
                        if (continuation.isActive) continuation.resumeWithException(DownloadFailure(DownloadError.NO_BODY))
                        return@accept
                    }
                    delivered.set(response)
                    if (continuation.isActive) continuation.resume(response)
                    else runCatching { response.body?.close() }
                }, { error ->
                    if (continuation.isActive) continuation.resumeWithException(error ?: DownloadFailure(DownloadError.IO_ERROR))
                })
                continuation.invokeOnCancellation {
                    runCatching { result.cancel() }
                    runCatching { delivered.getAndSet(null)?.body?.close() }
                }
            }
        }
    }
}

internal class DownloadFailure(val reason: DownloadError) : IOException(reason.name)

/** Pure bounded streaming loop; the body is never loaded into a byte-array/string in full. */
internal object DownloadCopier {
    fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        expectedBytes: Long?,
        maximumDurationMs: Long,
        checkCancellation: () -> Unit,
        reserveBytes: (Int) -> Unit = {},
        progress: (Long) -> Unit = {},
        clockNanos: () -> Long = System::nanoTime,
    ): Long {
        if (expectedBytes != null && expectedBytes > maxBytes) throw DownloadFailure(DownloadError.SIZE_LIMIT)
        val start = clockNanos()
        val buffer = ByteArray(32 * 1024)
        var written = 0L
        var emptyReads = 0
        while (true) {
            checkCancellation()
            if ((clockNanos() - start) / 1_000_000 >= maximumDurationMs) throw DownloadFailure(DownloadError.READ_TIMEOUT)
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                if (++emptyReads >= 8) throw DownloadFailure(DownloadError.IO_ERROR)
                continue
            }
            emptyReads = 0
            if (count.toLong() > maxBytes - written) throw DownloadFailure(DownloadError.SIZE_LIMIT)
            reserveBytes(count)
            output.write(buffer, 0, count)
            written += count
            progress(written)
        }
        checkCancellation()
        if (expectedBytes != null && written != expectedBytes) throw DownloadFailure(DownloadError.IO_ERROR)
        return written
    }
}
