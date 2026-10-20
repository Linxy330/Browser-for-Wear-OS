package com.kenny.browserforwearos.engine

import android.content.Context
import android.text.InputType
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Exposes Gecko's actual editor state to Android's IME startup policy.
 *
 * GeckoView 158 supplies an InputConnection but inherits View.onCheckIsTextEditor() == false.
 * Wear's preventImeStartupUnlessTextEditor policy consequently unbinds that connection. Android
 * reads the editor flag before asking for the connection, so a changed native EditorInfo must be
 * reported by one subsequent restart. Only Gecko's own show request may display the keyboard.
 */
class ImeGeckoView(context: Context) : GeckoView(context) {
    @Volatile private var nativeEditor = false
    private var generation = 0L
    private var boundSession: GeckoSession? = null
    private var originalDelegate: GeckoSession.TextInputDelegate? = null
    private var bridgeDelegate: GeckoSession.TextInputDelegate? = null
    private var pendingRefresh: Runnable? = null
    private var pendingShow: Runnable? = null
    private var pendingDelivery: Runnable? = null

    override fun onCheckIsTextEditor(): Boolean = nativeEditor

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        // TYPE_NULL includes Gecko's disabled editor and inputmode="none" contracts.
        val editable = connection != null && outAttrs.inputType != InputType.TYPE_NULL
        if (nativeEditor != editable) {
            nativeEditor = editable
            if (!editable) cancelShow()
            // A show already queued by Gecko will perform this restart itself.
            if (pendingShow == null && pendingDelivery == null) scheduleEditorRefresh()
        }
        return connection
    }

    override fun setSession(session: GeckoSession) {
        if (this.session === session) return
        super.setSession(session)
        boundSession = session
        val original = session.textInput.delegate
        originalDelegate = original
        // Java default methods are not forwarded by Kotlin interface delegation (`by`).
        // Keep all six Gecko 158 methods explicit, including the IME state updates.
        val bridge = object : GeckoSession.TextInputDelegate {
            override fun restartInput(session: GeckoSession, reason: Int) {
                if (!owns(session)) return
                if (reason == GeckoSession.TextInputDelegate.RESTART_REASON_BLUR) {
                    nativeEditor = false
                    cancelPending()
                }
                original.restartInput(session, reason)
                if (reason == GeckoSession.TextInputDelegate.RESTART_REASON_BLUR) {
                    // Gecko may publish its disabled IME context after the blur restart. An IC
                    // created synchronously above can therefore still contain the old editor.
                    nativeEditor = false
                    cancelPending()
                }
            }

            override fun showSoftInput(session: GeckoSession) {
                if (owns(session)) scheduleShow(session, original)
            }

            override fun hideSoftInput(session: GeckoSession) {
                if (!owns(session)) return
                cancelShow()
                original.hideSoftInput(session)
            }

            override fun updateSelection(
                session: GeckoSession, selStart: Int, selEnd: Int,
                compositionStart: Int, compositionEnd: Int,
            ) {
                if (owns(session)) original.updateSelection(session, selStart, selEnd, compositionStart, compositionEnd)
            }

            override fun updateExtractedText(
                session: GeckoSession, request: ExtractedTextRequest, text: ExtractedText,
            ) {
                if (owns(session)) original.updateExtractedText(session, request, text)
            }

            override fun updateCursorAnchorInfo(session: GeckoSession, info: CursorAnchorInfo) {
                if (owns(session)) original.updateCursorAnchorInfo(session, info)
            }
        }
        bridgeDelegate = bridge
        session.textInput.setDelegate(bridge)
    }

    override fun releaseSession(): GeckoSession? {
        generation++
        cancelPending()
        nativeEditor = false
        val previous = boundSession
        if (previous != null && previous.textInput.delegate === bridgeDelegate) {
            previous.textInput.setDelegate(originalDelegate)
        }
        boundSession = null
        originalDelegate = null
        bridgeDelegate = null
        return super.releaseSession()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) {
            generation++
            cancelPending()
        }
        super.onWindowFocusChanged(hasWindowFocus)
    }

    override fun onDetachedFromWindow() {
        generation++
        cancelPending()
        super.onDetachedFromWindow()
    }

    private fun owns(candidate: GeckoSession): Boolean =
        boundSession === candidate && session === candidate && candidate.isOpen

    private fun canServe(candidate: GeckoSession, expectedGeneration: Long): Boolean =
        generation == expectedGeneration && owns(candidate) && isAttachedToWindow && isShown &&
            hasFocus() && hasWindowFocus()

    private fun scheduleEditorRefresh() {
        val candidate = boundSession ?: return
        pendingRefresh?.let(::removeCallbacks)
        val expectedGeneration = generation
        val refresh = Runnable {
            pendingRefresh = null
            if (canServe(candidate, expectedGeneration)) inputMethodManager()?.restartInput(this)
        }
        pendingRefresh = refresh
        post(refresh)
    }

    private fun scheduleShow(candidate: GeckoSession, original: GeckoSession.TextInputDelegate) {
        // onCreateInputConnection may synchronously ask Gecko to show again during our restart.
        if (pendingShow != null || pendingDelivery != null) return
        pendingRefresh?.let(::removeCallbacks)
        pendingRefresh = null
        val expectedGeneration = generation
        val show = Runnable {
            if (!nativeEditor || !canServe(candidate, expectedGeneration)) {
                pendingShow = null
                return@Runnable
            }
            // The previous request may have returned NO_EDITOR. Send the now-known native flag.
            inputMethodManager()?.restartInput(this)
            val deliver = Runnable {
                pendingDelivery = null
                if (nativeEditor && canServe(candidate, expectedGeneration)) {
                    original.showSoftInput(candidate)
                }
            }
            pendingDelivery = deliver
            pendingShow = null
            // Let the IME client and window target process the restart before showing.
            post(deliver)
        }
        pendingShow = show
        post(show)
    }

    private fun inputMethodManager(): InputMethodManager? =
        context.getSystemService(InputMethodManager::class.java)

    private fun cancelShow() {
        pendingShow?.let(::removeCallbacks)
        pendingDelivery?.let(::removeCallbacks)
        pendingShow = null
        pendingDelivery = null
    }

    private fun cancelPending() {
        pendingRefresh?.let(::removeCallbacks)
        pendingRefresh = null
        cancelShow()
    }
}
