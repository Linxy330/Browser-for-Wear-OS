package com.kenny.browserforwearos

import android.os.Looper
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Test-only first-page input barrier. Construct immediately after loadUri/its UI action in the
 * same main-thread turn, before Gecko can deliver navigation/paint callbacks for the new page.
 * All methods run on Main. No navigation, focus changes, input or production hooks are generated.
 */
internal class NativePaintBarrier(
    private val session: GeckoSession,
    private val expectedUrl: String,
) : AutoCloseable {
    private val originalContent = checkNotNull(session.contentDelegate)
    private val originalProgress = checkNotNull(session.progressDelegate)
    private var closed = false
    private var epoch = 0L
    private var documentStarted = false
    private var contentPainted = false
    private var captureView: GeckoView? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureRequested = false
    private var captured = false
    private var captureFailure: Throwable? = null

    private val content = object : ForwardingContentDelegate(originalContent) {
        override fun onFirstContentfulPaint(session: GeckoSession) {
            originalContent.onFirstContentfulPaint(session)
            if (!closed && session === this@NativePaintBarrier.session && documentStarted) {
                contentPainted = true
                invalidateCapture()
            }
        }

        override fun onPaintStatusReset(session: GeckoSession) {
            originalContent.onPaintStatusReset(session)
            if (!closed && session === this@NativePaintBarrier.session) {
                contentPainted = false
                invalidateCapture()
            }
        }

    }

    private val progress = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            originalProgress.onPageStart(session, url)
            if (!closed && session === this@NativePaintBarrier.session) {
                documentStarted = url == expectedUrl
                contentPainted = false
                invalidateCapture()
            }
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) = originalProgress.onPageStop(session, success)

        override fun onProgressChange(session: GeckoSession, progress: Int) =
            originalProgress.onProgressChange(session, progress)

        override fun onSecurityChange(
            session: GeckoSession, securityInfo: GeckoSession.ProgressDelegate.SecurityInformation,
        ) = originalProgress.onSecurityChange(session, securityInfo)

        override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) =
            originalProgress.onSessionStateChange(session, sessionState)
    }

    init {
        checkMain()
        session.contentDelegate = content
        session.progressDelegate = progress
    }

    /** Nonblocking: starts one native readback for this painted viewport; a later poll can pass. */
    fun isReadyForInput(view: GeckoView?): Boolean {
        checkMain()
        if (closed || !documentStarted || !contentPainted || !session.isOpen || view == null ||
            view.session !== session || !view.isAttachedToWindow || !view.isShown ||
            view.width <= 0 || view.height <= 0 || !view.hasFocus() || !view.hasWindowFocus()) return false

        if (captureView !== view || captureWidth != view.width || captureHeight != view.height) {
            invalidateCapture()
            captureView = view
            captureWidth = view.width
            captureHeight = view.height
        }
        captureFailure?.let { throw AssertionError("Native compositor readback failed for $expectedUrl", it) }
        if (!captureRequested) {
            captureRequested = true
            val requestedEpoch = epoch
            val width = captureWidth
            val height = captureHeight
            // A first composite may contain only the clear color, and draw callbacks can be
            // removed when a display detaches. Read back the actual compositor after true FCP.
            view.capturePixels().accept({ bitmap ->
                try {
                    if (ownsCapture(requestedEpoch, view)) {
                        if (view.width != width || view.height != height) {
                            // A real viewport resize starts a new readback on the next poll.
                            captured = false
                        } else if (bitmap == null || bitmap.width != width || bitmap.height != height) {
                            captureFailure = AssertionError("Native readback must match its measured viewport")
                        } else {
                            captured = true
                        }
                    }
                } finally {
                    bitmap?.recycle()
                }
            }, { failure ->
                if (ownsCapture(requestedEpoch, view)) {
                    captureFailure = failure ?: AssertionError("Native readback failed without a cause")
                }
            })
        }
        return captured
    }

    /** Read-only diagnostic; does not request a frame, change focus or advance this barrier. */
    fun diagnostic(view: GeckoView?): String {
        checkMain()
        return JSONObject()
            .put("closed", closed)
            .put("epoch", epoch)
            .put("docStarted", documentStarted)
            .put("contentPainted", contentPainted)
            .put("captureRequested", captureRequested)
            .put("captured", captured)
            .put("captureFailure", captureFailure?.javaClass?.simpleName ?: JSONObject.NULL)
            .put("nativeOpen", session.isOpen)
            .put("ownsContentDelegate", session.contentDelegate === content)
            .put("ownsProgressDelegate", session.progressDelegate === progress)
            .put("viewPresent", view != null)
            .put("sameNative", view?.session === session)
            .put("sameCaptureView", view != null && captureView === view)
            .put("attached", view?.isAttachedToWindow == true)
            .put("shown", view?.isShown == true)
            .put("focus", view?.hasFocus() == true)
            .put("windowFocus", view?.hasWindowFocus() == true)
            .put("width", view?.width ?: 0)
            .put("height", view?.height ?: 0)
            .put("windowWidth", view?.rootView?.width ?: 0)
            .put("windowHeight", view?.rootView?.height ?: 0)
            .put("captureWidth", captureWidth)
            .put("captureHeight", captureHeight)
            .toString()
    }

    private fun ownsCapture(expectedEpoch: Long, view: GeckoView): Boolean =
        !closed && epoch == expectedEpoch && captureView === view && view.session === session

    private fun invalidateCapture() {
        epoch++
        captureRequested = false
        captured = false
        captureFailure = null
    }

    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        invalidateCapture()
        captureView = null
        if (session.contentDelegate === content) session.contentDelegate = originalContent
        if (session.progressDelegate === progress) session.progressDelegate = originalProgress
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "NativePaintBarrier requires Main" }
    }
}
