package com.kenny.browserforwearos

import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kenny.browserforwearos.data.download.PreparedUpload
import com.kenny.browserforwearos.data.download.UploadStager
import com.kenny.browserforwearos.engine.EnginePrompt
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** A file input may be used before the current document's first native page stop. */
@RunWith(AndroidJUnit4::class)
class EmulatorUploadLeaseLifetimeTest : EmulatorProductFixture() {
    @Before
    fun requireTheDedicatedEmulator() {
        assertTrue("Upload lease regression refuses physical devices", Build.HARDWARE in setOf("ranchu", "goldfish"))
    }

    @Test
    fun currentDocumentFirstStopKeepsSelectedFileUntilLaterRealMultipartCompletes() {
        openModel()
        val controller = createController()
        val run = UUID.randomUUID().toString().replace("-", "")
        val target = "$base/product/upload-late?run=$run"
        val owned = File(app.filesDir, "downloads/files/upload-lease-test-$run")
        val original = File(owned, "fixture.bin")
        val bytes = ByteArray(8192) { ((it * 43 + 11) % 256).toByte() }
        var lease: PreparedUpload? = null
        try {
            assertTrue(owned.mkdirs())
            original.writeBytes(bytes)
            val content = FileProvider.getUriForFile(app, "${app.packageName}.downloads", original)
            onMain { controller.loadUri(target) }
            await("The form DOM must be usable while its real image response is held open") {
                controller.title.value == "PRODUCT_UPLOAD_LATE_READY" && controller.isLoading.value &&
                    control(run).getBoolean("started")
            }
            assertEquals("The first top-level load must still have no successful stop", 0L, controller.committedRevision.value)
            tapCentre()
            await("An actual file-input touch must produce a native pending file prompt") {
                controller.pendingPrompt.value is EnginePrompt.File
            }
            val prepared = runBlocking { UploadStager(app).prepare(listOf(content)) }
            assertNull(prepared.error)
            val selected = checkNotNull(prepared.lease)
            lease = selected
            val staged = selected.uris.map { File(checkNotNull(it.path)) }
            assertTrue(staged.all(File::isFile))
            onMain { assertTrue(controller.confirmFiles(selected.uris, selected)) }
            await("File selection must update this still-loading DOM without submitting it") {
                controller.title.value == "PRODUCT_UPLOAD_LATE_SELECTED" && controller.isLoading.value
            }
            assertEquals(0L, controller.committedRevision.value)
            assertNull(controller.pendingPrompt.value)

            // This is the first stop of the SAME document, not an uploaded form's
            // destination. Its selected files still belong to the current DOM.
            assertTrue(control(run, release = true).getBoolean("released"))
            await("Releasing the held resource must produce the first real successful page stop") {
                controller.title.value == "PRODUCT_UPLOAD_LATE_LOADED_SELECTED" &&
                    controller.committedUrl.value == target && controller.committedRevision.value > 0 && !controller.isLoading.value
            }
            val firstStopRevision = controller.committedRevision.value
            val observationEnd = SystemClock.elapsedRealtime() + 1_200
            while (SystemClock.elapsedRealtime() < observationEnd) {
                assertTrue("This document's first stop must not unlink its selected upload", staged.all(File::isFile))
                Thread.sleep(50)
            }
            assertArrayEquals(bytes, staged.single().readBytes())

            // Only now does a real DOM submit button send multipart bytes. The
            // server computes the title from actual received parts, not our hash.
            tapCentre()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val expectedTitle = "PRODUCT_UPLOADED_1_${bytes.size}_$hash"
            await("The later actual multipart POST must retain the selected file's original bytes", 35_000) {
                controller.title.value == expectedTitle && controller.committedUrl.value == "$base/product/upload" &&
                    controller.committedRevision.value > firstStopRevision && !controller.isLoading.value
            }
            assertNull(controller.error.value)
            await("A subsequent document's successful stop must release the former DOM's lease") {
                staged.none(File::exists)
            }
            assertArrayEquals("Lease cleanup must not delete the chosen provider source", bytes, original.readBytes())
        } finally {
            runCatching { control(run, release = true) }
            onMain { controller.close() } // Close native before an exceptional cleanup can unlink its DOM files.
            lease?.close()
            owned.deleteRecursively()
        }
    }

    /** Fixture control only. Browser navigation and POST always use the native engine. */
    private fun control(run: String, release: Boolean = false): JSONObject {
        val uri = URI("$base/product/upload-late-control?run=$run&release=${if (release) 1 else 0}")
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, if (uri.port < 0) 80 else uri.port), 3_000)
            socket.soTimeout = 3_000
            val request = "GET ${uri.rawPath}?${uri.rawQuery} HTTP/1.1\r\nHost: ${uri.host}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(response.startsWith("HTTP/1.1 200") || response.startsWith("HTTP/1.0 200")) { "Late-load fixture control request failed" }
            JSONObject(response.substringAfter("\r\n\r\n"))
        }
    }
}
