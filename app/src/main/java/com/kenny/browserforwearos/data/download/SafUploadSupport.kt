package com.kenny.browserforwearos.data.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import java.util.Locale

/** Only the picker grants access; no storage permission or persistent grant is requested. */
class SafUploadSupport(context: Context) {
    private val app = context.applicationContext

    fun createOpenIntent(mimeTypes: Array<String>?, multiple: Boolean): Intent? {
        val types = acceptedMimeTypes(mimeTypes)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(types.singleOrNull() ?: "*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (types.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
        return intent.takeIf { it.resolveActivity(app.packageManager) != null }
    }

    /** Null/empty means user cancellation. Only user-selected content: URIs reach Gecko. */
    fun selectedUris(data: Intent?, multiple: Boolean): UploadSelection {
        if (data == null) return UploadSelection(emptyList())
        val clip = data.clipData
        val count = clip?.itemCount ?: if (data.data != null) 1 else 0
        if (count > MAX_FILES || (!multiple && count > 1)) return UploadSelection(emptyList(), UploadSelectionError.TOO_MANY_FILES)
        val uris: List<Uri?> = if (clip != null) (0 until count).map { clip.getItemAt(it).uri } else listOfNotNull(data.data)
        if (uris.any { it == null || it.scheme != "content" || it.authority.isNullOrBlank() }) return UploadSelection(emptyList(), UploadSelectionError.INVALID_URI)
        return UploadSelection(uris.filterNotNull().distinct())
    }

    private fun acceptedMimeTypes(raw: Array<String>?): List<String> {
        val valid = Regex("^[a-z0-9!#$&^_.+-]+/(?:[a-z0-9!#$&^_.+-]+|\\*)$")
        return raw.orEmpty().take(32).mapNotNull { original ->
            val value = original.trim().lowercase(Locale.ROOT)
            when {
                value == "*/*" -> value
                value.startsWith('.') -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(value.drop(1))
                valid.matches(value) -> value
                else -> null
            }
        }.distinct().ifEmpty { listOf("*/*") }
    }

    companion object { const val MAX_FILES = 10 }
}

enum class UploadSelectionError { TOO_MANY_FILES, INVALID_URI }
data class UploadSelection(val uris: List<Uri>, val error: UploadSelectionError? = null)
