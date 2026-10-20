package com.kenny.browserforwearos.data.download

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/** An insert-only transaction. The caller never supplies an existing public URI. */
internal class MediaStoreDownloadDestination(private val resolver: ContentResolver) {
    suspend fun writeNew(fileName: String, mimeType: String, write: suspend (OutputStream) -> Unit): ExportResult {
        var created: Uri? = null
        var delivered = false
        return try {
            val result = withContext(Dispatchers.IO) {
                coroutineContext.ensureActive()
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, DownloadPolicy.safeFileName(fileName))
                    put(MediaStore.MediaColumns.MIME_TYPE, DownloadPolicy.mimeType(mimeType))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: throw IOException("Could not create a public download")
                created = uri
                coroutineContext.ensureActive()
                val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Could not open a public download")
                output.use {
                    write(it)
                    coroutineContext.ensureActive()
                    it.flush()
                }
                coroutineContext.ensureActive()
                val published = resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                if (published != 1) throw IOException("Could not publish a public download")
                coroutineContext.ensureActive()
                ExportResult.SUCCESS
            }
            // withContext has prompt cancellation on dispatch back to the caller.
            // If delivery is cancelled even just after publication, finally still
            // removes only this newly inserted row rather than leaving an orphan.
            delivered = result == ExportResult.SUCCESS
            result
        } catch (_: CancellationException) {
            ExportResult.CANCELLED
        } catch (_: Exception) {
            ExportResult.FAILED
        } finally {
            if (!delivered) created?.let { uri ->
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { runCatching { resolver.delete(uri, null, null) } }
                }
            }
        }
    }

    companion object {
        val RELATIVE_PATH: String = Environment.DIRECTORY_DOWNLOADS + "/Wear Browser/"
    }
}
